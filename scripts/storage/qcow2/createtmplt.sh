#!/usr/bin/env bash
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


# $Id: createtmplt.sh 11601 2010-08-11 17:26:15Z kris $ $HeadURL: svn://svn.lab.vmops.com/repos/branches/2.1.refactor/java/scripts/storage/qcow2/createtmplt.sh $
# createtmplt.sh -- install a template

usage() {
  printf "Usage: %s: -t <template-fs> -n <templatename> -f <root disk file> -s <size in Gigabytes> -c <snapshot name> -d <descr> -h  [-u]\n" $(basename $0) >&2
}


#set -x
qemu_img="cloud-qemu-img"
which $qemu_img >& /dev/null
if [ $? -gt 0 ]
then
   which qemu-img >& /dev/null
   if [ $? -eq 0 ]
   then
       qemu_img="qemu-img"
   fi
fi

untar() {
  local ft=$(file "$1"| awk -F" " '{print $2}')
  local basedir=$(dirname $1)
  case $ft in
  USTAR)  local rootimg=$(tar tf $1 | grep $3)
          (cd $2; tar xf $1)
          rm -f $1
          printf "$2/$rootimg"
          ;;
      *)  printf "$1"
          return 0
	  ;;
  esac

}

uncompress() {
  local ft=$(file "$1"| awk -F" " '{print $2}')
  local imgfile=${1%.*} #strip out trailing file suffix
  local tmpfile=${imgfile}.tmp

  case $ft in
  gzip)  gunzip -c $1 > $tmpfile
         ;;
  bzip2)  bunzip2 -c $1 > $tmpfile
         ;;
  [zZ][iI][pP])  unzip -p $1 | cat > $tmpfile
        ;;
  XZ)   xz -d -c $1 > $tmpfile
        ;;
  *)	printf "$1"
        return 0
	;;
  esac

  if [ $? -gt 0 ]
  then
    printf "Failed to uncompress file, exiting "
    exit 1
  fi

  mv $tmpfile $imgfile
  printf "$imgfile"

  return 0
}

create_from_file() {
  local tmpltfs=$1
  local tmpltimg="$2"
  local tmpltname=$3
  local target="/$tmpltfs/$tmpltname"
  local format
  format=$($qemu_img info "$tmpltimg") || return 1
  if [ -b "$tmpltimg" ] || printf '%s\n' "$format" | grep -q '^file format: raw$'; then
      $qemu_img convert -f raw -O qcow2 "$tmpltimg" "$target" || return 1
  else
    # if backing image exists, we need to combine them, otherwise
    # copy the image to preserve snapshots/compression
    if printf '%s\n' "$format" | grep -q backing; then
      $qemu_img convert -f qcow2 -O qcow2 "$tmpltimg" "$target" || return 1
    else
      cp -f -- "$tmpltimg" "$target" || return 1
    fi
  fi

  # A valid header alone cannot detect an interrupted copy with zeroed L2 entries.
  # Verify guest-visible data before publishing the template's properties.
  $qemu_img compare "$tmpltimg" "$target" || return 1
  chmod a+r "$target" || return 1
}

create_from_snapshot() {
  local tmpltImg="$1"
  local snapshotName="$2"
  local tmpltfs=$3
  local tmpltname=$4

  $qemu_img convert -f qcow2 -O qcow2 -s "$snapshotName" "$tmpltImg" "/$tmpltfs/$tmpltname"
  if [ $? -gt 0 ]
  then
     printf "Failed to create template /$tmpltfs/$tmpltname from snapshot $snapshotName on disk $tmpltImg "
     exit 2
  fi

  chmod a+r "/$tmpltfs/$tmpltname" || return 1
}

tflag=
nflag=
fflag=
sflag=
hflag=
hvm=false
cleanup=false
dflag=
cflag=
snapshotName=

while getopts 'uht:n:f:sc:d:' OPTION
do
  case $OPTION in
  t)	tflag=1
		tmpltfs="$OPTARG"
		;;
  n)	nflag=1
		tmpltname="$OPTARG"
		;;
  f)	fflag=1
		tmpltimg="$OPTARG"
		;;
  s)	sflag=1
		;;
  c)	cflag=1
		snapshotName="$OPTARG"
		;;
  d)	dflag=1
		descr="$OPTARG"
		;;
  u)	cleanup="true"
		;;
  ?)	usage
		exit 2
		;;
  esac
done


if [ ! -d "/$tmpltfs" ]
then
  mkdir -p "/$tmpltfs"
  if [ $? -gt 0 ]
  then
    printf "Failed to create user fs $tmpltfs\n" >&2
    exit 1
  fi
fi

if [ ! -f "$tmpltimg" ] && [ ! -b "$tmpltimg" ]
then
  printf "root disk file $tmpltimg doesn't exist\n"
  exit 3
fi

tmpltimg=$(uncompress "$tmpltimg")
if [ $? -ne 0 ]
then
  printf "failed to uncompress %s\n" "$tmpltimg" >&2
  exit 1
fi

target="/$tmpltfs/$tmpltname"
properties="/$tmpltfs/template.properties"
complete=false
# A failed copy must never leave a readable partial template or success metadata.
trap 'if [ "$complete" != "true" ]; then rm -f -- "$target" "$properties"; fi' EXIT
if [ "$sflag" == "1" ]
then
   create_from_snapshot "$tmpltimg" "$snapshotName" "$tmpltfs" "$tmpltname" || exit 2
else
   create_from_file "$tmpltfs" "$tmpltimg" "$tmpltname" || exit 2
fi

today=$(date '+%m_%d_%Y')
{
  printf 'filename=%s\n' "$tmpltname" &&
  printf 'snapshot.name=%s\n' "$today" &&
  printf 'description=%s\n' "$descr"
} > "$properties" || exit 2
chmod a+r "$properties" || exit 2
complete=true

if [ "$cleanup" == "true" ]
then
  rm -f "$tmpltimg"
fi

exit 0
