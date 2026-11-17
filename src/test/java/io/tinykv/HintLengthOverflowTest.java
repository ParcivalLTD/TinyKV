package io.tinykv;

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

/**
 * The hint-file counterpart of LengthOverflowTest.
 *
 * readHintAt checked `size - position < HINT_HEADER_SIZE + kLen` with the sum in int. With
 * KEY_LEN = Integer.MAX_VALUE the sum wraps negative, the check passes, and
 * ByteBuffer.allocate(Integer.MAX_VALUE) throws OutOfMemoryError. SegmentManager only
 * catches Exception around hint loading, so the Error stopped KVStore.open. A bad hint file
 * must instead be reported as Corrupted, so that recovery falls back to scanning the data
 * segment.
 */
class HintLengthOverflowTest {

    /** A 37-byte hint header with a valid magic byte and the given KEY_LEN. */
    private static byte[] hintHeader(int kLen) {
        return ByteBuffer.allocate(RecordCodec.HINT_HEADER_SIZE)
                .put(RecordCodec.HINT_MAGIC)
                .putInt(0)      // CRC32: never reached
                .putLong(0L)    // TIMESTAMP
                .putInt(kLen)
                .putLong(1L)    // SEG_ID
                .putLong(0L)    // OFFSET
                .putInt(0)      // LEN
                .array();
    }

    @Test
    @DisplayName("readHintAt: KEY_LEN = MAX_VALUE is reported as corrupted")
    void readHintAtOverflowingKeyLength(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("overflow.hint");
        Files.write(f, hintHeader(Integer.MAX_VALUE));
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            assertThat(RecordCodec.readHintAt(ch, 0)).isInstanceOf(RecordCodec.HintReadResult.Corrupted.class);
        }
    }

    /**
     * The explicit bound. In a hint file larger than 2 GiB, KEY_LEN = MAX_VALUE declares
     * 37 + 2^31 - 1 bytes, all of which exist, so the EOF check alone cannot reject it; but
     * bytesRead is an int and the key buffer is int-sized (before the fix:
     * ByteBuffer.allocate(MAX_VALUE) threw OutOfMemoryError). The file is sparse.
     */
    @Test
    @DisplayName("readHintAt: a hint longer than Integer.MAX_VALUE in a >2 GiB file is corrupted")
    void readHintAtLengthAboveIntRange(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("huge.hint");
        long declaredTotal = RecordCodec.HINT_HEADER_SIZE + (long) Integer.MAX_VALUE;
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(hintHeader(Integer.MAX_VALUE)), 0);
            ch.write(ByteBuffer.wrap(new byte[]{0}), declaredTotal - 1); // file size = declaredTotal
        }
        assertThat(Files.size(f)).isEqualTo(declaredTotal);
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            assertThat(RecordCodec.readHintAt(ch, 0)).isInstanceOf(RecordCodec.HintReadResult.Corrupted.class);
        }
    }

    @Test
    @DisplayName("KVStore.open falls back to the data segment when its hint file has an overflowing KEY_LEN")
    void storeOpensWithBadHintFile(@TempDir Path dir) throws IOException {
        try (KVStore store = KVStore.open(dir)) {
            store.put("k", "v");
        }
        Files.write(dir.resolve(Segment.hintFileNameFor(1)), hintHeader(Integer.MAX_VALUE));

        try (KVStore store = KVStore.open(dir)) {
            assertThat(store.get("k")).contains("v");
            assertThat(store.count()).isEqualTo(1);
        }
    }
}
