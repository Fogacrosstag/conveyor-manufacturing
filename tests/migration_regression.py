#!/usr/bin/env python3
"""Run after the baseline demo and the migration-profile services have started."""
import sys,json
from pathlib import Path
from urllib.request import urlopen
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from migrate_all import run
from migrate import call

def orders():
    with urlopen('http://127.0.0.1:8080/api/legacy/production-orders',timeout=20) as r:return json.load(r)['data']['items']
if __name__=='__main__':
    before=orders();run(False);after=orders()
    assert before==after,'Forward migration changed the legacy API contract'
    assert call('http://127.0.0.1:8080','production-order','state')['state']=='REMOTE'
    run(True);restored=orders()
    assert before==restored,'Reverse migration changed records'
    assert call('http://127.0.0.1:8080','production-order','state')['state']=='LOCAL'
    print('PASS: forward/reverse content reconciliation, stable identifiers, ownership and legacy contract')
