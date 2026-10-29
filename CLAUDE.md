# TinQa Procurement Backend

Before any change, read `docs/engineering-rules.md` and follow its checklist. These rules are shared by all four
TinQa services; change every copy together. The optimization roadmap is in
`../Tinqa-Procurement-Web-UI/docs/optimization-roadmap.md`.

Specific to this service:
- Migrations: `src/main/resources/db/migration/V{n}__*.sql`. Integration tests use real Postgres and Redis
  (Testcontainers), so Docker must be running. Run them with `mvn test`.
- Every endpoint needs `@RequiresPermission` or `@PreAuthorize`, which `EndpointInventoryIntegrationTest` enforces.
  When an endpoint needs a permission, list it in the Web UI's `src/config/endpointAccess.ts`.
- Check permissions inside a service with `CurrentAccess.has(...)`.
- Approvals Hub counts: `approval/summary` (`ApprovalQueue` enum) and the SQL function `approval_queue_counts` (V15).
  A new waiting state gets a queue in the enum, in the function (in a new migration) and in
  `ApprovalSummaryIntegrationTest`.
- To measure, count SQL statements per request with `common/monitoring` (`QueryCountingDataSourcePostProcessor`).
