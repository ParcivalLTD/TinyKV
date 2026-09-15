package io.tinykv;

import io.tinykv.Record.IndexEntry;
import io.tinykv.Record.LogRecord;
import io.tinykv.RecordCodec.HintReadResult;
import io.tinykv.RecordCodec.ReadResult;
import io.tinykv.StorageException.CorruptedRecordException;
import io.tinykv.StorageException.StorageClosedException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages active and sealed WAL segments, crash replay, and torn-write auto-recovery.
 */
public final class SegmentManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SegmentManager.class);
    private static final Pattern PATTERN = Pattern.compile("segment_(\\d{10})\\.data");

    private final Path dataDir;
    private final long maxSegmentSize;
    private final Index index;
    private final Map<Long, Segment> sealed = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    private volatile Segment active;
    private volatile boolean closed;

    public SegmentManager(Path dataDir, long maxSegmentSize, Index index) {
        this.dataDir = dataDir;
        this.maxSegmentSize = maxSegmentSize;
        this.index = index;
    }

    public synchronized void initialize() throws IOException {
        Files.createDirectories(dataDir);
        List<Long> ids = discoverIds();

        if (ids.isEmpty()) {
            active = Segment.open(dataDir, 1L, false);
            nextId.set(2L);
            return;
        }

        long lastId = ids.get(ids.size() - 1);
        nextId.set(lastId + 1);

        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            boolean isTail = (i == ids.size() - 1);

            Path hintPath = dataDir.resolve(Segment.hintFileNameFor(id));
            boolean hintLoaded = false;
            if (Files.exists(hintPath)) {
                try {
                    loadHint(hintPath);
                    hintLoaded = true;
                } catch (Exception e) {
                    log.warn("Corrupted hint for segment {}, falling back to data scan", id);
                }
            }

            if (!hintLoaded) {
                recoverDataSegment(id, isTail);
            }

            if (isTail) {
                active = Segment.open(dataDir, id, false);
                if (active.size() >= maxSegmentSize) {
                    rotateActiveSegment();
                }
            } else {
                sealed.put(id, Segment.open(dataDir, id, true));
            }
        }
    }

    private void loadHint(Path hintPath) throws IOException {
        try (FileChannel ch = FileChannel.open(hintPath, StandardOpenOption.READ)) {
            long pos = 0;
            while (true) {
                HintReadResult res = RecordCodec.readHintAt(ch, pos);
                if (res instanceof HintReadResult.CleanEof) break;
                if (res instanceof HintReadResult.Success s) {
                    index.putIfNewer(s.hint().key(), new IndexEntry(s.hint().segmentId(), s.hint().offset(), s.hint().length(), s.hint().timestamp()));
                    pos += s.bytesRead();
                } else if (res instanceof HintReadResult.Corrupted c) {
                    throw new CorruptedRecordException("Corrupted hint file: " + c.reason());
                }
            }
        }
    }

    private void recoverDataSegment(long segId, boolean isTail) throws IOException {
        Path p = dataDir.resolve(Segment.fileNameFor(segId));
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long pos = 0;
            while (true) {
                ReadResult res = RecordCodec.readRecordAt(ch, pos);
                if (res instanceof ReadResult.CleanEof) break;
                if (res instanceof ReadResult.Success s) {
                    LogRecord rec = s.record();
                    if (rec.isTombstone()) {
                        index.remove(rec.key(), rec.timestamp());
                    } else {
                        index.putIfNewer(rec.key(), new IndexEntry(segId, pos, s.bytesRead(), rec.timestamp()));
                    }
                    pos += s.bytesRead();
                } else if (res instanceof ReadResult.Corrupted c) {
                    if (isTail && c.isTornTail()) {
                        log.warn("Detected torn write at tail of segment {} at offset {}. Truncating to valid state.", segId, c.position());
                        ch.truncate(c.position());
                        ch.force(true);
                        break;
                    } else {
                        throw new CorruptedRecordException("Fatal data corruption in segment " + segId + " at " + c.position() + ": " + c.reason());
                    }
                }
            }
        }
    }

    public synchronized Segment rotateActiveSegment() throws IOException {
        checkOpen();
        active.force(false);
        active.markReadOnly();
        sealed.put(active.getSegmentId(), active);

        long newId = nextId.getAndIncrement();
        active = Segment.open(dataDir, newId, false);
        return active;
    }

    public Segment getActiveSegment() { checkOpen(); return active; }

    public Segment getSegment(long id) {
        checkOpen();
        if (active != null && active.getSegmentId() == id) return active;
        Segment s = sealed.get(id);
        if (s != null) return s;
        throw new IllegalArgumentException("Unknown segment id: " + id);
    }

    public boolean shouldRotate() {
        return active != null && active.size() >= maxSegmentSize;
    }

    public List<Segment> getSealedSegments() {
        checkOpen();
        List<Segment> list = new ArrayList<>(sealed.values());
        list.sort((a, b) -> Long.compare(a.getSegmentId(), b.getSegmentId()));
        return Collections.unmodifiableList(list);
    }

    public synchronized void replaceCompactedSegments(List<Long> oldIds, Segment compacted, Path hintPath) throws IOException {
        checkOpen();
        compacted.markReadOnly();
        sealed.put(compacted.getSegmentId(), compacted);

        for (long oldId : oldIds) {
            Segment old = sealed.remove(oldId);
            if (old != null) old.delete();
            Files.deleteIfExists(dataDir.resolve(Segment.hintFileNameFor(oldId)));
        }
    }

    public long allocateNextSegmentId() {
        return nextId.getAndIncrement();
    }

    public long calculateTotalDiskSizeBytes() {
        long total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) total += Files.size(p);
            }
        } catch (IOException ignored) {}
        return total;
    }

    private List<Long> discoverIds() throws IOException {
        List<Long> ids = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dataDir, "*.data")) {
            for (Path p : s) {
                Matcher m = PATTERN.matcher(p.getFileName().toString());
                if (m.matches()) ids.add(Long.parseLong(m.group(1)));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private void checkOpen() {
        if (closed) throw new StorageClosedException("SegmentManager is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            if (active != null) {
                active.force(false);
                active.close();
            }
            for (Segment s : sealed.values()) s.close();
            sealed.clear();
        }
    }
}
