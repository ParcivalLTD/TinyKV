package io.tinykv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DurabilityTest {

    @Test
    @DisplayName("Multiple concurrent worker threads under GROUP_COMMIT persist all records durably")
    void testConcurrentGroupCommitDurability(@TempDir Path tempDir) throws Exception {
        Config config = Config.builder(tempDir)
                .maxSegmentSizeBytes(64 * 1024)
                .syncPolicy(SyncPolicy.GROUP_COMMIT)
                .groupCommitIntervalMs(1)
                .maxGroupCommitBatch(64)
                .build();

        int threads = 4;
        int opsPerThread = 100;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        try (KVStore store = KVStore.open(config)) {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                exec.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            store.put("k:" + tid + ":" + i, "v:" + tid + ":" + i);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        exec.shutdown();

        // Reopen and verify
        try (KVStore store = KVStore.open(config)) {
            assertThat(store.count()).isEqualTo(threads * opsPerThread);
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < opsPerThread; i++) {
                    assertThat(store.get("k:" + t + ":" + i)).contains("v:" + t + ":" + i);
                }
            }
        }
    }
}
