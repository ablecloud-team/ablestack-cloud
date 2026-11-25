#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

"""Verify a NetBackup Client against local interfaces and current Mold inventory."""

import ipaddress
import json
import socket
import subprocess
import sys


def resolve_addresses(name: str) -> set[str]:
    try:
        return {str(ipaddress.ip_address(name))}
    except ValueError:
        try:
            return {str(ipaddress.ip_address(item[4][0].split("%", 1)[0]))
                    for item in socket.getaddrinfo(name, None, type=socket.SOCK_STREAM)}
        except (OSError, ValueError):
            return set()


def local_host_addresses() -> set[str]:
    try:
        result = subprocess.run(["ip", "-j", "address", "show"], check=True, capture_output=True, text=True, timeout=10)
        addresses = {str(ipaddress.ip_address(address["local"])) for interface in json.loads(result.stdout)
                     for address in interface.get("addr_info", []) if address.get("local")}
        return {address for address in addresses if not ipaddress.ip_address(address).is_loopback
                and not ipaddress.ip_address(address).is_unspecified}
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError) as error:
        raise ValueError(f"Cannot inspect local KVM interfaces: {type(error).__name__}; "
                         "run this tool on the KVM host with iproute installed") from error


def client_addresses(client: str) -> set[str]:
    addresses = resolve_addresses(client)
    if not addresses.intersection(local_host_addresses()):
        raise ValueError("NetBackup Client does not resolve to this KVM host's local interfaces")
    return addresses


def inventory_page(document: dict, addresses: set[str]) -> tuple[int, list[dict]]:
    hosts = document["listhostsresponse"].get("host", [])
    if isinstance(hosts, dict):
        hosts = [hosts]
    if not isinstance(hosts, list):
        raise ValueError("Invalid Mold Host inventory")
    matches = [host for host in hosts if str(host.get("hypervisor", "")).lower() == "kvm"
               and resolve_addresses(str(host.get("ipaddress") or "")).intersection(addresses)]
    for host in matches:
        for key in ("id", "name", "zoneid", "clusterid"):
            if not isinstance(host.get(key), str) or not host[key] or any(char in host[key] for char in "\t\r\n"):
                raise ValueError("Matched Mold Host is missing its UUID, name, zone ID or cluster ID")
    return len(hosts), matches


def main():
    try:
        if len(sys.argv) != 3:
            raise ValueError("Specify client or inventory and its argument")
        if sys.argv[1] == "client":
            print(json.dumps(sorted(client_addresses(sys.argv[2]))))
        elif sys.argv[1] == "inventory":
            count, matches = inventory_page(json.load(sys.stdin), set(json.loads(sys.argv[2])))
            host = matches[0] if matches else {}
            print(count, len(matches), host.get("id", "-"), host.get("name", "-"), sep="\t")
        else:
            raise ValueError("Unsupported Host identity operation")
    except (ValueError, KeyError, TypeError, AttributeError) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
