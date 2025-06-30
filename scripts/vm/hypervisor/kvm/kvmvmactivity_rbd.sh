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
                    -t time on ms\n"
   exit 1
}
#set -x
PoolName=
PoolAuthUserName=
PoolAuthSecret=
HostIP=
SourceHostIP=
UUIDList=
interval=0
skeyPath="/var/lib/libvirt/images/"

while getopts 'p:n:s:h:i:u:t:' OPTION; do
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
   u)
      UUIDList="$OPTARG"
      ;;
   t)
      interval="$OPTARG"
      ;;
   *)
      help
      ;;
   esac
done

if [ -z "$PoolName" ]; then
   exit 2
fi

# 1차 확인 : heartbeat 파일 체크
Timestamp=$(date +%s)
CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")
getHbTime=$(
  rbd -p "$PoolName" \
      --id "${PoolAuthUserName}" \
      -m "${SourceHostIP}" \
      -K "${skeyPath}${PoolAuthSecret}" \
      image-meta get "MOLD-HB-${HostIP}" "${HostIP}"
)
if [ $? -eq 0 ]; then
   diff=$(expr $Timestamp - $getHbTime)
   getHbTimeFmt=$(date -d @${getHbTime} '+%Y-%m-%d %H:%M:%S')
   logger -p user.info -t MOLD-HA-AC "[Checking] 호스트:$HostIP | HB 파일 체크(RBD, 스토리지:$PoolName) > [현 시간:$CurrentTime | HB 파일 시간:$getHbTimeFmt | 시간 차이:$diff초]"
   if [ $diff -le $interval ]; then
      logger -p user.info -t MOLD-HA-AC "[Result]   호스트:$HostIP | HB 체크 결과(RBD, 스토리지:$PoolName) > [HOST STATE : ALIVE]"
      echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"
      exit 0
   fi
fi

if [ -z "$UUIDList" ]; then
   logger -p user.info -t MOLD-HA-AC "[Result]   호스트:$HostIP | HB 체크 결과(RBD, 스토리지:$PoolName) > [HOST HOST STATE : DEAD] 볼륨 UUID 목록이 비어 있음 => 호스트가 다운된 것으로 간주됨"
   echo " ### [HOST STATE : DEAD] Volume UUID list is empty => Considered host down in [PoolType : RBD] ###"
   exit 0
fi

# 2차 확인 : RBD 이미지 사용 여부 체크
for img in $(echo "$UUIDList" | tr ',' ' '); do
   # rbd status 실패 시 다음으로
   output=$(rbd status "${PoolName}/${img}" \
      --id "${PoolAuthUserName}" \
      -m "${SourceHostIP}" \
      -K "${skeyPath}${PoolAuthSecret}")

   # Watchers: none 이 아니면 ALIVE
   if ! echo "$output" | grep -q '^ *Watchers: none'; then
      logger -p user.info -t MOLD-HA-AC "[Result]   호스트:${HostIP} | AC 체크 결과(RBD, 스토리지:$PoolName) > [HOST STATE : ALIVE] ${img} 볼륨에 Watcher 모니터 존재"
      echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"
      exit 0
   fi
done
# 끝까지 빠져나왔으면 DEAD
logger -p user.info -t MOLD-HA-AC "[Result]   호스트:${HostIP} | HB 체크 결과(RBD, 스토리지:$PoolName) > [HOST STATE : DEAD] 볼륨 이미지 목록의 정상 동작을 확인할 수 없음 => 호스트가 다운된 것으로 간주됨"
echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : RBD] ###"
exit 0
