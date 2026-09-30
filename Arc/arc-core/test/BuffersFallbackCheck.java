import arc.util.*;

import java.nio.*;

/** Dependency-free JVM checks for {@link Buffers}' pure-Java fallback. */
public class BuffersFallbackCheck{
    public static void main(String[] args){
        check(!Buffers.isJavaBufferFallback(), "native/direct buffers must remain the default");
        check(Buffers.newByteBuffer(1).isDirect(), "default byte-buffer allocation must remain direct");

        Buffers.setJavaBufferFallback(true);
        try{
            checkAllocations();
            checkArrayCopies();
            checkBufferCopy();
            checkUnsafeLifecycle();
            System.out.println("BuffersFallbackCheck passed");
        }finally{
            Buffers.setJavaBufferFallback(false);
        }
    }

    private static void checkAllocations(){
        ByteBuffer bytes = Buffers.newByteBuffer(16);
        check(!bytes.isDirect(), "fallback byte buffer must use Java heap storage");
        check(bytes.order() == ByteOrder.nativeOrder(), "byte buffer must use native byte order");

        FloatBuffer floats = Buffers.newFloatBuffer(4);
        ShortBuffer shorts = Buffers.newShortBuffer(4);
        IntBuffer ints = Buffers.newIntBuffer(4);
        check(!floats.isDirect() && !shorts.isDirect() && !ints.isDirect(), "typed fallback buffers must use heap storage");
        check(floats.order() == ByteOrder.nativeOrder(), "float buffer must use native byte order");
        check(shorts.order() == ByteOrder.nativeOrder(), "short buffer must use native byte order");
        check(ints.order() == ByteOrder.nativeOrder(), "int buffer must use native byte order");
    }

    private static void checkArrayCopies(){
        ByteBuffer floatsAtZero = Buffers.newByteBuffer(16);
        floatsAtZero.position(7);
        floatsAtZero.limit(15);
        Buffers.copy(new float[]{-1f, 1.25f, -7.5f}, floatsAtZero, 2, 1);
        check(floatsAtZero.position() == 0, "zero-based float copy must reset position");
        check(floatsAtZero.limit() == 8, "zero-based float copy must set byte limit");
        equal(1.25f, floatsAtZero.getFloat(0), "first zero-based float");
        equal(-7.5f, floatsAtZero.getFloat(4), "second zero-based float");

        ByteBuffer bytes = Buffers.newByteBuffer(10);
        bytes.position(3);
        Buffers.copy(new byte[]{9, 10, 11, 12, 13}, 1, bytes, 3);
        check(bytes.position() == 3, "byte copy must preserve position");
        check(bytes.limit() == 6, "byte copy must set limit from position");
        check(bytes.get(3) == 10 && bytes.get(4) == 11 && bytes.get(5) == 12, "byte copy contents");

        ByteBuffer shorts = Buffers.newByteBuffer(12);
        shorts.position(2);
        Buffers.copy(new short[]{3, 0x1234, -7}, 1, shorts, 2);
        check(shorts.position() == 2, "short copy must preserve byte-buffer position");
        check(shorts.limit() == 6, "short copy must set byte-buffer limit");
        check(shorts.getShort(2) == 0x1234 && shorts.getShort(4) == -7, "short copy contents");

        IntBuffer ints = Buffers.newIntBuffer(4);
        ints.position(1);
        Buffers.copy(new int[]{8, 0x12345678, -9}, 1, ints, 2);
        check(ints.position() == 1, "int copy must preserve typed-buffer position");
        check(ints.limit() == 3, "int copy must set typed-buffer limit");
        check(ints.get(1) == 0x12345678 && ints.get(2) == -9, "int copy contents");

        ByteBuffer floats = Buffers.newByteBuffer(16);
        floats.position(4);
        floats.limit(15);
        Buffers.copy(new float[]{0f, 2.5f, 6.75f}, 1, 2, floats);
        check(floats.position() == 4 && floats.limit() == 15, "state-preserving float copy must preserve state");
        equal(2.5f, floats.getFloat(4), "first positioned float");
        equal(6.75f, floats.getFloat(8), "second positioned float");

        byte[] encoded = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0x76543210).array();
        IntBuffer typedDestination = Buffers.newIntBuffer(2);
        typedDestination.position(1);
        Buffers.copy(encoded, 0, typedDestination, encoded.length);
        check(typedDestination.position() == 1 && typedDestination.limit() == 2, "raw copy must preserve typed destination state");
        check(typedDestination.get(1) == 0x76543210, "raw bytes must retain native representation in a typed buffer");
    }

    private static void checkBufferCopy(){
        FloatBuffer source = Buffers.newFloatBuffer(3);
        source.put(0, -2f);
        source.put(1, 3.5f);
        source.put(2, 9.25f);
        source.position(1);
        source.limit(3);

        ByteBuffer destination = Buffers.newByteBuffer(16);
        destination.position(4);
        Buffers.copy(source, destination, 2);

        check(source.position() == 1 && source.limit() == 3, "buffer copy must preserve source state");
        check(destination.position() == 4 && destination.limit() == 12, "buffer copy must preserve destination position and set limit");
        equal(3.5f, destination.getFloat(4), "first buffer-copy float");
        equal(9.25f, destination.getFloat(8), "second buffer-copy float");
    }

    private static void checkUnsafeLifecycle(){
        int before = Buffers.getAllocatedBytesUnsafe();
        ByteBuffer allocated = Buffers.newUnsafeByteBuffer(12);
        check(!allocated.isDirect(), "fallback unsafe buffer must use Java heap storage");
        check(Buffers.isUnsafeByteBuffer(allocated), "fallback unsafe buffer must be registered");
        check(Buffers.getAllocatedBytesUnsafe() == before + 12, "unsafe byte count after allocation");

        boolean addressRejected = false;
        try{
            Buffers.getUnsafeBufferAddress(allocated);
        }catch(UnsupportedOperationException expected){
            addressRejected = true;
        }
        check(addressRejected, "fallback unsafe buffer must not expose a native address");

        Buffers.disposeUnsafeByteBuffer(allocated);
        check(!Buffers.isUnsafeByteBuffer(allocated), "disposed fallback buffer must be unregistered");
        check(Buffers.getAllocatedBytesUnsafe() == before, "unsafe byte count after disposal");

        ByteBuffer registered = Buffers.newUnsafeByteBuffer(ByteBuffer.allocate(5));
        check(Buffers.isUnsafeByteBuffer(registered), "registered Java buffer must be tracked");
        Buffers.disposeUnsafeByteBuffer(registered);
        check(Buffers.getAllocatedBytesUnsafe() == before, "registered Java buffer must dispose without JNI");

        boolean doubleDisposeRejected = false;
        try{
            Buffers.disposeUnsafeByteBuffer(registered);
        }catch(IllegalArgumentException expected){
            doubleDisposeRejected = true;
        }
        check(doubleDisposeRejected, "double disposal must remain rejected");
    }

    private static void equal(float expected, float actual, String message){
        check(Float.floatToIntBits(expected) == Float.floatToIntBits(actual), message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
