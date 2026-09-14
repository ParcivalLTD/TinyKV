package io.tinykv;

import io.tinykv.StorageException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces concurrent fsync requests into batched group commits.
 */
public final class GroupCommit implements AutoCloseable {

    private record Request(Segment segment, CompletableFuture<Void> future) {}

    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final int maxBatch;
    private final long intervalMs;
    private volatile boolean running = true;

    public GroupCommit(int maxBatch, long intervalMs) {
        this.maxBatch = maxBatch;
        this.intervalMs = intervalMs;
        this.thread = new Thread(this::runLoop, "tinykv-group-commit");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    public CompletableFuture<Void> submit(Segment segment) {
        if (!running) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            f.completeExceptionally(new StorageException("GroupCommit coordinator is closed"));
            return f;
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        queue.offer(new Request(segment, f));
        return f;
    }

    private void runLoop() {
        List<Request> batch = new ArrayList<>(maxBatch);
        while (running || !queue.isEmpty()) {
            try {
                Request first = queue.poll(intervalMs, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, maxBatch - 1);
                    flush(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                if (!running) break;
            }
        }
        if (!queue.isEmpty()) {
            queue.drainTo(batch);
            flush(batch);
        }
    }

    private void flush(List<Request> batch) {
        if (batch.isEmpty()) return;
        Set<Segment> segments = new HashSet<>();
        for (Request r : batch) segments.add(r.segment());

        Throwable err = null;
        for (Segment s : segments) {
            try {
                s.force(false);
            } catch (IOException e) {
                err = e;
                break;
            }
        }

        for (Request r : batch) {
            if (err != null) {
                r.future().completeExceptionally(new StorageException("GroupCommit fsync failed", err));
            } else {
                r.future().complete(null);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(2000);
        } catch (InterruptedException ignored) {}
    }
}
