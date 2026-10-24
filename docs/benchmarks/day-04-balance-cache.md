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