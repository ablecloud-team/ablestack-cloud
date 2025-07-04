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
                    -m mount point
                    -h host
                    -r write/read hb log
                    -c cleanup
                    -t interval between read hb log\n"
  exit 1
}
#set -x
MountPoint=
HostIP=
interval=
rflag=0
cflag=0

while getopts 'm:h:t:rc' OPTION; do
  case $OPTION in
  m)
    MountPoint="$OPTARG"
    ;;
  h)
    HostIP="$OPTARG"
    ;;
  r)
    rflag=1
    ;;
  t)
    interval="$OPTARG"
    ;;
  c)
    cflag=1
    ;;
  *)
    help
    ;;
  esac
done

# #delete VMs on this mountpoint
# deleteVMs() {
#   local mountPoint=$1
#   vmPids=$(ps aux| grep qemu | grep "$mountPoint" | awk '{print $2}' 2> /dev/null)
#   if [ $? -gt 0 ]
#   then
#      return
#   fi

#   if [ -z "$vmPids" ]
#   then
#      return
#   fi

#   for pid in $vmPids
#   do
#      kill -9 $pid &> /dev/null
#   done
# }

# #checking is there the same nfs server mounted under $MountPoint?
# mounts=$(cat /proc/mounts |grep nfs|grep $MountPoint)
# if [ $? -gt 0 ]
# then
#    # remount it
#    mount $NfsSvrIP:$NfsSvrPath $MountPoint -o sync,soft,proto=tcp,acregmin=0,acregmax=0,acdirmin=0,acdirmax=0,noac &> /dev/null
#    if [ $? -gt 0 ]
#    then
#       printf "Failed to remount $NfsSvrIP:$NfsSvrPath under $MountPoint"
#       exit 1
#    fi
#    if [ "$rflag" == "0" ]
#    then
#      deleteVMs $MountPoint
#    fi
# fi

hbFolder=$MountPoint/MOLD-HB
MPTitle=$(echo $MountPoint | sed 's/\//-/g' 2>/dev/null)

hbFile=$hbFolder/$HostIP$MPTitle

write_hbLog() {
  #write the heart beat log
  stat $hbFile &>/dev/null
  if [ $? -gt 0 ]; then
    # create a new one
    mkdir -p $hbFolder &>/dev/null
    touch $hbFile &>/dev/null
    if [ $? -gt 0 ]; then
      printf "Failed to create $hbFile"
      return 2
    fi
  fi

  Timestamp=$(date +%s)
  CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")
  echo "$Timestamp" >"$hbFile"
  ret=$?
  if [ $ret -eq 0 ]; then
    logger -p user.info -t MOLD-HA-HB "[Writing]  호스트:$HostIP | HB 파일 갱신(GFS, 스토리지:$MountPoint) > [현 시간:$CurrentTime]"
  else
    logger -p user.info -t MOLD-HA-HB "[Writing]  호스트:$HostIP | HB 파일 갱신(GFS, 스토리지:$MountPoint) > HB 갱신 실패!!!"
  fi
  return 0
}

check_hbLog() {
  Timestamp=$(date +%s)
  CurrentTime=$(date +"%Y-%m-%d %H:%M:%S")

  getHbTime=$(cat $hbFile)
  diff=$(expr $Timestamp - $getHbTime)

  getHbTimeFmt=$(date -d @${getHbTime} '+%Y-%m-%d %H:%M:%S')
  logger -p user.info -t MOLD-HA-HB "[Checking] 호스트:$HostIP | HB 파일 체크(GFS, 스토리지:$MountPoint) > [현 시간:$CurrentTime | HB 파일 시간:$getHbTimeFmt | 시간 차이:$diff초]"
  if { [ "$diff" -gt 30 ] && [ "$diff" -le 45 ]; } || { [ "$diff" -gt 60 ] && [ "$diff" -le 75 ]; }; then
    timeout 1 ssh ccvm "
      mysql -u cloud -pAblecloud1! -D cloud -e \"
        INSERT INTO event (
          uuid, type, state, description, user_id, account_id, domain_id,
          resource_id, resource_type, created, level, start_id,
          parameters, archived, display
        ) VALUES (
          UUID(), 'HA.STATE.TRANSITION', 'Completed',
          '[Heartbeat Checking] Host: $HostIP | Storage: $MountPoint [Current Time: $CurrentTime | HB File Time: $getHbTimeFmt | Time Difference: $diff seconds]', 1, 1, 1, 0, 'Host',
          UTC_TIMESTAMP(), 'WARN', 0, NULL, 0, 1
        );
      \"
    "
  fi

  if [ $diff -gt $interval ]; then
    logger -p user.info -t MOLD-HA-HB "[Result]   호스트:$HostIP | HB 체크 결과(GFS, 스토리지:$MountPoint) > [HOST STATE : DEAD]"
    echo "### [HOST STATE : DEAD] Set maximum interval: ($interval seconds), Actual difference: ($diff seconds) => Considered host down in [PoolType : SharedMountPoint] ###"
    return 0
  else
    logger -p user.info -t MOLD-HA-HB "[Result]   호스트:$HostIP | HB 체크 결과(GFS, 스토리지:$MountPoint) > [HOST STATE : ALIVE]"
    echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
  fi
  return 0
}

if [ "$rflag" == "1" ]; then
  check_hbLog
  exit 0
elif [ "$cflag" == "1" ]; then
  /usr/bin/logger -t heartbeat "kvmheartbeat_gfs.sh will reboot system because it was unable to write the heartbeat to the storage."
  sync &
  sleep 5
  echo b >/proc/sysrq-trigger
  exit $?
else
  write_hbLog
  exit 0
fi
