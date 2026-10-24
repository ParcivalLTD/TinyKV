package io.tinykv.demo;

import io.tinykv.Config;
import io.tinykv.KVStore;
import io.tinykv.SyncPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable-write throughput: one fsync per write (IMMEDIATE) against batched fsync
 * (GROUP_COMMIT), at 1 to 64 concurrent writers.
 *
 * Each run writes for a fixed time on its own fresh store, after a short warm-up,
 * and reports throughput plus p50 and p99 latency per put. Results depend heavily on
 * the disk and file system, so run it on the machine you quote numbers from.
 *
 * Usage: ./gradlew runWriteBenchmark  (optional args: seconds-per-run, value-bytes)
 */
public final class WriteBenchmark {

    private static final int[] WRITERS = {1, 2, 4, 8, 16, 32, 64};

    private record Result(double opsPerSec, double p50Micros, double p99Micros) {}

    private WriteBenchmark() {}

    public static void main(String[] args) throws Exception {
        double seconds = args.length > 0 ? Double.parseDouble(args[0]) : 3.0;
        int valueBytes = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        byte[] value = new byte[valueBytes];
        Arrays.fill(value, (byte) 'x');

        System.out.printf(Locale.ROOT,
                "TinyKV durable-write benchmark: %.1f s per run, %d-byte values, %s%n%n",
                seconds, valueBytes, System.getProperty("os.name"));
        System.out.printf(Locale.ROOT, "%8s | %14s %10s %10s | %14s %10s %10s | %8s%n",
                "writers", "IMMEDIATE ops/s", "p50 us", "p99 us",
                "GROUP ops/s", "p50 us", "p99 us", "speed-up");
        System.out.println("-".repeat(104));

        for (int writers : WRITERS) {
            Result immediate = run(SyncPolicy.IMMEDIATE, writers, seconds, value);
            Result group = run(SyncPolicy.GROUP_COMMIT, writers, seconds, value);
            System.out.printf(Locale.ROOT, "%8d | %14.0f %10.0f %10.0f | %14.0f %10.0f %10.0f | %7.1fx%n",
                    writers, immediate.opsPerSec(), immediate.p50Micros(), immediate.p99Micros(),
                    group.opsPerSec(), group.p50Micros(), group.p99Micros(),
                    group.opsPerSec() / immediate.opsPerSec());
        }
    }

    private static Result run(SyncPolicy policy, int writers, double seconds, byte[] value) throws Exception {
        Path dir = Files.createTempDirectory("tinykv_bench_");
        try {
            Config config = Config.builder(dir).syncPolicy(policy).build();
            try (KVStore store = KVStore.open(config)) {
                writeFor(store, writers, 0.5, value, null);
                List<long[]> latencies = new ArrayList<>();
                long ops = writeFor(store, writers, seconds, value, latencies);
                return new Result(ops / seconds, percentile(latencies, 0.50), percentile(latencies, 0.99));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    /** Writes from {@code writers} threads for {@code seconds}; returns the number of puts. */
    private static long writeFor(KVStore store, int writers, double seconds, byte[] value,
                                 List<long[]> latencySink) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        long[] counts = new long[writers];
        long[][] samples = new long[writers][];
        List<Thread> threads = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int id = w;
            Thread t = new Thread(() -> {
                long[] lat = new long[1 << 16];
                int n = 0;
                long i = 0;
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (running.get()) {
                    byte[] key = ("w" + id + ":" + (i++)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    long t0 = System.nanoTime();
                    store.put(key, value);
                    long dt = System.nanoTime() - t0;
                    if (n == lat.length) lat = Arrays.copyOf(lat, lat.length * 2);
                    lat[n++] = dt;
                }
                counts[id] = i;
                samples[id] = Arrays.copyOf(lat, n);
            }, "bench-writer-" + w);
            threads.add(t);
            t.start();
        }

        start.countDown();
        Thread.sleep((long) (seconds * 1000));
        running.set(false);
        for (Thread t : threads) t.join();

        long total = 0;
        for (long c : counts) total += c;
        if (latencySink != null) {
            for (long[] s : samples) if (s != null) latencySink.add(s);
        }
        return total;
    }

    private static double percentile(List<long[]> perThread, double q) {
        List<Long> all = new ArrayList<>();
        for (long[] s : perThread) for (long v : s) all.add(v);
        if (all.isEmpty()) return 0;
        Collections.sort(all);
        int idx = (int) Math.min(all.size() - 1, Math.round(q * (all.size() - 1)));
        return all.get(idx) / 1000.0;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Collections.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort cleanup of the temp directory
                }
            });
        }
    }
}
