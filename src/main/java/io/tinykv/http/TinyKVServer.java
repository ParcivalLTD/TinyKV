package io.tinykv.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tinykv.KVStore;
import io.tinykv.Stats;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight HTTP REST front-end and Web Dashboard for TinyKV.
 * Runs on Java 21 Virtual Threads with zero external dependencies.
 */
public final class TinyKVServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TinyKVServer.class);

    public record ServerConfig(
            int port,
            int maxKeyBytes,
            int maxValueBytes,
            long maxKeys,
            String writeToken,
            boolean readOnly,
            int rateLimitRpm
    ) {
        public static ServerConfig defaultForPort(int port) {
            return new ServerConfig(port, 256, 65536, 10000, null, false, 0);
        }
    }

    private final KVStore store;
    private final HttpServer server;
    private final ServerConfig config;
    private final ConcurrentHashMap<String, TokenBucket> ipRateLimiters = new ConcurrentHashMap<>();

    public TinyKVServer(KVStore store, int port) throws IOException {
        this(store, ServerConfig.defaultForPort(port));
    }

    public TinyKVServer(KVStore store, ServerConfig config) throws IOException {
        this.store = store;
        this.config = config;
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        setupRoutes();
    }

    private void setupRoutes() {
        server.createContext("/healthz", this::handleHealthz);
        server.createContext("/readyz", this::handleReadyz);
        server.createContext("/api/v1/keys", this::handleKeys);
        server.createContext("/api/v1/stats", this::handleStats);
        server.createContext("/api/v1/compact", this::handleCompact);
        server.createContext("/", this::handleRoot);
    }

    public void start() {
        server.start();
        log.info("TinyKV HTTP server started on port {}", getPort());
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String path = exchange.getRequestURI().getPath();

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("/".equals(path) || "/index.html".equals(path)) {
            byte[] htmlBytes = loadResource("static/index.html");
            if (htmlBytes != null) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, htmlBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(htmlBytes);
                }
                return;
            }
        }

        sendResponse(exchange, 404, "{\"error\":\"Not Found\"}");
    }

    private void handleHealthz(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        sendResponse(exchange, 200, "{\"status\":\"UP\"}");
    }

    private void handleReadyz(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        if (store != null && store.isOpen()) {
            sendResponse(exchange, 200, "{\"status\":\"READY\"}");
        } else {
            sendResponse(exchange, 503, "{\"status\":\"NOT_READY\"}");
        }
    }

    private void handleKeys(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod().toUpperCase();

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath(); // e.g. /api/v1/keys/myKey
        String prefix = "/api/v1/keys";

        if (!path.startsWith(prefix) || path.length() <= prefix.length() + 1) {
            sendResponse(exchange, 400, "{\"error\":\"Missing key in path. Use /api/v1/keys/{key}\"}");
            return;
        }

        String rawKey = path.substring(prefix.length() + 1);
        String key = java.net.URLDecoder.decode(rawKey, StandardCharsets.UTF_8);

        if (key.isBlank()) {
            sendResponse(exchange, 400, "{\"error\":\"Key cannot be empty\"}");
            return;
        }

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        switch (method) {
            case "GET" -> {
                Optional<byte[]> value = store.get(keyBytes);
                if (value.isPresent()) {
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, value.get().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(value.get());
                    }
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Key not found: " + escapeJson(key) + "\"}");
                }
            }
            case "PUT" -> {
                if (!checkAuthorized(exchange) || !checkRateLimit(exchange)) {
                    return;
                }
                if (keyBytes.length > config.maxKeyBytes()) {
                    sendResponse(exchange, 400, "{\"error\":\"Key size exceeds limit (" + keyBytes.length + " > " + config.maxKeyBytes() + " bytes)\"}");
                    return;
                }

                String cl = exchange.getRequestHeaders().getFirst("Content-Length");
                if (cl != null) {
                    try {
                        long len = Long.parseLong(cl);
                        if (len > config.maxValueBytes()) {
                            sendResponse(exchange, 413, "{\"error\":\"Payload Too Large: maximum value size is " + config.maxValueBytes() + " bytes\"}");
                            return;
                        }
                    } catch (NumberFormatException ignored) {}
                }

                InputStream is = exchange.getRequestBody();
                byte[] body = is.readNBytes(config.maxValueBytes() + 1);
                if (body.length > config.maxValueBytes()) {
                    sendResponse(exchange, 413, "{\"error\":\"Payload Too Large: maximum value size is " + config.maxValueBytes() + " bytes\"}");
                    return;
                }

                if (store.count() >= config.maxKeys() && !store.contains(keyBytes)) {
                    sendResponse(exchange, 507, "{\"error\":\"Insufficient Storage: maximum key capacity reached (" + config.maxKeys() + " keys)\"}");
                    return;
                }

                store.put(keyBytes, body);
                sendResponse(exchange, 200, "{\"status\":\"OK\",\"key\":\"" + escapeJson(key) + "\",\"size\":" + body.length + "}");
            }
            case "DELETE" -> {
                if (!checkAuthorized(exchange) || !checkRateLimit(exchange)) {
                    return;
                }
                boolean deleted = store.delete(keyBytes);
                if (deleted) {
                    sendResponse(exchange, 204, "");
                } else {
                    sendResponse(exchange, 404, "{\"error\":\"Key not found: " + escapeJson(key) + "\"}");
                }
            }
            default -> sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
        }
    }

    private void handleStats(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        Stats stats = store.stats();
        String json = String.format(
                "{\"activeKeys\":%d,\"totalSegments\":%d,\"totalDiskBytes\":%d,\"activeDataBytes\":%d,\"deadDataBytes\":%d,\"deadSpacePercent\":%.2f}",
                stats.activeKeys(),
                stats.totalSegments(),
                stats.totalDiskBytes(),
                stats.activeDataBytes(),
                stats.deadDataBytes(),
                stats.deadSpacePercent()
        );
        sendResponse(exchange, 200, json);
    }

    private void handleCompact(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        if (!checkAuthorized(exchange) || !checkRateLimit(exchange)) {
            return;
        }
        store.compact();
        Stats stats = store.stats();
        String json = String.format(
                "{\"status\":\"COMPACTED\",\"activeKeys\":%d,\"totalSegments\":%d,\"totalDiskBytes\":%d,\"deadSpacePercent\":%.2f}",
                stats.activeKeys(),
                stats.totalSegments(),
                stats.totalDiskBytes(),
                stats.deadSpacePercent()
        );
        sendResponse(exchange, 200, json);
    }

    private boolean checkAuthorized(HttpExchange exchange) throws IOException {
        if (config.readOnly()) {
            sendResponse(exchange, 403, "{\"error\":\"Forbidden: server is operating in read-only mode\"}");
            return false;
        }
        if (config.writeToken() != null && !config.writeToken().isBlank()) {
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String writeTokenHeader = exchange.getRequestHeaders().getFirst("X-Write-Token");
            boolean authorized = false;
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                authorized = config.writeToken().equals(token);
            } else if (writeTokenHeader != null) {
                authorized = config.writeToken().equals(writeTokenHeader.trim());
            }
            if (!authorized) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"TinyKV\"");
                sendResponse(exchange, 401, "{\"error\":\"Unauthorized: valid write token required\"}");
                return false;
            }
        }
        return true;
    }

    private boolean checkRateLimit(HttpExchange exchange) throws IOException {
        if (config.rateLimitRpm() <= 0) return true;
        String clientIp = getClientIp(exchange);
        TokenBucket bucket = ipRateLimiters.computeIfAbsent(clientIp, k -> new TokenBucket(config.rateLimitRpm()));
        if (!bucket.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            sendResponse(exchange, 429, "{\"error\":\"Too Many Requests: rate limit exceeded (" + config.rateLimitRpm() + " requests/minute)\"}");
            return false;
        }
        if (ipRateLimiters.size() > 5000) {
            long cutoff = System.currentTimeMillis() - 120_000;
            ipRateLimiters.entrySet().removeIf(e -> e.getValue().lastRefillTime < cutoff);
        }
        return true;
    }

    private String getClientIp(HttpExchange exchange) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int commaIdx = xff.indexOf(',');
            return (commaIdx > 0 ? xff.substring(0, commaIdx) : xff).trim();
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        return (remote != null && remote.getAddress() != null) ? remote.getAddress().getHostAddress() : "unknown";
    }

    static final class TokenBucket {
        private final int capacity;
        private final double refillRatePerMs;
        private double tokens;
        volatile long lastRefillTime;

        TokenBucket(int maxRequestsPerMinute) {
            this.capacity = Math.max(1, maxRequestsPerMinute);
            this.refillRatePerMs = (double) this.capacity / 60_000.0;
            this.tokens = this.capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            tokens = Math.min(capacity, tokens + elapsed * refillRatePerMs);
            lastRefillTime = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        if (statusCode == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(statusCode, -1);
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static byte[] loadResource(String path) {
        try (InputStream in = TinyKVServer.class.getClassLoader().getResourceAsStream(path)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ignored) {}
        return null;
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        server.stop(1);
    }
}
