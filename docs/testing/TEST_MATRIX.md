# Build and test results

Checked on 2026-09-28 with OpenJDK 21.0.12, Maven 3.9.9 and Go 1.26.8.

| Check | Result | Command |
|---|---|---|
| Java formatting | Passed, 29 source files | `mvn -B -ntp spotless:check` |
| Go formatting | Passed, 6 source files | `python3 scripts/go_tasks.py format-check` |
| Java compilation and packaging | Passed, all 13 reactor modules; six executable Spring Boot JARs | `mvn -B -ntp -DskipTests package` |
| Java material rules | Passed, 2 tests; no failures or skipped tests | `mvn -B -ntp -Dtest=MaterialRulesTest -Dsurefire.failIfNoSpecifiedTests=false test` |
| Go compilation | Passed, all three modules; both service executables also built with `CGO_ENABLED=0` | `python3 scripts/go_tasks.py build` |
| Go unit tests | Passed, 3 platform tests; service packages compile but contain no unit tests | `python3 scripts/go_tasks.py test` |
| Go static analysis | Passed, all three modules | `python3 scripts/go_tasks.py vet` |
| Go dependency integrity | Passed, all three modules | `go mod verify` in each module |
| Reconciliation rules | Passed, 4 tests | `python3 -m unittest discover -s tests -p 'test_*.py'` |
| Repository structure | Passed | `python3 scripts/verify_repository.py` |
| Full Java integration suite | Blocked at Testcontainers startup: no Docker environment; one suite initialization error | `mvn -B -ntp verify` |
| Docker images, Compose and API scenarios | Not run; require a Docker daemon and running stack | Commands below |

The successful packaging command skips test execution but compiles test sources. The separate Java unit-test command runs only `MaterialRulesTest`. Neither command establishes PostgreSQL integration behavior.

Go tests cover event-envelope validation, detached nested copies and rejection of combined event names. The two Go applications' business workflows are exercised by the API scenarios below, which were not executed in this check.

## Full-stack checks

Run these with Docker Compose v2 available:

```bash
mvn -B -ntp verify
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 300
python3 scripts/demo.py
python3 tests/api_regression.py
python3 tests/broker_recovery.py
```

`LegacyWorkflowTest` uses PostgreSQL Testcontainers for manufacturing, concurrent reservations, inbox rollback and failed outbox publication. API scenarios cover equipment failure, quality rework, shortages, duplicate deliveries and consumer restart.

For forward and reverse ownership transfer, follow the [cutover runbook](../modernization/data-migration.md), then run `python3 tests/migration_regression.py` on its quiescent baseline/target stack.

GitHub Actions runs these gates and uploads Maven reports, build logs, migration artifacts and Compose logs. A workflow run is still needed to establish the full-stack results for this revision.
