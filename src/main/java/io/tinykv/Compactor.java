package io.tinykv;

import io.tinykv.Record.Hint;
import io.tinykv.Record.IndexEntry;
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
 */
public final class Compactor {

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

        try (FileChannel hintChannel = FileChannel.open(
                hintPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            for (Segment sourceSeg : sealed) {
                long segId = sourceSeg.getSegmentId();
                FileChannel sourceChannel = sourceSeg.getChannel();
                long pos = 0;

                while (true) {
                    ReadResult res = RecordCodec.readRecordAt(sourceChannel, pos);
                    if (res instanceof ReadResult.CleanEof) break;
                    if (res instanceof ReadResult.Success s) {
                        LogRecord record = s.record();
                        int recordBytes = s.bytesRead();

                        Optional<IndexEntry> cur = index.get(record.key());
                        if (cur.isPresent() && cur.get().segmentId() == segId && cur.get().offset() == pos) {
                            // Live record: copy to compacted segment
                            ByteBuffer enc = RecordCodec.encode(record);
                            Segment.AppendResult app = compactedSegment.append(enc);

                            // Write hint
                            Hint hint = new Hint(record.timestamp(), record.key(), compactedId, app.offset(), app.length());
                            ByteBuffer encHint = RecordCodec.encodeHint(hint);
                            while (encHint.hasRemaining()) hintChannel.write(encHint);

                            index.putIfNewer(record.key(), new IndexEntry(compactedId, app.offset(), app.length(), record.timestamp()));
                        }
                        pos += recordBytes;
                    } else if (res instanceof ReadResult.Corrupted) {
                        break;
                    }
                }
            }
            hintChannel.force(false);
            compactedSegment.force(false);
        } catch (Exception e) {
            compactedSegment.close();
            Files.deleteIfExists(compactedDataPath);
            Files.deleteIfExists(hintPath);
            throw new IOException("Compaction failed: " + e.getMessage(), e);
        }

        segmentManager.replaceCompactedSegments(oldIds, compactedSegment, hintPath);
    }
}
