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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.backup.CommvaultRestoreBackupCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@ResourceWrapper(handles = CommvaultRestoreBackupCommand.class)
public class LibvirtCommvaultRestoreBackupCommandWrapper extends CommandWrapper<CommvaultRestoreBackupCommand, Answer, LibvirtComputingResource> {
    private static final String FILE_PATH_PLACEHOLDER = "%s/%s";
    private static final String ATTACH_QCOW2_DISK_COMMAND = " virsh attach-disk %s %s %s --driver qemu --subdriver qcow2 --cache none";
    private static final String ATTACH_RBD_DISK_XML_COMMAND = " virsh attach-device %s /dev/stdin <<EOF%sEOF";
    private static final String CURRRENT_DEVICE = "virsh domblklist --domain %s | tail -n 3 | head -n 1 | awk '{print $1}'";
    private static final String RSYNC_COMMAND = "rsync -az %s %s";
    private static final String MKDIR_P = "mkdir -p %s";
    private static final String RSYNC_DIR_FROM_REMOTE = "rsync -az -e \"ssh -o StrictHostKeyChecking=no\" %s:%s/ %s/";

    @Override
    public Answer execute(CommvaultRestoreBackupCommand command, LibvirtComputingResource serverResource) {
        String vmName = command.getVmName();
        String backupPath = command.getBackupPath();
        Boolean vmExists = command.isVmExists();
        String diskType = command.getDiskType();
        List<String> backedVolumeUUIDs = command.getBackupVolumesUUIDs();
        List<PrimaryDataStoreTO> restoreVolumePools = command.getRestoreVolumePools();
        List<String> restoreVolumePaths = command.getRestoreVolumePaths();
        String restoreVolumeUuid = command.getRestoreVolumeUUID();
        int timeout = command.getWait();
        String cacheMode = command.getCacheMode();
        String hostName = command.getHostName();
        KVMStoragePoolManager storagePoolMgr = serverResource.getStoragePoolMgr();

        String newVolumeId = null;
        try {
            if (hostName != null) {
                fetchBackupFile(hostName, backupPath);
            }
            if (Objects.isNull(vmExists)) {
                PrimaryDataStoreTO volumePool = restoreVolumePools.get(0);
                String volumePath = restoreVolumePaths.get(0);
                int lastIndex = volumePath.lastIndexOf("/");
                newVolumeId = volumePath.substring(lastIndex + 1);
                restoreVolume(storagePoolMgr, backupPath, volumePool, volumePath, diskType, restoreVolumeUuid,
                        new Pair<>(vmName, command.getVmState()), timeout, cacheMode);
            } else if (Boolean.TRUE.equals(vmExists)) {
                restoreVolumesOfExistingVM(storagePoolMgr, restoreVolumePools, restoreVolumePaths, backedVolumeUUIDs, backupPath, timeout);
            } else {
                restoreVolumesOfDestroyedVMs(storagePoolMgr, restoreVolumePools, restoreVolumePaths, vmName, backupPath, timeout);
            }
        } catch (CloudRuntimeException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "";
            return new BackupAnswer(command, false, errorMessage);
        }

        return new BackupAnswer(command, true, newVolumeId);
    }

    private void verifyBackupFile(String backupPath, String volUuid) {
        if (!checkBackupPathExists(backupPath)) {
            throw new CloudRuntimeException(String.format("Backup file for the volume [%s] does not exist.", volUuid));
        }
        if (!checkBackupFileImage(backupPath)) {
            throw new CloudRuntimeException(String.format("Backup qcow2 file for the volume [%s] is corrupt.", volUuid));
        }
    }

    private void restoreVolumesOfExistingVM(KVMStoragePoolManager storagePoolMgr, List<PrimaryDataStoreTO> restoreVolumePools, List<String> restoreVolumePaths, List<String> backedVolumesUUIDs,
                                            String backupPath, int timeout) {
        String diskType = "root";
        try {
            for (int idx = 0; idx < restoreVolumePaths.size(); idx++) {
                PrimaryDataStoreTO restoreVolumePool = restoreVolumePools.get(idx);
                String restoreVolumePath = restoreVolumePaths.get(idx);
                String backupVolumeUuid = backedVolumesUUIDs.get(idx);
                Pair<String, String> bkpPathAndVolUuid = getBackupPath(null, backupPath, diskType, backupVolumeUuid);
                diskType = "datadisk";
                verifyBackupFile(bkpPathAndVolUuid.first(), bkpPathAndVolUuid.second());
                if (!replaceVolumeWithBackup(storagePoolMgr, restoreVolumePool, restoreVolumePath, bkpPathAndVolUuid.first(), timeout)) {
                    throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", bkpPathAndVolUuid.second()));
                }
            }
        } finally {
            deleteBackupDirectory(backupPath);
        }
    }

