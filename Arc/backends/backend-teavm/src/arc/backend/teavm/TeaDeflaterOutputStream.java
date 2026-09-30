package arc.backend.teavm;

import com.jcraft.jzlib.Deflater;
import com.jcraft.jzlib.GZIPException;
import com.jcraft.jzlib.JZlib;

import java.io.IOException;
import java.io.OutputStream;

/** Synchronous zlib stream that bypasses TeaVM's incomplete JDK Deflater adapter. */
public final class TeaDeflaterOutputStream extends OutputStream{
    private static final int bufferSize = 16 * 1024;

    private final OutputStream output;
    private final Deflater deflater;
    private final byte[] compressed = new byte[bufferSize];
    private final byte[] single = new byte[1];
    private boolean closed;
    private boolean finished;

    public TeaDeflaterOutputStream(OutputStream output){
        this.output = output;
        try{
            deflater = new Deflater(JZlib.Z_DEFAULT_COMPRESSION);
        }catch(GZIPException error){
            throw new IllegalStateException("Could not initialize browser zlib compression.", error);
        }
    }

    @Override
    public void write(int value) throws IOException{
        single[0] = (byte)value;
        write(single, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException{
        ensureOpen();
        if(bytes == null) throw new NullPointerException("bytes");
        if(offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if(length == 0) return;

        deflater.setInput(bytes, offset, length, false);
        while(deflater.avail_in > 0){
            int before = deflater.avail_in;
            int written = pump(JZlib.Z_NO_FLUSH);
            if(written == 0 && deflater.avail_in == before){
                throw new IOException("Browser zlib compressor made no progress.");
            }
        }
    }

    private int pump(int flush) throws IOException{
        deflater.setOutput(compressed, 0, compressed.length);
        int result = deflater.deflate(flush);
        int written = compressed.length - deflater.avail_out;
        if(written > 0) output.write(compressed, 0, written);

        if(result == JZlib.Z_STREAM_END){
            finished = true;
        }else if(result != JZlib.Z_OK && result != JZlib.Z_BUF_ERROR){
            throw new IOException("Browser zlib compression failed: " + result +
                (deflater.msg == null ? "" : " (" + deflater.msg + ")"));
        }
        return written;
    }

    public void finish() throws IOException{
        ensureOpen();
        int stalled = 0;
        while(!finished){
            int written = pump(JZlib.Z_FINISH);
            if(written == 0){
                if(++stalled > 1) throw new IOException("Browser zlib compressor could not finish.");
            }else{
                stalled = 0;
            }
        }
    }

    @Override
    public void flush() throws IOException{
        ensureOpen();
        output.flush();
    }

    @Override
    public void close() throws IOException{
        if(closed) return;
        IOException failure = null;
        try{
            finish();
        }catch(IOException error){
            failure = error;
        }

        deflater.end();
        closed = true;
        try{
            output.close();
        }catch(IOException error){
            if(failure == null) failure = error;
        }
        if(failure != null) throw failure;
    }

    private void ensureOpen() throws IOException{
        if(closed) throw new IOException("Compression stream is closed.");
    }
}
