package arc.util;

import arc.struct.*;

import java.nio.*;

/**
 * Class with static helper methods to increase the speed of array/direct buffer and direct buffer/direct buffer transfers
 * @author mzechner, xoppa
 */
public final class Buffers{
    static final Seq<ByteBuffer> unsafeBuffers = new Seq<>();
    static final Seq<ByteBuffer> javaUnsafeBuffers = new Seq<>();
    static int allocatedUnsafe = 0;
    private static volatile boolean javaBufferFallback;

    /**
     * Selects a pure-Java buffer implementation for platforms without direct buffers or JNI, such as a TeaVM browser target.
     * This must be set before allocating buffers. Native/direct behavior remains the default for existing backends.
     */
    public static void setJavaBufferFallback(boolean enabled){
        javaBufferFallback = enabled;
    }

    /** @return whether buffer allocation and copies use the pure-Java fallback. */
    public static boolean isJavaBufferFallback(){
        return javaBufferFallback;
    }

    /**
     * Copies numFloats floats from src starting at offset to dst. Dst is assumed to be a direct {@link Buffer}. The method will
     * crash if that is not the case. The position and limit of the buffer are ignored, the copy is placed at position 0 in the
     * buffer. After the copying process the position of the buffer is set to 0 and its limit is set to numFloats * 4 if it is a
     * ByteBuffer and numFloats if it is a FloatBuffer. In case the Buffer is neither a ByteBuffer nor a FloatBuffer the limit is
     * not set. This is an expert method, use at your own risk.
     * @param src the source array
     * @param dst the destination buffer, has to be a direct Buffer
     * @param numFloats the number of floats to copy
     * @param offset the offset in src to start copying from
     */
    public static void copy(float[] src, Buffer dst, int numFloats, int offset){
        if(dst instanceof ByteBuffer)
            dst.limit(numFloats << 2);
        else if(dst instanceof FloatBuffer) dst.limit(numFloats);

        if(javaBufferFallback){
            copyJava(src, offset, dst, 0, numFloats << 2);
        }else{
            copyJni(src, dst, numFloats, offset);
        }
        dst.position(0);
    }

    /**
     * Copies the contents of src to dst, starting from src[srcOffset], copying numElements elements. The {@link Buffer}
     * instance's {@link Buffer#position()} is used to define the offset into the Buffer itself. The position will stay the same,
     * the limit will be set to position + numElements. <b>The Buffer must be a direct Buffer with native byte order. No error
     * checking is performed</b>.
     * @param src the source array.
     * @param srcOffset the offset into the source array.
     * @param dst the destination Buffer, its position is used as an offset.
     * @param numElements the number of elements to copy.
     */
    public static void copy(byte[] src, int srcOffset, Buffer dst, int numElements){
        dst.limit(dst.position() + bytesToElements(dst, numElements));
        if(javaBufferFallback){
            copyJava(src, srcOffset, dst, positionInBytes(dst), numElements);
        }else{
            copyJni(src, srcOffset, dst, positionInBytes(dst), numElements);
        }
    }

    /**
     * Copies the contents of src to dst, starting from src[srcOffset], copying numElements elements. The {@link Buffer}
     * instance's {@link Buffer#position()} is used to define the offset into the Buffer itself. The position will stay the same,
     * the limit will be set to position + numElements. <b>The Buffer must be a direct Buffer with native byte order. No error
     * checking is performed</b>.
     * @param src the source array.
     * @param srcOffset the offset into the source array.
     * @param dst the destination Buffer, its position is used as an offset.
     * @param numElements the number of elements to copy.
     */
    public static void copy(short[] src, int srcOffset, Buffer dst, int numElements){
        dst.limit(dst.position() + bytesToElements(dst, numElements << 1));
        if(javaBufferFallback){
            copyJava(src, srcOffset, dst, positionInBytes(dst), numElements << 1);
        }else{
            copyJni(src, srcOffset, dst, positionInBytes(dst), numElements << 1);
        }
    }

