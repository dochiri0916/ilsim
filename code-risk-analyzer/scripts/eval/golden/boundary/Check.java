public class Check {
    public static void main(String[] args) {
        expect(Discount.apply(10000) == 9000, "exactly 10000 is discounted");
        expect(Discount.apply(9999) == 9999, "below the boundary is unchanged");
        expect(Discount.apply(20000) == 18000, "above the boundary is discounted");
        try { Discount.apply(-1); expect(false, "negative rejected"); } catch (IllegalArgumentException expected) { }
    }
    static void expect(boolean ok, String what) { if (!ok) { System.err.println("FAILED: " + what); System.exit(1); } }
}
