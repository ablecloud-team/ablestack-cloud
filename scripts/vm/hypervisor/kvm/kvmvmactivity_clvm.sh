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
                    -n rbd pool auth username
                    -g sharedmountpoint type GFS2 path
                    -h host
                    -u volume uuid list
                    -t time on ms
                    -d suspect time\n"
   exit 1
}
#set -x
RbdPoolName=
RbdPoolAuthUserName=
GfsPoolPath=
HostIP=
UUIDList=
MSTime=
SuspectTime=

while getopts 'p:n:g:h:q:u:t:d:' OPTION; do
   case $OPTION in
   p)
      RbdPoolName="$OPTARG"
      ;;
   n)
      RbdPoolAuthUserName="$OPTARG"
      ;;
   g)
      GfsPoolPath="$OPTARG"
      ;;
   h)
      HostIP="$OPTARG"
      ;;
   q)
      poolPath="$OPTARG"
      ;;
   u)
      UUIDList="$OPTARG"
      ;;
   t)
      interval="$OPTARG"
      ;;
   d)
      SuspectTime="$OPTARG"
      ;;
   *)
      help
      ;;
   esac
done

poolPath=$(echo $poolPath | cut -d '/' -f2-)
hbFolder=$GfsPoolPath/MOLD-HB
hbFile=$hbFolder/$HostIP-$poolPath

# First check: heartbeat file
Timestamp=$(date +%s)
CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")
if [ -n "$RbdPoolName" ]; then
   getHbTime=$(rbd -p $RbdPoolName --id $RbdPoolAuthUserName image-meta get MOLD-HB-$HostIP-$poolPath $HostIP-$poolPath)
   if [ $? -eq 0 ]; then
      diff=$(expr $Timestamp - $getHbTime)
      getHbTimeFmt=$(date -d @${getHbTime} '+%Y-%m-%d %H:%M:%S')
      logger -p user.info -t MOLD-HA-AC "[Checking] 호스트:$HostIP | HB 파일 체크(CLVM with RBD) > [현 시간:$CurrentTime | HB 파일 시간:$getHbTimeFmt | 시간 차이:$diff초]"
      if [ $diff -le $interval ]; then
         logger -p user.info -t MOLD-HA-AC "[Result]   호스트:$HostIP | HB 체크 결과(CLVM with RBD) > [HOST STATE : ALIVE]"
         echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
         exit 0
      fi
   fi
elif [ -n "$GfsPoolPath" ]; then
   getHbTime=$(cat $hbFile)
   diff=$(expr $Timestamp - $getHbTime)
   getHbTimeFmt=$(date -d @${getHbTime} '+%Y-%m-%d %H:%M:%S')
   logger -p user.info -t MOLD-HA-AC "[Checking] 호스트:$HostIP | HB 파일 체크(CLVM with GFS) > [현 시간:$CurrentTime | HB 파일 시간:$getHbTimeFmt | 시간 차이:$diff초]"
   if [ $diff -le $interval ]; then
      logger -p user.info -t MOLD-HA-AC "[Result]   호스트:$HostIP | HB 체크 결과(CLVM with GFS) > [HOST STATE : ALIVE]"
      echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
      exit 0
   fi
else
   logger -p user.info -t MOLD-HA-AC "[Writing]  호스트:$HostIP | HB 파일 갱신(CLVM) 실패!!! > RBD 또는 GFS 형식의 스토리지가 존재하지 않습니다."
   printf "There is no storage information of type RBD or SharedMountPoint."
   return 0
fi

if [ -z "$UUIDList" ]; then
   logger -p user.info -t MOLD-HA-AC "[Result]   호스트:$HostIP | HB 체크 결과(CLVM) > [HOST HOST STATE : DEAD] 볼륨 UUID 목록이 비어 있음 => 호스트가 다운된 것으로 간주됨"
   echo " ### [HOST STATE : DEAD] Volume UUID list is empty => Considered host down in [PoolType : CLVM] ###"
   exit 0
fi

# Second check: disk activity check
statusFlag=false
for img in $(echo $UUIDList | sed 's/,/ /g'); do
   # vol_persist=$(sg_persist -ik /dev/vg_iscsi/$UUID)
   vol_lvs=$(lvs 2>/dev/null | grep $img)
   if [[ $vol_lvs =~ "-wi-ao----" ]]; then
      statusFlag=true
      logger -p user.info -t MOLD-HA-AC "[Result]   호스트:${HostIP} | AC 체크 결과(CLVM) > [HOST STATE : ALIVE] ${img} 볼륨이 사용중(-wi-ao----)으로 확인"
      echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
      break
   fi
done

# 빠져나왔으면 DEAD
logger -p user.info -t MOLD-HA-AC "[Result]   호스트:${HostIP} | HB 체크 결과(CLVM) > [HOST STATE : DEAD] 볼륨 이미지 목록의 정상 동작을 확인할 수 없음 => 호스트가 다운된 것으로 간주됨"
echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : CLVM] ### "
exit 0
