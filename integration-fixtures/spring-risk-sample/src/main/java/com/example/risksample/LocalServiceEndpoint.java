package com.example.risksample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** Opt-in loopback entrypoint for this isolated fixture, using real service proxies. */
@Component
@ConditionalOnProperty(name = "sample.service.enabled", havingValue = "true")
public final class LocalServiceEndpoint implements SmartLifecycle {
    private final SafeService service;
    private final SafeRepository repository;
    private final PricePolicy policy = new PricePolicy();
    private final InetSocketAddress address;
    private HttpServer server;
    private ExecutorService workers;
    private volatile boolean running;

    @Autowired
    public LocalServiceEndpoint(SafeService service, SafeRepository repository) {
        this(service, repository, new InetSocketAddress("127.0.0.1", 0));
    }

    LocalServiceEndpoint(SafeService service, SafeRepository repository, InetSocketAddress address) {
        this.service = service;
        this.repository = repository;
        this.address = address;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        try {
            server = HttpServer.create(address, 16);
            workers = Executors.newFixedThreadPool(4);
            server.setExecutor(workers);
            server.createContext("/", this::handle);
            server.start();
            running = true;
            System.out.println("RISK_SAMPLE_READY_PORT=" + port());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not bind fixture loopback service");
        }
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public boolean isRunning() { return running; }

    @Override
    public synchronized void stop() {
        if (server != null) server.stop(1);
        if (workers != null) workers.shutdownNow();
        running = false;
    }

    private void handle(HttpExchange exchange) throws IOException {
        int status = 200;
        String body;
        try {
            String route = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            boolean allowed = switch (route) {
                case "/ready", "/discount" -> method.equals("GET");
                case "/records" -> method.equals("GET") || method.equals("POST");
                case "/fail" -> method.equals("POST");
                default -> false;
            };
            if (!allowed) {
                status = java.util.Set.of("/ready", "/discount", "/records", "/fail").contains(route) ? 405 : 404;
                body = "unsupported request";
            } else if (route.equals("/ready")) {
                repository.count(); // Checks actual DB availability and initialized schema.
                body = "ready";
            } else if (route.equals("/discount")) {
                String quantity = parameter(exchange, "quantity");
                body = Integer.toString(policy.discountPercent(Integer.parseInt(quantity)));
            } else {
                String key = parameter(exchange, "key");
                if (!key.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException();
                if (route.equals("/fail")) {
                    service.saveThenFail(key);
                    body = "unexpected success";
                } else if (method.equals("GET")) {
                    body = Long.toString(repository.countByBusinessKey(key));
                } else {
                    service.create(key);
                    status = 201;
                    body = "created";
                }
            }
        } catch (DataIntegrityViolationException conflict) {
            status = 409;
            body = "conflict";
        } catch (IllegalArgumentException invalid) {
            status = 400;
            body = "invalid input";
        } catch (RuntimeException failed) {
            status = 500;
            body = "operation failed";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var response = exchange.getResponseBody()) { response.write(bytes); }
        finally { exchange.close(); }
    }

    private String parameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        // Deliberately narrow fixture protocol: exactly one unencoded parameter.
        if (query == null || !query.startsWith(name + "=") || query.contains("&") || query.contains("%"))
            throw new IllegalArgumentException();
        return query.substring(name.length() + 1);
    }
}
