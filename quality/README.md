# Quality gate

A free, local replacement for SonarQube. It runs at the end of every `mvn package` (and so in `docker build`).

| Check | Tool | Output |
|---|---|---|
| Bugs and security vulnerabilities | SpotBugs + FindSecBugs | `target/spotbugsXml.xml` |
| Code smells, error-prone code | PMD (quickstart rules) | `target/pmd.xml` |
| Hard-coded passwords, keys, tokens | `QualityGate.java` secret scan | |
| Vulnerable dependencies | OSV.dev (free, no key) | `target/quality/dependencies.txt` |
| Tests and line coverage | Surefire + JaCoCo | `target/site/jacoco/` |
| API budgets: SQL statements per call, N+1 queries, crashes (500) | API monitor during the tests | `target/quality/api-calls.tsv` |
| New-code coverage: the lines changed since the base branch that the tests run | git diff + JaCoCo | |

The report is written to `target/quality/index.html`.

## Local and strict builds

| | Local `mvn package` | Strict `mvn package -Dquality.strict=true` (UAT, production, docker images) |
|---|---|---|
| Failing tests, new errors and security findings, high/critical vulnerabilities, coverage below `coverage.minLinePercent` | fails | fails |
| New warnings (PMD, API budgets), hard-coded secrets (except test fixtures), moderate vulnerabilities, new-code coverage below `coverage.minNewCodePercent`, a dependency check that could not run, skipped tests | **warning** | fails |

Existing findings live in `baseline.txt`: only new ones count. Accept an advisory in `quality-gate.properties` with a
reason and an expiry date. The docker images build strictly with `-Dquality.tests=external`: Testcontainers cannot
start inside `docker build`, so the tests, API budgets and coverage come from the `mvn package` that must pass first.

## What the tests check, beyond each feature

- **Every endpoint** (`EndpointInventoryIT` / `EndpointInventoryIntegrationTest`), found at runtime, so a new endpoint is
  covered the day it is added: it must declare who may call it, anonymous callers are turned away from everything
  that is not public, and every endpoint is called with empty input; a 500 is reported per endpoint.
- **Concurrency** (`LoadSmokeIT` / `LoadSmokeIntegrationTest`): a burst of parallel calls, more than the connection
  pool holds, answered as a single call is and within the latency budget.
- **API budgets**: the API monitor (`common/monitoring`) counts the SQL statements of every request. The gate reports
  endpoints over `monitoring.api.max-queries-per-request`, with one statement repeated
  `monitoring.api.max-repeats-of-one-statement` times (N+1), or answering 500.

## Monitoring calls at runtime

The same monitor runs in every environment: a request over its SQL budget, with an N+1 pattern or slower than
`monitoring.api.slow-request` is logged as `API budget: …` (once a minute per endpoint), and every
`monitoring.api.summary-interval` (default 15 minutes) the busiest endpoints are logged as
`API calls since the last summary: …`. Load and stress tests against a running environment: `load/run.sh`
(see `load/k6.js`).

## Commands

- Re-baseline after fixing (or deliberately accepting) findings: `mvn package -Dquality.baseline=true`, commit `baseline.txt`.
- Run every push through the build first (opt-in, once per clone): `git config core.hooksPath .githooks`.
- Skip the gate: `mvn package -Dquality.skip=true`.
- Re-run the gate on existing reports: `java quality/QualityGate.java`.
