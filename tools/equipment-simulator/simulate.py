#!/usr/bin/env python3
"""Deterministic synthetic equipment observations; no hardware integration."""
import argparse,sys,uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts'))
from client import api
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--equipment',required=True);p.add_argument('--work-center',required=True);p.add_argument('--order',required=True);p.add_argument('--sequence',required=True,type=int);p.add_argument('--event',choices=['EQUIPMENT_STARTED','EQUIPMENT_STOPPED','EQUIPMENT_FAILURE','EQUIPMENT_RECOVERED','PRODUCTION_CYCLE_COMPLETED','MAINTENANCE_REQUIRED'],required=True)
a=p.parse_args();event_id=str(uuid.uuid5(uuid.NAMESPACE_URL,f'{a.equipment}:{a.sequence}:{a.event}'))
print(api('shop-floor','/shop-floor/events',{'eventId':event_id,'equipmentId':a.equipment,'workCenterId':a.work_center,'productionOrderId':a.order,'sequence':a.sequence,'eventType':a.event,'timestamp':'2026-01-01T10:00:00Z','payload':{'reason':'SIMULATED_EVENT'}},key=event_id))
