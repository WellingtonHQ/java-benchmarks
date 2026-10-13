# Virtual Threads and JDBC Connection-Pool Sizing

This benchmark compares fixed-concurrency PostgreSQL request throughput across virtual threads and traditional platform threads, using a small HikariCP pool and a pool sized to match the worker count. It demonstrates whether giving 1,000 tasks 1,000 database connections improves throughput over sharing 20 connections.

Each scenario keeps 1,000 tasks in flight. A task repeatedly checks out a pooled connection, runs a count over a randomly selected 5,001-row range in a million-row table, and returns the connection. Each scenario runs for a 5-second warmup followed by a 30-second measurement. The only HikariCP setting changed between the two pool configurations is `maximumPoolSize`.

## Scenarios

| Scenario | Executor | HikariCP maximum pool size |
| --- | --- | ---: |
| Virtual threads, pool 20 | One virtual thread per task | 20 |
| Platform threads, pool 20 | Fixed pool of 1,000 platform threads | 20 |
| Virtual threads, pool 1000 | One virtual thread per task | 1,000 |
| Platform threads, pool 1000 | Fixed pool of 1,000 platform threads | 1,000 |

The PostgreSQL JDBC driver is deliberately recent (42.7.13); older driver versions can pin virtual threads during blocking I/O. If HikariCP's default 30-second checkout timeout expires under saturation, that attempt is not counted, and the same worker continues trying so all 1,000 tasks stay in the workload.

## Requirements

- Java 25 or newer.
- Maven 3.9 or newer.
- Docker Engine with Docker Compose.

## Run

From this directory:

```sh
docker compose up -d && mvn package && java -jar target/virtual-thread-pool-sizing-1.0.0.jar
```

The benchmark waits up to two minutes for PostgreSQL to finish starting and loading the fixture. The first `docker compose up` creates the table and loads one million rows; later runs reuse the named volume. Stop PostgreSQL with `docker compose down`, or remove the database volume too with `docker compose down -v` to recreate the fixture next time.

To sweep virtual-thread pool sizes while holding 1,000 tasks in flight, run:

```sh
java -jar target/virtual-thread-pool-sizing-1.0.0.jar --sweep
```

The sweep tests pool sizes 20, 50, 100, 200, 400, 600, 800, and 1,000, with the same 5-second warmup and 30-second measurement per size. It takes about five minutes after the database is ready.

To recheck the likely plateau candidates (100, 200, 400, and 1,000), run the same benchmark with `--refine`.

## Pool-size sweep results

Two full sweeps tested all eight pool sizes; a third refinement run retested 100, 200, 400, and 1,000. Each run used the same 5-second warmup and 30-second measurement. Values below are requests per second; the mean is over two runs for pool sizes 20, 50, 600, and 800, and three runs for the refined sizes.

| Pool size | Full sweep 1 | Full sweep 2 | Refine run | Mean TPS |
| ---: | ---: | ---: | ---: | ---: |
| 20 | 18,019.6 | 18,829.0 | — | 18,424.3 |
| 50 | 21,802.0 | 22,585.4 | — | 22,193.7 |
| 100 | 24,743.5 | 23,923.4 | 25,371.7 | 24,679.5 |
| 200 | 23,168.3 | 25,502.3 | 25,149.6 | 24,606.7 |
| 400 | 25,129.1 | 24,891.7 | 25,942.7 | 25,321.2 |
| 600 | 24,647.8 | 24,019.3 | — | 24,333.6 |
| 800 | 24,254.5 | 24,382.3 | — | 24,318.4 |
| 1,000 | 25,702.9 | 26,004.3 | 25,385.1 | 25,697.4 |

The highest mean was at 1,000 connections, but 400 reached about 98.5% of that throughput, and 100 reached about 96.0% using one quarter as many connections as 400. The results point to a practical knee around 100–400 connections rather than a sharply defined optimum; the small gaps among the larger pools were comparable to run-to-run variation. One checkout timeout occurred at pool size 50 in the second full sweep and was excluded from completed requests.

## Wellisearch live-index mode

Live mode reads the running Wellisearch PostgreSQL database directly. It uses only `SELECT` statements, sets each Hikari session read-only, and does not write search logs or update hit counters. The Java process needs `WELLISEARCH_JDBC_URL`, `WELLISEARCH_DB_USER`, and `WELLISEARCH_DB_PASSWORD`; the following PowerShell snippet loads those values from the running `wellisearch` container without printing them or saving them in the repository:

