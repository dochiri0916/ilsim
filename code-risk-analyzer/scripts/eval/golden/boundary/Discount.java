public class Discount {
    public static long apply(long amount) {
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        if (amount > 10000) return amount - amount / 10;
        return amount;
    }
}
