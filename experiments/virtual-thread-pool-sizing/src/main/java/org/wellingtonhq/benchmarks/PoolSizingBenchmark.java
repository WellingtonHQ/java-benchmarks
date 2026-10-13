package org.wellingtonhq.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/** Compares JDBC throughput with virtual and platform threads at two pool sizes. */
public final class PoolSizingBenchmark {
    private static final int TASKS_IN_FLIGHT = 1_000;
    private static final int ROW_COUNT = 1_000_000;
    private static final int RANGE_SIZE = 5_000;
    private static final List<Integer> SWEEP_POOL_SIZES = List.of(20, 50, 100, 200, 400, 600, 800, 1_000);
    private static final List<Integer> REFINE_POOL_SIZES = List.of(100, 200, 400, 1_000);
    private static final List<Integer> LIVE_POOL_SIZES = List.of(2, 4, 8, 12, 16, 24, 32);
    private static final List<Integer> LIVE_COPY_SWEEP_POOL_SIZES = List.of(2, 4, 8, 12, 16, 24, 32, 40, 48);
    private static final List<Integer> LIVE_REFINE_POOL_SIZES = List.of(24, 32, 40, 48);
    private static final List<Integer> LIVE_CLONE_REFINE_POOL_SIZES = List.of(40, 48, 56, 64, 72, 80);
    private static final List<String> LIVE_FTS_QUERIES = List.of(
            "marginalia search API documentation",
            "RDAP registration data access protocol",
            "CAF C++ actor framework",
            "Kilo Code hybrid AI coding assistant",
            "T-Mobile AI 5G network",
            "macOS Golden Gate compatible Macs"
    );
    private static final Duration WARMUP = Duration.ofSeconds(5);
    private static final Duration MEASUREMENT = Duration.ofSeconds(30);
    private static final String QUERY = "SELECT COUNT(*) FROM t WHERE id BETWEEN ? AND ? + 5000";
    private static final String LIVE_FTS_SQL = "SELECT id, url FROM chunks "
            + "WHERE tsv @@ plainto_tsquery('english', ?) "
            + "ORDER BY ts_rank_cd(tsv, plainto_tsquery('english', ?)) DESC LIMIT 10";
    private static final String LIVE_VECTOR_SQL = "SELECT id, url FROM chunks "
            + "WHERE embedding IS NOT NULL ORDER BY embedding <=> ?::vector LIMIT 10";
    private static final String LIVE_VECTORS_SQL = "SELECT embedding::text FROM chunks "
            + "WHERE embedding IS NOT NULL ORDER BY id LIMIT 32";

    private static final String JDBC_URL = setting("BENCHMARK_JDBC_URL", "jdbc:postgresql://localhost:5433/benchmark");
    private static final String DB_USER = setting("BENCHMARK_DB_USER", "benchmark");
    private static final String DB_PASSWORD = setting("BENCHMARK_DB_PASSWORD", "benchmark");
    private static final String WELLISEARCH_JDBC_URL = setting(
            "WELLISEARCH_JDBC_URL", "jdbc:postgresql://localhost:5432/wellisearch");

    private PoolSizingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        RunOptions options = parseOptions(args);
        DatabaseSettings database = databaseSettings(options.wellisearch());
        waitForDatabase(database);
        List<String> liveVectors = options.wellisearch() ? loadLiveVectors(database) : List.of();
        List<Scenario> scenarios = scenariosFor(options);

        if (options.wellisearch()) {
            System.err.printf("Using Wellisearch's live index read-only; adding %d ms per index query.%n",
                    options.simulatedRttMs());
        }

        List<ScenarioResult> results = new ArrayList<>(scenarios.size());
        for (Scenario scenario : scenarios) {
            System.err.printf("Running %s...%n", scenario.name());
            ScenarioResult result = runScenario(scenario, database, options, liveVectors);
            results.add(result);
            if (result.connectionTimeouts() > 0) {
                System.err.printf("  %d pool checkout timeouts were not counted as completed requests.%n",
                        result.connectionTimeouts());
            }
        }

