package io.tinykv;

import io.tinykv.Record.Hint;
import io.tinykv.Record.IndexEntry;
import io.tinykv.Record.Key;
import io.tinykv.Record.LogRecord;
import io.tinykv.RecordCodec.ReadResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Background garbage collector: purges dead records and generates companion .hint files.
 *
 * Compaction runs concurrently with writers, so it works in three phases:
 * <ol>
 *   <li>Copy every record that is live right now into a new segment, without touching the index.</li>
 *   <li>Register the new segment, so any index entry pointing at it can be read.</li>
 *   <li>Swap each copied key's index entry with a compare-and-set. If the key was overwritten or
 *       deleted during the copy, the swap fails and the newer state wins. Only then are the old
 *       segments deleted.</li>
 * </ol>
 */
public final class Compactor {

    private record Swap(Key key, IndexEntry before, IndexEntry after) {}

    private final Path dataDir;
    private final SegmentManager segmentManager;
    private final Index index;

    public Compactor(Path dataDir, SegmentManager segmentManager, Index index) {
        this.dataDir = dataDir;
        this.segmentManager = segmentManager;
        this.index = index;
    }

    public synchronized void compact() throws IOException {
        List<Segment> sealed = segmentManager.getSealedSegments();
        if (sealed.isEmpty()) return;

        List<Long> oldIds = new ArrayList<>();
        for (Segment s : sealed) oldIds.add(s.getSegmentId());

        long compactedId = segmentManager.allocateNextSegmentId();
        Path compactedDataPath = dataDir.resolve(Segment.fileNameFor(compactedId));
        Path hintPath = dataDir.resolve(Segment.hintFileNameFor(compactedId));

        Segment compactedSegment = Segment.open(dataDir, compactedId, false);
        List<Swap> swaps = new ArrayList<>();

        // Phase 1: copy live records. The index still points at the old segments.
        try {
            for (Segment sourceSeg : sealed) {
                long segId = sourceSeg.getSegmentId();
                FileChannel sourceChannel = sourceSeg.getChannel();
                long pos = 0;

                while (true) {
                    ReadResult res = RecordCodec.readRecordAt(sourceChannel, pos);
                    if (res instanceof ReadResult.CleanEof) break;
                    if (res instanceof ReadResult.Success s) {
                        LogRecord record = s.record();
                        Optional<IndexEntry> cur = index.get(record.key());
                        if (cur.isPresent() && cur.get().segmentId() == segId && cur.get().offset() == pos) {
                            Segment.AppendResult app = compactedSegment.append(RecordCodec.encode(record));
                            swaps.add(new Swap(record.key(), cur.get(),
                                    new IndexEntry(compactedId, app.offset(), app.length(), record.timestamp())));
                        }
                        pos += s.bytesRead();
                    } else if (res instanceof ReadResult.Corrupted) {
                        break;
                    }
                }
            }
            compactedSegment.force(false);
        } catch (Exception e) {
            compactedSegment.close();
            Files.deleteIfExists(compactedDataPath);
            throw new IOException("Compaction failed: " + e.getMessage(), e);
        }

        // Phase 2: make the new segment readable before any index entry points at it.
        segmentManager.registerCompactedSegment(compactedSegment);

        // Phase 3: swap entries that are still unchanged; write hints only for those.
        try (FileChannel hintChannel = FileChannel.open(
                hintPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            for (Swap swap : swaps) {
                if (index.replaceIfUnchanged(swap.key(), swap.before(), swap.after())) {
                    IndexEntry e = swap.after();
                    ByteBuffer encHint = RecordCodec.encodeHint(
                            new Hint(e.timestamp(), swap.key(), compactedId, e.offset(), e.length()));
                    while (encHint.hasRemaining()) hintChannel.write(encHint);
                }
            }
            hintChannel.force(false);
        }

        segmentManager.removeCompactedSources(oldIds);
    }
}
