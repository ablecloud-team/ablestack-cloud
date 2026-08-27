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
# specific language govening permissions and limitations
# under the License.

set -e
set -x

CLOUDSTACK_RELEASE=4.21.0
CREATE_DATE=

function configure_apache2() {
   # Enable ssl, rewrite and auth
   a2enmod ssl rewrite auth_basic auth_digest
   a2ensite default-ssl
   # Backup stock apache configuration since we may modify it in Secondary Storage VM
   cp /etc/apache2/sites-available/000-default.conf /etc/apache2/sites-available/default.orig
   cp /etc/apache2/sites-available/default-ssl.conf /etc/apache2/sites-available/default-ssl.orig
   sed -i 's/SSLProtocol .*$/SSLProtocol TLSv1.2/g' /etc/apache2/mods-available/ssl.conf
}

function configure_strongswan() {
  # change the charon stroke timeout from 3 minutes to 30 seconds
  sed -i "s/# timeout = 0/timeout = 30000/" /etc/strongswan.d/charon/stroke.conf
}

function configure_issue() {
  cat > /etc/issue <<EOF

   __?.o/  ABLECLOUD Mold SystemVM $CLOUDSTACK_RELEASE
  (  )#    https://www.ablecloud.io
 (___(_)   Debian GNU/Linux 12 \n \l
 ########  SystemVM Template Creation Date : $CREATE_DATE

EOF
}

function configure_cacerts() {
  CDIR=$(pwd)
  cd /tmp
  # Add LetsEncrypt ca-cert
  wget https://letsencrypt.org/certs/isrgrootx1.der
  wget https://letsencrypt.org/certs/lets-encrypt-r3.der
  keytool -trustcacerts -keystore /etc/ssl/certs/java/cacerts -storepass changeit -noprompt -importcert -alias letsencryptauthorityx1 -file isrgrootx1.der
  keytool -trustcacerts -keystore /etc/ssl/certs/java/cacerts -storepass changeit -noprompt -importcert -alias letsencryptauthorityr3 -file lets-encrypt-r3.der
  rm -f lets-encrypt-r3.der isrgrootx1.der
  cd $CDIR
}

function install_cloud_scripts() {
  # ./cloud_scripts/ has been put there by ../../cloud_scripts_shar_archive.sh
  rsync -av ./cloud_scripts/ /

  chmod +x /opt/cloud/bin/* /opt/cloud/bin/setup/* \
    /root/{clearUsageRules.sh,reconfigLB.sh,monitorServices.py} \
    /etc/profile.d/cloud.sh /etc/cron.daily/* /etc/cron.hourly/*
  if [ ! -s /usr/local/bin/ablestack-storagectl ]; then
    echo "Missing or empty /usr/local/bin/ablestack-storagectl" >&2
    exit 1
  fi
  if ! grep -q "probe_nfs_export_visibility" /usr/local/bin/ablestack-storagectl; then
    echo "Stale /usr/local/bin/ablestack-storagectl missing runtime probe support" >&2
    exit 1
  fi
  if grep -qE "\berro\b" /usr/local/bin/ablestack-storagectl; then
    echo "Stale /usr/local/bin/ablestack-storagectl contains known typo token erro" >&2
    exit 1
  fi
  if [ "$(grep -c "def rpcbind_active" /usr/local/bin/ablestack-storagectl)" -lt 3 ]; then
    echo "Stale /usr/local/bin/ablestack-storagectl missing standalone monitor rpcbind helper support" >&2
    exit 1
  fi
  if [ "$(grep -c "def nfs_protocol_mode_from_content" /usr/local/bin/ablestack-storagectl)" -lt 3 ]; then
    echo "Stale /usr/local/bin/ablestack-storagectl missing standalone monitor protocol-mode parser support" >&2
    exit 1
  fi
  chmod +x /usr/local/bin/ablestack-storagectl
  if [ ! -s /usr/local/bin/ablestack-storage-monitor ]; then
    echo "Missing or empty /usr/local/bin/ablestack-storage-monitor" >&2
    exit 1
  fi
  chmod +x /usr/local/bin/ablestack-storage-monitor
  if [ ! -s /usr/local/bin/ablestack-storage-boot-reconcile ]; then
    echo "Missing or empty /usr/local/bin/ablestack-storage-boot-reconcile" >&2
    exit 1
  fi
  chmod +x /usr/local/bin/ablestack-storage-boot-reconcile
  if [ ! -s /etc/systemd/system/ablestack-storage-ganesha@.service ]; then
    echo "Missing or empty /etc/systemd/system/ablestack-storage-ganesha@.service" >&2
    exit 1
  fi
  if ! grep -q "ganesha.nfsd -F" /etc/systemd/system/ablestack-storage-ganesha@.service; then
    echo "Storage Service Ganesha unit missing foreground managed runtime" >&2
    exit 1
  fi
  if ! grep -q "/run/ganesha" /etc/systemd/system/ablestack-storage-ganesha@.service; then
    echo "Storage Service Ganesha unit missing default runtime directory preparation" >&2
    exit 1
  fi
  if ! grep -q "ablestack-storage-ganesha@.*.service" /usr/local/bin/ablestack-storagectl; then
    echo "Stale /usr/local/bin/ablestack-storagectl missing Ganesha endpoint unit naming" >&2
    exit 1
  fi
  if ! grep -q '"restart", unit' /usr/local/bin/ablestack-storagectl; then
    echo "Stale /usr/local/bin/ablestack-storagectl missing systemd-owned Ganesha restart" >&2
    exit 1
  fi

  chmod +x /root/health_checks/*
  chmod -x /etc/systemd/system/* || true

  systemctl daemon-reload
  systemctl enable cloud-preinit
  systemctl enable cloud-early-config
  systemctl enable cloud-postinit
  if [ -f /etc/systemd/system/ablestack-storage-monitor.service ]; then
    chmod 0644 /etc/systemd/system/ablestack-storage-monitor.service
    systemctl unmask ablestack-storage-monitor.service || true
    systemctl enable ablestack-storage-monitor.service
  fi
  if [ -f /etc/systemd/system/ablestack-storage-reconcile.service ]; then
    chmod 0644 /etc/systemd/system/ablestack-storage-reconcile.service
    systemctl unmask ablestack-storage-reconcile.service || true
    systemctl enable ablestack-storage-reconcile.service
  fi
}

function do_signature() {
  mkdir -p /var/cache/cloud/ /usr/share/cloud/
  (cd ./cloud_scripts/; tar -cvf - * | gzip > /usr/share/cloud/cloud-scripts.tgz)
  md5sum /usr/share/cloud/cloud-scripts.tgz | awk '{print $1}' > /var/cache/cloud/cloud-scripts-signature
  echo "Cloudstack Release $CLOUDSTACK_RELEASE $(date)" > /etc/cloudstack-release
}

function configure_services() {
  mkdir -p /var/www/html
  mkdir -p /opt/cloud/bin
  mkdir -p /var/cache/cloud
  mkdir -p /usr/share/cloud
  mkdir -p /usr/local/cloud

  # Fix dnsmasq directory issue
  mkdir -p /opt/tftpboot

  # Fix haproxy directory issue
  mkdir -p /var/lib/haproxy

  install_cloud_scripts
  do_signature

  systemctl daemon-reload
  systemctl disable apt-daily.service
  systemctl disable apt-daily.timer
  systemctl disable apt-daily-upgrade.timer

  # Disable services that slow down boot and are not used anyway
  systemctl disable apache2
  systemctl disable conntrackd
  systemctl disable console-setup
  systemctl disable dnsmasq
  systemctl disable haproxy
  systemctl disable keepalived
  systemctl disable radvd
  systemctl disable frr
  systemctl disable strongswan-starter
  systemctl disable x11-common
  systemctl disable xl2tpd
  systemctl disable vgauth
  systemctl disable sshd
  systemctl disable nfs-common
  systemctl disable nfs-server
  systemctl disable nfs-ganesha || true
  systemctl mask nfs-ganesha || true
  systemctl disable portmap
  systemctl disable smbd
  systemctl disable nmbd
  systemctl disable winbind
  systemctl disable sssd
  systemctl disable rtslib-fb-targetctl || true
  systemctl disable nvmet || true

  # Disable guest services which will selectively be started based on hypervisor
  systemctl disable open-vm-tools
  systemctl disable xe-daemon
  systemctl disable hyperv-daemons.hv-fcopy-daemon.service
  systemctl disable hyperv-daemons.hv-kvp-daemon.service
  systemctl disable hyperv-daemons.hv-vss-daemon.service
  systemctl disable qemu-guest-agent

  # Disable container services
  systemctl disable containerd

  # Disable cloud init by default
cat <<EOF > /etc/cloud/cloud.cfg.d/cloudstack.cfg
datasource_list: [ ConfigDrive, CloudStack, None ]
datasource:
  CloudStack:
    max_wait: 120
    timeout: 50
EOF

  touch /etc/cloud/cloud-init.disabled
  systemctl stop cloud-init
  systemctl disable cloud-init

  configure_apache2
  configure_strongswan
  configure_issue
  configure_cacerts
}

return 2>/dev/null || configure_services
