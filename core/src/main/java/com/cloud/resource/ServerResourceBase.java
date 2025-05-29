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
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.storage.command.browser.ListRbdObjectsAnswer;

import org.apache.cloudstack.storage.command.browser.ListDataStoreObjectsAnswer;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
// import org.json.JSONArray;
// import org.json.JSONObject;
import org.apache.logging.log4j.LogManager;

import com.cloud.agent.IAgentControl;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartupCommand;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.cloud.agent.api.ListHostDeviceAnswer;
// import com.cloud.agent.api.ListHostLunDeviceAnswer;
// import com.cloud.agent.api.ListHostUsbDeviceAnswer;
// import com.cloud.agent.api.ListHostLunDeviceCommand;

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

    // protected Answer updateHostUsbDevices(Command command, String vmName, String xmlConfig, boolean isAttach) {
    //     try {
    //         // 임시 XML 파일 생성
    //         String tempXmlPath = "/tmp/usb_device_" + System.currentTimeMillis() + ".xml";
    //         try (PrintWriter writer = new PrintWriter(tempXmlPath)) {
    //             writer.write(xmlConfig);
    //         }
    //         logger.info("Generated temporary XML file: {}", tempXmlPath);
    //         // virsh 명령어 실행
    //         Script virshCmd = new Script("virsh");
    //         if (isAttach) {
    //             virshCmd.add("attach-device", vmName, tempXmlPath);
    //         } else {
    //             virshCmd.add("detach-device", vmName, tempXmlPath);
    //         }

    //         String result = virshCmd.execute();

    //         // 임시 파일 삭제
    //         File tempFile = new File(tempXmlPath);
    //         if (tempFile.exists() && !tempFile.delete()) {
    //             logger.warn("Failed to delete temporary file: {}", tempXmlPath);
    //         }

    //         if (result != null) {
    //             String action = isAttach ? "attach" : "detach";
    //             logger.error("Failed to {} USB device: {}", action, result);
    //             return new Answer(command, false, "Failed to " + action + " USB device: " + result);
    //         }

    //         String action = isAttach ? "attached to" : "detached from";
    //         logger.info("Successfully {} USB device {} VM {}", action, tempXmlPath, vmName);
    //         return new Answer(command, true, null);

    //     } catch (Exception e) {
    //         String action = isAttach ? "attaching" : "detaching";
    //         logger.error("Error {} USB device: {}", action, e.getMessage(), e);
    //         return new Answer(command, false, "Error " + action + " USB device: " + e.getMessage());
    //     }
    // }
}