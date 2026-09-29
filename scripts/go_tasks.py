#!/usr/bin/env python3
"""Run consistent Go checks across the three independent modules."""
import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = (
    "go-platform",
    "services/shop-floor-service",
    "services/manufacturing-audit-service",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=("test", "vet", "build", "tidy", "format", "format-check"))
    task = parser.parse_args().task
    if task in ("format", "format-check"):
        files = sorted(str(path) for module in MODULES for path in (ROOT / module).rglob("*.go"))
        if task == "format":
            subprocess.run(["gofmt", "-w", *files], check=True)
        else:
            result = subprocess.run(["gofmt", "-l", *files], check=True, text=True, capture_output=True)
            if result.stdout:
                print(result.stdout, end="")
                raise SystemExit("Run python3 scripts/go_tasks.py format")
        return
    for module in MODULES:
        print(f"{module}: {task}", flush=True)
        command = ["go", "mod", "tidy"] if task == "tidy" else ["go", task, "-mod=readonly", "./..."]
        if task == "build":
            command[2:2] = ["-trimpath", "-buildvcs=false"]
        subprocess.run(command, cwd=ROOT / module, check=True)


if __name__ == "__main__":
    main()
