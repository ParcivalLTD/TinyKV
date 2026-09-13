package io.tinykv;

/**
 * Durability and fsync synchronization policy for writes.
 */
public enum SyncPolicy {
    /**
     * fsync called synchronously on every write. Safest, lower throughput.
     */
    IMMEDIATE,

    /**
     * Concurrent writes are coalesced into batched fsync calls. High IOPS + strict durability.
     */
    GROUP_COMMIT,

    /**
     * Flush delegated to OS page cache. Maximum throughput.
     */
    ASYNC_OS
}
