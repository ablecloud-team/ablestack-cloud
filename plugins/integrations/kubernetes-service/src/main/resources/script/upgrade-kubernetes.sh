#!/bin/bash -e
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

# Version 1.14 and below needs extra flags with kubeadm upgrade node
if [ $# -lt 4 ]; then
    echo "Invalid input. Valid usage: ./upgrade-kubernetes.sh UPGRADE_VERSION IS_CONTROL_NODE IS_OLD_VERSION IS_EJECT_ISO IS_EXTERNAL_CNI"
    echo "eg: ./upgrade-kubernetes.sh 1.16.3 true false false"
    exit 1
fi
UPGRADE_VERSION="${1}"
IS_MAIN_CONTROL=""
if [ $# -gt 1 ]; then
  IS_MAIN_CONTROL="${2}"
fi
IS_OLD_VERSION=""
if [ $# -gt 2 ]; then
  IS_OLD_VERSION="${3}"
fi
EJECT_ISO_FROM_OS=false
if [ $# -gt 3 ]; then
  EJECT_ISO_FROM_OS="${4}"
fi
EXTERNAL_CNI=false
if [ $# -gt 4 ]; then
  EXTERNAL_CNI="${5}"
fi

export PATH=$PATH:/opt/bin
if [[ "$PATH" != *:/usr/sbin && "$PATH" != *:/usr/sbin:* ]]; then
  export PATH=$PATH:/usr/sbin
fi

ISO_MOUNT_DIR=/mnt/k8sdisk
BINARIES_DIR=${ISO_MOUNT_DIR}/

OFFLINE_INSTALL_ATTEMPT_SLEEP=5
MAX_OFFLINE_INSTALL_ATTEMPTS=10
offline_attempts=1
iso_drive_path=""
while true; do
  if (( "$offline_attempts" > "$MAX_OFFLINE_INSTALL_ATTEMPTS" )); then
    echo "Warning: Offline install timed out!"
    break
  fi
  set +e
  output=`blkid -o device -t LABEL=CDROM`
  set -e
  if [ "$output" != "" ]; then
    while read -r line; do
      if [ ! -d "${ISO_MOUNT_DIR}" ]; then
        mkdir "${ISO_MOUNT_DIR}"
      fi
      retval=0
      set +e
      mount -o ro "${line}" "${ISO_MOUNT_DIR}"
      retval=$?
      set -e
      if [ $retval -eq 0 ]; then
        if [ -d "$BINARIES_DIR" ]; then
          iso_drive_path="${line}"
          break
        else
          umount "${line}" && rmdir "${ISO_MOUNT_DIR}"
        fi
      fi
    done <<< "$output"
  fi
  if [ -d "$BINARIES_DIR" ]; then
    break
  fi
  echo "Waiting for Binaries directory $BINARIES_DIR to be available, sleeping for $OFFLINE_INSTALL_ATTEMPT_SLEEP seconds, attempt: $offline_attempts"
  sleep $OFFLINE_INSTALL_ATTEMPT_SLEEP
  offline_attempts=$[$offline_attempts + 1]
done

if [ -d "$BINARIES_DIR" ]; then
  ### Binaries available offline ###
  echo "Installing binaries from ${BINARIES_DIR}"

  cd /opt/bin

  if [ -f "${BINARIES_DIR}/manifest.json" ]; then
    (cd "${BINARIES_DIR}" && sha256sum -c SHA256SUMS) || exit 1
  fi
  cp ${BINARIES_DIR}/k8s/kubeadm /opt/bin
  chmod +x kubeadm
  # Preserve archive digests; containerd 2.x defaults to transfer import.
  CTR_IMPORT_OPTIONS=()
  if ctr -n k8s.io image import --help 2>/dev/null | grep -q -- "--local"; then
    CTR_IMPORT_OPTIONS=(--local)
  fi
  output=$(find "${BINARIES_DIR}/docker" -maxdepth 1 -type f -name "*.tar" -printf "%f\n")
  if [ "$output" != "" ]; then
    while read -r line; do
        image_repository=""
        if [ -s "${BINARIES_DIR}/docker/images.list" ]; then
          image_repository=$(awk -v archive="$line" '$1 == archive {print $2}' "${BINARIES_DIR}/docker/images.list")
          [ -n "$image_repository" ] || { echo "ERROR: image import repository missing" >&2; exit 1; }
        fi
        ctr -n k8s.io image import "${CTR_IMPORT_OPTIONS[@]}" --digests --base-name "$image_repository" "${BINARIES_DIR}/docker/$line"
    done <<< "$output"
  fi
  if [ -e "${BINARIES_DIR}/provider.yaml" ]; then
    mkdir -p /opt/provider
    cp "${BINARIES_DIR}/provider.yaml" /opt/provider/provider.yaml
  fi

  # Fetch the autoscaler if present
  if [ -e "${BINARIES_DIR}/autoscaler.yaml" ]; then
    mkdir -p /opt/autoscaler
    cp "${BINARIES_DIR}/autoscaler.yaml" /opt/autoscaler/autoscaler_tmpl.yaml
  fi

  PAUSE_IMAGE=""
  if [ -s "${BINARIES_DIR}/docker/images.list" ]; then
    PAUSE_IMAGE=$(awk '$2 ~ /\/pause(:[^@]+)?$/ {digest=$1; sub(/\.tar$/, "", digest); repository=$2; sub(/:[^/]+$/, "", repository); print repository "@sha256:" digest}' "${BINARIES_DIR}/docker/images.list")
  else
    PAUSE_IMAGE=$(ctr -n k8s.io images ls -q | grep -E '/pause(:|@)' | sort | tail -n 1)
  fi
  if [ -z "$PAUSE_IMAGE" ] || ! ctr -n k8s.io images ls -q | grep -Fxq "$PAUSE_IMAGE"; then
    echo "ERROR: ISO pause image is not imported" >&2
    exit 1
  fi
  if grep -qE '^[[:space:]]*sandbox_image[[:space:]]*=' /etc/containerd/config.toml; then
    sed -i -E "s|^([[:space:]]*)sandbox_image[[:space:]]*=.*|\1sandbox_image = \"$PAUSE_IMAGE\"|" /etc/containerd/config.toml
  else
    # containerd 2.x uses config version 3 and a separate pinned_images table.
    if ! awk -v image="$PAUSE_IMAGE" '
      /^[[:space:]]*\[/ {pinned = ($0 ~ /cri\.v1\.images.*\.pinned_images\]/)}
      pinned && /^[[:space:]]*sandbox[[:space:]]*=/ {$0 = "      sandbox = \"" image "\""; changed = 1}
      {print}
      END {if (!changed) exit 1}
    ' /etc/containerd/config.toml > /etc/containerd/config.toml.pause.tmp; then
      rm -f /etc/containerd/config.toml.pause.tmp
      echo "ERROR: unsupported containerd sandbox image configuration" >&2
      exit 1
    fi
    cat /etc/containerd/config.toml.pause.tmp > /etc/containerd/config.toml
    rm -f /etc/containerd/config.toml.pause.tmp
  fi
  echo "Configured ISO pause image: $PAUSE_IMAGE"

  tar -f "${BINARIES_DIR}/cni/cni-plugins-"*64.tgz -C /opt/cni/bin -xz
  tar -f "${BINARIES_DIR}/cri-tools/crictl-linux-"*64.tar.gz -C /opt/bin -xz

  if [ "${IS_MAIN_CONTROL}" == 'true' ]; then
    set +e
    kubeadm --v=5 upgrade apply ${UPGRADE_VERSION} -y
    retval=$?
    set -e
    if [ $retval -ne 0 ]; then
      kubeadm --v=5 upgrade apply ${UPGRADE_VERSION} --ignore-preflight-errors=CoreDNSUnsupportedPlugins -y
    fi
  else
    if [ "${IS_OLD_VERSION}" == 'true' ]; then
      kubeadm --v=5 upgrade node config --kubelet-version ${UPGRADE_VERSION}
    else
      kubeadm --v=5 upgrade node
    fi
  fi

  systemctl stop kubelet
  cp -a ${BINARIES_DIR}/k8s/{kubelet,kubectl} /opt/bin
  chmod +x /opt/bin/{kubelet,kubectl}

  # New Mold payloads use the external CCM to initialize canonical provider IDs.
  # Preserve unrelated kubelet flags when enabling an existing cluster.
  if [ -s "${BINARIES_DIR}/provider.yaml" ]; then
    if ! grep -q -- '--cloud-provider=external' /etc/default/kubelet; then
      if grep -q -- '--cloud-provider=' /etc/default/kubelet; then
        echo "ERROR: conflicting kubelet cloud provider" >&2
        exit 1
      fi
      awk '
        /^KUBELET_EXTRA_ARGS=/ {
          value = substr($0, length("KUBELET_EXTRA_ARGS=") + 1)
          quote = substr(value, 1, 1)
          if ((quote == sprintf("%c", 34) || quote == sprintf("%c", 39)) && substr(value, length(value), 1) == quote) {
            value = substr(value, 2, length(value) - 2)
          }
          $0 = "KUBELET_EXTRA_ARGS=" value " --cloud-provider=external"
        }
        {print}
      ' /etc/default/kubelet > /etc/default/kubelet.provider.tmp
      grep -q -- '--cloud-provider=external' /etc/default/kubelet.provider.tmp || exit 1
      cat /etc/default/kubelet.provider.tmp > /etc/default/kubelet
      rm -f /etc/default/kubelet.provider.tmp
    fi
  fi

  systemctl daemon-reload
  systemctl restart containerd
  systemctl restart kubelet

  if [ "${IS_MAIN_CONTROL}" == 'true' ]; then
    if [[ ${EXTERNAL_CNI} == true ]]; then
      /opt/bin/kubectl apply -f ${BINARIES_DIR}/network.yaml
    fi
    if [ -f "${BINARIES_DIR}/headlamp.yaml" ]; then
      /opt/bin/kubectl apply -f "${BINARIES_DIR}/headlamp.yaml"
    elif [ -f "${BINARIES_DIR}/dashboard.yaml" ]; then
      /opt/bin/kubectl apply -f "${BINARIES_DIR}/dashboard.yaml"
    else
      echo "ERROR: dashboard payload is missing" >&2
      exit 1
    fi
    [ -s /opt/provider/provider.yaml ] || { echo "ERROR: Mold Provider payload is missing" >&2; exit 1; }
    /opt/bin/kubectl apply -f /opt/provider/provider.yaml
    # Already registered legacy nodes need CCM initialization as well. This
    # NoSchedule taint does not evict workloads; CCM removes it after API lookup.
    node_identities=$(mktemp)
    /opt/bin/kubectl --request-timeout=10s get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.providerID}{"\n"}{end}' > "$node_identities"
    while read -r node provider_id; do
      if [ -z "$provider_id" ]; then
        /opt/bin/kubectl --request-timeout=10s taint node "$node" node.cloudprovider.kubernetes.io/uninitialized=true:NoSchedule --overwrite
      fi
    done < "$node_identities"
    rm -f "$node_identities"
    /opt/bin/kubectl --request-timeout=10s wait --for=jsonpath='{.spec.providerID}' nodes --all --timeout=120s
  fi

  umount "${ISO_MOUNT_DIR}" && rmdir "${ISO_MOUNT_DIR}"
  if [ "$EJECT_ISO_FROM_OS" = true ] && [ "$iso_drive_path" != "" ]; then
    eject "${iso_drive_path}"
  fi
else
  echo "ERROR: Unable to access Binaries directory for upgrade version ${UPGRADE_VERSION}"
  exit 1
fi
