package com.example.risksample;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deliberately unsafe analysis targets; preserve these defects. */
@Service
public class UnsafeService {
    private final UnsafeRepository repository;
    public UnsafeService(UnsafeRepository repository) { this.repository = repository; }

    public void createIfAbsent(String businessKey) {
        if (!repository.existsByBusinessKey(businessKey)) {
            repository.save(new UnsafeRecord(businessKey));
        }
    }

    // Models repeated delivery without a broker or deduplication.
    public void handleMessage(String messageId) {
        repository.save(new UnsafeRecord(messageId));
    }

    public void selfInvokeThenFail(String businessKey) {
        saveThenFail(businessKey);
    }

    @Transactional
    public void saveThenFail(String businessKey) {
        repository.saveAndFlush(new UnsafeRecord(businessKey));
        throw new IllegalStateException("simulated failure after database write");
    }
}
