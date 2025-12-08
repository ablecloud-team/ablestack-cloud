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

package com.cloud.resource;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.storage.command.browser.ListRbdObjectsAnswer;

import org.apache.cloudstack.storage.command.browser.ListDataStoreObjectsAnswer;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.cloud.agent.api.ListHostDeviceAnswer;
import com.cloud.agent.api.CreateVhbaDeviceCommand;
import com.cloud.agent.api.DeleteVhbaDeviceAnswer;
import com.cloud.agent.api.DeleteVhbaDeviceCommand;

import com.cloud.agent.api.ListHostHbaDeviceAnswer;
import com.cloud.agent.api.ListHostLunDeviceAnswer;
import com.cloud.agent.api.ListHostScsiDeviceAnswer;
import com.cloud.agent.api.ListHostUsbDeviceAnswer;
import com.cloud.agent.api.ListVhbaDevicesCommand;

import com.cloud.agent.api.UpdateHostHbaDeviceAnswer;
import com.cloud.agent.api.UpdateHostLunDeviceAnswer;
import com.cloud.agent.api.UpdateHostLunDeviceCommand;
import com.cloud.agent.api.UpdateHostScsiDeviceAnswer;
import com.cloud.agent.api.UpdateHostScsiDeviceCommand;
import com.cloud.agent.api.UpdateHostUsbDeviceAnswer;
import com.cloud.agent.api.UpdateHostVhbaDeviceAnswer;

import org.json.JSONArray;
import org.json.JSONObject;


public abstract class ServerResourceBase implements ServerResource {
    protected Logger logger = LogManager.getLogger(getClass());
    protected String name;
    private ArrayList<String> warnings = new ArrayList<String>();
    private ArrayList<String> errors = new ArrayList<String>();
    protected NetworkInterface publicNic;
    protected NetworkInterface privateNic;
    protected NetworkInterface storageNic;
    protected NetworkInterface storageNic2;
    protected IAgentControl agentControl;
    protected static final String DEFAULT_LOCAL_STORAGE_PATH = "/var/lib/libvirt/images/";

    @Override
    public String getName() {
        return name;
    }

    protected String findScript(String script) {
        return Script.findScript(getDefaultScriptsDir(), script);
    }

    protected abstract String getDefaultScriptsDir();

    @Override
    public boolean configure(final String name, Map<String, Object> params) throws ConfigurationException {
        this.name = name;

        defineResourceNetworkInterfaces(params);

        if (privateNic == null) {
            tryToAutoDiscoverResourcePrivateNetworkInterface();
        }

        String infos[] = NetUtils.getNetworkParams(privateNic);
        if (infos == null) {
            logger.warn("Incorrect details for private Nic during initialization of ServerResourceBase");
            return false;
        }
        params.put("host.ip", infos[0]);
        params.put("host.mac.address", infos[1]);

        return true;
    }

    protected void defineResourceNetworkInterfaces(Map<String, Object> params) {
        String privateNic = (String) params.get("private.network.device");
        privateNic = privateNic == null ? "xenbr0" : privateNic;

        String publicNic = (String) params.get("public.network.device");
        publicNic = publicNic == null ? "xenbr1" : publicNic;

        String storageNic = (String) params.get("storage.network.device");
        String storageNic2 = (String) params.get("storage.network.device.2");

        this.privateNic = NetUtils.getNetworkInterface(privateNic);
        this.publicNic = NetUtils.getNetworkInterface(publicNic);
        this.storageNic = NetUtils.getNetworkInterface(storageNic);
        this.storageNic2 = NetUtils.getNetworkInterface(storageNic2);
    }

    protected void tryToAutoDiscoverResourcePrivateNetworkInterface() throws ConfigurationException {
        logger.info("Trying to autodiscover this resource's private network interface.");

        List<NetworkInterface> nics;
        try {
            nics = Collections.list(NetworkInterface.getNetworkInterfaces());
            if (CollectionUtils.isEmpty(nics)) {
                throw new ConfigurationException("This resource has no NICs. Unable to configure it.");
            }
        } catch (SocketException e) {
            throw new ConfigurationException(String.format("Could not retrieve the environment NICs due to [%s].", e.getMessage()));
        }

        logger.debug(String.format("Searching the private NIC along the environment NICs [%s].", Arrays.toString(nics.toArray())));

        for (NetworkInterface nic : nics) {
            if (isValidNicToUseAsPrivateNic(nic))  {
                logger.info(String.format("Using NIC [%s] as private NIC.", nic));
                privateNic = nic;
                return;
            }
        }

        throw new ConfigurationException("It was not possible to define a private NIC for this resource.");
    }

    protected boolean isValidNicToUseAsPrivateNic(NetworkInterface nic) {
        String nicName = nic.getName();

        logger.debug(String.format("Verifying if NIC [%s] can be used as private NIC.", nic));

        String[] nicNameStartsToAvoid = {"vnif", "vnbr", "peth", "vif", "virbr"};
        if (nic.isVirtual() || StringUtils.startsWithAny(nicName, nicNameStartsToAvoid) || nicName.contains(":")) {
            logger.debug(String.format("Not using NIC [%s] because it is either virtual, starts with %s, or contains \":\"" +
             " in its name.", Arrays.toString(nicNameStartsToAvoid), nic));
            return false;
        }

        String[] info = NetUtils.getNicParams(nicName);
        if (info == null || info[0] == null) {
            logger.debug(String.format("Not using NIC [%s] because it does not have a valid IP to use as the private IP.", nic));
            return false;
        }

        return true;
    }

    protected Answer listHostDevices() {
        List<String> hostDevicesText = new ArrayList<>();
        List<String> hostDevicesNames = new ArrayList<>();
        Script listCommand = new Script("lspci");
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = listCommand.execute(parser);
        if (result == null && parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.contains("System peripheral") || line.contains("PIC")
                        || line.contains("Performance counters")) {
                    continue;
                }
                String deviceName = line;
                String deviceText = "";

                Pattern pattern = Pattern.compile(
                        "^((?:[0-9a-fA-F]{4}:)?[0-9a-fA-F]{2}:[0-9a-fA-F]{2}\\.[0-7])\\s+([^:]+):\\s*(.+)$");
                Matcher matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    String address = matcher.group(1).trim();
                    String type = matcher.group(2).trim();
                    String detail = matcher.group(3).trim();
                    deviceName = address + " " + type;
                    deviceText = detail;
                }