        printResults(results);
    }

    private static RunOptions parseOptions(String[] args) {
        boolean wellisearch = false;
        boolean sweep = false;
        boolean refine = false;
        boolean cloneRefine = false;
        boolean compareThreads = false;
        Long requestedRttMs = null;

        for (String arg : args) {
            if (arg.equals("--wellisearch")) {
                wellisearch = true;
            } else if (arg.equals("--sweep")) {
                sweep = true;
            } else if (arg.equals("--refine")) {
                refine = true;
            } else if (arg.equals("--clone-refine")) {
                cloneRefine = true;
            } else if (arg.equals("--compare-threads")) {
                compareThreads = true;
            } else if (arg.startsWith("--simulated-rtt-ms=")) {
                requestedRttMs = Long.parseLong(arg.substring("--simulated-rtt-ms=".length()));
            } else {
                throw new IllegalArgumentException(usage());
            }
        }

        int scenarioModes = (sweep ? 1 : 0) + (refine ? 1 : 0) + (cloneRefine ? 1 : 0)
                + (compareThreads ? 1 : 0);
        if (scenarioModes > 1) {
            throw new IllegalArgumentException("Choose one sweep/refinement option at a time.");
        }
        if (cloneRefine && !wellisearch) {
            throw new IllegalArgumentException("--clone-refine requires --wellisearch.");
        }
        if (compareThreads && !wellisearch) {
            throw new IllegalArgumentException("--compare-threads requires --wellisearch.");
        }
        if (requestedRttMs != null && !wellisearch) {
            throw new IllegalArgumentException("--simulated-rtt-ms is only valid with --wellisearch.");
        }

        long simulatedRttMs = requestedRttMs == null ? (wellisearch ? 20 : 0) : requestedRttMs;
        if (simulatedRttMs < 0 || simulatedRttMs > 1_000) {
            throw new IllegalArgumentException("Simulated RTT must be between 0 and 1,000 ms.");
        }

        return new RunOptions(wellisearch, sweep, refine, cloneRefine, compareThreads, simulatedRttMs);
    }

    private static String usage() {
        return "Usage: java -jar virtual-thread-pool-sizing-1.0.0.jar "
                + "[--sweep|--refine|--wellisearch "
                + "[--refine|--clone-refine|--compare-threads] [--simulated-rtt-ms=N]]";
    }

    private static DatabaseSettings databaseSettings(boolean wellisearch) {
        if (!wellisearch) {
            return new DatabaseSettings(JDBC_URL, DB_USER, DB_PASSWORD);
        }

        return new DatabaseSettings(
                WELLISEARCH_JDBC_URL,
                requiredSetting("WELLISEARCH_DB_USER"),
                requiredSetting("WELLISEARCH_DB_PASSWORD"));
    }

    private static List<Scenario> scenariosFor(RunOptions options) {
        if (options.wellisearch()) {
            if (options.compareThreads()) {
                return List.of(
                        new Scenario("Virtual threads, pool 8", ThreadType.VIRTUAL, 8),
                        new Scenario("Platform threads, pool 8", ThreadType.PLATFORM, 8),
                        new Scenario("Virtual threads, pool 56", ThreadType.VIRTUAL, 56),
                        new Scenario("Platform threads, pool 56", ThreadType.PLATFORM, 56)
                );
            }
            if (options.cloneRefine()) {
                return virtualThreadScenarios(LIVE_CLONE_REFINE_POOL_SIZES);
            }
            if (options.refine()) {
                return virtualThreadScenarios(LIVE_REFINE_POOL_SIZES);
            }
            return virtualThreadScenarios(options.sweep() ? LIVE_COPY_SWEEP_POOL_SIZES : LIVE_POOL_SIZES);
        }
        if (options.sweep()) {
            return virtualThreadScenarios(SWEEP_POOL_SIZES);
        }
        if (options.refine()) {
            return virtualThreadScenarios(REFINE_POOL_SIZES);
        }

        return List.of(
                new Scenario("Virtual threads, pool 20", ThreadType.VIRTUAL, 20),
                new Scenario("Platform threads, pool 20", ThreadType.PLATFORM, 20),
                new Scenario("Virtual threads, pool 1000", ThreadType.VIRTUAL, TASKS_IN_FLIGHT),
                new Scenario("Platform threads, pool 1000", ThreadType.PLATFORM, TASKS_IN_FLIGHT)
        );
    }

    private static List<Scenario> virtualThreadScenarios(List<Integer> poolSizes) {
        return poolSizes.stream()
                .map(poolSize -> new Scenario("Virtual threads, pool " + poolSize, ThreadType.VIRTUAL, poolSize))
                .toList();
    }

    private static ScenarioResult runScenario(
            Scenario scenario,
            DatabaseSettings database,
            RunOptions options,
            List<String> liveVectors) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setPoolName(scenario.name().replace(' ', '-').toLowerCase(Locale.ROOT));
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setMaximumPoolSize(scenario.poolSize());
        if (options.wellisearch()) {
            // The live mode is strictly read-only, including at the database session level.
            config.setReadOnly(true);
            config.setConnectionInitSql("SET default_transaction_read_only = on");
        }
        // Keep Hikari performance settings at defaults; the live-mode read-only guard is shared by every pool size.

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            return runWorkers(dataSource, scenario, options, liveVectors);
        }
    }

    private static ScenarioResult runWorkers(
            HikariDataSource dataSource,
            Scenario scenario,
            RunOptions options,
            List<String> liveVectors) throws Exception {
        ExecutorService executor = scenario.threadType() == ThreadType.VIRTUAL
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(TASKS_IN_FLIGHT);

        CountDownLatch workersReady = new CountDownLatch(TASKS_IN_FLIGHT);
        CountDownLatch startSignal = new CountDownLatch(1);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        LongAdder completedRequests = new LongAdder();
        LongAdder connectionTimeouts = new LongAdder();
        ScenarioResult result = null;

        try {
            for (int task = 0; task < TASKS_IN_FLIGHT; task++) {
                executor.submit(() -> runTask(
                        dataSource, workersReady, startSignal, running, workerFailure,
                        completedRequests, connectionTimeouts, options, liveVectors));
            }

            if (!workersReady.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Not all worker threads started for " + scenario.name());
            }

            // Start all 1,000 long-lived tasks together, then exclude warmup requests from the count.
            startSignal.countDown();
            TimeUnit.NANOSECONDS.sleep(WARMUP.toNanos());
            throwIfWorkerFailed(workerFailure);

            long countBeforeMeasurement = completedRequests.sum();
            long measurementStartedAt = System.nanoTime();
            TimeUnit.NANOSECONDS.sleep(MEASUREMENT.toNanos());
            long measurementFinishedAt = System.nanoTime();
            throwIfWorkerFailed(workerFailure);

            long requests = completedRequests.sum() - countBeforeMeasurement;
            double seconds = (measurementFinishedAt - measurementStartedAt) / 1_000_000_000.0;
            result = new ScenarioResult(scenario, requests / seconds, connectionTimeouts.sum());
        } finally {
            running.set(false);
            startSignal.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Worker threads did not stop for " + scenario.name());
            }
        }

        throwIfWorkerFailed(workerFailure);
        return result;
    }

    private static void runTask(
            HikariDataSource dataSource,
            CountDownLatch workersReady,
            CountDownLatch startSignal,
            AtomicBoolean running,
            AtomicReference<Throwable> workerFailure,
            LongAdder completedRequests,
            LongAdder connectionTimeouts,
            RunOptions options,
            List<String> liveVectors) {
        workersReady.countDown();
        try {
            startSignal.await();
            while (running.get()) {
                Connection connection;
                try {
                    connection = dataSource.getConnection();
                } catch (SQLTransientConnectionException exception) {
                    // A saturated pool can time out a waiter; keep its task alive and count no request.
                    if (running.get()) {
                        connectionTimeouts.increment();
                    }
                    continue;
                }

                try (connection) {
                    if (options.wellisearch()) {
                        runLiveIndexQueries(connection, liveVectors, options.simulatedRttMs());
                    } else {
                        try (PreparedStatement statement = connection.prepareStatement(QUERY)) {
                            int startId = ThreadLocalRandom.current().nextInt(1, ROW_COUNT - RANGE_SIZE + 1);
                            statement.setInt(1, startId);
                            statement.setInt(2, startId);

                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next() || result.getLong(1) != RANGE_SIZE + 1L) {
                                    throw new SQLException("Range count did not return the expected 5,001 rows");
                                }
                            }
                        }
                    }
                }
                completedRequests.increment();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (SQLException | RuntimeException exception) {
            if (running.get()) {
                workerFailure.compareAndSet(null, exception);
                running.set(false);
            }
        }
    }

    private static void runLiveIndexQueries(
            Connection connection,
            List<String> liveVectors,
            long simulatedRttMs) throws SQLException, InterruptedException {
        String textQuery = LIVE_FTS_QUERIES.get(ThreadLocalRandom.current().nextInt(LIVE_FTS_QUERIES.size()));
        try (PreparedStatement statement = connection.prepareStatement(LIVE_FTS_SQL)) {
            statement.setString(1, textQuery);
            statement.setString(2, textQuery);
            statement.setQueryTimeout(5);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    // Consume the returned index rows without copying page content into the benchmark.
                }
            }
        }
        pauseForSimulatedRtt(simulatedRttMs);

        String vector = liveVectors.get(ThreadLocalRandom.current().nextInt(liveVectors.size()));
        try (PreparedStatement statement = connection.prepareStatement(LIVE_VECTOR_SQL)) {
            statement.setString(1, vector);
            statement.setQueryTimeout(5);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    // Consume the HNSW hits; no result content is changed or logged.
                }
            }
        }
        pauseForSimulatedRtt(simulatedRttMs);
    }

    private static void pauseForSimulatedRtt(long simulatedRttMs) throws InterruptedException {
        if (simulatedRttMs > 0) {
            TimeUnit.MILLISECONDS.sleep(simulatedRttMs);
        }
    }

    private static void waitForDatabase(DatabaseSettings database) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        SQLException lastFailure = null;

        while (System.nanoTime() < deadline) {
            try (Connection connection = DriverManager.getConnection(
                    database.jdbcUrl(), database.username(), database.password())) {
                connection.setReadOnly(true);
                return;
            } catch (SQLException exception) {
                lastFailure = exception;
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }

        throw new IllegalStateException("Could not connect to PostgreSQL at " + database.jdbcUrl(), lastFailure);
    }

    private static List<String> loadLiveVectors(DatabaseSettings database) throws SQLException {
        List<String> vectors = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                database.jdbcUrl(), database.username(), database.password())) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(LIVE_VECTORS_SQL);
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    vectors.add(results.getString(1));
                }
            }
        }

        if (vectors.isEmpty()) {
            throw new IllegalStateException("No embedded chunks were found in the Wellisearch index.");
        }
        return List.copyOf(vectors);
    }

    private static void throwIfWorkerFailed(AtomicReference<Throwable> workerFailure) {
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("A benchmark worker failed", failure);
        }
    }

    private static void printResults(List<ScenarioResult> results) {
        System.out.printf(Locale.ROOT, "%-34s %-12s %10s %14s%n", "Scenario", "Thread type", "Pool size", "Requests/sec");
        System.out.println("-".repeat(76));
        for (ScenarioResult result : results) {
            System.out.printf(Locale.ROOT, "%-34s %-12s %10d %,14.1f%n",
                    result.scenario().name(), result.scenario().threadType().label(),
                    result.scenario().poolSize(), result.requestsPerSecond());
        }
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredSetting(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set the " + name + " environment variable for live mode.");
        }
        return value;
    }

    private enum ThreadType {
        VIRTUAL("Virtual"),
        PLATFORM("Platform");

        private final String label;

        ThreadType(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    private record Scenario(String name, ThreadType threadType, int poolSize) {
    }

    private record RunOptions(
            boolean wellisearch,
            boolean sweep,
            boolean refine,
            boolean cloneRefine,
            boolean compareThreads,
            long simulatedRttMs) {
    }

    private record DatabaseSettings(String jdbcUrl, String username, String password) {
    }

    private record ScenarioResult(Scenario scenario, double requestsPerSecond, long connectionTimeouts) {
    }
}
