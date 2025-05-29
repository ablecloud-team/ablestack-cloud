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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.Duration;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import org.libvirt.StoragePool;

import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;

import com.cloud.agent.api.to.HostTO;
import com.cloud.hypervisor.kvm.resource.KVMHABase.HAStoragePool;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef.PoolType;
import com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolXMLParser;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

public class LibvirtStoragePool implements KVMStoragePool {
    protected Logger logger = LogManager.getLogger(getClass());
    protected String uuid;
    protected long capacity;
    protected long used;
    protected Long capacityIops;
    protected Long usedIops;
    protected long available;
    protected String name;
    protected String localPath;
    protected PhysicalDiskFormat defaultFormat;
    protected StoragePoolType type;
    protected StorageAdaptor _storageAdaptor;
    protected StoragePool _pool;
    protected String authUsername;
    protected String authSecret;
    protected String sourceHost;
    protected int sourcePort;

    protected String sourceDir;

    public LibvirtStoragePool(String uuid, String name, StoragePoolType type, StorageAdaptor adaptor, StoragePool pool) {
        this.uuid = uuid;
        this.name = name;
        this.type = type;
        this._storageAdaptor = adaptor;
        this.capacity = 0;
        this.used = 0;
        this.available = 0;
        this._pool = pool;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    @Override
    public long getCapacity() {
        return this.capacity;
    }

    public void setUsed(long used) {
        this.used = used;
    }

    @Override
    public long getUsed() {
        return this.used;
    }

    @Override
    public Long getCapacityIops() {
        return capacityIops;
    }

    public void setCapacityIops(Long capacityIops) {
        this.capacityIops = capacityIops;
    }

    @Override
    public Long getUsedIops() {
        return usedIops;
    }

    public void setUsedIops(Long usedIops) {
        this.usedIops = usedIops;
    }

    @Override
    public long getAvailable() {
        return this.available;
    }

    public void setAvailable(long available) {
        this.available = available;
    }

    public StoragePoolType getStoragePoolType() {
        return this.type;
    }

    public String getName() {
        return this.name;
    }

    @Override
    public String getUuid() {
        return this.uuid;
    }

    @Override
    public PhysicalDiskFormat getDefaultFormat() {
        if (getStoragePoolType() == StoragePoolType.CLVM || getStoragePoolType() == StoragePoolType.RBD || getStoragePoolType() == StoragePoolType.PowerFlex) {
            return PhysicalDiskFormat.RAW;
        } else {
            return PhysicalDiskFormat.QCOW2;
        }
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name,
            PhysicalDiskFormat format, Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        return this._storageAdaptor
                .createPhysicalDisk(name, this, format, provisioningType, size, passphrase);
    }

    @Override
    public KVMPhysicalDisk createPhysicalDisk(String name, Storage.ProvisioningType provisioningType, long size, byte[] passphrase) {
        return this._storageAdaptor.createPhysicalDisk(name, this,
                this.getDefaultFormat(), provisioningType, size, passphrase);
    }

    @Override
    public KVMPhysicalDisk getPhysicalDisk(String volumeUid) {
        KVMPhysicalDisk disk = null;
        String volumeUuid = volumeUid;
        if ( volumeUid.contains("/") ) {
            String[] tokens = volumeUid.split("/");
            volumeUuid = tokens[tokens.length -1];
        }
        try {
            disk = this._storageAdaptor.getPhysicalDisk(volumeUuid, this);
        } catch (CloudRuntimeException e) {
            if ((this.getStoragePoolType() != StoragePoolType.NetworkFilesystem) && (this.getStoragePoolType() != StoragePoolType.Filesystem)) {
                throw e;
            }
        }

        if (disk != null) {
            return disk;
        }
        logger.debug("find volume bypass libvirt volumeUid " + volumeUid);
        //For network file system or file system, try to use java file to find the volume, instead of through libvirt. BUG:CLOUDSTACK-4459
        String localPoolPath = this.getLocalPath();
        File f = new File(localPoolPath + File.separator + volumeUuid);
        if (!f.exists()) {
            logger.debug("volume: " + volumeUuid + " not exist on storage pool");
            throw new CloudRuntimeException("Can't find volume:" + volumeUuid);
        }
        disk = new KVMPhysicalDisk(f.getPath(), volumeUuid, this);
        disk.setFormat(PhysicalDiskFormat.QCOW2);
        disk.setSize(f.length());
        disk.setVirtualSize(f.length());
        logger.debug("find volume bypass libvirt disk " + disk.toString());
        return disk;
    }

    @Override
    public boolean connectPhysicalDisk(String name, Map<String, String> details) {
        return true;
    }

    @Override
    public boolean disconnectPhysicalDisk(String uuid) {
        return true;
    }

    @Override
    public boolean deletePhysicalDisk(String uuid, Storage.ImageFormat format) {
        return this._storageAdaptor.deletePhysicalDisk(uuid, this, format);
    }

    @Override
    public List<KVMPhysicalDisk> listPhysicalDisks() {
        return this._storageAdaptor.listPhysicalDisks(this.uuid, this);
    }

    @Override
    public boolean refresh() {
        return this._storageAdaptor.refresh(this);
    }

    @Override
    public boolean isExternalSnapshot() {
        if (this.type == StoragePoolType.CLVM || type == StoragePoolType.RBD || type == StoragePoolType.SharedMountPoint) {
            return true;
        }
        return false;
    }

    @Override
    public String getLocalPath() {
        return this.localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    @Override
    public String getAuthUserName() {
        return this.authUsername;
    }

    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }

    @Override
    public String getAuthSecret() {
        return this.authSecret;
    }

    public void setAuthSecret(String authSecret) {
        this.authSecret = authSecret;
    }

    @Override
    public String getSourceHost() {
        return this.sourceHost;
    }

    public void setSourceHost(String host) {
        this.sourceHost = host;
    }

    @Override
    public int getSourcePort() {
        return this.sourcePort;
    }

    public void setSourcePort(int port) {
        this.sourcePort = port;
    }

    @Override
    public String getSourceDir() {
        return this.sourceDir;
    }

    public void setSourceDir(String dir) {
        this.sourceDir = dir;
    }

    @Override
    public StoragePoolType getType() {
        return this.type;
    }

    public StoragePool getPool() {
        return this._pool;
    }

    public void setPool(StoragePool pool) {
        this._pool = pool;
    }


    @Override
    public boolean delete() {
        try {
            return this._storageAdaptor.deleteStoragePool(this);
        } catch (Exception e) {
            logger.debug("Failed to delete storage pool", e);
        }
        return false;
    }

    @Override
    public boolean createFolder(String path) {
        return this._storageAdaptor.createFolder(this.uuid, path, this.type == StoragePoolType.Filesystem ? this.localPath : null);
    }

    @Override
    public boolean supportsConfigDriveIso() {
        if (this.type == StoragePoolType.NetworkFilesystem) {
            return true;
        }
        return false;
    }

    @Override
    public Map<String, String> getDetails() {
        return null;
    }

    @Override
    public boolean isPoolSupportHA() {
        return type == StoragePoolType.NetworkFilesystem || type == StoragePoolType.SharedMountPoint || type == StoragePoolType.RBD || type == StoragePoolType.CLVM;
    }

    @Override
    public String getHearthBeatPath() {
        if (type == StoragePoolType.NetworkFilesystem) {
            return Script.findScript(kvmScriptsDir, "kvmheartbeat.sh");
        }
        if (type == StoragePoolType.SharedMountPoint) {
            return Script.findScript(kvmScriptsDir, "kvmheartbeat_gfs.sh");
        }
        if (type == StoragePoolType.RBD) {
            return Script.findScript(kvmScriptsDir, "kvmheartbeat_rbd.sh");
        }
        if (type == StoragePoolType.CLVM) {
            return Script.findScript(kvmScriptsDir, "kvmheartbeat_clvm.sh");
        }
        return null;
    }

    public String createHeartBeatCommand(HAStoragePool primaryStoragePool, String hostPrivateIp, boolean hostValidation) {
        logger.info("### [HA Checking] createHeartBeatCommand Method Start!!!");
        Script cmd = new Script(getHearthBeatPath(), HeartBeatUpdateTimeout, logger);
        if (primaryStoragePool.getPool().getType() == StoragePoolType.NetworkFilesystem) {
            cmd = new Script(getHearthBeatPath(), HeartBeatUpdateTimeout, logger);
            cmd.add("-i", primaryStoragePool.getPoolIp());
            cmd.add("-p", primaryStoragePool.getPoolMountSourcePath());
            cmd.add("-m", primaryStoragePool.getMountDestPath());
            if (hostValidation) {
                cmd.add("-h", hostPrivateIp);
            } else {
                cmd.add("-c");
            }
        } else if (primaryStoragePool.getPool().getType() == StoragePoolType.SharedMountPoint) {
                cmd = new Script(getHearthBeatPath(), HeartBeatUpdateTimeout, logger);
                cmd.add("-m", primaryStoragePool.getMountDestPath());
                if (hostValidation) {
                    cmd.add("-h", hostPrivateIp);
                } else {
                    cmd.add("-c");
                }
        } else if (primaryStoragePool.getPool().getType() == StoragePoolType.RBD) {
            createRBDSecretKeyFileIfNoExist(primaryStoragePool.getPoolUUID(), "/var/lib/libvirt/images/", primaryStoragePool.getPoolAuthSecret());
            cmd.add("-i", primaryStoragePool.getPoolSourceHost());
            cmd.add("-p", primaryStoragePool.getPoolMountSourcePath());
            cmd.add("-n", primaryStoragePool.getPoolAuthUserName());
            cmd.add("-s", primaryStoragePool.getPoolUUID());
            cmd.add("-h", hostPrivateIp);
            if (!hostValidation) {
                cmd.add("-c");
            }
        } else if (primaryStoragePool.getPool().getType() == StoragePoolType.CLVM) {
            Connect conn = null;
            try {
                conn = LibvirtConnection.getConnection();
            } catch (LibvirtException e) {
                throw new CloudRuntimeException(e.toString());
            }

            String rbdPoolName = "";
            String authUserName = "";
            String smpTargetPath = "";
            try {
                String glueBlockPool = Script.runSimpleBashScript(String.format("virsh pool-list --type rbd | grep active | head -1 | awk '{print $1}'"));
                if (glueBlockPool != null) {
                    logger.info("### [HA Checking] createHeartBeatCommand Method Start!!! - CLVM HA Use GlueBlockPool > ");
                    StoragePool sp = conn.storagePoolLookupByName(glueBlockPool);
                    String poolDefXML = sp.getXMLDesc(0);
                    LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                    LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                    if (pdef == null) {
                        throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + glueBlockPool);
                    }
                    if (pdef.getPoolType() == PoolType.RBD) {
                        logger.debug(String.format("RBD Pool name [%s] auth name [%s]", pdef.getSourceDir(), pdef.getAuthUserName()));
                        rbdPoolName = pdef.getSourceDir();
                        authUserName = pdef.getAuthUserName();
                    }
                } else  {
                    logger.info("### [HA Checking] createHeartBeatCommand Method Start!!! - CLVM HA Use SharedMountPointPoolCmd > ");
                    Script listCommand = new Script("/bin/bash", logger);
                    listCommand.add("-c");
                    listCommand.add("virsh pool-list --type dir | grep active | awk '{print $1}' | sort");

                    OutputInterpreter.AllLinesParser pars = new OutputInterpreter.AllLinesParser();
                    String result = listCommand.execute(pars);
                    if (result == null && pars.getLines() != null) {
                        String[] lines = pars.getLines().split(System.lineSeparator());
                        for (String smpPool : lines) {
                            StoragePool sp = conn.storagePoolLookupByName(smpPool);
                            String poolDefXML = sp.getXMLDesc(0);
                            LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                            LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                            if (pdef == null) {
                                throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + smpPool);
                            }
                            if (pdef.getPoolType() == PoolType.DIR && !"/var/lib/libvirt/images".equals(pdef.getTargetPath())) {
                                logger.debug(String.format("SharedMountPoint Pool source path [%s]", pdef.getTargetPath()));
                                smpTargetPath = pdef.getTargetPath();
                                break;
                            }
                        }
                    }
                }
            } catch (LibvirtException e) {
                logger.error("Failure in attempting to see if an existing storage pool might be using the path of the pool to be created:" + e);
                return "0";
            }

            if (rbdPoolName.length() > 0 && authUserName.length() > 0) {
                cmd.add("-p", rbdPoolName);
                cmd.add("-n", authUserName);
            } else if (smpTargetPath.length() > 0) {
                cmd.add("-g", smpTargetPath);
            } else {
                return "0";
            }
            cmd.add("-q", primaryStoragePool.getPoolMountSourcePath());
            if (hostValidation) {
                cmd.add("-h", hostPrivateIp);
            } else {
                cmd.add("-c");
            }
        }
        return cmd.execute();
    }

    @Override
    public String toString() {
        return String.format("LibvirtStoragePool %s", ReflectionToStringBuilderUtils.reflectOnlySelectedFields(this, "uuid", "path"));
    }

    @Override
    public String getStorageNodeId() {
        return null;
    }

    @Override
    public Boolean checkingHeartBeat(HAStoragePool pool, HostTO host) {
        logger.info("### [HA Checking] checkingHeartBeat Method Start!!!");
        boolean validResult = false;
        Script cmd = new Script(getHearthBeatPath(), HeartBeatCheckerTimeout, logger);
        if (pool.getPool().getType() == StoragePoolType.NetworkFilesystem) {
            cmd.add("-i", pool.getPoolIp());
            cmd.add("-p", pool.getPoolMountSourcePath());
            cmd.add("-m", pool.getMountDestPath());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-r");
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
        } else if (pool.getPool().getType() == StoragePoolType.SharedMountPoint) {
            cmd.add("-m", pool.getMountDestPath());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-r");
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
        } else if (pool.getPool().getType() == StoragePoolType.CLVM) {
            Connect conn = null;
            try {
                conn = LibvirtConnection.getConnection();
            } catch (LibvirtException e) {
                throw new CloudRuntimeException(e.toString());
            }

            String rbdPoolName = "";
            String authUserName = "";
            String smpTargetPath = "";
            try {
                String glueBlockPool = Script.runSimpleBashScript(String.format("virsh pool-list --type rbd | grep active | head -1 | awk '{print $1}'"));
                if (glueBlockPool != null) {
                    logger.info("### [HA Checking] checkingHeartBeat Method Start!!! - CLVM HA Use GlueBlockPool > ");
                    StoragePool sp = conn.storagePoolLookupByName(glueBlockPool);
                    String poolDefXML = sp.getXMLDesc(0);
                    LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                    LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                    if (pdef == null) {
                        throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + glueBlockPool);
                    }
                    if (pdef.getPoolType() == PoolType.RBD) {
                        logger.debug(String.format("RBD Pool name [%s] auth name [%s]", pdef.getSourceDir(), pdef.getAuthUserName()));
                        rbdPoolName = pdef.getSourceDir();
                        authUserName = pdef.getAuthUserName();
                    }
                } else  {
                    logger.info("### [HA Checking] checkingHeartBeat Method Start!!! - CLVM HA Use SharedMountPointPoolCmd > ");
                    Script listCommand = new Script("/bin/bash", logger);
                    listCommand.add("-c");
                    listCommand.add("virsh pool-list --type dir | grep active | awk '{print $1}' | sort");

                    OutputInterpreter.AllLinesParser pars = new OutputInterpreter.AllLinesParser();
                    String result = listCommand.execute(pars);
                    if (result == null && pars.getLines() != null) {
                        String[] lines = pars.getLines().split(System.lineSeparator());
                        for (String smpPool : lines) {
                            StoragePool sp = conn.storagePoolLookupByName(smpPool);
                            String poolDefXML = sp.getXMLDesc(0);
                            LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                            LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                            if (pdef == null) {
                                throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + smpPool);
                            }
                            if (pdef.getPoolType() == PoolType.DIR && !"/var/lib/libvirt/images".equals(pdef.getTargetPath())) {
                                logger.debug(String.format("SharedMountPoint Pool source path [%s]", pdef.getTargetPath()));
                                smpTargetPath = pdef.getTargetPath();
                                break;
                            }
                        }
                    }
                }
            } catch (LibvirtException e) {
                logger.error("Failure in attempting to see if an existing storage pool might be using the path of the pool to be created:" + e);
                return true;
            }

            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-q", pool.getPoolMountSourcePath());
            cmd.add("-r");
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
            if (rbdPoolName.length() > 0 && authUserName.length() > 0) {
                cmd.add("-p", rbdPoolName);
                cmd.add("-n", authUserName);
            } else if (smpTargetPath.length() > 0) {
                cmd.add("-g", smpTargetPath);
            } else {
                return true;
            }
        }

        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        String result = cmd.execute(parser);
        String parsedLine = parser.getLine();

        logger.debug(String.format("Checking heart beat with KVMHAChecker [{command=\"%s\", result: \"%s\", log: \"%s\", pool: \"%s\"}].", cmd.toString(), result, parsedLine,
                pool.getPoolIp()));

        if (result == null && parsedLine.contains("DEAD")) {
            logger.warn(String.format("Checking heart beat with KVMHAChecker command [%s] returned [%s]. [%s]. It may cause a shutdown of host IP [%s].", cmd.toString(),
                    result, parsedLine, host.getPrivateNetwork().getIp()));
        } else {
            validResult = true;
        }
        return validResult;
    }

    @Override
    public Boolean checkingHeartBeatRBD(HAStoragePool pool, HostTO host, String volumeList) {
        logger.info("### [HA Checking] checkingHeartBeatRBD Method Start!!!");
        boolean validResult = false;
        Script cmd = new Script(getHearthBeatPath(), HeartBeatCheckerTimeout, logger);
        if (pool.getPool().getType() == StoragePoolType.RBD) {
            cmd.add("-i", pool.getPoolSourceHost());
            cmd.add("-p", pool.getPoolMountSourcePath());
            cmd.add("-n", pool.getPoolAuthUserName());
            cmd.add("-s", pool.getPoolUUID());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-u", volumeList.length() > 0 ? volumeList : "");
            cmd.add("-r", "r");
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
        }

        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        String result = cmd.execute(parser);
        String parsedLine = parser.getLine();

        logger.debug(String.format("Checking heart beat with KVMHAChecker [{command=\"%s\", result: \"%s\", log: \"%s\", pool: \"%s\"}].", cmd.toString(), result, parsedLine,
                pool.getPoolIp()));

        if (result == null && parsedLine.contains("DEAD")) {
            logger.warn(String.format("Checking heart beat with KVMHAChecker command [%s] returned [%s]. [%s]. It may cause a shutdown of host IP [%s].", cmd.toString(),
                    result, parsedLine, host.getPrivateNetwork().getIp()));
        } else {
            validResult = true;
        }
        return validResult;
    }

    @Override
    public Boolean vmActivityCheck(HAStoragePool pool, HostTO host, Duration activityScriptTimeout, String volumeUUIDListString, String vmActivityCheckPath, long duration) {
        logger.info("### [HA Checking] vmActivityCheck Method Start!!!");
        Script cmd = new Script(vmActivityCheckPath, activityScriptTimeout.getStandardSeconds(), logger);
        if (pool.getPool().getType() == StoragePoolType.NetworkFilesystem) {
            cmd.add("-i", pool.getPoolIp());
            cmd.add("-p", pool.getPoolMountSourcePath());
            cmd.add("-m", pool.getMountDestPath());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-u", volumeUUIDListString);
            cmd.add("-t", String.valueOf(String.valueOf(System.currentTimeMillis() / 1000)));
            cmd.add("-d", String.valueOf(duration));
        } else if (pool.getPool().getType() == StoragePoolType.SharedMountPoint) {
            cmd.add("-m", pool.getMountDestPath());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-u", volumeUUIDListString);
            cmd.add("-i", String.valueOf(HeartBeatCheckerFreq / 1000));
            cmd.add("-t", String.valueOf(String.valueOf(System.currentTimeMillis() / 1000)));
            cmd.add("-d", String.valueOf(duration));
        } else if (pool.getPool().getType() == StoragePoolType.RBD) {
            cmd.add("-i", pool.getPoolSourceHost());
            cmd.add("-p", pool.getPoolMountSourcePath());
            cmd.add("-n", pool.getPoolAuthUserName());
            cmd.add("-s", pool.getPoolUUID());
            cmd.add("-h", host.getPrivateNetwork().getIp());
            cmd.add("-u", volumeUUIDListString);
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
        } else if (pool.getPool().getType() == StoragePoolType.CLVM) {
            Connect conn = null;
            try {
                conn = LibvirtConnection.getConnection();
            } catch (LibvirtException e) {
                throw new CloudRuntimeException(e.toString());
            }
            String rbdPoolName = "";
            String authUserName = "";
            String smpTargetPath = "";
            try {
                String glueBlockPool = Script.runSimpleBashScript(String.format("virsh pool-list --type rbd | grep active | head -1 | awk '{print $1}'"));
                if (glueBlockPool != null) {
                    logger.info("### [HA Checking] vmActivityCheck Method Start!!! - CLVM HA Use GlueBlockPool > ");
                    StoragePool sp = conn.storagePoolLookupByName(glueBlockPool);
                    String poolDefXML = sp.getXMLDesc(0);
                    LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                    LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                    if (pdef == null) {
                        throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + glueBlockPool);
                    }
                    if (pdef.getPoolType() == PoolType.RBD) {
                        logger.debug(String.format("RBD Pool name [%s] auth name [%s]", pdef.getSourceDir(), pdef.getAuthUserName()));
                        rbdPoolName = pdef.getSourceDir();
                        authUserName = pdef.getAuthUserName();
                    }
                } else  {
                    logger.info("### [HA Checking] vmActivityCheck Method Start!!! - CLVM HA Use SharedMountPointPoolCmd > ");
                    Script listCommand = new Script("/bin/bash", logger);
                    listCommand.add("-c");
                    listCommand.add("virsh pool-list --type dir | grep active | awk '{print $1}' | sort");

                    OutputInterpreter.AllLinesParser pars = new OutputInterpreter.AllLinesParser();
                    String result = listCommand.execute(pars);
                    if (result == null && pars.getLines() != null) {
                        String[] lines = pars.getLines().split(System.lineSeparator());
                        for (String smpPool : lines) {
                            StoragePool sp = conn.storagePoolLookupByName(smpPool);
                            String poolDefXML = sp.getXMLDesc(0);
                            LibvirtStoragePoolXMLParser parser = new LibvirtStoragePoolXMLParser();
                            LibvirtStoragePoolDef pdef =  parser.parseStoragePoolXML(poolDefXML);
                            if (pdef == null) {
                                throw new CloudRuntimeException("Unable to parse the storage pool definition for storage pool " + smpPool);
                            }
                            if (pdef.getPoolType() == PoolType.DIR && !"/var/lib/libvirt/images".equals(pdef.getTargetPath())) {
                                logger.debug(String.format("SharedMountPoint Pool source path [%s]", pdef.getTargetPath()));
                                smpTargetPath = pdef.getTargetPath();
                                break;
                            }
                        }
                    }
                }
            } catch (LibvirtException e) {
                logger.error("Failure in attempting to see if an existing storage pool might be using the path of the pool to be created:" + e);
                return true;
            }

            cmd.add("-h", host.getPublicNetwork().getIp());
            cmd.add("-q", pool.getPoolMountSourcePath());
            cmd.add("-u", volumeUUIDListString);
            cmd.add("-t", String.valueOf(HeartBeatCheckerFreq / 1000));
            cmd.add("-d", String.valueOf(duration));
            if (rbdPoolName.length() > 0 && authUserName.length() > 0) {
                cmd.add("-p", rbdPoolName);
                cmd.add("-n", authUserName);
            } else if (smpTargetPath.length() > 0) {
                cmd.add("-g", smpTargetPath);
            } else {
                return true;
            }
        }

        OutputInterpreter.OneLineParser parser = new OutputInterpreter.OneLineParser();
        String result = cmd.execute(parser);
        String parsedLine = parser.getLine();

        logger.debug(String.format("Checking heart beat with KVMHAVMActivityChecker [{command=\"%s\", result: \"%s\", log: \"%s\", pool: \"%s\"}].", cmd.toString(), result, parsedLine, pool.getPoolIp()));

        if (result == null && parsedLine.contains("DEAD")) {
            logger.warn(String.format("Checking heart beat with KVMHAVMActivityChecker command [%s] returned [%s]. It is [%s]. It may cause a shutdown of host IP [%s].", cmd.toString(), result, parsedLine, host.getPrivateNetwork().getIp()));
            return false;
        } else {
            return true;
        }
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

}