                hostDevicesNames.add(deviceName);
                hostDevicesText.add(deviceText);
            }
        }
        return new ListHostDeviceAnswer(true, hostDevicesNames, hostDevicesText);
    }

    protected Answer listHostUsbDevices(Command command) {
        List<String> hostDevicesText = new ArrayList<>();
        List<String> hostDevicesNames = new ArrayList<>();
        Script listCommand = new Script("lsusb");
        OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
        String result = listCommand.execute(parser);
        if (result == null && parser.getLines() != null) {
            String[] lines = parser.getLines().split("\\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("Bus ")) {
                    String[] colonParts = line.split(":", 2);
                    if (colonParts.length == 2) {
                        String busDevicePart = colonParts[0].trim();
                        String detailPart = colonParts[1].trim();

                        String deviceName = busDevicePart.replaceFirst("^Bus\\s+", "");
                        hostDevicesNames.add(deviceName);
                        hostDevicesText.add(detailPart);
                    } else {
                        hostDevicesNames.add(line);
                        hostDevicesText.add("");
                    }
                } else {

                    String[] parts = line.split("\\s+", 2);
                    if (parts.length >= 2) {
                        hostDevicesNames.add(parts[0].trim());
                        hostDevicesText.add(parts[1].trim());
                    }
                }
            }
        }
        return new ListHostUsbDeviceAnswer(true, hostDevicesNames, hostDevicesText);
    }

    public Answer listHostLunDevices(Command command) {
        try {
            ListHostLunDeviceAnswer fast = listHostLunDevicesFast();
            if (fast != null && fast.getResult()) {
                return fast;
            }

            List<String> hostDevicesNames = new ArrayList<>();
            List<String> hostDevicesText = new ArrayList<>();
            List<Boolean> hasPartitions = new ArrayList<>();
            List<String> scsiAddresses = new ArrayList<>();

            // lsblk --json 실행
            Script cmd = new Script("/usr/bin/lsblk");
            cmd.add("--json", "--paths", "--output", "NAME,TYPE,SIZE,MOUNTPOINT");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                return new ListHostLunDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);
            }

            JSONObject json = new JSONObject(parser.getLines());
            JSONArray blockdevices = json.getJSONArray("blockdevices");


            Map<String, String> scsiAddressCache = getScsiAddressesBatch();

            for (int i = 0; i < blockdevices.length(); i++) {
                JSONObject device = blockdevices.getJSONObject(i);
                addLunDeviceRecursiveOptimized(device, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses, scsiAddressCache);
            }


            Set<String> addedDevices = new HashSet<>();
            collectMultipathDevicesUnified(hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses, scsiAddressCache, addedDevices);

            return new ListHostLunDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);

        } catch (Exception e) {
            return new ListHostLunDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private ListHostLunDeviceAnswer listHostLunDevicesFast() {
        try {
            List<String> names = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<Boolean> hasPartitions = new ArrayList<>();
            List<String> scsiAddresses = new ArrayList<>();

            Map<String, String> scsiAddressCache = getScsiAddressesFromSysfs();
            Map<java.nio.file.Path, String> realToById = buildByIdReverseMap();

            File sysBlock = new File("/sys/block");
            File[] entries = sysBlock.listFiles();
            if (entries == null) {
                return null;
            }


            collectAllLunDevicesUnified(names, texts, hasPartitions, scsiAddresses, scsiAddressCache, realToById);

            return new ListHostLunDeviceAnswer(true, names, texts, hasPartitions, scsiAddresses);
        } catch (Exception e) {
            return null;
        }
    }

    private void collectAllLunDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitions,
                                           List<String> scsiAddresses, Map<String, String> scsiAddressCache,
                                           Map<Path, String> realToById) {
        File sysBlock = new File("/sys/block");
        File[] entries = sysBlock.listFiles();
        if (entries == null) return;

        Set<String> addedDevices = new HashSet<>();

        for (File entry : entries) {
            String bname = entry.getName();
            if (!(bname.startsWith("sd") || bname.startsWith("vd") || bname.startsWith("xvd") ||
                  bname.startsWith("nvme") || bname.startsWith("dm-"))) {
                continue;
            }

            String devPath = "/dev/" + bname;
            String preferred = resolveById(realToById, devPath);

            String deviceKey = preferred.startsWith("/dev/disk/by-id/") ?
                preferred.substring(preferred.lastIndexOf('/') + 1) : devPath;

            if (!addedDevices.contains(deviceKey)) {
                addDeviceToList(devPath, preferred, entry, names, texts, hasPartitions, scsiAddresses, scsiAddressCache, bname);
                addedDevices.add(deviceKey);
            }
        }

        collectMultipathDevicesUnified(names, texts, hasPartitions, scsiAddresses, scsiAddressCache, addedDevices);
    }

    private void addDeviceToList(String devPath, String preferred, File entry,
                               List<String> names, List<String> texts, List<Boolean> hasPartitionsList,
                               List<String> scsiAddresses, Map<String, String> scsiAddressCache, String bname) {
        StringBuilder info = new StringBuilder();
        info.append("TYPE: ");
        if (bname.startsWith("dm-")) info.append("multipath");
        else if (bname.startsWith("nvme")) info.append("nvme");
        else info.append("disk");

        boolean deviceHasPartitions = sysHasPartitions(entry);

        String size = sysGetSizeHuman(entry);
        if (size != null) {
            info.append(" SIZE: ").append(size);
        }

        info.append(" HAS_PARTITIONS: ").append(deviceHasPartitions ? "true" : "false");

        String scsiAddr = scsiAddressCache.getOrDefault(devPath, scsiAddressCache.get(preferred));
        if (scsiAddr != null) {
            info.append(" SCSI_ADDRESS: ").append(scsiAddr);
        }

        if (!preferred.equals(devPath) && preferred.startsWith("/dev/disk/by-id/")) {
            String byIdName = preferred.substring(preferred.lastIndexOf('/') + 1);
            String displayName = devPath + " (" + byIdName + ")";

            names.add(displayName);
            texts.add(info.toString());
            hasPartitionsList.add(deviceHasPartitions);
            scsiAddresses.add(scsiAddr != null ? scsiAddr : "");
        } else {
        }
    }

    private void collectMultipathDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitionsList,
                                               List<String> scsiAddresses, Map<String, String> scsiAddressCache, Set<String> addedDevices) {
        try {
            Script cmd = new Script("/usr/sbin/multipath");
            cmd.add("-l");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null || parser.getLines() == null || parser.getLines().trim().isEmpty()) return;

            String[] lines = parser.getLines().split("\n");
            for (String line : lines) {
                if (line.contains("mpath")) {
                    String dmDevice = extractDmDeviceFromLine(line);
                    if (dmDevice != null) {
                        String devicePath = "/dev/mapper/" + dmDevice;
                        String preferredName = resolveDevicePathToById(devicePath);

                        String deviceKey = preferredName.startsWith("/dev/disk/by-id/") ?
                            preferredName.substring(preferredName.lastIndexOf('/') + 1) : devicePath;

                        if (!addedDevices.contains(deviceKey)) {
                            addMultipathDeviceToList(devicePath, preferredName, names, texts, hasPartitionsList, scsiAddresses, scsiAddressCache);
                            addedDevices.add(deviceKey);
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void addMultipathDeviceToList(String devicePath, String preferredName,
                                        List<String> names, List<String> texts, List<Boolean> hasPartitionsList,
                                        List<String> scsiAddresses, Map<String, String> scsiAddressCache) {
        boolean hasPartition = hasPartitionRecursiveForDevice(devicePath);

        StringBuilder deviceInfo = new StringBuilder();
        deviceInfo.append("TYPE: multipath");

        String size = getDeviceSize(devicePath);
        if (size != null) {
            deviceInfo.append(" SIZE: ").append(size);
        }

        deviceInfo.append(" HAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

        String scsiAddress = scsiAddressCache.get(devicePath);
        if (scsiAddress != null) {
            deviceInfo.append(" SCSI_ADDRESS: ").append(scsiAddress);
        }

        deviceInfo.append(" MULTIPATH_DEVICE: true");

        if (!preferredName.equals(devicePath) && preferredName.startsWith("/dev/disk/by-id/")) {
            String byIdName = preferredName.substring(preferredName.lastIndexOf('/') + 1);
            String displayName = devicePath + " (" + byIdName + ")";

            names.add(displayName);
            texts.add(deviceInfo.toString());
            hasPartitionsList.add(hasPartition);
            scsiAddresses.add(scsiAddress != null ? scsiAddress : "");
        } else {
        }
    }

    private String extractDmDeviceFromLine(String line) {
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            if (part.startsWith("dm-")) {
                return part;
            }
        }

        Pattern pattern = Pattern.compile("dm-\\d+");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }
    private Map<Path, String> buildByIdReverseMap() {
        Map<Path, String> map = new HashMap<>();
        Path byIdDir = Path.of("/dev/disk/by-id");
        try {
            if (!Files.isDirectory(byIdDir)) return map;
            try (Stream<Path> s = Files.list(byIdDir)) {
                s.filter(Files::isSymbolicLink).forEach(p -> {
                    Path rp = safeRealPath(p);
                    if (rp != null) {
                        String byIdPath = byIdDir.resolve(p.getFileName()).toString();
                        map.put(rp, byIdPath);
                        if (byIdPath.contains("HFS3T8G3H2X069N")) {
                        }
                    }
                });
            }
        } catch (Exception ignore) {}
        return map;
    }

    private String resolveById(Map<Path, String> realToById, String devicePath) {
        try {
            Path real = Path.of(devicePath).toRealPath();
            String byId = realToById.get(real);
            if (byId != null) {
                if (byId.contains("-part")) {
                    byId = byId.replaceAll("-part\\d+$", "");
                }
                if (byId.contains("HFS3T8G3H2X069N")) {
                }
                return byId;
            }
            return devicePath;
        } catch (Exception e) {
            return devicePath;
        }
    }

    private boolean sysHasPartitions(File sysBlockEntry) {
        try {
            String name = sysBlockEntry.getName();
            File[] children = sysBlockEntry.listFiles();
            if (children == null) return false;

            for (File child : children) {
                String childName = child.getName();

                if (childName.startsWith(name) && childName.length() > name.length()) {
                    String suffix = childName.substring(name.length());
                    if (suffix.matches("\\d+")) {
                        File partFile = new File(child, "partition");
                        if (partFile.exists()) {
                            return true;
                        }
                    }
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String sysGetSizeHuman(File sysBlockEntry) {
        try {
            File sizeFile = new File(sysBlockEntry, "size");
            if (!sizeFile.exists()) return null;
            String content = new String(Files.readAllBytes(sizeFile.toPath())).trim();
            if (content.isEmpty()) return null;
            long sectors = Long.parseLong(content);
            double gib = (sectors * 512.0) / 1024 / 1024 / 1024;
            return String.format(Locale.ROOT, "%.2fG", gib);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Map<String, String> getScsiAddressesFromSysfs() {
        Map<String, String> map = new HashMap<>();
        try {
            File sysBlock = new File("/sys/block");
            File[] entries = sysBlock.listFiles();
            if (entries == null) return map;
            for (File e : entries) {
                String name = e.getName();
                if (!(name.startsWith("sd") || name.startsWith("vd") || name.startsWith("xvd"))) continue;
                Path devLink = Path.of(e.getAbsolutePath(), "device");
                try {
                    Path real = Files.readSymbolicLink(devLink);
                    Path resolved = devLink.getParent().resolve(real).normalize();
                    String scsi = resolved.getFileName().toString();
                    map.put("/dev/" + name, scsi);
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return map;
    }

    private void addLunDeviceRecursiveOptimized(JSONObject device, List<String> names, List<String> texts,
            List<Boolean> hasPartitions, List<String> scsiAddresses,
            Map<String, String> scsiAddressCache) {
        String name = device.getString("name");
        String type = device.getString("type");
        String size = device.optString("size", "");

        if (!"part".equals(type)
            && !name.contains("/dev/mapper/ceph--")
        ) {
            boolean hasPartition = hasPartitionRecursive(device);

            String preferredName = resolveDevicePathToById(name);

            if (!preferredName.equals(name) && preferredName.startsWith("/dev/disk/by-id/")) {
                String byIdName = preferredName.substring(preferredName.lastIndexOf('/') + 1);
                String displayName = name + " (" + byIdName + ")";

                names.add(displayName);
                StringBuilder deviceInfo = new StringBuilder();
                deviceInfo.append("TYPE: ").append(type);
                if (!size.isEmpty()) {
                    deviceInfo.append(" SIZE: ").append(size);
                }
                deviceInfo.append(" HAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

                String scsiAddress = scsiAddressCache.get(name);
                if (scsiAddress != null) {
                    deviceInfo.append(" SCSI_ADDRESS: ").append(scsiAddress);
                }

                texts.add(deviceInfo.toString());
                hasPartitions.add(hasPartition);
                scsiAddresses.add(scsiAddress != null ? scsiAddress : "");
            } else {
            }
        }

        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                addLunDeviceRecursiveOptimized(children.getJSONObject(i), names, texts, hasPartitions, scsiAddresses, scsiAddressCache);
            }
        }
    }

    private String resolveDevicePathToById(String devicePath) {
        try {
            if (devicePath == null || !devicePath.startsWith("/dev/")) {
                return devicePath;
            }

            Path device = Path.of(devicePath).toRealPath();
            Path byIdDir = Path.of("/dev/disk/by-id");

            if (!Files.isDirectory(byIdDir)) {
                return devicePath;
            }

            try (Stream<Path> stream = Files.list(byIdDir)) {
                Optional<String> firstMatch = stream
                    .filter(Files::isSymbolicLink)
                    .map(p -> new AbstractMap.SimpleEntry<>(p, safeRealPath(p)))
                    .filter(e -> e.getValue() != null && e.getValue().equals(device))
                    .map(e -> byIdDir.resolve(e.getKey().getFileName()).toString())
                    .findFirst();

                String byIdPath = firstMatch.orElse(devicePath);

                if (byIdPath.contains("-part")) {
                    byIdPath = byIdPath.replaceAll("-part\\d+$", "");
                }

                return byIdPath;
            }
        } catch (Exception ignore) {
            return devicePath;
        }
    }

    private Path safeRealPath(Path link) {
        try {
            Path target = Files.readSymbolicLink(link);
            Path resolved = link.getParent().resolve(target).normalize();
            return resolved.toRealPath();
        } catch (Exception e) {
            return null;
        }
    }

    private void addLunDeviceRecursive(JSONObject device, List<String> names, List<String> texts, List<Boolean> hasPartitions, List<String> scsiAddresses) {
        String name = device.getString("name");
        String type = device.getString("type");
        String size = device.optString("size", "");

        if (!"part".equals(type)
            && !name.contains("/dev/mapper/ceph--")
        ) {
            boolean hasPartition = hasPartitionRecursive(device);


            names.add(name);
            StringBuilder deviceInfo = new StringBuilder();
            deviceInfo.append("TYPE: ").append(type);
            if (!size.isEmpty()) {
                deviceInfo.append(" SIZE: ").append(size);
            }
            deviceInfo.append(" HAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

            String scsiAddress = getScsiAddress(name);
            if (scsiAddress != null) {
                deviceInfo.append(" SCSI_ADDRESS: ").append(scsiAddress);
            }

            texts.add(deviceInfo.toString());
            hasPartitions.add(hasPartition);
            scsiAddresses.add(scsiAddress != null ? scsiAddress : "");

        }
        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                addLunDeviceRecursive(children.getJSONObject(i), names, texts, hasPartitions, scsiAddresses);
            }
        }
    }
    protected Map<String, Map<String, List<String>>> getLunDeviceUuidMapping() {
        Map<String, Map<String, List<String>>> dmMap = new LinkedHashMap<>();

        try {
            Set<String> dmDevices = getDmDevices();

            dmMap = mapLinksByDm(dmDevices);
        } catch (Exception e) {
        }
        return dmMap;
    }

    private Set<String> getDmDevices() {
        Set<String> dmDevices = new HashSet<>();

        try {
            Script cmd = new Script("/usr/bin/multipath");
            cmd.add("-l");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null) {
                String[] lines = parser.getLines().split("\n");
                for (String line : lines) {
                    if (line.contains("mpath")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            String dmDevice = parts[2];
                            if (dmDevice.startsWith("dm-")) {
                                dmDevices.add(dmDevice);
                            }
                        }
                    }
                }
            } else {
            }

        } catch (Exception e) {
        }

        return dmDevices;
    }

    private Map<String, Map<String, List<String>>> mapLinksByDm(Set<String> dmDevices) {
        Map<String, Map<String, List<String>>> dmMap = new LinkedHashMap<>();
        String byIdPath = "/dev/disk/by-id";

        try {
            File byIdDir = new File(byIdPath);
            if (!byIdDir.exists() || !byIdDir.isDirectory()) {
                return dmMap;
            }

            File[] entries = byIdDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry.isFile() && isSymbolicLink(entry)) {
                        String entryName = entry.getName();
                        String target = getSymbolicLinkTarget(entry);

                        if (target != null) {
                            String targetBasename = new File(target).getName();

                            if (dmDevices.contains(targetBasename)) {
                                dmMap.computeIfAbsent(targetBasename, k -> {
                                    Map<String, List<String>> categories = new HashMap<>();
                                    categories.put("multipath_id", new ArrayList<>());
                                    categories.put("multipath_name", new ArrayList<>());
                                    categories.put("scsi", new ArrayList<>());
                                    categories.put("wwn", new ArrayList<>());
                                    return categories;
                                });

                                Map<String, List<String>> categories = dmMap.get(targetBasename);

                                if (entryName.startsWith("dm-uuid-mpath")) {
                                    categories.get("multipath_id").add("/dev/disk/by-id/" + entryName);
                                } else if (entryName.startsWith("dm-name-")) {
                                    String mpathName = entryName.replace("dm-name-", "");
                                    categories.get("multipath_name").add("/dev/mapper/" + mpathName);
                                } else if (entryName.startsWith("scsi-")) {
                                    categories.get("scsi").add(entryName);
                                } else if (entryName.startsWith("wwn-")) {
                                    categories.get("wwn").add(entryName);
                                }
                            }
                        }
                    }
                }
            }

            return dmMap.entrySet().stream()
                .sorted((e1, e2) -> {
                    int num1 = Integer.parseInt(e1.getKey().split("-")[1]);
                    int num2 = Integer.parseInt(e2.getKey().split("-")[1]);
                    return Integer.compare(num1, num2);
                })
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (e1, e2) -> e1,
                    LinkedHashMap::new
                ));

        } catch (Exception e) {
        }

        return dmMap;
    }

    private boolean isSymbolicLink(File file) {
        try {
            return Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private String getSymbolicLinkTarget(File file) {
        try {
            Path target = Files.readSymbolicLink(file.toPath());
            return target.toString();
        } catch (Exception e) {
            return null;
        }
    }


    private boolean hasPartitionRecursiveForDevice(String devicePath) {
        return false;
    }

    private String getDeviceSize(String devicePath) {
        return null;
    }

    private boolean hasPartitionRecursive(JSONObject device) {
        String deviceName = device.optString("name", "");
        String deviceType = device.optString("type", "");

        if ("part".equals(deviceType)) {
            return false;
        }

        if ("lvm".equals(deviceType) || deviceName.startsWith("/dev/mapper/")) {
            if (deviceName.contains("ceph--") && deviceName.contains("--osd--block--")) {
                return false;
            }
            return true;
        }

        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");

            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                String childType = child.optString("type", "");
                String childName = child.optString("name", "");

                if ("part".equals(childType)) {
                    return true;
                }

                if ("lvm".equals(childType)) {
                    return true;
                }

                if (hasPartitionRecursive(child)) {
                    return true;
                }
            }
        } else {
        }
        return false;
    }

    private Map<String, String> getScsiAddressesBatch() {
        Map<String, String> scsiAddressMap = new HashMap<>();

        try {
            try {
                Script lsscsiCmd = new Script("/usr/bin/lsscsi");
                lsscsiCmd.add("-g");
                OutputInterpreter.AllLinesParser lsscsiParser = new OutputInterpreter.AllLinesParser();
                String lsscsiResult = lsscsiCmd.execute(lsscsiParser);

                if (lsscsiResult == null && lsscsiParser.getLines() != null) {
                    String[] lines = lsscsiParser.getLines().split("\\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty()) continue;

                        String[] tokens = line.split("\\s+");
                        if (tokens.length >= 6) {
                            String scsiAddr = tokens[0].replaceAll("[\\[\\]]", ""); // [0:0:275:0] -> 0:0:275:0
                            String devicePath = tokens[5]; // /dev/sda

                            if (devicePath != null && !devicePath.isEmpty()) {
                                scsiAddressMap.put(devicePath, scsiAddr);
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }

            try {
                File sysBlockDir = new File("/sys/block");
                if (sysBlockDir.exists() && sysBlockDir.isDirectory()) {
                    File[] devices = sysBlockDir.listFiles();
                    if (devices != null) {
                        for (File device : devices) {
                            String deviceName = "/dev/" + device.getName();
                            if (!scsiAddressMap.containsKey(deviceName)) {
                                String scsiDevicePath = device.getAbsolutePath() + "/device/scsi_device";
                                File scsiDeviceFile = new File(scsiDevicePath);
                                if (scsiDeviceFile.exists()) {
                                    try {
                                        String scsiAddress = new String(java.nio.file.Files.readAllBytes(scsiDeviceFile.toPath())).trim();
                                        if (!scsiAddress.isEmpty()) {
                                            scsiAddressMap.put(deviceName, scsiAddress);
                                        }
                                    } catch (Exception e) {

                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }

        } catch (Exception e) {
        }

        return scsiAddressMap;
    }

    private Set<String> getDevicesInUseBatch() {
        Set<String> devicesInUse = new HashSet<>();

        try {
            Script listCommand = new Script("/bin/bash");
            listCommand.add("-c");
            listCommand.add("virsh list --all | grep -v 'Id' | grep -v '^-' | awk '{print $1}' | grep -v '^$'");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = listCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String[] vmIds = parser.getLines().split("\\n");
                for (String vmId : vmIds) {
                    vmId = vmId.trim();
                    if (!vmId.isEmpty()) {
                        try {
                            Script dumpCommand = new Script("virsh");
                            dumpCommand.add("dumpxml", vmId);
                            OutputInterpreter.AllLinesParser dumpParser = new OutputInterpreter.AllLinesParser();
                            String dumpResult = dumpCommand.execute(dumpParser);

                            if (dumpResult == null && dumpParser.getLines() != null) {
                                String vmXml = dumpParser.getLines();
                                Pattern pattern = Pattern.compile("dev=['\"]([^'\"]+)['\"]");
                                Matcher matcher = pattern.matcher(vmXml);
                                while (matcher.find()) {
                                    String devicePath = matcher.group(1);
                                    if (devicePath.startsWith("/dev/")) {
                                        devicesInUse.add(devicePath);
                                    }
                                }
                            }
                        } catch (Exception e) {
                        }
                    }
                }
            }

            try {
                Script mountCommand = new Script("/bin/bash");
                mountCommand.add("-c");
                mountCommand.add("mount | grep '^/dev/' | awk '{print $1}'");
                OutputInterpreter.AllLinesParser mountParser = new OutputInterpreter.AllLinesParser();
                String mountResult = mountCommand.execute(mountParser);

                if (mountResult == null && mountParser.getLines() != null) {
                    String[] mountedDevices = mountParser.getLines().split("\\n");
                    for (String device : mountedDevices) {
                        device = device.trim();
                        if (!device.isEmpty()) {
                            devicesInUse.add(device);
                        }
                    }
                }
            } catch (Exception e) {
            }

        } catch (Exception e) {
        }

        return devicesInUse;
    }

    private Set<String> getDevicesInUseBatch(long timeoutMs) {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            Future<Set<String>> fut = es.submit(() -> getDevicesInUseBatch());
            try {
                return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                timedOut.set(true);
                fut.cancel(true);
                return new HashSet<>();
            }
        } catch (Exception e) {
            return new HashSet<>();
        } finally {
            es.shutdownNow();
        }
    }

    private String getScsiAddress(String deviceName) {
        try {
            if (deviceName.contains("ceph--") && deviceName.contains("--osd--block--")) {
                return generateVirtualScsiAddressForCeph(deviceName);
            }

            String blockDevice = deviceName.replace("/dev/", "");

            if (blockDevice.contains("(")) {
                blockDevice = blockDevice.substring(0, blockDevice.indexOf("(")).trim();
            }

            String scsiDevicePath = "/sys/block/" + blockDevice + "/device/scsi_device";

            try {
                File scsiDeviceFile = new File(scsiDevicePath);
                if (scsiDeviceFile.exists() && scsiDeviceFile.canRead()) {
                    String scsiAddress = new String(Files.readAllBytes(scsiDeviceFile.toPath())).trim();
                    if (!scsiAddress.isEmpty()) {
                        return scsiAddress;
                    }
                }
            } catch (Exception e) {
            }

            try {
                Script lsscsiCmd = new Script("/usr/bin/lsscsi");
                lsscsiCmd.add("-g");
                OutputInterpreter.AllLinesParser lsscsiParser = new OutputInterpreter.AllLinesParser();
                String lsscsiResult = lsscsiCmd.execute(lsscsiParser);

                if (lsscsiResult == null && lsscsiParser.getLines() != null) {
                    String[] lines = lsscsiParser.getLines().split("\\n");
                    for (String line : lines) {
                        if (line.contains(deviceName)) {

                            String[] parts = line.split("\\s+");
                            if (parts.length > 0) {
                                String scsiPart = parts[0].replace("[", "").replace("]", "");
                                return scsiPart;
                            }
                        }
                    }
                }
            } catch (Exception e) {
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private String generateVirtualScsiAddressForCeph(String deviceName) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("lsblk -no NAME " + deviceName + " | head -1");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().isEmpty()) {
                String physicalDevice = parser.getLines().trim();

                String physicalScsiAddress = getPhysicalScsiAddress(physicalDevice);
                if (physicalScsiAddress != null) {
                    String[] parts = physicalScsiAddress.split(":");
                    if (parts.length >= 4) {
                        int unit = Integer.parseInt(parts[3]) + 1;
                        String virtualAddress = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + unit;
                        return virtualAddress;
                    }
                }
            }

            String defaultVirtualAddress = "0:0:277:1";

            return defaultVirtualAddress;

        } catch (Exception e) {

            return "0:0:277:1";
        }
    }

    private String getPhysicalScsiAddress(String deviceName) {
        try {
            String scsiDevicePath = "/sys/block/" + deviceName + "/device/scsi_device";

            File scsiDeviceFile = new File(scsiDevicePath);
            if (scsiDeviceFile.exists() && scsiDeviceFile.canRead()) {
                String scsiAddress = new String(Files.readAllBytes(scsiDeviceFile.toPath())).trim();
                if (!scsiAddress.isEmpty()) {
                    return scsiAddress;
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String generateBasicVhbaXml(String parentHbaName) {
        try {
            StringBuilder xml = new StringBuilder();
            xml.append("<device>\n");
            xml.append("  <parent>").append(parentHbaName).append("</parent>\n");
            xml.append("  <capability type='scsi_host'>\n");
            xml.append("    <capability type='fc_host'>\n");
            xml.append("    </capability>\n");
            xml.append("  </capability>\n");
            xml.append("</device>");
            return xml.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String findVhbaDeviceByWwnn(String wwnn) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("/usr/bin/virsh nodedev-list --cap scsi_host");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null) {
                String[] devices = parser.getLines().split("\\n");
                for (String device : devices) {
                    device = device.trim();
                    if (device.isEmpty()) continue;

                    String deviceXml = getVhbaDumpXml(device);
                    if (deviceXml != null && deviceXml.contains("<wwnn>" + wwnn + "</wwnn>")) {
                        return device;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isDeviceInUse(String deviceName) {
        try {
            if (isLunDeviceAllocatedToVm(deviceName)) {
                return true;
            }

            Script mountCommand = new Script("/bin/bash");
            mountCommand.add("-c");
            mountCommand.add("mount | grep -q '" + deviceName + "'");
            String mountResult = mountCommand.execute(null);
            if (mountResult == null) {
                return true;
            }

            Script lvmCommand = new Script("/bin/bash");
            lvmCommand.add("-c");
            lvmCommand.add("lvs --noheadings -o lv_name,vg_name 2>/dev/null | grep -q '" + deviceName + "'");
            String lvmResult = lvmCommand.execute(null);
            if (lvmResult == null) {
                return true;
            }

            Script swapCommand = new Script("/bin/bash");
            swapCommand.add("-c");
            swapCommand.add("swapon --show | grep -q '" + deviceName + "'");
            String swapResult = swapCommand.execute(null);
            if (swapResult == null) {
                return true; // 스왑으로 사용 중
            }

            Script partCommand = new Script("/bin/bash");
            partCommand.add("-c");
            partCommand.add("fdisk -l " + deviceName + " 2>/dev/null | grep -q 'Disklabel type:'");
            String partResult = partCommand.execute(null);
            if (partResult == null) {
                return true;
            }

            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isLunDeviceAllocatedToVm(String deviceName) {
        try {
            Script listCommand = new Script("/bin/bash");
            listCommand.add("-c");
            listCommand.add("virsh list --name | sed '/^$/d'");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String listRes = listCommand.execute(parser);
            if (listRes == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String[] vms = parser.getLines().trim().split("\n");
                for (String vm : vms) {
                    vm = vm.trim();
                    if (vm.isEmpty()) continue;
                    try {
                        Script dump = new Script("virsh");
                        dump.add("dumpxml", vm);
                        OutputInterpreter.AllLinesParser dp = new OutputInterpreter.AllLinesParser();
                        String dr = dump.execute(dp);
                        if (dr == null && dp.getLines() != null) {
                            String xml = dp.getLines();
                            if (xml.contains(deviceName)) return true;
                        }
                    } catch (Exception ignore) {}
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public Answer listHostScsiDevices(Command command) {
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> hostDevicesText = new ArrayList<>();
        List<Boolean> hasPartitions = new ArrayList<>();

        try {
            ListHostScsiDeviceAnswer fast = listHostScsiDevicesFast();
            if (fast != null && fast.getResult()) {
                return fast;
            }
            Map<Path, String> realToById = buildByIdReverseMap();

            Script cmd = new Script("/usr/bin/lsscsi");
            cmd.add("-g");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                return new ListHostScsiDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions);
            }

            String[] lines = parser.getLines().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                if (tokens.length < 7) continue;
                String scsiAddr = tokens[0];
                String sgdev = tokens[6];
                String name = sgdev;

                StringBuilder text = new StringBuilder();
                text.append("SCSI_Address: ").append(scsiAddr);

                String displayName = name;
                try {
                    String dev = tokens[5];
                    String byId = resolveById(realToById, dev);
                    if (byId != null && !byId.equals(dev)) {
                        text.append(" BY_ID: ").append(byId);
                        String byIdName = byId.substring(byId.lastIndexOf('/') + 1);
                        displayName = name + " (" + byIdName + ")";
                    }
                } catch (Exception ignore) {}
                hostDevicesNames.add(displayName);
                hostDevicesText.add(text.toString());
                hasPartitions.add(false);
            }

            return new ListHostScsiDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions);
        } catch (Exception e) {
            return new ListHostScsiDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private ListHostScsiDeviceAnswer listHostScsiDevicesFast() {
        try {
            List<String> names = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<Boolean> hasPartitions = new ArrayList<>();

            Map<String, String> scsiAddressCache = getScsiAddressesBatch();
            Map<Path, String> realToById = buildByIdReverseMap();

            File sysBlock = new File("/sys/block");
            File[] entries = sysBlock.listFiles();
            if (entries == null) {
                return null;
            }

            collectAllScsiDevicesUnified(names, texts, hasPartitions, scsiAddressCache, realToById);
            for (int i = 0; i < names.size(); i++) {
            }

            return new ListHostScsiDeviceAnswer(true, names, texts, hasPartitions);
        } catch (Exception ex) {
            return null;
        }
    }

    private void collectAllScsiDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitions,
                                            Map<String, String> scsiAddressCache, Map<Path, String> realToById) {
        try {
            File sysBlock = new File("/sys/block");
            File[] entries = sysBlock.listFiles();
            if (entries == null) return;

            for (File e : entries) {
                String bname = e.getName();
                if (!(bname.startsWith("sd") || bname.startsWith("vd") || bname.startsWith("xvd"))) {
                    continue;
                }

                String dev = "/dev/" + bname;

                String sg = null;
                try {
                    File sgDir = new File(e, "device/scsi_generic");
                    File[] sgs = sgDir.listFiles();
                    if (sgs != null && sgs.length > 0) {
                        sg = "/dev/" + sgs[0].getName();
                    }
                } catch (Exception ignore) {}

                String scsiAddress = scsiAddressCache.get(dev);
                if (scsiAddress == null) {
                    try {
                        Path devLink = Path.of(e.getAbsolutePath(), "device");
                        Path real = Files.readSymbolicLink(devLink);
                        Path resolved = devLink.getParent().resolve(real).normalize();
                        scsiAddress = resolved.getFileName().toString();
                    } catch (Exception ignore) {}
                }

                String vendor = readFirstLineQuiet(new File(e, "device/vendor"));
                String model = readFirstLineQuiet(new File(e, "device/model"));
                String rev = readFirstLineQuiet(new File(e, "device/rev"));

                String byId = resolveById(realToById, dev);

                StringBuilder text = new StringBuilder();
                text.append("SCSI_Address: ").append(scsiAddress != null ? ("["+scsiAddress+"]") : "");
                text.append(" Type: disk");
                if (vendor != null) text.append(" Vendor: ").append(vendor);
                if (model != null) text.append(" Model: ").append(model);
                if (rev != null) text.append(" Revision: ").append(rev);
                text.append(" Device: ").append(dev);
                if (!byId.equals(dev)) text.append(" BY_ID: ").append(byId);

                String displayName = sg != null ? sg : dev;
                if (!byId.equals(dev)) {
                    String byIdName = byId.substring(byId.lastIndexOf('/') + 1);
                    displayName = (sg != null ? sg : dev) + " (" + byIdName + ")";
                }

                names.add(displayName);
                texts.add(text.toString());
                hasPartitions.add(false);
            }
        } catch (Exception e) {
        }
    }

    private String readFirstLineQuiet(File f) {
        try {
            if (!f.exists()) return null;
            String s = new String(Files.readAllBytes(f.toPath())).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignore) {
            return null;
        }
    }

    protected Answer listHostHbaDevices(Command command) {
        List<String> hostDevicesText = new ArrayList<>();
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> deviceTypes = new ArrayList<>();
        List<String> parentHbaNames = new ArrayList<>();
        Map<String, String> scsiAddressMap = new HashMap<>();

        try {
            Script lsscsiCommand = new Script("/bin/bash");
            lsscsiCommand.add("-c");
            lsscsiCommand.add("lsscsi -g");
            OutputInterpreter.AllLinesParser lsscsiParser = new OutputInterpreter.AllLinesParser();
            String lsscsiResult = lsscsiCommand.execute(lsscsiParser);

            if (lsscsiResult == null && lsscsiParser.getLines() != null) {
                String[] lines = lsscsiParser.getLines().split("\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] tokens = line.split("\\s+");
                    if (tokens.length < 7) continue;

                    String scsiAddr = tokens[0];
                    String type = tokens[1];
                    String vendor = tokens[2];
                    String model = tokens[3];
                    String rev = tokens[4];
                    String dev = tokens[5];
                    String sgdev = tokens[6];

                    String scsiAddress = scsiAddr.replaceAll("[\\[\\]]", "");

                    String[] scsiParts = scsiAddress.split(":");
                    if (scsiParts.length >= 4) {
                        String hostNum = scsiParts[0];
                        String scsiHostName = "scsi_host" + hostNum;

                        if (!scsiAddressMap.containsKey(scsiHostName)) {
                            scsiAddressMap.put(scsiHostName, scsiAddress);
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        try {
            Script vportsCommand = new Script("/bin/bash");
            vportsCommand.add("-c");
            vportsCommand.add("virsh nodedev-list --cap vports");
            OutputInterpreter.AllLinesParser vportsParser = new OutputInterpreter.AllLinesParser();
            String vportsResult = vportsCommand.execute(vportsParser);
            if (vportsResult == null && vportsParser.getLines() != null) {
                String[] vportsLines = vportsParser.getLines().split("\\n");
                for (String vportsLine : vportsLines) {
                    String hbaName = vportsLine.trim();
                    if (!hbaName.isEmpty() && !hostDevicesNames.contains(hbaName)) {
                        String detailedInfo = getHbaDeviceDetailsFromVports(hbaName);
                        String scsiAddress = scsiAddressMap.get(hbaName);
                        if (scsiAddress != null) {
                            detailedInfo += "\nSCSI_Address: " + scsiAddress;
                        }

                        hostDevicesNames.add(hbaName);
                        hostDevicesText.add(detailedInfo);
                        deviceTypes.add("physical");
                        parentHbaNames.add("");
                    }
                }
            }
        } catch (Exception e) {
        }

        return new ListHostHbaDeviceAnswer(true, hostDevicesNames, hostDevicesText, deviceTypes, parentHbaNames);
    }

    private String getHbaDeviceDetailsFromVports(String hbaName) {
        StringBuilder details = new StringBuilder();
        details.append(hbaName);

        try {
            Script hbaInfoCommand = new Script("/bin/bash");
            hbaInfoCommand.add("-c");
            hbaInfoCommand.add("virsh nodedev-dumpxml " + hbaName);
            OutputInterpreter.AllLinesParser hbaInfoParser = new OutputInterpreter.AllLinesParser();
            String hbaInfoResult = hbaInfoCommand.execute(hbaInfoParser);

            if (hbaInfoResult == null && hbaInfoParser.getLines() != null) {
                String[] infoLines = hbaInfoParser.getLines().split("\\n");
                String maxVports = "";
                String wwnn = "";
                String wwpn = "";
                String fabricWwn = "";

                for (String infoLine : infoLines) {
                    if (infoLine.contains("<max_vports>")) {
                        maxVports = infoLine.replaceAll("<[^>]*>", "").trim();
                    } else if (infoLine.contains("<wwnn>")) {
                        wwnn = infoLine.replaceAll("<[^>]*>", "").trim();
                    } else if (infoLine.contains("<wwpn>")) {
                        wwpn = infoLine.replaceAll("<[^>]*>", "").trim();
                    } else if (infoLine.contains("<fabric_wwn>")) {
                        fabricWwn = infoLine.replaceAll("<[^>]*>", "").trim();
                    }
                }

                if (!maxVports.isEmpty()) {
                    details.append(" (Max vPorts: ").append(maxVports).append(")");
                }
                if (!wwnn.isEmpty()) {
                    details.append(" WWNN: ").append(wwnn);
                }
                if (!wwpn.isEmpty()) {
                    details.append(" WWPN: ").append(wwpn);
                }
                if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                    details.append(" Fabric_WWN: ").append(fabricWwn);
                }
            }
        } catch (Exception e) {

        }

        return details.toString();
    }

    public Answer createHostVHbaDevice(CreateVhbaDeviceCommand command, String parentHbaName, String wwnn, String wwpn, String vhbaName, String xmlContent) {

        try {
            if (parentHbaName == null || parentHbaName.trim().isEmpty()) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA 이름이 필요합니다");
            }
            if (wwnn == null) {
                wwnn = "";
            }

            if (!validateParentHbaFromVports(parentHbaName)) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA가 vports를 지원하지 않습니다: " + parentHbaName);
            }

            String basicXmlContent = generateBasicVhbaXml(parentHbaName);
            if (basicXmlContent == null) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "기본 XML 생성 실패");
            }

            String xmlFilePath = String.format("/tmp/vhba_%s.xml", vhbaName);

            try (FileWriter writer = new FileWriter(xmlFilePath)) {
                writer.write(basicXmlContent);
            } catch (IOException e) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "XML 파일 생성 실패: " + e.getMessage());
            }

            Script createCommand = new Script("/bin/bash");
            createCommand.add("-c");
            createCommand.add("/usr/bin/virsh nodedev-create " + xmlFilePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = createCommand.execute(parser);

            if (result != null) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 실패: " + result);
            }

            String createdDeviceName = extractCreatedDeviceNameFromOutput(parser.getLines());
            if (createdDeviceName == null) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "디바이스 이름 추출 실패");
            }

            if (!validateCreatedVhba(createdDeviceName)) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 검증 실패");
            }

            String actualVhbaXml = getVhbaDumpXml(createdDeviceName);
            if (actualVhbaXml != null && !actualVhbaXml.isEmpty()) {
                File vhbaDir = new File("/etc/vhba");
                if (!vhbaDir.exists()) {
                    if (vhbaDir.mkdirs()) {
                    } else {
                    }
                }

                String extractedWwnn = extractWwnnFromXml(actualVhbaXml);
                String backupFilePath;

                if (extractedWwnn != null && !extractedWwnn.trim().isEmpty()) {
                    backupFilePath = String.format("/etc/vhba/vhba_%s.xml", extractedWwnn);
                } else {
                    backupFilePath = String.format("/etc/vhba/%s.xml", createdDeviceName);
                }

                File backupFile = new File(backupFilePath);
                if (!backupFile.exists()) {
                    try (FileWriter writer = new FileWriter(backupFilePath)) {
                        writer.write(actualVhbaXml);
                    } catch (IOException e) {
                    }
                } else {
                }
            } else {
            }

            cleanupXmlFile(xmlFilePath);

            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(true, vhbaName, createdDeviceName);

        } catch (Exception e) {
            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 중 오류: " + e.getMessage());
        }
    }

    public Answer deleteHostVHbaDevice(DeleteVhbaDeviceCommand command) {
        String vhbaName = command.getVhbaName();
        String wwnn = command.getWwnn();

        try {
            String targetDeviceName = null;

            if (wwnn != null && !wwnn.trim().isEmpty()) {
                targetDeviceName = findVhbaDeviceByWwnn(wwnn);
                if (targetDeviceName == null) {
                    return new DeleteVhbaDeviceAnswer(command, false, "WWNN으로 vHBA 디바이스를 찾을 수 없습니다: " + wwnn);
                }
            } else {
                if (vhbaName == null || vhbaName.trim().isEmpty()) {
                    return new DeleteVhbaDeviceAnswer(command, false, "vHBA 이름이 필요합니다");
                }
                targetDeviceName = vhbaName;
            }

            if (isVhbaAllocatedToVm(targetDeviceName)) {
                return new DeleteVhbaDeviceAnswer(command, false, "vHBA가 VM에 할당되어 있어 삭제할 수 없습니다. 먼저 할당을 해제해주세요.");
            }

            Script destroyCommand = new Script("/bin/bash");
            destroyCommand.add("-c");
            destroyCommand.add("/usr/bin/virsh nodedev-destroy " + targetDeviceName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = destroyCommand.execute(parser);

            if (result != null) {
                return new DeleteVhbaDeviceAnswer(command, false, "vHBA 삭제 실패: " + result);
            }

            String backupFilePath;
            if (wwnn != null && !wwnn.trim().isEmpty()) {
                backupFilePath = String.format("/etc/vhba/vhba_%s.xml", wwnn);
            } else {
                backupFilePath = String.format("/etc/vhba/%s.xml", targetDeviceName);
            }

            File backupFile = new File(backupFilePath);
            if (backupFile.exists()) {
                if (backupFile.delete()) {
                } else {
                }
            } else {
            }

            return new DeleteVhbaDeviceAnswer(command, true, "vHBA 디바이스가 성공적으로 삭제되었습니다: " + vhbaName);

        } catch (Exception e) {
            return new DeleteVhbaDeviceAnswer(command, false, "vHBA 삭제 중 오류: " + e.getMessage());
        }
    }

    private boolean isVhbaAllocatedToVm(String vhbaName) {
        try {
            Script checkCommand = new Script("/bin/bash");
            checkCommand.add("-c");
            checkCommand.add("virsh list --all | grep -v 'Id' | grep -v '^-' | while read line; do " +
                           "vm_id=$(echo $line | awk '{print $1}'); " +
                           "if [ ! -z \"$vm_id\" ]; then " +
                           "virsh dumpxml $vm_id | grep -q '" + vhbaName + "'; " +
                           "if [ $? -eq 0 ]; then " +
                           "echo 'allocated'; " +
                           "break; " +
                           "fi; " +
                           "fi; " +
                           "done");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = checkCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                return parser.getLines().trim().equals("allocated");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateParentHbaFromVports(String parentHbaName) {
        try {
            Script vportsCommand = new Script("/bin/bash");
            vportsCommand.add("-c");
            vportsCommand.add("virsh nodedev-list --cap vports");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = vportsCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String[] lines = parser.getLines().split("\\n");
                for (String line : lines) {
                    String hbaName = line.trim();
                    if (hbaName.equals(parentHbaName)) {
                        return true;
                    }
                }
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractCreatedDeviceNameFromOutput(String output) {
        if (output == null) {
            return null;
        }

        String[] lines = output.split("\\n");
        for (String line : lines) {
            if (line.contains("Node device") && line.contains("created from")) {
                String[] parts = line.split(" ");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].startsWith("scsi_host")) {
                        String deviceName = parts[i];
                        return deviceName;
                    }
                }
            }
        }

        return null;
    }

    private boolean validateCreatedVhba(String deviceName) {
        try {
            Script validateCommand = new Script("/bin/bash");
            validateCommand.add("-c");
            validateCommand.add("virsh nodedev-dumpxml " + deviceName + " | grep -c 'fc_host'");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = validateCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String count = parser.getLines().trim();
                return !count.equals("0");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanupXmlFile(String xmlFilePath) {
        try {
            File xmlFile = new File(xmlFilePath);
            if (xmlFile.exists()) {
                // xmlFile.delete();
            }
        } catch (Exception e) {
        }
    }

    protected Answer listHostVHbaDevices(Command command) {
        try {
            ListVhbaDevicesCommand cmd = (ListVhbaDevicesCommand) command;
            String keyword = cmd.getKeyword();
            List<ListVhbaDevicesCommand.VhbaDeviceInfo> vhbaDevices = new ArrayList<>();
            HashSet<String> vhbaNames = new HashSet<>();
            if (keyword != null && !keyword.isEmpty()) {
                try {
                    Script specificVhbaCommand = new Script("/bin/bash");
                    specificVhbaCommand.add("-c");
                    specificVhbaCommand.add("virsh nodedev-list | grep scsi_host | while read device; do " +
                                       "parent=$(virsh nodedev-dumpxml $device | grep '<parent>' | sed 's/<[^>]*>//g' | tr -d ' '); " +
                                       "if [ \"$parent\" = \"" + keyword + "\" ]; then " +
                                       "echo $device; " +
                                       "fi; " +
                                       "done");
                    OutputInterpreter.AllLinesParser specificVhbaParser = new OutputInterpreter.AllLinesParser();
                    String specificVhbaResult = specificVhbaCommand.execute(specificVhbaParser);

                    if (specificVhbaResult == null && specificVhbaParser.getLines() != null) {
                        String[] specificVhbaLines = specificVhbaParser.getLines().split("\\n");
                        for (String specificVhbaLine : specificVhbaLines) {
                            String vhbaName = specificVhbaLine.trim();
                            if (!vhbaName.isEmpty() && !vhbaName.startsWith("===")) {
                                // vHBA인지 확인 후 추가
                                if (isVhbaDevice(vhbaName)) {
                                    vhbaNames.add(vhbaName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            } else {
                try {
                    Script scsiHostCommand = new Script("/bin/bash");
                    scsiHostCommand.add("-c");
                    scsiHostCommand.add("virsh nodedev-list | grep scsi_host");
                    OutputInterpreter.AllLinesParser scsiHostParser = new OutputInterpreter.AllLinesParser();
                    String scsiHostResult = scsiHostCommand.execute(scsiHostParser);

                    if (scsiHostResult == null && scsiHostParser.getLines() != null) {
                        String[] scsiHostLines = scsiHostParser.getLines().split("\\n");

                        for (String scsiHostLine : scsiHostLines) {
                            String deviceName = scsiHostLine.trim();
                            if (!deviceName.isEmpty()) {
                                if (isVhbaDevice(deviceName)) {
                                    vhbaNames.add(deviceName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }

            if (vhbaNames.isEmpty() && (keyword == null || keyword.isEmpty())) {
                try {
                    Script fcRemotePortsCommand = new Script("/bin/bash");
                    fcRemotePortsCommand.add("-c");
                    fcRemotePortsCommand.add("find /sys/class/fc_remote_ports -name 'rport-*' -type d 2>/dev/null | head -10");
                    OutputInterpreter.AllLinesParser fcRemotePortsParser = new OutputInterpreter.AllLinesParser();
                    String fcRemotePortsResult = fcRemotePortsCommand.execute(fcRemotePortsParser);

                    if (fcRemotePortsResult == null && fcRemotePortsParser.getLines() != null) {
                        String[] fcRemotePortsLines = fcRemotePortsParser.getLines().split("\\n");
                        for (String fcRemotePortsLine : fcRemotePortsLines) {
                            String vhbaPath = fcRemotePortsLine.trim();
                            if (!vhbaPath.isEmpty()) {
                                String vhbaName = extractVhbaNameFromPath(vhbaPath);
                                if (vhbaName != null) {
                                    vhbaNames.add(vhbaName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }

            for (String vhbaName : vhbaNames) {

                Script vhbaInfoCommand = new Script("/bin/bash");
                vhbaInfoCommand.add("-c");
                vhbaInfoCommand.add("virsh nodedev-dumpxml " + vhbaName);
                OutputInterpreter.AllLinesParser vhbaInfoParser = new OutputInterpreter.AllLinesParser();
                String vhbaInfoResult = vhbaInfoCommand.execute(vhbaInfoParser);

                String parentHbaName = "";
                String wwnn = "";
                String wwpn = "";
                String fabricWwn = "";
                String description = "";
                String status = "Active";
                String scsiAddress = "";

                if (vhbaInfoResult == null && vhbaInfoParser.getLines() != null) {
                    String[] infoLines = vhbaInfoParser.getLines().split("\\n");
                    for (String infoLine : infoLines) {
                        if (infoLine.contains("<parent>")) {
                            parentHbaName = infoLine.replaceAll("<[^>]*>", "").trim();
                        } else if (infoLine.contains("<wwnn>")) {
                            wwnn = infoLine.replaceAll("<[^>]*>", "").trim();
                        } else if (infoLine.contains("<wwpn>")) {
                            wwpn = infoLine.replaceAll("<[^>]*>", "").trim();
                        } else if (infoLine.contains("<fabric_wwn>")) {
                            fabricWwn = infoLine.replaceAll("<[^>]*>", "").trim();
                        }
                    }

                    try {
                        Script lsscsiAllCommand = new Script("/bin/bash");
                        lsscsiAllCommand.add("-c");
                        lsscsiAllCommand.add("lsscsi");
                        OutputInterpreter.AllLinesParser lsscsiAllParser = new OutputInterpreter.AllLinesParser();
                        String lsscsiAllResult = lsscsiAllCommand.execute(lsscsiAllParser);

                        if (lsscsiAllResult == null && lsscsiAllParser.getLines() != null) {
                        }

                        Script scsiAddressCommand = new Script("/bin/bash");
                        scsiAddressCommand.add("-c");
                        scsiAddressCommand.add("lsscsi | grep -E '\\[.*:.*:.*:.*\\].*" + vhbaName + "' | head -1");
                        OutputInterpreter.AllLinesParser scsiAddressParser = new OutputInterpreter.AllLinesParser();
                        String scsiAddressResult = scsiAddressCommand.execute(scsiAddressParser);

                        if (scsiAddressResult == null && scsiAddressParser.getLines() != null) {
                            String scsiLine = scsiAddressParser.getLines().trim();
                            if (!scsiLine.isEmpty()) {
                                String[] parts = scsiLine.split("\\s+");
                                if (parts.length > 0) {
                                    String scsiPart = parts[0];
                                    if (scsiPart.startsWith("[") && scsiPart.endsWith("]")) {
                                        scsiAddress = scsiPart.substring(1, scsiPart.length() - 1);
                                    } else {
                                    }
                                }
                            } else {
                            }
                        } else {
                        }

                        if (scsiAddress.isEmpty()) {

                            String hostNum = vhbaName.replace("scsi_host", "");

                            if (hostNum.matches("\\d+")) {
                                scsiAddress = hostNum + ":0:1:0";

                                Script hostCheckCommand = new Script("/bin/bash");
                                hostCheckCommand.add("-c");
                                hostCheckCommand.add("ls /sys/class/scsi_host/" + vhbaName + " 2>/dev/null || echo 'not_found'");
                                OutputInterpreter.AllLinesParser hostCheckParser = new OutputInterpreter.AllLinesParser();
                                String hostCheckResult = hostCheckCommand.execute(hostCheckParser);

                                if (hostCheckResult == null && hostCheckParser.getLines() != null) {
                                    String checkResult = hostCheckParser.getLines().trim();
                                    if (checkResult.equals("not_found")) {
                                        scsiAddress = "";
                                    }
                                }
                            } else {
                            }
                        }

                    } catch (Exception e) {
                    }

                    StringBuilder descBuilder = new StringBuilder();

                    if (!wwnn.isEmpty()) {
                        descBuilder.append("WWNN: ").append(wwnn);
                    }
                    if (!wwpn.isEmpty()) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append(" ");
                        }
                        descBuilder.append("WWPN: ").append(wwpn);
                    }
                    if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append(" ");
                        }
                        descBuilder.append("Fabric_WWN: ").append(fabricWwn);
                    }
                    if (!scsiAddress.isEmpty()) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append(" ");
                        }
                        descBuilder.append("SCSI_Address: ").append(scsiAddress);
                    } else {
                    }
                    // description 변수에 descBuilder 내용 할당
                    description = descBuilder.toString();
                }

                boolean shouldInclude = true;
                if (keyword != null && !keyword.isEmpty()) {
                    shouldInclude = parentHbaName.equals(keyword);
                }

                if (!shouldInclude) {
                    continue;
                }

                com.cloud.agent.api.ListVhbaDevicesCommand.VhbaDeviceInfo vhbaInfo =
                    new com.cloud.agent.api.ListVhbaDevicesCommand.VhbaDeviceInfo(
                        vhbaName, parentHbaName, wwnn, wwpn, description, status
                    );
                vhbaDevices.add(vhbaInfo);
            }

            return new com.cloud.agent.api.ListVhbaDevicesAnswer(true, vhbaDevices);

        } catch (Exception e) {
            return new com.cloud.agent.api.ListVhbaDevicesAnswer(false, new ArrayList<>());
        }
    }

    private boolean isVhbaDevice(String deviceName) {
        try {
            Script checkCommand = new Script("/bin/bash");
            checkCommand.add("-c");
            checkCommand.add("virsh nodedev-dumpxml " + deviceName + " | grep -c 'fc_host' || true");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = checkCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String count = parser.getLines().trim();
                return !count.equals("0");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String extractVhbaNameFromPath(String path) {
        try {

            Script extractCommand = new Script("/bin/bash");
            extractCommand.add("-c");
            extractCommand.add("find /sys/class/fc_remote_ports -name '$(basename " + path + ")' -exec dirname {} \\; | grep scsi_host | head -1");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = extractCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String scsiHostPath = parser.getLines().trim();
                if (!scsiHostPath.isEmpty()) {
                    return scsiHostPath.substring(scsiHostPath.lastIndexOf('/') + 1);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    protected Answer listVhbaCapableHbas(Command command) {
        List<String> hostDevicesText = new ArrayList<>();
        List<String> hostDevicesNames = new ArrayList<>();

        try {

            Script vportsCommand = new Script("/bin/bash");
            vportsCommand.add("-c");
            vportsCommand.add("virsh nodedev-list --cap vports");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = vportsCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String[] lines = parser.getLines().split("\\n");
                for (String line : lines) {
                    String hbaName = line.trim();
                    if (!hbaName.isEmpty()) {
                        // HBA 상세 정보 조회
                        Script hbaInfoCommand = new Script("/bin/bash");
                        hbaInfoCommand.add("-c");
                        hbaInfoCommand.add("virsh nodedev-dumpxml " + hbaName);
                        OutputInterpreter.AllLinesParser hbaInfoParser = new OutputInterpreter.AllLinesParser();
                        String hbaInfoResult = hbaInfoCommand.execute(hbaInfoParser);

                        String hbaDescription = hbaName;
                        if (hbaInfoResult == null && hbaInfoParser.getLines() != null) {
                            String[] infoLines = hbaInfoParser.getLines().split("\\n");
                            String maxVports = "";
                            String wwnn = "";
                            String wwpn = "";
                            String fabricWwn = "";

                            for (String infoLine : infoLines) {
                                if (infoLine.contains("<max_vports>")) {
                                    maxVports = infoLine.replaceAll("<[^>]*>", "").trim();
                                } else if (infoLine.contains("<wwnn>")) {
                                    wwnn = infoLine.replaceAll("<[^>]*>", "").trim();
                                } else if (infoLine.contains("<wwpn>")) {
                                    wwpn = infoLine.replaceAll("<[^>]*>", "").trim();
                                } else if (infoLine.contains("<fabric_wwn>")) {
                                    fabricWwn = infoLine.replaceAll("<[^>]*>", "").trim();
                                }
                            }

                            StringBuilder descBuilder = new StringBuilder(hbaName);
                            if (!maxVports.isEmpty()) {
                                descBuilder.append(" (Max vPorts: ").append(maxVports).append(")");
                            }
                            if (!wwnn.isEmpty()) {
                                descBuilder.append(" WWNN: ").append(wwnn);
                            }
                            if (!wwpn.isEmpty()) {
                                descBuilder.append(" WWPN: ").append(wwpn);
                            }
                            hbaDescription = descBuilder.toString();
                        }

                        hostDevicesNames.add(hbaName);
                        hostDevicesText.add(hbaDescription);
                    }
                }
            }
        } catch (Exception e) {
        }

        return new ListHostHbaDeviceAnswer(true, hostDevicesNames, hostDevicesText);
    }

    protected Answer createImageRbd(String poolUuid, String skey, String authUserName, String host, String names, long sizes, String poolPath) {
        createRBDSecretKeyFileIfNoExist(poolUuid, DEFAULT_LOCAL_STORAGE_PATH, skey);
        String cmdout = Script.runSimpleBashScript("rbd -p " + poolPath + " --id " + authUserName + " -m " + host + " -K " + DEFAULT_LOCAL_STORAGE_PATH + poolUuid + " create -s " + (sizes * 1024) + " " + names);
        if (cmdout == null) {
            logger.debug(cmdout);
        }else{
        }
        return new ListRbdObjectsAnswer(true, names);
    }

    protected Answer deleteImageRbd(String poolUuid, String skey, String authUserName, String host, String name, String poolPath) {
        createRBDSecretKeyFileIfNoExist(poolUuid, DEFAULT_LOCAL_STORAGE_PATH, skey);
        String cmdout = Script.runSimpleBashScript("rbd -p " + poolPath + " --id " + authUserName + " -m " + host + " -K " + DEFAULT_LOCAL_STORAGE_PATH + poolUuid + " rm " + name);
        if (cmdout == null) {
            logger.debug(cmdout);
        }
        return new ListRbdObjectsAnswer(true, name);
    }

    protected Answer listRbdFilesAtPath(String poolUuid, String skey, String authUserName, String host, int startIndex, int pageSize, String poolPath, String keyword) {
        int count = 0;
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> absPaths = new ArrayList<>();
        List<Boolean> isDirs = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        List<Long> modifiedList = new ArrayList<>();

        createRBDSecretKeyFileIfNoExist(poolUuid, DEFAULT_LOCAL_STORAGE_PATH, skey);

        Script listCommand = new Script("/bin/bash", logger);
        listCommand.add("-c");

        if (keyword != null && !keyword.isEmpty()) {
            listCommand.add("rbd ls -p " + poolPath + " --id " + authUserName + " -m " + host + " -K " + DEFAULT_LOCAL_STORAGE_PATH + poolUuid + " | grep " + keyword );
        } else {
            listCommand.add("rbd ls -p " + poolPath + " --id " + authUserName + " -m " + host + " -K " + DEFAULT_LOCAL_STORAGE_PATH + poolUuid);
        }
        OutputInterpreter.AllLinesParser listParser = new OutputInterpreter.AllLinesParser();
        String listResult = listCommand.execute(listParser);
        if (listResult == null && listParser.getLines() != null) {
            String[] imageNames = listParser.getLines().split("\\n");

            for (String imageName : imageNames) {
                if (count >= startIndex && count < startIndex + pageSize) {
                    Long imageSize = 0L;
                    Long lastModified = 0L;
                    names.add(imageName.trim());
                    paths.add(imageName);
                    isDirs.add(false);
                    absPaths.add("/");

                    Script infoCommand = new Script("rbd");
                    infoCommand.add("-p", poolPath);
                    infoCommand.add("--id", authUserName);
                    infoCommand.add("-m", host);
                    infoCommand.add("-K", DEFAULT_LOCAL_STORAGE_PATH + poolUuid);
                    infoCommand.add("info", imageName.trim());
                    OutputInterpreter.AllLinesParser infoParser = new OutputInterpreter.AllLinesParser();
                    String infoResult = infoCommand.execute(infoParser);
                    if (infoResult == null && infoParser.getLines() != null) {
                        String[] infoLines = infoParser.getLines().split("\\n");
                        for (String infoLine : infoLines) {
                            if (infoLine.contains("size")) {
                                String[] part = infoLine.split(" ");
                                String numberString = part[1];
                                double number = Double.parseDouble(numberString);
                                imageSize = (long) (number * 1024 * 1024 * 1024);
                                sizes.add(imageSize);
                            }
                            if (infoLine.contains("modify_timestamp")) {
                                String[] parts = infoLine.split(": ");
                                try {
                                    String modifyTimestamp = parts[1].trim();
                                    SimpleDateFormat inputDateFormat = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US);
                                    Date modifyDate = inputDateFormat.parse(modifyTimestamp);
                                    lastModified = modifyDate.getTime();
                                    modifiedList.add(lastModified);
                                } catch (ParseException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }
                count++;
            }
        }
        return new ListDataStoreObjectsAnswer(true, count, names, paths, absPaths, isDirs, sizes, modifiedList);
    }

    public void createRBDSecretKeyFileIfNoExist(String uuid, String localPath, String skey) {
        File file = new File(localPath + File.separator + uuid);
        try {
            // 파일이 존재하지 않을 때만 생성
            if (!file.exists()) {
                boolean isCreated = file.createNewFile();
                if (isCreated) {
                    // 파일 생성 후 내용 작성
                    FileWriter writer = new FileWriter(file);
                    writer.write(skey);
                    writer.close();
                }
            }
        } catch (IOException e) {}
    }

    protected Answer listFilesAtPath(String nfsMountPoint, String relativePath, int startIndex, int pageSize, String keyword) {
        int count = 0;
        File file = new File(nfsMountPoint, relativePath);
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> absPaths = new ArrayList<>();
        List<Boolean> isDirs = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        List<Long> modifiedList = new ArrayList<>();
        if (file.isFile()) {
            count = 1;
            names.add(file.getName());
            paths.add(file.getPath().replace(nfsMountPoint, ""));
            absPaths.add(file.getPath());
            isDirs.add(file.isDirectory());
            sizes.add(file.length());
            modifiedList.add(file.lastModified());
        } else if (file.isDirectory()) {
            String[] files = file.list();
            List<String> filteredFiles = new ArrayList<>();
            if (keyword != null && !"".equals(keyword)) {
                for (String fileName : files) {
                    if (fileName.contains(keyword)) {
                        filteredFiles.add(fileName);
                    }
                }
            } else {
                filteredFiles.addAll(Arrays.asList(files));
            }
            count = filteredFiles.size();
            for (int i = startIndex; i < startIndex + pageSize && i < count; i++) {
                File f = new File(nfsMountPoint, relativePath + '/' + filteredFiles.get(i));
                names.add(f.getName());
                paths.add(f.getPath().replace(nfsMountPoint, ""));
                absPaths.add(f.getPath());
                isDirs.add(f.isDirectory());
                sizes.add(f.length());
                modifiedList.add(f.lastModified());
            }
        }
        return new ListDataStoreObjectsAnswer(file.exists(), count, names, paths, absPaths, isDirs, sizes, modifiedList);
    }

    protected Answer listFilesAtPath(String nfsMountPoint, String relativePath, int startIndex, int pageSize) {
        int count = 0;
        File file = new File(nfsMountPoint, relativePath);
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> absPaths = new ArrayList<>();
        List<Boolean> isDirs = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        List<Long> modifiedList = new ArrayList<>();
        if (file.isFile()) {
            count = 1;
            names.add(file.getName());
            paths.add(file.getPath().replace(nfsMountPoint, ""));
            absPaths.add(file.getPath());
            isDirs.add(file.isDirectory());
            sizes.add(file.length());
            modifiedList.add(file.lastModified());
        } else if (file.isDirectory()) {
            String[] files = file.list();
            count = files.length;
            for (int i = startIndex; i < startIndex + pageSize && i < count; i++) {
                File f = new File(nfsMountPoint, relativePath + '/' + files[i]);
                names.add(f.getName());
                paths.add(f.getPath().replace(nfsMountPoint, ""));
                absPaths.add(f.getPath());
                isDirs.add(f.isDirectory());
                sizes.add(f.length());
                modifiedList.add(f.lastModified());
            }
        }
        return new ListDataStoreObjectsAnswer(file.exists(), count, names, paths, absPaths, isDirs, sizes, modifiedList);
    }

    protected void fillNetworkInformation(final StartupCommand cmd) {
        String[] info = null;
        if (privateNic != null) {
            info = NetUtils.getNetworkParams(privateNic);
            if (info != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Parameters for private nic: " + info[0] + " - " + info[1] + "-" + info[2]);
                }
                cmd.setPrivateIpAddress(info[0]);
                cmd.setPrivateMacAddress(info[1]);
                cmd.setPrivateNetmask(info[2]);
            }
        }

        if (storageNic != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Storage has its now nic: " + storageNic.getName());
            }
            info = NetUtils.getNetworkParams(storageNic);
        }

        // NOTE: In case you're wondering, this is not here by mistake.
        if (info != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Parameters for storage nic: " + info[0] + " - " + info[1] + "-" + info[2]);
            }
            cmd.setStorageIpAddress(info[0]);
            cmd.setStorageMacAddress(info[1]);
            cmd.setStorageNetmask(info[2]);
        }

        if (publicNic != null) {
            info = NetUtils.getNetworkParams(publicNic);
            if (info != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Parameters for public nic: " + info[0] + " - " + info[1] + "-" + info[2]);
                }
                cmd.setPublicIpAddress(info[0]);
                cmd.setPublicMacAddress(info[1]);
                cmd.setPublicNetmask(info[2]);
            }
        }

        if (storageNic2 != null) {
            info = NetUtils.getNetworkParams(storageNic2);
            if (info != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Parameters for storage nic 2: " + info[0] + " - " + info[1] + "-" + info[2]);
                }
                cmd.setStorageIpAddressDeux(info[0]);
                cmd.setStorageMacAddressDeux(info[1]);
                cmd.setStorageNetmaskDeux(info[2]);
            }
        }
    }

    @Override
    public void disconnected() {
    }

    @Override
    public IAgentControl getAgentControl() {
        return agentControl;
    }

    @Override
    public void setAgentControl(IAgentControl agentControl) {
        this.agentControl = agentControl;
    }

    protected void recordWarning(final String msg, final Throwable th) {
        final String str = getLogStr(msg, th);
        synchronized (warnings) {
            warnings.add(str);
        }
    }

    protected void recordWarning(final String msg) {
        recordWarning(msg, null);
    }

    protected List<String> getWarnings() {
        synchronized (warnings) {
            final List<String> results = new LinkedList<String>(warnings);
            warnings.clear();
            return results;
        }
    }

    protected List<String> getErrors() {
        synchronized (errors) {
            final List<String> result = new LinkedList<String>(errors);
            errors.clear();
            return result;
        }
    }

    protected void recordError(final String msg, final Throwable th) {
        final String str = getLogStr(msg, th);
        synchronized (errors) {
            errors.add(str);
        }
    }

    protected void recordError(final String msg) {
        recordError(msg, null);
    }

    protected Answer createErrorAnswer(final Command cmd, final String msg, final Throwable th) {
        final StringWriter writer = new StringWriter();
        if (msg != null) {
            writer.append(msg);
        }
        writer.append("===>Stack<===");
        th.printStackTrace(new PrintWriter(writer));
        return new Answer(cmd, false, writer.toString());
    }

    protected String createErrorDetail(final String msg, final Throwable th) {
        final StringWriter writer = new StringWriter();
        if (msg != null) {
            writer.append(msg);
        }
        writer.append("===>Stack<===");
        th.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    protected String getLogStr(final String msg, final Throwable th) {
        final StringWriter writer = new StringWriter();
        writer.append(new Date().toString()).append(": ").append(msg);
        if (th != null) {
            writer.append("\n  Exception: ");
            th.printStackTrace(new PrintWriter(writer));
        }
        return writer.toString();
    }

    @Override
    public boolean start() {
        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected Answer updateHostUsbDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        String usbXmlPath = String.format("/tmp/usb_device_%s.xml", vmName);
        try {
            try (PrintWriter writer = new PrintWriter(usbXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, usbXmlPath);
            } else {
                virshCmd.add("detach-device", vmName, usbXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                String lower = result.toLowerCase();
                if (!isAttach && (lower.contains("device not found") || lower.contains("not found"))) {
                    return new UpdateHostUsbDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                logger.error("Failed to {} USB device: {}", action, result);
                return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostUsbDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }
    protected Answer updateHostLunDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        String hostDeviceName = null;
        if (command instanceof UpdateHostLunDeviceCommand) {
            UpdateHostLunDeviceCommand lunCmd = (UpdateHostLunDeviceCommand) command;
            hostDeviceName = lunCmd.getHostDeviceName();
        }

        String lunDeviceName = extractDeviceNameFromLunXml(xmlConfig);
        if (lunDeviceName == null) {
            lunDeviceName = "unknown";
        }
        String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, lunDeviceName);
        try {
            String devicePath = extractDevicePathFromLunXml(xmlConfig);

            if (devicePath != null && isAttach) {
                if (isDeviceAllocatedInOtherType(devicePath, vmName, "LUN")) {
                    return new UpdateHostLunDeviceAnswer(false, "Device is already allocated as SCSI device in another VM. Please remove it from SCSI allocation first.");
                }

                try {
                    if (devicePath.startsWith("/dev/disk/by-id/") && !isDeviceExists(devicePath)) {

                        String baseGuess = null;
                        if (hostDeviceName != null && !hostDeviceName.isEmpty()) {
                            Pattern hostNamePattern = Pattern.compile("(/dev/\\S+)");
                            Matcher hostNameMatcher = hostNamePattern.matcher(hostDeviceName);
                            if (hostNameMatcher.find()) {
                                baseGuess = hostNameMatcher.group(1);
                            }
                        }

                        if (baseGuess == null) {
                            Pattern patternName = Pattern.compile("(/dev/[^<\\n]+) \\\\(");
                            Matcher mName = patternName.matcher(xmlConfig);
                            if (mName.find()) {
                                baseGuess = mName.group(1);
                            }
                        }
                        if (baseGuess == null) {
                            Matcher mBase = Pattern.compile("(/dev/\\S+)").matcher(xmlConfig);
                            if (mBase.find()) {
                                baseGuess = mBase.group(1);
                            }
                        }
                        if (baseGuess == null) {
                            Matcher mFallback = Pattern.compile("<!-- Fallback device path: (/dev/\\S+) -->").matcher(xmlConfig);
                            if (mFallback.find()) {
                                baseGuess = mFallback.group(1);
                            }
                        }

                        if (baseGuess != null) {
                            String altById = findExistingByIdForBaseDevice(baseGuess);
                            if (altById != null) {
                                xmlConfig = xmlConfig.replace(devicePath, altById);
                                devicePath = altById;
                            } else {
                                logger.warn("No alternative by-id found for base: {}", baseGuess);
                            }
                        } else {
                            logger.warn("Could not extract baseGuess from XML config");
                        }

                    }
                } catch (Exception e) {
                    logger.error("Error in fallback logic: {}", e.getMessage(), e);
                }

                String validationResult = validateLunDeviceForAttachment(devicePath, vmName);
                if (validationResult != null) {
                    return new UpdateHostLunDeviceAnswer(false, validationResult);
                }
            }

            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);
            String mappedScsiDevice = null;
            if (mapping != null && mapping.getScsiDevicePath() != null) {
                mappedScsiDevice = mapping.getScsiDevicePath();
            }

            xmlConfig = ensureUniqueLunTargetDev(vmName, xmlConfig);

            try (PrintWriter writer = new PrintWriter(lunXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, lunXmlPath);
            } else {
                if (!isLunDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, lunXmlPath);
            }

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} LUN device: {}", action, result);
                return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach, result);
            }

            if (mappedScsiDevice != null) {
                try {
                    String scsiXmlConfig = generateScsiXmlFromLunXml(xmlConfig, mappedScsiDevice);
                    String scsiXmlPath = String.format("/tmp/scsi_device_%s_%s.xml", vmName, lunDeviceName);

                    try (PrintWriter writer = new PrintWriter(scsiXmlPath)) {
                        writer.write(scsiXmlConfig);
                    }

                    Script scsiVirshCmd = new Script("virsh");
                    if (isAttach) {
                        scsiVirshCmd.add("attach-device", vmName, scsiXmlPath);
                    } else {
                        scsiVirshCmd.add("detach-device", vmName, scsiXmlPath);
                    }

                    String scsiResult = scsiVirshCmd.execute();
                    if (scsiResult != null) {
                        logger.warn("Failed to {} mapped SCSI device {}: {}",
                                   isAttach ? "attach" : "detach", mappedScsiDevice, scsiResult);
                    } else {
                    }

                    // 임시 파일 정리
                    new File(scsiXmlPath).delete();

                } catch (Exception e) {
                        logger.warn("Error processing mapped SCSI device {}: {}", mappedScsiDevice, e.getMessage());
                }
            }

            return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach, e.getMessage());
        }
    }

    protected Answer updateHostHbaDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        String hbaDeviceName = extractAdapterNameFromXml(xmlConfig);
        if (hbaDeviceName == null) {
            hbaDeviceName = "unknown";
        }
        String hbaXmlPath = String.format("/tmp/hba_device_%s_%s.xml", vmName, hbaDeviceName);
        try {
            try (PrintWriter writer = new PrintWriter(hbaXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, hbaXmlPath);
            } else {
                if (!isDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    return new UpdateHostHbaDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, hbaXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} HBA device: {}", action, result);
                return new UpdateHostHbaDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostHbaDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            return new UpdateHostHbaDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }

        protected Answer updateHostVHbaDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        String vhbaDeviceName = extractDeviceNameFromVhbaXml(xmlConfig);
        if (vhbaDeviceName == null) {
            vhbaDeviceName = "unknown";
        }
        String vhbaXmlPath = String.format("/tmp/vhba_device_%s_%s.xml", vmName, vhbaDeviceName);

        try {
            try (PrintWriter writer = new PrintWriter(vhbaXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, vhbaXmlPath);
            } else {
                if (!isVhbaDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    return new UpdateHostVhbaDeviceAnswer(true, vhbaDeviceName, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, vhbaXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} vHBA device: {}", action, result);
                return new UpdateHostVhbaDeviceAnswer(false, vhbaDeviceName, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostVhbaDeviceAnswer(true, vhbaDeviceName, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            return new UpdateHostVhbaDeviceAnswer(false, vhbaDeviceName, vmName, xmlConfig, isAttach);
        }
    }

    protected Answer updateHostScsiDevices(UpdateHostScsiDeviceCommand command, String vmName, String xmlConfig, boolean isAttach) {
        String scsiDeviceName = extractDeviceNameFromScsiXml(xmlConfig);
        if (scsiDeviceName == null) {
            scsiDeviceName = "unknown";
        }
        String scsiXmlPath = String.format("/tmp/scsi_device_%s_%s.xml", vmName, scsiDeviceName);
        try {
            String devicePath = extractDeviceNameFromScsiXml(xmlConfig);

            if (isAttach && isDeviceAllocatedInOtherType(devicePath, vmName, "SCSI")) {
                return new UpdateHostScsiDeviceAnswer(false, "Device is already allocated as LUN device in another VM. Please remove it from LUN allocation first.");
            }

            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);
            String mappedLunDevice = null;
            if (mapping != null && mapping.getLunDevicePath() != null) {
                mappedLunDevice = mapping.getLunDevicePath();
            }

            try (PrintWriter writer = new PrintWriter(scsiXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, scsiXmlPath);
            } else {
                if (!isScsiDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, scsiXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                String lower = result.toLowerCase();
                if (!isAttach && (lower.contains("device not found") || lower.contains("host scsi device") && lower.contains("not found"))) {
                    return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                logger.error("Failed to {} SCSI device: {}", action, result);
                return new UpdateHostScsiDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            if (mappedLunDevice != null) {
                try {
                    String lunXmlConfig = generateLunXmlFromScsiXml(xmlConfig, mappedLunDevice);
                    String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, scsiDeviceName);

                    try (PrintWriter writer = new PrintWriter(lunXmlPath)) {
                        writer.write(lunXmlConfig);
                    }

                    Script lunVirshCmd = new Script("virsh");
                    if (isAttach) {
                        lunVirshCmd.add("attach-device", vmName, lunXmlPath);
                    } else {
                        lunVirshCmd.add("detach-device", vmName, lunXmlPath);
                    }

                    String lunResult = lunVirshCmd.execute();
                    if (lunResult != null) {
                    } else {
                    }

                    // 임시 파일 정리
                    new File(lunXmlPath).delete();

                } catch (Exception e) {
                    logger.warn("Error processing mapped LUN device {}: {}", mappedLunDevice, e.getMessage());
                }
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            return new UpdateHostScsiDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }

    private boolean isDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            String hbaDeviceName = extractAdapterNameFromXml(xmlConfig);
            if (hbaDeviceName == null) {
                hbaDeviceName = "unknown";
            }
            String hbaXmlPath = String.format("/tmp/hba_device_%s_%s.xml", vmName, hbaDeviceName);

            File xmlFile = new File(hbaXmlPath);
            if (!xmlFile.exists()) {
                return false;
            }

            // virsh dumpxml로 VM의 현재 상태 확인
            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                return false;
            }

            String adapterName = extractAdapterNameFromXml(xmlConfig);
            if (adapterName == null) {
                return false;
            }

            boolean deviceFound = vmXml.contains("<adapter name='" + adapterName + "'/>") ||
                                vmXml.contains("<adapter name=\"" + adapterName + "\"/>");
            return deviceFound;

        } catch (Exception e) {
            return false;
        }
    }

    private String extractAdapterNameFromXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("name=['\"]([^'\"]+)['\"]");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDeviceNameFromLunXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("dev=['\"]([^'\"]+)['\"]");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                String devicePath = matcher.group(1);
                String[] parts = devicePath.split("/");
                if (parts.length > 0) {
                    return parts[parts.length - 1];
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDevicePathFromLunXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("dev=['\"]([^'\"]+)['\"]");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractUuidFromLunXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("<serial>([^<]+)</serial>");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String validateLunDeviceForAttachment(String devicePath, String vmName) {
        try {
            if (!isDeviceExists(devicePath)) {
                return "디바이스가 존재하지 않습니다: " + devicePath;
            }

            if (isPartitionDevice(devicePath)) {
                return "파티션 디바이스는 할당할 수 없습니다. 전체 디스크를 사용해주세요: " + devicePath;
            }

            if (isLunDeviceAllocatedToOtherVm(devicePath, vmName)) {
                return "디바이스가 이미 다른 VM에 할당되어 있습니다: " + devicePath;
            }

            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);
            if (mapping != null && mapping.getScsiDevicePath() != null) {
                if (isLunDeviceAllocatedToOtherVm(mapping.getScsiDevicePath(), vmName)) {
                    return "매핑된 SCSI 디바이스가 이미 다른 VM에 할당되어 있습니다: " + mapping.getScsiDevicePath();
                }
            }

            if (isDeviceMounted(devicePath)) {
                return "디바이스가 마운트되어 있어 할당할 수 없습니다: " + devicePath;
            }

            if (isDeviceUsedByLvm(devicePath)) {
                return "디바이스가 LVM에서 사용 중이어 할당할 수 없습니다: " + devicePath;
            }

            if (isDeviceUsedAsSwap(devicePath)) {
                return "디바이스가 스왑으로 사용 중이어 할당할 수 없습니다: " + devicePath;
            }

            if (hasPartitionTable(devicePath)) {
                return "디스크에 파티션 테이블이 있어 할당할 수 없습니다. 파티션을 삭제한 후 시도해주세요: " + devicePath;
            }

            if (isDeviceReadOnly(devicePath)) {
                return "디바이스가 읽기 전용이어 할당할 수 없습니다: " + devicePath;
            }

            if (getDeviceSizeBytes(devicePath) < 1024 * 1024) {
                return "디바이스 크기가 너무 작아 할당할 수 없습니다 (최소 1MB 필요): " + devicePath;
            }

            return null;
        } catch (Exception e) {
            return "디바이스 검증 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    private boolean isDeviceExists(String devicePath) {
        if (devicePath != null && devicePath.startsWith("/dev/disk/by-id/")) {
            return true;
        }

        try {
            File device = new File(devicePath);
            if (!device.exists()) {
                return false;
            }

            // 심볼릭 링크인 경우 실제 타겟 확인
            if (Files.isSymbolicLink(device.toPath())) {
                try {
                    Path realPath = device.toPath().toRealPath();
                    File realDevice = realPath.toFile();
                    if (!realDevice.exists()) {
                        return false;
                    }
                } catch (Exception e) {
                    return false;
                }
            }

            Script statCommand = new Script("/bin/bash");
            statCommand.add("-c");
            statCommand.add("stat -c '%F' " + devicePath + " 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = statCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String fileType = parser.getLines().trim();
                return fileType.contains("block special file");
            }

            return device.isFile();
        } catch (Exception e) {
            logger.debug("Error checking device existence {}: {}", devicePath, e.getMessage());
            return false;
        }
    }

    private boolean isPartitionDevice(String devicePath) {
        try {
            String deviceName = new File(devicePath).getName();

            if (deviceName.matches(".*\\d+$")) {
                if (deviceName.startsWith("nvme")) {
                    return deviceName.contains("p") && deviceName.matches(".*p\\d+$");
                }
                return deviceName.matches("(sd|vd|xvd|hd).*\\d+$");
            }

            return false;
        } catch (Exception e) {
            logger.debug("Error checking partition device: " + e.getMessage());
            return false;
        }
    }

    private boolean isLunDeviceAllocatedToOtherVm(String devicePath, String currentVmName) {
        try {
            Script listCommand = new Script("/bin/bash");
            listCommand.add("-c");
            listCommand.add("virsh list --all | grep -v 'Id' | grep -v '^-' | while read line; do " +
                           "vm_id=$(echo $line | awk '{print $1}'); " +
                           "if [ ! -z \"$vm_id\" ] && [ \"$vm_id\" != \"" + currentVmName + "\" ]; then " +
                           "virsh dumpxml $vm_id | grep -q '" + devicePath + "'; " +
                           "if [ $? -eq 0 ]; then " +
                           "echo 'allocated'; " +
                           "break; " +
                           "fi; " +
                           "fi; " +
                           "done");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = listCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                return parser.getLines().trim().equals("allocated");
            }
            return false;
        } catch (Exception e) {
            logger.debug("Error checking device allocation to other VMs: {}", e.getMessage());
            return false;
        }
    }

    private boolean isDeviceMounted(String devicePath) {
        try {
            Script mountCommand = new Script("/bin/bash");
            mountCommand.add("-c");
            mountCommand.add("mount | grep -q '^" + devicePath + "'");
            String result = mountCommand.execute(null);
            return result == null; // 명령어가 성공하면 마운트됨
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDeviceUsedByLvm(String devicePath) {
        try {
            Script lvmCommand = new Script("/bin/bash");
            lvmCommand.add("-c");
            lvmCommand.add("pvs " + devicePath + " 2>/dev/null | grep -q " + devicePath);
            String result = lvmCommand.execute(null);
            return result == null; // 명령어가 성공하면 LVM에서 사용 중
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDeviceUsedAsSwap(String devicePath) {
        try {
            Script swapCommand = new Script("/bin/bash");
            swapCommand.add("-c");
            swapCommand.add("swapon --show | grep -q '" + devicePath + "'");
            String result = swapCommand.execute(null);
            return result == null; // 명령어가 성공하면 스왑으로 사용 중
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasPartitionTable(String devicePath) {
        try {
            Script partCommand = new Script("/bin/bash");
            partCommand.add("-c");
            partCommand.add("fdisk -l " + devicePath + " 2>/dev/null | grep -q 'Disklabel type:'");
            String result = partCommand.execute(null);
            return result == null; // 명령어가 성공하면 파티션 테이블 존재
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDeviceReadOnly(String devicePath) {
        try {
            Script roCommand = new Script("/bin/bash");
            roCommand.add("-c");
            roCommand.add("blockdev --getro " + devicePath + " 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = roCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String roValue = parser.getLines().trim();
                return "1".equals(roValue);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private long getDeviceSizeBytes(String devicePath) {
        try {
            Script sizeCommand = new Script("/bin/bash");
            sizeCommand.add("-c");
            sizeCommand.add("blockdev --getsize64 " + devicePath + " 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = sizeCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String sizeStr = parser.getLines().trim();
                return Long.parseLong(sizeStr);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isLunDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            String lunDeviceName = extractDeviceNameFromLunXml(xmlConfig);
            if (lunDeviceName == null) {
                lunDeviceName = "unknown";
            }
            String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, lunDeviceName);

            File xmlFile = new File(lunXmlPath);
            if (!xmlFile.exists()) {
                logger.warn("XML file does not exist for device check: {}", lunXmlPath);
                return false;
            }

            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                logger.warn("Failed to get VM XML for device check: {}", result);
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                logger.warn("Empty VM XML for device check");
                return false;
            }

            String sourceDev = extractDeviceNameFromLunXml(xmlConfig);
            if (sourceDev == null) {
                logger.warn("Could not extract source dev from XML config");
                return false;
            }

            boolean deviceFound = vmXml.contains("dev='" + sourceDev + "'") ||
                                vmXml.contains("dev=\"" + sourceDev + "\"");
            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking LUN device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    private String extractDeviceNameFromScsiXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("name=['\"]([^'\"]+)['\"]");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isScsiDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            String scsiDeviceName = extractDeviceNameFromScsiXml(xmlConfig);
            if (scsiDeviceName == null) {
                scsiDeviceName = "unknown";
            }
            String scsiXmlPath = String.format("/tmp/scsi_device_%s_%s.xml", vmName, scsiDeviceName);

            File xmlFile = new File(scsiXmlPath);
            if (!xmlFile.exists()) {
                return false;
            }

            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                return false;
            }

            String adapterName = extractDeviceNameFromScsiXml(xmlConfig);
            if (adapterName == null) {
                return false;
            }

            // VM XML에 해당 adapter가 있는지 확인
            boolean deviceFound = vmXml.contains("<adapter name='" + adapterName + "'/>") ||
                                vmXml.contains("<adapter name=\"" + adapterName + "\"/>");

            return deviceFound;

        } catch (Exception e) {
            return false;
        }
    }

    private String extractDeviceNameFromVhbaXml(String xmlConfig) {
        try {
            Pattern parentPattern = Pattern.compile("<parent>([^<]+)</parent>");
            Matcher parentMatcher = parentPattern.matcher(xmlConfig);
            if (parentMatcher.find()) {
                return parentMatcher.group(1);
            }

            Pattern adapterPattern = Pattern.compile("<adapter\\s+name=['\"]([^'\"]+)['\"][^>]*?>");
            Matcher adapterMatcher = adapterPattern.matcher(xmlConfig);
            if (adapterMatcher.find()) {
                return adapterMatcher.group(1);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isUsbDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                return false;
            }

            String busMatch = extractBusFromUsbXml(xmlConfig);
            String deviceMatch = extractDeviceFromUsbXml(xmlConfig);

            if (busMatch == null || deviceMatch == null) {
                return false;
            }
            boolean deviceFound = vmXml.contains("bus='" + busMatch + "'") &&
                                  vmXml.contains("device='" + deviceMatch + "'");

            return deviceFound;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVhbaDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            String vhbaDeviceName = extractDeviceNameFromVhbaXml(xmlConfig);
            if (vhbaDeviceName == null) {
                vhbaDeviceName = "unknown";
            }
            String vhbaXmlPath = String.format("/tmp/vhba_device_%s_%s.xml", vmName, vhbaDeviceName);

            File xmlFile = new File(vhbaXmlPath);
            if (!xmlFile.exists()) {
                return false;
            }

            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                return false;
            }

            String targetName = extractDeviceNameFromVhbaXml(xmlConfig);
            if (targetName == null) {
                return false;
            }

            boolean deviceFound = vmXml.contains("<adapter name='" + targetName + "'/>") ||
                                  vmXml.contains("<adapter name=\"" + targetName + "\"/>") ||
                                  vmXml.contains("<parent>" + targetName + "</parent>");

            return deviceFound;

        } catch (Exception e) {
            return false;
        }
    }
    private String extractBusFromUsbXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("bus='(0x[0-9A-Fa-f]+)'");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractDeviceFromUsbXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("device='(0x[0-9A-Fa-f]+)'");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractWwnnFromXml(String xmlContent) {
        try {
            String[] patterns = {
                "wwnn=['\"]([0-9A-Fa-f]{16})['\"]",
                "<wwnn>([0-9A-Fa-f]{16})</wwnn>",
                "wwnn=\"([0-9A-Fa-f]{16})\""
            };

            for (String pattern : patterns) {
                Pattern p = Pattern.compile(pattern);
                Matcher m = p.matcher(xmlContent);
                if (m.find()) {
                    return m.group(1);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Answer listVhbaBackups() {

        try {
            File vhbaDir = new File("/etc/vhba");
            if (!vhbaDir.exists()) {
                return new Answer(null, true, "백업 파일이 없습니다");
            }

            File[] backupFiles = vhbaDir.listFiles((dir, name) -> name.endsWith(".xml"));
            if (backupFiles == null || backupFiles.length == 0) {
                return new Answer(null, true, "백업 파일이 없습니다");
            }

            List<String> backupList = new ArrayList<>();
            for (File file : backupFiles) {
                String vhbaName = file.getName().replace(".xml", "");
                backupList.add(vhbaName);
            }

            return new Answer(null, true, "vHBA 백업 파일 목록: " + String.join(", ", backupList));

        } catch (Exception e) {
            return new Answer(null, false, "vHBA 백업 파일 목록 조회 중 오류: " + e.getMessage());
        }
    }

    private String getVhbaDumpXml(String vhbaName) {
        try {
            Script dumpCommand = new Script("/bin/bash");
            dumpCommand.add("-c");
            dumpCommand.add("virsh nodedev-dumpxml " + vhbaName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String xmlContent = parser.getLines();
                return xmlContent;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static class DeviceMapping {
        private String lunDevicePath;
        private String scsiDevicePath;
        private String scsiAddress;
        private String physicalDevicePath;

        public DeviceMapping(String lunDevicePath, String scsiDevicePath, String scsiAddress, String physicalDevicePath) {
            this.lunDevicePath = lunDevicePath;
            this.scsiDevicePath = scsiDevicePath;
            this.scsiAddress = scsiAddress;
            this.physicalDevicePath = physicalDevicePath;
        }

        public String getLunDevicePath() { return lunDevicePath; }
        public String getScsiDevicePath() { return scsiDevicePath; }
        public String getScsiAddress() { return scsiAddress; }
        public String getPhysicalDevicePath() { return physicalDevicePath; }

        public boolean isSamePhysicalDevice(DeviceMapping other) {
            return this.physicalDevicePath != null &&
                   other.physicalDevicePath != null &&
                   this.physicalDevicePath.equals(other.physicalDevicePath);
        }

        public boolean isSameScsiAddress(DeviceMapping other) {
            return this.scsiAddress != null &&
                   other.scsiAddress != null &&
                   this.scsiAddress.equals(other.scsiAddress);
        }
    }

    protected Map<String, DeviceMapping> buildDeviceMapping() {
        Map<String, DeviceMapping> deviceMappings = new HashMap<>();

        try {

            ListHostLunDeviceAnswer lunAnswer = listHostLunDevicesFast();
            if (lunAnswer != null && lunAnswer.getResult()) {
                List<String> lunNames = lunAnswer.getHostDevicesNames();
                List<String> lunScsiAddresses = lunAnswer.getScsiAddresses();

                for (int i = 0; i < lunNames.size(); i++) {
                    String lunDevice = lunNames.get(i);
                    String scsiAddress = lunScsiAddresses.get(i);

                    if (scsiAddress != null && !scsiAddress.isEmpty()) {

                        String physicalPath = resolvePhysicalDeviceFromScsiAddress(scsiAddress);
                        DeviceMapping mapping = new DeviceMapping(lunDevice, null, scsiAddress, physicalPath);
                        deviceMappings.put(lunDevice, mapping);
                    }
                }
            }


            ListHostScsiDeviceAnswer scsiAnswer = listHostScsiDevicesFast();
            if (scsiAnswer != null && scsiAnswer.getResult()) {
                List<String> scsiNames = scsiAnswer.getHostDevicesNames();

                for (String scsiDevice : scsiNames) {
                    String scsiAddress = getScsiAddress(scsiDevice);
                    if (scsiAddress != null && !scsiAddress.isEmpty()) {
                        String physicalPath = resolvePhysicalDeviceFromScsiAddress(scsiAddress);

                        DeviceMapping existingMapping = findMappingByPhysicalPath(deviceMappings, physicalPath);
                        if (existingMapping != null) {
                            existingMapping = new DeviceMapping(existingMapping.getLunDevicePath(),
                                                               scsiDevice,
                                                               scsiAddress,
                                                               physicalPath);
                            deviceMappings.put(existingMapping.getLunDevicePath(), existingMapping);
                        } else {
                            DeviceMapping mapping = new DeviceMapping(null, scsiDevice, scsiAddress, physicalPath);
                            deviceMappings.put(scsiDevice, mapping);
                        }
                    }
                }
            }

            logger.debug("Built device mapping with " + deviceMappings.size() + " entries");

        } catch (Exception e) {
            logger.error("Error building device mapping: " + e.getMessage(), e);
        }

        return deviceMappings;
    }

    private String resolvePhysicalDeviceFromScsiAddress(String scsiAddress) {
        try {
            File scsiClassDir = new File("/sys/class/scsi_device");
            File[] scsiDevices = scsiClassDir.listFiles();

            if (scsiDevices != null) {
                for (File scsiDevice : scsiDevices) {
                    String deviceName = scsiDevice.getName();
                    if (deviceName.equals(scsiAddress)) {
                        File blockDir = new File(scsiDevice, "device/block");
                        if (blockDir.exists()) {
                            File[] blockDevices = blockDir.listFiles();
                            if (blockDevices != null && blockDevices.length > 0) {
                                String blockDeviceName = blockDevices[0].getName();
                                return "/dev/" + blockDeviceName;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        return null;
    }

    private DeviceMapping findMappingByPhysicalPath(Map<String, DeviceMapping> mappings, String physicalPath) {
        if (physicalPath == null) return null;

        for (DeviceMapping mapping : mappings.values()) {
            if (physicalPath.equals(mapping.getPhysicalDevicePath())) {
                return mapping;
            }
        }
        return null;
    }


    protected DeviceMapping findMappedDevice(String devicePath, Map<String, DeviceMapping> mappings) {

        DeviceMapping directMapping = mappings.get(devicePath);
        if (directMapping != null) {
            return directMapping;
        }

        String physicalPath = resolvePhysicalDeviceFromScsiAddress(getScsiAddress(devicePath));
        if (physicalPath != null) {
            return findMappingByPhysicalPath(mappings, physicalPath);
        }

        return null;
    }

    protected boolean isDeviceAllocatedInOtherType(String devicePath, String currentVmName, String deviceType) {
        try {
            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);

            if (mapping == null) {
                return false;
            }

            if ("LUN".equals(deviceType) && mapping.getScsiDevicePath() != null) {
                return isScsiDeviceAllocatedToVm(mapping.getScsiDevicePath(), currentVmName);
            }

            if ("SCSI".equals(deviceType) && mapping.getLunDevicePath() != null) {
                return isLunDeviceAllocatedToVm(mapping.getLunDevicePath(), currentVmName);
            }

            return false;

        } catch (Exception e) {

            return false;
        }
    }

    private boolean isScsiDeviceAllocatedToVm(String scsiDevicePath, String currentVmName) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("find /var/lib/libvirt/qemu/ -name '*.xml' -exec grep -l '" + scsiDevicePath + "' {} \\; 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String[] xmlFiles = parser.getLines().trim().split("\n");
                for (String xmlFile : xmlFiles) {
                    if (xmlFile.contains(currentVmName)) {
                        continue;
                    }

                    if (isScsiDeviceInXml(xmlFile, scsiDevicePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * LUN 디바이스가 다른 VM에 할당되어 있는지 확인
     */
    private boolean isLunDeviceAllocatedToVm(String lunDevicePath, String currentVmName) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("find /var/lib/libvirt/qemu/ -name '*.xml' -exec grep -l '" + lunDevicePath + "' {} \\; 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String[] xmlFiles = parser.getLines().trim().split("\n");
                for (String xmlFile : xmlFiles) {
                    if (xmlFile.contains(currentVmName)) {
                        continue; // 현재 VM은 제외
                    }

                    if (isLunDeviceInXml(xmlFile, lunDevicePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            logger.debug("Error checking LUN device allocation: " + e.getMessage());
            return false;
        }
    }

    /**
     * XML 파일에서 SCSI 디바이스가 할당되어 있는지 확인
     */
    private boolean isScsiDeviceInXml(String xmlFilePath, String scsiDevicePath) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("grep -q '<hostdev.*type=\"scsi\".*" + scsiDevicePath + "' '" + xmlFilePath + "' 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);
            return result == null; // grep이 성공하면 (디바이스 발견) true 반환
        } catch (Exception e) {
            logger.debug("Error checking SCSI device in XML: " + e.getMessage());
            return false;
        }
    }

    /**
     * XML 파일에서 LUN 디바이스가 할당되어 있는지 확인
     */
    private boolean isLunDeviceInXml(String xmlFilePath, String lunDevicePath) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("grep -q '<disk.*device=\"lun\".*" + lunDevicePath + "' '" + xmlFilePath + "' 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);
            return result == null; // grep이 성공하면 (디바이스 발견) true 반환
        } catch (Exception e) {
            logger.debug("Error checking LUN device in XML: " + e.getMessage());
            return false;
        }
    }

    /**
     * LUN XML에서 SCSI XML을 생성하는 메서드
     */
    private String generateScsiXmlFromLunXml(String lunXmlConfig, String scsiDevicePath) {
        try {
            // LUN XML에서 기본 구조 추출
            String lunDevicePath = extractDevicePathFromLunXml(lunXmlConfig);
            if (lunDevicePath == null) {
                return null;
            }

            // SCSI 주소 추출
            String scsiAddress = getScsiAddress(scsiDevicePath);
            if (scsiAddress == null) {
                return null;
            }

            StringBuilder scsiXml = new StringBuilder();
            scsiXml.append("<hostdev mode='subsystem' type='scsi' managed='yes'>\n");
            scsiXml.append("  <source>\n");
            scsiXml.append("    <adapter name='scsi_host0'/>\n");
            scsiXml.append("    <address bus='0' target='").append(scsiAddress.split(":")[2]).append("' unit='").append(scsiAddress.split(":")[3]).append("'/>\n");
            scsiXml.append("  </source>\n");
            scsiXml.append("  <alias name='scsi-").append(scsiDevicePath.replace("/dev/", "")).append("'/>\n");
            scsiXml.append("  <address type='drive' controller='0' bus='0' target='0' unit='0'/>\n");
            scsiXml.append("</hostdev>");

            return scsiXml.toString();

        } catch (Exception e) {
            logger.error("Error generating SCSI XML from LUN XML: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * SCSI XML에서 LUN XML을 생성하는 메서드
     */
    private String generateLunXmlFromScsiXml(String scsiXmlConfig, String lunDevicePath) {
        try {
            String scsiDeviceName = extractDeviceNameFromScsiXml(scsiXmlConfig);
            if (scsiDeviceName == null) {
                return null;
            }

            StringBuilder lunXml = new StringBuilder();
            lunXml.append("<disk type='block' device='disk'>\n");
            lunXml.append("  <driver name='qemu' type='raw' cache='none'/>\n");
            lunXml.append("  <source dev='").append(lunDevicePath).append("'/>\n");
            lunXml.append("  <target dev='").append(scsiDeviceName.replace("/dev/", "")).append("' bus='scsi'/>\n");
            lunXml.append("  <alias name='scsi-").append(scsiDeviceName.replace("/dev/", "")).append("'/>\n");
            lunXml.append("  <address type='drive' controller='0' bus='0' target='0' unit='0'/>\n");
            lunXml.append("</disk>");

            return lunXml.toString();

        } catch (Exception e) {
            logger.error("Error generating LUN XML from SCSI XML: " + e.getMessage(), e);
            return null;
        }
    }

    private Set<String> getUsedScsiTargetDevs(String vmName) {
        HashSet<String> used = new HashSet<>();
        try {
            Script virshDump = new Script("virsh");
            virshDump.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String res = virshDump.execute(parser);
            if (res == null) {
                String xml = parser.getLines();
                if (xml != null) {
                    Matcher m = Pattern.compile("<target\\s+dev='(sd[a-z]+)'\\s+bus='scsi'").matcher(xml);
                    while (m.find()) {
                        used.add(m.group(1));
                    }
                }
            }
        } catch (Exception e) {
        }
        return used;
    }

    private String pickAvailableScsiTargetDev(Set<String> used) {
        // Prefer from sdc to sdz, then sdaa..sdaz
        for (char c = 'c'; c <= 'z'; c++) {
            String dev = "sd" + c;
            if (!used.contains(dev)) return dev;
        }
        for (char c1 = 'a'; c1 <= 'z'; c1++) {
            for (char c2 = 'a'; c2 <= 'z'; c2++) {
                String dev = "sd" + c1 + c2;
                if (!used.contains(dev)) return dev;
            }
        }
        return "sdz"; // Fallback
    }

    private String ensureUniqueLunTargetDev(String vmName, String xmlConfig) {
        try {
            Matcher m = Pattern.compile("<target\\s+dev='(sd[a-z]+)'\\s+bus='scsi'\\s*/?>").matcher(xmlConfig);
            String current = null;
            if (m.find()) {
                current = m.group(1);
            }
            Set<String> used = getUsedScsiTargetDevs(vmName);
            if (current == null) {
                // No target present: inject one with available dev after <source .../>
                String chosen = pickAvailableScsiTargetDev(used);
                return xmlConfig.replaceFirst("(</source>\\s*)", "$1\n  <target dev='" + chosen + "' bus='scsi'/>\n");
            }
            if (used.contains(current)) {
                String chosen = pickAvailableScsiTargetDev(used);
                return xmlConfig.replaceFirst("dev='" + Pattern.quote(current) + "'", "dev='" + chosen + "'");
            }
            return xmlConfig;
        } catch (Exception e) {
            return xmlConfig;
        }
    }

    private String findExistingByIdForBaseDevice(String baseDevicePath) {
        try {
            if (baseDevicePath == null || baseDevicePath.isEmpty()) return null;
            Path base = Path.of(baseDevicePath);
            if (!Files.exists(base)) return null;
            Path baseReal;
            try {
                baseReal = base.toRealPath();
            } catch (Exception e) {
                baseReal = base;
            }
            Path byIdDir = Path.of("/dev/disk/by-id");
            if (!Files.isDirectory(byIdDir)) return null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(byIdDir)) {
                for (Path entry : stream) {
                    try {
                        if (Files.isSymbolicLink(entry)) {
                            Path target = Files.readSymbolicLink(entry);
                            Path targetAbs = entry.getParent().resolve(target).normalize();
                            Path targetReal;
                            try {
                                targetReal = targetAbs.toRealPath();
                            } catch (Exception ignore) {
                                targetReal = targetAbs;
                            }
                            try {
                                if (Files.isSameFile(targetReal, baseReal)) {
                                    return entry.toString();
                                }
                            } catch (Exception ignore) {
                                // continue
                            }
                        }
                    } catch (Exception ignore) {
                        // continue
                    }
                }
            }
        } catch (Exception e) {
        }
        return null;
    }
}
