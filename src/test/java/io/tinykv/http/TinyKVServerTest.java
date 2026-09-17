package io.tinykv.http;

import io.tinykv.KVStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class TinyKVServerTest {

    private KVStore store;
    private TinyKVServer server;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        store = KVStore.open(tempDir);
        // Port 0 selects an available ephemeral port
        server = new TinyKVServer(store, 0);
        server.start();
        baseUrl = "http://localhost:" + server.getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        if (store != null) store.close();
    }

    @Test
    @DisplayName("GET /healthz and /readyz return 200 OK")
    void testHealthAndReadiness() throws Exception {
        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/healthz")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");

        HttpResponse<String> ready = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/readyz")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).contains("\"status\":\"READY\"");
    }

    @Test
    @DisplayName("GET / returns 200 OK and serves the HTML landing page")
    void testRootLandingPage() throws Exception {
        HttpResponse<String> root = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.headers().firstValue("Content-Type")).isPresent().contains("text/html; charset=utf-8");
        assertThat(root.body()).contains("TinyKV");
        assertThat(root.body()).contains("kv.wavebeef.com");
    }

    @Test
    @DisplayName("PUT, GET, and DELETE operations via REST API")
    void testCrudRestEndpoints() throws Exception {
        // 1. PUT key
        HttpRequest putReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/my_session"))
                .PUT(HttpRequest.BodyPublishers.ofString("session_token_12345"))
                .build();
        HttpResponse<String> putRes = client.send(putReq, HttpResponse.BodyHandlers.ofString());
        assertThat(putRes.statusCode()).isEqualTo(200);
        assertThat(putRes.body()).contains("\"status\":\"OK\"");

        // 2. GET key
        HttpRequest getReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/my_session")).GET().build();
        HttpResponse<String> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertThat(getRes.statusCode()).isEqualTo(200);
        assertThat(getRes.body()).isEqualTo("session_token_12345");

        // 3. GET non-existent key returns 404
        HttpResponse<String> notFound = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/unknown")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(notFound.statusCode()).isEqualTo(404);

        // 4. DELETE key
        HttpResponse<String> delRes = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/my_session")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(delRes.statusCode()).isEqualTo(204);

        // 5. GET deleted key returns 404
        HttpResponse<String> afterDel = client.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertThat(afterDel.statusCode()).isEqualTo(404);

        // 6. DELETE again returns 404
        HttpResponse<String> delAgain = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/keys/my_session")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(delAgain.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("GET /api/v1/stats returns JSON metrics")
    void testStatsEndpoint() throws Exception {
        store.put("key1", "val1");
        store.put("key2", "val2");

        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/stats")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"activeKeys\":2");
        assertThat(res.body()).contains("\"totalSegments\":");
    }

    @Test
    @DisplayName("POST /api/v1/compact triggers compaction")
    void testCompactEndpoint() throws Exception {
        store.put("key1", "val1");
        store.delete("key1");

        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/compact"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"COMPACTED\"");
    }

    @Test
    @DisplayName("GET /readyz returns 503 when store is closed")
    void testReadyzClosedStore() throws Exception {
        store.close();
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/readyz")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.body()).contains("\"status\":\"NOT_READY\"");
    }

    @Test
    @DisplayName("PUT rejects payload exceeding max value bytes with 413")
    void testPayloadTooLarge(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 256, 100, 1000, null, false, 0))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            byte[] bigPayload = new byte[101];
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/toolarge"))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(bigPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res.statusCode()).isEqualTo(413);
            assertThat(res.body()).contains("Payload Too Large");
        }
    }

    @Test
    @DisplayName("PUT rejects key exceeding max key bytes with 400")
    void testKeySizeLimit(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 10, 1000, 1000, null, false, 0))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            String longKey = "key_that_is_too_long_for_limit";
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/" + longKey))
                            .PUT(HttpRequest.BodyPublishers.ofString("val"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("Key size exceeds limit");
        }
    }

    @Test
    @DisplayName("PUT rejects key when capacity limit reached with 507")
    void testKeyCapacityLimit(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 256, 1000, 1, null, false, 0))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            // First key succeeds
            HttpResponse<String> res1 = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/first"))
                            .PUT(HttpRequest.BodyPublishers.ofString("v1"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res1.statusCode()).isEqualTo(200);

            // Overwriting existing key succeeds even at capacity
            HttpResponse<String> resOverwrite = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/first"))
                            .PUT(HttpRequest.BodyPublishers.ofString("v1-updated"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(resOverwrite.statusCode()).isEqualTo(200);

            // Second unique key rejected with 507
            HttpResponse<String> res2 = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/second"))
                            .PUT(HttpRequest.BodyPublishers.ofString("v2"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res2.statusCode()).isEqualTo(507);
            assertThat(res2.body()).contains("Insufficient Storage");
        }
    }

    @Test
    @DisplayName("Write token guards mutations: 401 when missing, 200 when valid")
    void testWriteTokenAuth(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 256, 1000, 1000, "secret-token", false, 0))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            // 1. Unauthenticated PUT fails with 401
            HttpResponse<String> unauth = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/authkey"))
                            .PUT(HttpRequest.BodyPublishers.ofString("val"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(unauth.statusCode()).isEqualTo(401);

            // 2. Authenticated PUT with Bearer header succeeds
            HttpResponse<String> auth = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/authkey"))
                            .header("Authorization", "Bearer secret-token")
                            .PUT(HttpRequest.BodyPublishers.ofString("val"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(auth.statusCode()).isEqualTo(200);

            // 3. GET remains unauthenticated and succeeds
            HttpResponse<String> getRes = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/authkey")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(getRes.statusCode()).isEqualTo(200);
            assertThat(getRes.body()).isEqualTo("val");
        }
    }

    @Test
    @DisplayName("Read-only mode rejects mutations with 403 Forbidden")
    void testReadOnlyMode(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 256, 1000, 1000, null, true, 0))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/anykey"))
                            .PUT(HttpRequest.BodyPublishers.ofString("val"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(res.statusCode()).isEqualTo(403);
            assertThat(res.body()).contains("read-only");
        }
    }

    @Test
    @DisplayName("Rate limiting rejects mutations exceeding RPM with 429")
    void testRateLimitingWhenConfigured(@TempDir Path dir) throws Exception {
        try (KVStore s = KVStore.open(dir);
             TinyKVServer srv = new TinyKVServer(s, new TinyKVServer.ServerConfig(0, 256, 1000, 1000, null, false, 2))) {
            srv.start();
            String url = "http://localhost:" + srv.getPort();

            // Request 1: allowed
            HttpResponse<String> r1 = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/k1")).PUT(HttpRequest.BodyPublishers.ofString("v1")).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(r1.statusCode()).isEqualTo(200);

            // Request 2: allowed
            HttpResponse<String> r2 = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/k2")).PUT(HttpRequest.BodyPublishers.ofString("v2")).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(r2.statusCode()).isEqualTo(200);

            // Request 3: rate limit exceeded (429)
            HttpResponse<String> r3 = client.send(
                    HttpRequest.newBuilder(URI.create(url + "/api/v1/keys/k3")).PUT(HttpRequest.BodyPublishers.ofString("v3")).build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(r3.statusCode()).isEqualTo(429);
            assertThat(r3.body()).contains("Too Many Requests");
            assertThat(r3.headers().firstValue("Retry-After")).isPresent().contains("60");
        }
    }
}
