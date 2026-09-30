package arc.util;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/** TeaVM replacement for Arc's optional sun.misc.Unsafe direct-buffer helper. */
public final class UnsafeBuffers{
    public static boolean failed;
    public static boolean initialized;

    private UnsafeBuffers(){
    }

    public static void checkInit(){
        initialized = true;
        failed = false;
    }

    public static void copy(Buffer src, int srcPos, Buffer dst, int dstPos, int length){
        ByteBuffer source = ((ByteBuffer)src).duplicate();
        ByteBuffer destination = ((ByteBuffer)dst).duplicate();
        source.clear().position(srcPos).limit(srcPos + length);
        destination.clear().position(dstPos);
        destination.put(source);
    }
}
