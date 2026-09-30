import arc.struct.IntSeq;

public final class ArcCoreSmoke {
    private ArcCoreSmoke() {
    }

    public static void main(String[] args) {
        int sum = IntSeq.range(1, 5).sum();
        if (sum != 10) {
            throw new AssertionError("Unexpected Arc IntSeq result: " + sum);
        }
    }
}
