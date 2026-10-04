import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class RateLimiter {
    private final int limit;
    private final long windowMillis;
    private final Map<String, Deque<Long>> calls = new HashMap<>();

    public RateLimiter(int limit, long windowMillis) {
        this.limit = limit;
        this.windowMillis = windowMillis;
    }

    public synchronized boolean allow(String caller, long now) {
        Deque<Long> recent = calls.computeIfAbsent(caller, key -> new ArrayDeque<>());
        if (recent.size() >= limit) return false;
        recent.addLast(now);
        return true;
    }
}
