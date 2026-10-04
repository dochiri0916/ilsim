public class Check {
    public static void main(String[] args) {
        long[] shares = Split.share(10001, 3);
        expect(shares[0] + shares[1] + shares[2] == 10001, "shares add up to the total");
        expect(shares[0] == 3334 && shares[1] == 3334 && shares[2] == 3333, "first people take the extra won");
        expect(java.util.Arrays.equals(Split.share(9, 3), new long[]{3, 3, 3}), "even split unchanged");
        try { Split.share(10, 0); expect(false, "zero people rejected"); } catch (IllegalArgumentException expected) { }
    }
    static void expect(boolean ok, String what) { if (!ok) { System.err.println("FAILED: " + what); System.exit(1); } }
}
