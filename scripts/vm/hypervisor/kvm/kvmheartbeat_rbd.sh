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
UUIDList=""
skeyPath="/var/lib/libvirt/images/"

while getopts 'p:n:s:h:i:t:u:r:c' OPTION; do
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

#write the heart beat log
write_hbLog() {
  Timestamp=$(date +%s)
  CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")

  obj=$(rbd -p $PoolName ls --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret | grep MOLD-HB-$HostIP)

  if [ $? -gt 0 ]; then
    rbd -p $PoolName create --size 1 --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret MOLD-HB-$HostIP
  fi

  logger -p user.info -t MOLD-HA-HB "[Writing]  호스트:$HostIP | HB 파일 갱신(RBD) > [현 시간:$CurrentTime]"
  obj=$(rbd -p $PoolName --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret image-meta set MOLD-HB-$HostIP $HostIP $Timestamp)
  if [ $? -gt 0 ]; then
    printf "Failed to create rbd file and set image-meta"
    return 2
  fi
  return 0
}

#check the heart beat log
check_hbLog() {
  Timestamp=$(date +%s)
  CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")

  getHbTime=$(rbd -p $PoolName --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret image-meta get MOLD-HB-$HostIP $HostIP)
  if [ $? -gt 0 ] || [ -z "$getHbTime" ]; then
    return 1
  fi

  diff=$(expr $Timestamp - $getHbTime)
  getHbTimeFmt=$(date -d @${getHbTime} '+%Y-%m-%d %H:%M:%S')
  logger -p user.info -t MOLD-HA-HB "[Checking] 호스트:$HostIP | HB 파일 체크(RBD) > [현 시간:$CurrentTime | HB 파일 시간:$getHbTimeFmt | 시간 차이:$diff초]"
  if [ $diff -gt $interval ]; then
    return $diff
  fi
  return 0
}

if [ "$rflag" == "1" ]; then
  check_hbLog
  diff=$?
  if [ $diff == 0 ]; then
    logger -p user.info -t MOLD-HA-HB "[Result]   호스트:$HostIP | HB 체크 결과(RBD) > [HOST STATE : ALIVE]"
    echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"
  else
    logger -p user.info -t MOLD-HA-HB "[Result]   호스트:$HostIP | HB 체크 결과(RBD) > [HOST STATE : DEAD]"
    echo "### [HOST STATE : DEAD] Set maximum interval: ($interval seconds), Actual difference: ($diff seconds) => Considered host down in [PoolType : RBD] ###"
  fi
  exit 0
elif [ "$cflag" == "1" ]; then
  /usr/bin/logger -t heartbeat "kvmheartbeat_rbd.sh will reboot system because it was unable to write the heartbeat to the storage."
  sync &
  sleep 5
  echo b >/proc/sysrq-trigger
  exit $?
else
  write_hbLog
  exit 0
fi
