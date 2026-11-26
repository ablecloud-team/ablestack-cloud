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
package com.cloud.hypervisor.kvm.storage;

import com.cloud.storage.MigrationDiskControllerPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/** Read-only capabilities and XML verification; never opens a guest disk image. */
public final class MigrationDiskControllerGuard {
    interface Runner {
        String run(List<String> args);
    }

    private final Runner runner;

    public MigrationDiskControllerGuard() {
        this(args -> Gfs2AllocationCommandRunner.run(args, 30000));
    }

    MigrationDiskControllerGuard(Runner runner) {
        this.runner = runner;
    }

    static Document xml(String value) {
        try {
            var f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(value)));
        } catch (Exception e) {
            throw new CloudRuntimeException("DISK_CONTROLLER_XML_INVALID", e);
        }
    }

    private static List<Element> descendants(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        var nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
        return out;
    }

    private static Element first(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && tag.equals(node.getNodeName())) return (Element) node;
        return null;
    }

    public String normalizeDeviceSections(String value) {
        Document document = xml(value);
        Element root = document.getDocumentElement();
        List<Element> sections = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && "devices".equals(n.getNodeName()))
                sections.add((Element) n);
        if (sections.size() < 2) return value;
        Element destination = sections.get(0);
        for (int i = 1; i < sections.size(); i++) {
            Element other = sections.get(i);
            while (other.hasChildNodes()) destination.appendChild(other.getFirstChild());
            root.removeChild(other);
        }
        try {
            var factory = javax.xml.transform.TransformerFactory.newDefaultInstance();
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var writer = new java.io.StringWriter();
            factory.newTransformer()
                    .transform(
                            new javax.xml.transform.dom.DOMSource(document),
                            new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (Exception error) {
            throw new CloudRuntimeException("DISK_CONTROLLER_XML_NORMALIZATION_FAILED", error);
        }
    }

    public Map<String, Object> capabilities(String machine, String firmware) {
        if (!"q35".equals(machine) || !List.of("bios", "efi").contains(firmware))
            throw new CloudRuntimeException("DISK_CONTROLLER_CAPABILITY_UNSUPPORTED");
        var root =
                xml(runner.run(
                                List.of(
                                        "virsh",
                                        "-c",
                                        "qemu:///system",
                                        "domcapabilities",
                                        "--virttype",
                                        "kvm",
                                        "--arch",
                                        "x86_64",
                                        "--machine",
                                        machine)))
                        .getDocumentElement();
        Element actualMachine = first(root, "machine"),
                devices = first(root, "devices"),
                os = first(root, "os");
        if (actualMachine == null
                || !actualMachine.getTextContent().contains("q35")
                || devices == null
                || os == null
                || !"yes".equals(os.getAttribute("supported")))
            throw new CloudRuntimeException("DISK_CONTROLLER_NATIVE_CAPABILITY_UNAVAILABLE");
        Element disk = first(devices, "disk");
        Set<String> buses = new LinkedHashSet<>();
        if (disk != null && "yes".equals(disk.getAttribute("supported")))
            for (Element e : descendants(disk, "enum"))
                if ("bus".equals(e.getAttribute("name")))
                    for (Element v : descendants(e, "value"))
                        if (List.of("sata", "scsi").contains(v.getTextContent()))
                            buses.add(v.getTextContent());
        Element path = first(root, "path");
        if (path == null || !Path.of(path.getTextContent()).isAbsolute())
            throw new CloudRuntimeException("DISK_CONTROLLER_EMULATOR_UNAVAILABLE");
        String models = runner.run(List.of(path.getTextContent(), "-device", "help"));
        if (!models.contains("virtio-scsi-pci")) buses.remove("scsi");
        if (!models.contains("ich9-ahci")) buses.remove("sata");
        boolean firmwareOk =
                "bios".equals(firmware)
                        && java.util.stream.Stream.of(
                                        "/usr/share/seabios/bios-256k.bin",
                                        "/usr/share/qemu-kvm/bios-256k.bin",
                                        "/usr/share/seabios/bios.bin")
                                .anyMatch(v -> Files.isReadable(Path.of(v)));
        for (Element e : descendants(os, "enum"))
            if ("firmware".equals(e.getAttribute("name")))
                for (Element v : descendants(e, "value"))
                    if (firmware.equals(v.getTextContent())) firmwareOk = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", firmwareOk && !buses.isEmpty());
        result.put("machine", actualMachine.getTextContent());
        result.put("firmware", firmware);
        result.put("buses", new ArrayList<>(buses));
        return result;
    }

    public void qualify(JsonObject plan) {
        var caps =
                capabilities(plan.get("machine").getAsString(), plan.get("firmware").getAsString());
        if (!Boolean.TRUE.equals(caps.get("supported")))
            throw new CloudRuntimeException("DISK_CONTROLLER_CAPABILITY_UNSUPPORTED");
        for (var d : plan.getAsJsonArray("disks"))
            if (!((List<?>) caps.get("buses"))
                    .contains(
                            d.getAsJsonObject().getAsJsonObject("target").get("bus").getAsString()))
                throw new CloudRuntimeException("DISK_CONTROLLER_BUS_UNSUPPORTED");
    }

    public Map<String, Object> validate(
            String planText, String bindingsText, String actualXml, boolean normalized) {
        JsonObject plan = MigrationDiskControllerPolicy.parse(planText);
        JsonArray expected = MigrationDiskControllerPolicy.bindingArray(bindingsText);
        Element root = xml(actualXml).getDocumentElement(), os = first(root, "os");
        Element type = os == null ? null : first(os, "type");
        if (type == null
                || !type.getAttribute("machine").contains("q35")
                || ("efi".equals(plan.get("firmware").getAsString())
                        != (first(os, "loader") != null)))
            throw new CloudRuntimeException("DISK_CONTROLLER_MACHINE_OR_FIRMWARE_MISMATCH");
        Element devices = first(root, "devices");
        if (devices == null) throw new CloudRuntimeException("DISK_CONTROLLER_DEVICES_MISSING");
        List<Element> disks = new ArrayList<>();
        for (Element d : descendants(devices, "disk"))
            if ("disk".equals(d.getAttribute("device"))) disks.add(d);
        if (disks.size() != expected.size() || disks.size() != plan.getAsJsonArray("disks").size())
            throw new CloudRuntimeException(
                    "DISK_CONTROLLER_DISK_COUNT_MISMATCH: actual="
                            + disks.size()
                            + ",bound="
                            + expected.size()
                            + ",planned="
                            + plan.getAsJsonArray("disks").size()
                            + ",normalized="
                            + normalized);
        List<Map<String, Object>> observations = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < expected.size(); i++) {
            JsonObject bound = expected.get(i).getAsJsonObject(),
                    want =
                            plan.getAsJsonArray("disks")
                                    .get(i)
                                    .getAsJsonObject()
                                    .getAsJsonObject("target");
            String dev = want.get("device").getAsString(), bus = want.get("bus").getAsString();
            Element disk = null;
            for (Element candidate : disks) {
                Element target = first(candidate, "target");
                if (target != null && dev.equals(target.getAttribute("dev"))) {
                    if (disk != null)
                        throw new CloudRuntimeException("DISK_CONTROLLER_DUPLICATE_DEVICE");
                    disk = candidate;
                }
            }
            if (disk == null
                    || !used.add(dev)
                    || !bus.equals(first(disk, "target").getAttribute("bus")))
                throw new CloudRuntimeException("DISK_CONTROLLER_BUS_OR_DEVICE_MISMATCH");
            Element source = first(disk, "source"), driver = first(disk, "driver");
            String storage = bound.get("storage").getAsString();
            if (source == null
                    || driver == null
                    || !bound.get("format").getAsString().equals(driver.getAttribute("type")))
                throw new CloudRuntimeException("DISK_CONTROLLER_IMAGE_FORMAT_MISMATCH");
            String observed =
                    "rbd".equals(storage)
                            ? source.getAttribute("name")
                            : source.getAttribute("file");
            if (!bound.get("path").getAsString().equals(observed)
                    || ("rbd".equals(storage) && !"rbd".equals(source.getAttribute("protocol"))))
                throw new CloudRuntimeException("DISK_CONTROLLER_VOLUME_IDENTITY_MISMATCH");
            Element boot = first(disk, "boot");
            if (i == 0 && boot != null && !"1".equals(boot.getAttribute("order")))
                throw new CloudRuntimeException("DISK_CONTROLLER_ROOT_BOOT_ORDER_MISMATCH");
            if (i == 0 && boot == null && disk != disks.get(0))
                throw new CloudRuntimeException("DISK_CONTROLLER_ROOT_ORDER_MISMATCH");
            Element address = first(disk, "address");
            if (first(disk, "readonly") != null)
                throw new CloudRuntimeException("DISK_CONTROLLER_READONLY_DISK");
            if (normalized && (address == null || !"drive".equals(address.getAttribute("type"))))
                throw new CloudRuntimeException("DISK_CONTROLLER_ADDRESS_MISSING");
            boolean controller = false;
            for (Element c : descendants(devices, "controller")) {
                if (bus.equals(c.getAttribute("type"))
                        && address != null
                        && address.getAttribute("controller").equals(c.getAttribute("index"))
                        && (!"scsi".equals(bus) || "virtio-scsi".equals(c.getAttribute("model"))))
                    controller = true;
            }
            if (normalized && !controller)
                throw new CloudRuntimeException("DISK_CONTROLLER_MODEL_MISMATCH");
            if (address != null && "drive".equals(address.getAttribute("type"))) {
                String value =
                        bus
                                + ":"
                                + address.getAttribute("controller")
                                + ":"
                                + address.getAttribute("bus")
                                + ":"
                                + address.getAttribute("target")
                                + ":"
                                + address.getAttribute("unit");
                if (!addresses.add(value)
                        || ("sata".equals(bus)
                                && Integer.parseInt(address.getAttribute("unit")) > 5))
                    throw new CloudRuntimeException("DISK_CONTROLLER_ADDRESS_MISMATCH");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bound.get("id").getAsString());
            item.put("device", dev);
            item.put("bus", bus);
            item.put("model", want.get("model").getAsString());
            item.put("path", observed);
            item.put("format", driver.getAttribute("type"));
            observations.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supported", true);
        result.put("planhash", plan.get("hash").getAsString());
        result.put("disks", observations);
        result.put("machine", type.getAttribute("machine"));
        result.put("firmware", plan.get("firmware").getAsString());
        return result;
    }
}
