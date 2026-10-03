package com.example.risksample;

import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

// No test-level transaction: assertions observe commits across real proxy boundaries.
@SpringBootTest
@Import(RaceWindowConfiguration.class)
class DatabaseBehaviorTests {
    @Autowired SafeService safe;
    @Autowired UnsafeService unsafe;
    @Autowired SafeRepository safeRepository;
    @Autowired UnsafeRepository unsafeRepository;
    @Autowired RaceWindowConfiguration.RaceGate raceGate;

    @BeforeEach
    void clearDatabase() {
        safeRepository.deleteAll();
        unsafeRepository.deleteAll();
    }

    @Test
    void proxyInvocationRollsBackTheWrite() {
        assertThrows(IllegalStateException.class, () -> safe.saveThenFail("rollback"));
        assertEquals(0, safeRepository.countByBusinessKey("rollback"));
    }

    @Test
    void selfInvocationLeavesTheRepositoryCommitBehind() {
        assertThrows(IllegalStateException.class, () -> unsafe.selfInvokeThenFail("self"));
        assertEquals(1, unsafeRepository.countByBusinessKey("self"));
    }

    @Test
    void directProxyInvocationOfUnsafeServiceStillRollsBack() {
        assertThrows(IllegalStateException.class, () -> unsafe.saveThenFail("direct"));
        assertEquals(0, unsafeRepository.countByBusinessKey("direct"));
    }

    @Test
    void repeatedUnsafeMessageProducesTwoWrites() {
        unsafe.handleMessage("message");
        unsafe.handleMessage("message");
        assertEquals(2, unsafeRepository.countByBusinessKey("message"));
    }

    @Test
    void repeatedSafeMessageReportsConflictAndKeepsOneWrite() {
        safe.handleMessage("message");
        assertThrows(DataIntegrityViolationException.class, () -> safe.handleMessage("message"));
        assertEquals(1, safeRepository.countByBusinessKey("message"));
    }

    @Test
    void existingUnsafeRecordIsNotInsertedAgain() {
        unsafe.createIfAbsent("existing");
        unsafe.createIfAbsent("existing");
        assertEquals(1, unsafeRepository.countByBusinessKey("existing"));
    }

    @Test
    void concurrentUnsafeCreatesCommitDuplicateRows() throws Exception {
        raceGate.open("unsafe-concurrent");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> unsafe.createIfAbsent("unsafe-concurrent"));
            Future<?> second = executor.submit(() -> unsafe.createIfAbsent("unsafe-concurrent"));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            raceGate.close();
        }
        // This is a characterization of the defect, not a claim that it is safe.
        assertEquals(2, unsafeRepository.countByBusinessKey("unsafe-concurrent"));
    }

    @Test
    void concurrentCreatesHaveOneCommitAndOneConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("start barrier");
                try {
                    safe.create("concurrent");
                    return true;
                } catch (DataIntegrityViolationException conflict) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(attempt);
            Future<Boolean> second = executor.submit(attempt);
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            int commits = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, commits);
        }
        assertEquals(1, safeRepository.countByBusinessKey("concurrent"));
    }
}
