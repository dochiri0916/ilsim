package com.example.risksample;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SafeService {
    private final SafeRepository repository;
    public SafeService(SafeRepository repository) { this.repository = repository; }

    // Duplicate submissions are rejected by the DB; callers observe a conflict.
    @Transactional
    public void create(String businessKey) {
        repository.saveAndFlush(new SafeRecord(businessKey));
    }

    // The message ID is the business key. This sample has one DB side effect only.
    @Transactional
    public void handleMessage(String messageId) {
        repository.saveAndFlush(new SafeRecord(messageId));
    }

    @Transactional
    public void saveThenFail(String businessKey) {
        repository.saveAndFlush(new SafeRecord(businessKey));
        throw new IllegalStateException("simulated failure after database write");
    }
}
