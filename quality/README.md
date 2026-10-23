# Quality gate

A free, local replacement for SonarQube. It runs at the end of every `mvn package` (and so in `docker build`).

| Check | Tool | Output |
|---|---|---|
| Bugs and security vulnerabilities | SpotBugs + FindSecBugs | `target/spotbugsXml.xml` |
| Code smells, error-prone code | PMD (quickstart rules) | `target/pmd.xml` |
| Hard-coded passwords, keys, tokens | `QualityGate.java` secret scan | |
| Vulnerable dependencies | OSV.dev (free, no key) | `target/quality/dependencies.txt` |
| Tests and line coverage | Surefire + JaCoCo | `target/site/jacoco/` |

The report is written to `target/quality/index.html`.

**The build fails on:**
- new SpotBugs findings, or new high-priority or security PMD findings, that are not in `baseline.txt`
- high or critical dependency vulnerabilities, unless accepted in `quality-gate.properties` (with a reason and an expiry date)
- failing tests
- line coverage below `coverage.minLinePercent`

**Hard-coded secrets** are always reported and never baselined:
- in local, dev or test files (`application-dev.yml`, `src/test/…`): **warning**
- anywhere else (shared config or main code): **error**

Neither stops a normal build, so local development keeps working. A strict build fails on the errors:
`mvn package -Dquality.strict=true` (use it for release images once those values come from environment variables).
`mvn spring-boot:run` does not run the gate.

**Commands:**
- After fixing old findings: `mvn package -DskipTests -Dquality.baseline=true` (commit `baseline.txt`).
- Skip the gate: `mvn package -Dquality.skip=true`.
- Re-run the gate on existing reports: `java quality/QualityGate.java`.
