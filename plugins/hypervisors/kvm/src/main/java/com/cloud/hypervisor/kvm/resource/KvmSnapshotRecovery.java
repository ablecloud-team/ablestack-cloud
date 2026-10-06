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

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.agent.api.DeleteVMSnapshotAnswer;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.vm.snapshot.VMSnapshot;

/** Explicit snapshot recovery; all observations and mutations use the common VM flock. */
public final class KvmSnapshotRecovery {
    private KvmSnapshotRecovery() { }

    interface NativeAccess {
        String virsh(String... args) throws Exception;
        String imageInfo(String path) throws Exception;
        void deleteImageSnapshot(String path, String name) throws Exception;
        void deleteArtifact(String path) throws Exception;
    }

    static final class LocalAccess implements NativeAccess {
        @Override public String virsh(String... args) throws Exception {
            List<String> command = new ArrayList<>(List.of("virsh", "-c", "qemu:///system"));
            command.addAll(Arrays.asList(args));
            return KvmVmOperationGuard.probe(60000, command.toArray(new String[0]));
        }
        @Override public String imageInfo(String path) throws Exception {
            return KvmVmOperationGuard.probe(10000, "qemu-img", "info", "--output=json", "--backing-chain", path);
        }
        @Override public void deleteImageSnapshot(String path, String name) throws Exception {
            KvmVmOperationGuard.probe(60000, "qemu-img", "snapshot", "-d", name, path);
        }
        @Override public void deleteArtifact(String path) throws Exception {
            if (Files.notExists(Path.of(path))) return;
            if (Files.isSymbolicLink(Path.of(path)) || !Files.isRegularFile(Path.of(path))) throw new IOException("Snapshot artifact type is unknown");
            imageInfo(path); // No -U: a live writer or failed query must keep the artifact.
            Files.delete(Path.of(path));
        }
    }

