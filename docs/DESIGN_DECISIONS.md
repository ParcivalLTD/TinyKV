# TinyKV: Architecture & Design Decisions

This document explains the real-world constraints and architectural choices behind **TinyKV**.

---

## 1. The Core Constraint: Keys in RAM, Values on Disk

Every storage engine makes a fundamental compromise between memory usage, read speed, and dataset size.

TinyKV is built under one clear operational constraint:
> **All active keys and their file coordinates must fit in computer memory (RAM). Values can be arbitrary in size and remain on disk.**

### Is this practical?
* In TinyKV, each in-memory index entry takes $\approx 100$ bytes (including the key string, object header, file ID, offset, and length).
* **1 million keys** consume $\approx 100\text{ MB}$ of RAM.
* **10 million keys** consume $\approx 1\text{ GB}$ of RAM.
* For session storage, metadata catalogs, object registries, and caching layers, 1 GB of memory to index millions of disk records is lightweight on modern hardware.

---

## 2. The Main Architectural Choice: Bitcask vs. LSM-Tree

When designing an embedded key-value store, the primary fork in the road is:
1. **Bitcask**: Append-only log on disk + in-memory hash table of coordinates.
2. **LSM-Tree** (used in RocksDB / LevelDB): MemTable in RAM + tiered SSTable files on disk + Bloom filters.

### Comparison

| Requirement | Bitcask (Our Design) | LSM-Tree (Alternative) |
| :--- | :--- | :--- |
| **Read Latency (Point Lookups)** | **Guaranteed 1 disk read ($O(1)$)** | Variable (may check 2 to 5 disk files) |
| **Read Amplification** | **1.0** (Zero extra reads) | High (checks multiple levels on misses) |
| **Write Stalls** | None (pure sequential append) | Frequent (when background merges fall behind) |
| **Memory Constraint** | Keys must fit in RAM | Keys can exceed RAM |
| **Compaction Complexity** | Simple single-pass filter | Complex multi-way merge-sort |
| **Range Scans** | Unordered (requires sorting keys) | Ordered ($O(\log N)$ tree scan) |

### Why Bitcask was chosen:
* **Predictable Speed**: In an LSM-Tree, reading a key can require reading multiple files on disk, creating latency spikes. In Bitcask, the in-memory index points to the exact file and byte offset. Every read takes **at most one disk hop**.
* **Zero Write Freezes**: Writes append to the end of the file. Background compaction simply copies surviving entries to a fresh file without locking or slowing down the active append path.

---

## 3. Durability: Why `fsync` is slow, and how Group Commit fixes it

When you save data to a file, the operating system usually keeps it in a memory cache before writing it to the physical drive. If power is lost, unwritten cached data is lost.

Calling `fsync` (`FileChannel.force(false)`) forces the physical drive to write the data immediately:
* On rotating hard drives (HDD), an `fsync` takes ~5 to 10 ms (limited by physical disk rotation to 100–200 writes/sec).
* On SSDs, an `fsync` takes ~0.2 to 2 ms (limiting single-threaded writes to ~500–5,000 writes/sec).

### The Solution: Group Commit
TinyKV provides `SyncPolicy.GROUP_COMMIT`:
1. When multiple threads write data concurrently, each thread quickly appends its record and registers a completion ticket.
2. A background flusher collects pending tickets and issues **one single `fsync` for the entire batch**.
3. All waiting callers are notified at once.

This achieves high throughput while guaranteeing that when a `put()` returns, the data is physically on disk.

### Durability Guarantees Across Sync Policies

| Sync Policy | Power Outage Durability | Program Crash Durability | Throughput Trade-off |
| :--- | :--- | :--- | :--- |
| **`IMMEDIATE`** | **Guaranteed** (fsync on every write) | **Guaranteed** | Lower IOPS (bound by physical disk sync) |
| **`GROUP_COMMIT`** | **Guaranteed** (micro-batched fsync) | **Guaranteed** | **High concurrent IOPS** (recommended) |
| **`ASYNC_OS`** | Possible loss of unflushed OS cache | **Guaranteed** | Maximum IOPS (relies on OS kernel flush) |

> [!NOTE]
> TinyKV defaults to `GROUP_COMMIT`. Data is guaranteed durable against power failure when `put()` returns, while coalescing concurrent requests into single physical disk flushes.

---

## 4. Crash Consistency & The "Torn Write" Problem

### What happens when the power cord is pulled mid-write?
If a computer loses power while writing, the disk might only save the first 10 bytes of a 50-byte entry. This is a **torn write**.

### How TinyKV prevents corruption:
1. Every entry starts with a **22-byte binary header** containing:
   * A 1-byte magic identifier (`0x4B`)
   * A 4-byte **CRC-32 checksum** covering the entire payload
   * An 8-byte timestamp
   * Key length and value length
2. **Auto-Recovery on Boot**:
   * When TinyKV starts up, it reads the log file from beginning to end.
   * If it encounters an incomplete header or a CRC checksum mismatch **at the end of the active file**, it knows the program crashed mid-write.
   * It logs a warning, **automatically truncates the file back to the last valid completed record**, and recovers cleanly.

---

## 5. Hint Files: Instant Startup

If a database has 20 GB of data on disk, reading all 20 GB at startup just to find where keys are located would take minutes.

During background compaction, TinyKV writes companion `.hint` files:
* A `.hint` file contains only `(timestamp, key, fileId, offset, length)` — **no value payloads**.
* Because values are omitted, `.hint` files are tiny (often $< 2\%$ of the data size).
* On startup, TinyKV reads the `.hint` files into RAM in milliseconds, achieving cold boot times under 100 ms.

---

## 6. Concurrency: Positional Reads vs. Memory Mapping (MMap)

Many Java projects use memory-mapped files (`MappedByteBuffer`). However, memory mapping has known drawbacks in production Java:
1. **JVM Unmapping Bugs on Windows**: Java does not provide a safe public API to unmap a file. On Windows, open memory-mapped files cannot be deleted or truncated during background compaction, resulting in file-locking errors.
2. **Uncatchable Crashes (SIGBUS)**: If a file is truncated while a memory-mapped buffer is accessed, the entire JVM terminates with a fatal access violation.
3. **The Solution**: TinyKV uses **positional `FileChannel.read(ByteBuffer dst, long position)`**. In Java NIO, this call is completely thread-safe and does not modify the channel's position. Multiple reader threads query the same file simultaneously without locks or race conditions.
