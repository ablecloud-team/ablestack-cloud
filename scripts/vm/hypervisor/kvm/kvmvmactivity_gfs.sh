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
                    -u volume uuid list
                    -i interval between read hb log
                    -t time on ms
                    -d suspect time\n"
  exit 1
}

#set -x

MountPoint=
HostIP=
UUIDList=
MSTime=
SuspectTime=
interval=

while getopts 'm:u:t:i:h:d:' OPTION
do
  case $OPTION in
  m)
     MountPoint="$OPTARG"
     ;;
  h)
     HostIP="$OPTARG"
     ;;
  u)
     UUIDList="$OPTARG"
     ;;
  i)
     interval="$OPTARG"
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

if [ -z "$SuspectTime" ]
then
   exit 2
fi

MPTitle=$(echo $MountPoint | sed 's/\//-/g' 2> /dev/null)

hbFile=$MountPoint/MOLD-HB/$HostIP$MPTitle
acFolder=$MountPoint/MOLD-AC
acFile=$acFolder/$HostIP$MPTitle

if [ ! -f $acFolder ]; then
    mkdir -p $acFolder &> /dev/null
fi

# First check: heartbeat file
now=$(date +%s)
hb=$(cat $hbFile)
diff=$(expr $now - $hb)
if [ $diff -lt $interval ]
then
    echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
    exit 0
fi

if [ -z "$UUIDList" ]
then
    echo " ### [HOST STATE : DEAD] Volume UUID list is empty => Considered host down in [PoolType : SharedMountPoint] ###"
    exit 0
fi

# Second check: disk activity check
cd $MountPoint
latestUpdateTime=$(stat -c %Y $(echo $UUIDList | sed 's/,/ /g') 2> /dev/null | sort -nr | head -1)

if [ ! -f $acFile ]; then
    echo "$SuspectTime:$latestUpdateTime:$MSTime" > $acFile

    if [[ $latestUpdateTime -gt $SuspectTime ]]; then
        echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
    else
         echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : SharedMountPoint] ### "
    fi
else
    acTime=$(cat $acFile)
    arrTime=(${acTime//:/ })
    lastSuspectTime=${arrTime[0]}
    lastUpdateTime=${arrTime[1]}
    echo "$SuspectTime:$latestUpdateTime:$MSTime" > $acFile

    suspectTimeDiff=$(expr $SuspectTime - $lastSuspectTime)
    if [[ $suspectTimeDiff -lt 0 ]]; then
        if [[ $latestUpdateTime -gt $SuspectTime ]]; then
            echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
        else
            echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : SharedMountPoint] ### "
        fi
    else
        if [[ $latestUpdateTime -gt $lastUpdateTime ]]; then
            echo "### [HOST STATE : ALIVE] in [PoolType : SharedMountPoint] ###"
        else
         echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : SharedMountPoint] ### "
        fi
    fi
fi

exit 0