```powershell
$values = docker inspect wellisearch --format '{{json .Config.Env}}' | ConvertFrom-Json
$wellisearchEnv = @{}
foreach ($entry in $values) {
    $name, $value = $entry -split '=', 2
    $wellisearchEnv[$name] = $value
}
$env:WELLISEARCH_JDBC_URL = "jdbc:postgresql://localhost:$($wellisearchEnv['POSTGRES_PORT'])/$($wellisearchEnv['POSTGRES_DB'])"
$env:WELLISEARCH_DB_USER = $wellisearchEnv['POSTGRES_USER']
$env:WELLISEARCH_DB_PASSWORD = $wellisearchEnv['POSTGRES_PASSWORD']
java -jar target/virtual-thread-pool-sizing-1.0.0.jar --wellisearch --simulated-rtt-ms=20
```

Each live request runs a full-text lookup through `chunks_tsv_gin` and a nearest-neighbor lookup through `chunks_vec_hnsw`, using query phrases from the Wellisearch benchmark set and vectors loaded from existing chunks. These are real JDBC/TCP queries to the host-mapped PostgreSQL port. A fixed 20 ms delay is added after each SQL statement while its connection remains checked out, simulating two database round trips (40 ms total) per request on top of the local Docker network latency. The live mode keeps 1,000 virtual tasks in flight. Plain `--wellisearch` uses pool sizes 2–32 for the shared database; `--wellisearch --refine` tests 24, 32, 40, and 48.

One full live sweep and two refinement runs were completed against the index (about 12,000 pages and 153,000 chunks at the time). The first refinement retested 8, 16, 24, and 32; the second tested 24, 32, 40, and 48. The mean is over the available repetitions; low-pool sizes have one or two runs, while 24 and 32 have three.

| Pool size | Full sweep | Refine 1 | Refine 2 | Mean TPS |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 40.7 | — | — | 40.7 |
| 4 | 81.2 | — | — | 81.2 |
| 8 | 164.5 | 165.6 | — | 165.1 |
| 12 | 247.7 | — | — | 247.7 |
| 16 | 330.3 | 330.4 | — | 330.4 |
| 24 | 493.5 | 495.0 | 493.5 | 494.0 |
| 32 | 623.7 | 654.4 | 655.1 | 644.4 |
| 40 | — | — | 814.1 | 814.1 |
| 48 | — | — | 887.0 | 887.0 |

Throughput continued to rise through the safe 48-connection cap, so this live test did not locate a plateau or a definitive sweet spot. Pool 48 was fastest among the tested sizes; the next step would require a larger connection budget, which was not tested against this shared database. Pool-checkout timeouts are reported to stderr, excluded from TPS, and retried by their workers; counts were 990/948/826/625/624/387/31 for pools 2/4/8/12/16/24/32 in the full sweep, 860/604/280/68 for 8/16/24/32 in refine 1, and 429/227/80/0 for 24/32/40/48 in refine 2.

The live benchmark deliberately does not call the full `fn_search_local` ranking function under concurrency: a single read-only probe with a null query vector took 9.4 seconds. The test isolates its underlying GIN and HNSW index paths instead, avoiding a high-load run of that much more expensive function.

## Isolated Wellisearch clone

For a more controlled CPU/RAM benchmark, create a separate PostgreSQL 18 + pgvector container with a filtered copy of the Wellisearch index:

```powershell
.\scripts\clone-wellisearch.ps1
java -jar target/virtual-thread-pool-sizing-1.0.0.jar --wellisearch --sweep --simulated-rtt-ms=20
```

The script reads the live connection settings from the running Wellisearch container without printing them, takes a read-only custom-format dump of only `pages` and `chunks` (including their indexes), restores those tables into the clone, and points the current PowerShell session at the clone. It does not copy search/crawl logs or provider state. The clone runs on localhost port 5434 with **4 CPUs and 8 GiB RAM**; it uses its own Docker volume and the local-only `benchmark-copy-only` database password. Override the host port with `WELLISEARCH_COPY_DB_PORT` before running the script.

The clone-only `--sweep` profile tests pool sizes 2, 4, 8, 12, 16, 24, 32, 40, and 48. To continue upward on the isolated container, use `--wellisearch --clone-refine --simulated-rtt-ms=20`, which tests 40, 48, 56, 64, 72, and 80; the live shared database profile intentionally stays below that range. To refresh the copy, first remove only the clone's container and volume, then rerun the script:

```powershell
docker compose -p wellisearch-benchmark-copy -f docker-compose.wellisearch-copy.yml down -v
.\scripts\clone-wellisearch.ps1
```

The clone restored **12,198 pages and 153,205 chunks**, including the GIN full-text, trigram, and HNSW vector indexes. It was verified with a 4-CPU limit and an 8-GiB memory limit.

### Constrained-clone sweep results

One full sweep was followed by two high-pool refinements, all with 1,000 virtual tasks and a 20 ms simulated delay after each of the two index queries.

