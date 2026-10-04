public class Split {
    public static long[] share(long total, int people) {
        if (people <= 0 || total < 0) throw new IllegalArgumentException("invalid split");
        long[] shares = new long[people];
        for (int i = 0; i < people; i++) shares[i] = total / people;
        return shares;
    }
}
