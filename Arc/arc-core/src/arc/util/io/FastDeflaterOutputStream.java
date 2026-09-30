package arc.util.io;

import arc.func.*;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;

public class FastDeflaterOutputStream extends FilterOutputStream{
    private static Func<OutputStream, OutputStream> streamFactory = DeflaterOutputStream::new;
    private final byte[] tmp = {0};

    /** Overrides the compression stream for runtimes whose JDK zip adapter is incomplete. */
    public static void setStreamFactory(Func<OutputStream, OutputStream> factory){
        streamFactory = factory;
    }

    public FastDeflaterOutputStream(OutputStream outputStream){
        super(streamFactory.get(outputStream));
    }

    @Override
    public void write(int var1) throws IOException{
        tmp[0] = (byte)(var1 & 255);
        this.write(tmp, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException{
        if(length == 0) return;
        out.write(bytes, offset, length);
    }
}
