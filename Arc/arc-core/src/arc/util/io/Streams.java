package arc.util.io;

import arc.func.*;
import arc.util.*;

import java.io.*;
import java.nio.*;

/** Provides utility methods to copy streams. */
public final class Streams{
    public static final int defaultBufferSize = 8192;
    public static final byte[] emptyBytes = new byte[0];

    /**
     * Allocates a {@value #defaultBufferSize} byte[] for use as a temporary buffer and calls
     * {@link #copy(InputStream, OutputStream, byte[])}.
     */
    public static void copy(InputStream input, OutputStream output) throws IOException{
        copy(input, output, new byte[defaultBufferSize]);
    }

    /**
     * Allocates a byte[] of the specified size for use as a temporary buffer and calls
     * {@link #copy(InputStream, OutputStream, byte[])}.
     */
    public static void copy(InputStream input, OutputStream output, int bufferSize) throws IOException{
        copy(input, output, new byte[bufferSize]);
    }

    /**
     * Copy the data from an {@link InputStream} to an {@link OutputStream}, using the specified byte[] as a temporary buffer.
     * The stream is not closed.
     */
    public static void copy(InputStream input, OutputStream output, byte[] buffer) throws IOException{
        int bytesRead;
        while((bytesRead = input.read(buffer)) != -1){
            output.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Copy the data from an {@link InputStream} to an {@link OutputStream}, using the specified byte[] as a temporary buffer.
     * The stream is not closed.
     * Provides progress as a 0-1 value through the specified listener.
     * @param totalLength the total byte length of the input.
     */
    public static void copyProgress(InputStream input, OutputStream output, long totalLength, int bufferSize, Floatc progress) throws IOException{
        byte[] buffer = new byte[bufferSize];
        long totalRead = 0;
        int bytesRead;
        while((bytesRead = input.read(buffer)) != -1){
            totalRead += bytesRead;
            progress.get(totalRead / (float)totalLength);
            output.write(buffer, 0, bytesRead);
        }
    }

    /**
     * Allocates a {@value #defaultBufferSize} byte[] for use as a temporary buffer and calls
     * {@link #copy(InputStream, OutputStream, byte[])}.
     */
    public static void copy(InputStream input, ByteBuffer output) throws IOException{
        copy(input, output, new byte[defaultBufferSize]);
    }

    /**
     * Allocates a byte[] of the specified size for use as a temporary buffer and calls
     * {@link #copy(InputStream, ByteBuffer, byte[])}.
     */
    public static void copy(InputStream input, ByteBuffer output, int bufferSize) throws IOException{
        copy(input, output, new byte[bufferSize]);
    }

    /**
     * Copy the data from an {@link InputStream} to a {@link ByteBuffer}, using the specified byte[] as a temporary buffer. The
     * buffer's limit is increased by the number of bytes copied, the position is left unchanged. The stream is not closed.
     * @param output Must be a direct Buffer with native byte order and the buffer MUST be large enough to hold all the bytes in
     * the stream. No error checking is performed.
     * @return the number of bytes copied.
     */
    public static int copy(InputStream input, ByteBuffer output, byte[] buffer) throws IOException{
        int startPosition = output.position(), total = 0, bytesRead;
        while((bytesRead = input.read(buffer)) != -1){
            Buffers.copy(buffer, 0, output, bytesRead);
            total += bytesRead;
            output.position(startPosition + total);
        }
        output.position(startPosition);
        return total;
    }

    /** Copy the data from an {@link InputStream} to a byte array. The stream is not closed. */
    public static byte[] copyBytes(InputStream input) throws IOException{
        return copyBytes(input, input.available());
    }

    /**
     * Copy the data from an {@link InputStream} to a byte array. The stream is not closed.
     * @param estimatedSize Used to allocate the output byte[] to possibly avoid an array copy.
     */
    public static byte[] copyBytes(InputStream input, int estimatedSize) throws IOException{
        ByteArrayOutputStream baos = new OptimizedByteArrayOutputStream(Math.max(0, estimatedSize));
        copy(input, baos);
        return baos.toByteArray();
    }

    /**
     * Calls {@link #copyString(InputStream, int, String)} using the input's {@link InputStream#available() available} size
     * and the platform's default charset.
     */
    public static String copyString(InputStream input) throws IOException{
        return copyString(input, input.available(), null);
    }

    /** Calls {@link #copyString(InputStream, int, String)} using the platform's default charset. */
    public static String copyString(InputStream input, int estimatedSize) throws IOException{
        return copyString(input, estimatedSize, null);
    }

    /**
     * Copy the data from an {@link InputStream} to a string using the specified charset.
     * @param estimatedSize Used to allocate the output buffer to possibly avoid an array copy.
     * @param charset May be null to use the platform's default charset.
     */
    public static String copyString(InputStream input, int estimatedSize, String charset) throws IOException{
        InputStreamReader reader = new InputStreamReader(input, charset == null ? "UTF-8" : charset);
        StringWriter writer = new StringWriter(Math.max(0, estimatedSize));
        char[] buffer = new char[defaultBufferSize];
        int charsRead;
        while((charsRead = reader.read(buffer)) != -1){
            writer.write(buffer, 0, charsRead);
        }
        return writer.toString();
    }

    /** Close and ignore all errors. */
    public static void close(Closeable c){
        if(c != null){
            try{
                c.close();
            }catch(Throwable ignored){
            }
        }
    }

    /** @return sha256 hash of provided byte array */
    public static byte[] sha256(byte[] bytes){
        if(bytes == null) throw new IllegalArgumentException("bytes cannot be null");

        final int[] constants = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
        };
        int[] hash = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

        int paddedLength = ((bytes.length + 9 + 63) / 64) * 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        padded[bytes.length] = (byte)0x80;
        long bitLength = (long)bytes.length * 8L;
        for(int i = 0; i < 8; i++) padded[paddedLength - 1 - i] = (byte)(bitLength >>> (i * 8));

        int[] schedule = new int[64];
        for(int offset = 0; offset < padded.length; offset += 64){
            for(int i = 0; i < 16; i++){
                int p = offset + i * 4;
                schedule[i] = (padded[p] & 0xff) << 24 | (padded[p + 1] & 0xff) << 16 | (padded[p + 2] & 0xff) << 8 | padded[p + 3] & 0xff;
            }
            for(int i = 16; i < 64; i++){
                int x = schedule[i - 15], y = schedule[i - 2];
                int s0 = Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ x >>> 3;
                int s1 = Integer.rotateRight(y, 17) ^ Integer.rotateRight(y, 19) ^ y >>> 10;
                schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1;
            }

            int a = hash[0], b = hash[1], c = hash[2], d = hash[3];
            int e = hash[4], f = hash[5], g = hash[6], h = hash[7];
            for(int i = 0; i < 64; i++){
                int sum1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int choose = e & f ^ ~e & g;
                int temp1 = h + sum1 + choose + constants[i] + schedule[i];
                int sum0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int majority = a & b ^ a & c ^ b & c;
                int temp2 = sum0 + majority;
                h = g; g = f; f = e; e = d + temp1;
                d = c; c = b; b = a; a = temp1 + temp2;
            }
            hash[0] += a; hash[1] += b; hash[2] += c; hash[3] += d;
            hash[4] += e; hash[5] += f; hash[6] += g; hash[7] += h;
        }

        byte[] output = new byte[32];
        for(int i = 0; i < hash.length; i++){
            output[i * 4] = (byte)(hash[i] >>> 24);
            output[i * 4 + 1] = (byte)(hash[i] >>> 16);
            output[i * 4 + 2] = (byte)(hash[i] >>> 8);
            output[i * 4 + 3] = (byte)hash[i];
        }
        return output;
    }

    /** A ByteArrayOutputStream which avoids copying of the byte array if possible. */
    public static class OptimizedByteArrayOutputStream extends ByteArrayOutputStream{
        public OptimizedByteArrayOutputStream(int initialSize){
            super(initialSize);
        }

        @Override
        public synchronized byte[] toByteArray(){
            if(count == buf.length) return buf;
            return super.toByteArray();
        }

        public byte[] getBuffer(){
            return buf;
        }
    }
}
