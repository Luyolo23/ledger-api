# Double-Entry Ledger API

A Spring Boot portfolio project that models a bank-like ledger using double-entry bookkeeping. Users can register, log in with JWT, create accounts, transfer money between their own accounts, and view paginated transaction history.

## Problem Statement

A naive account table with a `balance` column is easy to corrupt: concurrent transfers, retries, and failed transactions can all leave money in an inconsistent state. This project instead stores immutable ledger entries and derives balances from them. Every transfer creates exactly one `DEBIT` ledger entry and one `CREDIT` ledger entry in one database transaction.

The API must:

- Authenticate users with JWT.
- Isolate users so they can only access their own accounts.
- Prevent overdrafts under concurrent transfers.
- Make transfer retries safe using an `Idempotency-Key`.
- Keep balances derived from ledger entries, never edited directly.
- Store money as integer minor units.

## Architecture Overview

Layered architecture:

```text
HTTP controllers
    -> services / transactional business logic
        -> Spring Data repositories
            -> PostgreSQL
```

Important design points:

- `transfers` stores the business transfer.
- `ledger_entries` stores exactly two rows per successful transfer: one debit and one credit.
- Balances are derived:

```text
balanceMinor = SUM(CREDIT entries) - SUM(DEBIT entries)
```

- `DEBIT` reduces an account balance. `CREDIT` increases it.
- The transfer service locks both account rows with `PESSIMISTIC_WRITE` locks before calculating the source balance.
- Account rows are always locked in deterministic UUID order to prevent deadlocks.
- Idempotency is handled with a user-scoped idempotency key, a PostgreSQL transaction-scoped advisory lock, and a database unique constraint `(user_id, idempotency_key)`.
- Money is represented as `BIGINT` minor units in Java as `Long`/`long`, never `double` or `float`.

## How To Run With Docker Compose

Prerequisites: Docker and Docker Compose.

1. Create your local environment file:

```bash
cp .env.example .env
```

2. Edit `.env` and set safe local values. Generate a JWT secret with:

```bash
openssl rand -base64 48
```

3. Start the API and PostgreSQL:

```bash
docker compose up --build
```

The API is available at:

```text
http://localhost:8080
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## How To Run Without Docker For PostgreSQL

You still need PostgreSQL running and the environment variables set.

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/ledger'
export SPRING_DATASOURCE_USERNAME='ledger'
export SPRING_DATASOURCE_PASSWORD='your-password'
export JWT_SECRET='a-random-secret-at-least-32-bytes-long'
export JWT_EXPIRATION='PT1H'

mvn spring-boot:run
```

Flyway runs migrations automatically.

## Running Tests

```bash
mvn test
```

The integration tests use Testcontainers and require Docker.

## Example API Requests

The examples use `jq` to extract IDs. You can replace `$TOKEN`, `$FROM_ID`, and `$TO_ID` manually if you do not use `jq`.

### Register

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "alice@example.com",
    "password": "password123"
  }'
```

### Log In

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{
    "email": "alice@example.com",
    "password": "password123"
  }' | jq -r '.accessToken')

echo "$TOKEN"
```

### Create Two Accounts

```bash
FROM_ID=$(curl -s -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Checking",
    "currency": "ZAR"
  }' | jq -r '.id')

TO_ID=$(curl -s -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Savings",
    "currency": "ZAR"
  }' | jq -r '.id')

echo "$FROM_ID"
echo "$TO_ID"
```

### Demo Funding Only

There is intentionally no public user-facing "mint money" endpoint. For local demos, seed a balanced double-entry opening transfer from the hidden system equity account:

```bash
docker compose exec db psql -U ledger -d ledger \
  -v account_id="$FROM_ID" \
  -v amount=10000 <<'SQL'
WITH new_transfer AS (
    INSERT INTO transfers (
        id,
        user_id,
        from_account_id,
        to_account_id,
        amount_minor,
        currency,
        idempotency_key,
        created_at
    )
    VALUES (
        gen_random_uuid(),
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        :'account_id'::uuid,
        :amount::bigint,
        'ZAR',
        'seed-' || gen_random_uuid(),
        now()
    )
    RETURNING id, from_account_id, to_account_id, amount_minor, currency, created_at
)
INSERT INTO ledger_entries (
    id,
    transfer_id,
    account_id,
    direction,
    amount_minor,
    currency,
    created_at
)
SELECT
    gen_random_uuid(),
    id,
    from_account_id,
    'DEBIT',
    amount_minor,
    currency,
    created_at
FROM new_transfer
UNION ALL
SELECT
    gen_random_uuid(),
    id,
    to_account_id,
    'CREDIT',
    amount_minor,
    currency,
    created_at
FROM new_transfer;
SQL
```

This credits `$FROM_ID` by `10000` minor units (100.00 ZAR).

### Transfer

The first successful request returns `201 Created`. Retrying with the same `Idempotency-Key` and same payload returns `200 OK` and the original transfer body.

```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-transfer-1" \
  -H 'Content-Type: application/json' \
  -d "{
    \"fromAccountId\": \"$FROM_ID\",
    \"toAccountId\": \"$TO_ID\",
    \"amountMinor\": 2500
  }"
```

### Retry The Same Transfer

```bash
curl -i -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-transfer-1" \
  -H 'Content-Type: application/json' \
  -d "{
    \"fromAccountId\": \"$FROM_ID\",
    \"toAccountId\": \"$TO_ID\",
    \"amountMinor\": 2500
  }"
```

Expected: `200 OK` and the same `transferId`.

### List Accounts