    static final class Inventory {
        final Map<String, JsonObject> images = new TreeMap<>();
        final Map<String, String> nodes = new TreeMap<>();
        final Set<String> referenced = new HashSet<>();
        final Set<String> registered = new TreeSet<>();
        final Map<String, Set<String>> snapshots = new TreeMap<>();
        String state;
        String fingerprint() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("state", state); value.put("registered", registered);
            Map<String, Object> identity = new TreeMap<>();
            for (Map.Entry<String, JsonObject> image : images.entrySet()) {
                Map<String, Object> disk = new TreeMap<>();
                disk.put("virtualSize", image.getValue().get("virtual-size"));
                disk.put("snapshots", image.getValue().get("snapshots"));
                disk.put("backing", image.getValue().get("full-backing-filename"));
                identity.put(image.getKey(), disk);
            }
            value.put("images", identity);
            value.put("paths", images.keySet()); value.put("references", new TreeSet<>(referenced)); value.put("snapshots", snapshots);
            return new Gson().toJson(value);
        }
    }

    public static DeleteVMSnapshotAnswer execute(DeleteVMSnapshotCommand command, Map<Long, String> disks, Map<Long, String> artifacts) {
        return execute(command, disks, artifacts, new LocalAccess(), null);
    }

    static DeleteVMSnapshotAnswer execute(DeleteVMSnapshotCommand command, Map<Long, String> disks, Map<Long, String> artifacts,
            NativeAccess access, Path root) {
        KvmVmOperationGuard guard = null;
        boolean paused = false;
        boolean changed = false;
        try {
            java.util.concurrent.Callable<String> observer = () -> inspect(command, disks, artifacts, access).fingerprint();
            guard = root == null ? KvmVmOperationGuard.beginSnapshotRecovery(command.getVmUuid(), observer)
                    : new KvmVmOperationGuard(root, command.getVmUuid(), "recover-vm-snapshot", false, 0, observer);
            Inventory before = inspect(command, disks, artifacts, access);
            String target = command.getTarget().getSnapshotName();
            boolean internal = before.snapshots.values().stream().anyMatch(names -> names.contains(target));
            if (internal || before.registered.contains(target)) {
                if ("running".equals(before.state)) { access.virsh("suspend", command.getVmUuid()); paused = true; }
                changed = true;
                if (before.registered.contains(target)) {
                    access.virsh("snapshot-delete", command.getVmUuid(), "--snapshotname", target);
                }
                // A partially created internal snapshot can exist without libvirt metadata.
                Inventory partial = inspect(command, disks, artifacts, access);
                for (Map.Entry<String, Set<String>> image : partial.snapshots.entrySet()) {
                    if (!image.getValue().contains(target)) continue;
                    if ("shut off".equals(partial.state)) access.deleteImageSnapshot(image.getKey(), target);
                    else qmp(access, command.getVmUuid(), "blockdev-snapshot-delete-internal-sync",
                            Map.of("device", partial.nodes.get(image.getKey()), "name", target));
                }
            }
            for (String path : artifacts.values()) {
                if (before.referenced.contains(path)) throw new IOException("Snapshot artifact is still part of an active disk chain");
                changed = true;
                access.deleteArtifact(path);
            }
            Inventory after = inspect(command, disks, artifacts, access);
            if (after.registered.contains(target) || after.snapshots.values().stream().anyMatch(names -> names.contains(target))) {
                throw new IOException("Snapshot recovery could not confirm object removal");
            }
            if (paused) {
                access.virsh("resume", command.getVmUuid());
                if (!"running".equals(access.virsh("domstate", command.getVmUuid()).trim())) {
                    throw new IOException("VM resume result is unknown after snapshot cleanup");
                }
                paused = false;
            }
            return new DeleteVMSnapshotAnswer(command, command.getVolumeTOs());
        } catch (Exception e) {
            if (guard != null && changed) guard.uncertain();
            return new DeleteVMSnapshotAnswer(command, false, "Snapshot recovery blocked or incomplete: " + e.getMessage());
        } finally {
            if (paused) {
                try { access.virsh("resume", command.getVmUuid()); }
                catch (Exception e) { if (guard != null) guard.uncertain(); }
            }
            if (guard != null) guard.close();
        }
    }

    static Inventory inspect(DeleteVMSnapshotCommand command, Map<Long, String> disks, Map<Long, String> artifacts, NativeAccess access) throws Exception {
        if (command.getVmUuid() == null || command.getKnownSnapshotNames() == null || disks.isEmpty()) throw new IOException("Snapshot recovery identity is incomplete");
        Inventory result = new Inventory();
        String uuid = command.getVmUuid();
        String target = command.getTarget().getSnapshotName();
        result.state = access.virsh("domstate", uuid).trim();
        if (!Set.of("running", "paused", "shut off").contains(result.state)) throw new IOException("VM state does not permit snapshot recovery");
        String job = access.virsh("domjobinfo", uuid);
        if (!job.matches("(?s).*Job type:\\s+None\\s*.*")) throw new IOException("Native VM job is active or unknown");
        for (String name : access.virsh("snapshot-list", uuid, "--name").split("\\R")) {
            if (!name.trim().isEmpty()) result.registered.add(name.trim());
        }
        Set<String> expected = new TreeSet<>(command.getKnownSnapshotNames());
        Set<String> registered = new TreeSet<>(result.registered); registered.remove(target);
        if (!registered.equals(expected)) throw new IOException("Cloud and libvirt snapshot inventories disagree");
        if (!result.registered.isEmpty()) {
            String current = access.virsh("snapshot-current", uuid, "--name").trim();
            if (!Objects.equals(current, command.getExpectedCurrent())) {
                if (!current.equals(target) || !Objects.equals(xmlText(access.virsh("snapshot-dumpxml", uuid, target), "parent", "name"), command.getExpectedCurrent())) {
                    throw new IOException("Cloud and provider current snapshot disagree");
                }
            }
        } else if (command.getExpectedCurrent() != null) throw new IOException("Provider current snapshot is missing");
        String xml = access.virsh("dumpxml", uuid);
        Set<String> paths = new TreeSet<>(disks.values());
        String nvram = xmlText(xml, "os", "nvram");
        if (nvram != null && !nvram.isEmpty()) paths.add(nvram);
        if ("shut off".equals(result.state)) {
            for (String path : paths) {
                JsonElement image = JsonParser.parseString(access.imageInfo(path));
                JsonArray chain = image.isJsonArray() ? image.getAsJsonArray() : null;
                JsonObject root = chain == null ? image.getAsJsonObject() : chain.get(0).getAsJsonObject();
                addImage(result, root, path, null, command);
                if (chain != null) for (JsonElement backing : chain) result.referenced.add(backing.getAsJsonObject().get("filename").getAsString());
            }
        } else {
            if (qmp(access, uuid, "query-block-jobs", null).getAsJsonArray().size() != 0
                    || qmp(access, uuid, "query-jobs", null).getAsJsonArray().size() != 0) throw new IOException("Native block or QEMU job is active");
            for (JsonElement block : qmp(access, uuid, "query-block", null).getAsJsonArray()) {
                JsonObject entry = block.getAsJsonObject();
                if (!entry.has("inserted")) continue;
                JsonObject inserted = entry.getAsJsonObject("inserted");
                if (!inserted.has("image")) throw new IOException("QEMU image information is missing");
                JsonObject image = inserted.getAsJsonObject("image");
                String path = image.get("filename").getAsString();
                references(image, result.referenced);
                if (paths.contains(path)) {
                    if (inserted.has("ro") && inserted.get("ro").getAsBoolean()) throw new IOException("Expected disk is read-only");
                    addImage(result, image, path, inserted.get("node-name").getAsString(), command);
                } else if (!inserted.has("ro") || !inserted.get("ro").getAsBoolean()) {
                    throw new IOException("Active VM disk paths differ from Cloud recovery inventory");
                }
            }
        }
        if (!result.images.keySet().equals(paths)) throw new IOException("Not all VM disks were inspected");
        if (!artifacts.isEmpty()) {
            Set<String> domains = new TreeSet<>();
            for (String domain : access.virsh("list", "--all", "--uuid").split("\\R")) if (!domain.trim().isEmpty()) domains.add(domain.trim());
            if (!domains.contains(uuid)) throw new IOException("Provider VM inventory is incomplete");
            for (String other : domains) {
                if (other.isEmpty() || other.equals(uuid)) continue;
                String otherState = access.virsh("domstate", other).trim();
                if ("shut off".equals(otherState)) {
                    DocumentBuilderFactory factory = secureXmlFactory();
                    org.w3c.dom.Document domain = factory.newDocumentBuilder().parse(new InputSource(new StringReader(access.virsh("dumpxml", other))));
                    org.w3c.dom.NodeList sources = domain.getElementsByTagName("source");
                    for (int i = 0; i < sources.getLength(); i++) {
                        String path = ((org.w3c.dom.Element) sources.item(i)).getAttribute("file");
                        if (path.isEmpty()) continue;
                        JsonElement info = JsonParser.parseString(access.imageInfo(path));
                        JsonArray chain = info.isJsonArray() ? info.getAsJsonArray() : null;
                        if (chain == null) references(info.getAsJsonObject(), result.referenced);
                        else for (JsonElement image : chain) references(image.getAsJsonObject(), result.referenced);
                    }
                } else if ("running".equals(otherState) || "paused".equals(otherState)) {
                    for (JsonElement block : qmp(access, other, "query-block", null).getAsJsonArray()) {
                        JsonObject entry = block.getAsJsonObject();
                        if (!entry.has("inserted")) continue;
                        JsonObject inserted = entry.getAsJsonObject("inserted");
                        if (!inserted.has("image")) throw new IOException("Other VM disk references are unknown");
                        references(inserted.getAsJsonObject("image"), result.referenced);
                    }
                } else throw new IOException("Other VM state does not permit artifact reference verification");
            }
        }
        for (String path : artifacts.values()) if (result.referenced.contains(path)) throw new IOException("Snapshot artifact is referenced by a VM disk or backing chain");
        return result;
    }

    private static void references(JsonObject image, Set<String> paths) {
        paths.add(image.get("filename").getAsString());
        if (image.has("backing-image")) references(image.getAsJsonObject("backing-image"), paths);
        if (image.has("full-backing-filename")) paths.add(image.get("full-backing-filename").getAsString());
    }

    private static void addImage(Inventory inventory, JsonObject image, String path, String node, DeleteVMSnapshotCommand command) throws IOException {
        if (!"qcow2".equals(image.get("format").getAsString()) || !path.equals(image.get("filename").getAsString())
                || !image.has("virtual-size")) throw new IOException("Disk image identity or format is unknown");
        if (command.getTarget().getType() == VMSnapshot.Type.DiskAndMemory && image.has("backing-image")) {
            throw new IOException("External backing chains require their provider recovery path");
        }
        Set<String> names = new TreeSet<>();
        if (image.has("snapshots")) for (JsonElement snapshot : image.getAsJsonArray("snapshots")) names.add(snapshot.getAsJsonObject().get("name").getAsString());
        Set<String> remainder = new TreeSet<>(names); remainder.remove(command.getTarget().getSnapshotName());
        if (!remainder.equals(new TreeSet<>(command.getKnownSnapshotNames()))) throw new IOException("Disk snapshot table differs from Cloud inventory");
        inventory.images.put(path, image); inventory.nodes.put(path, node); inventory.snapshots.put(path, names);
        inventory.referenced.add(path);
    }

    private static JsonElement qmp(NativeAccess access, String uuid, String execute, Map<String, String> arguments) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>(); request.put("execute", execute);
        if (arguments != null) request.put("arguments", arguments);
        JsonObject response = JsonParser.parseString(access.virsh("qemu-monitor-command", uuid, new Gson().toJson(request))).getAsJsonObject();
        if (response.has("error") || !response.has("return")) throw new IOException("QEMU query or operation failed: " + execute);
        return response.get("return");
    }

    private static String xmlText(String xml, String parent, String child) throws Exception {
        DocumentBuilderFactory factory = secureXmlFactory();
        org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        org.w3c.dom.NodeList parents = document.getElementsByTagName(parent);
        if (parents.getLength() == 0) return null;
        org.w3c.dom.NodeList children = ((org.w3c.dom.Element) parents.item(0)).getElementsByTagName(child);
        return children.getLength() == 0 ? null : children.item(0).getTextContent().trim();
    }

    private static DocumentBuilderFactory secureXmlFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory;
    }
}
