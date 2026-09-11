package io.tinykv;

import io.tinykv.Record.Hint;
import io.tinykv.Record.Key;
import io.tinykv.Record.LogRecord;
import io.tinykv.Record.Type;
import io.tinykv.StorageException.CorruptedRecordException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;

/**
 * Binary framing, CRC-32 integrity validation, and streaming parsers.
 *
 * Log Frame (22 bytes header):
 * [MAGIC: 1B][CRC32: 4B][TIMESTAMP: 8B][TYPE: 1B][KEY_LEN: 4B][VAL_LEN: 4B][KEY][VAL]
 *
 * Hint Frame (37 bytes header):
 * [MAGIC: 1B][CRC32: 4B][TIMESTAMP: 8B][KEY_LEN: 4B][SEG_ID: 8B][OFFSET: 8B][LEN: 4B][KEY]
 */
public final class RecordCodec {

    public static final byte DATA_MAGIC = 0x4B; // 'K'
    public static final byte HINT_MAGIC = 0x48; // 'H'
    public static final int HEADER_SIZE = 22;
    public static final int HINT_HEADER_SIZE = 37;

    private RecordCodec() {}

    public sealed interface ReadResult {
        record Success(LogRecord record, int bytesRead) implements ReadResult {}
        record CleanEof() implements ReadResult {}
        record Corrupted(long position, String reason, boolean isTornTail) implements ReadResult {}
    }

    public sealed interface HintReadResult {
        record Success(Hint hint, int bytesRead) implements HintReadResult {}
        record CleanEof() implements HintReadResult {}
        record Corrupted(long position, String reason) implements HintReadResult {}
    }

    public static ByteBuffer encode(LogRecord record) {
        byte[] kBytes = record.key().getBytes();
        byte[] vBytes = record.value();
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + kBytes.length + vBytes.length);

        long crc = computeCrc(record.timestamp(), record.type().getCode(), kBytes, vBytes);

