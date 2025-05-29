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

while getopts 'p:n:s:h:i:u:t:' OPTION
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

# if [ -z "$SuspectTime" ]; then
#   exit 2
# fi

# First check: heartbeat filei

now=$(date +%s)
getHbTime=$(rbd -p $PoolName --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret image-meta get MOLD-HB $HostIP)

if [ $? -eq 0 ]; then
   diff=$(expr $now - $getHbTime)
   if [ $diff -le $interval ]; then
      echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"
      exit 0
   fi
fi

if [ -z "$UUIDList" ]; then
   echo " ### [HOST STATE : DEAD] Volume UUID list is empty => Considered host down in [PoolType : RBD] ###"
   exit 0
fi

# Second check: disk activity check
for uuid in $(echo $UUIDList | sed 's/,/ /g'); do
   acTime=$(rbd -p $PoolName --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret image-meta get MOLD-AC $uuid > /dev/null 2>&1)
   if [ $? -gt 0 ]; then
      echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : RBD] ### "
      exit 0
   else
      acTime=$(rbd -p $PoolName --id $PoolAuthUserName -m $SourceHostIP -K $skeyPath$PoolAuthSecret image-meta get MOLD-AC $uuid)
      if [ -z "$acTime" ]; then
         echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : RBD] ### "
         exit 0
      else
         arrTime=(${acTime//:/ })
         acTime=${arrTime[1]}
         if [ $(expr $now - $acTime) > $interval ]; then
            echo "### [HOST STATE : DEAD] Unable to confirm normal activity of volume image list => Considered host down in [PoolType : RBD] ### "
            exit 0
         fi
      fi
   fi
done

echo "### [HOST STATE : ALIVE] in [PoolType : RBD] ###"

exit 0