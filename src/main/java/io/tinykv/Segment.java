package io.tinykv;

import io.tinykv.Record.LogRecord;
import io.tinykv.StorageException.CorruptedRecordException;
import io.tinykv.StorageException.StorageClosedException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages an individual on-disk segment file.
 * Thread-safe append; lock-free concurrent positional reads.
 */
public final class Segment implements AutoCloseable {

    private final long segmentId;
    private final Path path;
    private final FileChannel channel;
    private final AtomicLong writeOffset;
    private volatile boolean readOnly;
    private volatile boolean closed;

    public record AppendResult(long offset, int length) {}

    private Segment(long id, Path path, FileChannel channel, long initialSize, boolean readOnly) {
        this.segmentId = id;
        this.path = path;
        this.channel = channel;
        this.writeOffset = new AtomicLong(initialSize);
        this.readOnly = readOnly;
        this.closed = false;
    }

    public static Segment open(Path dir, long id, boolean readOnly) throws IOException {
        Path file = dir.resolve(fileNameFor(id));
        FileChannel ch = readOnly
                ? FileChannel.open(file, StandardOpenOption.READ)
                : FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = ch.size();
        if (!readOnly) ch.position(size);
        return new Segment(id, file, ch, size, readOnly);
    }

    public static String fileNameFor(long id) {
        return String.format("segment_%010d.data", id);
    }

    public static String hintFileNameFor(long id) {
        return String.format("segment_%010d.hint", id);
    }

    public long getSegmentId() { return segmentId; }
    public Path getPath() { return path; }
    public boolean isReadOnly() { return readOnly; }
    public void markReadOnly() { this.readOnly = true; }
    public long size() { return writeOffset.get(); }
    public FileChannel getChannel() { return channel; }

    public synchronized AppendResult append(ByteBuffer buffer) throws IOException {
        checkOpen();
        if (readOnly) throw new IllegalStateException("Segment " + segmentId + " is read-only");
        long offset = writeOffset.get();
        int len = buffer.remaining();
        while (buffer.hasRemaining()) channel.write(buffer);
        writeOffset.set(offset + len);
        return new AppendResult(offset, len);
    }

    public LogRecord readRecord(long offset, int length) throws IOException {
        checkOpen();
        if (offset < 0 || offset + length > writeOffset.get()) {
            throw new IllegalArgumentException("Read bounds outside segment: offset=" + offset + ", len=" + length);
        }
        ByteBuffer buf = ByteBuffer.allocate(length);
        int total = 0;
        while (total < length) {
            int r = channel.read(buf, offset + total);
            if (r < 0) throw new CorruptedRecordException("Unexpected EOF at offset " + (offset + total));
            total += r;
        }
        buf.flip();
        return RecordCodec.decode(buf);
    }

    public void force(boolean metaData) throws IOException {
        if (!closed && channel.isOpen()) channel.force(metaData);
    }

    public synchronized void truncate(long newSize) throws IOException {
        checkOpen();
        channel.truncate(newSize);
        channel.position(newSize);
        writeOffset.set(newSize);
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            if (channel.isOpen()) channel.close();
        }
    }

    public synchronized void delete() throws IOException {
        close();
        Files.deleteIfExists(path);
    }

    private void checkOpen() {
        if (closed || !channel.isOpen()) throw new StorageClosedException("Segment " + segmentId + " is closed");
    }
}
