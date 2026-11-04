# Engineering rules: database, API efficiency and testing

These rules apply to all four TinQa services:

- Procurement BE: `TinQa_Procurement_Backend` (Spring Boot 3.5, Java 21, PostgreSQL, Flyway, Redis)
- Ecommerce BE: `TinQa_Ecommerce_BE` (Spring Boot 4.1, Java 21, PostgreSQL, Flyway)
- Procurement Web UI: `Tinqa-Procurement-Web-UI` (React 19, Vite, TypeScript)
- Storefront: `TinQa_Ecommerce_FE` (React 19, Vite, TypeScript)

Every change request follows them. A rule may be broken only with a written reason in the code or in the pull
request. Each repo holds an identical copy of this file: change all four copies together. Planned and finished
optimization work is tracked in `Tinqa-Procurement-Web-UI/docs/optimization-roadmap.md`.

## 1. Database (both backends)

### Schema and migrations
1. The schema changes only through Flyway migrations named `V{n}__snake_case.sql` (Procurement:
   `src/main/resources/db/migration`, Ecommerce: the same path). Never edit a migration that has run anywhere; add
   a new one.
2. `ddl-auto` stays `validate`. JPA `@Index` annotations create nothing; every index is created in a migration.

### Indexes
3. Every new filter or sort in a query gets a matching index in the same change:
   - put the equality columns first and the sort columns after, e.g. `(status, created_at, id)` for "waiting, oldest first";
   - use a partial index (`WHERE status = 'PENDING'`) when the query reads a small subset of a growing table;
   - drop an index that a new composite index makes redundant (the composite's leading columns cover it).
4. A `LIKE '%term%'` search on a growing table needs a `pg_trgm` GIN index, or must not be offered. Never bind
   `'%'` when no search term was given; leave the condition out instead.

### Queries
5. Count, sum and group in SQL (`COUNT(*) FILTER (...)`, `SUM`, `GROUP BY`). Never load entities and count or add
   them up in Java.
6. Never call the database inside a loop: no repository calls per item, and no lazy collection touched per row.
   Batch instead: `findAllById`, `WHERE id IN (...)`, `saveAll`, `INSERT ... SELECT`, or JDBC batch writes.
   Take row locks for many rows in one statement (`WHERE id IN (...) ORDER BY id FOR UPDATE`).
7. List endpoints are paginated (`Pageable`, with a capped page size), unless the list is small by design; say so in
   a comment. No unfiltered `findAll()` on a table that grows.
8. List endpoints return DTO projections. Leave out large JSON or text columns the list does not show.
9. Read methods are `@Transactional(readOnly = true)`.
10. No HTTP call to another service while a database transaction is open. Call it after commit, or through an
    outbox table.

### Stored functions and views
11. Use a PostgreSQL function or view when a screen needs one read model built from several tables or queues
    (badges, dashboards, summaries, reports). Example: `approval_queue_counts`, Procurement V15 and Ecommerce V30.
12. Business writes stay in Java services: state transitions, maker-checker and permission checks. Each rule lives
    in exactly one place. Triggers are used only as integrity guards, such as the append-only ledger.
13. Every function:
    - is created by a migration, and changed with `CREATE OR REPLACE` in a new migration;
    - is `LANGUAGE sql STABLE` unless it must write;
    - takes only what it needs, such as the queues the caller may see;
    - has a comment above each branch naming the endpoint whose results it mirrors;
    - has an integration test that compares its output with those endpoints (for example
      `ApprovalSummaryIntegrationTest` and `ApprovalSummaryIT`).
14. When a rule mirrored by a function changes in Java (for example which rows a queue lists), update the function
    in the same change. The comparison test fails if you forget.

### Configuration
15. `spring.jpa.open-in-view=false`, `show-sql` off outside dev, and the Hikari pool sized explicitly.
16. Batch writes: `hibernate.jdbc.batch_size`, `order_inserts` and `order_updates`. Use a pooled SEQUENCE id on tables
    that take bulk inserts (IDENTITY ids turn batching off).

## 2. APIs

17. A screen makes as few calls as it can. Per-page budgets are in each front end's `quality-gate.config.json`
    (default 8). Raising a budget needs a dated reason, and only after an aggregate endpoint was considered.
18. Badges and counters call a count endpoint (one SQL statement). Never download a list to show its length.
19. An aggregate endpoint filters by the caller's permissions on the server. Each backend keeps the
    queue-to-permission mapping in one enum (e.g. `ApprovalQueue`), and the UI mirrors it in one file (e.g.
    `approvalSummaryApi.ts`).
20. Every endpoint declares who may call it: `@RequiresPermission`, or `@PreAuthorize` when its rule is broader.
    Endpoint inventory tests enforce this.
    - Ecommerce BE: add the route to `SecurityIT.ROUTES`.
    - Web UI: add every endpoint that needs a permission to `src/config/endpointAccess.ts`.
21. Do not call other services in a loop. Add a batch endpoint, such as one that takes a list of ids.
22. A scheduled sync must cost almost nothing when nothing changed. Check a cursor or change marker before paging
    through data.
23. Public, rarely changing data (company profile, active catalogue) is served with `Cache-Control` and `ETag`, and
    cached on the server with eviction on change.

## 3. Front ends

24. Every request goes through the shared API clients (`createApiClient` in the Web UI, `axiosClient` in the
    storefront). No raw `fetch`.
25. No request the user is not allowed to make. Gate each load by its endpoint's permission (`useAccess().can`).
26. Load lazily. A hidden tab, closed modal or collapsed panel fetches nothing. Data hooks take an `enabled` option,
    and opening the view loads its latest data.
27. After a change, refresh only what it affects: the summary and the list it changed. Never reload every queue.
28. Server-side search uses `useSearchTerm` (2 s debounce, Enter searches at once) together with
    `createSearchResultCache`.
29. Shared lookups (dealers, items, categories, company profile) come from one cached hook or store. Components never
    fetch them on their own.
30. No polling without an agreed reason. When there is one, poll only while the page is visible, at least every 30 s,
    and against a delta or ETag endpoint.
31. Every endpoint a page calls has an e2e fixture (Web UI: `e2e/support/fixtures.ts`). Unmocked calls fail strict
    builds.

## 4. Testing (all four services)

### What runs where

| Service | Unit tests | Integration tests | Command |
|---|---|---|---|
| Procurement BE | `*Test.java` (no Spring context) | `*IntegrationTest.java` (Testcontainers PostgreSQL, `IntegrationTestSupport`) | `mvn test -Dquality.skip=true` |
| Ecommerce BE | `*Test.java` | `it/*IT.java` (Testcontainers PostgreSQL, `IntegrationTest`, response snapshots) | `./mvnw test` |
| Procurement Web UI | `*.test.ts(x)` (Vitest + Testing Library) | `e2e/*.spec.ts` (Playwright on the built bundle) | `npx vitest run`; `vite build`, `npx playwright test`, `node scripts/e2e-gate.mjs` |
| Storefront | `*.test.ts(x)` (Vitest + Testing Library) | `e2e/*.spec.ts` (Playwright on the built bundle) | the same as the Web UI |

`node scripts/test-all.mjs --e2e` in `Tinqa-Procurement-Web-UI` runs every suite of all four services side by side
and prints the status table of rule 39 (leave out `--e2e` to skip the Playwright suites; `--only=web-ui,...` picks
services). Backend integration tests need Docker running.

### Rules
32. Every change ships with tests in the same change: new behaviour gets a test that fails without it, and every bug
    fix gets a regression test. Backend rules (permissions, maker-checker, state transitions, SQL functions) are
    tested through the HTTP API in an integration test, not only in a unit test.
33. Before a change is called done, the full unit and integration suites of every repo it touches pass. A change
    to a contract between services (an endpoint, a DTO, a permission name, an `ApprovalQueue`) runs all four.
34. A red test is never deleted, skipped (`@Disabled`, `it.skip`, `.only`), or weakened to make a build pass. Fix
    the code or the test. When they disagree, the intended rule is in the user guide, the feature doc or the code
    comments; follow that, and say in the change which side was wrong.
35. Tests do not depend on today's date. Freeze the clock (Ecommerce `TestClock.freezeAt`, `Clock.fixed` in unit
    tests, `vi.useFakeTimers({ toFake: ['Date'] })` in Vitest), or compute dates relative to now. A hard-coded date
    that a validation compares with today (such as an expected delivery date with `min = today`) is far in the
    future (`2099-12-31`) or in the past on purpose, never "a few days from now".
