#!/usr/bin/env python3
"""Run against a seeded stack. Assertions check durable business outcomes."""
import sys,uuid
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from client import api,poll,expect,stable
from seed import seed

def run():
    product,bom=seed()
    body={'productId':product,'bomRevisionId':bom,'quantity':1,'priority':'NORMAL'}
    key=str(uuid.uuid4());first=api('production-order','/production-orders',body,key=key)
    assert api('production-order','/production-orders',body,key=key)==first
    expect(409,lambda:api('production-order','/production-orders',dict(body,quantity=2),key=key))
    expect(422,lambda:api('production-order','/production-orders',dict(body,quantity=-1)))
    expect(404,lambda:api('production-order','/production-orders',dict(body,bomRevisionId=str(uuid.uuid4()))))
    oid=first['id'];api('production-order',f'/production-orders/{oid}/cancel',{})
    expect(409,lambda:api('production-order',f'/production-orders/{oid}/submit',{}))
    # Stock shortage is a business outcome, not a retryable consumer exception.
    huge=api('production-order','/production-orders',dict(body,quantity=1000000))
    api('production-order',f"/production-orders/{huge['id']}/submit",{})
    failed=poll(lambda:api('production-order',f"/production-orders/{huge['id']}"),lambda x:bool(x.get('materialFailure')))
    assert failed['status']=='MATERIALS_PENDING'
    api('production-order',f"/production-orders/{huge['id']}/cancel",{})
    # Competing reservations may not make available inventory negative.
    roller=api('material','/materials/'+stable('ROLLER'))
    quantity=int(roller['availableQuantity']//12)
    assert quantity>0,'Reset the local stack before running stock contention checks'
    orders=[api('production-order','/production-orders',dict(body,quantity=quantity)) for _ in range(2)]
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda o:api('production-order',f"/production-orders/{o['id']}/submit",{}),orders))
    results=[poll(lambda oid=o['id']:api('production-order','/production-orders/'+oid),lambda x:x.get('materialsReserved') or x.get('materialFailure')) for o in orders]
    assert sum(bool(x.get('materialsReserved')) for x in results)==1,results
    for material in api('material','/materials')['items']:
        assert material['availableQuantity']>=0 and material['quantityOnHand']>=material['reservedQuantity']>=0
    for order in orders:api('production-order',f"/production-orders/{order['id']}/cancel",{})
    winner=next(x for x in results if x.get('materialsReserved'))
    poll(lambda:api('material',f"/materials/reservations/{winner['id']}"),lambda x:x['status']=='RELEASED')
    print('PASS: request idempotency, invalid input, cancellation, stock shortage, concurrent reservations and release')
if __name__=='__main__':run()
