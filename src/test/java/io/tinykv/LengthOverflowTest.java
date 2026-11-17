package io.tinykv;

import io.tinykv.StorageException.CorruptedRecordException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the 32-bit overflow of KEY_LEN + VAL_LEN.
 *
 * Before the fix, HEADER_SIZE + kLen + vLen and kLen + vLen were computed in int. With
 * KEY_LEN = Integer.MAX_VALUE and VAL_LEN = 1 the sum wraps to a negative number, so the
 * "enough bytes left?" check passed, and the code then tried to allocate a buffer of that
 * size: decode() threw OutOfMemoryError and readRecordAt() threw
 * IllegalArgumentException("capacity < 0"), so KVStore.open failed. Both must now report
 * a corrupted record instead.
 */
class LengthOverflowTest {

    /** A 22-byte header with a valid magic byte and the given length fields. */
    private static byte[] header(int kLen, int vLen) {
        return ByteBuffer.allocate(RecordCodec.HEADER_SIZE)
                .put(RecordCodec.DATA_MAGIC)
                .putInt(0)              // CRC32: never reached
                .putLong(0L)            // TIMESTAMP
                .put((byte) 1)          // TYPE = PUT
                .putInt(kLen)
                .putInt(vLen)
                .array();
    }

    @Test
    @DisplayName("readRecordAt: KEY_LEN = MAX_VALUE, VAL_LEN = 1 is reported as a record running past EOF")
    void readRecordAtOverflowingLengths(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("overflow.data");
        Files.write(f, header(Integer.MAX_VALUE, 1));
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            RecordCodec.ReadResult res = RecordCodec.readRecordAt(ch, 0);
            assertThat(res).isInstanceOf(RecordCodec.ReadResult.Corrupted.class);
            // The declared record (22 + 2^31 bytes) runs past EOF, which readRecordAt
            // classifies as a torn tail, as it does for any length that runs past EOF.
            assertThat(((RecordCodec.ReadResult.Corrupted) res).isTornTail()).isTrue();
        }
    }

    @Test
    @DisplayName("decode: KEY_LEN = MAX_VALUE, VAL_LEN = 1 throws CorruptedRecordException")
    void decodeOverflowingLengths() {
        ByteBuffer buf = ByteBuffer.wrap(header(Integer.MAX_VALUE, 1));
        assertThatThrownBy(() -> RecordCodec.decode(buf))
                .isInstanceOf(CorruptedRecordException.class)
                .hasMessageContaining("Invalid payload lengths");
    }

    /**
     * The explicit bound check. In a file larger than 2 GiB, a header with
     * KEY_LEN = MAX_VALUE and VAL_LEN = 0 declares 22 + 2^31 - 1 bytes, all of which exist,
     * so the EOF check alone cannot reject it; but that length does not fit the int-sized
     * buffers readRecordAt allocates (before the fix: ByteBuffer.allocate(MAX_VALUE) threw
     * OutOfMemoryError). The file is sparse, so it takes almost no disk space.
     */
    @Test
    @DisplayName("readRecordAt: a record longer than Integer.MAX_VALUE in a >2 GiB file is corrupted, not torn")
    void readRecordAtLengthAboveIntRange(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("huge.data");
        long declaredTotal = RecordCodec.HEADER_SIZE + (long) Integer.MAX_VALUE;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(header(Integer.MAX_VALUE, 0)), 0);
            ch.write(ByteBuffer.wrap(new byte[]{0}), declaredTotal - 1); // file size = declaredTotal
        }
        assertThat(Files.size(f)).isEqualTo(declaredTotal);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            RecordCodec.ReadResult res = RecordCodec.readRecordAt(ch, 0);
            assertThat(res).isInstanceOf(RecordCodec.ReadResult.Corrupted.class);
            assertThat(((RecordCodec.ReadResult.Corrupted) res).isTornTail()).isFalse();
        }
    }

    @Test
    @DisplayName("KVStore.open recovers when the tail segment ends with an overflowing header")
    void storeOpensWithOverflowingHeaderAtTail(@TempDir Path dir) throws IOException {
        try (KVStore store = KVStore.open(dir)) {
            store.put("k", "v");
        }
        Path segment = dir.resolve(Segment.fileNameFor(1));
        long validSize = Files.size(segment);
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(header(Integer.MAX_VALUE, 1)));
        }

        try (KVStore store = KVStore.open(dir)) {
            assertThat(store.get("k")).contains("v");
            assertThat(store.count()).isEqualTo(1);
        }
        assertThat(Files.size(segment)).isEqualTo(validSize);
    }
}
