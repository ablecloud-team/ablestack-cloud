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
package com.cloud.hypervisor.kvm.resource;

import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Read-only inspection of generated or libvirt-normalized domain XML. */
public final class PciTopologyInspector {
    private PciTopologyInspector() { }

    public static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception error) {
            throw new IllegalArgumentException("PCI_TOPOLOGY_XML_INVALID", error);
        }
    }

    public static boolean q35(Document document) {
        var types = document.getElementsByTagName("type");
        for (int i = 0; i < types.getLength(); i++) {
            Element type = (Element) types.item(i);
            if (!"os".equals(type.getParentNode().getNodeName())) continue;
            return isQ35Machine(type.getAttribute("machine"));
        }
        return false;
    }

    static boolean isQ35Machine(String machine) {
        return machine != null && ("q35".equals(machine) || machine.startsWith("pc-q35-"));
    }

    public static int rootPorts(Document document) {
        int count = 0;
        var controllers = document.getElementsByTagName("controller");
        for (int i = 0; i < controllers.getLength(); i++) {
            if ("pcie-root-port".equals(((Element) controllers.item(i)).getAttribute("model"))) count++;
        }
        return count;
    }

    public static int freeHotplugPorts(Document document) {
        Set<Integer> ports = new HashSet<>();
        Set<Integer> occupied = new HashSet<>();
        var controllers = document.getElementsByTagName("controller");
        for (int i = 0; i < controllers.getLength(); i++) {
            Element controller = (Element) controllers.item(i);
            if (!"pcie-root-port".equals(controller.getAttribute("model"))) continue;
            Element target = child(controller, "target");
            if (target == null || !"off".equals(target.getAttribute("hotplug"))) {
                ports.add(number(controller.getAttribute("index")));
            }
        }
        var addresses = document.getElementsByTagName("address");
        for (int i = 0; i < addresses.getLength(); i++) {
            Element address = (Element) addresses.item(i);
            if ("pci".equals(address.getAttribute("type"))) occupied.add(number(address.getAttribute("bus")));
        }
        ports.removeAll(occupied);
        return ports.size();
    }

    public static void validateAddresses(Document document) {
        Set<String> seen = new HashSet<>();
        Map<String, Element> zeroFunctions = new HashMap<>();
        Set<String> multifunctionSlots = new HashSet<>();
        var addresses = document.getElementsByTagName("address");
        for (int i = 0; i < addresses.getLength(); i++) {
            Element address = (Element) addresses.item(i);
            if (!"pci".equals(address.getAttribute("type"))) continue;
            int domain = number(address.getAttribute("domain"));
            int bus = number(address.getAttribute("bus"));
            int slot = number(address.getAttribute("slot"));
            int function = number(address.getAttribute("function"));
            if (domain > 65535 || bus > 255 || slot > 31 || function > 7) {
                throw new IllegalArgumentException("PCI_TOPOLOGY_ADDRESS_RANGE: invalid PCI address");
            }
            String group = domain + ":" + bus + ":" + slot;
            if (!seen.add(group + ":" + function)) throw new IllegalArgumentException("PCI_TOPOLOGY_ADDRESS_DUPLICATE");
            if (function == 0) zeroFunctions.put(group, address);
            else multifunctionSlots.add(group);
        }
        for (String group : multifunctionSlots) {
            Element zero = zeroFunctions.get(group);
            // q35's LPC function 0 is an implicit chipset device, absent from XML.
            if (q35(document) && "0:0:31".equals(group)) continue;
            if (zero == null || !"on".equals(zero.getAttribute("multifunction"))) {
                throw new IllegalArgumentException("PCI_TOPOLOGY_MULTIFUNCTION: function zero must enable multifunction");
            }
        }
    }

    /** Conservative controller/endpoint demand, never one port per SCSI disk. */
    public static int bootPortDemand(Document document) {
        Element devices = (Element) document.getElementsByTagName("devices").item(0);
        if (devices == null) throw new IllegalArgumentException("PCI_TOPOLOGY_DEVICES_MISSING");
        int demand = 0;
        boolean channel = false, serialController = false, balloon = false;
        for (Node node = devices.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            String tag = element.getTagName();
            if ("channel".equals(tag)) { channel = true; continue; }
            if ("memballoon".equals(tag)) balloon = true;
            if ("controller".equals(tag)) {
                String model = element.getAttribute("model");
                if ("pcie-root".equals(model) || "pcie-root-port".equals(model)) continue;
                if ("virtio-serial".equals(element.getAttribute("type"))) serialController = true;
            }
            boolean consumes = Set.of("interface", "hostdev", "video", "rng", "sound", "memballoon", "vsock", "controller").contains(tag);
            if ("disk".equals(tag)) {
                Element target = child(element, "target");
                consumes = target != null && "virtio".equals(target.getAttribute("bus"));
            }
            if (!consumes) continue;
            Element address = child(element, "address");
            // An explicit root-complex/legacy placement does not consume a root-port.
            if (address != null && "pci".equals(address.getAttribute("type")) && number(address.getAttribute("bus")) == 0) continue;
            if ("memballoon".equals(tag) && "none".equals(element.getAttribute("model"))) continue;
            demand++;
        }
        if (channel && !serialController) demand++;
        if (!balloon) demand++; // libvirt's default virtio balloon
        return demand;
    }

    private static Element child(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && tag.equals(node.getNodeName())) return (Element) node;
        }
        return null;
    }

    private static int number(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            int result = Integer.decode(value);
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("PCI_TOPOLOGY_ADDRESS_INVALID", error);
        }
    }
}
