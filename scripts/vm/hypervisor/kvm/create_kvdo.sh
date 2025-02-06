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
                    -i image name
                    -s image size\n"
  exit 1
}
#set -x
PoolName=
PoolAuthUserName=
ImageName=
ImageSize=

while getopts 'p:i:n:s:' OPTION
do
  case $OPTION in
  p)
     PoolName="$OPTARG"
     ;;
  n)
     PoolAuthUserName="$OPTARG"
     ;;
  i)
     ImageName="$OPTARG"
     ;;
  s)
     ImageSize="$OPTARG"
     ;;
  *)
     help
     ;;
  esac
done

if [ -z "$PoolName" ] || [ -z "$ImageName" ]; then
  exit 2
fi

# Check if rbd image exists
devicePath=$(rbd showmapped | grep "$PoolName[ ]*$ImageName" | grep -o "[^ ]*[ ]*$")
if [ -z "$devicePath" ]; then
  # device no exist
  devicePath=$(rbd map "$PoolName/$ImageName" --id "$PoolAuthUserName")
fi

# Check if a partition exists
partitionExist=$(lsblk $devicePath -p -J |jq -r '.blockdevices[0].children')
vg_name=vg_$(echo $ImageName| sed 's/-//g')
if [ "$partitionExist" == "null" ]; then
  # create partition
  parted -s $devicePath mklabel gpt mkpart primary 0% 100% set 1 lvm on

  firstPartitionPath=$(lsblk $devicePath -p -J |jq -r '.blockdevices[0].children[0].name')

  # create pv
  pvcreate $firstPartitionPath

  # create vg
  vgcreate $vg_name $firstPartitionPath

  lvcreate --type vdo --name ablestack_kvdo -l +100%FREE $vg_name

else
  firstPartitionPath=$(lsblk $devicePath -p -J |jq -r '.blockdevices[0].children[0].name')
  ex_vg_name=$(pvs $firstPartitionPath --reportformat json |jq -r '.report[0].pv[0].vg_name')

  if [ -n "$ex_vg_name" ] && [ "$ex_vg_name" != "null" ] && [ $ex_vg_name != $vg_name ]; then
    vgrename $ex_vg_name $vg_name
    vgchange --uuid $vg_name
    pvchange --uuid $firstPartitionPath
  fi
  vgchange -ay $vg_name
fi

exit 0