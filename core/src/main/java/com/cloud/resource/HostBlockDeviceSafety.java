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
package com.cloud.resource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

/** Read-only, fail-closed inspection shared by inventory and the final attach guard. */
public class HostBlockDeviceSafety {
    private final Map<String, Set<String>> links = new HashMap<>();
    private final Map<String, String> reasons = new HashMap<>();
    private final Map<String, String> wwns = new HashMap<>();
    private boolean verified;

    protected String run(String command, String... args) throws Exception {
        Script script = new Script(command, 10000);
        script.add(args);
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        if (script.execute(parser) != null || parser.getLines() == null) {
            throw new IllegalStateException("Cannot inspect block device usage");
        }
        return parser.getLines();
    }

    protected String resolve(String path) throws Exception {
        Path real = Paths.get(path).toRealPath();
        String name = real.getFileName().toString();
        if (name.matches("sg[0-9]+")) {
            return scsiBlock(Paths.get("/sys/class/scsi_generic", name, "device/block"));
        }
        if (!Files.exists(Paths.get("/sys/class/block", name))) {
            throw new IllegalArgumentException("Not a block device");
        }
        return name;
    }

    private String scsiBlock(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.findFirst().orElseThrow(() -> new IllegalArgumentException("No block device")).getFileName().toString();
        }
    }

    protected String resolveScsi(String address) throws Exception {
        if (!address.matches("[0-9]+:[0-9]+:[0-9]+:[0-9]+")) {
            throw new IllegalArgumentException("Invalid SCSI address");
        }
        return scsiBlock(Paths.get("/sys/class/scsi_device", address, "device/block"));
    }

    public HostBlockDeviceSafety inspect() {
        verified = false;
        links.clear();
        reasons.clear();
        wwns.clear();
        try {
            readBlocks(new JSONObject(run("/usr/bin/lsblk", "--json", "--paths", "--output",
                    "NAME,KNAME,TYPE,FSTYPE,MOUNTPOINT,WWN")).getJSONArray("blockdevices"), null);
            // Include both live XML and persistent configuration (including stopped VMs).
            for (String domain : run("virsh", "list", "--all", "--name").split("\\R")) {
                if (!domain.trim().isEmpty()) {
                    readDomain(run("virsh", "dumpxml", domain.trim()));
                }
            }
            for (String domain : run("virsh", "list", "--all", "--persistent", "--name").split("\\R")) {
                if (!domain.trim().isEmpty()) {
                    readDomain(run("virsh", "dumpxml", domain.trim(), "--inactive"));
                }
            }
            verified = !links.isEmpty();
        } catch (Exception e) {
            // Missing tooling, unreadable XML, or an inventory race never means "unused".
            verified = false;
        }
        return this;
    }

    private void link(String a, String b) {
        links.computeIfAbsent(a, key -> new HashSet<>());
        if (b != null) {
            links.computeIfAbsent(b, key -> new HashSet<>()).add(a);
            links.get(a).add(b);
        }
    }

    private void readBlocks(JSONArray blocks, String parent) throws Exception {
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            String name = Paths.get(block.getString("kname")).getFileName().toString();
            link(name, parent);
            String wwn = block.optString("wwn", "");
            if (!wwn.isEmpty()) {
                link(name, wwns.putIfAbsent(wwn, name));
            }
            String type = block.getString("type");
            if (!block.optString("mountpoint", "").isEmpty()) {
                reasons.put(name, "mounted");
            } else if (!block.optString("fstype", "").isEmpty() && !"mpath_member".equals(block.optString("fstype"))) {
                reasons.put(name, "filesystem");
            } else if ("part".equals(type)) {
                reasons.put(name, "partitioned");
            } else if (!"disk".equals(type) && !"mpath".equals(type)) {
                reasons.put(name, "host-volume");
            }
            JSONArray children = block.optJSONArray("children");
            if (children != null) {
                readBlocks(children, name);
            }
        }
    }

    private Document xml(String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
    }

    private String scsiSource(Element device) throws Exception {
        Element source = (Element) device.getElementsByTagName("source").item(0);
        Element adapter = (Element) source.getElementsByTagName("adapter").item(0);
        Element address = (Element) source.getElementsByTagName("address").item(0);
        String host = adapter.getAttribute("name");
        if (!host.matches("scsi_host[0-9]+")) {
            throw new IllegalArgumentException("Unverified SCSI adapter");
        }
        return resolveScsi(host.substring(9) + ":" + address.getAttribute("bus") + ":"
                + address.getAttribute("target") + ":" + address.getAttribute("unit"));
    }

    private void readDomain(String text) throws Exception {
        Document doc = xml(text);
        NodeList disks = doc.getElementsByTagName("disk");
        for (int i = 0; i < disks.getLength(); i++) {
            Element disk = (Element) disks.item(i);
            NodeList sources = disk.getElementsByTagName("source");
            if ("block".equals(disk.getAttribute("type")) && sources.getLength() > 0) {
                String dev = ((Element) sources.item(0)).getAttribute("dev");
                if (!dev.isEmpty()) {
                    reasons.put(resolve(dev), "vm-connected");
                }
            }
        }
        NodeList devices = doc.getElementsByTagName("hostdev");
        for (int i = 0; i < devices.getLength(); i++) {
            Element device = (Element) devices.item(i);
            if ("scsi".equals(device.getAttribute("type"))) {
                reasons.put(scsiSource(device), "vm-connected");
            }
        }
    }

    String status(String name) {
        if (!verified || !links.containsKey(name)) {
            return "unknown";
        }
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(name);
        String reason = "available";
        while (!pending.isEmpty()) {
            String next = pending.remove();
            if (seen.add(next)) {
                if ("vm-connected".equals(reasons.get(next))) {
                    return "vm-connected";
                }
                if (reasons.containsKey(next)) {
                    reason = reasons.get(next);
                }
                pending.addAll(links.getOrDefault(next, java.util.Collections.emptySet()));
            }
        }
        return reason;
    }

    public Map<String, String> statuses(List<String> names) {
        Map<String, String> result = new HashMap<>();
        for (String name : names) {
            try {
                result.put(name, status(resolve(name.split(" ")[0])));
            } catch (Exception e) {
                result.put(name, "unknown");
            }
        }
        return result;
    }

    public String attachmentStatus(String text, boolean scsi) {
        try {
            Element device = xml(text).getDocumentElement();
            if (scsi) {
                if (!"hostdev".equals(device.getTagName()) || !"scsi".equals(device.getAttribute("type"))) {
                    return "unknown";
                }
                return status(scsiSource(device));
            }
            if (!"disk".equals(device.getTagName()) || !"block".equals(device.getAttribute("type"))) {
                return "unknown";
            }
            Element source = (Element) device.getElementsByTagName("source").item(0);
            return status(resolve(source.getAttribute("dev")));
        } catch (Exception e) {
            return "unknown";
        }
    }
}