36. Response snapshots (`src/test/resources/snapshots`) mask values that change between runs (ids, timestamps,
    dates) and are taken on a frozen clock. A snapshot is updated only when the response was meant to change, and
    the diff is reviewed in the change.
37. Tests are independent: no order between test classes or files, no shared mutable state, and each one creates
    the data it needs. Integration tests use a throwaway Testcontainers database, never a shared or local one.
38. Mock only at the edges: other services, email, payment gateways, the clock. In front ends, mock the API module
    (`vi.mock('./api/...')`), not the hooks or components under test. Query the DOM by role and label, as a user
    would.
39. Test status is reported per service in one table, with unit and integration counts kept apart:
    `service | unit passed/total | integration passed/total | result`. The result is one of:
    - **pass**: every test ran and passed;
    - **fail**: name each failing test, its cause, and whether it was fixed;
    - **not run**: the suite could not start (for example Docker down). It is never reported as passing.
40. A failure caused by the environment (Docker, ports, network) is fixed and the suite re-run before reporting. A
    failure that appears only under load or on one run is flaky; it is fixed, not re-run until green.
41. Keep the suites fast. Unit tests need no Spring context, database or network. An integration test that needs
    its own application context (a new `@MockitoBean` or properties) says why in a comment, because each one starts
    the application again.

## 5. Checklist for every change request

- [ ] Any schema, index or function change is a new migration; no applied migration was edited.
- [ ] New filters and sorts have matching indexes; redundant indexes were dropped.
- [ ] No new database call in a loop, unpaginated list, or counting or adding up in Java.
- [ ] Reads are `readOnly`; no HTTP call inside a transaction.
- [ ] Each new endpoint declares access, and its access tests and `endpointAccess.ts` are updated.
- [ ] Each SQL function has a test comparing it with the endpoints it mirrors, and Java rules it mirrors are in sync.
- [ ] Page call budgets are unchanged or lower, or raised with a dated reason; e2e fixtures were added.
- [ ] Every load is gated by permission and loads lazily.
- [ ] Behaviour users notice is in the user guide and its changelog
      (`src/features/user-guide/content/guideContent.ts`).
- [ ] New behaviour and bug fixes have tests; no test was deleted, skipped or weakened.
- [ ] Unit and integration suites of every touched repo pass (all four for a contract change), and the status table
      (rule 39) is in the change.
- [ ] No test depends on today's date; snapshots changed only on purpose.
- [ ] Performance work shows numbers before and after:
  - Procurement: SQL statements per request (`QueryCountingDataSourcePostProcessor`, `ApiCallRegistry`);
  - front ends: calls per page from `reports/e2e/network`.
