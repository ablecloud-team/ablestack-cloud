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


# Names and ownership must agree for create, reconnect, migration and cleanup.
# Keep existing usable names. Never rename the physical device or an existing bridge.
usage() {
    echo "Usage: $0 -o add|delete -v VLAN -p PIF -b BRIDGE [-d true|false]" >&2
}
fail() {
    echo "VLAN operation failed: pif=$pif vlan=$vlanId bridge=$brName(${#brName}) vlanDevice=$vlanDev(${#vlanDev}): $*" >&2
    return 1
}
validName() { [[ $1 =~ ^[a-zA-Z0-9_.:-]{1,15}$ ]]; }
vlanIdentity() {
    [[ -r /proc/net/vlan/$1 ]] || return 1
    awk '/VID:/ {for(i=1;i<=NF;i++) if($i=="VID:") vid=$(i+1)} /^Device:/ {parent=$2} END {if(parent!="" && vid!="") print parent, vid; else exit 1}' "/proc/net/vlan/$1"
}
checkVlan() { [[ $(vlanIdentity "$vlanDev") == "$pif $vlanId" ]]; }
marker() { cat "/sys/class/net/$1/ifalias"; }
checkBridge() {
    [[ -d /sys/class/net/$brName/bridge ]] || { fail "existing name is not a Linux bridge"; return 1; }
    local owner member
    owner=$(marker "$brName")
    if [[ $owner != "$identity" ]]; then
        # Unmarked historical bridges are reusable only under their exact original name.
        [[ -z $owner && ( $brName == "br$pif-$vlanId" || $brName == "cloudVirBr$vlanId" ) ]] || { fail "bridge ownership conflict ($owner)"; return 1; }
    fi
    for member in /sys/class/net/"$brName"/brif/*; do
        [[ -e $member ]] || continue
        member=${member##*/}
        [[ $member == "$vlanDev" || -e /sys/class/net/$member/tun_flags ]] || { fail "bridge contains unrelated member $member"; return 1; }
    done
}
addVlan() {
    local madeVlan=false madeBridge=false master
    [[ -d /sys/class/net/$pif ]] || { fail "parent does not exist"; return 1; }
    if [[ -e /sys/class/net/$brName ]]; then checkBridge || return 1; fi
    if [[ -e /sys/class/net/$vlanDev ]]; then
        checkVlan || { fail "VLAN device ownership conflict"; return 1; }
        master=$(readlink "/sys/class/net/$vlanDev/master")
        [[ -z $master || ${master##*/} == "$brName" ]] || { fail "VLAN already belongs to $master"; return 1; }
    else
        ip link add link "$pif" name "$vlanDev" type vlan id "$vlanId" || { fail "cannot create VLAN device"; return 1; }
        madeVlan=true
        ip link set dev "$vlanDev" alias "$identity"
    fi
    if [[ ! -e /sys/class/net/$brName ]]; then
        if ! ip link add name "$brName" type bridge; then
            $madeVlan && ip link delete dev "$vlanDev"
            fail "cannot create bridge"; return 1
        fi
        madeBridge=true
        ip link set dev "$brName" alias "$identity"
    fi
    if ! { ip link set dev "$vlanDev" up && ip link set dev "$vlanDev" master "$brName" && ip link set dev "$brName" up; }; then
        # Roll back only devices created by this invocation; leave existing devices untouched.
        $madeVlan && ip link delete dev "$vlanDev"
        $madeBridge && ip link delete dev "$brName" type bridge
        fail "cannot activate/attach VLAN"; return 1
    fi
    echo 1 > "/proc/sys/net/ipv6/conf/$vlanDev/disable_ipv6"
    echo 1 > "/proc/sys/net/ipv6/conf/$brName/disable_ipv6"
}
deleteVlan() {
    [[ $deleteBr == true ]] || return 0
    [[ -e /sys/class/net/$brName ]] || return 0
    # A compact bridge cannot encode the original PIF. Its creation marker is authoritative,
    # but it must also agree with every actual VLAN member before anything is removed.
    local owner member
    owner=$(marker "$brName")
    if [[ $owner == cloudstack:vlan:* ]]; then
        [[ ${owner##*:} == "$vlanId" ]] || { fail "bridge VLAN marker conflict"; return 1; }
        local recordedPif=${owner#cloudstack:vlan:}
        recordedPif=${recordedPif%:*}
        [[ $pif == undefined || $pif == "$recordedPif" ]] || { fail "bridge parent marker conflict"; return 1; }
        pif=$recordedPif
        setNames || return 1
    elif [[ $pif == undefined ]]; then
        # Historical cloudVirBr bridges have no parent in their name. Require exactly one VLAN.
        local found=0 parent vid
        for member in /sys/class/net/"$brName"/brif/*; do
            [[ -e $member ]] || continue
            read -r parent vid < <(vlanIdentity "${member##*/}")
            [[ $vid == "$vlanId" && -n $parent ]] || { fail "unrecognized historical bridge member"; return 1; }
            pif=$parent; ((found+=1))
        done
        [[ $found == 1 ]] || { fail "historical bridge parent is ambiguous"; return 1; }
        setNames || return 1
    fi
    checkBridge || return 1
    for member in /sys/class/net/"$brName"/brif/*; do
        [[ -e $member ]] || continue
        [[ ${member##*/} == "$vlanDev" ]] || { fail "bridge is still in use by ${member##*/}"; return 1; }
    done
    if [[ -e /sys/class/net/$vlanDev ]]; then
        checkVlan || { fail "refusing to delete unrelated VLAN"; return 1; }
        [[ -e /sys/class/net/$brName/brif/$vlanDev ]] || { fail "VLAN is not attached to this bridge"; return 1; }
        ip link delete dev "$vlanDev" type vlan || { fail "cannot delete VLAN"; return 1; }
    fi
    ip link delete dev "$brName" type bridge || { fail "cannot delete bridge"; return 1; }
}
setNames() {
    validName "$pif" || { fail "invalid parent name"; return 1; }
    vlanDev="$pif.$vlanId"
    if (( ${#vlanDev} > 15 )); then
        local hash
        hash=$(printf '%s\n%s' "$pif" "$vlanId" | sha256sum) || return 1
        vlanDev="vlan${hash:0:11}"
    fi
    identity="cloudstack:vlan:$pif:$vlanId"
}
op= vlanId= pif= brName= vlanDev= deleteBr=true
while getopts 'o:v:p:b:d:' option; do
    case $option in
        o) op=$OPTARG;; v) vlanId=$OPTARG;; p) pif=$OPTARG;; b) brName=$OPTARG;; d) deleteBr=$OPTARG;; *) usage; exit 2;;
    esac
done
[[ $op == add || $op == delete ]] && [[ $vlanId =~ ^[0-9]{1,4}$ ]] && validName "$brName" && [[ $deleteBr == true || $deleteBr == false ]] || { usage; exit 2; }
vlanId=$((10#$vlanId))
# Reserved sentinel used by existing native VLAN callers.
[[ $vlanId == 4095 ]] && exit 0
(( vlanId >= 1 && vlanId <= 4094 )) || { fail "VID must be between 1 and 4094"; exit 2; }
setNames || exit 2
# Serialize across processes, including hash collisions (which fail ownership checks).
exec 9>"/run/lock/cloudstack-vlan-$brName.lock" || exit 1
flock -x 9 || exit 1
if [[ $op == add ]]; then addVlan; else deleteVlan; fi
