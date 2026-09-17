package io.tinykv.http;

import io.tinykv.Config;
import io.tinykv.KVStore;
import io.tinykv.SyncPolicy;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main daemon entrypoint for containerized TinyKV HTTP service.
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("TINYKV_PORT", "8080"));
        String dataDirStr = System.getenv().getOrDefault("TINYKV_DATA_DIR", "/data");
        String syncPolicyStr = System.getenv().getOrDefault("TINYKV_SYNC_POLICY", "GROUP_COMMIT");
        long maxSegMb = Long.parseLong(System.getenv().getOrDefault("TINYKV_MAX_SEGMENT_MB", "16"));
        int maxKeyBytes = Integer.parseInt(System.getenv().getOrDefault("TINYKV_MAX_KEY_BYTES", "256"));
        int maxValueBytes = Integer.parseInt(System.getenv().getOrDefault("TINYKV_MAX_VALUE_BYTES", "65536"));
        long maxKeys = Long.parseLong(System.getenv().getOrDefault("TINYKV_MAX_KEYS", "10000"));
        String writeToken = System.getenv("TINYKV_WRITE_TOKEN");
        if (writeToken != null && writeToken.isBlank()) writeToken = null;
        boolean readOnly = Boolean.parseBoolean(System.getenv().getOrDefault("TINYKV_READ_ONLY", "false"));
        int rateLimitRpm = Integer.parseInt(System.getenv().getOrDefault("TINYKV_RATE_LIMIT_RPM", "0"));

        SyncPolicy syncPolicy = switch (syncPolicyStr.toUpperCase()) {
            case "IMMEDIATE" -> SyncPolicy.IMMEDIATE;
            case "ASYNC_OS" -> SyncPolicy.ASYNC_OS;
            default -> SyncPolicy.GROUP_COMMIT;
        };

        Path dataDir = Path.of(dataDirStr);

        System.out.println("===============================================================");
        System.out.println("             TINYKV HTTP SERVICE (CONTAINERIZED)               ");
        System.out.println("===============================================================");
        System.out.println("Port:           " + port);
        System.out.println("Data Dir:       " + dataDir.toAbsolutePath());
        System.out.println("Sync Policy:    " + syncPolicy);
        System.out.println("Max Segment:    " + maxSegMb + " MB");
        System.out.println("Max Key Bytes:  " + maxKeyBytes);
        System.out.println("Max Val Bytes:  " + maxValueBytes);
        System.out.println("Max Keys:       " + maxKeys);
        System.out.println("Read-Only:      " + readOnly);
        System.out.println("Write Token:    " + (writeToken != null ? "[CONFIGURED]" : "[NONE]"));
        System.out.println("Rate Limit RPM: " + rateLimitRpm);
        System.out.println("Endpoints:");
        System.out.println("  - GET    /healthz");
        System.out.println("  - GET    /readyz");
        System.out.println("  - GET    /api/v1/keys/{key}");
        System.out.println("  - PUT    /api/v1/keys/{key}");
        System.out.println("  - DELETE /api/v1/keys/{key}");
        System.out.println("  - GET    /api/v1/stats");
        System.out.println("  - POST   /api/v1/compact");
        System.out.println("===============================================================\n");

        Config config = Config.builder(dataDir)
                .maxSegmentSizeBytes(maxSegMb * 1024 * 1024)
                .syncPolicy(syncPolicy)
                .build();

        KVStore store = KVStore.open(config);
        TinyKVServer.ServerConfig serverConfig = new TinyKVServer.ServerConfig(
                port, maxKeyBytes, maxValueBytes, maxKeys, writeToken, readOnly, rateLimitRpm
        );
        TinyKVServer server = new TinyKVServer(store, serverConfig);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping TinyKV HTTP server...");
            try {
                server.close();
                store.close();
                log.info("TinyKV cleanly stopped.");
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }, "shutdown-hook"));

        // Keep main thread alive
        Thread.currentThread().join();
    }
}
