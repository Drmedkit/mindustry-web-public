package arc.util;

import java.nio.ByteBuffer;

/** TeaVM replacement for Arc's optional Java-16 direct-buffer helper. */
public final class Java16Buffers{
    private Java16Buffers(){
    }

    public static void copy(ByteBuffer src, int srcPos, ByteBuffer dst, int dstPos, int length){
        ByteBuffer source = src.duplicate();
        ByteBuffer destination = dst.duplicate();
        source.clear().position(srcPos).limit(srcPos + length);
        destination.clear().position(dstPos);
        destination.put(source);
    }
}
