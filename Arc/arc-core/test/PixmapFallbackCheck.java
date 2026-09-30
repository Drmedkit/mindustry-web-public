import arc.graphics.*;
import arc.util.*;

import java.nio.*;

/** Dependency-free JVM checks for {@link Pixmap}'s pure-Java buffer fallback. */
public class PixmapFallbackCheck{
    // Mindustry/core/assets-raw/sprites/shapes/blank.png, a 1x1 RGBA PNG.
    private static final byte[] tinyPng = hex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489" +
        "0000000b4944415478da63f80f040009fb03fd68fa1ccc0000000049454e44ae426082"
    );

    public static void main(String[] args){
        check(!Buffers.isJavaBufferFallback(), "native/direct buffers must remain the default");
        check(!UnsafeBuffers.initialized, "Unsafe must not be initialized before the fallback check");

        boolean nativesLoaded = ArcNativesLoader.loaded;
        Buffers.setJavaBufferFallback(true);
        ArcNativesLoader.loaded = true; // Any accidental JNI Pixmap call will now fail this check.
        try{
            checkBlankAllocationAndCopy();
            checkPngDecoding();
            check(!UnsafeBuffers.initialized, "fallback Pixmap operations must not initialize Unsafe");
        }finally{
            ArcNativesLoader.loaded = nativesLoaded;
            Buffers.setJavaBufferFallback(false);
        }

        checkDesktopDefaults();
        System.out.println("PixmapFallbackCheck passed");
    }

    private static void checkDesktopDefaults(){
        Pixmap blank = new Pixmap(1, 1);
        check(blank.pixels.isDirect(), "default blank Pixmap allocation must remain direct");

        Pixmap decoded = PixmapIO.readPNG(tinyPng);
        check(decoded.pixels.isDirect(), "default PNG decoder allocation must remain direct");

        boolean heapRejected = false;
        try{
            new Pixmap(ByteBuffer.allocate(4), 1, 1);
        }catch(ArcRuntimeException expected){
            heapRejected = true;
        }
        check(heapRejected, "default Pixmap constructor must still reject heap buffers");

        blank.dispose();
        decoded.dispose();
    }

    private static void checkBlankAllocationAndCopy(){
        Pixmap source = new Pixmap(2, 2);
        check(!source.pixels.isDirect(), "fallback blank Pixmap must use Java heap storage");
        check(source.width == 2 && source.height == 2 && source.pixels.capacity() == 16, "blank Pixmap dimensions and capacity");
        for(int i = 0; i < source.pixels.capacity(); i++){
            check(source.pixels.get(i) == 0, "blank Pixmap storage must be zero-filled");
        }

        source.setRaw(0, 0, 0x10203040);
        source.setRaw(1, 0, 0x50607080);
        source.setRaw(0, 1, 0x90a0b0c0);
        source.setRaw(1, 1, 0xd0e0f001);

        Pixmap destination = new Pixmap(2, 2);
        source.pixels.position(3).limit(7);
        destination.pixels.position(2).limit(6);
        destination.draw(source);
        check(source.pixels.position() == 3 && source.pixels.limit() == 7, "copy must preserve source buffer state");
        check(destination.pixels.position() == 2 && destination.pixels.limit() == 6, "copy must preserve destination buffer state");

        source.pixels.clear();
        destination.pixels.clear();
        check(destination.getRaw(0, 0) == 0x10203040, "first copied pixel");
        check(destination.getRaw(1, 0) == 0x50607080, "second copied pixel");
        check(destination.getRaw(0, 1) == 0x90a0b0c0, "third copied pixel");
        check(destination.getRaw(1, 1) == 0xd0e0f001, "fourth copied pixel");

        Pixmap copy = source.copy();
        check(!copy.pixels.isDirect(), "fallback Pixmap copy must use Java heap storage");
        check(copy.getRaw(1, 1) == source.getRaw(1, 1), "Pixmap.copy contents");

        source.dispose();
        destination.dispose();
        copy.dispose();
    }

    private static void checkPngDecoding(){
        Pixmap decoded = new Pixmap(tinyPng);
        check(decoded.width == 1 && decoded.height == 1, "Pixmap PNG dimensions");
        check(!decoded.pixels.isDirect(), "Pixmap PNG decoder must use Java heap storage");

        Pixmap explicit = PixmapIO.readPNG(tinyPng);
        check(explicit.width == 1 && explicit.height == 1, "PixmapIO PNG dimensions");
        check(!explicit.pixels.isDirect(), "PixmapIO PNG decoder must use Java heap storage");
        check(explicit.getRaw(0, 0) == decoded.getRaw(0, 0), "PNG decode contents");

        decoded.dispose();
        explicit.dispose();
    }

    private static byte[] hex(String value){
        byte[] bytes = new byte[value.length() / 2];
        for(int i = 0; i < bytes.length; i++){
            bytes[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void check(boolean condition, String message){
        if(!condition) throw new AssertionError(message);
    }
}
