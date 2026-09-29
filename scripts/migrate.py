#!/usr/bin/env python3
"""Quiescent ownership transfer with persistent snapshots and reconciliation.

Stop client traffic and drain broker consumers before using this tool. Both
applications must be reachable. Transfer capabilities in dependency order.
The script intentionally leaves both sides frozen if reconciliation fails.
"""
import argparse,json,hashlib,time,os
from pathlib import Path
from urllib.request import Request,urlopen

def call(base,cap,action,body=None):
    data=None if body is None else json.dumps(body).encode()
    req=Request(base+f'/internal/migration/{cap}/{action}',data=data,headers={'Content-Type':'application/json','X-Admin-Token':os.environ.get('ADMIN_TOKEN','local-migration-token-change-me')},method='GET' if body is None else 'POST')
    with urlopen(req,timeout=30) as r:return json.load(r)
def canonical(value):
    # Preserve decimal text semantics while normalizing cross-language 1 / 1.0.
    from decimal import Decimal
    if isinstance(value,dict):return {k:canonical(v) for k,v in sorted(value.items())}
    if isinstance(value,list):return [canonical(v) for v in value]
    if isinstance(value,(float,int)) and not isinstance(value,bool):return {'$number':str(Decimal(str(value)).normalize())}
    return value
def digest(value):return hashlib.sha256(json.dumps(canonical(value),sort_keys=True,separators=(',',':')).encode()).hexdigest()
def transfer(source,target,cap,directory,reverse=False):
    # This is a coordinated maintenance operation, never a runtime feature flag.
    call(source,cap,'freeze',{});call(target,cap,'freeze',{})
    for _ in range(120):
        if all(call(base,cap,'state')['pendingOutbox']==0 for base in [source,target]):break
        time.sleep(.5)
    else:raise RuntimeError('Outbox did not drain; both sides remain frozen')
    snapshot=call(source,cap,'snapshot')
    if cap=='production-order':
        active=[row['id'] for row in snapshot['order'] if row['body']['status'] not in ['CREATED','ON_HOLD','COMPLETED','CANCELLED']]
        if active:raise RuntimeError(f'Active orders block migration: {active}')
    directory.mkdir(parents=True,exist_ok=True)
    backup=call(target,cap,'snapshot')
    (directory/(cap+'-target-before.json')).write_text(json.dumps(backup,indent=2))
    (directory/(cap+'-source.json')).write_text(json.dumps(snapshot,indent=2))
    call(target,cap,'import',snapshot)
    actual=call(target,cap,'snapshot')
    assert digest(snapshot)==digest(actual),'Snapshot content differs; both owners remain frozen'
    report={'capability':cap,'counts':{k:len(v) for k,v in snapshot.items()},'sha256':digest(snapshot),'source':source,'target':target}
    (directory/(cap+'-reconciliation.json')).write_text(json.dumps(report,indent=2))
    # Reverse transfer restores local legacy ownership; modern source stays frozen.
    call(target,cap,'activate',{})
    if not reverse:call(source,cap,'redirect',{})
    print(json.dumps(report,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('capability',choices=['engineering','manufacturing-audit','shop-floor','quality-control','material','production-planning','production-order']);p.add_argument('--source',required=True);p.add_argument('--target',required=True);p.add_argument('--snapshot-dir',type=Path,default=Path('artifacts/migration'));p.add_argument('--reverse',action='store_true');p.add_argument('--quiescent-confirmed',action='store_true',help='Confirm clients stopped, no active orders and consumer lag is zero')
    a=p.parse_args()
    if not a.quiescent_confirmed:p.error('Maintenance preconditions must be checked before transfer')
    transfer(a.source,a.target,a.capability,a.snapshot_dir,a.reverse)
