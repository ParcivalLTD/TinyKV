package io.tinykv;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionAndRotationTest {

    @Test
    @DisplayName("Segment rotation creates multiple segment files when size limit is exceeded")
    void testSegmentRotation(@TempDir Path tempDir) throws IOException {
        Config config = Config.builder(tempDir)
                .maxSegmentSizeBytes(1024) // 1 KB segments
                .syncPolicy(SyncPolicy.ASYNC_OS)
                .build();

        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < 40; i++) {
                store.put("key:" + i, "a_long_payload_to_trigger_segment_rotation_" + i);
            }
            Stats stats = store.stats();
            assertThat(stats.activeKeys()).isEqualTo(40);
            assertThat(stats.totalSegments()).isGreaterThan(2);

            for (int i = 0; i < 40; i++) {
                assertThat(store.get("key:" + i)).contains("a_long_payload_to_trigger_segment_rotation_" + i);
            }
        }
    }

    @Test
    @DisplayName("Compaction purges dead space and generates .hint files for fast restart")
    void testCompactionAndHintFiles(@TempDir Path tempDir) throws IOException {
        Config config = Config.builder(tempDir)
                .maxSegmentSizeBytes(1024)
                .syncPolicy(SyncPolicy.ASYNC_OS)
                .build();

        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < 30; i++) store.put("k:" + i, "v1_" + i);
            for (int i = 0; i < 20; i++) store.put("k:" + i, "v2_updated_" + i);
            for (int i = 20; i < 30; i++) store.delete("k:" + i);

            Stats before = store.stats();
            assertThat(before.deadDataBytes()).isGreaterThan(0);

            store.compact();

            Stats after = store.stats();
            assertThat(after.activeKeys()).isEqualTo(20);
            assertThat(after.totalDiskBytes()).isLessThan(before.totalDiskBytes());
        }

        // Verify .hint file exists
        List<Path> hints = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(tempDir, "*.hint")) {
            for (Path p : s) hints.add(p);
        }
        assertThat(hints).isNotEmpty();

        // Restart store and verify data loaded via hint files
        try (KVStore store = KVStore.open(config)) {
            assertThat(store.count()).isEqualTo(20);
            for (int i = 0; i < 20; i++) {
                assertThat(store.get("k:" + i)).contains("v2_updated_" + i);
            }
            for (int i = 20; i < 30; i++) {
                assertThat(store.get("k:" + i)).isEmpty();
            }
        }
    }
}
