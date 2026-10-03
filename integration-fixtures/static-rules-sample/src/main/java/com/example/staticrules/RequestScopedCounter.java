package com.example.staticrules;

import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.annotation.RequestScope;

// EXPECT: no CON-003 (one instance per request)
@Service
@RequestScope
public class RequestScopedCounter {
    private int count;

    @GetMapping
    public int next() { return ++count; }
}