        buf.put(DATA_MAGIC);
        buf.putInt((int) (crc & 0xFFFFFFFFL));
        buf.putLong(record.timestamp());
        buf.put(record.type().getCode());
        buf.putInt(kBytes.length);
        buf.putInt(vBytes.length);
        buf.put(kBytes);
        buf.put(vBytes);
        buf.flip();
        return buf;
    }

    public static LogRecord decode(ByteBuffer buf) {
        if (buf.remaining() < HEADER_SIZE) {
            throw new CorruptedRecordException("Buffer too small for header");
        }
        byte magic = buf.get();
        if (magic != DATA_MAGIC) {
            throw new CorruptedRecordException(String.format("Invalid magic byte: 0x%02X", magic));
        }

        long storedCrc = Integer.toUnsignedLong(buf.getInt());
        long ts = buf.getLong();
        byte typeCode = buf.get();
        int kLen = buf.getInt();
        int vLen = buf.getInt();

        if (kLen <= 0 || vLen < 0 || buf.remaining() < kLen + vLen) {
            throw new CorruptedRecordException("Invalid payload lengths");
        }

        byte[] kBytes = new byte[kLen];
        buf.get(kBytes);
        byte[] vBytes = new byte[vLen];
        buf.get(vBytes);

        long calculated = computeCrc(ts, typeCode, kBytes, vBytes);
        if (calculated != storedCrc) {
            throw new CorruptedRecordException("CRC mismatch during decode");
        }

        Type type = Type.fromCode(typeCode);
        if (type == null) {
            throw new CorruptedRecordException("Unknown record type: " + typeCode);
        }

        return new LogRecord(ts, type, Key.of(kBytes), vBytes, storedCrc);
    }

    public static ReadResult readRecordAt(FileChannel channel, long position) throws IOException {
        long size = channel.size();
        if (position >= size) return new ReadResult.CleanEof();

        long remaining = size - position;
        if (remaining < HEADER_SIZE) {
            return new ReadResult.Corrupted(position, "Incomplete header at EOF", true);
        }

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        channel.read(header, position);
        header.flip();

        byte magic = header.get();
        if (magic != DATA_MAGIC) {
            return new ReadResult.Corrupted(position, "Invalid magic byte", false);
        }

        long storedCrc = Integer.toUnsignedLong(header.getInt());
        long ts = header.getLong();
        byte typeCode = header.get();
        int kLen = header.getInt();
        int vLen = header.getInt();

        if (kLen <= 0 || vLen < 0) {
            return new ReadResult.Corrupted(position, "Corrupted lengths", false);
        }

        int total = HEADER_SIZE + kLen + vLen;
        if (remaining < total) {
            return new ReadResult.Corrupted(position, "Incomplete payload at EOF", true);
        }

        ByteBuffer payload = ByteBuffer.allocate(kLen + vLen);
        channel.read(payload, position + HEADER_SIZE);
        payload.flip();

        byte[] kBytes = new byte[kLen];
        payload.get(kBytes);
        byte[] vBytes = new byte[vLen];
        payload.get(vBytes);

        if (computeCrc(ts, typeCode, kBytes, vBytes) != storedCrc) {
            return new ReadResult.Corrupted(position, "CRC mismatch", position + total >= size);
        }

        Type type = Type.fromCode(typeCode);
        return new ReadResult.Success(new LogRecord(ts, type, Key.of(kBytes), vBytes, storedCrc), total);
    }

    public static ByteBuffer encodeHint(Hint hint) {
        byte[] kBytes = hint.key().getBytes();
        ByteBuffer buf = ByteBuffer.allocate(HINT_HEADER_SIZE + kBytes.length);

        CRC32 crc = new CRC32();
        ByteBuffer p = ByteBuffer.allocate(8 + 4 + 8 + 8 + 4);
        p.putLong(hint.timestamp()).putInt(kBytes.length).putLong(hint.segmentId()).putLong(hint.offset()).putInt(hint.length()).flip();
        crc.update(p);
        crc.update(kBytes);

        buf.put(HINT_MAGIC);
        buf.putInt((int) (crc.getValue() & 0xFFFFFFFFL));
        buf.putLong(hint.timestamp());
        buf.putInt(kBytes.length);
        buf.putLong(hint.segmentId());
        buf.putLong(hint.offset());
        buf.putInt(hint.length());
        buf.put(kBytes);
        buf.flip();
        return buf;
    }

    public static HintReadResult readHintAt(FileChannel channel, long position) throws IOException {
        long size = channel.size();
        if (position >= size) return new HintReadResult.CleanEof();
        if (size - position < HINT_HEADER_SIZE) return new HintReadResult.Corrupted(position, "Incomplete hint header");

        ByteBuffer h = ByteBuffer.allocate(HINT_HEADER_SIZE);
        channel.read(h, position);
        h.flip();

        if (h.get() != HINT_MAGIC) return new HintReadResult.Corrupted(position, "Invalid hint magic");

        long storedCrc = Integer.toUnsignedLong(h.getInt());
        long ts = h.getLong();
        int kLen = h.getInt();
        long segId = h.getLong();
        long offset = h.getLong();
        int len = h.getInt();

        if (kLen <= 0 || size - position < HINT_HEADER_SIZE + kLen) {
            return new HintReadResult.Corrupted(position, "Invalid key length in hint");
        }

        ByteBuffer kB = ByteBuffer.allocate(kLen);
        channel.read(kB, position + HINT_HEADER_SIZE);
        kB.flip();
        byte[] kBytes = new byte[kLen];
        kB.get(kBytes);

        CRC32 crc = new CRC32();
        ByteBuffer p = ByteBuffer.allocate(8 + 4 + 8 + 8 + 4);
        p.putLong(ts).putInt(kLen).putLong(segId).putLong(offset).putInt(len).flip();
        crc.update(p);
        crc.update(kBytes);

        if (crc.getValue() != storedCrc) return new HintReadResult.Corrupted(position, "Hint CRC mismatch");

        return new HintReadResult.Success(new Hint(ts, Key.of(kBytes), segId, offset, len), HINT_HEADER_SIZE + kLen);
    }

    private static long computeCrc(long ts, byte type, byte[] k, byte[] v) {
        CRC32 crc = new CRC32();
        ByteBuffer p = ByteBuffer.allocate(8 + 1 + 4 + 4);
        p.putLong(ts).put(type).putInt(k.length).putInt(v.length).flip();
        crc.update(p);
        crc.update(k);
        crc.update(v);
        return crc.getValue();
    }
}
