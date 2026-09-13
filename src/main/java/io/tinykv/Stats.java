package io.tinykv;

/**
 * Snapshot of database size, active keys, and reclaimable dead space.
 */
public record Stats(
        long activeKeys,
        long totalSegments,
        long totalDiskBytes,
        long activeDataBytes,
        long deadDataBytes,
        double deadSpacePercent
) {
    public static Stats of(long activeKeys, long totalSegments, long totalDiskBytes, long activeDataBytes) {
        long dead = Math.max(0, totalDiskBytes - activeDataBytes);
        double pct = totalDiskBytes > 0 ? ((double) dead / totalDiskBytes) * 100.0 : 0.0;
        return new Stats(activeKeys, totalSegments, totalDiskBytes, activeDataBytes, dead, pct);
    }
}
