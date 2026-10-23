// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.utils.security.ParserUtils;
import com.cloud.vm.KvmQ35PcieTopology;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Bootstrap old VM records once; subsequent starts use the policy returned in StartAnswer and saved in VM details. */
public final class LibvirtQ35PcieTopology {
    public static final String UNKNOWN = "Q35_PCIE_TOPOLOGY_UNKNOWN";
    private static final Path LOG_ROOT = Path.of("/var/log/libvirt/qemu");
    private static final long MAX_SCAN_CHARS = 64L * 1024 * 1024;
    private static final Pattern PORT = Pattern.compile("\"driver\"\\s*:\\s*\"pcie-root-port\"|^-device\\s+pcie-root-port[, ]");

    private LibvirtQ35PcieTopology() { }

    public static boolean isQ35(String machine) {
        return machine != null && ("q35".equals(machine) || machine.startsWith("pc-q35-"));
    }

    public static void prepare(VirtualMachineTO spec, LibvirtVMDef vm, Connect connection) {
        prepare(spec, vm, connection, LOG_ROOT);
    }

    static void prepare(VirtualMachineTO spec, LibvirtVMDef vm, Connect connection, Path logRoot) {
        if (spec.getType() != VirtualMachine.Type.User || !vm.getDevices().isQ35()) { return; }
        Map<String, String> details = spec.getDetails();
        KvmQ35PcieTopology policy;
        if (details != null && details.containsKey(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY)) {
            policy = KvmQ35PcieTopology.resolve(details);
        } else {
            policy = find(connection, spec.getName(), spec.getUuid(), logRoot);
            if (policy == null) {
                throw new CloudRuntimeException(UNKNOWN + ": previous Q35 configuration is unavailable for " + spec.getName()
                        + ". Check the last Host or set kvm.q35.pcie.topology to legacy/reserved32 after verifying its previous configuration.");
            }
        }
        Map<String, String> updated = details == null ? new HashMap<>() : new HashMap<>(details);
        updated.put(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, policy.value());
        spec.setDetails(updated);
        vm.getDevices().setReserveQ35PciePorts(policy == KvmQ35PcieTopology.RESERVED_32);
    }

    public static KvmQ35PcieTopology find(Connect connection, String name, String uuid, Path logRoot) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+") || ".".equals(name) || "..".equals(name) || uuid == null) {
            throw new CloudRuntimeException("Invalid VM identity for PCIe topology lookup");
        }
        KvmQ35PcieTopology observed = null;
        if (Files.isDirectory(logRoot)) {
            try (var paths = Files.list(logRoot)) {
                List<Path> logs = paths.filter(path -> path.getFileName().toString().equals(name + ".log")
                        || path.getFileName().toString().matches(Pattern.quote(name + ".log.") + "[0-9]+(?:\\.gz)?"))
                        .filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString).reversed())
                        .limit(12).collect(java.util.stream.Collectors.toList());
                long scanned = 0;
                for (Path path : logs) {
                    try (var input = Files.newInputStream(path);
                            var decoded = path.toString().endsWith(".gz") ? new GZIPInputStream(input) : input;
                            var reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
                        StringBuilder block = new StringBuilder();
                        for (String line; (line = reader.readLine()) != null;) {
                            scanned += line.length() + 1;
                            if (scanned > MAX_SCAN_CHARS) { throw new IOException("PCIe history scan limit exceeded"); }
                            if (line.contains("starting up libvirt version:") && block.length() > 0) {
                                KvmQ35PcieTopology found = fromLogBlock(block.toString(), uuid);
                                // An unmarked VM that used legacy hardware before the upgrade must not be
                                // classified as a new 32-port VM merely because it was later started by the changed Agent.
                                if (found == KvmQ35PcieTopology.LEGACY) { return found; }
                                if (found != null) { observed = found; }
                                block.setLength(0);
                            }
                            // QEMU launch command lines are small; avoid retaining a long guest/error log in memory.
                            if (block.length() < 256 * 1024) { block.append(line).append('\n'); }
                        }
                        KvmQ35PcieTopology found = fromLogBlock(block.toString(), uuid);
                        if (found == KvmQ35PcieTopology.LEGACY) { return found; }
                        if (found != null) { observed = found; }
                    }
                }
            } catch (IOException e) {
                throw new CloudRuntimeException(UNKNOWN + ": unable to read previous PCIe configuration for " + name, e);
            }
        }
        if (observed != null) { return observed; }
        if (connection != null) {
            Domain domain = null;
            try {
                domain = connection.domainLookupByName(name);
                return fromXml(domain.getXMLDesc(0), uuid);
            } catch (org.libvirt.LibvirtException e) {
                return null;
            } finally {
                if (domain != null) { try { domain.free(); } catch (org.libvirt.LibvirtException ignored) { } }
            }
        }
        return null;
    }

    static KvmQ35PcieTopology fromLogBlock(String block, String uuid) {
        boolean launch = false;
        boolean identity = false;
        boolean q35 = false;
        boolean complete = false;
        int ports = 0;
        for (String line : block.split("\\R")) {
            String value = line.stripLeading();
            if (value.contains("starting up libvirt version:")) { launch = true; }
            if (value.startsWith("-uuid ")) {
                String[] arguments = value.split("\\s+");
                identity = arguments.length > 1 && arguments[1].replace("'", "").replace("\"", "").equals(uuid);
            }
            if (value.startsWith("-machine ")) { q35 = isQ35(value.substring(9).split("[,\\s]")[0]); }
            if (value.startsWith("-device ") && PORT.matcher(value).find()) { ports++; }
            if (value.startsWith("-msg ")) { complete = true; }
        }
        return launch && identity && q35 && complete ? classify(ports) : null;
    }

    static KvmQ35PcieTopology fromXml(String xml, String uuid) {
        try {
            var builder = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder();
            var document = builder.parse(new InputSource(new StringReader(xml)));
            if (!uuid.equals(document.getElementsByTagName("uuid").item(0).getTextContent())) { return null; }
            var types = document.getElementsByTagName("type");
            boolean q35 = false;
            for (int i = 0; i < types.getLength(); i++) { q35 |= isQ35(((Element) types.item(i)).getAttribute("machine")); }
            if (!q35) { return null; }
            int ports = 0;
            var controllers = document.getElementsByTagName("controller");
            for (int i = 0; i < controllers.getLength(); i++) {
                if ("pcie-root-port".equals(((Element) controllers.item(i)).getAttribute("model"))) { ports++; }
            }
            return classify(ports);
        } catch (Exception e) {
            throw new CloudRuntimeException(UNKNOWN + ": previous domain XML cannot be read", e);
        }
    }

    private static KvmQ35PcieTopology classify(int ports) {
        if (ports < 32) { return KvmQ35PcieTopology.LEGACY; }
        if (ports == 32) { return KvmQ35PcieTopology.RESERVED_32; }
        throw new CloudRuntimeException(UNKNOWN + ": unsupported previous PCIe port count " + ports);
    }
}

