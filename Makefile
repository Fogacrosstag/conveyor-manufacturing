.PHONY: up baseline down build test integration-test e2e-test seed simulate logs clean verify format format-check
up:
	docker compose up --build -d --wait
baseline:
	docker compose -f docker-compose.yml -f compose.baseline.yml up --build -d --wait
down:
	docker compose down
build:
	docker compose build
test:
	mvn -B -ntp test
	python3 scripts/go_tasks.py test
integration-test:
	mvn -B -ntp test -Dtest=LegacyWorkflowTest -Dsurefire.failIfNoSpecifiedTests=false
e2e-test:
	python3 scripts/demo.py
	python3 tests/api_regression.py
seed:
	python3 scripts/seed.py
simulate:
	python3 tools/equipment-simulator/simulate.py --help
logs:
	docker compose logs -f --tail=100
verify:
	python3 scripts/verify_repository.py
clean:
	python3 scripts/clean.py

format:
	mvn -B -ntp spotless:apply
	python3 scripts/go_tasks.py format
format-check:
	mvn -B -ntp spotless:check
	python3 scripts/go_tasks.py format-check
