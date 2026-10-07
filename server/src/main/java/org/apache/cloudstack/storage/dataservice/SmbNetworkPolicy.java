// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.common.net.InetAddresses;
import com.cloud.exception.InvalidParameterValueException;

/** Literal-only, canonical, source-address allow-list independent of account ACLs. */
public final class SmbNetworkPolicy {
    private SmbNetworkPolicy() { }
    public static final class Source {
        public final StorageServiceInstance.PrincipalType type;
        public final String principal;
        private Source(StorageServiceInstance.PrincipalType type,String principal) { this.type=type;this.principal=principal; }
    }
    public static List<Source> normalize(String requestedType,String values,boolean ipv6Supported) {
        if (values==null || values.trim().isEmpty()) throw new InvalidParameterValueException("At least one literal IP or CIDR is required");
        StorageServiceInstance.PrincipalType type=null;
        if (requestedType!=null && !requestedType.trim().isEmpty()) {
            try { type=StorageServiceInstance.PrincipalType.valueOf(requestedType.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new InvalidParameterValueException("Network source type must be CIDR or IP_ADDRESS"); }
            if (type!=StorageServiceInstance.PrincipalType.CIDR && type!=StorageServiceInstance.PrincipalType.IP_ADDRESS) throw new InvalidParameterValueException("Network source type must be CIDR or IP_ADDRESS");
        }
        Map<String,Source> unique=new LinkedHashMap<>();
        for (String token:values.split(",",-1)) {
            String value=token.trim();
            if (value.isEmpty() || !value.matches("[0-9A-Fa-f:./]+")) throw new InvalidParameterValueException("Only literal IP addresses and CIDRs are supported");
            String[] parts=value.split("/",-1);
            StorageServiceInstance.PrincipalType effective=type==null ? (parts.length==2 ? StorageServiceInstance.PrincipalType.CIDR : StorageServiceInstance.PrincipalType.IP_ADDRESS) : type;
            if (parts.length!=(effective==StorageServiceInstance.PrincipalType.CIDR ? 2 : 1)) throw new InvalidParameterValueException("Source does not match its IP_ADDRESS or CIDR type");
            InetAddress address;
            try { address=InetAddresses.forString(parts[0]); }
            catch (IllegalArgumentException invalid) { throw new InvalidParameterValueException("Invalid literal source address"); }
            if (address instanceof Inet6Address && !ipv6Supported) throw new InvalidParameterValueException("The service has no verified IPv6 endpoint capability");
            String normalized=InetAddresses.toAddrString(address);
            if (effective==StorageServiceInstance.PrincipalType.CIDR) {
                int prefix;
                try { if (!parts[1].matches("[0-9]{1,3}")) throw new NumberFormatException();prefix=Integer.parseInt(parts[1]); }
                catch (NumberFormatException invalid) { throw new InvalidParameterValueException("Invalid CIDR prefix"); }
                byte[] bytes=address.getAddress();
                if (prefix<0 || prefix>bytes.length*8) throw new InvalidParameterValueException("CIDR prefix is outside the address family range");
                for (int index=0;index<bytes.length;index++) {
                    int bits=Math.max(0,Math.min(8,prefix-index*8));
                    bytes[index]=(byte)(bytes[index] & (bits==0 ? 0 : 0xff << (8-bits)));
                }
                try { normalized=InetAddresses.toAddrString(InetAddress.getByAddress(bytes))+"/"+prefix; }
                catch (UnknownHostException impossible) { throw new IllegalStateException(impossible); }
            }
            unique.put(effective.name()+":"+normalized,new Source(effective,normalized));
        }
        return new ArrayList<>(unique.values());
    }
}
