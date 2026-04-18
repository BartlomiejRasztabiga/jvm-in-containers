# jvm-in-containers

A companion project to the blog post [JVM in Kubernetes: Why Your App Keeps Getting OOMKilled](https://rasztabiga.me/blog/jvm-in-kubernetes).

A minimal Spring Boot app for hands-on exploration of JVM memory behavior inside Docker containers. It exposes endpoints to allocate/free heap memory and inspect memory stats, so you can observe how the JVM responds to container limits in real time.

## Prerequisites

- Docker
- Java 24+ (for local runs without Docker)
- `curl` or any HTTP client
- Eclipse MAT for heap dump analysis ([download](https://www.eclipse.org/mat/downloads.php))

---

## Build

```bash
docker build -t jvm-in-containers .
```

---

## Run with a memory limit

```bash
docker run --rm \
  -p 8080:8080 \
  -m 512m \
  --name jvm-demo \
  jvm-in-containers
```

The app starts with a 512MB container limit. The JVM (Java 10+) reads this automatically via `UseContainerSupport` and sizes the heap to 70% (~358MB) per `MaxRAMPercentage=70.0`.

You'll see the JVM flags echoed at startup from `JAVA_TOOL_OPTIONS`.

---

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/memory/stats` | Current heap, non-heap, metaspace, thread count |
| `POST` | `/memory/allocate?mb=100` | Allocate N MB on the heap (default 100) |
| `POST` | `/memory/free` | Release all allocations, hint GC |
| `POST` | `/memory/dump` | Write heap dump to `/dumps/heap.hprof` |

### Check memory stats

```bash
curl -s http://localhost:8080/memory/stats | python3 -m json.tool
```

### Allocate heap memory

```bash
# Allocate 100MB
curl -s -X POST "http://localhost:8080/memory/allocate?mb=100"

# Allocate another 150MB
curl -s -X POST "http://localhost:8080/memory/allocate?mb=150"

# Check stats — heap used should reflect the allocation
curl -s http://localhost:8080/memory/stats | python3 -m json.tool

# Release all
curl -s -X POST http://localhost:8080/memory/free
```

### Trigger an OOMKilled

Allocate memory in a loop until the container hits its limit:

```bash
for i in $(seq 1 10); do
  echo "Allocating 50MB (round $i)..."
  curl -s -X POST "http://localhost:8080/memory/allocate?mb=50"
  echo ""
done
```

Watch what happens in `docker stats` in another terminal:

```bash
docker stats jvm-demo
```

---

## Memory analysis from a running container

### 1. Full JVM memory breakdown (NMT)

`NativeMemoryTracking=summary` is enabled in the Dockerfile. Query it:

```bash
docker exec jvm-demo jcmd 1 VM.native_memory summary scale=MB
```

Output shows committed memory by region — heap, metaspace (Class), JIT cache (Code), thread stacks, GC bookkeeping. Compare `committed` total against the container limit.

### 2. Heap and GC stats (jstat)

```bash
# Print GC stats every 2 seconds
docker exec jvm-demo jstat -gcutil 1 2000

# Columns: S0  S1  E    O     M     CCS   YGC  YGCT  FGC  FGCT  GCT
# O (old gen) creeping toward 100% = likely leak
# M (metaspace) growing without bound = classloader leak
```

### 3. Raw process memory from the kernel

```bash
# Current RSS and peak RSS (no JDK tools needed — works in JRE/distroless images too)
docker exec jvm-demo cat /proc/1/status | grep -E "^Vm(RSS|HWM|Swap|Peak)"
```

- `VmRSS`  — current physical RAM used by the process
- `VmHWM`  — peak RSS (high water mark) — useful for right-sizing containers
- `VmSwap` — should be 0; if not, the container is under memory pressure
- `VmPeak` — peak virtual address space (not physical RAM, usually much larger)

```bash
# Proportional Set Size — most accurate for shared-memory accounting
docker exec jvm-demo cat /proc/1/smaps_rollup | grep Pss
```

### 4. List all JVM processes in the container

```bash
docker exec jvm-demo jcmd
```

If PID 1 is not the JVM (e.g. you used shell-form CMD), this will show you the real PID to use in the commands above.

---

## Heap dump analysis

### Generate a dump from a running container

```bash
# Trigger via the endpoint (live objects only)
curl -s -X POST "http://localhost:8080/memory/dump?path=/dumps/heap.hprof"

# Or directly with jcmd
docker exec jvm-demo jcmd 1 GC.heap_dump filename=/dumps/heap.hprof
```

### Copy the dump to your machine

```bash
docker cp jvm-demo:/dumps/heap.hprof ./heap.hprof
```

### Analyze with Eclipse MAT

1. Open Eclipse MAT → File → Open Heap Dump → select `heap.hprof`
2. **Dominator Tree** (`Window → Heap Dump Details → Dominator Tree`) — shows which objects retain the most memory. The top entries are your biggest consumers.
3. **Leak Suspects Report** (offered on first open) — MAT's automated leak detection. Good starting point, not always right.
4. Look at **Retained Heap**, not Shallow Heap — retained is everything that would be freed if this object were collected.

---

## Experiment: compare MaxRAMPercentage values

Run the same image with different percentages and observe the actual heap ceiling:

```bash
# 70% of 512MB = ~358MB heap
docker run --rm -p 8080:8080 -m 512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:NativeMemoryTracking=summary" \
  --name jvm-70 jvm-in-containers &

# 25% (JVM default) of 512MB = ~128MB heap
docker run --rm -p 8081:8080 -m 512m \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=25.0 -XX:NativeMemoryTracking=summary" \
  --name jvm-25 jvm-in-containers &

sleep 10

curl -s http://localhost:8080/memory/stats | python3 -m json.tool
curl -s http://localhost:8081/memory/stats | python3 -m json.tool

docker stop jvm-70 jvm-25
```

---

## Experiment: what happens without container support (Java 8 style)

Simulate the old behavior by disabling `UseContainerSupport`:

```bash
docker run --rm -p 8082:8080 -m 512m \
  -e JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" \
  --name jvm-nocontainer jvm-in-containers
```

The JVM will read the host machine's total RAM and set heap accordingly — potentially much larger than the 512MB container limit. On a 16GB machine, the heap ceiling would be ~4GB. Allocating even 600MB will kill the container.

---

## Useful Docker commands

```bash
# Live resource usage for all running containers
docker stats

# Container limit and usage info
docker inspect jvm-demo | python3 -m json.tool | grep -A5 Memory

# Tail GC logs (enabled via -Xlog:gc* in Dockerfile)
docker logs -f jvm-demo 2>&1 | grep "GC\|gc"
```
