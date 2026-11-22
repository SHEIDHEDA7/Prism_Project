#!/usr/bin/env python3
"""Concurrent admission test; creates temporary keys only through demo-key RPM limits."""
import concurrent.futures,json,urllib.request,urllib.error
URL='http://localhost:8080/v1/chat/completions'
def req(i):
 data=json.dumps({'model':'fast','messages':[{'role':'user','content':f'load request unique {i}'}]}).encode()
 q=urllib.request.Request(URL,data=data,headers={'Authorization':'Bearer prism-demo-team','Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(q,timeout=10) as r:return r.status
 except urllib.error.HTTPError as e:return e.code
with concurrent.futures.ThreadPoolExecutor(max_workers=40) as ex: statuses=list(ex.map(req,range(100)))
accepted=sum(x==200 for x in statuses); rejected=sum(x==429 for x in statuses)
print(json.dumps({'requests':len(statuses),'accepted':accepted,'rate_limited':rejected,'unexpected':len(statuses)-accepted-rejected,'over_admission':accepted>60},indent=2))
raise SystemExit(1 if accepted>60 or len(statuses)!=accepted+rejected else 0)
