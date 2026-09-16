package io.tinykv;

import io.tinykv.Record.Key;
import io.tinykv.Record.LogRecord;
import io.tinykv.StorageException.CorruptedRecordException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrashRecoveryTest {

    @Test
    @DisplayName("Clean restart recovers 100% of committed keys")
    void testCleanShutdownRecovery(@TempDir Path tempDir) throws IOException {
        try (KVStore store = KVStore.open(tempDir)) {
            for (int i = 0; i < 50; i++) store.put("k:" + i, "v:" + i);
        }

        try (KVStore store = KVStore.open(tempDir)) {
            assertThat(store.count()).isEqualTo(50);
            for (int i = 0; i < 50; i++) {
                assertThat(store.get("k:" + i)).contains("v:" + i);
            }
        }
    }

    @Test
    @DisplayName("Torn header at tail of active segment is auto-truncated and pre-crash records recovered")
    void testTornHeaderAutoTruncation(@TempDir Path tempDir) throws IOException {
        try (KVStore store = KVStore.open(tempDir)) {
            for (int i = 0; i < 10; i++) store.put("canary:" + i, "data_" + i);
        }

        Path latest = findLatest(tempDir);
        long validSize = Files.size(latest);

        // Inject incomplete 8-byte header
        try (FileChannel ch = FileChannel.open(latest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(new byte[]{0x4B, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07}));
            ch.force(true);
        }

        assertThat(Files.size(latest)).isGreaterThan(validSize);

        // Reopen: should auto-truncate back to validSize and recover all 10 keys
        try (KVStore store = KVStore.open(tempDir)) {
            assertThat(store.count()).isEqualTo(10);
            for (int i = 0; i < 10; i++) {
                assertThat(store.get("canary:" + i)).contains("data_" + i);
            }
        }

        assertThat(Files.size(latest)).isEqualTo(validSize);
    }

    @Test
    @DisplayName("Torn payload at tail is auto-truncated")
    void testTornPayloadAutoTruncation(@TempDir Path tempDir) throws IOException {
        try (KVStore store = KVStore.open(tempDir)) {
            store.put("valid:1", "data_1");
        }

        Path latest = findLatest(tempDir);
        long validSize = Files.size(latest);

        // Append incomplete record payload
        LogRecord incomplete = LogRecord.put(Key.of("interrupted"), "long_payload".getBytes(StandardCharsets.UTF_8), System.currentTimeMillis());
        ByteBuffer enc = RecordCodec.encode(incomplete);
        byte[] half = new byte[enc.remaining() - 5];
        enc.get(half);

        try (FileChannel ch = FileChannel.open(latest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(half));
            ch.force(true);
        }

        try (KVStore store = KVStore.open(tempDir)) {
            assertThat(store.count()).isEqualTo(1);
            assertThat(store.get("valid:1")).contains("data_1");
            assertThat(store.get("interrupted")).isEmpty();
        }

        assertThat(Files.size(latest)).isEqualTo(validSize);
    }

    @Test
    @DisplayName("Historical sealed segment corruption throws CorruptedRecordException")
    void testHistoricalCorruptionThrows(@TempDir Path tempDir) throws IOException {
        Config config = Config.builder(tempDir).maxSegmentSizeBytes(512).syncPolicy(SyncPolicy.ASYNC_OS).build();
        try (KVStore store = KVStore.open(config)) {
            for (int i = 0; i < 30; i++) store.put("k:" + i, "val_payload_" + i);
        }

        Path seg1 = tempDir.resolve(Segment.fileNameFor(1));
        assertThat(Files.exists(seg1)).isTrue();

        // Corrupt historical segment byte
        try (FileChannel ch = FileChannel.open(seg1, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[]{(byte) 0xFF}), 30);
            ch.force(true);
        }

        assertThatThrownBy(() -> KVStore.open(config))
                .isInstanceOf(CorruptedRecordException.class)
                .hasMessageContaining("Fatal data corruption in segment 1");
    }

    private Path findLatest(Path dir) throws IOException {
        Path latest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.data")) {
            for (Path p : stream) {
                if (latest == null || p.getFileName().toString().compareTo(latest.getFileName().toString()) > 0) {
                    latest = p;
                }
            }
        }
        return latest;
    }
}
