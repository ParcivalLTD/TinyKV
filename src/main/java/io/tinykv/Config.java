package io.tinykv;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration options for TinyKV storage engine.
 */
public final class Config {

    public static final long DEFAULT_MAX_SEGMENT_SIZE = 16 * 1024 * 1024; // 16 MB
    public static final SyncPolicy DEFAULT_SYNC_POLICY = SyncPolicy.GROUP_COMMIT;

    private final Path dataDir;
    private final long maxSegmentSizeBytes;
    private final SyncPolicy syncPolicy;
    private final long groupCommitIntervalMs;
    private final int maxGroupCommitBatch;

    private Config(Builder b) {
        this.dataDir = Objects.requireNonNull(b.dataDir, "dataDir cannot be null");
        this.maxSegmentSizeBytes = b.maxSegmentSizeBytes;
        this.syncPolicy = Objects.requireNonNull(b.syncPolicy, "syncPolicy cannot be null");
        this.groupCommitIntervalMs = b.groupCommitIntervalMs;
        this.maxGroupCommitBatch = b.maxGroupCommitBatch;
    }

    public static Config of(Path dataDir) {
        return builder(dataDir).build();
    }

    public static Builder builder(Path dataDir) {
        return new Builder(dataDir);
    }

    public Path getDataDir() { return dataDir; }
    public long getMaxSegmentSizeBytes() { return maxSegmentSizeBytes; }
    public SyncPolicy getSyncPolicy() { return syncPolicy; }
    public long getGroupCommitIntervalMs() { return groupCommitIntervalMs; }
    public int getMaxGroupCommitBatch() { return maxGroupCommitBatch; }

    public static final class Builder {
        private final Path dataDir;
        private long maxSegmentSizeBytes = DEFAULT_MAX_SEGMENT_SIZE;
        private SyncPolicy syncPolicy = DEFAULT_SYNC_POLICY;
        private long groupCommitIntervalMs = 2;
        private int maxGroupCommitBatch = 128;

        public Builder(Path dataDir) {
            this.dataDir = dataDir;
        }

        public Builder maxSegmentSizeBytes(long bytes) {
            this.maxSegmentSizeBytes = Math.max(1024, bytes);
            return this;
        }

        public Builder syncPolicy(SyncPolicy policy) {
            this.syncPolicy = policy;
            return this;
        }

        public Builder groupCommitIntervalMs(long ms) {
            this.groupCommitIntervalMs = ms;
            return this;
        }

        public Builder maxGroupCommitBatch(int batch) {
            this.maxGroupCommitBatch = batch;
            return this;
        }

        public Config build() {
            return new Config(this);
        }
    }
}
