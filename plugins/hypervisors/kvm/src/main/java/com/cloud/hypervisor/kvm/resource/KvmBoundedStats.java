//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.DomainBlockStats;
import org.libvirt.DomainInterfaceStats;
import com.cloud.utils.exception.CloudRuntimeException;

/** Monitoring-only RPCs run in bounded/reaped virsh processes, never abandoned Java jobs. */
public final class KvmBoundedStats {
    private KvmBoundedStats() { }
    private static final ThreadLocal<Map<List<String>, String>> CACHE = ThreadLocal.withInitial(HashMap::new);
    static void clear() { CACHE.remove(); }
    private static String run(Domain domain, String verb, String... tail) {
        try {
            List<String> args = new ArrayList<>(Arrays.asList("virsh", "-c", "qemu:///system", verb, domain.getUUIDString()));
            args.addAll(Arrays.asList(tail));
            String cached = CACHE.get().get(args);
            if (cached != null) return cached;
            String value = KvmVmOperationGuard.probe(1500, args.toArray(new String[0]));
            CACHE.get().put(args, value);
            return value;
        } catch (Exception e) { throw new CloudRuntimeException("Bounded VM observation unavailable", e); }
    }
    public static Map<String, String> parse(String text) {
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] item = line.trim().split("=", 2);
            if (item.length == 2) values.put(item[0], item[1].replace("'", ""));
            else {
                item = line.trim().split("\\s+");
                if (item.length >= 2) values.put(item[item.length - 2], item[item.length - 1]);
            }
        }
        return values;
    }
    private static long value(Map<String, String> values, String key) {
        if (!values.containsKey(key)) throw new CloudRuntimeException("Unavailable NOWAIT field: " + key);
        return Long.parseLong(values.get(key));
    }
    public static DomainInfo info(Domain domain) {
        Map<String, String> values = parse(run(domain, "domstats", "--nowait", "--raw", "--state", "--cpu-total", "--balloon", "--vcpu"));
        DomainInfo info = new DomainInfo();
        info.state = value(values, "state.state") == 1 ? DomainInfo.DomainState.VIR_DOMAIN_RUNNING : DomainInfo.DomainState.VIR_DOMAIN_NOSTATE;
        info.cpuTime = value(values, "cpu.time"); info.nrVirtCpu = (int) value(values, "vcpu.current");
        info.memory = value(values, "balloon.current"); info.maxMem = value(values, "balloon.maximum");
        return info;
    }
    public static long freeMemory(Domain domain) {
        Map<String, String> values = parse(run(domain, "domstats", "--nowait", "--raw", "--balloon"));
        return values.containsKey("balloon.unused") ? value(values, "balloon.unused") : -1;
    }
    public static DomainBlockStats block(Domain domain, String disk) {
        Map<String, String> values = parse(run(domain, "domblkstat", disk));
        DomainBlockStats result = new DomainBlockStats();
        result.rd_req = value(values, "rd_req"); result.wr_req = value(values, "wr_req");
        result.rd_bytes = value(values, "rd_bytes"); result.wr_bytes = value(values, "wr_bytes");
        return result;
    }
    public static DomainInterfaceStats network(Domain domain, String iface) {
        Map<String, String> values = parse(run(domain, "domifstat", iface));
        DomainInterfaceStats result = new DomainInterfaceStats();
        result.rx_bytes = value(values, "rx_bytes"); result.tx_bytes = value(values, "tx_bytes");
        return result;
    }
    public static LibvirtDomainXMLParser xml(Domain domain) {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        parser.parseDomainXML(run(domain, "dumpxml")); return parser;
    }
}
