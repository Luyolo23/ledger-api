# Double-Entry Ledger API

A Spring Boot portfolio project that models a bank-like ledger using double-entry bookkeeping. Users can register, log in with JWT, create accounts, transfer money between their own accounts, and view paginated transaction history.

API requests and manual testing are performed using **Postman or Bruno**, rather than `curl`.

## Problem Statement

A naive account table with a `balance` column is easy to corrupt: concurrent transfers, retries, and failed transactions can leave money in an inconsistent state. This project instead stores immutable ledger entries and derives balances from them. Every transfer creates exactly one `DEBIT` ledger entry and one `CREDIT` ledger entry in a single database transaction.

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

## How to Run With Docker Compose

Prerequisites:

- Docker
- Docker Compose
- Postman or Bruno

### 1. Create your environment file

From the project root, run:

```bash
cp .env.example .env
```

If you are using Windows PowerShell, use:

```powershell
Copy-Item .env.example .env
```

### 2. Configure environment variables

Edit `.env` and set safe local values. Generate a JWT secret with:

```bash
openssl rand -base64 48
```

Use the generated value as your local JWT secret, following the variable names in `.env.example`.

### 3. Start the API and PostgreSQL

```bash
docker compose up --build
```

The API should now be available at:

- Base URL: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Keep the Docker Compose terminal running while testing the API.

## How To Run Without Docker for PostgreSQL

You still need PostgreSQL running and the environment variables set.

The following commands use Bash:

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

## API Testing With Postman or Bruno

Use either Postman or Bruno to test the API manually. Both tools let you create HTTP requests, send JSON bodies, inspect responses, and save environment variables.

The following walkthrough uses the same endpoints in both tools.

### 1. Create an environment

Create an environment named `Ledger Local`.

Add these variables:

| Variable | Initial value |
|---|---|
| `baseUrl` | `http://localhost:8080` |
| `token` | Leave empty |
| `fromAccountId` | Leave empty |
| `toAccountId` | Leave empty |

In Postman, create these under **Environments** and select `Ledger Local` before sending requests.

In Bruno, create a collection environment named `Ledger Local` and select it before sending requests.

Use these variable references in your requests:

- Postman: `{{baseUrl}}`
- Bruno: `{{baseUrl}}`

Both tools support environment variables, but their scripting APIs differ slightly.

### 2. Register a user

Create a request named `Register User`.

**Method:** `POST`

**URL:**

```text
{{baseUrl}}/api/auth/register
```

Under **Body**, select `raw` and `JSON` in Postman, or the equivalent JSON body option in Bruno.

```json
{
  "email": "alice@example.com",
  "password": "password123"
}
```

Click **Send** in Postman or **Send** in Bruno.

Expected result: a successful response indicating that the user has been registered. The exact status code and response body depend on your controller implementation.

Save the email and password you used because you will need them to log in.

If you receive a duplicate-email error, register with a different email address.

### 3. Log in and save the JWT token

Create a request named `Login User`.

**Method:** `POST`

**URL:**

```text
{{baseUrl}}/api/auth/login
```

**JSON body:**

```json
{
  "email": "alice@example.com",
  "password": "password123"
}
```

A successful login should return an access token. For example:

```json
{
  "accessToken": "your-jwt-token"
}
```

Your actual response may include additional fields.

#### Postman: save the token automatically

Open the request's **Scripts → Post-response** section and add:

```javascript
const body = pm.response.json();

pm.test("Login successful", function () {
    pm.response.to.have.status(200);
});

pm.test("Access token is returned", function () {
    pm.expect(body.accessToken).to.be.a("string");
});

pm.environment.set("token", body.accessToken);
```

This assumes your login endpoint returns `200 OK` and the token is named `accessToken`. Adjust the status assertion if your implementation differs.

#### Bruno: save the token automatically

Open the request's **Tests** section and add:

```javascript
const body = res.getBody();

test("Login successful", function () {
    expect(res.status).to.equal(200);
});

test("Access token is returned", function () {
    expect(body.accessToken).to.be.a("string");
});

bru.setEnvVar("token", body.accessToken);
```

This assumes your Bruno version supports `res.getBody()`, `test`, `expect`, and `bru.setEnvVar()`.

