# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file distributed
# with this work for additional information regarding copyright ownership.
# The ASF licenses this file under the Apache License, Version 2.0
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS.
import os,ctypes as C,json
l=C.CDLL("libsmbclient.so.0",use_errno=True);V=C.c_void_p
AUTH=C.CFUNCTYPE(None,V,C.c_char_p,C.c_char_p,V,C.c_int,V,C.c_int,V,C.c_int)
@AUTH
def auth(ctx,server,share,work,wl,user,ul,password,pl):
 for dst,limit,value in [(work,wl,b"WORKGROUP"),(user,ul,b"nobody"),(password,pl,os.environ["PASSWD"].encode())]:
  C.memmove(dst,value,min(len(value),limit-1));C.memset(dst+min(len(value),limit-1),0,1)
def signature(name,args,ret):f=getattr(l,name);f.argtypes=args;f.restype=ret;return f
new=signature("smbc_new_context",[],V);init=signature("smbc_init_context",[V],V);free=signature("smbc_free_context",[V,C.c_int],C.c_int)
setauth=signature("smbc_setFunctionAuthDataWithContext",[V,AUTH],None)
proto=signature("smbc_setOptionProtocols",[V,C.c_char_p,C.c_char_p],C.c_int)
mode=signature("smbc_setOptionOpenShareMode",[V,C.c_int],None);port=signature("smbc_setPort",[V,C.c_uint16],None)
opener=signature("smbc_getFunctionOpen",[V],V);closer=signature("smbc_getFunctionClose",[V],V)
OPEN=C.CFUNCTYPE(V,V,C.c_char_p,C.c_int,C.c_uint,use_errno=True);CLOSE=C.CFUNCTYPE(C.c_int,V,V,use_errno=True)
contexts=[];files=[]
def context(p,deny):
 c=new();assert c;contexts.append(c);setauth(c,auth);assert proto(c,b"SMB2",b"SMB3");port(c,p);mode(c,deny);assert init(c);return c,OPEN(opener(c)),CLOSE(closer(c))
getmode=signature("smbc_getOptionOpenShareMode",[V],C.c_int)
if os.environ.get("LOCK_ROLE"):
 try:
  hold=os.environ["LOCK_ROLE"]=="hold"
  ip=os.environ.get("LOCK_IP",os.environ["SMB_A"]);pt=int(os.environ.get("LOCK_PORT",os.environ["SMB_PORT_A"]))
  c,op,close=context(pt,1 if hold else 4)
  C.set_errno(0);f=op(c,("smb://"+ip+"/probe/marker.txt").encode(),os.O_RDWR,0);e=C.get_errno()
  if f:files.append((c,f,close))
  print(json.dumps({"opened":bool(f),"errno":e,"mode":getmode(c)}),flush=True)
  if hold:assert f;input()
 finally:
  for c,f,close in files:close(c,f)
  for c in contexts:free(c,1)
else:
 import subprocess,sys,select
 h=subprocess.Popen([sys.executable,__file__],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env={**os.environ,"LOCK_ROLE":"hold"})
 try:
  assert select.select([h.stdout],[],[],8)[0],"hold startup"
  ready=json.loads(h.stdout.readline());assert ready["opened"],ready
  results={"held":ready}
  for name,ip,port in [("sameEndpoint",os.environ["SMB_A"],os.environ["SMB_PORT_A"]),("otherEndpoint",os.environ["SMB_B"],os.environ["SMB_PORT_B"])]:
   p=subprocess.run([sys.executable,__file__],capture_output=True,text=True,timeout=8,env={**os.environ,"LOCK_ROLE":"probe","LOCK_IP":ip,"LOCK_PORT":port})
   assert p.returncode==0,p.stderr;results[name]=json.loads(p.stdout)
  assert not results["sameEndpoint"]["opened"] and not results["otherEndpoint"]["opened"],results
  h.stdin.write("release\n");h.stdin.flush();h.wait(timeout=5)
  p=subprocess.run([sys.executable,__file__],capture_output=True,text=True,timeout=8,env={**os.environ,"LOCK_ROLE":"probe","LOCK_IP":os.environ["SMB_B"],"LOCK_PORT":os.environ["SMB_PORT_B"]})
  assert p.returncode==0,p.stderr;results["afterRelease"]=json.loads(p.stdout)
  assert results["afterRelease"]["opened"],results
  results["ok"]=True;print(json.dumps(results),flush=True)
 finally:
  if h.poll() is None:h.stdin.write("release\n");h.stdin.flush()
  try:h.wait(timeout=5)
  except subprocess.TimeoutExpired:h.kill();h.wait()