    /**
     * Copies the contents of src to dst, starting from src[srcOffset], copying numElements elements. The {@link Buffer}
     * instance's {@link Buffer#position()} is used to define the offset into the Buffer itself. The position and limit will stay
     * the same. <b>The Buffer must be a direct Buffer with native byte order. No error checking is performed</b>.
     * @param src the source array.
     * @param srcOffset the offset into the source array.
     * @param numElements the number of elements to copy.
     * @param dst the destination Buffer, its position is used as an offset.
     */
    public static void copy(float[] src, int srcOffset, int numElements, Buffer dst){
        if(javaBufferFallback){
            copyJava(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }else{
            copyJni(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }
    }

    /**
     * Copies the contents of src to dst, starting from src[srcOffset], copying numElements elements. The {@link Buffer}
     * instance's {@link Buffer#position()} is used to define the offset into the Buffer itself. The position will stay the same,
     * the limit will be set to position + numElements. <b>The Buffer must be a direct Buffer with native byte order. No error
     * checking is performed</b>.
     * @param src the source array.
     * @param srcOffset the offset into the source array.
     * @param dst the destination Buffer, its position is used as an offset.
     * @param numElements the number of elements to copy.
     */
    public static void copy(int[] src, int srcOffset, Buffer dst, int numElements){
        dst.limit(dst.position() + bytesToElements(dst, numElements << 2));
        if(javaBufferFallback){
            copyJava(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }else{
            copyJni(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }
    }

    /**
     * Copies the contents of src to dst, starting from src[srcOffset], copying numElements elements. The {@link Buffer}
     * instance's {@link Buffer#position()} is used to define the offset into the Buffer itself. The position will stay the same,
     * the limit will be set to position + numElements. <b>The Buffer must be a direct Buffer with native byte order. No error
     * checking is performed</b>.
     * @param src the source array.
     * @param srcOffset the offset into the source array.
     * @param dst the destination Buffer, its position is used as an offset.
     * @param numElements the number of elements to copy.
     */
    public static void copy(float[] src, int srcOffset, Buffer dst, int numElements){
        dst.limit(dst.position() + bytesToElements(dst, numElements << 2));
        if(javaBufferFallback){
            copyJava(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }else{
            copyJni(src, srcOffset, dst, positionInBytes(dst), numElements << 2);
        }
    }

    /**
     * Copies the contents of src to dst, starting from the current position of src, copying numElements elements (using the data
     * type of src, no matter the datatype of dst). The dst {@link Buffer#position()} is used as the writing offset. The position
     * of both Buffers will stay the same. The limit of the src Buffer will stay the same. The limit of the dst Buffer will be set
     * to dst.position() + numElements, where numElements are translated to the number of elements appropriate for the dst Buffer
     * data type. <b>The Buffers must be direct Buffers with native byte order. No error checking is performed</b>.
     * @param src the source Buffer.
     * @param dst the destination Buffer.
     * @param numElements the number of elements to copy.
     */
    public static void copy(Buffer src, Buffer dst, int numElements){
        int numBytes = elementsToBytes(src, numElements);
        dst.limit(dst.position() + bytesToElements(dst, numBytes));
        if(javaBufferFallback){
            copyJava(src, positionInBytes(src), dst, positionInBytes(dst), numBytes);
        }else{
            copyJni(src, positionInBytes(src), dst, positionInBytes(dst), numBytes);
        }
    }

    private static int positionInBytes(Buffer dst){
        return dst.position() << elementShift(dst);
    }

    private static int bytesToElements(Buffer dst, int bytes){
        return bytes >>> elementShift(dst);
    }

    private static int elementsToBytes(Buffer dst, int elements){
        return elements << elementShift(dst);
    }

    private static int elementShift(Buffer dst){
        if(dst instanceof ByteBuffer)
            return 0;
        else if(dst instanceof ShortBuffer || dst instanceof CharBuffer)
            return 1;
        else if(dst instanceof IntBuffer)
            return 2;
        else if(dst instanceof LongBuffer)
            return 3;
        else if(dst instanceof FloatBuffer)
            return 2;
        else if(dst instanceof DoubleBuffer)
            return 3;
        else
            throw new ArcRuntimeException("Can't copy to a " + dst.getClass().getName() + " instance");
    }

    public static FloatBuffer newFloatBuffer(int numFloats){
        return newByteBuffer(numFloats * 4).asFloatBuffer();
    }

    public static ShortBuffer newShortBuffer(int numShorts){
        return newByteBuffer(numShorts * 2).asShortBuffer();
    }

    public static ByteBuffer newByteBuffer(int numBytes){
        ByteBuffer buffer = javaBufferFallback ? ByteBuffer.allocate(numBytes) : ByteBuffer.allocateDirect(numBytes);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    public static IntBuffer newIntBuffer(int numInts){
        return newByteBuffer(numInts * 4).asIntBuffer();
    }

    public static void disposeUnsafeByteBuffer(ByteBuffer buffer){
        int size = buffer.capacity();
        boolean javaManaged;
        synchronized(unsafeBuffers){
            if(!unsafeBuffers.remove(buffer, true))
                throw new IllegalArgumentException("buffer not allocated with newUnsafeByteBuffer or already disposed");
            javaManaged = javaUnsafeBuffers.remove(buffer, true);
        }
        allocatedUnsafe -= size;
        if(!javaManaged) freeMemory(buffer);
    }

    public static boolean isUnsafeByteBuffer(ByteBuffer buffer){
        synchronized(unsafeBuffers){
            return unsafeBuffers.contains(buffer, true);
        }
    }

    /**
     * Allocates a new direct ByteBuffer from native heap memory using the native byte order. Needs to be disposed with
     * {@link #disposeUnsafeByteBuffer(ByteBuffer)}.
     */
    public static ByteBuffer newUnsafeByteBuffer(int numBytes){
        boolean javaManaged = javaBufferFallback;
        ByteBuffer buffer = javaManaged ? ByteBuffer.allocate(numBytes) : newDisposableByteBuffer(numBytes);
        buffer.order(ByteOrder.nativeOrder());
        allocatedUnsafe += numBytes;
        synchronized(unsafeBuffers){
            unsafeBuffers.add(buffer);
            if(javaManaged) javaUnsafeBuffers.add(buffer);
        }
        return buffer;
    }

    /**
     * Returns the address of the Buffer, it assumes it is an unsafe buffer.
     * @param buffer The Buffer to ask the address for.
     * @return the address of the Buffer.
     */
    public static long getUnsafeBufferAddress(Buffer buffer){
        if(buffer instanceof ByteBuffer){
            synchronized(unsafeBuffers){
                if(javaUnsafeBuffers.contains((ByteBuffer)buffer, true)){
                    throw new UnsupportedOperationException("Pure-Java buffers do not have a native address");
                }
            }
        }
        return getBufferAddress(buffer) + buffer.position();
    }

    /**
     * Registers the given ByteBuffer as an unsafe ByteBuffer. The ByteBuffer must have been allocated in native code, pointing to
     * a memory region allocated via malloc. Needs to be disposed with {@link #disposeUnsafeByteBuffer(ByteBuffer)}.
     * @param buffer the {@link ByteBuffer} to register
     * @return the ByteBuffer passed to the method
     */
    public static ByteBuffer newUnsafeByteBuffer(ByteBuffer buffer){
        allocatedUnsafe += buffer.capacity();
        synchronized(unsafeBuffers){
            unsafeBuffers.add(buffer);
            if(javaBufferFallback) javaUnsafeBuffers.add(buffer);
        }
        return buffer;
    }

    /** @return the number of bytes allocated with {@link #newUnsafeByteBuffer(int)} */
    public static int getAllocatedBytesUnsafe(){
        return allocatedUnsafe;
    }

    private static void copyJava(byte[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        Buffer out = duplicateAndClear(dst);
        if(out instanceof ByteBuffer){
            ByteBuffer bytes = (ByteBuffer)out;
            bytes.position(dstOffset);
            bytes.put(src, srcOffset, numBytes);
            return;
        }

        for(int i = 0; i < numBytes; i++){
            putByte(out, dstOffset + i, src[srcOffset + i]);
        }
    }

    private static void copyJava(short[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        Buffer out = duplicateAndClear(dst);
        if(out instanceof ByteBuffer){
            ByteBuffer bytes = (ByteBuffer)out;
            bytes.position(dstOffset);
            bytes.slice().order(ByteOrder.nativeOrder()).asShortBuffer().put(src, srcOffset, numBytes >>> 1);
            return;
        }else if(out instanceof ShortBuffer && (dstOffset & 1) == 0){
            ShortBuffer shorts = (ShortBuffer)out;
            shorts.position(dstOffset >>> 1);
            shorts.put(src, srcOffset, numBytes >>> 1);
            return;
        }

        for(int i = 0; i < numBytes; i++){
            putByte(out, dstOffset + i, arrayByte(src[srcOffset + (i >>> 1)] & 0xffffL, i & 1, 2));
        }
    }

    private static void copyJava(int[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        Buffer out = duplicateAndClear(dst);
        if(out instanceof ByteBuffer){
            ByteBuffer bytes = (ByteBuffer)out;
            bytes.position(dstOffset);
            bytes.slice().order(ByteOrder.nativeOrder()).asIntBuffer().put(src, srcOffset, numBytes >>> 2);
            return;
        }else if(out instanceof IntBuffer && (dstOffset & 3) == 0){
            IntBuffer ints = (IntBuffer)out;
            ints.position(dstOffset >>> 2);
            ints.put(src, srcOffset, numBytes >>> 2);
            return;
        }

        for(int i = 0; i < numBytes; i++){
            putByte(out, dstOffset + i, arrayByte(src[srcOffset + (i >>> 2)] & 0xffffffffL, i & 3, 4));
        }
    }

    private static void copyJava(float[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        Buffer out = duplicateAndClear(dst);
        if(out instanceof ByteBuffer){
            ByteBuffer bytes = (ByteBuffer)out;
            bytes.position(dstOffset);
            bytes.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().put(src, srcOffset, numBytes >>> 2);
            return;
        }else if(out instanceof FloatBuffer && (dstOffset & 3) == 0){
            FloatBuffer floats = (FloatBuffer)out;
            floats.position(dstOffset >>> 2);
            floats.put(src, srcOffset, numBytes >>> 2);
            return;
        }

        for(int i = 0; i < numBytes; i++){
            long value = Float.floatToRawIntBits(src[srcOffset + (i >>> 2)]) & 0xffffffffL;
            putByte(out, dstOffset + i, arrayByte(value, i & 3, 4));
        }
    }

    private static void copyJava(Buffer src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        Buffer in = duplicateAndClear(src);
        Buffer out = duplicateAndClear(dst);
        if(in instanceof ByteBuffer && out instanceof ByteBuffer){
            ByteBuffer source = (ByteBuffer)in;
            ByteBuffer destination = (ByteBuffer)out;
            source.position(srcOffset);
            source.limit(srcOffset + numBytes);
            destination.position(dstOffset);
            destination.put(source);
            return;
        }

        for(int i = 0; i < numBytes; i++){
            putByte(out, dstOffset + i, getByte(in, srcOffset + i));
        }
    }

    private static Buffer duplicateAndClear(Buffer buffer){
        Buffer duplicate;
        if(buffer instanceof ByteBuffer){
            ByteBuffer bytes = (ByteBuffer)buffer;
            duplicate = bytes.duplicate().order(bytes.order());
        }else if(buffer instanceof ShortBuffer){
            duplicate = ((ShortBuffer)buffer).duplicate();
        }else if(buffer instanceof CharBuffer){
            duplicate = ((CharBuffer)buffer).duplicate();
        }else if(buffer instanceof IntBuffer){
            duplicate = ((IntBuffer)buffer).duplicate();
        }else if(buffer instanceof LongBuffer){
            duplicate = ((LongBuffer)buffer).duplicate();
        }else if(buffer instanceof FloatBuffer){
            duplicate = ((FloatBuffer)buffer).duplicate();
        }else if(buffer instanceof DoubleBuffer){
            duplicate = ((DoubleBuffer)buffer).duplicate();
        }else{
            throw new ArcRuntimeException("Can't copy to a " + buffer.getClass().getName() + " instance");
        }
        duplicate.clear();
        return duplicate;
    }

    private static byte arrayByte(long value, int byteIndex, int bytesPerElement){
        int shift = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? (bytesPerElement - byteIndex - 1) << 3 : byteIndex << 3;
        return (byte)(value >>> shift);
    }

    private static byte getByte(Buffer buffer, int byteIndex){
        if(buffer instanceof ByteBuffer) return ((ByteBuffer)buffer).get(byteIndex);

        int shift = elementShift(buffer);
        int bytesPerElement = 1 << shift;
        int offset = byteIndex & (bytesPerElement - 1);
        int bitShift = bufferOrder(buffer) == ByteOrder.BIG_ENDIAN ? (bytesPerElement - offset - 1) << 3 : offset << 3;
        return (byte)(getBits(buffer, byteIndex >>> shift) >>> bitShift);
    }

    private static void putByte(Buffer buffer, int byteIndex, byte value){
        if(buffer instanceof ByteBuffer){
            ((ByteBuffer)buffer).put(byteIndex, value);
            return;
        }

        int shift = elementShift(buffer);
        int bytesPerElement = 1 << shift;
        int elementIndex = byteIndex >>> shift;
        int offset = byteIndex & (bytesPerElement - 1);
        int bitShift = bufferOrder(buffer) == ByteOrder.BIG_ENDIAN ? (bytesPerElement - offset - 1) << 3 : offset << 3;
        long mask = 0xffL << bitShift;
        putBits(buffer, elementIndex, (getBits(buffer, elementIndex) & ~mask) | ((value & 0xffL) << bitShift));
    }

    private static ByteOrder bufferOrder(Buffer buffer){
        if(buffer instanceof ShortBuffer) return ((ShortBuffer)buffer).order();
        if(buffer instanceof CharBuffer) return ((CharBuffer)buffer).order();
        if(buffer instanceof IntBuffer) return ((IntBuffer)buffer).order();
        if(buffer instanceof LongBuffer) return ((LongBuffer)buffer).order();
        if(buffer instanceof FloatBuffer) return ((FloatBuffer)buffer).order();
        if(buffer instanceof DoubleBuffer) return ((DoubleBuffer)buffer).order();
        return ((ByteBuffer)buffer).order();
    }

    private static long getBits(Buffer buffer, int index){
        if(buffer instanceof ShortBuffer) return ((ShortBuffer)buffer).get(index) & 0xffffL;
        if(buffer instanceof CharBuffer) return ((CharBuffer)buffer).get(index);
        if(buffer instanceof IntBuffer) return ((IntBuffer)buffer).get(index) & 0xffffffffL;
        if(buffer instanceof LongBuffer) return ((LongBuffer)buffer).get(index);
        if(buffer instanceof FloatBuffer) return Float.floatToRawIntBits(((FloatBuffer)buffer).get(index)) & 0xffffffffL;
        if(buffer instanceof DoubleBuffer) return Double.doubleToRawLongBits(((DoubleBuffer)buffer).get(index));
        return ((ByteBuffer)buffer).get(index) & 0xffL;
    }

    private static void putBits(Buffer buffer, int index, long value){
        if(buffer instanceof ShortBuffer){
            ((ShortBuffer)buffer).put(index, (short)value);
        }else if(buffer instanceof CharBuffer){
            ((CharBuffer)buffer).put(index, (char)value);
        }else if(buffer instanceof IntBuffer){
            ((IntBuffer)buffer).put(index, (int)value);
        }else if(buffer instanceof LongBuffer){
            ((LongBuffer)buffer).put(index, value);
        }else if(buffer instanceof FloatBuffer){
            ((FloatBuffer)buffer).put(index, Float.intBitsToFloat((int)value));
        }else if(buffer instanceof DoubleBuffer){
            ((DoubleBuffer)buffer).put(index, Double.longBitsToDouble(value));
        }else{
            ((ByteBuffer)buffer).put(index, (byte)value);
        }
    }


	/*JNI
	#include <stdio.h>
	#include <stdlib.h>
	#include <string.h>
	*/

    /**
     * Frees the memory allocated for the ByteBuffer, which MUST have been allocated via {@link #newUnsafeByteBuffer(ByteBuffer)}
     * or in native code.
     */
    private static void freeMemory(ByteBuffer buffer){
        //Direct Java buffers are reclaimed by the runtime GC.
    } /*
		free(buffer);
	 */

    private static ByteBuffer newDisposableByteBuffer(int numBytes){
        return ByteBuffer.allocateDirect(numBytes);
    } /*
		return env->NewDirectByteBuffer((char*)malloc(numBytes), numBytes);
	*/

    private static long getBufferAddress(Buffer buffer){
        return 0L;
    } /*
	    return (jlong) buffer;
	*/

    /** Writes the specified number of zeros to the buffer. This is generally faster than reallocating a new buffer. */
    private static void clear(ByteBuffer buffer, int numBytes){
        for(int i = 0; i < numBytes; i++) buffer.put(i, (byte)0);
    } /*
		memset(buffer, 0, numBytes);
	*/

    private static void copyJni(float[] src, Buffer dst, int numFloats, int offset){
        copyJava(src, offset, dst, 0, numFloats << 2);
    } /*
		memcpy(dst, src + offset, numFloats << 2 );
	*/

    private static void copyJni(byte[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        copyJava(src, srcOffset, dst, dstOffset, numBytes);
    } /*
		memcpy(dst + dstOffset, src + srcOffset, numBytes);
	*/

    private static void copyJni(short[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        copyJava(src, srcOffset, dst, dstOffset, numBytes);
    } /*
		memcpy(dst + dstOffset, src + srcOffset, numBytes);
	 */

    private static void copyJni(int[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        copyJava(src, srcOffset, dst, dstOffset, numBytes);
    } /*
		memcpy(dst + dstOffset, src + srcOffset, numBytes);
	*/

    private static void copyJni(float[] src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        copyJava(src, srcOffset, dst, dstOffset, numBytes);
    } /*
		memcpy(dst + dstOffset, src + srcOffset, numBytes);
	*/

    public static void copyJni(Buffer src, int srcOffset, Buffer dst, int dstOffset, int numBytes){
        copyJava(src, srcOffset, dst, dstOffset, numBytes);
    } /*
		memcpy(dst + dstOffset, src + srcOffset, numBytes);
	*/
}
