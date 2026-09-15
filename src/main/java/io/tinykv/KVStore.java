package io.tinykv;

import io.tinykv.Record.IndexEntry;
import io.tinykv.Record.Key;
import io.tinykv.Record.LogRecord;
import io.tinykv.StorageException.StorageClosedException;
import io.tinykv.StorageException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Embedded crash-safe Key-Value store with Write-Ahead Logging (WAL).
 *
 * <pre>{@code
 * try (KVStore store = KVStore.open(Path.of("./data"))) {
 *     store.put("name", "Julian");
 *     Optional<String> val = store.get("name");
 *     store.delete("name");
 * }
 * }</pre>
 */
public interface KVStore extends AutoCloseable {

    static KVStore open(Path dataDir) throws IOException {
        return open(Config.of(dataDir));
    }

    static KVStore open(Config config) throws IOException {
        return new Engine(config);
    }

    void put(byte[] key, byte[] value);

    default void put(String key, String value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    Optional<byte[]> get(byte[] key);

    default Optional<String> get(String key) {
        Objects.requireNonNull(key);
        return get(key.getBytes(StandardCharsets.UTF_8)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    boolean delete(byte[] key);

    default boolean delete(String key) {
        Objects.requireNonNull(key);
        return delete(key.getBytes(StandardCharsets.UTF_8));
    }

    boolean contains(byte[] key);

    default boolean contains(String key) {
        Objects.requireNonNull(key);
        return contains(key.getBytes(StandardCharsets.UTF_8));
    }

    long count();

    void sync();

    void compact();

    Stats stats();

    @Override
    void close();

    /**
     * Bitcask storage engine implementation.
     */
    final class Engine implements KVStore {
        private final Config config;
        private final Index index;
        private final SegmentManager segmentManager;
        private final Compactor compactor;
        private final GroupCommit groupCommit;
        private final ReentrantLock writeLock = new ReentrantLock();
        private volatile boolean closed = false;

        private Engine(Config config) throws IOException {
            this.config = config;
            this.index = new Index();
            this.segmentManager = new SegmentManager(config.getDataDir(), config.getMaxSegmentSizeBytes(), this.index);
            this.segmentManager.initialize();
            this.compactor = new Compactor(config.getDataDir(), this.segmentManager, this.index);
            this.groupCommit = (config.getSyncPolicy() == SyncPolicy.GROUP_COMMIT)
                    ? new GroupCommit(config.getMaxGroupCommitBatch(), config.getGroupCommitIntervalMs())
                    : null;
        }

        @Override
        public void put(byte[] keyBytes, byte[] valueBytes) {
            checkOpen();
            Key key = Key.of(keyBytes);
            long ts = System.currentTimeMillis();
            LogRecord record = LogRecord.put(key, valueBytes, ts);
            ByteBuffer encoded = RecordCodec.encode(record);

            Segment active;
            CompletableFuture<Void> commitFuture = null;

            writeLock.lock();
            try {
                if (segmentManager.shouldRotate()) {
                    segmentManager.rotateActiveSegment();
                }
                active = segmentManager.getActiveSegment();
                Segment.AppendResult app = active.append(encoded);
                index.put(key, new IndexEntry(active.getSegmentId(), app.offset(), app.length(), ts));

                if (config.getSyncPolicy() == SyncPolicy.IMMEDIATE) {
                    active.force(false);
                } else if (config.getSyncPolicy() == SyncPolicy.GROUP_COMMIT) {
                    commitFuture = groupCommit.submit(active);
                }
            } catch (IOException e) {
                throw new StorageException("Failed to put key: " + key, e);
            } finally {
                writeLock.unlock();
            }

            if (commitFuture != null) {
                commitFuture.join();
            }
        }

        @Override
        public Optional<byte[]> get(byte[] keyBytes) {
            checkOpen();
            Key key = Key.of(keyBytes);
            Optional<IndexEntry> entryOpt = index.get(key);
            if (entryOpt.isEmpty()) return Optional.empty();

            IndexEntry entry = entryOpt.get();
            try {
                Segment seg = segmentManager.getSegment(entry.segmentId());
                LogRecord rec = seg.readRecord(entry.offset(), entry.length());
                if (rec.isTombstone()) return Optional.empty();
                return Optional.of(rec.value());
            } catch (IOException e) {
                throw new StorageException("Failed to read key: " + key, e);
            }
        }

        @Override
        public boolean delete(byte[] keyBytes) {
            checkOpen();
            Key key = Key.of(keyBytes);
            if (!index.containsKey(key)) return false;

            long ts = System.currentTimeMillis();
            LogRecord record = LogRecord.delete(key, ts);
            ByteBuffer encoded = RecordCodec.encode(record);

            CompletableFuture<Void> commitFuture = null;

            writeLock.lock();
            try {
                if (!index.containsKey(key)) return false;
                if (segmentManager.shouldRotate()) segmentManager.rotateActiveSegment();

                Segment active = segmentManager.getActiveSegment();
                active.append(encoded);
                index.remove(key, ts);

                if (config.getSyncPolicy() == SyncPolicy.IMMEDIATE) {
                    active.force(false);
                } else if (config.getSyncPolicy() == SyncPolicy.GROUP_COMMIT) {
                    commitFuture = groupCommit.submit(active);
                }
            } catch (IOException e) {
                throw new StorageException("Failed to delete key: " + key, e);
            } finally {
                writeLock.unlock();
            }

            if (commitFuture != null) commitFuture.join();
            return true;
        }

        @Override
        public boolean contains(byte[] keyBytes) {
            checkOpen();
            return index.containsKey(Key.of(keyBytes));
        }

        @Override
        public long count() {
            checkOpen();
            return index.size();
        }

        @Override
        public void sync() {
            checkOpen();
            writeLock.lock();
            try {
                segmentManager.getActiveSegment().force(false);
            } catch (IOException e) {
                throw new StorageException("Sync failed", e);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void compact() {
            checkOpen();
            writeLock.lock();
            try {
                segmentManager.rotateActiveSegment();
            } catch (IOException e) {
                throw new StorageException("Rotation before compaction failed", e);
            } finally {
                writeLock.unlock();
            }

            try {
                compactor.compact();
            } catch (IOException e) {
                throw new StorageException("Compaction failed", e);
            }
        }

        @Override
        public Stats stats() {
            checkOpen();
            long activeKeys = index.size();
            long totalSegments = segmentManager.getSealedSegments().size() + 1;
            long diskBytes = segmentManager.calculateTotalDiskSizeBytes();
            long activeBytes = 0;
            for (IndexEntry e : index.asMap().values()) activeBytes += e.length();
            return Stats.of(activeKeys, totalSegments, diskBytes, activeBytes);
        }

        private void checkOpen() {
            if (closed) throw new StorageClosedException("KVStore is closed");
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                if (groupCommit != null) groupCommit.close();
                try {
                    segmentManager.close();
                } catch (IOException ignored) {}
            }
        }
    }
}
