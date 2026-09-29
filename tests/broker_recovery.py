#!/usr/bin/env python3
"""Requires the hybrid Compose stack; tests real redelivery and consumer restart."""
import sys,subprocess,json,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from client import api,poll
from seed import seed

def run():
    product,bom=seed()
    order=api('production-order','/production-orders',{'productId':product,'bomRevisionId':bom,'quantity':1,'priority':'NORMAL'})
    oid=order['id'];api('production-order',f'/production-orders/{oid}/submit',{})
    poll(lambda:api('production-order',f'/production-orders/{oid}'),lambda o:o.get('materialsReserved'))
    events=poll(lambda:api('manufacturing-audit',f'/audit/production-orders/{oid}')['items'],lambda events:any(e['eventType']=='ProductionOrderSubmitted' for e in events))
    submitted=next(e for e in events if e['eventType']=='ProductionOrderSubmitted')
    before=api('material','/materials')['items']
    topic='production-order.events'
    command=['docker','compose','exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic',topic,'--property','parse.key=true','--property','key.separator=|']
    line=oid+'|'+json.dumps(submitted)+'\n'
    subprocess.run(command,input=line*2,text=True,check=True)
    subprocess.run(['docker','compose','restart','material-service'],check=True)
    # A fresh event after duplicates proves the restarted consumer advanced.
    api('production-order',f'/production-orders/{oid}/cancel',{})
    poll(lambda:api('material',f'/materials/reservations/{oid}'),lambda r:r['status']=='RELEASED',timeout=120)
    after=api('material','/materials')['items']
    for old,new in zip(before,after):
        assert new['quantityOnHand']==old['quantityOnHand']
        assert new['reservedQuantity']<=old['reservedQuantity']
    history=api('manufacturing-audit',f'/audit/production-orders/{oid}')['items']
    assert sum(e['eventId']==submitted['eventId'] for e in history)==1
    print('PASS: real Kafka duplicate delivery, consumer restart, release and deduplicated audit')
if __name__=='__main__':run()