| Pool size | Full sweep | High refine 1 | High refine 2 | Mean TPS |
| ---: | ---: | ---: | ---: | ---: |
| 2 | 43.5 | — | — | 43.5 |
| 4 | 87.5 | — | — | 87.5 |
| 8 | 176.8 | — | — | 176.8 |
| 12 | 266.5 | — | — | 266.5 |
| 16 | 355.6 | — | — | 355.6 |
| 24 | 535.9 | — | — | 535.9 |
| 32 | 715.4 | — | — | 715.4 |
| 40 | 895.0 | 894.7 | 894.1 | 894.6 |
| 48 | 1,074.0 | 1,067.5 | 1,071.3 | 1,070.9 |
| 56 | — | 1,148.4 | 1,164.1 | 1,156.3 |
| 64 | — | 1,060.7 | 1,032.6 | 1,046.7 |
| 72 | — | 1,015.0 | 1,032.2 | 1,023.6 |
| 80 | — | 1,009.1 | 1,017.5 | 1,013.3 |

For this cloned workload, **pool size 56 was the sweet spot among the tested sizes**: it averaged 1,156.3 TPS, versus 1,070.9 at 48 and 1,046.7 at 64. Throughput declined at larger pools, consistent with the 4-CPU database becoming saturated. The 20 ms injected delay per SQL round trip is part of this result; other query costs or latency targets can move the optimum. Pool-checkout timeouts were retried and excluded from TPS; there were 10 and 12 timeouts at pool 56 in its two refinement runs.

To compare virtual threads with a fixed executor of 1,000 platform (OS) threads, run this **against the isolated clone only**:

```powershell
java -jar target/virtual-thread-pool-sizing-1.0.0.jar --wellisearch --compare-threads --simulated-rtt-ms=20
```

This compares both thread types at pool sizes 8 and 56 using the same index queries and simulated delay. Two trials produced:

| Pool | Thread type | Trial 1 TPS | Trial 2 TPS | Mean TPS | Checkout timeouts, trials 1 / 2 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 8 | Virtual | 176.8 | 176.7 | 176.8 | 818 / 800 |
| 8 | Platform | 177.2 | 177.4 | 177.3 | 904 / 930 |
| 56 | Virtual | 1,240.8 | 1,198.2 | 1,219.5 | 24 / 7 |
| 56 | Platform | 1,241.0 | 1,230.1 | 1,235.6 | 302 / 295 |

Throughput was effectively the same for both thread types; the platform-thread mean was about 1.3% higher at pool 56, a difference too small to call a winner from two trials. Platform threads had more pool-checkout timeouts, especially at pool 56. Those attempts were retried and excluded from TPS. The results show the main benefit of virtual threads here is handling the 1,000 blocked tasks without creating 1,000 OS threads, rather than increasing database throughput.

The default synthetic PostgreSQL 16 setup remains available separately on localhost port 5433.

## Memory and executor-queue comparison

`RequestComparison` models **finite requests**, with 1,000 outstanding request slots. Each completion submits one replacement. This differs from the original long-lived worker loops: a small executor can process the whole workload instead of having its first workers loop forever while the remaining tasks never start.

Each scenario runs in a fresh JVM, with identical `-Xms64m -Xmx256m -Xss1m -XX:+UseG1GC -XX:NativeMemoryTracking=summary` settings. All use the isolated 4-CPU/8-GiB clone and a 56-connection pool, two real index queries, and 20 ms simulated DB delay after each query. An optional external-I/O delay occurs **before acquiring a DB connection**. This delay models a blocking downstream call; it is a sleep, not an actual HTTP/network impairment test.

Build with `mvn clean package`, then use PowerShell 7 and a JDK providing `java` and `jcmd`:

```powershell
.\scripts\compare-memory.ps1 -Trials 2 -Modes @('virtual','platform200') -Delays @(0,100,500)
```

The script targets the clone automatically, reverses executor order in the second trial, samples Windows working set (resident memory), private process memory, and OS-thread count every second during measurement, and samples JVM native thread-memory accounting once per trial. JVM startup, warmup, and shutdown are excluded from process-memory averages. Database memory is not part of Java-process memory. Temporary stdout/stderr logs preserve each trial's result. All scenarios use a 5-second warmup and 30-second measurement.

This finite-request profile counts only requests whose execution starts and finishes during measurement; in-flight requests crossing either boundary are excluded. That introduces boundary bias for slower requests, and these short closed-loop trials are exploratory rather than a statistically controlled capacity or fairness study. Hikari checkout is not guaranteed to serve requests in submission order.

### Virtual threads versus 200 OS workers

These values average two trials per cell. Queue and checkout waits are means for successfully completed requests, not percentiles or deadline guarantees.

