package io.tinykv;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs compaction concurrently with deletes and reads.
 *
 * Guards two races that compaction used to have: a key deleted while compaction copied it
 * could come back (in memory and after a restart), and reads could fail with "Unknown segment
 * id" because index entries pointed at the new segment before it was registered.
 */
class CompactionRaceTest {

    private static final int KEYS = 20_000;
    private static final int ROUNDS = 10;

    @Test
    @DisplayName("Keys deleted while compaction runs stay deleted")
    void deletedKeysStayDeletedDuringCompaction(@TempDir Path dir) throws Exception {
        Config config = Config.builder(dir)
                .maxSegmentSizeBytes(256 * 1024)
                .syncPolicy(SyncPolicy.ASYNC_OS)
                .build();

        List<String> resurrected = new ArrayList<>();
        List<String> resurrectedAfterReopen = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            String prefix = "r" + round + ":";
            try (KVStore store = KVStore.open(config)) {
                for (int i = 0; i < KEYS; i++) {
                    store.put(prefix + i, "value-" + i);
                }

                CountDownLatch start = new CountDownLatch(1);
                Thread compactor = new Thread(() -> {
                    awaitQuietly(start);
                    store.compact();
                });
                Thread deleter = new Thread(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < KEYS; i += 2) {
                        store.delete(prefix + i);
                    }
                });
                compactor.start();
                deleter.start();
                start.countDown();
                compactor.join();
                deleter.join();

                for (int i = 0; i < KEYS; i += 2) {
                    if (store.get(prefix + i).isPresent()) resurrected.add(prefix + i);
                }
            }

            try (KVStore store = KVStore.open(config)) {
                for (int i = 0; i < KEYS; i += 2) {
                    if (store.get(prefix + i).isPresent()) resurrectedAfterReopen.add(prefix + i);
                }
            }
        }

        System.out.printf("Deleted keys visible again in memory: %d of %d%n",
                resurrected.size(), ROUNDS * KEYS / 2);
        System.out.printf("Deleted keys visible again after reopen: %d of %d%n",
                resurrectedAfterReopen.size(), ROUNDS * KEYS / 2);

        assertThat(resurrected)
                .as("deleted keys that came back in memory after a concurrent compaction")
                .isEmpty();
        assertThat(resurrectedAfterReopen)
                .as("deleted keys that came back after reopening the store")
                .isEmpty();
    }

    @Test
    @DisplayName("Reads never fail while compaction replaces segments")
    void readsDuringCompactionNeverFail(@TempDir Path dir) throws Exception {
        Config config = Config.builder(dir)
                .maxSegmentSizeBytes(256 * 1024)
                .syncPolicy(SyncPolicy.ASYNC_OS)
                .build();

        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < KEYS; i++) store.put("k" + i, "v" + i);
            for (int i = 0; i < KEYS; i++) store.put("k" + i, "w" + i);

            AtomicBoolean running = new AtomicBoolean(true);
            Thread reader = new Thread(() -> {
                int i = 0;
                while (running.get()) {
                    try {
                        store.get("k" + (i++ % KEYS));
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }
            });
            reader.start();
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < KEYS; i += 10) store.put("k" + i, "x" + round);
                store.compact();
            }
            running.set(false);
            reader.join();
        }

        Throwable first = errors.peek();
        System.out.printf("Reads that threw during compaction: %d%s%n", errors.size(),
                first == null ? "" : " (first: " + first + ")");

        assertThat(errors).as("exceptions thrown by reads during compaction").isEmpty();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
