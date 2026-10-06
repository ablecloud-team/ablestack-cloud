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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.TreeSet;
import java.util.Arrays;
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
    private final Map<String, Map<String, Object>> devices = new HashMap<>();
    private final Map<String, Set<String>> mounts = new HashMap<>();
    private final Map<String, String> numbers = new HashMap<>();
    private static final List<String> PRIORITY = Arrays.asList("available", "partitioned", "filesystem", "host-volume", "mounted", "vm-connected");
    private static final Set<String> PARTITION_FILESYSTEMS = new HashSet<>(Arrays.asList(
            "ext2", "ext3", "ext4", "xfs", "btrfs", "vfat", "ntfs", "ntfs3", "exfat", "f2fs", "udf"));
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
        devices.clear();
        mounts.clear();
        numbers.clear();
        try {
            readBlocks(new JSONObject(run("/usr/bin/lsblk", "--json", "--paths", "--output",
                    "NAME,KNAME,TYPE,FSTYPE,MOUNTPOINTS,WWN,MAJ:MIN,SIZE,SERIAL,MODEL,PTTYPE")).getJSONArray("blockdevices"), null);
            readMounts(new JSONObject(run("/usr/bin/findmnt", "--json", "--output",
                    "SOURCE,MAJ:MIN,TARGET")).getJSONArray("filesystems"));
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

    private void mark(String name, String reason) {
        if (PRIORITY.indexOf(reason) > PRIORITY.indexOf(reasons.getOrDefault(name, "available"))) {
            reasons.put(name, reason);
        }
    }

    private void addMount(String name, String target) {
        if (target != null && !target.isEmpty()) {
            mounts.computeIfAbsent(name, key -> new TreeSet<>()).add(target);
            mark(name, "[SWAP]".equals(target) ? "host-volume" : "mounted");
        }
    }

    private void readMounts(JSONArray filesystems) {
        for (int i = 0; i < filesystems.length(); i++) {
            JSONObject fs = filesystems.getJSONObject(i);
            String number = fs.getString("maj:min");
            String target = fs.getString("target");
            String name = numbers.get(number);
            if (name != null) { addMount(name, target); }
            JSONArray children = fs.optJSONArray("children");
            if (children != null) { readMounts(children); }
        }
    }

    private void readBlocks(JSONArray blocks, String parent) throws Exception {
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            // Missing fields/tool support must never turn an unverified medium into a candidate.
            for (String field : Arrays.asList("name", "kname", "type", "fstype", "mountpoints",
                    "wwn", "maj:min", "size", "serial", "model", "pttype")) {
                if (!block.has(field)) { throw new IllegalArgumentException("Incomplete block inventory"); }
            }
            String name = Paths.get(block.getString("kname")).getFileName().toString();
            link(name, parent);
            numbers.put(block.getString("maj:min"), name);
            String wwn = block.optString("wwn", "");
            if (!wwn.isEmpty()) { link(name, wwns.putIfAbsent(wwn, name)); }
            String type = block.getString("type");
            String fs = block.optString("fstype", "");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("path", block.getString("name"));
            detail.put("type", type);
            detail.put("filesystem", fs);
            for (String field : Arrays.asList("wwn", "serial", "size", "model", "pttype")) {
                detail.put(field, block.optString(field, ""));
            }
            devices.put(name, detail);
            JSONArray points = block.getJSONArray("mountpoints");
            for (int j = 0; j < points.length(); j++) { addMount(name, points.optString(j, "")); }
            if ("part".equals(type) || !block.optString("pttype", "").isEmpty()) {
                mark(name, "partitioned");
            }
            if (!"disk".equals(type) && !"mpath".equals(type) && !"part".equals(type)) {
                mark(name, "host-volume");
            }
            if (!fs.isEmpty() && !"mpath_member".equals(fs)) {
                if (fs.equals("swap") || fs.equals("LVM2_member") || fs.toLowerCase().contains("raid")
                        || fs.equals("crypto_LUKS")) {
                    mark(name, "host-volume");
                } else if (!"part".equals(type) || !PARTITION_FILESYSTEMS.contains(fs)) {
                    mark(name, "filesystem");
                }
            }
            JSONArray children = block.optJSONArray("children");
            if (children != null) { readBlocks(children, name); }
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
                    mark(resolve(dev), "vm-connected");
                }
            }
        }
        NodeList devices = doc.getElementsByTagName("hostdev");
        for (int i = 0; i < devices.getLength(); i++) {
            Element device = (Element) devices.item(i);
            if ("scsi".equals(device.getAttribute("type"))) {
                mark(scsiSource(device), "vm-connected");
            }
        }
    }

    private Set<String> related(String name) {
        Set<String> seen = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(name);
        while (!pending.isEmpty()) {
            String next = pending.remove();
            if (seen.add(next)) { pending.addAll(links.getOrDefault(next, Collections.emptySet())); }
        }
        return seen;
    }

    String status(String name) {
        if (!verified || !links.containsKey(name)) { return "unknown"; }
        String reason = "available";
        for (String next : related(name)) {
            String candidate = reasons.getOrDefault(next, "available");
            if (PRIORITY.indexOf(candidate) > PRIORITY.indexOf(reason)) { reason = candidate; }
        }
        return reason;
    }

    public Map<String, Map<String, Object>> details(List<String> names) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String name : names) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("verified", false);
            detail.put("status", "unknown");
            try {
                String resolved = resolve(name.split(" ")[0]);
                String usage = status(resolved);
                if (!"unknown".equals(usage)) {
                    detail.putAll(devices.get(resolved));
                    detail.put("verified", true);
                    detail.put("status", usage);
                    Set<String> mountpoints = new TreeSet<>();
                    List<Map<String, Object>> partitions = new ArrayList<>();
                    boolean partitioned = false;
                    for (String alias : related(resolved)) {
                        Map<String, Object> info = devices.get(alias);
                        if (info == null) { continue; }
                        mountpoints.addAll(mounts.getOrDefault(alias, Collections.emptySet()));
                        if ("part".equals(info.get("type"))) {
                            Map<String, Object> part = new LinkedHashMap<>(info);
                            part.put("mountpoints", new ArrayList<>(mounts.getOrDefault(alias, Collections.emptySet())));
                            partitions.add(part);
                            partitioned = true;
                        }
                        if (!"".equals(info.get("pttype"))) { partitioned = true; }
                        for (String field : Arrays.asList("wwn", "serial", "model")) {
                            if ("".equals(detail.get(field)) && !"".equals(info.get(field))) {
                                detail.put(field, info.get(field));
                            }
                        }
                    }
                    detail.put("haspartitions", partitioned);
                    detail.put("partitions", partitions);
                    detail.put("mountpoints", new ArrayList<>(mountpoints));
                }
            } catch (Exception e) {
                detail.clear();
                detail.put("verified", false);
                detail.put("status", "unknown");
            }
            result.put(name, detail);
        }
        return result;
    }

    public String attachmentDenial(String text, boolean scsi, boolean acknowledgePartitionRisk) {
        String usage = attachmentStatus(text, scsi);
        if ("partitioned".equals(usage)) {
            return acknowledgePartitionRisk ? null : "기존 파티션과 데이터에 미칠 영향을 확인한 후 할당하세요.";
        }
        switch (usage) {
            case "available": return null;
            case "mounted": return "매체 또는 하위 파티션이 호스트에 마운트되어 있어 할당할 수 없습니다.";
            case "vm-connected": return "이 매체는 다른 가상머신에서 사용 중이므로 할당할 수 없습니다.";
            case "host-volume": return "이 매체는 LVM·RAID·스왑 등 호스트 스토리지에서 사용하므로 할당할 수 없습니다.";
            case "filesystem": return "이 매체의 파일시스템 사용 상태가 할당 조건에 맞지 않습니다.";
            default: return "매체의 사용 상태를 확인할 수 없어 할당할 수 없습니다. 호스트 장치 정보를 업데이트한 후 다시 확인하세요.";
        }
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
