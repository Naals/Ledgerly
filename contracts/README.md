# Event contracts

Events other services depend on. Each sample file is checked by the producer's test (the event it really
produces must equal the sample) and by every consumer's test (the sample must parse).

## TransactionPosted v1

Topic `ledger.transaction-posted.v1`, key = transaction id, headers `event-id`, `event-type`, `aggregate-type`.
Published by ledger-core for every committed posting, including reversals.

| field | meaning |
|---|---|
| `version` | contract version, 1 |
| `transactionId` | the ledger transaction |
| `type` | `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` or `REVERSAL` |
| `description` | free text |
| `reversesTransactionId` | the transaction this one undoes; null unless `type` is `REVERSAL` |
| `entries[]` | `accountId`, `amountMinor` (signed, minor units), `currency` |

## Rules

- **Adding a field is not a breaking change.** Consumers must ignore fields they do not know.
- **Renaming, removing or changing the meaning of a field is.** It needs a new version, a new topic
  (`...v2`), and both versions published until every consumer has moved.
- Delivery is at-least-once and not ordered across transactions: deduplicate on the `event-id` header.