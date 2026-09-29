"""Standard-library API client for executable demonstration and regression tests."""
import json, os, time, uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

PORTS={'products':8080,'engineering':8081,'production-order':8082,'material':8083,'production-planning':8084,'shop-floor':8085,'quality-control':8086,'manufacturing-audit':8087}
BASELINE=os.environ.get('BASELINE','false').lower()=='true'
HOST=os.environ.get('CONVEYOR_HOST','127.0.0.1')
class APIError(Exception):
    def __init__(self,status,body): self.status,self.body=status,body;super().__init__(f'{status}: {body}')
def api(cap,path,body=None,key=None,raw=False,headers=None):
    port=8080 if BASELINE else PORTS[cap]
    data=None if body is None else json.dumps(body).encode()
    h={'Content-Type':'application/json','X-Correlation-ID':str(uuid.uuid4())}
    if body is not None: h['Idempotency-Key']=key or str(uuid.uuid4())
    h.update(headers or {})
    req=Request(f'http://{HOST}:{port}{path}',data=data,headers=h,method='GET' if body is None else 'POST')
    try:
        with urlopen(req,timeout=20) as response: result=json.load(response)
    except HTTPError as e:
        raise APIError(e.code,e.read().decode()) from e
    return result if raw else result['data']
def poll(fn,predicate=lambda x:bool(x),timeout=90):
    end=time.monotonic()+timeout;last=None
    while time.monotonic()<end:
        try:
            last=fn()
            if predicate(last):return last
        except (APIError,URLError) as e:last=str(e)
        time.sleep(0.3)
    raise AssertionError(f'Timed out waiting for state: {last}')
def expect(status,fn):
    try:fn()
    except APIError as e:
        assert e.status==status, f'Expected {status}, got {e}';return
    raise AssertionError(f'Expected HTTP {status}')
def stable(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'conveyor-manufacturing/'+name))
