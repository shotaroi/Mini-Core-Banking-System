# Mini Core Banking System

A portfolio-grade Spring Boot 4 (Java 21) REST API implementing a mini core banking system with JWT authentication, accounts, deposits, withdrawals, transfers, transaction ledger, and audit logging.

## Architecture

### Overview

The system follows a layered architecture:

```
┌─────────────────┐
│   REST API      │  Controllers (auth, accounts, transfers, ledger, admin)
└────────┬────────┘
         │
┌────────▼────────┐
│   Services      │  Business logic, validation, idempotency
└────────┬────────┘
         │
┌────────▼────────┐
│  Repositories   │  JPA / Spring Data
└────────┬────────┘
         │
┌────────▼────────┐
│   PostgreSQL    │  Persistence + Flyway migrations
└─────────────────┘
```

### Key Design Decisions

- **Correctness over features**: Never allow negative balance. Strict validation on amount, currency, and ownership.
- **Transactions**: All money-moving operations are wrapped in `@Transactional` boundaries.
- **Optimistic locking**: `Account` uses JPA `@Version`; concurrent modifications trigger retries (up to 3 attempts).
- **Idempotency**: Transfers use `Idempotency-Key` header to prevent double execution on retries.
- **BigDecimal**: All monetary amounts use `BigDecimal` with scale 2; currency stored alongside (e.g. "SEK").
- **Audit log**: All money-moving operations are recorded for compliance.

### Data Model

| Entity      | Description                                         |
|-------------|-----------------------------------------------------|
| Customer    | id, email (unique), passwordHash, role, createdAt   |
| Account     | id, customerId, iban (unique), currency, balance, version, createdAt |
| LedgerEntry | id, accountId, type (DEPOSIT\|WITHDRAW\|TRANSFER_IN\|TRANSFER_OUT), amount, currency, counterpartyAccountId, reference, createdAt |
| Transfer    | id, fromAccountId, toAccountId, amount, currency, status, idempotencyKey (unique), createdAt |
| AuditLog    | id, actorCustomerId, action, details, createdAt      |

## Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose (for PostgreSQL)

## Running the Application

### 1. Start PostgreSQL

```bash
docker compose up -d
```

### 2. Run the application

```bash
mvn spring-boot:run
```

The API is available at `http://localhost:8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`.

### 3. (Optional) Set JWT secret for production

```bash
export JWT_SECRET="your-64-char-secret-at-least-for-hs256-algorithm-xxxxxxxxx"
mvn spring-boot:run
```

## Sample cURL Commands

### Register

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}'
```

### Login

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}' \
  | jq -r '.accessToken')
echo $TOKEN
```

### Create account

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"currency":"SEK"}'
```

### Deposit

```bash
curl -X POST http://localhost:8080/api/accounts/1/deposit \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":1000.00,"currency":"SEK","reference":"Initial deposit"}'
```

### Withdraw

```bash
curl -X POST http://localhost:8080/api/accounts/1/withdraw \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"currency":"SEK","reference":"ATM withdrawal"}'
```

### Transfer (with Idempotency-Key)

```bash
curl -X POST http://localhost:8080/api/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":50.00,"currency":"SEK","reference":"Payment"}'
```

### Ledger (paginated)

```bash
curl -X GET "http://localhost:8080/api/accounts/1/ledger?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

With date range:

```bash
curl -X GET "http://localhost:8080/api/accounts/1/ledger?from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

### Admin audit (requires ADMIN role)

```bash
# First create an admin user: register normally, then update role in DB:
# UPDATE customer SET role = 'ADMIN' WHERE email = 'admin@example.com';

curl -X GET "http://localhost:8080/api/admin/audit?page=0&size=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## Testing

### Run all tests

```bash
mvn test
```

### Run unit tests only

```bash
mvn test -Dtest=*Test
```

### Run integration tests only

```bash
mvn test -Dtest=*IntegrationTest
```

Integration tests use **Testcontainers** with PostgreSQL 16. Ensure Docker is running.

### Test coverage

- **Unit tests**: `TransferServiceTest` – business rules (idempotency, insufficient funds, currency mismatch, ownership).
- **Integration tests**: `TransferIntegrationTest` – successful transfer (ledger + balances), idempotency (same key → same result), idempotency conflict (different payload → 409), concurrency (20 transfers, no negative balance, correct final sums).

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/auth/register | Register customer |
| POST | /api/auth/login | Login, returns JWT |
| POST | /api/accounts | Create account (currency) |
| GET | /api/accounts | List own accounts |
| GET | /api/accounts/{id} | Get account details |
| POST | /api/accounts/{id}/deposit | Deposit |
| POST | /api/accounts/{id}/withdraw | Withdraw |
| POST | /api/transfers | Transfer (requires Idempotency-Key) |
| GET | /api/accounts/{id}/ledger | Paginated ledger entries |
| GET | /api/admin/audit | List audit logs (ADMIN) |

## Error Handling

All errors return consistent JSON:

```json
{
  "timestamp": "2025-02-11T12:00:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Amount must be positive",
  "path": "/api/transfers"
}
```

## Notes on Transactions, Optimistic Locking, Idempotency

### Transactions

- Deposit, withdraw, and transfer are `@Transactional`.
- Balance updates and ledger entries are committed atomically.
- Rollback on any exception (e.g. validation, insufficient funds).

### Optimistic Locking

- `Account` has a `version` column (JPA `@Version`).
- On concurrent updates, Hibernate detects version mismatch and throws `ObjectOptimisticLockingFailureException`.
- `TransferService` retries up to 3 times on optimistic lock failure.
- Client receives 409 Conflict with message "Concurrent modification. Please retry."

### Idempotency

- Transfer endpoint requires `Idempotency-Key` header.
- Same key + same payload → returns original success response (no double transfer).
- Same key + different payload → 409 Conflict.

## Project Structure

```
src/main/java/com/shotaroi/bank/
├── config/
├── security/
├── customer/
├── account/
├── ledger/
├── transfer/
├── audit/
└── common/
    ├── exceptions/
    └── dto/
src/test/java/com/shotaroi/bank/
├── integration/
└── unit/
```

## License

MIT
