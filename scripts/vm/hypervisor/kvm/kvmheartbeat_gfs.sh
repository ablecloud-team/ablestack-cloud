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

while getopts 'm:h:t:rc' OPTION
do
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
MPTitle=$(echo $MountPoint | sed 's/\//-/g' 2> /dev/null)

hbFile=$hbFolder/$HostIP$MPTitle

write_hbLog() {
#write the heart beat log
  stat $hbFile &> /dev/null
  if [ $? -gt 0 ]
  then
     # create a new one
     mkdir -p $hbFolder &> /dev/null
     touch $hbFile &> /dev/null
     if [ $? -gt 0 ]
     then
 	printf "Failed to create $hbFile"
        return 2
     fi
  fi

  Timestamp=$(date +%s)
  echo $Timestamp > $hbFile
  return 0
}

check_hbLog() {
  now=$(date +%s)
  getHbTime=$(cat $hbFile)

  diff=$(expr $now - $getHbTime)

  if [ $diff -gt $interval ]; then
    return $diff
  fi
  return 0
}

if [ "$rflag" == "1" ]
then
  check_hbLog
  diff=$?
  if [ $diff == 0 ]
  then
    echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
  else
    echo "### [HOST STATE : DEAD] Set maximum interval: ($interval seconds), Actual difference: ($diff seconds) => Considered host down in [PoolType : SharedMountPoint] ###"
  fi
  exit 0
elif [ "$cflag" == "1" ]
then
  /usr/bin/logger -t heartbeat "kvmheartbeat_gfs.sh will reboot system because it was unable to write the heartbeat to the storage."
  sync &
  sleep 5
  echo b > /proc/sysrq-trigger
  exit $?
else
  write_hbLog
  exit 0
fi
