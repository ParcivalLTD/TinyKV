package io.tinykv;

import io.tinykv.StorageException.CorruptedRecordException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A record whose CRC is valid but whose TYPE byte is neither 1 (PUT) nor 2 (DELETE).
 *
 * decode() already rejected such a record. readRecordAt() passed Type.fromCode's null on to
 * the LogRecord constructor, which threw a NullPointerException, so KVStore.open failed with
 * an NPE. It now reports Corrupted(isTornTail = false): all bytes are present and the CRC
 * matches, so this is not a torn write, and recovery refuses to open the store with a
 * CorruptedRecordException instead of silently truncating the record away.
 */
class UnknownRecordTypeTest {

    private static final byte UNKNOWN_TYPE = 3;

    /** A complete record with the given type byte and a CRC that matches its contents. */
    private static byte[] recordWithType(byte type, String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        long ts = 42L;

        // Same fields, in the same order, as RecordCodec.computeCrc.
        CRC32 crc = new CRC32();
        crc.update(ByteBuffer.allocate(8 + 1 + 4 + 4).putLong(ts).put(type).putInt(k.length).putInt(v.length).flip());
        crc.update(k);
        crc.update(v);

        return ByteBuffer.allocate(RecordCodec.HEADER_SIZE + k.length + v.length)
                .put(RecordCodec.DATA_MAGIC)
                .putInt((int) crc.getValue())
                .putLong(ts)
                .put(type)
                .putInt(k.length)
                .putInt(v.length)
                .put(k)
                .put(v)
                .array();
    }

    @Test
    @DisplayName("readRecordAt: a valid-CRC record with an unknown type code is corrupted, not torn")
    void readRecordAtRejectsUnknownType(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("unknown-type.data");
        Files.write(f, recordWithType(UNKNOWN_TYPE, "k", "v"));
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            RecordCodec.ReadResult res = RecordCodec.readRecordAt(ch, 0);
            assertThat(res).isInstanceOf(RecordCodec.ReadResult.Corrupted.class);
            assertThat(((RecordCodec.ReadResult.Corrupted) res).isTornTail()).isFalse();
        }
    }

    @Test
    @DisplayName("The test record is otherwise valid: with type PUT it decodes")
    void sameRecordWithKnownTypeDecodes(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("put.data");
        Files.write(f, recordWithType((byte) 1, "k", "v"));
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            assertThat(RecordCodec.readRecordAt(ch, 0)).isInstanceOf(RecordCodec.ReadResult.Success.class);
        }
    }

    @Test
    @DisplayName("KVStore.open reports corruption, not a NullPointerException, for an unknown type code")
    void storeOpenReportsCorruption(@TempDir Path dir) throws IOException {
        try (KVStore store = KVStore.open(dir)) {
            store.put("a", "1");
        }
        Path segment = dir.resolve(Segment.fileNameFor(1));
        try (FileChannel ch = FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(recordWithType(UNKNOWN_TYPE, "b", "2")));
        }

        assertThatThrownBy(() -> KVStore.open(dir))
                .isInstanceOf(CorruptedRecordException.class)
                .hasMessageContaining("Unknown record type");
    }
}