After a successful login, the token should be available through `{{token}}`.

### 4. Configure authentication for protected requests

For every protected endpoint, add the JWT token.

**Postman:**

1. Open the request.
2. Select the **Authorization** tab.
3. Choose **Bearer Token**.
4. Enter `{{token}}`.

**Bruno:**

1. Open the request.
2. Select the **Auth** tab.
3. Choose **Bearer Token**.
4. Enter `{{token}}`.

Alternatively, configure the header manually in either tool:

| Key | Value |
|---|---|
| `Authorization` | `Bearer {{token}}` |

Do not include quotation marks around the token.

### 5. Create two accounts

Create a request named `Create Checking Account`.

**Method:** `POST`

**URL:**

```text
{{baseUrl}}/api/accounts
```

**Authorization:** Bearer token

**JSON body:**

```json
{
  "name": "Checking",
  "currency": "ZAR"
}
```

Create another request named `Create Savings Account` with the same URL and authentication.

**JSON body:**

```json
{
  "name": "Savings",
  "currency": "ZAR"
}
```

Both requests should return the newly created account details, including an `id`.

#### Postman: save each account ID

For the Checking request, add the following under **Scripts → Post-response**:

```javascript
const body = pm.response.json();

pm.test("Checking account created", function () {
    pm.expect(pm.response.code).to.be.oneOf([200, 201]);
});

pm.environment.set("fromAccountId", body.id);
```

For the Savings request, use:

```javascript
const body = pm.response.json();

pm.test("Savings account created", function () {
    pm.expect(pm.response.code).to.be.oneOf([200, 201]);
});

pm.environment.set("toAccountId", body.id);
```

#### Bruno: save each account ID

For the Checking request, add this under **Tests**:

```javascript
const body = res.getBody();

test("Checking account created", function () {
    expect(res.status).to.be.oneOf([200, 201]);
});

bru.setEnvVar("fromAccountId", body.id);
```

For the Savings request:

```javascript
const body = res.getBody();

test("Savings account created", function () {
    expect(res.status).to.be.oneOf([200, 201]);
});

bru.setEnvVar("toAccountId", body.id);
```

These scripts assume the account ID is returned at the top level of the JSON response. Adjust the field path if your API uses a different response structure.

You can now reference both IDs in subsequent requests without copying them manually.

### 6. Fund the Checking account for the demo

There is intentionally no public user-facing deposit or money-creation endpoint.

For local demonstrations, seed a balanced double-entry opening transfer from the hidden system equity account. This operation uses SQL against the local PostgreSQL database, not Postman or Bruno.

Run the following from the project root if your database container is named `db` and your Compose configuration uses the specified database credentials:

```bash
docker compose exec db psql -U ledger -d ledger
```

Alternatively, run the SQL directly through your preferred PostgreSQL client.

Replace `YOUR_CHECKING_ACCOUNT_UUID` with the actual value saved in `fromAccountId`. The following SQL assumes the system equity account and system user have already been seeded as described in the project configuration.

```sql
BEGIN;

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
        'YOUR_CHECKING_ACCOUNT_UUID'::uuid,
        10000,
        'ZAR',
        'seed-' || gen_random_uuid(),
        now()
    )
    RETURNING
        id,
        from_account_id,
        to_account_id,
        amount_minor,
        currency,
        created_at
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

COMMIT;
```

This credits the Checking account with `10000` minor units, equivalent to **R100.00**.

The SQL assumes the database schema, system-account identifiers, foreign keys, and transfer constraints match the project configuration. If your seed records or schema differ, adjust the SQL accordingly.

### 7. Make a transfer

Create a request named `Create Transfer`.

**Method:** `POST`

**URL:**

```text
{{baseUrl}}/api/transfers
```

**Authorization:** Bearer token

Add the following headers:

| Key | Value |
|---|---|
| `Content-Type` | `application/json` |
| `Idempotency-Key` | `demo-transfer-1` |

**JSON body:**

```json
{
  "fromAccountId": "{{fromAccountId}}",
  "toAccountId": "{{toAccountId}}",
  "amountMinor": 2500
}
```

This transfers R25.00 from Checking to Savings.

The first successful request should return `201 Created`.

The response should contain the transfer details, including a transfer ID. The exact response structure depends on your implementation.

