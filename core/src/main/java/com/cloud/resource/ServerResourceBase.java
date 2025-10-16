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
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.naming.ConfigurationException;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;

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
                if (!line.contains("System peripheral") && !line.contains("PIC")
                        && !line.contains("Performance counters")) {
                    String hostDevicesTexts = line;
                    hostDevicesText.add(hostDevicesTexts);
                }
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
                String[] parts = line.split("\\s+", 2);
                if (parts.length >= 2) {
                    hostDevicesNames.add(parts[0].trim());
                    hostDevicesText.add(parts[1].trim());
                }
            }
        }
        return new ListHostUsbDeviceAnswer(true, hostDevicesNames, hostDevicesText);
    }

    public Answer listHostLunDevices(Command command) {
        try {
            // 빠른 sysfs 스캐너 우선 시도
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
                logger.error("Failed to execute lsblk command: " + result);
                return new ListHostLunDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);
            }

            JSONObject json = new JSONObject(parser.getLines());
            JSONArray blockdevices = json.getJSONArray("blockdevices");

            // 성능 최적화: 배치로 정보 수집
            Map<String, String> scsiAddressCache = getScsiAddressesBatch();

            for (int i = 0; i < blockdevices.length(); i++) {
                JSONObject device = blockdevices.getJSONObject(i);
                addLunDeviceRecursiveOptimized(device, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses, scsiAddressCache);
            }

            // multipath 장치들 추가 (통합된 메서드 사용)
            Set<String> addedDevices = new HashSet<>();
            collectMultipathDevicesUnified(hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses, scsiAddressCache, addedDevices);

            return new ListHostLunDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);

        } catch (Exception e) {
            logger.error("Error listing LUN devices: " + e.getMessage(), e);
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

            java.io.File sysBlock = new java.io.File("/sys/block");
            java.io.File[] entries = sysBlock.listFiles();
            if (entries == null) {
                logger.debug("No entries found in /sys/block");
                return null;
            }

            logger.debug("Found " + entries.length + " entries in /sys/block");

            // 통합된 디바이스 수집으로 변경
            collectAllLunDevicesUnified(names, texts, hasPartitions, scsiAddresses, scsiAddressCache, realToById);

            logger.debug("Final LUN device count: " + names.size());
            for (int i = 0; i < names.size(); i++) {
                logger.debug("LUN device " + i + ": " + names.get(i));
            }

            return new ListHostLunDeviceAnswer(true, names, texts, hasPartitions, scsiAddresses);
        } catch (Exception e) {
            logger.debug("Fast sysfs scan failed, falling back: " + e.getMessage());
            return null;
        }
    }

    /**
     * 모든 LUN 디바이스를 통합적으로 수집하는 메서드
     */
    private void collectAllLunDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitions,
                                           List<String> scsiAddresses, Map<String, String> scsiAddressCache,
                                           Map<java.nio.file.Path, String> realToById) {
        java.io.File sysBlock = new java.io.File("/sys/block");
        java.io.File[] entries = sysBlock.listFiles();
        if (entries == null) return;

        // 중복 제거를 위한 Set
        Set<String> addedDevices = new HashSet<>();

        // 1. 직접 디바이스 수집
        for (java.io.File entry : entries) {
            String bname = entry.getName();
            if (!(bname.startsWith("sd") || bname.startsWith("vd") || bname.startsWith("xvd") ||
                  bname.startsWith("nvme") || bname.startsWith("dm-"))) {
                continue;
            }

            String devPath = "/dev/" + bname;
            String preferred = resolveById(realToById, devPath);

            // 중복 체크: 이미 ID로 추가된 디바이스인지 확인
            String deviceKey = preferred.startsWith("/dev/disk/by-id/") ?
                preferred.substring(preferred.lastIndexOf('/') + 1) : devPath;

            if (!addedDevices.contains(deviceKey)) {
                addDeviceToList(devPath, preferred, entry, names, texts, hasPartitions, scsiAddresses, scsiAddressCache, bname);
                addedDevices.add(deviceKey);
            }
        }

        // 2. multipath 디바이스 수집 (중복 제거)
        collectMultipathDevicesUnified(names, texts, hasPartitions, scsiAddresses, scsiAddressCache, addedDevices);
    }

    /**
     * 디바이스를 리스트에 추가하는 공통 메서드
     */
    private void addDeviceToList(String devPath, String preferred, java.io.File entry,
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
            info.append("\nSIZE: ").append(size);
        }

        info.append("\nHAS_PARTITIONS: ").append(deviceHasPartitions ? "true" : "false");

        String scsiAddr = scsiAddressCache.getOrDefault(devPath, scsiAddressCache.get(preferred));
        if (scsiAddr != null) {
            info.append("\nSCSI_ADDRESS: ").append(scsiAddr);
        }

        // ID 값이 있는 경우에만 리스트에 추가
        if (!preferred.equals(devPath) && preferred.startsWith("/dev/disk/by-id/")) {
            // ID 값이 있는 경우 경로와 ID를 함께 표시
            String byIdName = preferred.substring(preferred.lastIndexOf('/') + 1);
            String displayName = devPath + " (" + byIdName + ")";

            logger.debug("Adding LUN device: " + displayName + " (path: " + preferred + ", hasPartitions: " + deviceHasPartitions + ")");
            names.add(displayName);
            texts.add(info.toString());
            hasPartitionsList.add(deviceHasPartitions);
            scsiAddresses.add(scsiAddr != null ? scsiAddr : "");
        } else {
            // ID 값이 없는 경우 리스트에 추가하지 않음
            logger.debug("Skipping LUN device (no ID): " + devPath + " (preferred: " + preferred + ")");
        }
    }

    /**
     * multipath 디바이스를 통합적으로 수집하는 메서드
     */
    private void collectMultipathDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitionsList,
                                               List<String> scsiAddresses, Map<String, String> scsiAddressCache, Set<String> addedDevices) {
        try {
            Script cmd = new Script("/usr/sbin/multipath");
            cmd.add("-l");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) return;

            String[] lines = parser.getLines().split("\n");
            for (String line : lines) {
                if (line.contains("mpath")) {
                    String dmDevice = extractDmDeviceFromLine(line);
                    if (dmDevice != null) {
                        String devicePath = "/dev/mapper/" + dmDevice;
                        String preferredName = resolveDevicePathToById(devicePath);

                        // 중복 체크: 이미 추가된 디바이스인지 확인
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
            logger.debug("Error collecting multipath devices: " + e.getMessage());
        }
    }

    /**
     * multipath 디바이스를 리스트에 추가하는 메서드
     */
    private void addMultipathDeviceToList(String devicePath, String preferredName,
                                        List<String> names, List<String> texts, List<Boolean> hasPartitionsList,
                                        List<String> scsiAddresses, Map<String, String> scsiAddressCache) {
        boolean hasPartition = hasPartitionRecursiveForDevice(devicePath);

        StringBuilder deviceInfo = new StringBuilder();
        deviceInfo.append("TYPE: multipath");

        String size = getDeviceSize(devicePath);
        if (size != null) {
            deviceInfo.append("\nSIZE: ").append(size);
        }

        deviceInfo.append("\nHAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

        String scsiAddress = scsiAddressCache.get(devicePath);
        if (scsiAddress != null) {
            deviceInfo.append("\nSCSI_ADDRESS: ").append(scsiAddress);
        }

        deviceInfo.append("\nMULTIPATH_DEVICE: true");

        // ID 값이 있는 경우에만 리스트에 추가
        if (!preferredName.equals(devicePath) && preferredName.startsWith("/dev/disk/by-id/")) {
            // ID 값이 있는 경우 경로와 ID를 함께 표시
            String byIdName = preferredName.substring(preferredName.lastIndexOf('/') + 1);
            String displayName = devicePath + " (" + byIdName + ")";

            logger.debug("Adding multipath LUN device: " + displayName + " (path: " + preferredName + ", hasPartitions: " + hasPartition + ")");
            names.add(displayName);
            texts.add(deviceInfo.toString());
            hasPartitionsList.add(hasPartition);
            scsiAddresses.add(scsiAddress != null ? scsiAddress : "");
        } else {
            // ID 값이 없는 경우 리스트에 추가하지 않음
            logger.debug("Skipping multipath LUN device (no ID): " + devicePath + " (preferred: " + preferredName + ")");
        }
    }

    /**
     * multipath 라인에서 dm 디바이스 추출
     */
    private String extractDmDeviceFromLine(String line) {
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            if (part.startsWith("dm-")) {
                return part;
            }
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dm-\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }

        return null;
    }
    private Map<java.nio.file.Path, String> buildByIdReverseMap() {
        Map<java.nio.file.Path, String> map = new java.util.HashMap<>();
        java.nio.file.Path byIdDir = java.nio.file.Paths.get("/dev/disk/by-id");
        try {
            if (!java.nio.file.Files.isDirectory(byIdDir)) return map;
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(byIdDir)) {
                s.filter(java.nio.file.Files::isSymbolicLink).forEach(p -> {
                    java.nio.file.Path rp = safeRealPath(p);
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

    private String resolveById(Map<java.nio.file.Path, String> realToById, String devicePath) {
        try {
            java.nio.file.Path real = java.nio.file.Paths.get(devicePath).toRealPath();
            String byId = realToById.get(real);
            if (byId != null) {
                // LUN 디바이스는 전체 디스크를 사용해야 하므로 파티션 부분 제거
                if (byId.contains("-part")) {
                    byId = byId.replaceAll("-part\\d+$", "");
                }
                // 디버깅: HFS3T8G3H2X069N 관련 디바이스 로깅
                if (byId.contains("HFS3T8G3H2X069N")) {
                }
                return byId;
            }
            return devicePath;
        } catch (Exception e) {
            return devicePath;
        }
    }

    private boolean sysHasPartitions(java.io.File sysBlockEntry) {
        try {
            String name = sysBlockEntry.getName();
            java.io.File[] children = sysBlockEntry.listFiles();
            if (children == null) return false;

            logger.debug("Checking partitions for device: " + name);
            for (java.io.File child : children) {
                String childName = child.getName();
                logger.debug("Checking child: " + childName);

                // 파티션 디렉토리인지 확인 (예: sda1, sda2 등)
                if (childName.startsWith(name) && childName.length() > name.length()) {
                    String suffix = childName.substring(name.length());
                    // 숫자로만 구성된 경우 파티션으로 간주
                    if (suffix.matches("\\d+")) {
                        java.io.File partFile = new java.io.File(child, "partition");
                        if (partFile.exists()) {
                            logger.debug("Found partition: " + childName);
                            return true;
                        }
                    }
                }
            }

            // 추가로 lsblk 명령어로도 확인 (예외 처리 강화)
            try {
                Script lsblkCmd = new Script("/usr/bin/lsblk");
                lsblkCmd.add("--json", "--paths", "--output", "NAME,TYPE", "/dev/" + name);
                OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
                String result = lsblkCmd.execute(parser);

                if (result == null && parser.getLines() != null) {
                    try {
                        JSONObject json = new JSONObject(parser.getLines());
                        JSONArray blockdevices = json.getJSONArray("blockdevices");
                        if (blockdevices.length() > 0) {
                            JSONObject device = blockdevices.getJSONObject(0);
                            if (device.has("children")) {
                                JSONArray jsonChildren = device.getJSONArray("children");
                                for (int i = 0; i < jsonChildren.length(); i++) {
                                    JSONObject child = jsonChildren.getJSONObject(i);
                                    String childType = child.optString("type", "");
                                    if ("part".equals(childType)) {
                                        logger.debug("Found partition via lsblk: " + child.optString("name", ""));
                                        return true;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Error parsing lsblk output: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.debug("Error executing lsblk command for device {}: {}", name, e.getMessage());
                // lsblk 실패 시 sysfs 기반 결과만 사용
            }

            logger.debug("No partitions found for device: " + name);
            return false;
        } catch (Exception e) {
            logger.debug("Error checking partitions for " + sysBlockEntry.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private String sysGetSizeHuman(java.io.File sysBlockEntry) {
        try {
            java.io.File sizeFile = new java.io.File(sysBlockEntry, "size");
            if (!sizeFile.exists()) return null;
            String content = new String(java.nio.file.Files.readAllBytes(sizeFile.toPath())).trim();
            if (content.isEmpty()) return null;
            long sectors = Long.parseLong(content);
            double gib = (sectors * 512.0) / 1024 / 1024 / 1024;
            return String.format(java.util.Locale.ROOT, "%.2fG", gib);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Map<String, String> getScsiAddressesFromSysfs() {
        Map<String, String> map = new java.util.HashMap<>();
        try {
            java.io.File sysBlock = new java.io.File("/sys/block");
            java.io.File[] entries = sysBlock.listFiles();
            if (entries == null) return map;
            for (java.io.File e : entries) {
                String name = e.getName();
                if (!(name.startsWith("sd") || name.startsWith("vd") || name.startsWith("xvd"))) continue;
                java.nio.file.Path devLink = java.nio.file.Paths.get(e.getAbsolutePath(), "device");
                try {
                    java.nio.file.Path real = java.nio.file.Files.readSymbolicLink(devLink);
                    java.nio.file.Path resolved = devLink.getParent().resolve(real).normalize();
                    String scsi = resolved.getFileName().toString();
                    map.put("/dev/" + name, scsi);
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return map;
    }

    // 최적화된 LUN 디바이스 추가 메서드 (배치 처리된 정보 사용)
    private void addLunDeviceRecursiveOptimized(JSONObject device, List<String> names, List<String> texts,
            List<Boolean> hasPartitions, List<String> scsiAddresses,
            Map<String, String> scsiAddressCache) {
        String name = device.getString("name");
        String type = device.getString("type");
        String size = device.optString("size", "");

        if (!"part".equals(type)
            && !name.contains("/dev/mapper/ceph--") // Ceph OSD 블록 디바이스 제외
        ) {
            boolean hasPartition = hasPartitionRecursive(device);

            // 경로 방식으로 디바이스 추가 (가능하면 /dev/disk/by-id/* 로 변환)
            String preferredName = resolveDevicePathToById(name);

            // by-id가 있으면 경로와 ID를 함께 표시, 없으면 리스트에 추가하지 않음
            if (!preferredName.equals(name) && preferredName.startsWith("/dev/disk/by-id/")) {
                // by-id 경로에서 파일명만 추출하여 경로와 ID를 함께 표시
                String byIdName = preferredName.substring(preferredName.lastIndexOf('/') + 1);
                String displayName = name + " (" + byIdName + ")";

                logger.debug("Adding LUN device: " + displayName + " (path: " + preferredName + ", hasPartitions: " + hasPartition + ")");
                names.add(displayName);
                StringBuilder deviceInfo = new StringBuilder();
                deviceInfo.append("TYPE: ").append(type);
                if (!size.isEmpty()) {
                    deviceInfo.append("\nSIZE: ").append(size);
                }
                deviceInfo.append("\nHAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

                // 캐시된 SCSI 주소 정보 사용
                String scsiAddress = scsiAddressCache.get(name);
                if (scsiAddress != null) {
                    deviceInfo.append("\nSCSI_ADDRESS: ").append(scsiAddress);
                }

                texts.add(deviceInfo.toString());
                hasPartitions.add(hasPartition);
                scsiAddresses.add(scsiAddress != null ? scsiAddress : "");
            } else {
                // ID 값이 없는 경우 리스트에 추가하지 않음
                logger.debug("Skipping LUN device (no ID): " + name + " (preferred: " + preferredName + ")");
            }
        }

        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                addLunDeviceRecursiveOptimized(children.getJSONObject(i), names, texts, hasPartitions, scsiAddresses, scsiAddressCache);
            }
        }
    }

    /**
     * 주어진 블록 디바이스 경로를 가능한 경우 안정 식별자인 /dev/disk/by-id/* 경로로 변환한다.
     * - 심볼릭 링크 대상(realpath)이 동일한 첫 번째 by-id 항목을 우선 사용
     * - 없으면 원 경로를 그대로 반환
     */
    private String resolveDevicePathToById(String devicePath) {
        try {
            if (devicePath == null || !devicePath.startsWith("/dev/")) {
                return devicePath;
            }

            java.nio.file.Path device = java.nio.file.Paths.get(devicePath).toRealPath();
            java.nio.file.Path byIdDir = java.nio.file.Paths.get("/dev/disk/by-id");

            if (!java.nio.file.Files.isDirectory(byIdDir)) {
                return devicePath;
            }

            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(byIdDir)) {
                java.util.Optional<String> firstMatch = stream
                    .filter(java.nio.file.Files::isSymbolicLink)
                    .map(p -> new java.util.AbstractMap.SimpleEntry<>(p, safeRealPath(p)))
                    .filter(e -> e.getValue() != null && e.getValue().equals(device))
                    .map(e -> byIdDir.resolve(e.getKey().getFileName()).toString())
                    .findFirst();

                String byIdPath = firstMatch.orElse(devicePath);

                // LUN 디바이스는 전체 디스크를 사용해야 하므로 파티션 부분 제거
                if (byIdPath.contains("-part")) {
                    byIdPath = byIdPath.replaceAll("-part\\d+$", "");
                }

                return byIdPath;
            }
        } catch (Exception ignore) {
            // 원래 경로 반환
            return devicePath;
        }
    }

    private java.nio.file.Path safeRealPath(java.nio.file.Path link) {
        try {
            java.nio.file.Path target = java.nio.file.Files.readSymbolicLink(link);
            // 상대 링크일 수 있음
            java.nio.file.Path resolved = link.getParent().resolve(target).normalize();
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
            && !name.contains("/dev/mapper/ceph--") // Ceph OSD 블록 디바이스 제외
        ) {
            boolean hasPartition = hasPartitionRecursive(device);


            // 경로 방식으로 디바이스 추가
            names.add(name);
            StringBuilder deviceInfo = new StringBuilder();
            deviceInfo.append("TYPE: ").append(type);
            if (!size.isEmpty()) {
                deviceInfo.append("\nSIZE: ").append(size);
            }
            deviceInfo.append("\nHAS_PARTITIONS: ").append(hasPartition ? "true" : "false");

            // 추가 정보: 디바이스가 사용 중인지 여부 (동적 확인)
            boolean isInUse = isDeviceInUse(name);
            String usageStatus = isInUse ? "사용중" : "사용안함";
            deviceInfo.append("\nIN_USE: ").append(isInUse ? "true" : "false");
            deviceInfo.append("\nUSAGE_STATUS: ").append(usageStatus);

            // SCSI 주소 정보 추가
            String scsiAddress = getScsiAddress(name);
            if (scsiAddress != null) {
                deviceInfo.append("\nSCSI_ADDRESS: ").append(scsiAddress);
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
            // 1. multipath -l 명령어로 dm-* 장치 리스트 추출
            Set<String> dmDevices = getDmDevices();

            // 2. /dev/disk/by-id 내부 링크 분류 및 매핑
            dmMap = mapLinksByDm(dmDevices);
        } catch (Exception e) {
            logger.error("Error getting LUN device UUID mapping: " + e.getMessage(), e);
        }
        return dmMap;
    }

    /**
     * multipath -l 명령어로 dm-* 장치 리스트 추출
     */
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
                        // mpath line에서 dm-* 장치명 추출
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 3) {
                            String dmDevice = parts[2]; // dm-2 형태
                            if (dmDevice.startsWith("dm-")) {
                                dmDevices.add(dmDevice);
                            }
                        }
                    }
                }
            } else {
                logger.warn("Failed to execute multipath -l command: " + result);
            }

        } catch (Exception e) {
            logger.error("Error executing multipath command: " + e.getMessage(), e);
        }

        return dmDevices;
    }

    /**
     * /dev/disk/by-id 내부 링크 분류 및 매핑
     */
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
                            String targetBasename = new File(target).getName(); // ../../dm-2 → dm-2

                            if (dmDevices.contains(targetBasename)) {
                                // 분류
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
            logger.error("Error mapping links by dm: " + e.getMessage(), e);
        }

        return dmMap;
    }

    /**
     * 파일이 심볼릭 링크인지 확인
     */
    private boolean isSymbolicLink(File file) {
        try {
            return Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 심볼릭 링크의 타겟 경로 가져오기
     */
    private String getSymbolicLinkTarget(File file) {
        try {
            Path target = Files.readSymbolicLink(file.toPath());
            return target.toString();
        } catch (Exception e) {
            logger.debug("Error reading symbolic link " + file.getPath() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * UUID 기반 LUN 디바이스 할당을 위한 XML 생성
     */
    protected String generateLunUuidXmlConfig(String devicePath, String uuid, String scsiAddress) {
        // dm으로 시작하는 디바이스인지 확인
        boolean isDmDevice = isDmDevice(devicePath);

        if (isDmDevice) {
            // dm 디바이스는 <disk> 방식 사용 (by-id 대신 실제 디바이스 경로 사용)
            StringBuilder xml = new StringBuilder();
            xml.append("<disk type='block' device='lun'>\n");
            xml.append("  <driver name='qemu' type='raw' cache='none'/>\n");

            // dm 디바이스는 by-id 대신 실제 디바이스 경로 사용
            String actualDevicePath = getDmDevicePath(devicePath, uuid);
            xml.append("  <source dev='").append(actualDevicePath).append("'/>\n");

            // 안전한 target dev 할당 (VM에서 사용 중인 dev 제외)
            String safeTargetDev = findSafeTargetDevForVm(devicePath);
            xml.append("  <target dev='").append(safeTargetDev).append("' bus='scsi'/>\n");

            if (uuid != null && !uuid.isEmpty()) {
                xml.append("  <serial>").append(uuid).append("</serial>\n");
            }
            xml.append("</disk>");
            return xml.toString();
        } else {
            // 멀티패스 LUN은 <hostdev> 방식 사용
            return generateMultipathLunXmlConfig(devicePath, uuid);
        }
    }

    /**
     * dm 디바이스인지 확인하는 메서드
     */
    private boolean isDmDevice(String devicePath) {
        if (devicePath == null) return false;

        String lowerDevicePath = devicePath.toLowerCase();
        return lowerDevicePath.contains("dm-") || lowerDevicePath.contains("dm-uuid-");
    }

    /**
     * dm 디바이스를 위한 실제 디바이스 경로를 가져오는 메서드 (by-id 대신)
     */
    private String getDmDevicePath(String devicePath, String uuid) {
        try {
            // 1. 원본 경로가 /dev/dm-X 형태인지 확인
            if (devicePath != null && devicePath.startsWith("/dev/dm-")) {
                return devicePath;
            }

            // 2. by-id 경로에서 실제 dm-X 경로로 변환
            if (devicePath != null && devicePath.contains("dm-uuid-")) {
                // dm-uuid에서 실제 dm-X 경로 찾기
                String dmUuid = devicePath.split("/")[devicePath.split("/").length - 1];
                String actualPath = resolveDmUuidToDevice(dmUuid);
                if (actualPath != null) {
                    return actualPath;
                }
            }

            // 3. UUID로 by-id 경로 생성 후 실제 경로로 변환
            if (uuid != null && !uuid.isEmpty()) {
                String byIdPath = "/dev/disk/by-id/" + uuid;
                String actualPath = resolveByIdToDevice(byIdPath);
                if (actualPath != null) {
                    return actualPath;
                }
            }

            // 4. 기본 fallback
            return devicePath != null ? devicePath : "/dev/dm-10";

        } catch (Exception e) {
            logger.error("Error getting dm device path for {}: {}", devicePath, e.getMessage());
            return devicePath != null ? devicePath : "/dev/dm-10";
        }
    }

    /**
     * dm-uuid에서 실제 /dev/dm-X 경로로 변환
     */
    private String resolveDmUuidToDevice(String dmUuid) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("readlink -f /dev/disk/by-id/" + dmUuid + " 2>/dev/null");

            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String resolvedPath = parser.getLines().trim();
                if (resolvedPath.startsWith("/dev/dm-")) {
                    return resolvedPath;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve dm-uuid {}: {}", dmUuid, e.getMessage());
        }
        return null;
    }

    /**
     * by-id 경로에서 실제 디바이스 경로로 변환
     */
    private String resolveByIdToDevice(String byIdPath) {
        try {
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("readlink -f " + byIdPath + " 2>/dev/null");

            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                return parser.getLines().trim();
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve by-id path {}: {}", byIdPath, e.getMessage());
        }
        return null;
    }

    /**
     * VM에서 사용 중인 target dev를 제외하고 안전한 target dev를 찾는 메서드
     */
    private String findSafeTargetDevForVm(String devicePath) {
        try {
            // 기본적으로 sdd부터 시작 (sdc는 자주 충돌하므로)
            String[] candidates = {"sdd", "sde", "sdf", "sdg", "sdh", "sdi", "sdj", "sdk", "sdl", "sdm", "sdn", "sdo", "sdp", "sdq", "sdr", "sds", "sdt", "sdu", "sdv", "sdw", "sdx", "sdy", "sdz", "sdc"};

            // VM에서 현재 사용 중인 target dev 목록 가져오기
            Set<String> usedTargetDevs = getCurrentlyUsedTargetDevs();

            // 사용 가능한 첫 번째 target dev 찾기
            for (String candidate : candidates) {
                if (!usedTargetDevs.contains(candidate)) {
                    return candidate;
                }
            }

            // 모든 후보가 사용 중이면 sdd 반환 (fallback)
            logger.warn("All target dev candidates are in use, using sdd as fallback for device: {}", devicePath);
            return "sdd";

        } catch (Exception e) {
            logger.error("Error finding safe target dev for device {}: {}", devicePath, e.getMessage());
            return "sdd"; // 안전한 fallback
        }
    }

    /**
     * 현재 VM에서 사용 중인 target dev 목록을 가져오는 메서드
     */
    private Set<String> getCurrentlyUsedTargetDevs() {
        Set<String> usedDevs = new HashSet<>();
        try {
            // virsh dumpxml을 통해 현재 VM들의 디스크 정보 확인
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("virsh list --state-running --name | head -20"); // 실행 중인 VM 목록 (최대 20개)

            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String[] vmNames = parser.getLines().trim().split("\n");

                for (String vmName : vmNames) {
                    if (vmName != null && !vmName.trim().isEmpty()) {
                        try {
                            // 각 VM의 XML 덤프에서 target dev 추출
                            Script dumpCmd = new Script("/bin/bash");
                            dumpCmd.add("-c");
                            dumpCmd.add("virsh dumpxml '" + vmName.trim() + "' 2>/dev/null | grep -o \"<target dev='[^']*'\" | sed \"s/<target dev='\\([^']*\\)'/\\1/\" | head -10");

                            OutputInterpreter.AllLinesParser dumpParser = new OutputInterpreter.AllLinesParser();
                            String dumpResult = dumpCmd.execute(dumpParser);

                            if (dumpResult == null && dumpParser.getLines() != null) {
                                String[] targetDevs = dumpParser.getLines().trim().split("\n");
                                for (String targetDev : targetDevs) {
                                    if (targetDev != null && targetDev.trim().matches("sd[a-z]")) {
                                        usedDevs.add(targetDev.trim());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Error checking target devs for VM {}: {}", vmName, e.getMessage());
                        }
                    }
                }
            }

            logger.debug("Currently used target devs: {}", usedDevs);
            return usedDevs;

        } catch (Exception e) {
            logger.error("Error getting currently used target devs: {}", e.getMessage());
            return usedDevs; // 빈 Set 반환
        }
    }

    /**
     * 멀티패스 LUN을 위한 XML 생성
     */
    protected String generateMultipathLunXmlConfig(String devicePath, String uuid) {
        StringBuilder xml = new StringBuilder();
        xml.append("<hostdev mode='subsystem' type='lun' managed='yes'>\n");
        xml.append("  <source>\n");
        xml.append("    <adapter name='scsi_host0'/>\n");

        // 실제 존재하는 디바이스 경로 찾기
        String actualDevicePath = findActualDevicePath(devicePath, uuid);

        // 동적으로 SCSI 주소 추출
        String scsiAddress = extractScsiAddressFromDevice(devicePath);
        if (scsiAddress != null && !scsiAddress.isEmpty()) {
            xml.append("    ").append(scsiAddress).append("\n");
        } else {
            xml.append("    <address type='drive' controller='0' bus='0' target='0' unit='0'/>\n");
        }
        xml.append("  </source>\n");
        xml.append("  <source dev='").append(actualDevicePath).append("'/>\n");
        xml.append("  <rawio value='yes'/>\n");

        if (uuid != null && !uuid.isEmpty()) {
            xml.append("  <serial>").append(uuid).append("</serial>\n");
        }
        xml.append("</hostdev>");
        return xml.toString();
    }

    /**
     * 디바이스에서 SCSI 주소를 추출하는 메서드
     */
    private String extractScsiAddressFromDevice(String devicePath) {
        try {
            // sg_inq를 사용하여 SCSI 주소 추출
            ProcessBuilder pb = new ProcessBuilder("sg_inq", "-i", devicePath);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String result = output.toString();

                // SCSI 주소 패턴 찾기 (예: "SCSI Address: 0:0:1:0")
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("SCSI Address:\\s*(\\d+):(\\d+):(\\d+):(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(result);

                if (matcher.find()) {
                    String host = matcher.group(1);
                    String bus = matcher.group(2);
                    String target = matcher.group(3);
                    String unit = matcher.group(4);

                    return String.format("<address type='drive' controller='%s' bus='%s' target='%s' unit='%s'/>",
                                       host, bus, target, unit);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract SCSI address from device {}: {}", devicePath, e.getMessage());
        }

        return null;
    }

    /**
     * 멀티패스 LUN인지 확인하는 메서드
     */
    private boolean isMultipathLun(String devicePath) {
        if (devicePath == null) return false;

        String lowerDevicePath = devicePath.toLowerCase();
        return lowerDevicePath.contains("mpatha") ||
               lowerDevicePath.contains("mpathb") ||
               lowerDevicePath.contains("mpathc") ||
               lowerDevicePath.contains("mpathd") ||
               lowerDevicePath.contains("mpathe") ||
               lowerDevicePath.contains("dm-uuid-mpath-") ||
               lowerDevicePath.contains("/dev/mapper/mpath");
    }

    /**
     * 실제 존재하는 디바이스 경로를 찾는 메서드
     */
    private String findActualDevicePath(String devicePath, String uuid) {
        // 1. 원본 경로가 실제로 존재하는지 확인
        if (devicePath != null && new File(devicePath).exists()) {
            return devicePath;
        }

        // 2. by-id 경로 확인
        if (devicePath != null && devicePath.startsWith("/dev/disk/by-id/")) {
            if (new File(devicePath).exists()) {
                return devicePath;
            }
        }

        // 3. dm-uuid 경로 확인
        if (devicePath != null && devicePath.contains("dm-uuid-")) {
            String dmUuid = devicePath.split("/")[devicePath.split("/").length - 1];
            String byIdPath = "/dev/disk/by-id/" + dmUuid;
            if (new File(byIdPath).exists()) {
                return byIdPath;
            }
        }

        // 4. UUID로 by-id 경로 생성하여 확인
        if (uuid != null && !uuid.isEmpty()) {
            String byIdPath = "/dev/disk/by-id/" + uuid;
            if (new File(byIdPath).exists()) {
                return byIdPath;
            }

            // scsi- 접두사 추가하여 확인
            String scsiByIdPath = "/dev/disk/by-id/scsi-" + uuid;
            if (new File(scsiByIdPath).exists()) {
                return scsiByIdPath;
            }
        }

        // 5. 원본 경로에서 실제 디바이스 경로 추출
        if (devicePath != null) {
            // /dev/dm-X 형태로 변환 시도
            String baseDevice = devicePath.replace("/dev/disk/by-id/", "/dev/");
            if (baseDevice.contains("dm-uuid-")) {
                // dm-uuid를 실제 dm-X로 변환하는 로직 (실제 구현에서는 심볼릭 링크 확인 필요)
                // 여기서는 기본값으로 fallback
                return "/dev/dm-10"; // 기본 fallback
            }
            return baseDevice;
        }

        // 6. 최종 fallback
        return "/dev/dm-10";
    }

    /**
     * 디바이스 경로에서 타겟 디바이스명 추출
     */
    private String getTargetDeviceName(String devicePath) {
        String targetDev = "sdc"; // 기본값

        if (devicePath != null && devicePath.startsWith("/dev/")) {
            String[] parts = devicePath.split("/");
            if (parts.length > 0) {
                String deviceName = parts[parts.length - 1];
                if (deviceName.matches("[a-z]+[a-z0-9]*")) {
                    // dm-* (multipath) 디바이스는 sdc로, sd*는 그대로 사용
                    if (deviceName.startsWith("dm-")) {
                        targetDev = "sdc";
                    } else if (deviceName.startsWith("sd")) {
                        targetDev = deviceName;
                    } else {
                        targetDev = "sdc";
                    }
                }
            }
        }
        return targetDev;
    }

    /**
     * 특정 디바이스의 파티션 여부를 확인하는 메서드
     */
    private boolean hasPartitionRecursiveForDevice(String devicePath) {
        try {
            Script cmd = new Script("/usr/bin/lsblk");
            cmd.add("--json", "--paths", "--output", "NAME,TYPE", devicePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result;
            try {
                result = cmd.execute(parser);
            } catch (Exception e) {
                logger.debug("Error executing lsblk command for device {}: {}", devicePath, e.getMessage());
                return false;
            }

            if (result == null && parser.getLines() != null) {
                try {
                    JSONObject json = new JSONObject(parser.getLines());
                    JSONArray blockdevices = json.getJSONArray("blockdevices");

                    if (blockdevices.length() > 0) {
                        JSONObject device = blockdevices.getJSONObject(0);
                        return hasPartitionRecursive(device);
                    }
                } catch (Exception e) {
                    logger.debug("Error parsing lsblk JSON output for device {}: {}", devicePath, e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.debug("Error executing lsblk command for device {}: {}", devicePath, e.getMessage());
        }

        return false;
    }

    /**
     * 디바이스 크기를 가져오는 메서드
     */
    private String getDeviceSize(String devicePath) {
        try {
            Script cmd = new Script("/usr/bin/lsblk");
            cmd.add("--json", "--paths", "--output", "NAME,SIZE", devicePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null) {
                JSONObject json = new JSONObject(parser.getLines());
                JSONArray blockdevices = json.getJSONArray("blockdevices");

                if (blockdevices.length() > 0) {
                    JSONObject device = blockdevices.getJSONObject(0);
                    return device.optString("size", "");
                }
            }

        } catch (Exception e) {
            logger.debug("Error getting size for device " + devicePath + ": " + e.getMessage());
        }

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
                logger.debug("Ceph OSD device, no partitions: " + deviceName);
                return false;
            }
            logger.debug("LVM device, has partitions: " + deviceName);
            return true;
        }

        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");
            logger.debug("Device has " + children.length() + " children: " + deviceName);

            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                String childType = child.optString("type", "");
                String childName = child.optString("name", "");

                // 파티션이 있으면 true
                if ("part".equals(childType)) {
                    return true;
                }

                // LVM 볼륨이 있으면 true
                if ("lvm".equals(childType)) {
                    logger.debug("Found LVM child: " + childName);
                    return true;
                }

                if (hasPartitionRecursive(child)) {
                    return true;
                }
            }
        } else {
            logger.debug("Device has no children: " + deviceName);
        }
        return false;
    }

    // 배치로 SCSI 주소들을 가져오는 메서드 (성능 최적화)
    private Map<String, String> getScsiAddressesBatch() {
        Map<String, String> scsiAddressMap = new HashMap<>();

        try {
            // lsscsi -g 명령어를 한 번만 실행하여 모든 SCSI 주소 정보 수집
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
                logger.debug("Error executing lsscsi command in batch: {}", e.getMessage());
            }

            // sysfs에서 추가 정보 수집 (lsscsi에서 찾지 못한 디바이스들)
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
                logger.debug("Error reading sysfs SCSI addresses: " + e.getMessage());
            }

        } catch (Exception e) {
            logger.debug("Error getting SCSI addresses batch: " + e.getMessage());
        }

        return scsiAddressMap;
    }

    // 배치로 사용 중인 디바이스들을 가져오는 메서드 (성능 최적화)
    private Set<String> getDevicesInUseBatch() {
        Set<String> devicesInUse = new HashSet<>();

        try {
            // virsh list --all을 한 번만 실행하여 모든 VM의 디바이스 정보 수집
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
                            // 각 VM의 XML에서 디바이스 정보 추출
                            Script dumpCommand = new Script("virsh");
                            dumpCommand.add("dumpxml", vmId);
                            OutputInterpreter.AllLinesParser dumpParser = new OutputInterpreter.AllLinesParser();
                            String dumpResult = dumpCommand.execute(dumpParser);

                            if (dumpResult == null && dumpParser.getLines() != null) {
                                String vmXml = dumpParser.getLines();
                                // 디바이스 경로들 추출
                                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dev=['\"]([^'\"]+)['\"]");
                                java.util.regex.Matcher matcher = pattern.matcher(vmXml);
                                while (matcher.find()) {
                                    String devicePath = matcher.group(1);
                                    if (devicePath.startsWith("/dev/")) {
                                        devicesInUse.add(devicePath);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Error processing VM " + vmId + ": " + e.getMessage());
                        }
                    }
                }
            }

            // 마운트된 디바이스들도 추가
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
                logger.debug("Error getting mounted devices: " + e.getMessage());
            }

        } catch (Exception e) {
            logger.debug("Error getting devices in use batch: " + e.getMessage());
        }

        return devicesInUse;
    }

    private Set<String> getDevicesInUseBatch(long timeoutMs) {
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.ExecutorService es = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Set<String>> fut = es.submit(() -> getDevicesInUseBatch());
            try {
                return fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                timedOut.set(true);
                fut.cancel(true);
                logger.warn("getDevicesInUseBatch timed out after " + timeoutMs + "ms; returning partial/empty set");
                return new HashSet<>();
            }
        } catch (Exception e) {
            logger.debug("getDevicesInUseBatch with timeout failed: " + e.getMessage());
            return new HashSet<>();
        } finally {
            es.shutdownNow();
        }
    }

    // SCSI 주소를 가져오는 메서드
    private String getScsiAddress(String deviceName) {
        try {
            // Ceph OSD 디바이스인지 확인
            if (deviceName.contains("ceph--") && deviceName.contains("--osd--block--")) {
                logger.debug("Ceph OSD device detected: {}", deviceName);
                return generateVirtualScsiAddressForCeph(deviceName);
            }

            String blockDevice = deviceName.replace("/dev/", "");

            String scsiDevicePath = "/sys/block/" + blockDevice + "/device/scsi_device";
            try {
                Script cmd = new Script("/bin/cat");
                cmd.add(scsiDevicePath);
                OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
                String result = cmd.execute(parser);
                if (result == null && parser.getLines() != null && !parser.getLines().isEmpty()) {
                    String scsiAddress = parser.getLines().trim();
                    return scsiAddress;
                }
            } catch (Exception e) {
                logger.debug("Error reading SCSI device file {}: {}", scsiDevicePath, e.getMessage());
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
                logger.debug("Error executing lsscsi command for device {}: {}", deviceName, e.getMessage());
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    // Ceph OSD 디바이스를 위한 가상 SCSI 주소 생성
    private String generateVirtualScsiAddressForCeph(String deviceName) {
        try {
            // Ceph OSD 디바이스의 실제 물리 디바이스 찾기
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("lsblk -no NAME " + deviceName + " | head -1");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().isEmpty()) {
                String physicalDevice = parser.getLines().trim();

                // 물리 디바이스의 SCSI 주소 가져오기
                String physicalScsiAddress = getPhysicalScsiAddress(physicalDevice);
                if (physicalScsiAddress != null) {
                    String[] parts = physicalScsiAddress.split(":");
                    if (parts.length >= 4) {
                        // unit 번호를 1씩 증가시켜 가상 주소 생성
                        int unit = Integer.parseInt(parts[3]) + 1;
                        String virtualAddress = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + unit;
                        return virtualAddress;
                    }
                }
            }

            // 물리 디바이스를 찾지 못한 경우 기본 가상 주소 생성
            String defaultVirtualAddress = "0:0:277:1"; // Ceph OSD용 기본 주소

            return defaultVirtualAddress;

        } catch (Exception e) {

            return "0:0:277:1"; // 기본값
        }
    }

    // 물리 디바이스의 SCSI 주소 가져오기
    private String getPhysicalScsiAddress(String deviceName) {
        try {
            // /sys/block/{device}/device/scsi_device 파일에서 SCSI 주소 읽기
            String scsiDevicePath = "/sys/block/" + deviceName + "/device/scsi_device";
            Script cmd = new Script("/bin/cat");
            cmd.add(scsiDevicePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().isEmpty()) {
                return parser.getLines().trim();
            }

            return null;
        } catch (Exception e) {

            return null;
        }
    }

    // 기본 vHBA XML 생성 (WWNN/WWPN 없이)
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
            logger.error("Error generating basic vHBA XML for parent {}: {}", parentHbaName, e.getMessage());
            return null;
        }
    }

    // WWNN으로 vHBA 디바이스 찾기
    private String findVhbaDeviceByWwnn(String wwnn) {
        try {
            // virsh nodedev-list --cap scsi_host로 모든 SCSI 호스트 디바이스 조회
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

                    // 각 디바이스의 XML 덤프에서 WWNN 확인
                    String deviceXml = getVhbaDumpXml(device);
                    if (deviceXml != null && deviceXml.contains("<wwnn>" + wwnn + "</wwnn>")) {
                        return device;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            logger.error("Error finding vHBA device by WWNN {}: {}", wwnn, e.getMessage());
            return null;
        }
    }

    // 디바이스가 사용 중인지 확인하는 메서드 (LUN 디바이스용)
    private boolean isDeviceInUse(String deviceName) {
        try {
            // 1. VM 할당 상태 확인 (가장 중요)
            if (isLunDeviceAllocatedToVm(deviceName)) {
                return true;
            }

            // 2. 마운트 포인트 확인
            Script mountCommand = new Script("/bin/bash");
            mountCommand.add("-c");
            mountCommand.add("mount | grep -q '" + deviceName + "'");
            String mountResult = mountCommand.execute(null);
            if (mountResult == null) {
                return true;
            }

            // 3. LVM 사용 확인
            Script lvmCommand = new Script("/bin/bash");
            lvmCommand.add("-c");
            lvmCommand.add("lvs --noheadings -o lv_name,vg_name 2>/dev/null | grep -q '" + deviceName + "'");
            String lvmResult = lvmCommand.execute(null);
            if (lvmResult == null) {
                return true; // LVM에서 사용 중
            }

            // 4. 스왑 확인
            Script swapCommand = new Script("/bin/bash");
            swapCommand.add("-c");
            swapCommand.add("swapon --show | grep -q '" + deviceName + "'");
            String swapResult = swapCommand.execute(null);
            if (swapResult == null) {
                return true; // 스왑으로 사용 중
            }

            // 5. 파티션 테이블 확인
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

    // LUN 디바이스가 VM에 할당되어 있는지 확인하는 메서드
    private boolean isLunDeviceAllocatedToVm(String deviceName) {
        try {
            // virsh list --all로 모든 VM 조회
            Script listCommand = new Script("/bin/bash");
            listCommand.add("-c");
            listCommand.add("virsh list --all | grep -v 'Id' | grep -v '^-' | while read line; do " +
                           "vm_id=$(echo $line | awk '{print $1}'); " +
                           "if [ ! -z \"$vm_id\" ]; then " +
                           "virsh dumpxml $vm_id | grep -q '" + deviceName + "'; " +
                           "if [ $? -eq 0 ]; then " +
                           "echo 'allocated'; " +
                           "break; " +
                           "fi; " +
                           "fi; " +
                           "done");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = listCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                boolean isAllocated = parser.getLines().trim().equals("allocated");
                if (isAllocated) {
                }
                return isAllocated;
            }
            return false;
        } catch (Exception e) {
            logger.debug("LUN 디바이스 할당 상태 확인 중 오류: " + e.getMessage());
            return false;
        }
    }

    // 최적화된 SCSI 디바이스 정보 조회 메서드
    public Answer listHostScsiDevices(Command command) {
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> hostDevicesText = new ArrayList<>();
        List<Boolean> hasPartitions = new ArrayList<>();

        try {
            com.cloud.agent.api.ListHostScsiDeviceAnswer fast = listHostScsiDevicesFast();
            if (fast != null && fast.getResult()) {
                return fast;
            }
            Map<java.nio.file.Path, String> realToById = buildByIdReverseMap();

            Script cmd = new Script("/usr/bin/lsscsi");
            cmd.add("-g");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                return new com.cloud.agent.api.ListHostScsiDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions);
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
                text.append("SCSI Address: ").append(scsiAddr).append("\n");

                String displayName = name;
                try {
                    String dev = tokens[5];
                    String byId = resolveById(realToById, dev);
                    if (byId != null && !byId.equals(dev)) {
                        text.append("BY_ID: ").append(byId).append("\n");
                        String byIdName = byId.substring(byId.lastIndexOf('/') + 1);
                        displayName = name + " (" + byIdName + ")";
                    }
                } catch (Exception ignore) {}
                hostDevicesNames.add(displayName);
                hostDevicesText.add(text.toString());
                hasPartitions.add(false);
            }

            return new com.cloud.agent.api.ListHostScsiDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions);
        } catch (Exception e) {
            logger.error("Error listing SCSI devices with lsscsi -g: " + e.getMessage(), e);
            return new com.cloud.agent.api.ListHostScsiDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private com.cloud.agent.api.ListHostScsiDeviceAnswer listHostScsiDevicesFast() {
        try {
            List<String> names = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            List<Boolean> hasPartitions = new ArrayList<>();

            // LUN과 동일한 최적화: 배치로 정보 수집
            Map<String, String> scsiAddressCache = getScsiAddressesBatch();
            Map<java.nio.file.Path, String> realToById = buildByIdReverseMap();

            java.io.File sysBlock = new java.io.File("/sys/block");
            java.io.File[] entries = sysBlock.listFiles();
            if (entries == null) {
                return null;
            }

            // 통합된 디바이스 수집 (LUN과 동일한 패턴)
            collectAllScsiDevicesUnified(names, texts, hasPartitions, scsiAddressCache, realToById);
            for (int i = 0; i < names.size(); i++) {
                logger.debug("SCSI device " + i + ": " + names.get(i));
            }

            return new com.cloud.agent.api.ListHostScsiDeviceAnswer(true, names, texts, hasPartitions);
        } catch (Exception ex) {
            logger.debug("Fast SCSI sysfs scan failed: " + ex.getMessage());
            return null;
        }
    }

    /**
     * 모든 SCSI 디바이스를 통합적으로 수집하는 메서드 (LUN과 동일한 최적화)
     */
    private void collectAllScsiDevicesUnified(List<String> names, List<String> texts, List<Boolean> hasPartitions,
                                            Map<String, String> scsiAddressCache, Map<java.nio.file.Path, String> realToById) {
        try {
            java.io.File sysBlock = new java.io.File("/sys/block");
            java.io.File[] entries = sysBlock.listFiles();
            if (entries == null) return;

            for (java.io.File e : entries) {
                String bname = e.getName();
                if (!(bname.startsWith("sd") || bname.startsWith("vd") || bname.startsWith("xvd"))) {
                    continue;
                }

                String dev = "/dev/" + bname;

                // sg 매핑
                String sg = null;
                try {
                    java.io.File sgDir = new java.io.File(e, "device/scsi_generic");
                    java.io.File[] sgs = sgDir.listFiles();
                    if (sgs != null && sgs.length > 0) {
                        sg = "/dev/" + sgs[0].getName();
                    }
                } catch (Exception ignore) {}

                // SCSI 주소 (캐시에서 우선 확인)
                String scsiAddress = scsiAddressCache.get(dev);
                if (scsiAddress == null) {
                    try {
                        java.nio.file.Path devLink = java.nio.file.Paths.get(e.getAbsolutePath(), "device");
                        java.nio.file.Path real = java.nio.file.Files.readSymbolicLink(devLink);
                        java.nio.file.Path resolved = devLink.getParent().resolve(real).normalize();
                        scsiAddress = resolved.getFileName().toString();
                    } catch (Exception ignore) {}
                }

                // 벤더/모델/리비전 (배치 읽기)
                String vendor = readFirstLineQuiet(new java.io.File(e, "device/vendor"));
                String model = readFirstLineQuiet(new java.io.File(e, "device/model"));
                String rev = readFirstLineQuiet(new java.io.File(e, "device/rev"));

                String byId = resolveById(realToById, dev);

                StringBuilder text = new StringBuilder();
                text.append("SCSI Address: ").append(scsiAddress != null ? ("["+scsiAddress+"]") : "").append("\n");
                text.append("Type: disk\n");
                if (vendor != null) text.append("Vendor: ").append(vendor).append("\n");
                if (model != null) text.append("Model: ").append(model).append("\n");
                if (rev != null) text.append("Revision: ").append(rev).append("\n");
                text.append("Device: ").append(dev).append("\n");
                if (!byId.equals(dev)) text.append("BY_ID: ").append(byId).append("\n");

                // by-id가 있으면 괄호로 표시, 없으면 원래 경로 사용
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
            logger.debug("Error collecting SCSI devices unified: " + e.getMessage());
        }
    }

    private String readFirstLineQuiet(java.io.File f) {
        try {
            if (!f.exists()) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
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

                    // SCSI 주소에서 대괄호 제거
                    String scsiAddress = scsiAddr.replaceAll("[\\[\\]]", "");

                    // SCSI 주소에서 host 번호 추출 (예: 13:0:0:2 -> 13)
                    String[] scsiParts = scsiAddress.split(":");
                    if (scsiParts.length >= 4) {
                        String hostNum = scsiParts[0];
                        String scsiHostName = "scsi_host" + hostNum;

                        // 해당 scsi_host에 대한 SCSI 주소 정보 저장
                        if (!scsiAddressMap.containsKey(scsiHostName)) {
                            scsiAddressMap.put(scsiHostName, scsiAddress);
                            logger.debug("Mapped " + scsiHostName + " to SCSI address: " + scsiAddress);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("SCSI 디바이스 조회 중 오류 발생: " + e.getMessage());
        }

        // 2. virsh nodedev-list --cap vports로 vHBA 지원 물리 HBA 조회 (화면에 표시)
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

                        // SCSI 주소 정보가 있으면 추가
                        String scsiAddress = scsiAddressMap.get(hbaName);
                        if (scsiAddress != null) {
                            detailedInfo += "\nSCSI Address: " + scsiAddress;
                        }

                        hostDevicesNames.add(hbaName);
                        hostDevicesText.add(detailedInfo);
                        deviceTypes.add("physical");
                        parentHbaNames.add("");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("vHBA 지원 HBA 조회 중 오류 발생: " + e.getMessage());
        }

        // 3. virsh nodedev-list | grep vhba로 vHBA 추가
        try {
            Script vhbaCommand = new Script("/bin/bash");
            vhbaCommand.add("-c");
            vhbaCommand.add("virsh nodedev-list | grep vhba");
            OutputInterpreter.AllLinesParser vhbaParser = new OutputInterpreter.AllLinesParser();
            String vhbaResult = vhbaCommand.execute(vhbaParser);
            if (vhbaResult == null && vhbaParser.getLines() != null) {
                String[] vhbaLines = vhbaParser.getLines().split("\\n");
                for (String vhbaLine : vhbaLines) {
                    String vhbaName = vhbaLine.trim();
                    if (!vhbaName.isEmpty() && !hostDevicesNames.contains(vhbaName)) {
                        // vHBA 상세 정보 조회
                        Script vhbaInfoCommand = new Script("/bin/bash");
                        vhbaInfoCommand.add("-c");
                        vhbaInfoCommand.add("virsh nodedev-dumpxml " + vhbaName);
                        OutputInterpreter.AllLinesParser vhbaInfoParser = new OutputInterpreter.AllLinesParser();
                        String vhbaInfoResult = vhbaInfoCommand.execute(vhbaInfoParser);
                        String vhbaDescription = "";
                        String parentHbaName = "";
                        String wwnn = "";
                        String wwpn = "";
                        String fabricWwn = "";

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

                            StringBuilder descBuilder = new StringBuilder();
                            if (!wwnn.isEmpty()) {
                                descBuilder.append("WWNN: ").append(wwnn);
                            }
                            if (!wwpn.isEmpty()) {
                                if (descBuilder.length() > 0) {
                                    descBuilder.append("\n");
                                }
                                descBuilder.append("WWPN: ").append(wwpn);
                            }
                            if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                                if (descBuilder.length() > 0) {
                                    descBuilder.append("\n");
                                }
                                descBuilder.append("Fabric WWN: ").append(fabricWwn);
                            }
                            vhbaDescription = descBuilder.toString();
                        }
                        hostDevicesNames.add(vhbaName);
                        hostDevicesText.add(vhbaDescription);
                        deviceTypes.add("virtual");
                        parentHbaNames.add(parentHbaName);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("vHBA 조회 중 오류 발생: " + e.getMessage());
        }
        return new ListHostHbaDeviceAnswer(true, hostDevicesNames, hostDevicesText, deviceTypes, parentHbaNames);
    }


    // vHBA 지원 물리 HBA 디바이스의 상세 정보 조회
    private String getHbaDeviceDetailsFromVports(String hbaName) {
        StringBuilder details = new StringBuilder();
        details.append(hbaName);

        try {
            // HBA 상세 정보 조회
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
                    details.append("\n(Max vPorts: ").append(maxVports).append(")");
                }
                if (!wwnn.isEmpty()) {
                    details.append("\nWWNN: ").append(wwnn);
                }
                if (!wwpn.isEmpty()) {
                    details.append("\nWWPN: ").append(wwpn);
                }
                if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                    details.append("\nFabric WWN: ").append(fabricWwn);
                }
            }
        } catch (Exception e) {

        }

        return details.toString();
    }

    public Answer createHostVHbaDevice(CreateVhbaDeviceCommand command, String parentHbaName, String wwnn, String wwpn, String vhbaName, String xmlContent) {

        try {
            // 1. 입력 파라미터 검증
            if (parentHbaName == null || parentHbaName.trim().isEmpty()) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA 이름이 필요합니다");
            }
            if (wwnn == null) {
                wwnn = "";
            }

            // 2. 부모 HBA의 유효성 검증 (virsh nodedev-list --cap vports에서 나온 값인지 확인)
            if (!validateParentHbaFromVports(parentHbaName)) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA가 vports를 지원하지 않습니다: " + parentHbaName);
            }

            // 3. 기본 XML 생성 (WWNN/WWPN 없이)
            String basicXmlContent = generateBasicVhbaXml(parentHbaName);
            if (basicXmlContent == null) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "기본 XML 생성 실패");
            }

            // 4. 임시 XML 파일 경로 설정
            String xmlFilePath = String.format("/tmp/vhba_%s.xml", vhbaName);

            try (FileWriter writer = new FileWriter(xmlFilePath)) {
                writer.write(basicXmlContent);
            } catch (IOException e) {
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "XML 파일 생성 실패: " + e.getMessage());
            }

            // 5. virsh nodedev-create 명령 실행
            Script createCommand = new Script("/bin/bash");
            createCommand.add("-c");
            createCommand.add("/usr/bin/virsh nodedev-create " + xmlFilePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = createCommand.execute(parser);

            if (result != null) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 실패: " + result);
            }

            // 6. 생성된 디바이스 이름 추출 (Node device scsi_host5 created from vhba_host3.xml 형태에서 파싱)
            String createdDeviceName = extractCreatedDeviceNameFromOutput(parser.getLines());
            if (createdDeviceName == null) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "디바이스 이름 추출 실패");
            }

            // 7. 생성된 vHBA 검증
            if (!validateCreatedVhba(createdDeviceName)) {
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 검증 실패");
            }

            // 8. 생성된 vHBA의 실제 dumpxml을 /etc/vhba에 백업
            String actualVhbaXml = getVhbaDumpXml(createdDeviceName);
            if (actualVhbaXml != null && !actualVhbaXml.isEmpty()) {
                // /etc/vhba 디렉토리 생성 (존재하지 않는 경우)
                File vhbaDir = new File("/etc/vhba");
                if (!vhbaDir.exists()) {
                    if (vhbaDir.mkdirs()) {
                    } else {
                    }
                }

                // dumpxml에서 WWNN 추출하여 백업 파일명 생성
                String extractedWwnn = extractWwnnFromXml(actualVhbaXml);
                String backupFilePath;

                if (extractedWwnn != null && !extractedWwnn.trim().isEmpty()) {
                    // WWNN 기반 백업 파일 경로
                    backupFilePath = String.format("/etc/vhba/vhba_%s.xml", extractedWwnn);
                } else {
                    // 기본 백업 파일 경로
                    backupFilePath = String.format("/etc/vhba/%s.xml", createdDeviceName);
                }

                File backupFile = new File(backupFilePath);
                if (!backupFile.exists()) {
                    try (FileWriter writer = new FileWriter(backupFilePath)) {
                        writer.write(actualVhbaXml);
                    } catch (IOException e) {
                        logger.warn("vHBA 백업 파일 생성 실패: " + e.getMessage());
                    }
                } else {
                }
            } else {
            }

            // 9. 임시 XML 파일 정리
            cleanupXmlFile(xmlFilePath);

            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(true, vhbaName, createdDeviceName);

        } catch (Exception e) {
            logger.error("vHBA 디바이스 생성 중 오류: " + e.getMessage(), e);
            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 중 오류: " + e.getMessage());
        }
    }

    public Answer deleteHostVHbaDevice(DeleteVhbaDeviceCommand command) {
        String vhbaName = command.getVhbaName();
        String wwnn = command.getWwnn();

        try {
            String targetDeviceName = null;

            // WWNN이 제공된 경우 WWNN으로 디바이스 찾기
            if (wwnn != null && !wwnn.trim().isEmpty()) {
                targetDeviceName = findVhbaDeviceByWwnn(wwnn);
                if (targetDeviceName == null) {
                    logger.error("WWNN으로 vHBA 디바이스를 찾을 수 없습니다: " + wwnn);
                    return new DeleteVhbaDeviceAnswer(command, false, "WWNN으로 vHBA 디바이스를 찾을 수 없습니다: " + wwnn);
                }
            } else {
                if (vhbaName == null || vhbaName.trim().isEmpty()) {
                    logger.error("vHBA 이름이 제공되지 않았습니다");
                    return new DeleteVhbaDeviceAnswer(command, false, "vHBA 이름이 필요합니다");
                }
                targetDeviceName = vhbaName;
            }

            // vHBA가 VM에 할당되어 있는지 확인
            if (isVhbaAllocatedToVm(targetDeviceName)) {
                logger.error("vHBA가 VM에 할당되어 있어 삭제할 수 없습니다: " + targetDeviceName);
                return new DeleteVhbaDeviceAnswer(command, false, "vHBA가 VM에 할당되어 있어 삭제할 수 없습니다. 먼저 할당을 해제해주세요.");
            }

            // virsh nodedev-destroy 명령 실행
            Script destroyCommand = new Script("/bin/bash");
            destroyCommand.add("-c");
            destroyCommand.add("/usr/bin/virsh nodedev-destroy " + targetDeviceName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = destroyCommand.execute(parser);

            if (result != null) {
                logger.error("vHBA 삭제 실패: " + result);
                return new DeleteVhbaDeviceAnswer(command, false, "vHBA 삭제 실패: " + result);
            }

            // /etc/vhba 경로의 백업 파일 삭제
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
                    logger.warn("vHBA 백업 파일 삭제 실패: " + backupFilePath);
                }
            } else {
            }

            return new DeleteVhbaDeviceAnswer(command, true, "vHBA 디바이스가 성공적으로 삭제되었습니다: " + vhbaName);

        } catch (Exception e) {
            logger.error("vHBA 디바이스 삭제 중 오류: " + e.getMessage(), e);
            return new DeleteVhbaDeviceAnswer(command, false, "vHBA 삭제 중 오류: " + e.getMessage());
        }
    }

    // vHBA가 VM에 할당되어 있는지 확인
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
            logger.debug("vHBA 할당 상태 확인 중 오류: " + e.getMessage());
            return false;
        }
    }

    // virsh nodedev-list --cap vports에서 나온 부모 HBA 유효성 검증
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

            logger.warn("부모 HBA가 vports 지원 목록에 없음: " + parentHbaName);
            return false;
        } catch (Exception e) {
            logger.debug("부모 HBA vports 검증 중 오류: " + e.getMessage());
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

        logger.warn("생성된 디바이스명을 찾을 수 없음. 출력: " + output);
        return null;
    }

    // 생성된 vHBA 검증
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
            logger.debug("생성된 vHBA 검증 중 오류: " + e.getMessage());
            return false;
        }
    }

    // XML 파일 정리
    private void cleanupXmlFile(String xmlFilePath) {
        try {
            File xmlFile = new File(xmlFilePath);
            if (xmlFile.exists()) {
                // xmlFile.delete();
                logger.debug("임시 XML 파일 삭제: " + xmlFilePath);
            }
        } catch (Exception e) {
            logger.debug("XML 파일 정리 중 오류: " + e.getMessage());
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
                    logger.debug("특정 물리 HBA vHBA 조회 중 오류: " + e.getMessage());
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
                    logger.debug("scsi_host 디바이스 조회 중 오류: " + e.getMessage());
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
                                    logger.debug("fc_remote_ports에서 발견된 vHBA: " + vhbaName);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("fc_remote_ports 조회 중 오류: " + e.getMessage());
                }
            }

            // 발견된 vHBA 디바이스들의 상세 정보 조회
            for (String vhbaName : vhbaNames) {

                // vHBA 상세 정보 조회
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

                    // SCSI 주소 추출 - 실제 lsscsi 명령어로 추출
                    try {
                        // 먼저 전체 lsscsi 출력을 확인
                        Script lsscsiAllCommand = new Script("/bin/bash");
                        lsscsiAllCommand.add("-c");
                        lsscsiAllCommand.add("lsscsi");
                        OutputInterpreter.AllLinesParser lsscsiAllParser = new OutputInterpreter.AllLinesParser();
                        String lsscsiAllResult = lsscsiAllCommand.execute(lsscsiAllParser);

                        if (lsscsiAllResult == null && lsscsiAllParser.getLines() != null) {
                        }

                        // vHBA 이름으로 검색
                        Script scsiAddressCommand = new Script("/bin/bash");
                        scsiAddressCommand.add("-c");
                        scsiAddressCommand.add("lsscsi | grep -E '\\[.*:.*:.*:.*\\].*" + vhbaName + "' | head -1");
                        OutputInterpreter.AllLinesParser scsiAddressParser = new OutputInterpreter.AllLinesParser();
                        String scsiAddressResult = scsiAddressCommand.execute(scsiAddressParser);

                        if (scsiAddressResult == null && scsiAddressParser.getLines() != null) {
                            String scsiLine = scsiAddressParser.getLines().trim();
                            if (!scsiLine.isEmpty()) {
                                // lsscsi 출력에서 SCSI 주소 추출: [18:0:0:0] -> 18:0:0:0
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

                        // lsscsi에서 찾지 못한 경우, sysfs에서 직접 확인
                        if (scsiAddress.isEmpty()) {

                            // vHBA 이름에서 호스트 번호 추출
                            String hostNum = vhbaName.replace("scsi_host", "");

                            if (hostNum.matches("\\d+")) {
                                scsiAddress = hostNum + ":0:1:0";

                                // 실제로 해당 호스트가 존재하는지 확인
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
                        logger.error("vHBA " + vhbaName + "의 SCSI 주소 추출 중 오류: " + e.getMessage());
                    }

                    StringBuilder descBuilder = new StringBuilder();

                    if (!wwnn.isEmpty()) {
                        descBuilder.append("WWNN: ").append(wwnn);
                    }
                    if (!wwpn.isEmpty()) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append("\n");
                        }
                        descBuilder.append("WWPN: ").append(wwpn);
                    }
                    if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append("\n");
                        }
                        descBuilder.append("Fabric WWN: ").append(fabricWwn);
                    }
                    if (!scsiAddress.isEmpty()) {
                        if (descBuilder.length() > 0) {
                            descBuilder.append("\n");
                        }
                        descBuilder.append("SCSI Address: ").append(scsiAddress);
                    } else {
                    }
                }

                boolean shouldInclude = true;
                if (keyword != null && !keyword.isEmpty()) {
                    shouldInclude = parentHbaName.equals(keyword);
                }

                if (!shouldInclude) {
                    continue;
                }

                // vHBA 상태 확인
                Script statusCommand = new Script("/bin/bash");
                statusCommand.add("-c");
                statusCommand.add("virsh nodedev-info " + vhbaName + " | grep State");
                OutputInterpreter.AllLinesParser statusParser = new OutputInterpreter.AllLinesParser();
                String statusResult = statusCommand.execute(statusParser);

                if (statusResult == null && statusParser.getLines() != null) {
                    String statusLine = statusParser.getLines().trim();
                    if (statusLine.contains("State:")) {
                        status = statusLine.replaceAll(".*State:\\s*([^\\s]+).*", "$1").trim();
                    }
                }

                com.cloud.agent.api.ListVhbaDevicesCommand.VhbaDeviceInfo vhbaInfo =
                    new com.cloud.agent.api.ListVhbaDevicesCommand.VhbaDeviceInfo(
                        vhbaName, parentHbaName, wwnn, wwpn, description, status
                    );
                vhbaDevices.add(vhbaInfo);
            }

            return new com.cloud.agent.api.ListVhbaDevicesAnswer(true, vhbaDevices);

        } catch (Exception e) {
            logger.error("vHBA 디바이스 조회 중 오류: " + e.getMessage(), e);
            return new com.cloud.agent.api.ListVhbaDevicesAnswer(false, new ArrayList<>());
        }
    }

    // scsi_host 디바이스가 vHBA인지 확인하는 메서드
    private boolean isVhbaDevice(String deviceName) {
        try {
            Script checkCommand = new Script("/bin/bash");
            checkCommand.add("-c");
            checkCommand.add("virsh nodedev-dumpxml " + deviceName + " | grep -c 'fc_host'");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = checkCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String count = parser.getLines().trim();
                return !count.equals("0");
            }
            return false;
        } catch (Exception e) {
            logger.debug("vHBA 확인 중 오류: " + e.getMessage());
            return false;
        }
    }

    // 경로에서 vHBA 이름 추출하는 메서드
    private String extractVhbaNameFromPath(String path) {
        try {
            // /sys/class/fc_remote_ports/rport-1:0-0 형태에서 scsi_host 이름 추출
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
            logger.debug("vHBA 이름 추출 중 오류: " + e.getMessage());
            return null;
        }
    }

    protected Answer listVhbaCapableHbas(Command command) {
        List<String> hostDevicesText = new ArrayList<>();
        List<String> hostDevicesNames = new ArrayList<>();

        try {
            // vHBA를 지원하는 HBA 조회
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
                                descBuilder.append("\n(Max vPorts: ").append(maxVports).append(")");
                            }
                            if (!wwnn.isEmpty()) {
                                descBuilder.append("\nWWNN: ").append(wwnn);
                            }
                            if (!wwpn.isEmpty()) {
                                descBuilder.append("\nWWPN: ").append(wwpn);
                            }
                            hbaDescription = descBuilder.toString();
                        }

                        hostDevicesNames.add(hbaName);
                        hostDevicesText.add(hbaDescription);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("vHBA 지원 HBA 조회 중 오류 발생: " + e.getMessage());
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
        }else{
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
                if (!isUsbDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    logger.warn("USB device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    return new UpdateHostUsbDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, usbXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} USB device: {}", action, result);
                return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostUsbDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} USB device: {}", action, e.getMessage(), e);
            return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }
    protected Answer updateHostLunDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        // UpdateHostLunDeviceCommand에서 hostDeviceName 추출
        String hostDeviceName = null;
        if (command instanceof UpdateHostLunDeviceCommand) {
            UpdateHostLunDeviceCommand lunCmd = (UpdateHostLunDeviceCommand) command;
            hostDeviceName = lunCmd.getHostDeviceName();
        }

        // LUN 디바이스 이름을 추출하여 고유한 파일명 생성
        String lunDeviceName = extractDeviceNameFromLunXml(xmlConfig);
        if (lunDeviceName == null) {
            lunDeviceName = "unknown";
        }
        String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, lunDeviceName);
        try {
            // 디바이스 경로 추출 및 검증
            String devicePath = extractDevicePathFromLunXml(xmlConfig);

            // dm 디바이스 또는 멀티패스 LUN인지 확인하고 적절한 XML 생성
            if (devicePath != null && isAttach) {
                if (isDmDevice(devicePath)) {
                    // dm 디바이스는 <disk> 방식으로 XML 재생성
                    String uuid = extractUuidFromLunXml(xmlConfig);
                    xmlConfig = generateLunUuidXmlConfig(devicePath, uuid, null);
                } else if (isMultipathLun(devicePath)) {
                    // 멀티패스 LUN은 <hostdev> 방식으로 XML 재생성
                    String uuid = extractUuidFromLunXml(xmlConfig);
                    xmlConfig = generateMultipathLunXmlConfig(devicePath, uuid);
                }
            }

            if (devicePath != null && isAttach) {
                // LUN과 SCSI 간 상호 배타적 할당 검증
                if (isDeviceAllocatedInOtherType(devicePath, vmName, "LUN")) {
                    logger.warn("Device {} is already allocated as SCSI device in another VM", devicePath);
                    return new UpdateHostLunDeviceAnswer(false, "Device is already allocated as SCSI device in another VM. Please remove it from SCSI allocation first.");
                }

                // by-id 경로가 존재하지 않으면, 요청의 hostdevicesname에서 베이스 경로를 추출하여 대체 by-id를 찾고 XML을 보정
                try {
                    if (devicePath.startsWith("/dev/disk/by-id/") && !isDeviceExists(devicePath)) {

                        // hostDeviceName에서 베이스 경로 추출 (우선순위 1)
                        String baseGuess = null;
                        if (hostDeviceName != null && !hostDeviceName.isEmpty()) {
                            // hostDeviceName에서 베이스 경로 추출 (예: "/dev/sdf (scsi-35ace42e4350075fa)" -> "/dev/sdf")
                            java.util.regex.Pattern hostNamePattern = java.util.regex.Pattern.compile("(/dev/\\S+)");
                            java.util.regex.Matcher hostNameMatcher = hostNamePattern.matcher(hostDeviceName);
                            if (hostNameMatcher.find()) {
                                baseGuess = hostNameMatcher.group(1);
                            }
                        }

                        // XML에서 베이스 경로 추출 (우선순위 2)
                        if (baseGuess == null) {
                            java.util.regex.Pattern patternName = java.util.regex.Pattern.compile("(/dev/[^<\\n]+) \\\\(");
                            java.util.regex.Matcher mName = patternName.matcher(xmlConfig);
                            if (mName.find()) {
                                baseGuess = mName.group(1);
                            }
                        }
                        if (baseGuess == null) {
                            // table에선 base만 전달된 경우도 있어 괄호가 없을 수 있음
                            java.util.regex.Matcher mBase = java.util.regex.Pattern.compile("(/dev/\\S+)").matcher(xmlConfig);
                            if (mBase.find()) {
                                baseGuess = mBase.group(1);
                            }
                        }
                        if (baseGuess == null) {
                            // XML 주석에서 fallback 경로 추출
                            java.util.regex.Matcher mFallback = java.util.regex.Pattern.compile("<!-- Fallback device path: (/dev/\\S+) -->").matcher(xmlConfig);
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
                    logger.error("LUN device validation failed: {}", validationResult);
                    return new UpdateHostLunDeviceAnswer(false, validationResult);
                }
            }

            // 매핑된 SCSI 디바이스도 함께 처리
            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);
            String mappedScsiDevice = null;
            if (mapping != null && mapping.getScsiDevicePath() != null) {
                mappedScsiDevice = mapping.getScsiDevicePath();
            }

            // target dev 충돌 방지: 현재 VM에서 미사용인 안전한 이름으로 보정
            xmlConfig = ensureUniqueLunTargetDev(vmName, xmlConfig);

            // XML 파일 생성 (기존 파일이 있어도 덮어씀 - 고유한 파일명이므로 안전)
            try (PrintWriter writer = new PrintWriter(lunXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, lunXmlPath);
            } else {
                // detach 시도 전에 실제 VM에 해당 디바이스가 붙어있는지 확인
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

            // 매핑된 SCSI 디바이스가 있으면 함께 처리
            if (mappedScsiDevice != null) {
                try {
                    // SCSI 디바이스용 XML 생성
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
                    new java.io.File(scsiXmlPath).delete();

                } catch (Exception e) {
                    logger.warn("Error processing mapped SCSI device {}: {}", mappedScsiDevice, e.getMessage());
                }
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} LUN device: {}", action, e.getMessage(), e);
            return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach, e.getMessage());
        }
    }

    protected Answer updateHostHbaDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        // HBA 디바이스 이름을 추출하여 고유한 파일명 생성
        String hbaDeviceName = extractAdapterNameFromXml(xmlConfig);
        if (hbaDeviceName == null) {
            hbaDeviceName = "unknown";
        }
        String hbaXmlPath = String.format("/tmp/hba_device_%s_%s.xml", vmName, hbaDeviceName);
        try {
            // XML 파일 생성 (기존 파일이 있어도 덮어씀 - 고유한 파일명이므로 안전)
            try (PrintWriter writer = new PrintWriter(hbaXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, hbaXmlPath);
            } else {
                // detach 시도 전에 실제 VM에 해당 디바이스가 붙어있는지 확인
                if (!isDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    logger.warn("Device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    // 실제로 붙어있지 않아도 성공으로 처리 (DB 상태만 정리)
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
            logger.error("Error {} HBA device: {}", action, e.getMessage(), e);
            return new UpdateHostHbaDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }

        protected Answer updateHostVHbaDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        // vHBA 디바이스 이름을 추출하여 고유한 파일명 생성
        String vhbaDeviceName = extractDeviceNameFromVhbaXml(xmlConfig);
        if (vhbaDeviceName == null) {
            vhbaDeviceName = "unknown";
        }
        String vhbaXmlPath = String.format("/tmp/vhba_device_%s_%s.xml", vmName, vhbaDeviceName);

        try {
            // XML 파일 생성 (기존 파일이 있어도 덮어씀 - 고유한 파일명이므로 안전)
            try (PrintWriter writer = new PrintWriter(vhbaXmlPath)) {
                writer.write(xmlConfig);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, vhbaXmlPath);
            } else {
                // detach 시도 전에 실제 VM에 해당 디바이스가 붙어있는지 확인
                if (!isVhbaDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    logger.warn("vHBA device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    // 실제로 붙어있지 않아도 성공으로 처리 (DB 상태만 정리)
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
            logger.error("Error {} vHBA device: {}", action, e.getMessage(), e);
            return new UpdateHostVhbaDeviceAnswer(false, vhbaDeviceName, vmName, xmlConfig, isAttach);
        }
    }

    protected Answer updateHostScsiDevices(UpdateHostScsiDeviceCommand command, String vmName, String xmlConfig, boolean isAttach) {
        // SCSI 디바이스 이름을 추출하여 고유한 파일명 생성
        String scsiDeviceName = extractDeviceNameFromScsiXml(xmlConfig);
        if (scsiDeviceName == null) {
            scsiDeviceName = "unknown";
        }
        String scsiXmlPath = String.format("/tmp/scsi_device_%s_%s.xml", vmName, scsiDeviceName);
        try {
            // 디바이스 경로 추출 및 매핑된 LUN 디바이스 확인
            String devicePath = extractDeviceNameFromScsiXml(xmlConfig);

            // SCSI와 LUN 간 상호 배타적 할당 검증
            if (isAttach && isDeviceAllocatedInOtherType(devicePath, vmName, "SCSI")) {
                logger.warn("Device {} is already allocated as LUN device in another VM", devicePath);
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
                    logger.warn("SCSI device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, scsiXmlPath);
            }


            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                String lower = result.toLowerCase();
                if (!isAttach && (lower.contains("device not found") || lower.contains("host scsi device") && lower.contains("not found"))) {
                    logger.warn("virsh reported device not found during detach; treating as success. Details: {}", result);
                    return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                logger.error("Failed to {} SCSI device: {}", action, result);
                return new UpdateHostScsiDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            if (mappedLunDevice != null) {
                try {
                    // LUN 디바이스용 XML 생성
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
                        logger.warn("Failed to {} mapped LUN device {}: {}",
                                   isAttach ? "attach" : "detach", mappedLunDevice, lunResult);
                    } else {
                    }

                    // 임시 파일 정리
                    new java.io.File(lunXmlPath).delete();

                } catch (Exception e) {
                    logger.warn("Error processing mapped LUN device {}: {}", mappedLunDevice, e.getMessage());
                }
            }

            String action = isAttach ? "attached to" : "detached from";
            return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} SCSI device: {}", action, e.getMessage(), e);
            return new UpdateHostScsiDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }

    // VM에 실제로 디바이스가 붙어있는지 확인하는 메서드
    private boolean isDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            // HBA 디바이스 이름을 추출하여 고유한 파일명 생성
            String hbaDeviceName = extractAdapterNameFromXml(xmlConfig);
            if (hbaDeviceName == null) {
                hbaDeviceName = "unknown";
            }
            String hbaXmlPath = String.format("/tmp/hba_device_%s_%s.xml", vmName, hbaDeviceName);

            // 해당 XML 파일이 존재하는지 확인
            File xmlFile = new File(hbaXmlPath);
            if (!xmlFile.exists()) {
                logger.warn("XML file does not exist for device check: {}", hbaXmlPath);
                return false;
            }

            // virsh dumpxml로 VM의 현재 상태 확인
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

            // XML에서 adapter name 추출
            String adapterName = extractAdapterNameFromXml(xmlConfig);
            if (adapterName == null) {
                logger.warn("Could not extract adapter name from XML config");
                return false;
            }

            // VM XML에 해당 adapter가 있는지 확인
            boolean deviceFound = vmXml.contains("<adapter name='" + adapterName + "'/>") ||
                                vmXml.contains("<adapter name=\"" + adapterName + "\"/>");
            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    // XML에서 adapter name 추출 (HBA용)
    private String extractAdapterNameFromXml(String xmlConfig) {
        try {
            // <adapter name='scsi_host14'/> 형태에서 scsi_host14 추출
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("name=['\"]([^'\"]+)['\"]");
            java.util.regex.Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting adapter name from XML: {}", e.getMessage());
            return null;
        }
    }

    // LUN XML에서 디바이스 이름 추출
    private String extractDeviceNameFromLunXml(String xmlConfig) {
        try {
            // <source dev='/dev/sdc'/> 형태에서 sdc 추출
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dev=['\"]([^'\"]+)['\"]");
            java.util.regex.Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                String devicePath = matcher.group(1);
                // 경로에서 디바이스 이름만 추출
                String[] parts = devicePath.split("/");
                if (parts.length > 0) {
                    return parts[parts.length - 1];
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting device name from LUN XML: {}", e.getMessage());
            return null;
        }
    }

    // LUN XML에서 디바이스 경로 추출
    private String extractDevicePathFromLunXml(String xmlConfig) {
        try {
            // <source dev='/dev/sdc'/> 형태에서 전체 경로 추출
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dev=['\"]([^'\"]+)['\"]");
            java.util.regex.Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting device path from LUN XML: {}", e.getMessage());
            return null;
        }
    }

    private String extractUuidFromLunXml(String xmlConfig) {
        try {
            // <serial>WWN</serial> 형태에서 UUID 추출
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<serial>([^<]+)</serial>");
            java.util.regex.Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting UUID from LUN XML: {}", e.getMessage());
            return null;
        }
    }

    // LUN 디바이스 할당 가능성 검증 (매핑된 SCSI 디바이스도 함께 확인)
    private String validateLunDeviceForAttachment(String devicePath, String vmName) {
        try {
            // 1. 디바이스 존재 여부 확인
            if (!isDeviceExists(devicePath)) {
                return "디바이스가 존재하지 않습니다: " + devicePath;
            }

            // 2. 파티션 여부 확인 (파티션은 할당할 수 없음)
            if (isPartitionDevice(devicePath)) {
                return "파티션 디바이스는 할당할 수 없습니다. 전체 디스크를 사용해주세요: " + devicePath;
            }

            // 3. 이미 다른 VM에 할당되어 있는지 확인 (LUN 디바이스)
            if (isLunDeviceAllocatedToOtherVm(devicePath, vmName)) {
                return "디바이스가 이미 다른 VM에 할당되어 있습니다: " + devicePath;
            }

            // 4. 매핑된 SCSI 디바이스가 다른 VM에 할당되어 있는지 확인
            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);
            if (mapping != null && mapping.getScsiDevicePath() != null) {
                if (isLunDeviceAllocatedToOtherVm(mapping.getScsiDevicePath(), vmName)) {
                    return "매핑된 SCSI 디바이스가 이미 다른 VM에 할당되어 있습니다: " + mapping.getScsiDevicePath();
                }
            }

            // 5. 마운트되어 있는지 확인
            if (isDeviceMounted(devicePath)) {
                return "디바이스가 마운트되어 있어 할당할 수 없습니다: " + devicePath;
            }

            // 6. LVM에서 사용 중인지 확인
            if (isDeviceUsedByLvm(devicePath)) {
                return "디바이스가 LVM에서 사용 중이어 할당할 수 없습니다: " + devicePath;
            }

            // 7. 스왑으로 사용 중인지 확인
            if (isDeviceUsedAsSwap(devicePath)) {
                return "디바이스가 스왑으로 사용 중이어 할당할 수 없습니다: " + devicePath;
            }

            // 7. 파티션 테이블이 있는지 확인 (파티션이 있는 디스크는 할당 불가)
            if (hasPartitionTable(devicePath)) {
                return "디스크에 파티션 테이블이 있어 할당할 수 없습니다. 파티션을 삭제한 후 시도해주세요: " + devicePath;
            }

            // 8. 디바이스가 읽기 전용인지 확인
            if (isDeviceReadOnly(devicePath)) {
                return "디바이스가 읽기 전용이어 할당할 수 없습니다: " + devicePath;
            }

            // 9. 디바이스 크기가 너무 작은지 확인 (최소 1MB)
            if (getDeviceSizeBytes(devicePath) < 1024 * 1024) {
                return "디바이스 크기가 너무 작아 할당할 수 없습니다 (최소 1MB 필요): " + devicePath;
            }

            return null; // 검증 통과
        } catch (Exception e) {
            logger.error("LUN device validation error: {}", e.getMessage(), e);
            return "디바이스 검증 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    // 디바이스 존재 여부 확인
    private boolean isDeviceExists(String devicePath) {
        // by-id 경로에 대해서는 검증을 우회하여 항상 true 반환
        if (devicePath != null && devicePath.startsWith("/dev/disk/by-id/")) {
            return true;
        }

        try {
            java.io.File device = new java.io.File(devicePath);
            if (!device.exists()) {
                return false;
            }

            // 심볼릭 링크인 경우 실제 타겟 확인
            if (java.nio.file.Files.isSymbolicLink(device.toPath())) {
                try {
                    java.nio.file.Path realPath = device.toPath().toRealPath();
                    java.io.File realDevice = realPath.toFile();
                    if (!realDevice.exists()) {
                        return false;
                    }
                } catch (Exception e) {
                    logger.debug("Error resolving symbolic link {}: {}", devicePath, e.getMessage());
                    return false;
                }
            }

            // 블록 디바이스인지 확인 (stat 명령어 사용)
            Script statCommand = new Script("/bin/bash");
            statCommand.add("-c");
            statCommand.add("stat -c '%F' " + devicePath + " 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = statCommand.execute(parser);

            if (result == null && parser.getLines() != null) {
                String fileType = parser.getLines().trim();
                return fileType.contains("block special file");
            }

            // stat 명령어가 실패하면 기본적으로 파일 존재 여부만 확인
            return device.isFile();
        } catch (Exception e) {
            logger.debug("Error checking device existence {}: {}", devicePath, e.getMessage());
            return false;
        }
    }

    // 파티션 디바이스인지 확인
    private boolean isPartitionDevice(String devicePath) {
        try {
            // /dev/sda1, /dev/sdb2 등 파티션 번호가 있는지 확인
            String deviceName = new java.io.File(devicePath).getName();

            // 숫자로 끝나는 디바이스명이 파티션인지 확인
            if (deviceName.matches(".*\\d+$")) {
                // 하지만 nvme 디바이스는 예외 (nvme0n1p1 형태)
                if (deviceName.startsWith("nvme")) {
                    // nvme 디바이스는 p가 포함된 경우만 파티션
                    return deviceName.contains("p") && deviceName.matches(".*p\\d+$");
                }
                // sd, vd, xvd 등은 숫자로 끝나면 파티션
                return deviceName.matches("(sd|vd|xvd|hd).*\\d+$");
            }

            return false;
        } catch (Exception e) {
            logger.debug("Error checking partition device: " + e.getMessage());
            return false;
        }
    }

    // 다른 VM에 할당되어 있는지 확인
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

    // 디바이스가 마운트되어 있는지 확인
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

    // LVM에서 사용 중인지 확인
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

    // 스왑으로 사용 중인지 확인
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

    // 파티션 테이블이 있는지 확인
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

    // 디바이스가 읽기 전용인지 확인
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

    // 디바이스 크기 확인 (바이트 단위)
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

    // LUN 디바이스가 VM에 실제로 붙어있는지 확인하는 메서드
    private boolean isLunDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            // LUN 디바이스 이름을 추출하여 고유한 파일명 생성
            String lunDeviceName = extractDeviceNameFromLunXml(xmlConfig);
            if (lunDeviceName == null) {
                lunDeviceName = "unknown";
            }
            String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, lunDeviceName);

            // 해당 XML 파일이 존재하는지 확인
            File xmlFile = new File(lunXmlPath);
            if (!xmlFile.exists()) {
                logger.warn("XML file does not exist for device check: {}", lunXmlPath);
                return false;
            }

            // virsh dumpxml로 VM의 현재 상태 확인
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

            // XML에서 source dev 추출
            String sourceDev = extractDeviceNameFromLunXml(xmlConfig);
            if (sourceDev == null) {
                logger.warn("Could not extract source dev from XML config");
                return false;
            }

            // VM XML에 해당 디바이스가 있는지 확인
            boolean deviceFound = vmXml.contains("dev='" + sourceDev + "'") ||
                                vmXml.contains("dev=\"" + sourceDev + "\"");
            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking LUN device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    // SCSI XML에서 디바이스 이름 추출
    private String extractDeviceNameFromScsiXml(String xmlConfig) {
        try {
            // <adapter name='scsi_host14'/> 형태에서 scsi_host14 추출
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("name=['\"]([^'\"]+)['\"]");
            java.util.regex.Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting device name from SCSI XML: {}", e.getMessage());
            return null;
        }
    }

    // SCSI 디바이스가 VM에 실제로 붙어있는지 확인하는 메서드
    private boolean isScsiDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            // SCSI 디바이스 이름을 추출하여 고유한 파일명 생성
            String scsiDeviceName = extractDeviceNameFromScsiXml(xmlConfig);
            if (scsiDeviceName == null) {
                scsiDeviceName = "unknown";
            }
            String scsiXmlPath = String.format("/tmp/scsi_device_%s_%s.xml", vmName, scsiDeviceName);

            // 해당 XML 파일이 존재하는지 확인
            File xmlFile = new File(scsiXmlPath);
            if (!xmlFile.exists()) {
                logger.warn("XML file does not exist for device check: {}", scsiXmlPath);
                return false;
            }

            // virsh dumpxml로 VM의 현재 상태 확인
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

            // XML에서 adapter name 추출
            String adapterName = extractDeviceNameFromScsiXml(xmlConfig);
            if (adapterName == null) {
                logger.warn("Could not extract adapter name from XML config");
                return false;
            }

            // VM XML에 해당 adapter가 있는지 확인
            boolean deviceFound = vmXml.contains("<adapter name='" + adapterName + "'/>") ||
                                vmXml.contains("<adapter name=\"" + adapterName + "\"/>");

            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking SCSI device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    // vHBA XML에서 디바이스 이름 추출
    private String extractDeviceNameFromVhbaXml(String xmlConfig) {
        try {
            java.util.regex.Pattern parentPattern = java.util.regex.Pattern.compile("<parent>([^<]+)</parent>");
            java.util.regex.Matcher parentMatcher = parentPattern.matcher(xmlConfig);
            if (parentMatcher.find()) {
                return parentMatcher.group(1);
            }

            java.util.regex.Pattern adapterPattern = java.util.regex.Pattern.compile("<adapter\\s+name=['\"]([^'\"]+)['\"][^>]*?>");
            java.util.regex.Matcher adapterMatcher = adapterPattern.matcher(xmlConfig);
            if (adapterMatcher.find()) {
                return adapterMatcher.group(1);
            }

            return null;
        } catch (Exception e) {
            logger.debug("Error extracting device name from vHBA XML: {}", e.getMessage());
            return null;
        }
    }

    // USB 디바이스가 VM에 실제로 붙어있는지 확인하는 메서드
    private boolean isUsbDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            Script dumpCommand = new Script("virsh");
            dumpCommand.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = dumpCommand.execute(parser);

            if (result != null) {
                logger.warn("Failed to get VM XML for USB device check: {}", result);
                return false;
            }

            String vmXml = parser.getLines();
            if (vmXml == null || vmXml.isEmpty()) {
                logger.warn("Empty VM XML for USB device check");
                return false;
            }

            String busMatch = extractBusFromUsbXml(xmlConfig);
            String deviceMatch = extractDeviceFromUsbXml(xmlConfig);

            if (busMatch == null || deviceMatch == null) {
                logger.warn("Could not extract bus/device from USB XML config");
                return false;
            }
            boolean deviceFound = vmXml.contains("bus='" + busMatch + "'") &&
                                  vmXml.contains("device='" + deviceMatch + "'");

            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking USB device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    // vHBA 디바이스가 VM에 실제로 붙어있는지 확인하는 메서드
    private boolean isVhbaDeviceActuallyAttachedToVm(String vmName, String xmlConfig) {
        try {
            // vHBA 디바이스 이름을 추출하여 고유한 파일명 생성
            String vhbaDeviceName = extractDeviceNameFromVhbaXml(xmlConfig);
            if (vhbaDeviceName == null) {
                vhbaDeviceName = "unknown";
            }
            // updateHostVHbaDevices에서 생성하는 경로와 접두어를 동일하게 사용
            String vhbaXmlPath = String.format("/tmp/vhba_device_%s_%s.xml", vmName, vhbaDeviceName);

            // 해당 XML 파일이 존재하는지 확인
            File xmlFile = new File(vhbaXmlPath);
            if (!xmlFile.exists()) {
                logger.warn("XML file does not exist for device check: {}", vhbaXmlPath);
                return false;
            }

            // virsh dumpxml로 VM의 현재 상태 확인
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

            // XML에서 대상 이름 추출 (parent 또는 adapter name)
            String targetName = extractDeviceNameFromVhbaXml(xmlConfig);
            if (targetName == null) {
                logger.warn("Could not extract device/parent name from XML config");
                return false;
            }

            // VM XML에 해당 adapter/parent 흔적이 있는지 확인
            boolean deviceFound = vmXml.contains("<adapter name='" + targetName + "'/>") ||
                                  vmXml.contains("<adapter name=\"" + targetName + "\"/>") ||
                                  vmXml.contains("<parent>" + targetName + "</parent>");

            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking vHBA device attachment for VM: {}", vmName, e);
            return false;
        }
    }
    // USB XML에서 bus 주소 추출
    private String extractBusFromUsbXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("bus='(0x[0-9A-Fa-f]+)'");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting bus from USB XML: {}", e.getMessage());
            return null;
        }
    }

    // USB XML에서 device 주소 추출
    private String extractDeviceFromUsbXml(String xmlConfig) {
        try {
            Pattern pattern = Pattern.compile("device='(0x[0-9A-Fa-f]+)'");
            Matcher matcher = pattern.matcher(xmlConfig);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error extracting device from USB XML: {}", e.getMessage());
            return null;
        }
    }

    private String extractWwnnFromXml(String xmlContent) {
        try {
            // XML에서 wwnn 속성 또는 태그 찾기
            String[] patterns = {
                "wwnn=['\"]([0-9A-Fa-f]{16})['\"]",
                "<wwnn>([0-9A-Fa-f]{16})</wwnn>",
                "wwnn=\"([0-9A-Fa-f]{16})\""
            };

            for (String pattern : patterns) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher m = p.matcher(xmlContent);
                if (m.find()) {
                    return m.group(1);
                }
            }
            return null;
        } catch (Exception e) {
            logger.debug("XML에서 WWNN 추출 실패: " + e.getMessage());
            return null;
        }
    }

    // vHBA 백업 파일 목록 조회 메서드
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
            logger.error("vHBA 백업 파일 목록 조회 중 오류: " + e.getMessage(), e);
            return new Answer(null, false, "vHBA 백업 파일 목록 조회 중 오류: " + e.getMessage());
        }
    }

    // 생성된 vHBA의 실제 dumpxml 가져오기
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
                logger.warn("vHBA dumpxml 가져오기 실패: " + vhbaName + " - " + result);
                return null;
            }
        } catch (Exception e) {
            logger.error("vHBA dumpxml 가져오기 중 오류: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * LUN과 SCSI 디바이스 간의 매핑을 관리하는 클래스
     */
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

    /**
     * 시스템의 모든 디바이스 매핑 정보를 수집하는 메서드
     */
    protected Map<String, DeviceMapping> buildDeviceMapping() {
        Map<String, DeviceMapping> deviceMappings = new HashMap<>();

        try {
            // LUN 디바이스 정보 수집
            ListHostLunDeviceAnswer lunAnswer = listHostLunDevicesFast();
            if (lunAnswer != null && lunAnswer.getResult()) {
                List<String> lunNames = lunAnswer.getHostDevicesNames();
                List<String> lunScsiAddresses = lunAnswer.getScsiAddresses();

                for (int i = 0; i < lunNames.size(); i++) {
                    String lunDevice = lunNames.get(i);
                    String scsiAddress = lunScsiAddresses.get(i);

                    if (scsiAddress != null && !scsiAddress.isEmpty()) {
                        // SCSI 주소로부터 실제 물리적 디바이스 경로 찾기
                        String physicalPath = resolvePhysicalDeviceFromScsiAddress(scsiAddress);
                        DeviceMapping mapping = new DeviceMapping(lunDevice, null, scsiAddress, physicalPath);
                        deviceMappings.put(lunDevice, mapping);
                    }
                }
            }

            // SCSI 디바이스 정보 수집
            com.cloud.agent.api.ListHostScsiDeviceAnswer scsiAnswer = listHostScsiDevicesFast();
            if (scsiAnswer != null && scsiAnswer.getResult()) {
                List<String> scsiNames = scsiAnswer.getHostDevicesNames();

                for (String scsiDevice : scsiNames) {
                    String scsiAddress = getScsiAddress(scsiDevice);
                    if (scsiAddress != null && !scsiAddress.isEmpty()) {
                        String physicalPath = resolvePhysicalDeviceFromScsiAddress(scsiAddress);

                        // 기존 LUN 매핑과 같은 물리적 디바이스인지 확인
                        DeviceMapping existingMapping = findMappingByPhysicalPath(deviceMappings, physicalPath);
                        if (existingMapping != null) {
                            // 기존 매핑에 SCSI 디바이스 정보 추가
                            existingMapping = new DeviceMapping(existingMapping.getLunDevicePath(),
                                                               scsiDevice,
                                                               scsiAddress,
                                                               physicalPath);
                            deviceMappings.put(existingMapping.getLunDevicePath(), existingMapping);
                        } else {
                            // 새로운 매핑 생성
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

    /**
     * SCSI 주소로부터 실제 물리적 디바이스 경로를 찾는 메서드
     */
    private String resolvePhysicalDeviceFromScsiAddress(String scsiAddress) {
        try {
            // /sys/class/scsi_device에서 SCSI 주소로 디바이스 찾기
            java.io.File scsiClassDir = new java.io.File("/sys/class/scsi_device");
            java.io.File[] scsiDevices = scsiClassDir.listFiles();

            if (scsiDevices != null) {
                for (java.io.File scsiDevice : scsiDevices) {
                    String deviceName = scsiDevice.getName();
                    if (deviceName.equals(scsiAddress)) {
                        // device/block 디렉토리에서 실제 블록 디바이스 찾기
                        java.io.File blockDir = new java.io.File(scsiDevice, "device/block");
                        if (blockDir.exists()) {
                            java.io.File[] blockDevices = blockDir.listFiles();
                            if (blockDevices != null && blockDevices.length > 0) {
                                String blockDeviceName = blockDevices[0].getName();
                                return "/dev/" + blockDeviceName;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error resolving physical device from SCSI address: " + e.getMessage());
        }

        return null;
    }

    /**
     * 물리적 디바이스 경로로 기존 매핑을 찾는 메서드
     */
    private DeviceMapping findMappingByPhysicalPath(Map<String, DeviceMapping> mappings, String physicalPath) {
        if (physicalPath == null) return null;

        for (DeviceMapping mapping : mappings.values()) {
            if (physicalPath.equals(mapping.getPhysicalDevicePath())) {
                return mapping;
            }
        }
        return null;
    }

    /**
     * 디바이스 경로로 매핑된 다른 타입의 디바이스를 찾는 메서드
     */
    protected DeviceMapping findMappedDevice(String devicePath, Map<String, DeviceMapping> mappings) {
        // 직접 매핑에서 찾기
        DeviceMapping directMapping = mappings.get(devicePath);
        if (directMapping != null) {
            return directMapping;
        }

        // 물리적 디바이스 경로로 매핑 찾기
        String physicalPath = resolvePhysicalDeviceFromScsiAddress(getScsiAddress(devicePath));
        if (physicalPath != null) {
            return findMappingByPhysicalPath(mappings, physicalPath);
        }

        return null;
    }

    /**
     * 디바이스가 다른 타입으로도 할당되어 있는지 확인하는 메서드
     */
    protected boolean isDeviceAllocatedInOtherType(String devicePath, String currentVmName, String deviceType) {
        try {
            Map<String, DeviceMapping> mappings = buildDeviceMapping();
            DeviceMapping mapping = findMappedDevice(devicePath, mappings);

            if (mapping == null) {
                return false;
            }

            // LUN 디바이스인 경우 SCSI 디바이스 할당 상태 확인
            if ("LUN".equals(deviceType) && mapping.getScsiDevicePath() != null) {
                return isScsiDeviceAllocatedToVm(mapping.getScsiDevicePath(), currentVmName);
            }

            // SCSI 디바이스인 경우 LUN 디바이스 할당 상태 확인
            if ("SCSI".equals(deviceType) && mapping.getLunDevicePath() != null) {
                return isLunDeviceAllocatedToVm(mapping.getLunDevicePath(), currentVmName);
            }

            return false;

        } catch (Exception e) {
            logger.error("Error checking device allocation in other type: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * SCSI 디바이스가 다른 VM에 할당되어 있는지 확인
     */
    private boolean isScsiDeviceAllocatedToVm(String scsiDevicePath, String currentVmName) {
        try {
            // 모든 호스트에서 SCSI 디바이스 할당 상태 확인
            Script cmd = new Script("/bin/bash");
            cmd.add("-c");
            cmd.add("find /var/lib/libvirt/qemu/ -name '*.xml' -exec grep -l '" + scsiDevicePath + "' {} \\; 2>/dev/null");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null && parser.getLines() != null && !parser.getLines().trim().isEmpty()) {
                String[] xmlFiles = parser.getLines().trim().split("\n");
                for (String xmlFile : xmlFiles) {
                    if (xmlFile.contains(currentVmName)) {
                        continue; // 현재 VM은 제외
                    }

                    // XML 파일에서 SCSI 디바이스가 실제로 할당되어 있는지 확인
                    if (isScsiDeviceInXml(xmlFile, scsiDevicePath)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            logger.debug("Error checking SCSI device allocation: " + e.getMessage());
            return false;
        }
    }

    /**
     * LUN 디바이스가 다른 VM에 할당되어 있는지 확인
     */
    private boolean isLunDeviceAllocatedToVm(String lunDevicePath, String currentVmName) {
        try {
            // 모든 호스트에서 LUN 디바이스 할당 상태 확인
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

                    // XML 파일에서 LUN 디바이스가 실제로 할당되어 있는지 확인
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

            // SCSI XML 생성
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
            // SCSI XML에서 기본 구조 추출
            String scsiDeviceName = extractDeviceNameFromScsiXml(scsiXmlConfig);
            if (scsiDeviceName == null) {
                return null;
            }

            // LUN XML 생성
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
        java.util.HashSet<String> used = new java.util.HashSet<>();
        try {
            Script virshDump = new Script("virsh");
            virshDump.add("dumpxml", vmName);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String res = virshDump.execute(parser);
            if (res == null) {
                String xml = parser.getLines();
                if (xml != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("<target\\s+dev='(sd[a-z]+)'\\s+bus='scsi'").matcher(xml);
                    while (m.find()) {
                        used.add(m.group(1));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to collect used SCSI target devs: " + e.getMessage());
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
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<target\\s+dev='(sd[a-z]+)'\\s+bus='scsi'\\s*/?>").matcher(xmlConfig);
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
                return xmlConfig.replaceFirst("dev='" + java.util.regex.Pattern.quote(current) + "'", "dev='" + chosen + "'");
            }
            return xmlConfig;
        } catch (Exception e) {
            logger.debug("Failed to ensure unique target dev: " + e.getMessage());
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
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(byIdDir)) {
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
            logger.debug("findExistingByIdForBaseDevice error: " + e.getMessage());
        }
        return null;
    }
}

