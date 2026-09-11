package io.tinykv;

import io.tinykv.Record.Key;
import io.tinykv.Record.LogRecord;
import io.tinykv.Record.Type;
import io.tinykv.StorageException.CorruptedRecordException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordCodecTest {

    @Test
    @DisplayName("Encode and decode roundtrip")
    void testEncodeDecodeRoundtrip() {
        LogRecord rec = LogRecord.put(Key.of("foo"), "bar".getBytes(StandardCharsets.UTF_8), 123456789L);
        ByteBuffer buf = RecordCodec.encode(rec);
        LogRecord decoded = RecordCodec.decode(buf);

        assertThat(decoded.key()).isEqualTo(Key.of("foo"));
        assertThat(decoded.value()).isEqualTo("bar".getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.timestamp()).isEqualTo(123456789L);
        assertThat(decoded.type()).isEqualTo(Type.PUT);
    }

    @Test
    @DisplayName("Decode detects corrupted payload byte via CRC mismatch")
    void testDetectsCrcMismatch() {
        LogRecord rec = LogRecord.put(Key.of("sample"), "data".getBytes(StandardCharsets.UTF_8), 100L);
        ByteBuffer buf = RecordCodec.encode(rec);
        byte[] arr = buf.array();
        arr[arr.length - 1] ^= (byte) 0xFF; // corrupt last byte

        assertThatThrownBy(() -> RecordCodec.decode(ByteBuffer.wrap(arr)))
                .isInstanceOf(CorruptedRecordException.class)
                .hasMessageContaining("CRC mismatch");
    }

    @Test
    @DisplayName("FileChannel read detects clean EOF")
    void testCleanEof(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("empty.data");
        Files.createFile(f);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            RecordCodec.ReadResult res = RecordCodec.readRecordAt(ch, 0);
            assertThat(res).isInstanceOf(RecordCodec.ReadResult.CleanEof.class);
        }
    }

    @Test
    @DisplayName("FileChannel read detects torn header at EOF")
    void testTornHeader(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("torn.data");
        Files.write(f, new byte[]{0x4B, 1, 2, 3, 4, 5}); // 6 bytes instead of 22
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            RecordCodec.ReadResult res = RecordCodec.readRecordAt(ch, 0);
            assertThat(res).isInstanceOf(RecordCodec.ReadResult.Corrupted.class);
            RecordCodec.ReadResult.Corrupted c = (RecordCodec.ReadResult.Corrupted) res;
            assertThat(c.isTornTail()).isTrue();
        }
    }
}
