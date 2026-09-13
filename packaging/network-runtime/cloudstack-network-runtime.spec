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
Name: cloudstack-network-runtime
Version: 1.0.0
Release: 2%{?dist}
Summary: Network Namespace runtime for CloudStack hosts
License: ASL 2.0
BuildArch: noarch
Source0: network-namespace-wrapper.sh
Source1: cloudstack-network-runtime-check
Requires: bash
Requires: coreutils
Requires: findutils
Requires: gawk
Requires: grep
Requires: sed
Requires: procps-ng
Requires: iproute
Requires: iptables
Requires: iputils
Requires: dnsmasq
Requires: haproxy
Requires: httpd
Requires: radvd
Requires: python3
Requires: util-linux
Requires: openssh-server

%description
Installs the Network Namespace wrapper and all DHCP, DNS, load balancing,
metadata and IPv6 runtime dependencies. Does not start host-level services,
create networks or modify existing VM/network configuration.

%prep
%build
bash -n %{SOURCE0}
bash -n %{SOURCE1}

%install
install -D -m 0755 %{SOURCE0} %{buildroot}/etc/cloudstack/extensions/network-namespace/network-namespace-wrapper.sh
install -D -m 0755 %{SOURCE1} %{buildroot}%{_sbindir}/cloudstack-network-runtime-check

%files
%dir /etc/cloudstack/extensions/network-namespace
/etc/cloudstack/extensions/network-namespace/network-namespace-wrapper.sh
%{_sbindir}/cloudstack-network-runtime-check
