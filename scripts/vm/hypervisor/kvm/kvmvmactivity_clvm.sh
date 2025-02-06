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

help() {
  printf "Usage: $0 
                    -h host
                    -u volume uuid list
                    -t time on ms
                    -d suspect time\n"
  exit 1
}
#set -x
PoolName=rbd
PoolAuthUserName=admin
HostIP=
UUIDList=
MSTime=
SuspectTime=

while getopts 'h:u:t:d:' OPTION
do
  case $OPTION in
  h)
     HostIP="$OPTARG"
     ;;
  u)
     UUIDList="$OPTARG"
     ;;
  t)
     MSTime="$OPTARG"
     ;;
  d)
     SuspectTime="$OPTARG"
     ;;
  *)
     help
     ;;
  esac
done

if [ -z "$PoolName" ]; then
  exit 2
fi

if [ -z "$SuspectTime" ]; then
  exit 2
fi


# First check: heartbeat file
now=$(date +%s)
hb=$(rados -p $PoolName get hb-$HostIP - --id $PoolAuthUserName)
diff=$(expr $now - $hb)
if [ $diff -lt 61 ]; then
    echo "=====> ALIVE <====="
    exit 0
fi

if [ -z "$UUIDList" ]; then
    echo "=====> Considering host as DEAD due to empty UUIDList <======"
    exit 0
fi

# Second check: disk activity check
statusFlag=true
for UUID in $(echo $UUIDList | sed 's/,/ /g'); do
    # vol_persist=$(sg_persist -ik /dev/vg_iscsi/$UUID)
    vol_lvs=$(lvs 2>/dev/null|grep $UUID) 
    if [[ $vol_lvs =~ "-wi-ao----" ]]; then
        continue
    else
        statusFlag=false
        break
    fi
done

if [ statusFlag == "true" ]; then
    echo "=====> ALIVE <====="
else
    echo "=====> Considering host as DEAD due to [CLVM] sg_persist does not exists <======"
fi

exit 0