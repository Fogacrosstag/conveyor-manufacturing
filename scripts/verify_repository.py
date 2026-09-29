
#!/usr/bin/env python3
"""Offline structural checks. This is not a substitute for compilation or E2E tests."""

from pathlib import Path
import ast
import json
import re
import xml.etree.ElementTree as ET
import sys

import yaml

ROOT = Path(__file__).resolve().parents[1]

errors = []
counts = {}

EXCLUDED_DIRECTORIES = {
    '.git',
    'target',
    '__pycache__',
    'artifacts',
}


def check(condition, message):
    if not condition:
        errors.append(message)


def balanced(source, path):
    # Strip comments and literals before checking block delimiters.
    token = re.compile(
        r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`[^`]*`',
        re.S
    )

    stripped = token.sub('', source)
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}

    for ch in stripped:
        if ch in '([{':
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack.pop() != pairs[ch]:
                errors.append(f'Unbalanced delimiters: {path}')
                return

    check(not stack, f'Unclosed delimiters: {path}')


for path in ROOT.rglob('*'):
    if not path.is_file():
        continue

    relative_path = path.relative_to(ROOT)

    # Exclude Git internals and generated build directories.
    if any(
        part in EXCLUDED_DIRECTORIES
        for part in relative_path.parts
    ):
        continue

    relative = str(relative_path)
    suffix = path.suffix

    counts[suffix] = counts.get(suffix, 0) + 1

    try:
        text = path.read_text(encoding='utf-8')

        check(
            not re.search('[\u0400-\u04ff]', text),
            f'Unexpected alphabet in {relative}'
        )

        if suffix == '.py':
            ast.parse(text, filename=relative)

        if suffix == '.json':
            json.loads(text)

        if suffix == '.xml':
            ET.fromstring(text)

        if suffix in ['.yml', '.yaml']:
            yaml.safe_load(text)

        if suffix in ['.java', '.go']:
            balanced(text, relative)

    except Exception as exc:
        errors.append(f'{relative}: {exc}')


compose = yaml.safe_load(
    (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
)

apps = [
    'legacy-monolith',
    'engineering-service',
    'production-order-service',
    'material-service',
    'production-planning-service',
    'shop-floor-service',
    'quality-control-service',
    'manufacturing-audit-service'
]

for name in apps:
    check(name in compose['services'], f'Missing service: {name}')

    cfg = compose['services'][name]

    check(
        (ROOT / cfg['build']['dockerfile']).exists(),
        f'Missing Dockerfile: {name}'
    )

    check(
        all(
            port.startswith('127.0.0.1:')
            for port in cfg.get('ports', [])
        ),
        f'Non-local binding: {name}'
    )

    check(
        'DB_URL' in cfg['environment'],
        f'Missing independent DB: {name}'
    )

check(
    len({
        compose['services'][n]['environment']['DB_URL']
        for n in apps
    }) == 8,
    'Database ownership is not isolated'
)

for script in [
    'demo.py',
    'seed.py',
    'migrate.py',
    'migrate_all.py',
    'go_tasks.py'
]:
    check(
        (ROOT / 'scripts' / script).exists(),
        f'Missing script: {script}'
    )


# Verify Maven reactor paths and internal module dependencies.

namespace = {
    'm': 'http://maven.apache.org/POM/4.0.0'
}

root = ET.parse(ROOT / 'pom.xml').getroot()

modules = [
    m.text
    for m in root.findall('m:modules/m:module', namespace)
]

artifacts = set()

for module in modules:
    path = ROOT / module / 'pom.xml'

    check(
        path.exists(),
        f'Missing module: {module}'
    )

    if path.exists():
        artifacts.add(
            ET.parse(path)
            .getroot()
            .find('m:artifactId', namespace)
            .text
        )

for module in modules:
    tree = ET.parse(ROOT / module / 'pom.xml').getroot()

    for dep in tree.findall(
        'm:dependencies/m:dependency',
        namespace
    ):
        if dep.find('m:groupId', namespace).text == 'com.conveyor':
            check(
                dep.find('m:artifactId', namespace).text in artifacts,
                f'Unknown internal dependency in {module}'
            )


print(
    json.dumps(
        {
            'files': sum(counts.values()),
            'extensions': counts,
            'errors': errors
        },
        indent=2
    )
)

if errors:
    sys.exit(1)

print(
    'PASS: offline structure and syntax-adjacent checks. '
    'Java/Go compilation and runtime tests were not performed by this script.'
)
