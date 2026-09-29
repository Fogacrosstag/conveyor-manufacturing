#!/usr/bin/env python3
"""Wait for empty outboxes and zero lag in existing Kafka consumer groups."""
import subprocess,time,json,sys
from urllib.request import Request,urlopen
from client import PORTS
from migrate import call
end=time.monotonic()+120
while time.monotonic()<end:
    pending=call('http://127.0.0.1:8080','production-order','state')['pendingOutbox']
    result=subprocess.run(['docker','compose','exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:9092','--all-groups','--describe'],text=True,capture_output=True,check=True)
    lag=[];groups=set()
    for line in result.stdout.splitlines():
        parts=line.split()
        if len(parts)>=6 and parts[2].isdigit():
            groups.add(parts[0])
            try:lag.append(int(parts[5]))
            except ValueError:pass
    if pending==0 and len(groups)>=8 and all(value==0 for value in lag):
        print('No pending legacy outbox events or observed consumer lag. Clients must remain stopped.');sys.exit(0)
    time.sleep(1)
raise SystemExit('The system did not become quiescent; migration is blocked')