#### What to verify

- The source and destination account IDs are correct.
- The amount is expressed in minor units.
- The response indicates a successful transfer.
- The transfer has a unique ID.
- The source account balance decreases by R25.00.
- The destination account balance increases by R25.00.

### 8. Retry the same transfer

Duplicate the `Create Transfer` request or send it again without changing anything.

Keep the same `Idempotency-Key` header:

```text
demo-transfer-1
```

Keep the same JSON body.

Expected result:

- The response returns `200 OK`.
- The same original transfer is returned.
- No additional debit or credit entries are created.
- Neither account balance changes a second time.

If you use the same idempotency key with a different payload, the API should return `409 Conflict`.

This verifies that retries do not move money twice.

### 9. List all accounts

Create a request named `List Accounts`.

**Method:** `GET`

**URL:**

```text
{{baseUrl}}/api/accounts
```

**Authorization:** Bearer token

No request body is required.

Expected result: a successful response containing the authenticated user's accounts.

Verify that both Checking and Savings appear in the response.

### 10. Get one account

Create a request named `Get Checking Account`.

**Method:** `GET`

**URL:**

```text
{{baseUrl}}/api/accounts/{{fromAccountId}}
```

**Authorization:** Bearer token

No request body is required.

Expected result: the details of the Checking account, including its ID and any balance information your API exposes.

After the transfer, the expected derived balance is R75.00 if the account started with R100.00 and no other transactions occurred.

### 11. View transaction history

Create a request named `Get Transaction History`.

**Method:** `GET`

**URL:**

```text
{{baseUrl}}/api/accounts/{{fromAccountId}}/transactions?page=0&size=20
```

**Authorization:** Bearer token

No request body is required.

Expected result: a paginated transaction history for the Checking account.

If supported by the API, you can also filter by date:

```text
{{baseUrl}}/api/accounts/{{fromAccountId}}/transactions?from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z&page=0&size=20
```

The date filters use ISO 8601 timestamps. Choose a date range that includes your test transactions.

### 12. Test error handling and security

Once the happy-path requests work, test the following scenarios using Postman or Bruno.

| Test | Expected result |
|---|---|
| Login with an incorrect password | `401 Unauthorized` |
| Call a protected endpoint without a token | `401 Unauthorized` |
| Request an account that does not exist | `404 Not Found` |
| Attempt to access another user's account | `404 Not Found` |
| Transfer more money than the available balance | `409 Conflict` |
| Reuse an idempotency key with a different payload | `409 Conflict` |
| Attempt a transfer between different currencies | Appropriate client error, depending on the implementation |
| Send an invalid request body | `400 Bad Request` |
| Register an already-registered email | `409 Conflict` |

These are expected behaviours based on the project's design. Confirm the exact responses against your controller and exception-handler implementation.

For the cross-user test, register a second user, log in as that user, and try accessing the first user's account using the second user's token. The account must not be accessible.

### Suggested Postman or Bruno collection structure

Organize your requests into folders so the complete workflow is easy to follow.

```text
Double-Entry Ledger API
│
├── Authentication
│   ├── Register User
│   └── Login User
│
├── Accounts
│   ├── Create Checking Account
│   ├── Create Savings Account
│   ├── List Accounts
│   └── Get Checking Account
│
├── Transfers
│   ├── Create Transfer
│   └── Retry Transfer
│
├── Transactions
│   └── Get Transaction History
│
└── Security and Error Handling
    ├── Login With Invalid Password
    ├── Request Without Token
    ├── Access Another User's Account
    ├── Transfer With Insufficient Funds
    └── Idempotency Conflict
```

This collection makes it easier to demonstrate the project during a portfolio review or technical interview.

## Design Decisions and Trade-Offs

### Double-entry ledger instead of mutable balances

A balance column updated with `UPDATE ... SET balance = balance - ?` is easy to get wrong under retries and failures. This project stores immutable ledger entries and derives balances. That is easier to audit and naturally supports transaction history.

Trade-off: deriving balances is more expensive than reading a single balance column. At larger scale, I would keep a materialized balance snapshot and periodically reconcile it against the ledger, never letting the snapshot become the source of truth.

### Integer minor units instead of `double` or `BigDecimal`

