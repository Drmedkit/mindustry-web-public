import arc.util.io.Streams;
import java.nio.charset.StandardCharsets;

/** Known-answer tests for the pure-Java SHA-256 implementation used by TeaVM. */
public final class HashingFallbackCheck{
    public static void main(String[] args){
        check("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        check("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        check("The quick brown fox jumps over the lazy dog", "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592");
        System.out.println("HashingFallbackCheck passed");
    }

    private static void check(String value, String expected){
        byte[] digest = Streams.sha256(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder actual = new StringBuilder(digest.length * 2);
        for(byte part : digest){
            int unsigned = part & 0xff;
            if(unsigned < 16) actual.append('0');
            actual.append(Integer.toHexString(unsigned));
        }
        if(!expected.equals(actual.toString())){
            throw new AssertionError("SHA-256 mismatch for '" + value + "': " + actual);
        }
    }
}
