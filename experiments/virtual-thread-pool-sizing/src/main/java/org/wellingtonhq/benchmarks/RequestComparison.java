package org.wellingtonhq.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** Finite requests expose executor queueing; run each mode in a fresh JVM for memory comparisons. */
public final class RequestComparison {
    private static final String FTS = "SELECT id FROM chunks WHERE tsv @@ plainto_tsquery('english', ?) "
            + "ORDER BY ts_rank_cd(tsv, plainto_tsquery('english', ?)) DESC LIMIT 10";
    private static final String VECTOR = "SELECT id FROM chunks WHERE embedding IS NOT NULL "
            + "ORDER BY embedding <=> ?::vector LIMIT 10";
    private static final List<String> PHRASES = List.of("marginalia search API documentation",
            "RDAP registration data access protocol", "CAF C++ actor framework",
            "Kilo Code hybrid AI coding assistant", "T-Mobile AI 5G network",
            "macOS Golden Gate compatible Macs");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: RequestComparison virtual|platform1000|platform200|platform56 external-delay-ms");
        }
        int externalDelayMs = Integer.parseInt(args[1]);
        if (externalDelayMs < 0 || externalDelayMs > 1_000) {
            throw new IllegalArgumentException("External delay must be 0–1000 ms.");
        }
        ExecutorService executor = switch (args[0]) {
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            case "platform1000" -> Executors.newFixedThreadPool(1_000);
            case "platform200" -> Executors.newFixedThreadPool(200);
            case "platform56" -> Executors.newFixedThreadPool(56);
            default -> throw new IllegalArgumentException("Unknown executor mode");
        };
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(required("WELLISEARCH_JDBC_URL"));
        config.setUsername(required("WELLISEARCH_DB_USER"));
        config.setPassword(required("WELLISEARCH_DB_PASSWORD"));
        config.setMaximumPoolSize(56);
        config.setReadOnly(true);
        config.setConnectionInitSql("SET default_transaction_read_only=on");

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean measuring = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LongAdder completed = new LongAdder();
        LongAdder queueNs = new LongAdder();
        LongAdder checkoutNs = new LongAdder();
        LongAdder latencyNs = new LongAdder();
        LongAdder lateStarts = new LongAdder();
        LongAdder checkoutTimeouts = new LongAdder();

        try (HikariDataSource pool = new HikariDataSource(config)) {
            List<String> vectors = new ArrayList<>();
            try (Connection connection = pool.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT embedding::text FROM chunks WHERE embedding IS NOT NULL ORDER BY id LIMIT 32");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    vectors.add(rows.getString(1));
                }
            }
            if (vectors.isEmpty()) {
                throw new IllegalStateException("Index has no vectors");
            }

            // Each of 1,000 request slots submits a replacement only after its finite request ends.
            // Smaller executors thus have queued requests, rather than 56 infinite loops starving a queue.
            class Request implements Runnable {
                private final long submittedAt = System.nanoTime();

                @Override
                public void run() {
                    long startedAt = System.nanoTime();
                    long checkoutStartedAt = 0;
                    long acquiredAt = 0;
                    boolean measureThis = measuring.get();
                    try {
                        if (!running.get()) {
                            return;
                        }
                        TimeUnit.MILLISECONDS.sleep(externalDelayMs);
                        checkoutStartedAt = System.nanoTime();
                        try (Connection connection = pool.getConnection()) {
                            acquiredAt = System.nanoTime();
                            String phrase = PHRASES.get(ThreadLocalRandom.current().nextInt(PHRASES.size()));
                            try (PreparedStatement statement = connection.prepareStatement(FTS)) {
                                statement.setString(1, phrase);
                                statement.setString(2, phrase);
                                execute(statement);
                            }
                            TimeUnit.MILLISECONDS.sleep(20);
                            try (PreparedStatement statement = connection.prepareStatement(VECTOR)) {
                                statement.setString(1, vectors.get(ThreadLocalRandom.current().nextInt(vectors.size())));
                                execute(statement);
                            }
                            TimeUnit.MILLISECONDS.sleep(20);
                        }
                        if (measureThis && measuring.get()) {
                            completed.increment();
                            queueNs.add(startedAt - submittedAt);
                            checkoutNs.add(acquiredAt - checkoutStartedAt);
                            latencyNs.add(System.nanoTime() - submittedAt);
                            if (startedAt - submittedAt > TimeUnit.SECONDS.toNanos(1)) {
                                lateStarts.increment();
                            }
                        }
                    } catch (java.sql.SQLTransientConnectionException exception) {
                        if (measuring.get()) {
                            checkoutTimeouts.increment();
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } catch (Exception exception) {
                        if (running.get()) {
                            failure.compareAndSet(null, exception);
                            running.set(false);
                        }
                    } finally {
                        if (running.get()) {
                            executor.submit(new Request());
                        }
                    }
                }
            }

            try {
                for (int slot = 0; slot < 1_000; slot++) {
                    executor.submit(new Request());
                }
                Thread.sleep(Duration.ofSeconds(5));
                System.out.println("MEASUREMENT_START");
                measuring.set(true);
                long begin = System.nanoTime();
                Thread.sleep(Duration.ofSeconds(30));
                measuring.set(false);
                double seconds = (System.nanoTime() - begin) / 1e9;
                if (failure.get() != null) {
                    throw new IllegalStateException("Request failed", failure.get());
                }
                long count = completed.sum();
                System.out.printf(Locale.ROOT,
                        "mode=%s external_ms=%d completed=%d tps=%.1f queue_ms=%.2f checkout_ms=%.2f "
                                + "latency_ms=%.2f queue_over_1s=%d checkout_timeouts=%d%n",
                        args[0], externalDelayMs, count, count / seconds,
                        queueNs.sum() / (count * 1e6), checkoutNs.sum() / (count * 1e6),
                        latencyNs.sum() / (count * 1e6), lateStarts.sum(), checkoutTimeouts.sum());
            } finally {
                running.set(false);
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Executor did not stop");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void execute(PreparedStatement statement) throws Exception {
        statement.setQueryTimeout(5);
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rows.getLong(1);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name);
        }
        return value;
    }
}
