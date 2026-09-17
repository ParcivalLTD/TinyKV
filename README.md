# TinyKV

> 🌐 **Live Web Console & Public API**: [https://kv.wavebeef.com](https://kv.wavebeef.com)

A small, fast, crash-safe embedded Key-Value database in Java 21 with a lightweight HTTP REST front-end, interactive web dashboard, Docker containerization, and Kubernetes deployment on **kind** behind **NGINX Gateway Fabric**.

It saves key-value pairs directly to disk using an append-only Write-Ahead Log (WAL) so your data survives program crashes and power outages (under `IMMEDIATE` and `GROUP_COMMIT` durability policies), while keeping in-memory $O(1)$ lookups instant.

---

## How It Works

Instead of overwriting data in the middle of files (which is slow and easily corrupted), TinyKV uses the **Bitcask storage model**:

1. **Append-Only Log (WAL)**: Every `put` and `delete` is written to the very end of the current segment file.
2. **In-Memory Index**: A concurrent hash map in RAM tracks coordinates for every key: `(segmentId, byteOffset, length)`.
3. **Single Disk Hop**: When reading a key, the engine looks up the exact coordinates in RAM and reads from disk in **exactly one disk read**.
4. **Crash Repair**: Every record has a CRC-32 checksum. If power fails mid-write, the engine detects the broken tail entry on startup and automatically snips it off to prevent data corruption.
5. **Compaction**: A background cleanup process purges outdated values and tombstones, emitting companion `.hint` index files for instant cold boots.

```
       PUT / DELETE                                GET
            │                                       │
            ▼                                       ▼ (RAM: O(1))
    ┌─────────────────┐                    ┌─────────────────┐
    │  Active Segment │                    │ In-Memory Index │
    │ (append to end) │                    │ key -> (pos,len)│
    └─────────────────┘                    └─────────────────┘
            │ (when file full)                      │
            ▼                                       ▼
    ┌─────────────────┐                    ┌─────────────────┐
    │ Sealed Segments │ ──[Compaction]──>  │ Compacted Data  │
    │   (read-only)   │                    │  + .hint file   │
    └─────────────────┘                    └─────────────────┘
```

---

## HTTP REST Front-End & Live Web Console

TinyKV includes a lightweight, zero-dependency HTTP server built with Java 21 Virtual Threads. 

Visiting **[https://kv.wavebeef.com/](https://kv.wavebeef.com/)** in any browser opens an interactive web console to execute live `PUT`/`GET`/`DELETE` queries and view real-time storage telemetry.

| Method | Endpoint | Description | Status Codes |
| :--- | :--- | :--- | :--- |
| `GET` | `/` | Interactive Web Console & Dashboard | `200 OK` |
| `GET` | `/healthz` | Kubernetes liveness probe | `200 OK` |
| `GET` | `/readyz` | Kubernetes readiness probe | `200 OK` |
| `GET` | `/api/v1/keys/{key}` | Retrieve value for key | `200 OK`, `404 Not Found` |
| `PUT` | `/api/v1/keys/{key}` | Store request body as value | `200 OK` |
| `DELETE` | `/api/v1/keys/{key}` | Delete key (appends tombstone) | `204 No Content`, `404` |
| `GET` | `/api/v1/stats` | JSON metrics (keys, disk size, dead space) | `200 OK` |
| `POST` | `/api/v1/compact` | Trigger background compaction | `200 OK` |

### Quick `curl` Examples
```bash
# Store a key (works on https://kv.wavebeef.com or http://localhost:8080)
curl -X PUT https://kv.wavebeef.com/api/v1/keys/user123 -d '{"name":"Alice","role":"admin"}'

# Read a key
curl https://kv.wavebeef.com/api/v1/keys/user123

# Inspect live metrics
curl https://kv.wavebeef.com/api/v1/stats

# Delete key
curl -X DELETE https://kv.wavebeef.com/api/v1/keys/user123

# Trigger log compaction
curl -X POST https://kv.wavebeef.com/api/v1/compact
```

---

## Containerization & Docker

TinyKV uses a multi-stage `Dockerfile` with Alpine JRE for a lean (~100MB) non-root container image:

```bash
# Build Docker image
docker build -t tinykv:latest .

# Run container with persistent volume
docker run -d \
  -p 8080:8080 \
  -v tinykv-data:/data \
  --name tinykv \
  tinykv:latest

# Check health
curl http://localhost:8080/healthz
```

---

## Deployment: kind with NGINX Gateway Fabric (v2.7.2)

TinyKV deploys as a **Kubernetes `StatefulSet`** with a `PersistentVolumeClaim` (to persist WAL data) behind **NGINX Gateway Fabric v2.7.2** using the official Kubernetes Gateway API (`Gateway` + `HTTPRoute`).

### Architecture on Kubernetes
```
  Client (curl http://localhost)
               │
               ▼ (Host Port 80 -> Kind NodePort 31437)
   [ NGINX Gateway Fabric v2.7.2 ] (Gateway: tinykv-gateway)
               │
               ▼ (HTTPRoute: / -> tinykv-service:8080)
   [ TinyKV StatefulSet ] (Pod: tinykv-0, Volume: /data)
```

### One-Command Deployment & Automated E2E Verification

On Windows (PowerShell):
```powershell
.\k8s\deploy.ps1
```

On Linux / macOS / WSL:
```bash
chmod +x ./k8s/deploy.sh
./k8s/deploy.sh
```

The script automatically:
1. Creates a local `kind` cluster with NodePort mappings (`31437` -> `80`, `30478` -> `443` in `k8s/kind-config.yaml`).
2. Installs official Gateway API standard CRDs (`v2.7.2` reference).
3. Creates namespace `nginx-gateway` and installs NGINX Gateway Fabric v2.7.2 CRDs via server-side apply.
4. Deploys the NGINX Gateway Fabric controller and waits for readiness.
5. Configures `NginxProxy` to expose the NGINX data plane via NodePort.
6. Builds and loads the `tinykv:latest` container image into the kind node.
7. Deploys the `StatefulSet`, `Service`, `Gateway`, and `HTTPRoute`.
8. Waits for the Gateway `Programmed` condition and StatefulSet pod readiness.
9. **Executes live end-to-end HTTP traffic tests**: curls `/healthz`, `/readyz`, performs a `PUT`, retrieves and verifies the value with a `GET`, inspects `/api/v1/stats`, and executes a `DELETE`. The script fails if any traffic check fails.

---

## Continuous Integration (GitHub Actions)

The repository includes a 4-stage GitHub Actions workflow in [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

1. **Build & Test (Java 21)**:
   - Sets up Java 21 (Temurin).
   - Runs the automated test suite (`./gradlew test --info`).
   - Runs interactive demo benchmark (`./gradlew runDemo`).
   - Uploads test reports on failure.
2. **Go Client Tests**:
   - Sets up Go 1.22+.
   - Runs unit and integration test suite with race detector (`go test -v -race ./...`).
3. **Docker Build & Smoke Test**:
   - Builds multi-stage container image using Buildx.
   - Spins up container, checks `/healthz`, `/readyz`, and executes `PUT`/`GET`/`DELETE` smoke tests.
4. **Kind E2E (NGINX Gateway Fabric v2.7.2)**:
   - Provisions a fresh `kind` cluster in CI.
   - Executes `./k8s/deploy.sh` to install Gateway Fabric v2.7.2 and TinyKV.
   - Runs the Go load generator (`cmd/loadgen`) through NGINX Gateway Fabric on `http://localhost/` to assert real-world traffic routing and log Gateway latency metrics on every commit.

---

## Java Embedded API Usage

If using TinyKV directly in Java applications as an embedded library:

```java
import io.tinykv.KVStore;
import java.nio.file.Path;
import java.util.Optional;

public class Example {
    public static void main(String[] args) throws Exception {
        try (KVStore store = KVStore.open(Path.of("./data"))) {
            store.put("session:123", "logged_in");
            
            Optional<String> status = store.get("session:123");
            System.out.println(status.orElse("not found"));
            
            store.delete("session:123");
            store.compact();
        }
    }
}
```

---

## Go Client & Benchmarking Tool

A lightweight, zero-dependency Go client library and concurrent load generator are located in [`client/go/`](client/go/):

### 1. Go Client Library
```go
import (
    "context"
    "fmt"
    "github.com/ParcivalLTD/TinyKV/client/go/tinykv"
)

client, err := tinykv.NewClient("http://localhost:8080")
ctx := context.Background()

// Store
client.Put(ctx, "session:101", []byte("token_xyz"))

// Retrieve
val, err := client.Get(ctx, "session:101")
fmt.Println(string(val))

// Delete
client.Delete(ctx, "session:101")
```

### 2. Concurrent Load Generator CLI
Execute concurrent traffic through NGINX Gateway Fabric or local instances:
```bash
cd client/go

# Run 500 requests across 10 concurrent workers
go run ./cmd/loadgen -url http://localhost/ -concurrency 10 -requests 500 -val-size 128
```

Output:
```
===============================================================
                     BENCHMARK RESULTS                         
===============================================================
Total Elapsed:   184ms
Successful Ops:  500 PUT, 500 GET (Errors: 0)
Throughput:      5434.78 ops/sec
---------------------------------------------------------------
Operation  Avg        p50        p90        p99        Max       
PUT (Write) 1.82ms     1.65ms     2.41ms     3.89ms     5.12ms    
GET (Read)  0.31ms     0.28ms     0.45ms     0.78ms     1.05ms    
===============================================================
```

---

## Security & Protection Guardrails

When exposed publicly (e.g. at `kv.wavebeef.com`), the HTTP server enforces strict guardrails to prevent denial-of-service and memory exhaustion:

| Guardrail | Environment Variable | Default | Behavior when Exceeded |
| :--- | :--- | :--- | :--- |
| **Max Key Length** | `TINYKV_MAX_KEY_BYTES` | `256` bytes | `400 Bad Request` |
| **Max Value Size** | `TINYKV_MAX_VALUE_BYTES` | `65536` (64 KB) | `413 Payload Too Large` (bounded stream reading) |
| **Max Key Count** | `TINYKV_MAX_KEYS` | `10000` keys | `507 Insufficient Storage` (protects in-memory index) |
| **Write Token** | `TINYKV_WRITE_TOKEN` | *Unset (open)* | `401 Unauthorized` without `Authorization: Bearer <token>` |
| **Read-Only Mode** | `TINYKV_READ_ONLY` | `false` | `403 Forbidden` on `PUT`, `DELETE`, `compact` |
| **Rate Limiter** | `TINYKV_RATE_LIMIT_RPM` | `0` (disabled) | `429 Too Many Requests` with `Retry-After: 60` (recommended `60` when exposed publicly) |

---

## Running Locally with Gradle

```bash
# Run all automated unit and integration tests
./gradlew test

# Start the HTTP server on port 8080
./gradlew run

# Run the interactive benchmark demo
./gradlew runDemo
```

---

## Project Structure

```
.
├── .github/workflows/
│   └── ci.yml                     # 4-stage GitHub Actions CI workflow (Test, Go, Docker, Kind E2E)
├── Dockerfile                     # Multi-stage lean container build
├── client/go/                     # Pure Go client library & load generator
│   ├── go.mod
│   ├── tinykv/
│   │   ├── client.go              # HTTP client with timeouts, retry, and auth
│   │   └── client_test.go         # Go unit tests against httptest.Server
│   └── cmd/loadgen/
│       └── main.go                # Concurrent CLI benchmark tool (p50/p90/p99)
├── k8s/
│   ├── kind-config.yaml           # Local kind cluster configuration (NodePort 31437/30478)
│   ├── nginx-proxy-config.yaml    # NGINX Gateway Fabric NginxProxy resource
│   ├── statefulset.yaml           # StatefulSet + PVC + Service
│   ├── gateway.yaml               # Gateway API Gateway resource (nginx class)
│   ├── httproute.yaml             # Gateway API HTTPRoute resource
│   ├── deploy.ps1                 # Automated deployment + live traffic checks (Windows)
│   └── deploy.sh                  # Automated deployment + live traffic checks (Linux/macOS)
├── src/main/java/io/tinykv/
│   ├── KVStore.java               # Public API and Bitcask storage engine
│   ├── Config.java                # Configuration options
│   ├── SyncPolicy.java            # Durability modes (GROUP_COMMIT, IMMEDIATE, ASYNC_OS)
│   ├── Stats.java                 # Metrics and dead space tracking
│   ├── Record.java                # Key, LogRecord, IndexEntry, Hint
│   ├── RecordCodec.java           # Binary framing, CRC-32, and torn-write parser
│   ├── Index.java                 # In-memory hash index with timestamp ordering
│   ├── Segment.java               # On-disk segment with thread-safe positional reads
│   ├── SegmentManager.java        # File rotation, discovery, and crash recovery
│   ├── Compactor.java             # Garbage collection & .hint file generator
│   ├── GroupCommit.java           # Fsync micro-batching for concurrent writers
│   ├── StorageException.java      # Clean exception hierarchy
│   ├── demo/
│   │   └── Demo.java              # Interactive demo and crash simulation
│   └── http/
│       ├── TinyKVServer.java      # REST HTTP server on Virtual Threads with security guards
│       └── ServerMain.java        # Container / CLI daemon entrypoint
└── src/test/java/io/tinykv/
    ├── KVStoreTest.java
    ├── CompactionAndRotationTest.java
    ├── CrashRecoveryTest.java
    ├── DurabilityTest.java
    ├── RecordCodecTest.java
    └── http/
        └── TinyKVServerTest.java   # HTTP REST endpoint integration tests
```
