-- Create a million-row table with enough data per row to make range counts do real work.
CREATE TABLE t (
    id INTEGER PRIMARY KEY,
    payload TEXT NOT NULL
);

INSERT INTO t (id, payload)
SELECT id, repeat(md5(id::text), 8)
FROM generate_series(1, 1000000) AS rows(id);

-- Mark freshly loaded pages all-visible and give the planner up-to-date statistics.
VACUUM (FREEZE, ANALYZE) t;
