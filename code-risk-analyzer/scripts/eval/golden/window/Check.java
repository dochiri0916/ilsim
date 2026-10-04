public class Check {
    public static void main(String[] args) {
        RateLimiter limiter = new RateLimiter(2, 1000);
        expect(limiter.allow("a", 0), "first call");
        expect(limiter.allow("a", 10), "second call");
        expect(!limiter.allow("a", 999), "third call inside the window is blocked");
        expect(limiter.allow("b", 999), "other callers are independent");
        expect(limiter.allow("a", 1000), "the call at 0 expired exactly at 1000");
        expect(!limiter.allow("a", 1009), "the call at 10 still counts at 1009");
        expect(limiter.allow("a", 1010), "the call at 10 expired at 1010");
    }
    static void expect(boolean ok, String what) { if (!ok) { System.err.println("FAILED: " + what); System.exit(1); } }
}
