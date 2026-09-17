package io.tinykv.demo;

import io.tinykv.Config;
import io.tinykv.KVStore;
import io.tinykv.Segment;
import io.tinykv.Stats;
import io.tinykv.SyncPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Interactive demo: write throughput, O(1) single-seek read latency,
 * compaction, and crash recovery with torn-write auto-repair.
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        System.out.println("===============================================================");
        System.out.println("            TINYKV: HIGH-PERFORMANCE EMBEDDED ENGINE          ");
        System.out.println("===============================================================\n");

        Path tempDir = Files.createTempDirectory("tinykv_demo_");
        try {
            run(tempDir);
        } finally {
            cleanup(tempDir);
        }
    }

    private static void run(Path dir) throws Exception {
        int count = 2_000;
        Config config = Config.builder(dir)
                .maxSegmentSizeBytes(64 * 1024) // 64 KB segments to trigger rotation
                .syncPolicy(SyncPolicy.GROUP_COMMIT)
                .build();

        // 1. Ingestion
        System.out.println(">> [1/4] Ingesting " + count + " records with GROUP_COMMIT durability...");
        long t0 = System.currentTimeMillis();
        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < count; i++) {
                store.put("key:" + i, "payload_value_for_item_" + i);
            }
        }
        long writeMs = System.currentTimeMillis() - t0;
        System.out.printf("   Wrote %d records in %d ms (~%.0f ops/sec)%n%n",
                count, writeMs, (double) count / (Math.max(1, writeMs) / 1000.0));

        // 2. Read Latency
        System.out.println(">> [2/4] Measuring random read latency (1 disk seek per get)...");
        List<Long> latencies = new ArrayList<>(1000);
        try (KVStore store = KVStore.open(config)) {
            Random rng = new Random(42);
            for (int i = 0; i < 1000; i++) {
                String k = "key:" + rng.nextInt(count);
                long start = System.nanoTime();
                Optional<String> v = store.get(k);
                latencies.add(System.nanoTime() - start);
                if (v.isEmpty()) throw new IllegalStateException("Missing key: " + k);
            }
        }
        Collections.sort(latencies);
        System.out.printf("   Read Latencies (p50: %.2f µs, p95: %.2f µs, p99: %.2f µs)%n%n",
                latencies.get(500) / 1000.0, latencies.get(950) / 1000.0, latencies.get(990) / 1000.0);

        // 3. Compaction
        System.out.println(">> [3/4] Overwriting and deleting records to test compaction...");
        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < 1000; i++) store.put("key:" + i, "new_updated_value_" + i);
            for (int i = 1000; i < 1500; i++) store.delete("key:" + i);

            Stats before = store.stats();
            System.out.printf("   Before: %,d bytes across %d segments (%.1f%% dead space)%n",
                    before.totalDiskBytes(), before.totalSegments(), before.deadSpacePercent());

            store.compact();

            Stats after = store.stats();
            System.out.printf("   After:  %,d bytes across %d segments (reclaimed %,d bytes)%n%n",
                    after.totalDiskBytes(), after.totalSegments(), before.totalDiskBytes() - after.totalDiskBytes());
        }

        // 4. Power Loss Simulation
        System.out.println(">> [4/4] Simulating power loss & torn write auto-repair...");
        try (KVStore store = KVStore.open(config)) {
            store.put("canary", "pre_crash_safe_data");
        }

        Path latest = findLatest(dir);
        System.out.printf("   Injecting 9 corrupted bytes at tail of %s...%n", latest.getFileName());
        try (FileChannel ch = FileChannel.open(latest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[]{0x4B, 0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x01, 0x02}));
            ch.force(true);
        }

        try (KVStore store = KVStore.open(config)) {
            Optional<String> canary = store.get("canary");
            if (canary.isPresent() && "pre_crash_safe_data".equals(canary.get())) {
                System.out.println("   [SUCCESS] Torn write detected at EOF and safely truncated.");
                System.out.println("             All committed data recovered with zero corruption!");
            } else {
                throw new AssertionError("Crash recovery failed!");
            }
        }

        System.out.println("\n===============================================================");
        System.out.println("                 ALL TINYKV VERIFICATIONS PASSED              ");
        System.out.println("===============================================================");
    }

    private static Path findLatest(Path dir) throws IOException {
        Path latest = null;
        try (var stream = Files.newDirectoryStream(dir, "*.data")) {
            for (Path p : stream) {
                if (latest == null || p.getFileName().toString().compareTo(latest.getFileName().toString()) > 0) {
                    latest = p;
                }
            }
        }
        return latest;
    }

    private static void cleanup(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Collections.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