    private void restoreVolumesOfDestroyedVMs(KVMStoragePoolManager storagePoolMgr, List<PrimaryDataStoreTO> volumePools, List<String> volumePaths, String vmName, String backupPath, int timeout) {
        String diskType = "root";
        try {
            for (int i = 0; i < volumePaths.size(); i++) {
                PrimaryDataStoreTO volumePool = volumePools.get(i);
                String volumePath = volumePaths.get(i);
                Pair<String, String> bkpPathAndVolUuid = getBackupPath(volumePath, backupPath, diskType, null);
                diskType = "datadisk";
                verifyBackupFile(bkpPathAndVolUuid.first(), bkpPathAndVolUuid.second());
                if (!replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, bkpPathAndVolUuid.first(), timeout)) {
                    throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", bkpPathAndVolUuid.second()));
                }
            }
        } finally {
            deleteBackupDirectory(backupPath);
        }
    }

    private void restoreVolume(KVMStoragePoolManager storagePoolMgr, String backupPath, PrimaryDataStoreTO volumePool, String volumePath, String diskType, String volumeUUID,
                               Pair<String, VirtualMachine.State> vmNameAndState, int timeout, String cacheMode) {
        Pair<String, String> bkpPathAndVolUuid;
        try {
            bkpPathAndVolUuid = getBackupPath(volumePath, backupPath, diskType, volumeUUID);
            verifyBackupFile(bkpPathAndVolUuid.first(), bkpPathAndVolUuid.second());
            if (!replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, bkpPathAndVolUuid.first(), timeout, true)) {
                throw new CloudRuntimeException(String.format("Unable to restore contents from the backup volume [%s].", bkpPathAndVolUuid.second()));
            }
            if (VirtualMachine.State.Running.equals(vmNameAndState.second())) {
                if (!attachVolumeToVm(storagePoolMgr, vmNameAndState.first(), volumePool, volumePath, cacheMode)) {
                    throw new CloudRuntimeException(String.format("Failed to attach volume to VM: %s", vmNameAndState.first()));
                }
            }
        } finally {
            deleteBackupDirectory(backupPath);
        }
    }

    private void deleteBackupDirectory(String backupDirectory) {
        try {
            FileUtils.deleteDirectory(new File(backupDirectory));
        } catch (IOException e) {
            logger.error(String.format("Failed to delete backup directory: %s", backupDirectory), e);
            throw new CloudRuntimeException("Failed to delete the backup directory");
        }
    }

    private Pair<String, String> getBackupPath(String volumePath, String backupPath, String diskType, String volumeUuid) {
        String volUuid = Objects.isNull(volumeUuid) ? volumePath.substring(volumePath.lastIndexOf(File.separator) + 1) : volumeUuid;
        String backupFileName = String.format("%s.%s.qcow2", diskType.toLowerCase(Locale.ROOT), volUuid);
        backupPath = String.format(FILE_PATH_PLACEHOLDER, backupPath, backupFileName);
        return new Pair<>(backupPath, volUuid);
    }

    private boolean checkBackupFileImage(String backupPath) {
        int exitValue = Script.runSimpleBashScriptForExitValue(String.format("qemu-img check %s", backupPath));
        return exitValue == 0;
    }

    private boolean checkBackupPathExists(String backupPath) {
        int exitValue = Script.runSimpleBashScriptForExitValue(String.format("ls %s", backupPath));
        return exitValue == 0;
    }

    private boolean replaceVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, String backupPath, int timeout) {
        return replaceVolumeWithBackup(storagePoolMgr, volumePool, volumePath, backupPath, timeout, false);
    }

    private boolean replaceVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, String backupPath, int timeout, boolean createTargetVolume) {
        if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
            int exitValue = Script.runSimpleBashScriptForExitValue(String.format(RSYNC_COMMAND, backupPath, volumePath));
            return exitValue == 0;
        }

        return replaceRbdVolumeWithBackup(storagePoolMgr, volumePool, volumePath, backupPath, timeout, createTargetVolume);
    }

    private boolean replaceRbdVolumeWithBackup(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, String backupPath, int timeout, boolean createTargetVolume) {
        KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
        QemuImg qemu;
        try {
            qemu = new QemuImg(timeout * 1000, true, false);
            if (!createTargetVolume) {
                KVMPhysicalDisk rdbDisk = volumeStoragePool.getPhysicalDisk(volumePath);
                logger.debug("Restoring RBD volume: {}", rdbDisk.toString());
                qemu.setSkipTargetVolumeCreation(true);
            }
        } catch (LibvirtException ex) {
            throw new CloudRuntimeException("Failed to create qemu-img command to restore RBD volume with backup", ex);
        }

        QemuImgFile srcBackupFile = null;
        QemuImgFile destVolumeFile = null;
        try {
            srcBackupFile = new QemuImgFile(backupPath, QemuImg.PhysicalDiskFormat.QCOW2);
            String rbdDestVolumeFile = KVMPhysicalDisk.RBDStringBuilder(volumeStoragePool.getSourceHost(), volumeStoragePool.getSourcePort(), volumeStoragePool.getAuthUserName(), volumeStoragePool.getAuthSecret(), volumePath);
            destVolumeFile = new QemuImgFile(rbdDestVolumeFile, QemuImg.PhysicalDiskFormat.RAW);

            logger.debug("Starting convert backup  {} to RBD volume  {}", backupPath, volumePath);
            qemu.convert(srcBackupFile, destVolumeFile);
            logger.debug("Successfully converted backup {} to RBD volume  {}", backupPath, volumePath);
        } catch (QemuImgException | LibvirtException e) {
            String srcFilename = srcBackupFile != null ? srcBackupFile.getFileName() : null;
            String destFilename = destVolumeFile != null ? destVolumeFile.getFileName() : null;
            logger.error("Failed to convert backup {} to volume {}, the error was: {}", srcFilename, destFilename, e.getMessage());
            return false;
        }

        return true;
    }

    private boolean attachVolumeToVm(KVMStoragePoolManager storagePoolMgr, String vmName, PrimaryDataStoreTO volumePool, String volumePath, String cacheMode) {
        String deviceToAttachDiskTo = getDeviceToAttachDisk(vmName);
        int exitValue;
        if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_QCOW2_DISK_COMMAND, vmName, volumePath, deviceToAttachDiskTo));
        } else {
            String xmlForRbdDisk = getXmlForRbdDisk(storagePoolMgr, volumePool, volumePath, deviceToAttachDiskTo, cacheMode);
            logger.debug("RBD disk xml to attach: {}", xmlForRbdDisk);
            exitValue = Script.runSimpleBashScriptForExitValue(String.format(ATTACH_RBD_DISK_XML_COMMAND, vmName, xmlForRbdDisk));
        }
        return exitValue == 0;
    }

    private String getDeviceToAttachDisk(String vmName) {
        String currentDevice = Script.runSimpleBashScript(String.format(CURRRENT_DEVICE, vmName));
        char lastChar = currentDevice.charAt(currentDevice.length() - 1);
        char incrementedChar = (char) (lastChar + 1);
        return currentDevice.substring(0, currentDevice.length() - 1) + incrementedChar;
    }

    private String getXmlForRbdDisk(KVMStoragePoolManager storagePoolMgr, PrimaryDataStoreTO volumePool, String volumePath, String deviceToAttachDiskTo, String cacheMode) {
        StringBuilder diskBuilder = new StringBuilder();
        diskBuilder.append("\n<disk ");
        diskBuilder.append(" device='disk'");
        diskBuilder.append(" type='network'");
        diskBuilder.append(">\n");

        diskBuilder.append("<driver name='qemu' type='raw'");
        if (StringUtils.isBlank(cacheMode)) {
            cacheMode = "none";
        }
        diskBuilder.append(" cache='").append(cacheMode).append("'/> \n");

        diskBuilder.append("<source ");
        diskBuilder.append(" protocol='rbd'");
        diskBuilder.append(" name='" + volumePath + "'");
        diskBuilder.append(">\n");
        for (String sourceHost : volumePool.getHost().split(",")) {
            diskBuilder.append("<host name='");
            diskBuilder.append(sourceHost.replace("[", "").replace("]", ""));
            if (volumePool.getPort() != 0) {
                diskBuilder.append("' port='");
                diskBuilder.append(volumePool.getPort());
            }
            diskBuilder.append("'/>\n");
        }
        diskBuilder.append("</source>\n");
        String authUserName = null;
        final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
        if (primaryPool != null) {
            authUserName = primaryPool.getAuthUserName();
        }
        if (StringUtils.isNotBlank(authUserName)) {
            diskBuilder.append("<auth username='" + authUserName + "'>\n");
            diskBuilder.append("<secret type='ceph' uuid='" + volumePool.getUuid() + "'/>\n");
            diskBuilder.append("</auth>\n");
        }
        diskBuilder.append("<target dev='" + deviceToAttachDiskTo + "'");
        diskBuilder.append(" bus='virtio'");
        diskBuilder.append("/>\n");
        diskBuilder.append("</disk>\n");
        return diskBuilder.toString();
    }

    private void fetchBackupFile(String hostName, String backupPath) {
        int mkdirExit = Script.runSimpleBashScriptForExitValue(String.format(MKDIR_P, backupPath));
        if (mkdirExit != 0) {
            throw new CloudRuntimeException(String.format("Failed to create local backup directory: %s", backupPath));
        }

        String cmd = String.format(RSYNC_DIR_FROM_REMOTE, hostName, backupPath, backupPath);
        logger.debug("Fetching commvault backup directory from remote host. cmd={}", cmd);

        int exit = Script.runSimpleBashScriptForExitValue(cmd);
        if (exit != 0) {
            throw new CloudRuntimeException(String.format(
                    "Failed to fetch backup directory from remote host [%s]. remotePath=[%s], localPath=[%s]",
                    hostName, backupPath, backupPath));
        }
    }
}