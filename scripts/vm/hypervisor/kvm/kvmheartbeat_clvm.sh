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
                    -h host ip
                    -q clvm pool mount source path
                    -r write/read hb log
                    -c cleanup
                    -t interval between read hb log\n"
  exit 1
}
#set -x
RbdPoolName=
RbdPoolAuthUserName=
GfsPoolPath=
HostIP=
poolPath=
interval=0
rflag=0
cflag=0

while getopts 'p:n:g:h:q:t:rc' OPTION
do
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
  t)
     interval="$OPTARG"
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

poolPath=$(echo $poolPath | cut -d '/' -f2-)

hbFolder=$GfsPoolPath/MOLD-HB
hbFile=$hbFolder/$HostIP-$poolPath

write_hbLog() {
  #write the heart beat log
  path=$(pvs 2>/dev/null | grep -w $poolPath | awk '{print $1}' | sed 's/[0-9]//g')
  persist=$(multipath -l $path | grep status=active)
  if [ $? -eq 0 ]
  then
    Timestamp=$(date +%s)
    if [ -n "$RbdPoolName" ]; then
      obj=$(rbd -p $RbdPoolName ls --id $RbdPoolAuthUserName | grep MOLD-HB)
      if [ $? -gt 0 ]; then
        rbd -p $RbdPoolName create --size 1 --id $RbdPoolAuthUserName MOLD-HB
      fi
      obj=$(rbd -p $RbdPoolName --id $RbdPoolAuthUserName image-meta set MOLD-HB $HostIP-$poolPath $Timestamp)
    elif [ -n "$GfsPoolPath" ] ; then
        stat $hbFile &> /dev/null
        if [ $? -gt 0 ] ; then
          mkdir -p $hbFolder &> /dev/null
          touch $hbFile &> /dev/null
          if [ $? -gt 0 ] ; then
            printf "Failed to create $hbFile"
            return 2
          fi
        fi

        echo $Timestamp > $hbFile
        return $?
    else
      printf "There is no storage information of type RBD or SharedMountPoint."
      return 0
    fi

    if [ $? -gt 0 ]; then
   	  printf "Failed to create rbd file and set image-meta"
      return 2
    fi
    return 0
  fi
}

check_hbLog() {
#check the heart beat log
  now=$(date +%s)
  if [ -n "$RbdPoolName" ] ; then
    getHbTime=$(rbd -p $RbdPoolName --id $RbdPoolAuthUserName image-meta get MOLD-HB $HostIP-$poolPath)
    if [ $? -gt 0 ] || [ -z "$getHbTime" ]; then
      return 1
    fi
    diff=$(expr $now - $getHbTime)
  elif [ -n "$GfsPoolPath" ] ; then
    getHbTime=$(cat $hbFile)
    diff=$(expr $now - $getHbTime)
  else
    printf "There is no storage information of type RBD or SharedMountPoint."
    return 0
  fi

  if [ $diff -gt $interval ]; then
    return $diff
  fi
  return 0
}

if [ "$rflag" == "1" ]; then
  check_hbLog
  diff=$?
  if [ $diff == 0 ]; then
    echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
  else
    echo "### [HOST STATE : DEAD] Set maximum interval: ($interval seconds), Actual difference: ($diff seconds) => Considered host down in [PoolType : CLVM] ###"
  fi
  exit 0
elif [ "$cflag" == "1" ]; then
  /usr/bin/logger -t heartbeat "kvmheartbeat_clvm.sh will reboot system because it was unable to write the heartbeat to the storage."
  sync &
  sleep 5
  echo b > /proc/sysrq-trigger
  exit $?
else
  write_hbLog
  exit 0
fi
