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

while getopts 'p:n:g:h:q:u:t:d:' OPTION
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
now=$(date +%s)
if [ -n "$RbdPoolName" ] ; then
   getHbTime=$(rbd -p $RbdPoolName --id $RbdPoolAuthUserName image-meta get MOLD-HB $HostIP-$poolPath)
   if [ $? -eq 0 ]; then
      diff=$(expr $now - $getHbTime)
      if [ $diff -le $interval ]; then
         echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
         exit 0
      fi
   fi
elif [ -n "$GfsPoolPath" ] ; then
   now=$(date +%s)
   getHbTime=$(cat $hbFile)
   diff=$(expr $now - $getHbTime)
   if [ $diff -le $interval ]; then
      echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
      exit 0
   fi
else
   printf "There is no storage information of type RBD or SharedMountPoint."
   return 0
fi

if [ -z "$UUIDList" ]; then
    echo " ### [HOST STATE : DEAD] Volume UUID list is empty => Considered host down in [PoolType : CLVM] ###"
    exit 0
fi

# Second check: disk activity check
statusFlag=true
for UUID in $(echo $UUIDList | sed 's/,/ /g'); do
    # vol_persist=$(sg_persist -ik /dev/vg_iscsi/$UUID)
    vol_lvs=$(lvs 2>/dev/null | grep $UUID)
    if [[ $vol_lvs =~ "-wi-ao----" ]]; then
        continue
    else
        statusFlag=false
        break
    fi
done

if [ statusFlag == "true" ]; then
    echo "### [HOST STATE : ALIVE] in [PoolType : CLVM] ###"
else
    echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : CLVM] ### "
fi

exit 0