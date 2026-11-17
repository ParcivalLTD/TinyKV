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
 * Documents CURRENT behaviour; it does not assert that this behaviour is desirable.
 *
 * readRecordAt reports any record whose declared length runs past EOF as a torn tail
 * (RecordCodec.readRecordAt, the "Incomplete payload at EOF" branch), and
 * SegmentManager.recoverDataSegment truncates the tail segment at such a record. It
 * cannot tell a record that really was cut short by a crash from a fully written record
 * whose KEY_LEN field was later damaged. In the second case the truncation also deletes
 * every fully written record after the damaged one, and the store opens without error
 * (only a WARN is logged).
 *
 * Byte-level example used below. Each record is put(k, v) with a 1-byte key and a
 * 1-byte value, so it is 22 + 1 + 1 = 24 bytes long:
 *
 *   offset  0..23   record a -> 1
 *   offset 24..47   record b -> 2   KEY_LEN at offsets 38..41 = 00 00 00 01
 *   offset 48..71   record c -> 3
 *
 * Changing the byte at offset 40 from 00 to 01 makes record b's KEY_LEN 0x00000101 = 257.
 * At offset 24, 48 bytes remain but the header now declares 22 + 257 + 1 = 280, so
 * readRecordAt returns Corrupted(isTornTail = true) and recovery truncates the file to
 * 24 bytes. Record c, written in full and untouched, is lost.
 */
class TornTailClassificationTest {

    private static final int RECORD_SIZE = RecordCodec.HEADER_SIZE + 1 + 1;
    private static final int KEY_LEN_OFFSET = 1 + 4 + 8 + 1; // MAGIC, CRC32, TIMESTAMP, TYPE

    @Test
    @DisplayName("Current behaviour: a damaged KEY_LEN mid-segment is treated as a torn tail and later records are truncated away")
    void corruptedLengthMidTailSegmentTruncatesLaterRecords(@TempDir Path dir) throws IOException {
        try (KVStore store = KVStore.open(dir)) {
            store.put("a", "1");
            store.put("b", "2");
            store.put("c", "3");
        }
        Path segment = dir.resolve(Segment.fileNameFor(1));
        assertThat(Files.size(segment)).isEqualTo(3L * RECORD_SIZE);

        // Damage one byte of record b's KEY_LEN field: 00 00 00 01 becomes 00 00 01 01.
        long keyLenThirdByte = RECORD_SIZE + KEY_LEN_OFFSET + 2;
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[]{0x01}), keyLenThirdByte);
            ch.force(true);
        }

        // Opening succeeds: the damage is classified as a torn tail, not as corruption.
        try (KVStore store = KVStore.open(dir)) {
            assertThat(store.get("a")).contains("1");
            assertThat(store.get("b")).isEmpty();
            assertThat(store.get("c")).isEmpty(); // fully written, yet dropped
            assertThat(store.count()).isEqualTo(1);
        }
        // Recovery truncated the file at record b, deleting record c's bytes from disk.
        assertThat(Files.size(segment)).isEqualTo(RECORD_SIZE);
    }
}
