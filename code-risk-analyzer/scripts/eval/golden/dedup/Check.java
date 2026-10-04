import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Check {
    public static void main(String[] args) throws Exception {
        PaymentHandler sequential = new PaymentHandler();
        expect(sequential.handle("p-1", 100), "first delivery charges");
        expect(!sequential.handle("p-1", 100), "repeat delivery is ignored");
        expect(sequential.charged() == 100, "charged once");
        for (int round = 0; round < 200; round++) {
            PaymentHandler handler = new PaymentHandler();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            for (int i = 0; i < 8; i++) pool.submit(() -> { start.await(); if (handler.handle("p-" , 50)) accepted.incrementAndGet(); return null; });
            start.countDown();
            pool.shutdown();
            pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
            expect(accepted.get() == 1 && handler.charged() == 50, "concurrent deliveries charge exactly once");
        }
    }
    static void expect(boolean ok, String what) { if (!ok) { System.err.println("FAILED: " + what); System.exit(1); } }
}
