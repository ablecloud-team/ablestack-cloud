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
                    -p rbd pool name
                    -n pool auth username
                    -s pool auth secret
                    -h host
                    -i source host ip
                    -u volume uuid list
                    -r write/read hb log
                    -c cleanup
                    -t interval between read hb log\n"
  exit 1
}
#set -x
PoolName=
PoolAuthUserName=
PoolAuthSecret=
HostIP=
SourceHostIP=
interval=0
rflag=0
cflag=0
UUIDList=

while getopts 'p:n:s:h:i:t:u:r:c' OPTION
do
  case $OPTION in
  p)
     PoolName="$OPTARG"
     ;;
  n)
     PoolAuthUserName="$OPTARG"
     ;;
  s)
     PoolAuthSecret="$OPTARG"
     ;;
  h)
     HostIP="$OPTARG"
     ;;
  i)
     SourceHostIP="$OPTARG"
     ;;
  t)
     interval="$OPTARG"
     ;;
  u)
     UUIDList="$OPTARG"
     ;;
  r)
     rflag=1
     ;;
  c)
     cflag=1
     ;;
  *)
     help
     ;;
  esac
done

if [ -z "$PoolName" ]; then
  exit 2
fi

# rados object touch action for vol list
res=$(rbd -p $PoolName ls --id $PoolAuthUserName | grep MOLD-AC)
if [ $? -gt 0 ]; then
  rbd -p $PoolName create --size 1 --id $PoolAuthUserName MOLD-AC
fi

timestamp=$(date +%s)

if [ -n "$UUIDList" ]; then
    for uuid in $(echo $UUIDList | sed 's/,/ /g'); do
      objId=$(rbd -p $PoolName info $uuid --id $PoolAuthUserName | grep 'id:')
      objId=${objId#*id: }
      res=$(timeout 3s bash -c "rados -p $PoolName touch rbd_object_map.$objId")
    if [ $? -eq 0 ]; then
      # 정상적인 touch 상태면 image meta에 key: uuid / value : timestamp 입력
      rbd -p $PoolName --id $PoolAuthUserName image-meta set MOLD-AC $uuid $HostIP:$timestamp
    else
      # 정상적으로 touch 상태가 아니면 image meta에 key : uuid 삭제
      rbd -p $PoolName --id $PoolAuthUserName image-meta rm MOLD-AC $uuid
    fi
  done
fi

#write the heart beat log
write_hbLog() {
  Timestamp=$(date +%s)
  obj=$(rbd -p $PoolName ls --id $PoolAuthUserName | grep MOLD-HB)

  if [ $? -gt 0 ]; then
     rbd -p $PoolName create --size 1 --id $PoolAuthUserName MOLD-HB
  fi

  obj=$(rbd -p $PoolName --id $PoolAuthUserName image-meta set MOLD-HB $HostIP $Timestamp)
  if [ $? -gt 0 ]; then
   	printf "Failed to create rbd file and set image-meta"
    return 2
  fi
  return 0
}

#check the heart beat log
check_hbLog() {
  now=$(date +%s)
  getHbTime=$(rbd -p $PoolName --id $PoolAuthUserName image-meta get MOLD-HB $HostIP)
  if [ $? -gt 0 ] || [ -z "$getHbTime" ]; then
    return 1
  fi

  diff=$(expr $now - $getHbTime)

  if [ $diff -gt $interval ]; then
    return $diff
  fi
  return 0
}

if [ "$rflag" == "1" ]; then
  check_hbLog
  diff=$?
  if [ $diff == 0 ]; then
    echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"
  else
    echo "### [HOST STATE : DEAD] Set maximum interval: ($interval seconds), Actual difference: ($diff seconds) => Considered host down in [PoolType : RBD] ###"
  fi
    exit 0
elif [ "$cflag" == "1" ]; then
  /usr/bin/logger -t heartbeat "kvmheartbeat_rbd.sh will reboot system because it was unable to write the heartbeat to the storage."
  sync &
  sleep 5
  echo b > /proc/sysrq-trigger
  exit $?
else
  write_hbLog
  exit 0
fi
