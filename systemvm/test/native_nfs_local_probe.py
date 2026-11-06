#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See NOTICE. Apache License, Version 2.0.
# http://www.apache.org/licenses/LICENSE-2.0
"""Private SystemVM regression: real candidate function, Ganesha and mount commands.
Uses a mounted SPARSE/FAT DATA subdirectory. It never grants the server client ACLs.
"""
from pathlib import Path
import argparse
import json
import os
import subprocess
import sys
from types import SimpleNamespace
from native_shared_directory_bind import Probe, command

CALL = r"""
from pathlib import Path
import ast,json,ipaddress,subprocess,tempfile,shutil,sys
source=Path(sys.argv[1]).read_text().splitlines()
a=next(i for i,x in enumerate(source)if x.startswith('def probe_nfs_export_visibility('))
b=next(i for i in range(a+1,len(source))if source[i]and not source[i][0].isspace())
calls=[]
real_run=subprocess.run
def run(args,**kwargs):
 calls.append(args)
 return real_run(args,**kwargs)
request=json.loads(Path(sys.argv[2]).read_text())
scope={'subprocess':subprocess,'ipaddress':ipaddress,'tempfile':tempfile,'shutil':shutil,
       'nfs_probe_root':sys.argv[3],'endpoint_key':lambda ip,port:str(port)}
exec(compile(ast.parse('\n'.join(source[a:b])),'actual candidate NFS probe','exec'),scope)
subprocess.run=run
runtime=request['runtime']
exports={(v['ip'],v['port']):v['exports']for v in request['exports']}
result=scope['probe_nfs_export_visibility'](runtime,exports)
print(json.dumps({'runtime':runtime,'failedPorts':[v['port']for v in result],
                 'mountCalls':sum(1 for c in calls if c[0]=='mount')}))
"""

class Native(Probe):
    def start_nfs(self, port):
        conf=self.configs[port]
        text=conf.read_text().replace("Bind_addr=127.0.0.1","Bind_addr=0.0.0.0")
        if "Clients=127.0.0.1" in text:text=text.replace("Clients=127.0.0.1","Clients=192.0.2.52")
        conf.write_text(text)
        return super().start_nfs(port)
    def call_probe(self, external=False, broken=False):
        inputs={"runtime":[{"listenIp":"0.0.0.0","port":port}for port in (23049,23050)],"exports":[]}
        for port in (23049,23050):
            clients=["192.0.2.98/32","192.0.2.99/32"] if external and not (broken and port==23050) else ["192.0.2.52/32"]
            row={"uuid":str(port),"name":"shared","path":str(self.alias),"pseudo":"/absent" if broken and port==23050 else "/shared",
                 "protocolMode":"V4_ONLY","clients":[{"clients":c}for c in clients]}
            inputs["exports"].append({"ip":"0.0.0.0","port":port,"exports":[row]})
        request=self.control/"probe-request.json";request.write_text(json.dumps(inputs))
        mounts=self.control/"probe-mounts";mounts.mkdir(exist_ok=True)
        output=command(["ip","netns","exec",self.ns,"python3","-c",CALL,self.args.cli,str(request),str(mounts)],timeout=90)
        return json.loads(output)
    def exercise(self, conf, other):
        result=self.call_probe()
        assert result["failedPorts"]==[] and result["mountCalls"]==2,result
        assert all(v["probeComplete"] and v["exportsVisible"] for v in result["runtime"])
        self.record("allowed local source retains actual two-port mount validation",result=result)
        for port in (23049,23050):
            path=self.configs[port];path.write_text(path.read_text().replace("Clients=192.0.2.52;","Clients=192.0.2.98,192.0.2.99;"))
        self.refresh([{"port":p}for p in (23049,23050)])
        before={p:self.configs[p].read_bytes()for p in (23049,23050)}
        result=self.call_probe(external=True)
        assert result["failedPorts"]==[] and result["mountCalls"]==0,result
        assert all(not v["probeComplete"] and v["exportsVisible"] is None for v in result["runtime"])
        assert before=={p:self.configs[p].read_bytes()for p in (23049,23050)}
        self.record("remote-only ACL is not expanded; local probe explicitly not applicable",result=result)
        # The real NFS server still denies the excluded source.
        target=self.control/"denied";target.mkdir()
        check=subprocess.run(["ip","netns","exec",self.ns,"mount","-t","nfs4","-o",
            "vers=4.1,proto=tcp,port=23049,ro,soft,timeo=10,retrans=1","192.0.2.52:/shared",str(target)],
            capture_output=True,text=True,timeout=30)
        assert check.returncode!=0 and any(text in check.stderr.lower() for text in ("access denied", "no such file or directory")),check.stderr
        self.record("actual server denies or hides export from excluded source; no hidden ACL grant",exitcode=check.returncode,serverResult=check.stderr.strip())
        port=23050;path=self.configs[port];path.write_text(path.read_text().replace("Clients=192.0.2.98,192.0.2.99;","Clients=192.0.2.52;"))
        self.refresh([{"port":port}])
        result=self.call_probe(external=True,broken=True)
        assert result["failedPorts"]==[23050] and result["mountCalls"]==1,result
        self.record("external-only first export cannot mask a genuine second-port mount failure",result=result)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--volume",required=True);parser.add_argument("--provisioning",choices=("SPARSE","FAT"),required=True)
    parser.add_argument("--cli",required=True);parser.add_argument("--inner",action="store_true")
    args=parser.parse_args()
    if not args.inner:
        q=subprocess.run(["unshare","--mount","--propagation","private",sys.executable,__file__,*sys.argv[1:],"--inner"],capture_output=True,text=True,timeout=200)
        print(q.stdout);print(q.stderr,file=sys.stderr);return q.returncode
    args.candidate=str(Path(args.cli).parent);args.live_export="/export/common1453r5"
    result=Native(args).run();print(json.dumps(result));return 0 if result["success"] else 1
if __name__=="__main__":sys.exit(main())
