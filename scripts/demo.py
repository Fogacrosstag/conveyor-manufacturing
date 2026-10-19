
#!/usr/bin/env python3
"""Exercise actual REST APIs, databases, Kafka and equipment failure/recovery."""

import json
import uuid

from client import api, poll, expect
from seed import seed


def run():
    product, bom = seed()

    order = api(
        'production-order',
        '/production-orders',
        {
            'productId': product,
            'bomRevisionId': bom,
            'quantity': 5,
            'priority': 'HIGH'
        }
    )

    oid = order['id']
    path = f'/production-orders/{oid}'

    expect(
        409,
        lambda: api('production-order', path + '/complete', {})
    )

    api('production-order', path + '/submit', {})

    poll(
        lambda: api('production-order', path),
        lambda o: o['status'] == 'MATERIALS_RESERVED'
    )

    api('production-planning', '/planning/dispatch', {})

    poll(
        lambda: api('production-order', path),
        lambda o: o['status'] == 'SCHEDULED'
    )

    operations = poll(
        lambda: [
            x for x in api(
                'shop-floor',
                '/shop-floor/operations'
            )['items']
            if x['productionOrderId'] == oid
        ],
        lambda x: len(x) == 7
    )

    operations.sort(key=lambda x: x['sequence'])

    first = operations[0]
    first_path = f"/shop-floor/operations/{first['id']}"

    expect(
        409,
        lambda: api('shop-floor', first_path + '/start', {})
    )

    api('production-order', path + '/start', {})

    poll(
        lambda: api('shop-floor', first_path + '/start', {})
    )

    expect(
        409,
        lambda: api(
            'shop-floor',
            f"/shop-floor/operations/{operations[1]['id']}/start",
            {}
        )
    )

    # Equipment event sequence numbers are positive integers.
    # Use consecutive values rather than Unix timestamps.
    sequence = 1

    failure = {
        'eventId': str(uuid.uuid4()),
        'equipmentId': first['equipmentId'],
        'workCenterId': first['workCenterId'],
        'productionOrderId': oid,
        'eventType': 'EQUIPMENT_FAILURE',
        'sequence': sequence,
        'timestamp': '2026-01-01T10:00:00Z',
        'payload': {
            'reason': 'SIMULATED_MOTOR_OVERLOAD'
        }
    }

    api('shop-floor', '/shop-floor/events', failure)

    poll(
        lambda: api('shop-floor', first_path),
        lambda x: x['status'] == 'PAUSED',
        timeout=120
    )

    expect(
        409,
        lambda: api('shop-floor', first_path + '/resume', {})
    )

    poll(
        lambda: api(
            'production-planning',
            f'/planning/schedules/{oid}'
        ),
        lambda x: x.get('delayed', False),
        timeout=120
    )

    recovery = {
        **failure,
        'eventId': str(uuid.uuid4()),
        'eventType': 'EQUIPMENT_RECOVERED',
        'sequence': sequence + 1
    }

    api('shop-floor', '/shop-floor/events', recovery)

    poll(
        lambda: api('shop-floor', first_path + '/resume', {}),
        timeout=120
    )

    for index, op in enumerate(operations):
        op_path = f"/shop-floor/operations/{op['id']}"

        if index:
            poll(
                lambda p=op_path: api(
                    'shop-floor',
                    p + '/start',
                    {}
                ),
                timeout=120
            )

        # Reuse the same idempotency key for repeated requests.
        completion_key = str(uuid.uuid4())

        completion_body = {
            'processedQuantity': 5,
            'rejectedQuantity': 0
        }

        result = api(
            'shop-floor',
            op_path + '/complete',
            completion_body,
            key=completion_key
        )

        assert result['status'] == 'COMPLETED', (
            f'Operation {op["id"]} did not complete: {result!r}'
        )

        again = api(
            'shop-floor',
            op_path + '/complete',
            completion_body,
            key=completion_key
        )

        assert again == result, (
            f'Idempotent completion response differs '
            f'for operation {op["id"]}: '
            f'first={result!r}, repeated={again!r}'
        )

    inspections = poll(
        lambda: api(
            'quality-control',
            f'/quality/production-orders/{oid}'
        )['items'],
        lambda x: len(x) == 5,
        timeout=120
    )

    failed = inspections[0]
    ip = f"/quality/inspections/{failed['id']}"

    api('quality-control', ip + '/start', {})

    api(
        'quality-control',
        ip + '/checks',
        {
            'measuredValue': 99,
            'inspectorId': 'DEMO-QA'
        }
    )

    assert api(
        'quality-control',
        ip + '/complete',
        {}
    )['result'] == 'FAILED'

    expect(
        409,
        lambda: api('production-order', path + '/complete', {})
    )

    api(
        'quality-control',
        ip + '/retry',
        {
            'reworkNote':
                'Adjusted assembly and re-measured after simulated rework'
        }
    )

    for inspection in inspections:
        ip = f"/quality/inspections/{inspection['id']}"

        api('quality-control', ip + '/start', {})

        api(
            'quality-control',
            ip + '/checks',
            {
                'measuredValue': 10,
                'inspectorId': 'DEMO-QA'
            }
        )

        assert api(
            'quality-control',
            ip + '/complete',
            {}
        )['result'] == 'PASSED'

    completed = poll(
        lambda: api('production-order', path),
        lambda x: x['status'] == 'COMPLETED',
        timeout=120
    )

    assert completed['materialsConsumed']
    assert completed['qualityApproved']
    assert completed['operationsComplete']

    reservation = api(
        'material',
        f'/materials/reservations/{oid}'
    )

    assert reservation['status'] == 'CONSUMED'

    legacy = api(
        'products',
        '/api/legacy' + path
    )

    assert legacy == completed, (
        'Legacy compatibility response differs'
    )

    history = poll(
        lambda: api(
            'manufacturing-audit',
            f'/audit/production-orders/{oid}'
        )['items'],
        lambda x: any(
            e['eventType'] == 'ProductionOrderCompleted'
            for e in x
        ),
        timeout=120
    )

    assert len({
        e['eventId'] for e in history
    }) == len(history), 'Duplicate audit events'

    print(
        json.dumps(
            {
                'productionOrderId': oid,
                'status': completed['status'],
                'operations': 7,
                'mandatoryInspections': 5,
                'auditEvents': len(history),
                'equipmentFailureAndRecovery': 'verified',
                'failedInspectionAndRetry': 'verified',
                'legacyCompatibility': 'verified'
            },
            indent=2
        )
    )

    return oid


if __name__ == '__main__':
    run()
