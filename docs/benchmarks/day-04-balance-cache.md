# Balance cache: measurements

Two kinds of measurements are recorded here. Compare numbers only within the same kind and on the same machine.

- **SQL level** (`pgbench`): the SQL each operation runs (lock the accounts in id order, read the balance, insert
  the transaction and its entries), replayed against PostgreSQL 16 on a 1 vCPU / 4 GB machine. Data: 54 accounts,
  311,000 transactions, 622,000 entries. This machine is shared, so run-to-run noise is about 20%.
- **End to end** (`TransferBenchmark`): the real service (Spring, JDBC, Testcontainers PostgreSQL) on a developer machine.

## Baseline: a balance is the SUM of the account's entries

### Reading one balance (SQL level, 1 client)

| entries on the account | latency per read |
|---|---|
| 0 | 0.068 ms |
| 1,000 | 0.226 ms |
| 10,000 | 1.462 ms |
| 100,000 | 15.053 ms |

The cost grows in step with the account's history, and every debit has to read the balance first.

### Transfers (SQL level, 8 clients, 15 s, 50 accounts with 4,000 entries each)

| | transfers/s | mean | p50 | p95 | p99 |
|---|---|---|---|---|---|
| SUM-based balance | 515 | 15.53 ms | 13.65 ms | 27.88 ms | 38.63 ms |

### End to end (`TransferBenchmark`, before any change)

Machine: FILL IN (CPU, cores, RAM)

PASTE ledger-core/target/benchmark-report.md HERE


## After: cached customer balances (V7) without caching the bank's own accounts (V8)

### Reading one balance (SQL level, 1 client)

| entries on the account | SUM (baseline) | cached |
|---|---|---|
| 0 | 0.068 ms | 0.041 ms |
| 1,000 | 0.226 ms | 0.038 ms |
| 10,000 | 1.462 ms | 0.038 ms |
| 100,000 | 15.053 ms | 0.041 ms |

The cached read no longer depends on the account's history. An earlier run on the V7 schema gave 0.067 to
0.071 ms, so treat the result as "flat", not as an exact figure.

### Transfers (SQL level, 8 clients, 15 s, same data)

| | transfers/s | mean | p50 | p95 | p99 |
|---|---|---|---|---|---|
| SUM-based balance (baseline) | 515 | 15.53 ms | 13.65 ms | 27.88 ms | 38.63 ms |
| cached balance (V8) | 1,056 | 7.58 ms | 5.75 ms | 11.74 ms | 17.03 ms |

An earlier run on the V7 schema gave 862 transfers/s. With about 20% noise on this machine, read the result as
"roughly 1.7 to 2 times the throughput".

### The hot row (SQL level, cash deposits, each transaction held open 2 ms to emulate application work)

| | 1 client | 8 clients |
|---|---|---|
| V7: the bank's cash account is cached | 274 ops/s | 233 ops/s (mean 34.3 ms) |
| V8: the bank's own accounts are not cached | 270 ops/s | 996 ops/s (mean 8.0 ms) |

### Correctness checks (SQL level)

- Two postings writing the same two accounts in opposite entry order: one session was killed with
  `deadlock detected`. In ascending order: none.
- 20 parallel withdrawals of 100 from a balance of 1,000, with no application lock and no balance read: 10
  committed, 10 were refused by `ck_accounts_balance_non_negative`, and the balance ended at exactly 0.
- After every benchmark run, the cached balance equalled the sum of entries for all customer accounts.

### End to end (`TransferBenchmark`, after V8)

Machine: FILL IN (the same machine as the baseline)

PASTE ledger-core/target/benchmark-report.md HERE


## Day 5: the stamping trigger (V9)

Transfers (SQL level, 8 clients, 15 s, same data and machine as above):

| | transfers/s | mean | p50 | p95 | p99 |
|---|---|---|---|---|---|
| cached balance (V8) | 1,056 | 7.58 ms | 5.75 ms | 11.74 ms | 17.03 ms |
| with entry stamping (V9) | 1,081 | 7.40 ms | 5.80 ms | 12.72 ms | 18.16 ms |

Within the noise of this machine: the stamping costs nothing measurable.


## Day 6: the outbox insert

Transfers (SQL level, 8 clients, 12 s per run, run in turns on the same data and machine):

| | run 1 | run 2 |
|---|---|---|
| without the outbox insert | 862 transfers/s | 1,240 transfers/s |
| with the outbox insert | 1,182 transfers/s | 1,127 transfers/s |

The difference between the two kinds is smaller than the difference between two runs of the same kind, so the
outbox costs nothing measurable here. 27,542 events averaged 372 bytes of payload and took 17 MB.