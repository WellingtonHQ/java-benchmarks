# Java Benchmarks

A collection of small, reproducible Java benchmarks for articles and experiments. Each experiment lives in its own folder with its own README, build file, and any supporting services or data setup.

## Experiments

- [Virtual threads and JDBC connection-pool sizing](experiments/virtual-thread-pool-sizing/README.md) compares PostgreSQL throughput across virtual and platform threads with small and concurrency-matched HikariCP pools.

## Repository layout

```text
experiments/
  virtual-thread-pool-sizing/  # Standalone, single-module Maven project
```

Add future benchmarks as self-contained directories under `experiments/`. See each experiment's README for its prerequisites and run instructions.
