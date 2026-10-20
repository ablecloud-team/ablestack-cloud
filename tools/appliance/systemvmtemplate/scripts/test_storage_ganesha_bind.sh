#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# Exercise the real daemon and kernel NFS client in private namespaces.  The
# disabled-IPv6 case deliberately keeps AF_INET6 socket creation available.
set -euo pipefail
if (( $# == 0 )); then set -- --outer; fi
if [[ "$1" != "--namespace" ]]; then
  proof=$(mktemp -d /var/tmp/ablestack-ganesha-bind.XXXXXX)
  chmod 0700 "$proof"
  timeout --kill-after=3 120 unshare --mount --net --pid --fork --kill-child=SIGKILL --mount-proc bash "$0" --namespace "$proof"
  cat "$proof/result-public.json"
  exit 0
fi
export PROOF="$2"
python3 - <<'PYBIND'
#!/usr/bin/env python3
"""Real Ganesha listener checks; run only in an owned isolated net namespace."""
import hashlib,ipaddress,json,os,socket,subprocess,time
from pathlib import Path
root=Path(os.environ["PROOF"])
binary="/usr/local/bin/ganesha.nfsd"
def run(*args,**kw):
    result=subprocess.run(args,text=True,capture_output=True,**kw)
    if result.returncode:raise AssertionError({"command":args,"returncode":result.returncode,"stdout":result.stdout,"stderr":result.stderr})
    return result
run("mount","--make-rprivate","/")
run("ip","link","set","lo","up")
run("ip","link","add","test0","type","dummy")
run("ip","addr","add","192.0.2.1/24","dev","test0")
run("ip","addr","add","192.0.2.2/24","dev","test0")
run("ip","link","set","test0","up")
(root/"data").mkdir(parents=True,exist_ok=True);(root/"mnt").mkdir(exist_ok=True)
(root/"data/before.txt").write_text("bind family real NFS read/write\n")
os.chmod(root/"data",0o777)
for path in ("/run/ganesha","/var/run/ganesha","/var/lib/nfs/ganesha"):Path(path).mkdir(parents=True,exist_ok=True)
def disable_ipv6(value):
    for name in ("all","default","lo","test0"):
        Path("/proc/sys/net/ipv6/conf/"+name+"/disable_ipv6").write_text(str(value))
def listeners(pid,port):
    own={os.readlink(item)[8:-1] for item in Path("/proc/"+str(pid)+"/fd").iterdir() if os.readlink(item).startswith("socket:[")}
    result=[]
    for family,name in ((socket.AF_INET,"tcp"),(socket.AF_INET6,"tcp6")):
        for line in Path("/proc/net/"+name).read_text().splitlines()[1:]:
            columns=line.split();address,rawport=columns[1].split(":")
            if columns[3]!="0A" or int(rawport,16)!=port or columns[9] not in own:continue
            raw=bytes.fromhex(address)
            raw=raw[::-1] if family==socket.AF_INET else b"".join(raw[x:x+4][::-1] for x in range(0,16,4))
            result.append(socket.inet_ntop(family,raw))
    return sorted(result)
def normalize(value):
    addr=ipaddress.ip_address(value)
    return str(addr.ipv4_mapped) if isinstance(addr,ipaddress.IPv6Address) and addr.ipv4_mapped else str(addr)
def case(name,bind,expected,port,nfs=False):
    work=root/name;work.mkdir()
    conf=work/"ganesha.conf"
    conf.write_text("NFS_Core_Param { NFS_Port = "+str(port)+"; Bind_Addr = "+bind+"; Protocols = 4; Enable_NLM = false; Enable_RQUOTA = false; }\nNFSv4 { Only_Numeric_Owners = true; Graceless = true; }\nEXPORT { Export_Id = 898; Path = "+str(root/"data")+"; Pseudo = /bind; Access_Type = RW; Squash = No_Root_Squash; Protocols = 4; Transports = TCP; SecType = sys; FSAL { Name = VFS; } CLIENT { Clients = *; Access_Type = RW; Squash = No_Root_Squash; } }\n")
    process=subprocess.Popen([binary,"-F","-p",str(work/"pid"),"-f",str(conf),"-L",str(work/"ganesha.log")],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    mounted=False
    try:
        found=[]
        for iteration in range(100):
            if process.poll() is not None:raise AssertionError(name+": daemon exited")
            found=listeners(process.pid,port)
            if found:break
            time.sleep(.1)
        record={"case":name,"configured":bind,"pid":process.pid,"listeners":found,"expected":expected}
        (work/"listener-public.json").write_text(json.dumps(record,sort_keys=True,indent=2)+"\n")
        assert len(found)==1 and normalize(found[0])==expected,record
        if bind=="192.0.2.1":
            test=socket.socket();test.settimeout(1)
            try:
                try:test.connect(("192.0.2.2",port))
                except OSError:pass
                else:raise AssertionError("specific IPv4 listener also accepts another interface address")
            finally:test.close()
            record["otherAddressRejected"]=True
        if nfs:
            run("mount","-t","nfs","-o","vers=4.1,port="+str(port)+",proto=tcp,noac",bind+":/bind",str(root/"mnt"),timeout=20);mounted=True
            before=(root/"data/before.txt").read_bytes()
            assert (root/"mnt/before.txt").read_bytes()==before
            (root/"mnt/after.txt").write_text("write through exact IPv4 listener\n")
            assert (root/"data/after.txt").read_text()=="write through exact IPv4 listener\n"
            record["nfsReadWrite"]=True;record["dataSha256"]=hashlib.sha256(before).hexdigest()
        return record
    finally:
        if mounted:run("umount",str(root/"mnt"),timeout=10)
        process.terminate()
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.kill();process.wait()
disabled={}
disable_ipv6(1)
with socket.socket(socket.AF_INET6,socket.SOCK_STREAM) as probe:disabled["ipv6SocketCanBeCreated"]=True
try:socket.getaddrinfo("192.0.2.1",0,socket.AF_INET6,0,0,socket.AI_ADDRCONFIG|socket.AI_V4MAPPED)
except socket.gaierror as error:disabled["ipv6AddrconfigResolutionRejected"]=True;disabled["gaiErrno"]=error.errno
else:raise AssertionError("required IPv6 disabled resolver condition was not established")
results=[case("ipv4-disabled","192.0.2.1","192.0.2.1",20492,True),case("wildcard-disabled","0.0.0.0","0.0.0.0",20493)]
disable_ipv6(0)
run("ip","-6","addr","add","2001:db8::1/64","dev","test0","nodad")
run("ip","-6","addr","add","::1/128","dev","lo") if not "inet6 ::1" in run("ip","-6","addr","show","dev","lo").stdout else None
results += [case("ipv4-enabled","192.0.2.1","192.0.2.1",20494),case("wildcard-enabled","0.0.0.0","0.0.0.0",20495),case("ipv6-enabled","2001:db8::1","2001:db8::1",20496)]
out={"success":True,"disabledIpv6Precondition":disabled,"cases":results}
(root/"result-public.json").write_text(json.dumps(out,sort_keys=True,indent=2)+"\n")
print(json.dumps(out,sort_keys=True))
PYBIND
