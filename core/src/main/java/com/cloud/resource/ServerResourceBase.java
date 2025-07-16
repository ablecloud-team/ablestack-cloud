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
import com.cloud.agent.api.UpdateHostVhbaDeviceCommand;
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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.cloudstack.storage.command.browser.ListDataStoreObjectsAnswer;
import org.apache.cloudstack.storage.command.browser.ListRbdObjectsAnswer;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

            Script cmd = new Script("/usr/bin/lsblk");
            cmd.add("--json", "--paths", "--output", "NAME,TYPE,SIZE,MOUNTPOINT");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = cmd.execute(parser);

            if (result != null) {
                logger.error("Failed to execute lsblk command: " + result);
                return new ListHostLunDeviceAnswer(false, hostDevicesNames, hostDevicesText, hasPartitions);
            }

            JSONObject json = new JSONObject(parser.getLines());
            JSONArray blockdevices = json.getJSONArray("blockdevices");

            // multipath 서비스 상태 확인
            boolean isMultipathActive = checkMultipathStatus();

            for (int i = 0; i < blockdevices.length(); i++) {
                JSONObject device = blockdevices.getJSONObject(i);

                // 파티션이 아닌 디스크만 처리
                if (!"part".equals(device.getString("type"))) {
                    String name = device.getString("name");
                    String size = device.getString("size");

                    // 파티션 존재 여부 확인
                    boolean hasPartition = hasPartitionRecursive(device);

                    StringBuilder info = new StringBuilder();
                    if (isMultipathActive && name.startsWith("/dev/disk/by-path/")) {
                        // info.append("Multipath LUN Device: ").append(name);
                    } else {
                        // info.append("LUN Device: ").append(name);
                    }
                    info.append(size);
                    if (hasPartition) {
                        info.append(" (").append(hasPartition ? "has partitions" : "no partitions").append(")");
                    }

                    hostDevicesNames.add(name);
                    hostDevicesText.add(info.toString());
                    hasPartitions.add(hasPartition);

                    if (logger.isDebugEnabled()) {
                        logger.debug("Found LUN device: " + info.toString());
                    }
                }
            }

            return new ListHostLunDeviceAnswer(true, hostDevicesNames, hostDevicesText, hasPartitions);

        } catch (Exception e) {
            logger.error("Error listing LUN devices: " + e.getMessage(), e);
            return new ListHostLunDeviceAnswer(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private boolean checkMultipathStatus() {
        Script cmd = new Script("systemctl");
        cmd.add("is-active", "multipathd");
        String result = cmd.execute(null);
        return "active".equals(result != null ? result.trim() : "");
    }

    private boolean hasPartitionRecursive(JSONObject device) {
        // children이 없으면 false
        if (!device.has("children")) {
            return false;
        }
        JSONArray children = device.getJSONArray("children");
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.getJSONObject(i);
            String type = child.optString("type", "");
            if ("part".equals(type)) {
                return true;
            }
            if (hasPartitionRecursive(child)) {
                return true;
            }
        }
        return false;
    }

    // lsscsi -g 명령을 사용하여 SCSI 디바이스 정보를 조회하는 메서드
    public Answer listHostScsiDevices(Command command) {
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> hostDevicesText = new ArrayList<>();
        List<Boolean> hasPartitions = new ArrayList<>();
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
                text.append("SG Device: ").append(sgdev);
                hostDevicesNames.add(name);
                hostDevicesText.add(text.toString());
                hasPartitions.add(false);
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
        List<String> deviceTypes = new ArrayList<>(); // 물리 HBA인지 vHBA인지 구분
        List<String> parentHbaNames = new ArrayList<>(); // vHBA의 경우 부모 HBA 이름
        // 1. lspci로 물리 HBA 조회 (PCI 패스스루용)
        /*
        try {
            Script lspciCommand = new Script("/bin/bash");
            lspciCommand.add("-c");
            lspciCommand.add("lspci | grep -i 'scsi\\|sas\\|fibre\\|raid\\|hba'");
            OutputInterpreter.AllLinesParser lspciParser = new OutputInterpreter.AllLinesParser();
            String lspciResult = lspciCommand.execute(lspciParser);
            if (lspciResult == null && lspciParser.getLines() != null) {
                String[] lines = lspciParser.getLines().split("\\n");
                for (String line : lines) {
                    String[] parts = line.split(" ", 2);
                    if (parts.length >= 2) {
                        String pciName = parts[0].trim(); // PCI 이름 (0000:01:00.0)
                        String description = parts[1].trim(); // 설명 (RAID bus controller)
                        if (!hostDevicesNames.contains(pciName)) { // 중복 방지
                            hostDevicesNames.add(pciName);
                            hostDevicesText.add(description);
                            deviceTypes.add("physical");
                            parentHbaNames.add("");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("물리 HBA(lspci) 조회 중 오류 발생: " + e.getMessage());
        }
        */
        // 2. virsh nodedev-list --cap vports로 vHBA 지원 물리 HBA도 추가(중복 방지)
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
                        String vhbaDescription = "Virtual HBA Device";
                        String parentHbaName = "";
                        if (vhbaInfoResult == null && vhbaInfoParser.getLines() != null) {
                            String[] infoLines = vhbaInfoParser.getLines().split("\\n");
                            for (String infoLine : infoLines) {
                                if (infoLine.contains("<parent>")) {
                                    parentHbaName = infoLine.replaceAll("<[^>]*>", "").trim();
                                } else if (infoLine.contains("<name>")) {
                                    String name = infoLine.replaceAll("<[^>]*>", "").trim();
                                    vhbaDescription = "Virtual HBA: " + name;
                                }
                            }
                        }
                        hostDevicesNames.add(vhbaName);
                        hostDevicesText.add(vhbaDescription);
                        deviceTypes.add("virtual");
                        parentHbaNames.add(parentHbaName);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("vHBA 조회 중 오류 발생 (일반적인 상황): " + e.getMessage());
        }
        return new ListHostHbaDeviceAnswer(true, hostDevicesNames, hostDevicesText, deviceTypes, parentHbaNames);
    }

    // vHBA 지원 물리 HBA 디바이스의 상세 정보 조회
    private String getHbaDeviceDetailsFromVports(String hbaName) {
        StringBuilder details = new StringBuilder();
        details.append("vHBA Capable HBA: ").append(hbaName);

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

                // 상세 정보 조합
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
                    details.append(" Fabric WWN: ").append(fabricWwn);
                }
            }
        } catch (Exception e) {
            logger.debug("HBA 상세 정보 조회 중 오류: " + e.getMessage());
        }

        return details.toString();
    }

    public Answer createHostVHbaDevice(CreateVhbaDeviceCommand command, String parentHbaName, String wwnn, String wwpn, String vhbaName, String xmlContent) {
        logger.info("vHBA 생성 시작 - 부모 HBA: " + parentHbaName + ", vHBA 이름: " + vhbaName);

        try {
            // 1. 입력 파라미터 검증
            if (parentHbaName == null || parentHbaName.trim().isEmpty()) {
                logger.error("부모 HBA 이름이 제공되지 않았습니다");
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA 이름이 필요합니다");
            }

            // 2. 부모 HBA의 유효성 검증 (virsh nodedev-list --cap vports에서 나온 값인지 확인)
            if (!validateParentHbaFromVports(parentHbaName)) {
                logger.error("부모 HBA가 vports 지원 목록에 없습니다: " + parentHbaName);
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "부모 HBA가 vports를 지원하지 않습니다: " + parentHbaName);
            }

            // 3. XML 내용 검증 (Vue에서 제공한 XML 사용)
            if (xmlContent == null || xmlContent.trim().isEmpty()) {
                logger.error("XML 내용이 제공되지 않았습니다");
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "XML 내용이 필요합니다");
            }

            if (!validateXmlContent(xmlContent)) {
                logger.error("제공된 XML이 유효하지 않습니다");
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "유효하지 않은 XML 형식입니다");
            }

            logger.info("사용된 XML 내용:\n" + xmlContent);

            // 4. XML 파일명 생성 (parenthbaname + 무작위 숫자)
            String xmlFileName = generateXmlFileName(parentHbaName);
            String xmlFilePath = "/tmp/" + xmlFileName + ".xml";

            try (FileWriter writer = new FileWriter(xmlFilePath)) {
                writer.write(xmlContent);
                logger.info("vHBA XML 파일 생성: " + xmlFilePath);
            } catch (IOException e) {
                logger.error("XML 파일 생성 실패: " + e.getMessage());
                return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "XML 파일 생성 실패: " + e.getMessage());
            }

            // 5. virsh nodedev-create 명령 실행
            Script createCommand = new Script("/bin/bash");
            createCommand.add("-c");
            createCommand.add("virsh nodedev-create " + xmlFilePath);
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

            // 8. 임시 XML 파일 정리
            cleanupXmlFile(xmlFilePath);

            logger.info("vHBA 디바이스 생성 성공: " + vhbaName + " -> " + createdDeviceName);
            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(true, vhbaName, createdDeviceName);

        } catch (Exception e) {
            logger.error("vHBA 디바이스 생성 중 오류: " + e.getMessage(), e);
            return new com.cloud.agent.api.CreateVhbaDeviceAnswer(false, vhbaName, "vHBA 생성 중 오류: " + e.getMessage());
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

    // XML 파일명 생성 (parenthbaname + 무작위 숫자)
    private String generateXmlFileName(String parentHbaName) {
        // parenthbaname에서 특수문자 제거하고 안전한 파일명 생성
        String safeParentName = parentHbaName.replaceAll("[^a-zA-Z0-9]", "_");
        long timestamp = System.currentTimeMillis();
        return "vhba_" + safeParentName + "_" + timestamp;
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

            // Set을 사용하여 중복 제거
            HashSet<String> vhbaNames = new HashSet<>();

            // 방법 1: 키워드가 있는 경우 특정 물리 HBA의 vHBA만 조회
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
                // 방법 2: 키워드가 없는 경우 모든 vHBA 조회 (중복 제거)
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

            // 방법 3: /sys/class/fc_remote_ports에서 vHBA 찾기 (백업 방법, 키워드가 없을 때만)
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
                String description = "Virtual HBA Device";
                String status = "Active";

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
                        } else if (infoLine.contains("<name>")) {
                            String name = infoLine.replaceAll("<[^>]*>", "").trim();
                            description = "Virtual HBA: " + name;
                        }
                    }

                    // description에 WWN 정보 추가 (줄 바꿈 사용)
                    StringBuilder descBuilder = new StringBuilder(description);
                    if (!wwnn.isEmpty()) {
                        descBuilder.append("\nWWNN: ").append(wwnn);
                    }
                    if (!wwpn.isEmpty()) {
                        descBuilder.append("\nWWPN: ").append(wwpn);
                    }
                    if (!fabricWwn.isEmpty() && !fabricWwn.equals("0")) {
                        descBuilder.append("\nFabric WWN: ").append(fabricWwn);
                    }
                    description = descBuilder.toString();
                }

                // 키워드 필터링 (키워드가 있는 경우에만)
                boolean shouldInclude = true;
                if (keyword != null && !keyword.isEmpty()) {
                    // 정확한 매칭만 허용 (scsi_host13과 같은 실제 값)
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

                        String hbaDescription = "vHBA Capable HBA: " + hbaName;
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

                            // description에 상세 정보 추가 (줄 바꿈 사용)
                            StringBuilder descBuilder = new StringBuilder("vHBA Capable HBA: " + hbaName);
                            if (!wwnn.isEmpty()) {
                                descBuilder.append("\nWWNN: ").append(wwnn);
                            }
                            if (!wwpn.isEmpty()) {
                                descBuilder.append("\nWWPN: ").append(wwpn);
                            }
                            if (!maxVports.isEmpty()) {
                                descBuilder.append("\n(Max vPorts: ").append(maxVports).append(")");
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
        String lunXmlPath = String.format("/tmp/lun_device_%s.xml", vmName);
        try {
            // XML 파일이 없을 경우에만 생성
            File xmlFile = new File(lunXmlPath);
            if (!xmlFile.exists()) {
                try (PrintWriter writer = new PrintWriter(lunXmlPath)) {
                    writer.write(xmlConfig);
                }
                logger.info("Generated XML file: {} for VM: {}", lunXmlPath, vmName);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, lunXmlPath);
            } else {
                virshCmd.add("detach-device", vmName, lunXmlPath);
                logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
            }

            logger.info("isAttach value: {}", isAttach);

            String result = virshCmd.execute();

            if (result != null) {
                String action = isAttach ? "attach" : "detach";
                logger.error("Failed to {} USB device: {}", action, result);
                return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach);
            }

            String action = isAttach ? "attached to" : "detached from";
            logger.info("Successfully {} USB device for VM {}", action, vmName);
            return new UpdateHostLunDeviceAnswer(true, vmName, xmlConfig, isAttach);

        } catch (Exception e) {
            String action = isAttach ? "attaching" : "detaching";
            logger.error("Error {} USB device: {}", action, e.getMessage(), e);
            return new UpdateHostLunDeviceAnswer(false, vmName, xmlConfig, isAttach);
        }
    }

    protected Answer updateHostScsiDevices(UpdateHostScsiDeviceCommand command, String vmName, String xmlConfig, boolean isAttach) {
        String scsiXmlPath = String.format("/tmp/scsi_device_%s.xml", vmName);
        try {
            // XML 파일이 없을 경우에만 생성
            File xmlFile = new File(scsiXmlPath);
            if (!xmlFile.exists()) {
                try (PrintWriter writer = new PrintWriter(scsiXmlPath)) {
                    writer.write(xmlConfig);
                }
                logger.info("Generated XML file: {} for VM: {}", scsiXmlPath, vmName);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, scsiXmlPath);
            } else {
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


    protected Answer updateHostHbaDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
        String hbaXmlPath = String.format("/tmp/hba_device_%s.xml", vmName);
        try {
            // XML 파일이 없을 경우에만 생성
            File xmlFile = new File(hbaXmlPath);
            if (!xmlFile.exists()) {
                try (PrintWriter writer = new PrintWriter(hbaXmlPath)) {
                    writer.write(xmlConfig);
                }
                logger.info("Generated XML file: {} for VM: {}", hbaXmlPath, vmName);
            }

            Script virshCmd = new Script("virsh");
            if (isAttach) {
                virshCmd.add("attach-device", vmName, hbaXmlPath);
            } else {
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
        try {
            UpdateHostVhbaDeviceCommand cmd = (UpdateHostVhbaDeviceCommand) command;
            String vhbaName = cmd.getVhbaName();

            String vhbaXmlPath = String.format("/tmp/vhba_device_%s.xml", vmName);
            try {
                // XML 파일이 없을 경우에만 생성
                File xmlFile = new File(vhbaXmlPath);
                if (!xmlFile.exists()) {
                    try (PrintWriter writer = new PrintWriter(vhbaXmlPath)) {
                        writer.write(xmlConfig);
                    }
                    logger.info("Generated XML file: {} for VM: {}", vhbaXmlPath, vmName);
                }

                Script virshCmd = new Script("virsh");
                if (isAttach) {
                    virshCmd.add("attach-device", vmName, vhbaXmlPath);
                } else {
                    virshCmd.add("detach-device", vmName, vhbaXmlPath);
                    logger.info("Executing detach command for VM: {} with XML: {}", vmName, xmlConfig);
                }

                logger.info("isAttach value: {}", isAttach);

                String result = virshCmd.execute();

                if (result != null) {
                    String action = isAttach ? "attach" : "detach";
                    logger.error("Failed to {} vHBA device: {}", action, result);
                    return new com.cloud.agent.api.UpdateHostVhbaDeviceAnswer(false, vhbaName, vmName, xmlConfig, isAttach);
                }

                String action = isAttach ? "attached to" : "detached from";
                logger.info("Successfully {} vHBA device for VM {}", action, vmName);
                return new com.cloud.agent.api.UpdateHostVhbaDeviceAnswer(true, vhbaName, vmName, xmlConfig, isAttach);

            } catch (Exception e) {
                String action = isAttach ? "attaching" : "detaching";
                logger.error("Error {} vHBA device: {}", action, e.getMessage(), e);
                return new com.cloud.agent.api.UpdateHostVhbaDeviceAnswer(false, vhbaName, vmName, xmlConfig, isAttach);
            }
        } catch (Exception e) {
            logger.error("Error in updateHostVhbaDevices: " + e.getMessage(), e);
            return new com.cloud.agent.api.UpdateHostVhbaDeviceAnswer(false, null, null, null, false);
        }
    }
}
