package com.example.staticrules;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;

@Service
public class CounterService {
    private boolean ready;
    private int count;
    private int total;

    // EXPECT: no CON-003 (a read-only unary operator is not a mutation)
    @GetMapping
    public boolean isNotReady() { return !ready; }

    // EXPECT: no CON-003 (parameter shadows the field)
    @GetMapping
    public int shadow(int total) { total = total + 1; return total; }

    // EXPECT: no CON-003 (guarded by synchronized)
    @GetMapping
    public void guarded() { synchronized (this) { total++; } }

    // EXPECT: CON-003 (unsynchronized increment on a singleton)
    @GetMapping
    public int next() { return ++count; }
}
