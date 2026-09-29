#!/usr/bin/env python3
"""Transfer a quiescent baseline to extracted owners, or reverse the transfer."""
import argparse,json,time
from pathlib import Path
from urllib.request import urlopen
from migrate import call,transfer
CAPS=[('engineering',8081),('manufacturing-audit',8087),('shop-floor',8085),('quality-control',8086),('material',8083),('production-planning',8084),('production-order',8082)]

def run(reverse=False):
    legacy='http://127.0.0.1:8080'
    # The public compatibility API identifies the actual order owner.
    with urlopen(legacy+'/api/legacy/production-orders',timeout=20) as r:orders=json.load(r)['data']['items']
    active=[o['id'] for o in orders if o['status'] not in ['CREATED','ON_HOLD','COMPLETED','CANCELLED']]
    if active:raise RuntimeError(f'Active orders prevent migration: {active}')
    for cap,port in CAPS:
        call(legacy,cap,'freeze',{});call(f'http://127.0.0.1:{port}',cap,'freeze',{})
    for cap,port in CAPS:
        remote=f'http://127.0.0.1:{port}'
        transfer(remote if reverse else legacy,legacy if reverse else remote,cap,Path('artifacts/migration-reverse' if reverse else 'artifacts/migration'),reverse)
    print('Ownership transfer complete. Review reconciliation reports before resuming traffic.')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--reverse',action='store_true');p.add_argument('--quiescent-confirmed',action='store_true')
    args=p.parse_args()
    if not args.quiescent_confirmed:p.error('Stop clients, verify zero consumer lag and pass --quiescent-confirmed')
    run(args.reverse)
