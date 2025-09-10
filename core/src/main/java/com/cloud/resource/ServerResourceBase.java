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

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.CreateVhbaDeviceCommand;
import com.cloud.agent.api.DeleteVhbaDeviceAnswer;
import com.cloud.agent.api.DeleteVhbaDeviceCommand;
import com.cloud.agent.api.ListHostDeviceAnswer;
import com.cloud.agent.api.ListHostHbaDeviceAnswer;
import com.cloud.agent.api.ListHostLunDeviceAnswer;
import com.cloud.agent.api.ListHostUsbDeviceAnswer;
import com.cloud.agent.api.ListVhbaDevicesCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.UpdateHostHbaDeviceAnswer;
import com.cloud.agent.api.UpdateHostLunDeviceAnswer;
import com.cloud.agent.api.UpdateHostScsiDeviceAnswer;
import com.cloud.agent.api.UpdateHostScsiDeviceCommand;
import com.cloud.agent.api.UpdateHostUsbDeviceAnswer;
import com.cloud.agent.api.UpdateHostVhbaDeviceAnswer;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.cloudstack.storage.command.browser.ListDataStoreObjectsAnswer;
import org.apache.cloudstack.storage.command.browser.ListRbdObjectsAnswer;
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

            for (int i = 0; i < blockdevices.length(); i++) {
                JSONObject device = blockdevices.getJSONObject(i);
                addLunDeviceRecursive(device, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);
            }

            // multipath 장치들 추가
            addMultipathDevices(hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);

            return new ListHostLunDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions, scsiAddresses);

        } catch (Exception e) {
            logger.error("Error listing LUN devices: " + e.getMessage(), e);
            return new ListHostLunDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private void addLunDeviceRecursive(JSONObject device, List<String> names, List<String> texts, List<Boolean> hasPartitions, List<String> scsiAddresses) {
        String name = device.getString("name");
        String type = device.getString("type");
        String size = device.optString("size", "");
        String mountpoint = device.optString("mountpoint", "");

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
            logger.debug("LUN device UUID mapping: " + dmMap);
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
                logger.warn("Directory /dev/disk/by-id does not exist");
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
        StringBuilder xml = new StringBuilder();
        xml.append("<disk type='block' device='lun'>\n");
        xml.append("  <driver name='qemu' type='raw'/>\n");
        xml.append("  <source dev='/dev/disk/by-uuid/").append(uuid).append("'/>\n");
        xml.append("  <target dev='").append(getTargetDeviceName(devicePath)).append("' bus='scsi'/>\n");
        if (scsiAddress != null && !scsiAddress.isEmpty()) {
            String[] parts = scsiAddress.split(":");
            if (parts.length >= 4) {
                xml.append("  <address type='drive' controller='0' bus='").append(parts[1]).append("' target='").append(parts[2]).append("' unit='").append(parts[3]).append("'/>\n");
            }
        }

        xml.append("  <serial>").append(uuid).append("</serial>\n");
        xml.append("</disk>");
        return xml.toString();
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
                    targetDev = deviceName;
                }
            }
        }
        return targetDev;
    }

    /**
     * multipath 장치들을 추가하는 메서드
     */
    private void addMultipathDevices(List<String> names, List<String> texts, List<Boolean> hasPartitions, List<String> scsiAddresses) {
        try {
            // multipath -l 명령어 실행
            Script cmd = new Script("/usr/sbin/multipath");
            cmd.add("-l");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                logger.warn("Failed to execute multipath -l command: " + result);
                return;
            }

            String[] lines = parser.getLines().split("\n");
            for (String line : lines) {
                if (line.contains("mpath")) {

                    String[] parts = line.trim().split("\\s+");
                    String dmDevice = null;

                    for (String part : parts) {
                        if (part.startsWith("dm-")) {
                            dmDevice = part;
                            break;
                        }
                    }

                    if (dmDevice == null) {
                        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("dm-\\d+");
                        java.util.regex.Matcher matcher = pattern.matcher(line);
                        if (matcher.find()) {
                            dmDevice = matcher.group();
                        }
                    }

                    if (dmDevice != null) {
                        String devicePath = "/dev/mapper/" + dmDevice;

                        // 파티션 여부 확인
                        boolean hasPartition = hasPartitionRecursiveForDevice(devicePath);

                        // 파티션이 없는 경우만 추가
                        if (!hasPartition) {
                            names.add(devicePath);

                            StringBuilder deviceInfo = new StringBuilder();
                            deviceInfo.append("TYPE: multipath");

                            // 디바이스 크기 가져오기
                            String size = getDeviceSize(devicePath);
                            if (size != null) {
                                deviceInfo.append("\nSIZE: ").append(size);
                            }

                            deviceInfo.append("\nHAS_PARTITIONS: false");

                            // 사용 중인지 여부 확인 (동적 확인)
                            boolean isInUse = isDeviceInUse(devicePath);
                            String usageStatus = isInUse ? "사용중" : "사용안함";
                            deviceInfo.append("\nIN_USE: ").append(isInUse ? "true" : "false");
                            deviceInfo.append("\nUSAGE_STATUS: ").append(usageStatus);

                            // SCSI 주소 정보 추가
                            String scsiAddress = getScsiAddress(devicePath);
                            if (scsiAddress != null) {
                                deviceInfo.append("\nSCSI_ADDRESS: ").append(scsiAddress);
                            }

                            deviceInfo.append("\nMULTIPATH_DEVICE: true");

                            texts.add(deviceInfo.toString());
                            hasPartitions.add(false);
                            scsiAddresses.add(scsiAddress != null ? scsiAddress : "");

                            logger.debug("Added multipath device: " + devicePath + " (no partitions)");
                        } else {
                            logger.debug("Skipped multipath device: " + devicePath + " (has partitions)");
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error adding multipath devices: " + e.getMessage(), e);
        }
    }

    /**
     * 특정 디바이스의 파티션 여부를 확인하는 메서드
     */
    private boolean hasPartitionRecursiveForDevice(String devicePath) {
        try {
            Script cmd = new Script("/usr/bin/lsblk");
            cmd.add("--json", "--paths", "--output", "NAME,TYPE", devicePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result == null) {
                JSONObject json = new JSONObject(parser.getLines());
                JSONArray blockdevices = json.getJSONArray("blockdevices");

                if (blockdevices.length() > 0) {
                    JSONObject device = blockdevices.getJSONObject(0);
                    return hasPartitionRecursive(device);
                }
            }

        } catch (Exception e) {
            logger.debug("Error checking partitions for device " + devicePath + ": " + e.getMessage());
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
                return false;
            }
            return true;
        }

        if (device.has("children")) {
            JSONArray children = device.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                String childType = child.optString("type", "");

                // 파티션이 있으면 true
                if ("part".equals(childType)) {
                    return true;
                }

                // LVM 볼륨이 있으면 true
                if ("lvm".equals(childType)) {
                    return true;
                }

                if (hasPartitionRecursive(child)) {
                    return true;
                }
            }
        }

        return false;
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
            Script cmd = new Script("/bin/cat");
            cmd.add(scsiDevicePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);
            if (result == null && parser.getLines() != null && !parser.getLines().isEmpty()) {
                String scsiAddress = parser.getLines().trim();
                logger.debug("Found SCSI address for {}: {}", deviceName, scsiAddress);
                return scsiAddress;
            }

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
                logger.debug("Found physical device for Ceph OSD {}: {}", deviceName, physicalDevice);

                // 물리 디바이스의 SCSI 주소 가져오기
                String physicalScsiAddress = getPhysicalScsiAddress(physicalDevice);
                if (physicalScsiAddress != null) {
                    String[] parts = physicalScsiAddress.split(":");
                    if (parts.length >= 4) {
                        // unit 번호를 1씩 증가시켜 가상 주소 생성
                        int unit = Integer.parseInt(parts[3]) + 1;
                        String virtualAddress = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + unit;
                        logger.debug("Generated virtual SCSI address: {} -> {}", physicalScsiAddress, virtualAddress);
                        return virtualAddress;
                    }
                }
            }

            // 물리 디바이스를 찾지 못한 경우 기본 가상 주소 생성
            String defaultVirtualAddress = "0:0:277:1"; // Ceph OSD용 기본 주소
            logger.debug("Using default virtual SCSI address for Ceph OSD: {}", defaultVirtualAddress);
            return defaultVirtualAddress;

        } catch (Exception e) {
            logger.debug("Error generating virtual SCSI address for Ceph OSD {}: {}", deviceName, e.getMessage());
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
            logger.debug("Error getting physical SCSI address for {}: {}", deviceName, e.getMessage());
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

            logger.debug("Generated basic vHBA XML for parent {}: {}", parentHbaName, xml.toString());
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
                        logger.debug("Found vHBA device {} for WWNN {}", device, wwnn);
                        return device;
                    }
                }
            }

            logger.debug("No vHBA device found for WWNN: {}", wwnn);
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
                logger.debug("LUN 디바이스가 VM에 할당됨: " + deviceName);
                return true;
            }

            // 2. 마운트 포인트 확인
            Script mountCommand = new Script("/bin/bash");
            mountCommand.add("-c");
            mountCommand.add("mount | grep -q '" + deviceName + "'");
            String mountResult = mountCommand.execute(null);
            if (mountResult == null) {
                logger.debug("LUN 디바이스가 마운트됨: " + deviceName);
                return true; // 마운트되어 있음
            }

            // 3. LVM 사용 확인
            Script lvmCommand = new Script("/bin/bash");
            lvmCommand.add("-c");
            lvmCommand.add("lvs --noheadings -o lv_name,vg_name 2>/dev/null | grep -q '" + deviceName + "'");
            String lvmResult = lvmCommand.execute(null);
            if (lvmResult == null) {
                logger.debug("LUN 디바이스가 LVM에서 사용 중: " + deviceName);
                return true; // LVM에서 사용 중
            }

            // 4. 스왑 확인
            Script swapCommand = new Script("/bin/bash");
            swapCommand.add("-c");
            swapCommand.add("swapon --show | grep -q '" + deviceName + "'");
            String swapResult = swapCommand.execute(null);
            if (swapResult == null) {
                logger.debug("LUN 디바이스가 스왑으로 사용 중: " + deviceName);
                return true; // 스왑으로 사용 중
            }

            // 5. 파티션 테이블 확인
            Script partCommand = new Script("/bin/bash");
            partCommand.add("-c");
            partCommand.add("fdisk -l " + deviceName + " 2>/dev/null | grep -q 'Disklabel type:'");
            String partResult = partCommand.execute(null);
            if (partResult == null) {
                logger.debug("LUN 디바이스에 파티션 테이블 존재: " + deviceName);
                return true; // 파티션 테이블이 있음
            }

            logger.debug("LUN 디바이스 사용 안함: " + deviceName);
            return false; // 사용되지 않음
        } catch (Exception e) {
            logger.debug("디바이스 사용 여부 확인 중 오류: " + e.getMessage());
            return true; // 오류 시 안전하게 사용 중으로 간주
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
                    logger.debug("LUN 디바이스가 VM에 할당됨: " + deviceName);
                }
                return isAllocated;
            }
            return false;
        } catch (Exception e) {
            logger.debug("LUN 디바이스 할당 상태 확인 중 오류: " + e.getMessage());
            return false;
        }
    }

    // lsscsi -g 명령을 사용하여 SCSI 디바이스 정보를 조회하는 메서드
    public Answer listHostScsiDevices(Command command) {
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> hostDevicesText = new ArrayList<>();
        List<Boolean> hasPartitions = new ArrayList<>();
        Map<String, String> deviceMappings = new HashMap<>(); // SCSI -> LUN 매핑
        try {
            Script cmd = new Script("/usr/bin/lsscsi");
            cmd.add("-g");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                logger.error("Failed to execute lsscsi -g command: " + result);
                return new com.cloud.agent.api.ListHostScsiDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions);
            }

            String[] lines = parser.getLines().split("\n");
            for (String line : lines) {
                // 예시: [0:0:275:0]  disk    ATA      HFS3T8G3H2X069N  DZ02  /dev/sda  /dev/sg0
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                if (tokens.length < 7) continue;
                String scsiAddr = tokens[0]; // [0:0:275:0]
                String type = tokens[1];     // disk
                String vendor = tokens[2];   // ATA
                String model = tokens[3];    // HFS3T8G3H2X069N
                String rev = tokens[4];      // DZ02
                String dev = tokens[5];      // /dev/sda
                String sgdev = tokens[6];    // /dev/sg0
                String name = sgdev; // sg 디바이스를 기본 이름으로 사용
                StringBuilder text = new StringBuilder();
                text.append("SCSI Address: ").append(scsiAddr).append("\n");
                text.append("Type: ").append(type).append("\n");
                text.append("Vendor: ").append(vendor).append("\n");
                text.append("Model: ").append(model).append("\n");
                text.append("Revision: ").append(rev).append("\n");
                text.append("Device: ").append(dev).append("\n");
                hostDevicesNames.add(name);
                hostDevicesText.add(text.toString());
                hasPartitions.add(false);

                // SCSI 디바이스와 LUN 디바이스 매핑 저장
                if (dev != null && !dev.isEmpty()) {
                    deviceMappings.put(name, dev);
                }
            }
            return new com.cloud.agent.api.ListHostScsiDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions);
        } catch (Exception e) {
            logger.error("Error listing SCSI devices with lsscsi -g: " + e.getMessage(), e);
            return new com.cloud.agent.api.ListHostScsiDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
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
                        logger.debug("Added vHBA device: " + vhbaName);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("vHBA 조회 중 오류 발생 (일반적인 상황): " + e.getMessage());
        }

        logger.info("HBA 디바이스 조회 완료: " + hostDevicesNames.size() + "개 발견");
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
            logger.debug("HBA 상세 정보 조회 중 오류: " + e.getMessage());
        }

        return details.toString();
    }

    public Answer createHostVHbaDevice(CreateVhbaDeviceCommand command, String parentHbaName, String wwnn, String wwpn, String vhbaName, String xmlContent) {

        try {
            // 1. 입력 파라미터 검증
            if (parentHbaName == null || parentHbaName.trim().isEmpty()) {
                logger.error("부모 HBA 이름이 제공되지 않았습니다");
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA 이름이 필요합니다");
            }
            if (wwnn == null) {
                wwnn = "";
            }

            // 2. 부모 HBA의 유효성 검증 (virsh nodedev-list --cap vports에서 나온 값인지 확인)
            if (!validateParentHbaFromVports(parentHbaName)) {
                logger.error("부모 HBA가 vports 지원 목록에 없습니다: " + parentHbaName);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA가 vports를 지원하지 않습니다: " + parentHbaName);
            }

            // 3. 기본 XML 생성 (WWNN/WWPN 없이)
            String basicXmlContent = generateBasicVhbaXml(parentHbaName);
            if (basicXmlContent == null) {
                logger.error("기본 XML 생성 실패");
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "기본 XML 생성 실패");
            }

            logger.info("생성된 기본 XML 내용:\n" + basicXmlContent);

            // 4. 임시 XML 파일 경로 설정
            String xmlFilePath = String.format("/tmp/vhba_%s.xml", vhbaName);

            try (FileWriter writer = new FileWriter(xmlFilePath)) {
                writer.write(basicXmlContent);
                logger.info("vHBA XML 파일 생성: " + xmlFilePath);
            } catch (IOException e) {
                logger.error("XML 파일 생성 실패: " + e.getMessage());
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "XML 파일 생성 실패: " + e.getMessage());
            }

            // 5. virsh nodedev-create 명령 실행
            Script createCommand = new Script("/bin/bash");
            createCommand.add("-c");
            createCommand.add("/usr/bin/virsh nodedev-create " + xmlFilePath);
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = createCommand.execute(parser);

            if (result != null) {
                logger.error("vHBA 생성 실패: " + result);
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 실패: " + result);
            }

            // 6. 생성된 디바이스 이름 추출 (Node device scsi_host5 created from vhba_host3.xml 형태에서 파싱)
            String createdDeviceName = extractCreatedDeviceNameFromOutput(parser.getLines());
            if (createdDeviceName == null) {
                logger.error("생성된 vHBA 디바이스 이름을 추출할 수 없습니다");
                cleanupXmlFile(xmlFilePath);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "디바이스 이름 추출 실패");
            }

            // 7. 생성된 vHBA 검증
            if (!validateCreatedVhba(createdDeviceName)) {
                logger.error("생성된 vHBA 검증 실패: " + createdDeviceName);
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
                        logger.info("Created /etc/vhba directory");
                    } else {
                        logger.warn("Failed to create /etc/vhba directory");
                    }
                }

                // dumpxml에서 WWNN 추출하여 백업 파일명 생성
                String extractedWwnn = extractWwnnFromXml(actualVhbaXml);
                String backupFilePath;

                if (extractedWwnn != null && !extractedWwnn.trim().isEmpty()) {
                    // WWNN 기반 백업 파일 경로
                    backupFilePath = String.format("/etc/vhba/vhba_%s.xml", extractedWwnn);
                    logger.info("생성된 vHBA에서 추출된 WWNN: " + extractedWwnn);
                } else {
                    // 기본 백업 파일 경로
                    backupFilePath = String.format("/etc/vhba/%s.xml", createdDeviceName);
                    logger.info("WWNN을 추출할 수 없어 기본 파일명 사용: " + backupFilePath);
                }

                File backupFile = new File(backupFilePath);
                if (!backupFile.exists()) {
                    try (FileWriter writer = new FileWriter(backupFilePath)) {
                        writer.write(actualVhbaXml);
                        logger.info("vHBA 백업 파일 생성: " + backupFilePath);
                    } catch (IOException e) {
                        logger.warn("vHBA 백업 파일 생성 실패: " + e.getMessage());
                    }
                } else {
                    logger.info("vHBA 백업 파일이 이미 존재함: " + backupFilePath);
                }
            } else {
                logger.warn("생성된 vHBA의 dumpxml을 가져올 수 없어 백업을 건너뜀: " + createdDeviceName);
            }

            // 9. 임시 XML 파일 정리
            cleanupXmlFile(xmlFilePath);

            logger.info("vHBA 디바이스 생성 성공: " + vhbaName + " -> " + createdDeviceName);
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
                logger.info("WWNN으로 찾은 vHBA 디바이스: " + targetDeviceName);
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
                    logger.info("vHBA 백업 파일 삭제 성공: " + backupFilePath);
                } else {
                    logger.warn("vHBA 백업 파일 삭제 실패: " + backupFilePath);
                }
            } else {
                logger.info("vHBA 백업 파일이 존재하지 않음: " + backupFilePath);
            }

            logger.info("vHBA 디바이스 삭제 성공: " + targetDeviceName);
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
                        logger.info("부모 HBA가 vports 지원 목록에 존재함: " + parentHbaName);
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

    // XML 내용 검증
    private boolean validateXmlContent(String xmlContent) {
        try {
            return xmlContent.contains("<device>") &&
                   xmlContent.contains("<parent>") &&
                   xmlContent.contains("<capability type='scsi_host'>");
        } catch (Exception e) {
            return false;
        }
    }

    // 생성된 디바이스 이름 추출 (Node device scsi_host5 created from vhba_host3.xml 형태에서 파싱)
    private String extractCreatedDeviceNameFromOutput(String output) {
        if (output == null) {
            return null;
        }

        String[] lines = output.split("\\n");
        for (String line : lines) {
            // "Node device scsi_host5 created from vhba_host3.xml" 형태에서 scsi_host5 추출
            if (line.contains("Node device") && line.contains("created from")) {
                String[] parts = line.split(" ");
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].startsWith("scsi_host")) {
                        String deviceName = parts[i];
                        logger.info("생성된 디바이스명 추출: " + deviceName);
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
            logger.info("vHBA 디바이스 조회 시작 - 키워드: " + keyword);
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
                                    logger.debug("특정 물리 HBA에서 발견된 vHBA: " + vhbaName + " (부모: " + keyword + ")");
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
                        logger.info("발견된 scsi_host 디바이스 수: " + scsiHostLines.length);

                        for (String scsiHostLine : scsiHostLines) {
                            String deviceName = scsiHostLine.trim();
                            if (!deviceName.isEmpty()) {
                                // 각 scsi_host 디바이스가 vHBA인지 확인
                                if (isVhbaDevice(deviceName)) {
                                    vhbaNames.add(deviceName);
                                    logger.debug("vHBA로 확인된 디바이스: " + deviceName);
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

            logger.info("총 발견된 vHBA 디바이스 수: " + vhbaNames.size());

            // 발견된 vHBA 디바이스들의 상세 정보 조회
            for (String vhbaName : vhbaNames) {
                logger.debug("vHBA 디바이스 처리 중: " + vhbaName);

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
                            logger.debug("vHBA " + vhbaName + "의 부모 HBA: " + parentHbaName);
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
                        logger.info("vHBA " + vhbaName + " SCSI 주소 검색 시작");
                        
                        // 먼저 전체 lsscsi 출력을 확인
                        Script lsscsiAllCommand = new Script("/bin/bash");
                        lsscsiAllCommand.add("-c");
                        lsscsiAllCommand.add("lsscsi");
                        OutputInterpreter.AllLinesParser lsscsiAllParser = new OutputInterpreter.AllLinesParser();
                        String lsscsiAllResult = lsscsiAllCommand.execute(lsscsiAllParser);
                        
                        logger.info("vHBA " + vhbaName + " 전체 lsscsi 출력: " + (lsscsiAllResult != null ? lsscsiAllResult : "null"));
                        if (lsscsiAllResult == null && lsscsiAllParser.getLines() != null) {
                            String allLines = lsscsiAllParser.getLines();
                            logger.info("vHBA " + vhbaName + " 전체 lsscsi 내용: '" + allLines + "'");
                        }
                        
                        // vHBA 이름으로 검색
                        Script scsiAddressCommand = new Script("/bin/bash");
                        scsiAddressCommand.add("-c");
                        scsiAddressCommand.add("lsscsi | grep -E '\\[.*:.*:.*:.*\\].*" + vhbaName + "' | head -1");
                        OutputInterpreter.AllLinesParser scsiAddressParser = new OutputInterpreter.AllLinesParser();
                        String scsiAddressResult = scsiAddressCommand.execute(scsiAddressParser);

                        logger.info("vHBA " + vhbaName + " SCSI 주소 검색 결과: " + (scsiAddressResult != null ? scsiAddressResult : "null"));
                        if (scsiAddressResult == null && scsiAddressParser.getLines() != null) {
                            String scsiLine = scsiAddressParser.getLines().trim();
                            logger.info("vHBA " + vhbaName + " lsscsi 출력: '" + scsiLine + "'");
                            if (!scsiLine.isEmpty()) {
                                // lsscsi 출력에서 SCSI 주소 추출: [18:0:0:0] -> 18:0:0:0
                                String[] parts = scsiLine.split("\\s+");
                                logger.info("vHBA " + vhbaName + " lsscsi 파싱된 부분들: " + java.util.Arrays.toString(parts));
                                if (parts.length > 0) {
                                    String scsiPart = parts[0];
                                    logger.info("vHBA " + vhbaName + " 첫 번째 부분: '" + scsiPart + "'");
                                    if (scsiPart.startsWith("[") && scsiPart.endsWith("]")) {
                                        scsiAddress = scsiPart.substring(1, scsiPart.length() - 1);
                                        logger.info("vHBA " + vhbaName + "의 실제 SCSI 주소: " + scsiAddress);
                                    } else {
                                        logger.info("vHBA " + vhbaName + " 첫 번째 부분이 [로 시작하지 않음: '" + scsiPart + "'");
                                    }
                                }
                            } else {
                                logger.info("vHBA " + vhbaName + " lsscsi 출력이 비어있음");
                            }
                        } else {
                            logger.info("vHBA " + vhbaName + " lsscsi 명령어 실행 실패 또는 출력 없음");
                        }

                        // lsscsi에서 찾지 못한 경우, sysfs에서 직접 확인
                        if (scsiAddress.isEmpty()) {
                            logger.info("vHBA " + vhbaName + " lsscsi에서 SCSI 주소를 찾지 못함, sysfs 확인 중...");
                            
                            // vHBA 이름에서 호스트 번호 추출
                            String hostNum = vhbaName.replace("scsi_host", "");
                            logger.info("vHBA " + vhbaName + " 추출된 호스트 번호: '" + hostNum + "'");
                            
                            if (hostNum.matches("\\d+")) {
                                // 호스트 번호가 유효한 경우 기본 SCSI 주소 생성
                                scsiAddress = hostNum + ":0:1:0";
                                logger.info("vHBA " + vhbaName + "의 SCSI 주소 (기본값): " + scsiAddress);
                                
                                // 실제로 해당 호스트가 존재하는지 확인
                                Script hostCheckCommand = new Script("/bin/bash");
                                hostCheckCommand.add("-c");
                                hostCheckCommand.add("ls /sys/class/scsi_host/" + vhbaName + " 2>/dev/null || echo 'not_found'");
                                OutputInterpreter.AllLinesParser hostCheckParser = new OutputInterpreter.AllLinesParser();
                                String hostCheckResult = hostCheckCommand.execute(hostCheckParser);
                                
                                logger.info("vHBA " + vhbaName + " 호스트 존재 확인: " + (hostCheckResult != null ? hostCheckResult : "null"));
                                if (hostCheckResult == null && hostCheckParser.getLines() != null) {
                                    String checkResult = hostCheckParser.getLines().trim();
                                    logger.info("vHBA " + vhbaName + " 호스트 확인 결과: '" + checkResult + "'");
                                    if (checkResult.equals("not_found")) {
                                        logger.info("vHBA " + vhbaName + " 호스트가 실제로 존재하지 않음, SCSI 주소 초기화");
                                        scsiAddress = "";
                                    }
                                }
                            } else {
                                logger.info("vHBA " + vhbaName + " 호스트 번호가 숫자가 아님: '" + hostNum + "'");
                            }
                        }

                        logger.info("vHBA " + vhbaName + " 최종 SCSI 주소: " + (scsiAddress.isEmpty() ? "없음" : scsiAddress));
                    } catch (Exception e) {
                        logger.error("vHBA " + vhbaName + "의 SCSI 주소 추출 중 오류: " + e.getMessage());
                    }

                    StringBuilder descBuilder = new StringBuilder();
                    logger.info("vHBA " + vhbaName + " description 생성 시작");
                    logger.info("vHBA " + vhbaName + " WWNN: '" + wwnn + "'");
                    logger.info("vHBA " + vhbaName + " WWPN: '" + wwpn + "'");
                    logger.info("vHBA " + vhbaName + " Fabric WWN: '" + fabricWwn + "'");
                    logger.info("vHBA " + vhbaName + " SCSI Address: '" + scsiAddress + "'");

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
                        logger.info("vHBA " + vhbaName + " SCSI Address를 description에 추가함");
                    } else {
                        logger.info("vHBA " + vhbaName + " SCSI Address가 비어있어 description에 추가하지 않음");
                    }
                    description = descBuilder.toString();
                    logger.info("vHBA " + vhbaName + " 최종 description: '" + description + "'");
                }

                boolean shouldInclude = true;
                if (keyword != null && !keyword.isEmpty()) {
                    shouldInclude = parentHbaName.equals(keyword);

                    logger.debug("키워드 필터링: keyword=" + keyword +
                               ", parentHbaName=" + parentHbaName +
                               ", shouldInclude=" + shouldInclude);
                }

                if (!shouldInclude) {
                    logger.debug("vHBA " + vhbaName + " 필터링됨");
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
                logger.debug("vHBA 디바이스 추가됨: " + vhbaName);
            }

            logger.info("vHBA 디바이스 조회 완료: " + vhbaDevices.size() + "개 발견");
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
            // XML 파일이 없을 경우에만 생성
            File xmlFile = new File(usbXmlPath);
            if (!xmlFile.exists()) {
                try (PrintWriter writer = new PrintWriter(usbXmlPath)) {
                    writer.write(xmlConfig);
                }
                logger.info("Generated XML file: {} for VM: {}", usbXmlPath, vmName);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, usbXmlPath);
            } else {
                virshCmd.add("detach-device", vmName, usbXmlPath);
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} USB device: {}", action, result);
                return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} USB device for VM {}", action, vmName);
            return new UpdateHostUsbDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} USB device: {}", action, e.getMessage(), e);
            return new UpdateHostUsbDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }
    protected Answer updateHostLunDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        // LUN 디바이스 이름을 추출하여 고유한 파일명 생성
        String lunDeviceName = extractDeviceNameFromLunXml(xmlConfig);
        if (lunDeviceName == null) {
            lunDeviceName = "unknown";
        }
        String lunXmlPath = String.format("/tmp/lun_device_%s_%s.xml", vmName, lunDeviceName);
        try {
            // XML 파일 생성 (기존 파일이 있어도 덮어씀 - 고유한 파일명이므로 안전)
            try (PrintWriter writer = new PrintWriter(lunXmlPath)) {
                writer.write(xmlConfig);
            }
            logger.info("Generated XML file: {} for VM: {}", lunXmlPath, vmName);

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, lunXmlPath);
            } else {
                // detach 시도 전에 실제 VM에 해당 디바이스가 붙어있는지 확인
                if (!isLunDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    logger.warn("LUN device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    // 실제로 붙어있지 않아도 성공으로 처리 (DB 상태만 정리)
                    return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, lunXmlPath);
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} LUN device: {}", action, result);
                return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} LUN device for VM {}", action, vmName);
            return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} LUN device: {}", action, e.getMessage(), e);
            return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach);
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
            logger.info("Generated XML file: {} for VM: {}", hbaXmlPath, vmName);

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
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} HBA device: {}", action, result);
                return new UpdateHostHbaDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} HBA device for VM {}", action, vmName);
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
            logger.info("Generated XML file: {} for VM: {}", vhbaXmlPath, vmName);

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
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} vHBA device: {}", action, result);
                return new UpdateHostVhbaDeviceAnswer(false, vhbaDeviceName, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} vHBA device for VM {}", action, vmName);
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
            // XML 파일 생성 (기존 파일이 있어도 덮어씀 - 고유한 파일명이므로 안전)
            try (PrintWriter writer = new PrintWriter(scsiXmlPath)) {
                writer.write(xmlConfig);
            }
            logger.info("Generated XML file: {} for VM: {}", scsiXmlPath, vmName);

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, scsiXmlPath);
            } else {
                // detach 시도 전에 실제 VM에 해당 디바이스가 붙어있는지 확인
                if (!isScsiDeviceActuallyAttachedToVm(vmName, xmlConfig)) {
                    logger.warn("SCSI device is not actually attached to VM: {}. Skipping detach operation.", vmName);
                    // 실제로 붙어있지 않아도 성공으로 처리 (DB 상태만 정리)
                    return new UpdateHostScsiDeviceAnswer(true, vmName, xmlConfig, isAttach);
                }
                virshCmd.add("detach-device", vmName, scsiXmlPath);
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} SCSI device: {}", action, result);
                return new UpdateHostScsiDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} SCSI device for VM {}", action, vmName);
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

            logger.info("Device attachment check for VM: {}, adapter: {}, file: {}, found: {}", vmName, adapterName, hbaXmlPath, deviceFound);
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

            logger.info("LUN device attachment check for VM: {}, device: {}, file: {}, found: {}", vmName, sourceDev, lunXmlPath, deviceFound);
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

            logger.info("SCSI device attachment check for VM: {}, adapter: {}, file: {}, found: {}", vmName, adapterName, scsiXmlPath, deviceFound);
            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking SCSI device attachment for VM: {}", vmName, e);
            return false;
        }
    }

    // vHBA XML에서 디바이스 이름 추출
    private String extractDeviceNameFromVhbaXml(String xmlConfig) {
        try {
            // 1) 우선 <parent>scsi_host14</parent> 형태를 시도
            java.util.regex.Pattern parentPattern = java.util.regex.Pattern.compile("<parent>([^<]+)</parent>");
            java.util.regex.Matcher parentMatcher = parentPattern.matcher(xmlConfig);
            if (parentMatcher.find()) {
                return parentMatcher.group(1);
            }

            // 2) 없으면 <adapter name='scsi_host14'/> 또는 <adapter name='scsi_host14'> 형태에서 name 추출
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

            logger.info("vHBA device attachment check for VM: {}, target: {}, file: {}, found: {}", vmName, targetName, vhbaXmlPath, deviceFound);
            return deviceFound;

        } catch (Exception e) {
            logger.error("Error checking vHBA device attachment for VM: {}", vmName, e);
            return false;
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
        logger.info("vHBA 백업 파일 목록 조회 시작");

        try {
            File vhbaDir = new File("/etc/vhba");
            if (!vhbaDir.exists()) {
                logger.info("/etc/vhba 디렉토리가 존재하지 않습니다");
                return new Answer(null, true, "백업 파일이 없습니다");
            }

            File[] backupFiles = vhbaDir.listFiles((dir, name) -> name.endsWith(".xml"));
            if (backupFiles == null || backupFiles.length == 0) {
                logger.info("vHBA 백업 파일이 없습니다");
                return new Answer(null, true, "백업 파일이 없습니다");
            }

            List<String> backupList = new ArrayList<>();
            for (File file : backupFiles) {
                String vhbaName = file.getName().replace(".xml", "");
                backupList.add(vhbaName);
                logger.info("발견된 vHBA 백업: " + vhbaName);
            }

            logger.info("vHBA 백업 파일 목록 조회 완료: " + backupList.size() + "개");
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
                logger.info("vHBA dumpxml 가져오기 성공: " + vhbaName);
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
}
