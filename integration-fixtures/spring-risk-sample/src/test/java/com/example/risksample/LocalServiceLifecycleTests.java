package com.example.risksample;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real sockets exercise lifecycle behavior; business/transaction tests use MySQL separately. */
class LocalServiceLifecycleTests {
    private final SafeService service = mock(SafeService.class);
    private final SafeRepository repository = mock(SafeRepository.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @AfterEach
    void closeClient() {
        client.close();
    }

    private HttpResponse<String> ready(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void stoppingBeforeStartIsRepeatableAndAllowsLaterStart() throws Exception {
        LocalServiceEndpoint local = new LocalServiceEndpoint(service, repository);
        try {
            assertFalse(local.isRunning());
            assertDoesNotThrow(() -> local.stop());
            assertFalse(local.isRunning());
            assertDoesNotThrow(() -> local.stop());
            assertFalse(local.isRunning());
            local.start();
            assertTrue(local.isRunning());
            assertEquals(200, ready(local.port()).statusCode());
        } finally {
            local.stop();
        }
        assertFalse(local.isRunning());
    }

    @Test
    void bindFailureKeepsStoppedStateAndAllowsRetryAfterPortRelease() throws Exception {
        LocalServiceEndpoint local;
        int port;
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            port = occupied.getLocalPort();
            local = new LocalServiceEndpoint(service, repository, new InetSocketAddress("127.0.0.1", port));
            try {
                assertThrows(IllegalStateException.class, () -> local.start());
                assertFalse(local.isRunning());
                assertThrows(IllegalStateException.class, () -> local.start());
                assertFalse(local.isRunning());
                assertDoesNotThrow(() -> local.stop());
                assertFalse(local.isRunning());
                assertFalse(occupied.isClosed());
            } finally {
                local.stop();
            }
        }
        try {
            local.start();
            assertTrue(local.isRunning());
            assertEquals(port, local.port());
            assertEquals(200, ready(port).statusCode());
        } finally {
            local.stop();
        }
        assertFalse(local.isRunning());
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", port));
            assertTrue(probe.isBound());
        }
    }
}
