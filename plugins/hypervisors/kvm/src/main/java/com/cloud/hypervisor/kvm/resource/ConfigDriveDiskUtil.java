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
import java.io.File;
import java.util.UUID;
import org.libvirt.Connect;
import org.libvirt.StoragePool;
import org.libvirt.LibvirtException;
import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.InternalErrorException;
import com.cloud.storage.Volume;

/** Identifies ConfigDrive media independently of the CD-ROM slot or bus. */
public final class ConfigDriveDiskUtil {
    private ConfigDriveDiskUtil() { }

    public static boolean isConfigDrivePath(String path, String vmName) {
        String expected = ConfigDrive.createConfigDrivePath(vmName);
        return path != null && (path.equals(expected) || path.endsWith("/" + expected));
    }

    static boolean isConfigDrivePoolPath(String path, String vmName, String poolXml) throws Exception {
        if (path == null || !path.endsWith("/" + vmName + ".iso")) {
            return false;
        }
        Document pool = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder()
                .parse(new InputSource(new StringReader(poolXml)));
        NodeList targets = pool.getElementsByTagName("target");
        NodeList sources = pool.getElementsByTagName("source");
        if (targets.getLength() != 1 || sources.getLength() != 1) {
            return false;
        }
        NodeList paths = ((Element) targets.item(0)).getElementsByTagName("path");
        NodeList dirs = ((Element) sources.item(0)).getElementsByTagName("dir");
        return paths.getLength() == 1 && dirs.getLength() == 1
                && new File(path).getParent().equals(paths.item(0).getTextContent())
                && ((Element) dirs.item(0)).getAttribute("path").endsWith("/" + ConfigDrive.CONFIGDRIVEDIR);
    }

    private static boolean isMountedConfigDrive(Connect conn, String path, String vmName) throws Exception {
        if (conn == null || !path.endsWith("/" + vmName + ".iso")) {
            return false;
        }
        // A UUID-shaped mount directory alone is not evidence of ConfigDrive.
        // Verify its libvirt pool source is the ConfigDrive directory as well.
        File parent = new File(path).getParentFile();
        if (parent == null) {
            return false;
        }
        try {
            UUID.fromString(parent.getName());
        } catch (IllegalArgumentException e) {
            return false;
        }
        StoragePool pool;
        try {
            pool = conn.storagePoolLookupByUUIDString(parent.getName());
        } catch (LibvirtException e) {
            return false;
        }
        try {
            return isConfigDrivePoolPath(path, vmName, pool.getXMLDesc(0));
        } finally {
            pool.free();
        }
    }

    public static DiskTO findDisk(VirtualMachineTO vm, String vmName) throws InternalErrorException {
        DiskTO found = null;
        if (vm.getDisks() == null) {
            return null;
        }
        for (DiskTO disk : vm.getDisks()) {
            if (disk != null && disk.getType() == Volume.Type.ISO && isConfigDrivePath(disk.getPath(), vmName)) {
                if (found != null || disk.getData() == null || !isConfigDrivePath(disk.getData().getPath(), vmName)) {
                    throw new InternalErrorException("Invalid or duplicate ConfigDrive disk for " + vmName);
                }
                found = disk;
            }
        }
        return found;
    }

    static Element findMedia(String domainXml, String vmName) throws InternalErrorException {
        return findMedia(domainXml, vmName, null);
    }

    static Element findMedia(String domainXml, String vmName, Connect conn) throws InternalErrorException {
        try {
            Document doc = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(domainXml)));
            NodeList disks = doc.getElementsByTagName("disk");
            Element found = null;
            for (int i = 0; i < disks.getLength(); i++) {
                Element disk = (Element) disks.item(i);
                NodeList sources = disk.getElementsByTagName("source");
                if ("cdrom".equals(disk.getAttribute("device")) && sources.getLength() == 1
                        && (isConfigDrivePath(((Element) sources.item(0)).getAttribute("file"), vmName)
                            || isMountedConfigDrive(conn, ((Element) sources.item(0)).getAttribute("file"), vmName))) {
                    if (found != null) {
                        throw new InternalErrorException("Multiple ConfigDrive media for " + vmName);
                    }
                    found = disk;
                }
            }
            return found;
        } catch (InternalErrorException e) {
            throw e;
        } catch (Exception e) {
            throw new InternalErrorException("Cannot inspect ConfigDrive media for " + vmName + ": " + e.getMessage());
        }
    }

    static String mediaXml(Element disk, boolean eject) throws InternalErrorException {
        try {
            Document doc = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder().newDocument();
            Element copy = (Element) doc.importNode(disk, true);
            doc.appendChild(copy);
            if (eject) {
                NodeList sources = copy.getElementsByTagName("source");
                while (sources.getLength() > 0) {
                    copy.removeChild(sources.item(0));
                }
            }
            return LibvirtXMLParser.getXml(doc);
        } catch (Exception e) {
            throw new InternalErrorException("Cannot prepare ConfigDrive media update: " + e.getMessage());
        }
    }
}
