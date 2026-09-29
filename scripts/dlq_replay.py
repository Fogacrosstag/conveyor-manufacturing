#!/usr/bin/env python3
"""Replay an explicitly selected DLQ record after its cause has been repaired."""
import argparse,json,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('record',type=Path,help='One JSON DLQ record exported with kafka-console-consumer');p.add_argument('--confirm-replay',action='store_true');a=p.parse_args()
if not a.confirm_replay:p.error('Review and repair the failure, then pass --confirm-replay')
dead=json.loads(a.record.read_text());event=json.loads(dead['record']);topic=dead['originalTopic']
allowed={x+'.events' for x in ['engineering','production-order','material','production-planning','shop-floor','quality-control','equipment','legacy']}
if topic not in allowed:raise ValueError('Unexpected source topic')
line=event['aggregateId']+'|'+json.dumps(event,separators=(',',':'))+'\n'
subprocess.run(['docker','compose','exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic',topic,'--property','parse.key=true','--property','key.separator=|'],input=line,text=True,check=True)
print('Replayed existing event ID',event['eventId'],'to',topic,'; inbox guards prevent duplicate effects.')