| External I/O per request | Executor | TPS | Private MiB | Resident MiB | OS threads | Queue wait ms | DB checkout wait ms | Request latency ms |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 ms | Virtual | 880.7 | 189.5 | 117.5 | 62 | 0.07 | 1,019.3 | 1,080.6 |
| 0 ms | 200 OS workers | 912.2 | 207.4 | 123.5 | 245 | 871.0 | 2.1 | 934.3 |
| 100 ms | Virtual | 880.0 | 195.4 | 123.5 | 62 | 0.07 | 931.8 | 1,094.1 |
| 100 ms | 200 OS workers | 908.1 | 206.6 | 122.7 | 245 | 874.8 | 50.4 | 1,093.5 |
| 500 ms | Virtual | 818.2 | 192.1 | 118.3 | 62 | 0.07 | 611.2 | 1,179.9 |
| 500 ms | 200 OS workers | 346.4 | 210.0 | 123.5 | 245 | 2,269.1 | 0.04 | 2,836.3 |

At 500 ms external I/O, virtual-thread trials measured 877.1 and 759.3 TPS; 200-worker trials measured 346.5 and 346.3 TPS. Virtual threads averaged **2.36× throughput** and lower end-to-end latency. At 0/100 ms external I/O, the 200-worker executor was already large enough to feed the database and virtual threads did not increase throughput. Private process memory was lower with virtual threads, but the resident-memory gap was modest and the 100 ms virtual-thread run used slightly more resident memory. This does not establish that every application will use less total memory with virtual threads.

### Native stack costs and the 1,000-worker baseline

In the first pair of no-external-I/O trials, the 1,000-worker executor averaged 187.5 MiB resident and 291.8 MiB private process memory, versus 119.6 MiB resident and 191.4 MiB private for virtual threads. Native Memory Tracking showed about **1,043 MiB reserved / 72.6 MiB committed** in the JVM's `Thread` category for 1,000 OS workers, versus **57.2 MiB reserved / 3.5 MiB committed** for virtual threads. The 200-worker executor used about **240.8 MiB reserved / 16.1 MiB committed**. Reserved address space is not consumed physical RAM, and the JVM `Thread` category excludes virtual-thread stack chunks, which live in the heap. OS-thread counts include JVM service threads and virtual-thread carrier threads, not just request workers.

### Where requests wait and when they time out

With 200 workers and 1,000 outstanding requests, most pending requests initially wait in the executor's queue. With virtual threads, each request is scheduled quickly in this test and its wait is primarily at connection checkout. That moves the waiting; it does not eliminate the database limit or the end-to-end request deadline.

`Executors.newFixedThreadPool` uses an unbounded queue and **does not automatically expire queued tasks**. A client, server framework, or explicit deadline/cancellation policy must enforce that timeout. Hikari's checkout timeout starts when `getConnection()` is called, so executor-queue time is not included. An HTTP client's timeout can also leave server work queued or running unless cancellation is propagated.

The benchmark records whether a successful request started more than one second after submission; it does not expire those requests. In the 500 ms / 200-worker trials, all 10,400 and 10,393 measured successes crossed that queue-wait threshold; none did with virtual threads. Virtual requests can still exceed a one-second end-to-end deadline while waiting at the DB.

Checkout timeouts were 115 and 118 for the 200-worker/no-external-I/O trials, and zero for the paired virtual-thread trials and both 100/500 ms comparisons. The initial 1,000-worker/no-external-I/O trials had 929 and 921 checkout timeouts. Consequently, latency statistics for successful requests can hide starved requests; compare failure counts alongside TPS and latency. Retries maintain the closed-loop load, rather than represent production deadline handling.

The default local connection settings are `jdbc:postgresql://localhost:5433/benchmark`, username `benchmark`, and password `benchmark`. Compose binds only to localhost and maps host port 5433 to PostgreSQL's port 5432; override the host port with `BENCHMARK_DB_PORT`. Override the application connection settings with `BENCHMARK_JDBC_URL`, `BENCHMARK_DB_USER`, and `BENCHMARK_DB_PASSWORD` if needed.

## Sample output

Measured on a Windows 11 machine with Java 25.0.4.1 and Docker Desktop configured for 20 CPUs and about 31 GiB of memory. This run did **not** support the hypothesis that the small virtual-thread pool matches or beats the large pool: the 1,000-connection virtual-thread scenario measured about 51% higher throughput than the 20-connection scenario. Replace these numbers with results from your own machine when comparing hardware or database configurations.

```text
Scenario                           Thread type   Pool size   Requests/sec
----------------------------------------------------------------------------
Virtual threads, pool 20           Virtual              20       20,739.5
Platform threads, pool 20          Platform             20       22,275.7
Virtual threads, pool 1000         Virtual            1000       31,395.6
Platform threads, pool 1000        Platform           1000       31,716.5
```

The platform-thread / pool-20 scenario also had 69 HikariCP checkout timeouts; those attempts were excluded from its completed-request count, and the workers continued running.

Results vary with CPU, storage, available memory, Docker resource limits, and PostgreSQL settings. The benchmark prints measured results as-is; it does not enforce an expected winner.
