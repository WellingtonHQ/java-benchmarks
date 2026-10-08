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
    private static final Duration WARMUP = Duration.ofSeconds(5);
    private static final Duration MEASUREMENT = Duration.ofSeconds(30);
    private static final String QUERY = "SELECT COUNT(*) FROM t WHERE id BETWEEN ? AND ? + 5000";

    private static final String JDBC_URL = setting("BENCHMARK_JDBC_URL", "jdbc:postgresql://localhost:5433/benchmark");
    private static final String DB_USER = setting("BENCHMARK_DB_USER", "benchmark");
    private static final String DB_PASSWORD = setting("BENCHMARK_DB_PASSWORD", "benchmark");

    private PoolSizingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        waitForDatabase();

        List<Scenario> scenarios = scenariosFor(args);

        List<ScenarioResult> results = new ArrayList<>(scenarios.size());
        for (Scenario scenario : scenarios) {
            System.err.printf("Running %s...%n", scenario.name());
            ScenarioResult result = runScenario(scenario);
            results.add(result);
            if (result.connectionTimeouts() > 0) {
                System.err.printf("  %d pool checkout timeouts were not counted as completed requests.%n",
                        result.connectionTimeouts());
            }
        }

        printResults(results);
    }

    private static List<Scenario> scenariosFor(String[] args) {
        if (args.length == 0) {
            return List.of(
                    new Scenario("Virtual threads, pool 20", ThreadType.VIRTUAL, 20),
                    new Scenario("Platform threads, pool 20", ThreadType.PLATFORM, 20),
                    new Scenario("Virtual threads, pool 1000", ThreadType.VIRTUAL, TASKS_IN_FLIGHT),
                    new Scenario("Platform threads, pool 1000", ThreadType.PLATFORM, TASKS_IN_FLIGHT)
            );
        }

        if (args.length == 1 && args[0].equals("--sweep")) {
            return virtualThreadScenarios(SWEEP_POOL_SIZES);
        }

        if (args.length == 1 && args[0].equals("--refine")) {
            return virtualThreadScenarios(REFINE_POOL_SIZES);
        }

        throw new IllegalArgumentException("Usage: java -jar virtual-thread-pool-sizing-1.0.0.jar [--sweep|--refine]");
    }

    private static List<Scenario> virtualThreadScenarios(List<Integer> poolSizes) {
        return poolSizes.stream()
                .map(poolSize -> new Scenario("Virtual threads, pool " + poolSize, ThreadType.VIRTUAL, poolSize))
                .toList();
    }

    private static ScenarioResult runScenario(Scenario scenario) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setPoolName(scenario.name().replace(' ', '-').toLowerCase(Locale.ROOT));
        config.setJdbcUrl(JDBC_URL);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        config.setMaximumPoolSize(scenario.poolSize());
        // All other settings are HikariCP defaults, so pool size is the variable under test.

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            return runWorkers(dataSource, scenario);
        }
    }

    private static ScenarioResult runWorkers(HikariDataSource dataSource, Scenario scenario) throws Exception {
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
                        completedRequests, connectionTimeouts));
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
            LongAdder connectionTimeouts) {
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

                try (connection; PreparedStatement statement = connection.prepareStatement(QUERY)) {
                    int startId = ThreadLocalRandom.current().nextInt(1, ROW_COUNT - RANGE_SIZE + 1);
                    statement.setInt(1, startId);
                    statement.setInt(2, startId);

                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getLong(1) != RANGE_SIZE + 1L) {
                            throw new SQLException("Range count did not return the expected 5,001 rows");
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

    private static void waitForDatabase() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        SQLException lastFailure = null;

        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASSWORD)) {
                return;
            } catch (SQLException exception) {
                lastFailure = exception;
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }

        throw new IllegalStateException("Could not connect to PostgreSQL at " + JDBC_URL, lastFailure);
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

    private record ScenarioResult(Scenario scenario, double requestsPerSecond, long connectionTimeouts) {
    }
}
