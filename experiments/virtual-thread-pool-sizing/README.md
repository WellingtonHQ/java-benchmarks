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

Each live request runs a full-text lookup through `chunks_tsv_gin` and a nearest-neighbor lookup through `chunks_vec_hnsw`, using query phrases from the Wellisearch benchmark set and vectors loaded from existing chunks. These are real JDBC/TCP queries to the host-mapped PostgreSQL port. A fixed 20 ms delay is added after each SQL statement while its connection remains checked out, simulating two database round trips (40 ms total) per request on top of the local Docker network latency. The live sweep keeps 1,000 virtual tasks in flight and tests pool sizes 2, 4, 8, 12, 16, 24, and 32; `--wellisearch --refine` retests 24, 32, 40, and 48. The largest tested pool is capped at 48 for this shared database, which has `max_connections=100`.

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