Money is stored as `BIGINT` minor units. Floating point is unacceptable for money. `BigDecimal` is also valid, but minor-unit integers avoid scale ambiguity and are common in payment systems.

Trade-off: API clients must think in minor units, and the API must clearly document conversion.

### Pessimistic locking for transfers

The transfer service uses `PESSIMISTIC_WRITE` locks on both account rows, sorted by UUID, before checking the balance.

For a high-contention operation like transferring out of an account, pessimistic locking is simple and reliable: the transaction holding the source-account row lock is the only transaction allowed to proceed through the corresponding serialized transfer operation.

Why not optimistic locking?

- Optimistic locking would require retries.
- Under high contention, retries increase latency and complexity.
- A ledger transfer must make a hard correctness decision: does this account have enough money right now? Pessimistic locking makes that decision within one serialized critical section.

Trade-off: throughput on a single heavily used account is limited. At very high scale, I would consider sharding, queueing transfers per account, or using an append-only command log with asynchronous settlement, but those add significant complexity.

### Deterministic account lock ordering

Two concurrent transfers could otherwise deadlock:

- Transfer 1 locks account A, then waits for B.
- Transfer 2 locks account B, then waits for A.

This project always sorts the two account UUIDs and locks them in that order.

### Idempotency

The transfer endpoint requires `Idempotency-Key`. The key is scoped by authenticated user. The service:

1. Acquires a PostgreSQL transaction-scoped advisory lock on `userId + ":" + idempotencyKey`.
2. Checks whether a transfer already exists for that user/key.
3. If it exists and the request payload matches, returns the original transfer.
4. If it exists and the payload differs, returns `409 Conflict`.
5. If it does not exist, performs the transfer and stores the idempotency key on the transfer row.

A database unique constraint `(user_id, idempotency_key)` is the final safety net.

Trade-off: PostgreSQL advisory locks are PostgreSQL-specific. A database-agnostic alternative is a dedicated idempotency table with insert/conflict handling.

### JWT authentication

JWT authentication is stateless and simple for a portfolio API. The JWT secret is supplied only through environment variables.

Trade-off: stateless JWT does not support immediate revocation without additional infrastructure. For production, I would add refresh tokens, token rotation, and a revocation or deny-list strategy.

### Consistent error handling

A global exception handler maps domain errors to meaningful HTTP status codes:

- `400` — validation or malformed request
- `401` — invalid credentials or missing authentication
- `404` — account not found or not owned by the user
- `409` — insufficient funds, duplicate email, idempotency conflict, or database conflict
- `500` — unexpected errors

Returning `404` for an account owned by another user avoids leaking whether the account exists.

### Testing strategy

- `TransferServiceTest` covers service logic with Mockito.
- `LedgerIntegrationTest` uses Testcontainers with real PostgreSQL.
- The concurrency test runs parallel transfers from one account and verifies no overdraft and conserved total money.
- The idempotency test verifies retrying does not move money twice and returns the original result.
- Postman or Bruno requests manually verify the HTTP endpoints, JWT authentication, request validation, response codes, and end-to-end workflow.

Manual API testing complements automated tests; it does not replace them.

## Known Limitations

- Transfers are restricted to accounts owned by the same authenticated user.
- No FX conversion. Both accounts must use the same currency.
- No public deposit or withdrawal endpoint. Demo funding is done through a hidden system equity account and SQL.
- The hidden system equity account is seeded only for ZAR.
- Balances are calculated on demand. This is fine for a portfolio project but would need snapshots, caching, or reporting tables at scale.
- Idempotency records never expire. Production would add cleanup and retention.
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
- Add database triggers and constraints for stronger double-entry invariants.
- Add Testcontainers tests for the HTTP layer and JWT authorization rules.
- Add OpenAPI examples for every endpoint.
- Add observability: structured logs, metrics, tracing, and health endpoints.
- Add rate limiting and request-size limits at the API gateway.
- Add multi-currency conversion with an explicit FX service and FX ledger entries.
- Add refresh tokens and token revocation.
- Export a reusable Postman collection or Bruno collection with environment variables, assertions, and example requests.
- Add automated API test scripts that verify successful transfers, failed transfers, authentication, and idempotent retries.
