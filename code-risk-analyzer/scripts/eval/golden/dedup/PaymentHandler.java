import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class PaymentHandler {
    private final Set<String> processed = new HashSet<>();
    private final AtomicLong charged = new AtomicLong();

    public boolean handle(String paymentId, long amount) {
        if (processed.contains(paymentId)) return false;
        charged.addAndGet(amount);
        processed.add(paymentId);
        return true;
    }

    public long charged() { return charged.get(); }
}
