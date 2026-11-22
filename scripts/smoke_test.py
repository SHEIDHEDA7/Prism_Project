#!/usr/bin/env python3
import json, urllib.request, urllib.error, uuid
BASE='http://localhost:8080'
def call(path, body=None, key='prism-demo-team'):
    data=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request(BASE+path,data=data,headers={'Authorization':'Bearer '+key,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=10) as r:return r.status,dict(r.headers),r.read().decode()
    except urllib.error.HTTPError as e:return e.code,dict(e.headers),e.read().decode()
body={'model':'fast','messages':[{'role':'user','content':'Explain semantic caching simply'}]}
s,h,b=call('/v1/chat/completions',body); assert s==200 and 'x-prism-provider' in h and json.loads(b)['usage']['total_tokens']>0
s,h,b=call('/v1/chat/completions',{'model':'fast','messages':[{'role':'user','content':'Describe semantic cache in simple terms'}]}); assert s==200 and h['x-prism-cache']=='HIT'
s,_,_=call('/v1/chat/completions',body,'prism-not-a-key'); assert s==401
s,_,_=call('/v1/chat/completions',{'model':'smart','messages':[{'role':'user','content':'hello'}]},'prism-demo-student'); assert s==403
s,h,b=call('/v1/chat/completions',{'model':'auto','stream':True,'messages':[{'role':'user','content':'What is 2+2?'}]}); assert s==200 and h['Content-Type'].startswith('text/event-stream') and '[DONE]' in b
urllib.request.urlopen(urllib.request.Request(BASE+'/ops/provider/mock-fast/down?value=true',method='POST')).read()
s,h,_=call('/v1/chat/completions',{'model':'fast','messages':[{'role':'user','content':'fallback validation '+str(uuid.uuid4())}]}); assert s==200 and h['x-prism-fallback']=='true' and h['x-prism-provider']=='mock'
urllib.request.urlopen(urllib.request.Request(BASE+'/ops/provider/mock-fast/down?value=false',method='POST')).read()
with urllib.request.urlopen(BASE+'/ops/usage') as r: assert r.status==200
print('SMOKE PASS: completions, metering, paraphrase cache, auth, allowlist, SSE [DONE], provider fallback, usage API')
