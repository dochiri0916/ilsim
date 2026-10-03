package com.example.risksample;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "sample.service.enabled=true")
class LocalServiceEndpointTests {
    @Autowired LocalServiceEndpoint endpoint;
    @Autowired SafeRepository repository;
    @Autowired SafeService service;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> request(String method, String route) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + endpoint.port() + route))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void normalAndDuplicateRequestsLeaveOneCommittedRow() throws Exception {
        assertEquals(200, request("GET", "/ready").statusCode());
        assertEquals(201, request("POST", "/records?key=http-normal").statusCode());
        assertEquals(409, request("POST", "/records?key=http-normal").statusCode());
        assertEquals("1", request("GET", "/records?key=http-normal").body());
        assertEquals(1, repository.countByBusinessKey("http-normal"));
    }

    @Test
    void failedRequestRollsBackAndCanBeRetried() throws Exception {
        assertEquals(500, request("POST", "/fail?key=http-rollback").statusCode());
        assertEquals(0, repository.countByBusinessKey("http-rollback"));
        assertEquals("0", request("GET", "/records?key=http-rollback").body());
        assertEquals(201, request("POST", "/records?key=http-rollback").statusCode());
        assertEquals(1, repository.countByBusinessKey("http-rollback"));
    }

    @Test
    void concurrentRequestsReportOneCommitAndOneConflict() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return request("POST", "/records?key=http-concurrent").statusCode();
            };
            Future<Integer> first = executor.submit(task);
            Future<Integer> second = executor.submit(task);
            assertEquals(java.util.Set.of(201, 409), java.util.Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, repository.countByBusinessKey("http-concurrent"));
    }

    @Test
    void invalidInputAndUnsupportedMethodsDoNotWrite() throws Exception {
        long before = repository.count();
        assertEquals(400, request("POST", "/records?key=").statusCode());
        assertEquals(405, request("DELETE", "/records?key=invalid").statusCode());
        assertEquals(400, request("GET", "/discount?quantity=0").statusCode());
        assertEquals("10", request("GET", "/discount?quantity=10").body());
        assertEquals(before, repository.count());
    }

    @Test
    void startsIdempotentlyStopsAndRestartsLocalServer() throws Exception {
        LocalServiceEndpoint local = new LocalServiceEndpoint(service, repository);
        try {
            local.start();
            assertTrue(local.isRunning());
            int port = local.port();
            local.start();
            assertEquals(port, local.port());
            local.stop();
            assertFalse(local.isRunning());
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
                    .timeout(Duration.ofSeconds(2)).build();
            assertThrows(java.io.IOException.class, () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
            local.start();
            assertTrue(local.isRunning());
            HttpRequest restarted = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + local.port() + "/ready"))
                    .timeout(Duration.ofSeconds(5)).build();
            assertEquals(200, client.send(restarted, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            local.stop();
        }
    }
}