```bash
curl -s http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Get One Account

```bash
curl -s http://localhost:8080/api/accounts/$FROM_ID \
  -H "Authorization: Bearer $TOKEN" | jq
```

### Transaction History

```bash
curl -s "http://localhost:8080/api/accounts/$FROM_ID/transactions?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq
```

With date filters:

```bash
curl -s "http://localhost:8080/api/accounts/$FROM_ID/transactions?from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq
```

## Design Decisions and Trade-Offs

### Double-entry ledger instead of mutable balances

A balance column updated with `UPDATE ... SET balance = balance - ?` is easy to get wrong under retries and failures. This project stores immutable ledger entries and derives balances. That is easier to audit and naturally supports transaction history.

Trade-off: deriving balances is more expensive than reading a single balance column. At larger scale, I would keep a materialized balance snapshot and periodically reconcile it against the ledger, never letting the snapshot be the source of truth.

### Integer minor units instead of `double` or `BigDecimal`

Money is stored as `BIGINT` minor units. Floating point is unacceptable for money. `BigDecimal` is also valid, but minor-unit integers avoid scale ambiguity and are common in payments systems.

Trade-off: API clients must think in minor units, and the API must clearly document conversion.

### Pessimistic locking for transfers

The transfer service uses `PESSIMISTIC_WRITE` locks on both account rows, sorted by UUID, before checking the balance. For a high-contention operation like transferring out of an account, pessimistic locking is simple and reliable: the transaction that holds the source-account row lock is the only transaction allowed to change that account's ledger.

Why not optimistic locking?

- Optimistic locking would require retries.
- Under high contention, retries increase latency and complexity.
- A ledger transfer must make a hard correctness decision: "does this account have enough money right now?" Pessimistic locking makes that decision within one serialized critical section.

Trade-off: throughput on a single hot account is limited. At very high scale, I would consider sharding, queueing transfers per account, or using an append-only command log with asynchronous settlement, but those add significant complexity.

### Deterministic account lock ordering

Two concurrent transfers could otherwise deadlock:

- Transfer 1 locks account A, then waits for B.
- Transfer 2 locks account B, then waits for A.

This project always sorts the two account UUIDs and locks in that order.

### Idempotency

The transfer endpoint requires `Idempotency-Key`. The key is scoped by authenticated user. The service:

1. Acquires a PostgreSQL transaction-scoped advisory lock on `userId + ":" + idempotencyKey`.
2. Checks whether a transfer already exists for that user/key.
3. If it exists and the request payload matches, returns the original transfer.
4. If it exists and the payload differs, returns `409 Conflict`.
5. If it does not exist, performs the transfer and stores the idempotency key on the transfer row.

A database unique constraint `(user_id, idempotency_key)` is the final safety net.

Trade-off: PostgreSQL advisory locks are PostgreSQL-specific. That is acceptable for this stack. A database-agnostic alternative is a dedicated idempotency table with insert/conflict handling.

### JWT authentication

JWT auth is stateless and simple for a portfolio API. The JWT secret is supplied only through environment variables.

Trade-off: stateless JWT does not support immediate revocation without extra infrastructure. For production, I would add refresh tokens, token rotation, and a revocation/deny-list strategy.

### Consistent error handling

A global exception handler maps domain errors to meaningful HTTP status codes:

- `400` validation or malformed request
- `401` invalid credentials / missing authentication
- `404` account not found or not owned by user
- `409` insufficient funds, duplicate email, idempotency conflict, database conflict
- `500` unexpected errors

Returning `404` for an account owned by another user avoids leaking whether the account exists.

### Testing strategy

- `TransferServiceTest` covers service logic with Mockito.
- `LedgerIntegrationTest` uses Testcontainers with real PostgreSQL.
- The concurrency test runs parallel transfers from one account and verifies no overdraft and conserved total money.
- The idempotency test verifies retrying does not move money twice and returns the original result.

## Known Limitations

- Transfers are restricted to accounts owned by the same authenticated user.
- No FX conversion. Both accounts must use the same currency.
- No public deposit/withdrawal endpoint. Demo funding is done through a hidden system equity account and SQL.
- The hidden system equity account is seeded only for ZAR.
- Balances are calculated on demand. This is fine for a portfolio project but would need snapshots, caching, or reporting tables at scale.
- Idempotency records never expire. Production would add cleanup/retention.
- Offset pagination can be slow for huge histories; cursor pagination would be better.
- No rate limiting, audit log export, alerting, or distributed tracing.
- No database trigger enforces exactly two ledger entries per transfer. The application plus `UNIQUE (transfer_id, direction)` enforces the intended one-debit/one-credit shape, but a trigger would be stronger.
- No refresh tokens, token revocation, MFA, or user roles.
- No direct support for account closure, holds, reversals, or disputed transactions.
- JWT secret rotation is not implemented.

## What I Would Improve With More Time

- Add an admin/internal funding API with proper authorization.
- Add a reconciliation job comparing derived balances with any cached balance snapshots.
- Add ledger-entry reversal support with explicit reversal transfers.
- Add cursor-based transaction history.
- Add idempotency-key TTL cleanup.
- Add database triggers/constraints for stronger double-entry invariants.
- Add Testcontainers tests for the HTTP layer and JWT authorization rules.
- Add OpenAPI examples for every endpoint.
- Add observability: structured logs, metrics, tracing, and health endpoints.
- Add rate limiting and request-size limits at the API gateway.
- Add multi-currency conversion with an explicit FX service and FX ledger entries.
- Add refresh tokens and token revocation.
