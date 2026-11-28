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
import com.cloud.agent.api.GetVolumesOnStorageAnswer;
import com.cloud.agent.api.GetVolumesOnStorageCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.RbdVolumeAllocation;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.volume.VolumeOnStorageTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.LibvirtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@ResourceWrapper(handles = GetVolumesOnStorageCommand.class)
public final class LibvirtGetVolumesOnStorageCommandWrapper extends CommandWrapper<GetVolumesOnStorageCommand, Answer, LibvirtComputingResource> {

    static final List<StoragePoolType> STORAGE_POOL_TYPES_SUPPORTED_BY_QEMU_IMG = Arrays.asList(StoragePoolType.NetworkFilesystem,
            StoragePoolType.Filesystem, StoragePoolType.RBD, StoragePoolType.SharedMountPoint);

    @Override
    public Answer execute(final GetVolumesOnStorageCommand command, final LibvirtComputingResource libvirtComputingResource) {

        final StorageFilerTO pool = command.getPool();
        final String volumePath = command.getVolumePath();
        final String keyword = command.getKeyword();

        final KVMStoragePoolManager storagePoolMgr = libvirtComputingResource.getStoragePoolMgr();
        final KVMStoragePool storagePool = storagePoolMgr.getStoragePool(pool.getType(), pool.getUuid(), true, true);

        if (StringUtils.isNotBlank(volumePath)) {
            return addVolumeByVolumePath(command, storagePool, volumePath);
        } else {
            return addAllVolumes(command, storagePool, keyword);
        }
    }

    private GetVolumesOnStorageAnswer addVolumeByVolumePath(final GetVolumesOnStorageCommand command, final KVMStoragePool storagePool, String volumePath) {
        List<VolumeOnStorageTO> volumes = new ArrayList<>();

        try {
            KVMPhysicalDisk disk = storagePool.getPhysicalDisk(volumePath);
            if (disk == null) {
                return new GetVolumesOnStorageAnswer(command, volumes);
            }
            if (!volumePath.equals(disk.getPath()) && !volumePath.equals(disk.getName())) {
                return new GetVolumesOnStorageAnswer(command, false, "Requested volume does not match the selected pool entry");
            }
            volumes.add(inspectVolume(storagePool, disk, true));
        } catch (CloudRuntimeException error) {
            return new GetVolumesOnStorageAnswer(command, false, error.getMessage());
        }
        return new GetVolumesOnStorageAnswer(command, volumes);
    }

    private VolumeOnStorageTO inspectVolume(KVMStoragePool pool, KVMPhysicalDisk disk, boolean verifyRbd) {
        if (StoragePoolType.RBD.equals(pool.getType())) {
            VolumeOnStorageTO volume = describe(disk, PhysicalDiskFormat.RAW.toString(), disk.getVirtualSize());
            if (verifyRbd) {
                RbdVolumeAllocation.ImportImage inspection = new RbdVolumeAllocation().inspectImportImage(pool, disk.getName());
                if (inspection.size() != volume.getVirtualSize()) {
                    throw new CloudRuntimeException("RBD size changed. Refresh the storage pool and retry");
                }
                volume.addDetail(VolumeOnStorageTO.Detail.SOURCE_IDENTITY, inspection.identity());
                volume.addDetail(VolumeOnStorageTO.Detail.IS_LOCKED, String.valueOf(inspection.hasWatchers()));
                if (inspection.hasParent()) {
                    volume.addDetail(VolumeOnStorageTO.Detail.BACKING_FILE, "RBD parent");
                }
            }
            return volume;
        }

        String identity = inspectFileIdentity(pool, disk);
        Map<String, String> info = getDiskFileInfo(pool, disk, true);
        if (info == null) {
            throw new CloudRuntimeException("Cannot read the disk format. The source may be locked or unsupported");
        }
        String format = StringUtils.defaultIfBlank(info.get(QemuImg.FILE_FORMAT), disk.getFormat() == null ? null : disk.getFormat().toString());
        if (!PhysicalDiskFormat.QCOW2.toString().equalsIgnoreCase(format) && !PhysicalDiskFormat.RAW.toString().equalsIgnoreCase(format)) {
            throw new CloudRuntimeException("Unsupported disk format");
        }
        long size = StringUtils.isNumeric(info.get(QemuImg.VIRTUAL_SIZE)) ? Long.parseLong(info.get(QemuImg.VIRTUAL_SIZE)) : disk.getVirtualSize();
        VolumeOnStorageTO volume = describe(disk, format.toUpperCase(Locale.ROOT), size);
        addDetailsToVolumeOnStorageTO(volume, info, pool, disk);
        if (identity != null) {
            if (!identity.equals(inspectFileIdentity(pool, disk))) {
                throw new CloudRuntimeException("Source file changed during inspection");
            }
            volume.addDetail(VolumeOnStorageTO.Detail.SOURCE_IDENTITY, identity);
        }
        return volume;
    }

    private VolumeOnStorageTO describe(KVMPhysicalDisk disk, String format, long virtualSize) {
        VolumeOnStorageTO volume = new VolumeOnStorageTO(Hypervisor.HypervisorType.KVM, disk.getName(), disk.getName(), disk.getPath(),
                format, disk.getSize(), virtualSize);
        if (disk.getQemuEncryptFormat() != null) {
            volume.setQemuEncryptFormat(disk.getQemuEncryptFormat().toString());
        }
        return volume;
    }

    String inspectFileIdentity(KVMStoragePool pool, KVMPhysicalDisk disk) {
        if (!STORAGE_POOL_TYPES_SUPPORTED_BY_QEMU_IMG.contains(pool.getType()) || StoragePoolType.RBD.equals(pool.getType())) {
            return null;
        }
        try {
            Path root = Path.of(pool.getLocalPath()).toRealPath();
            Path source = Path.of(disk.getPath()).toAbsolutePath().normalize();
            Path resolved = source.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(resolved, BasicFileAttributes.class);
            if (!resolved.startsWith(root) || !attributes.isRegularFile() || !source.equals(resolved) && Files.isSymbolicLink(source)) {
                throw new CloudRuntimeException("The source must be a regular file in the selected storage pool");
            }
            Object inode = Files.getAttribute(resolved, "unix:ino");
            return "file:" + DigestUtils.sha256Hex(inode + ":" + attributes.size() + ":" + attributes.lastModifiedTime());
        } catch (IOException | IllegalArgumentException error) {
            throw new CloudRuntimeException("The source file in the selected storage pool cannot be read");
        }
    }

    private void addDetailsToVolumeOnStorageTO(VolumeOnStorageTO volumeOnStorageTO, final Map<String, String> info, final KVMStoragePool storagePool, final KVMPhysicalDisk disk) {
        if (MapUtils.isEmpty(info)) {
            return;
        }

        String backingFilePath = info.get(QemuImg.BACKING_FILE);
        if (StringUtils.isNotBlank(backingFilePath)) {
            volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.BACKING_FILE, backingFilePath);
        }
        String backingFileFormat = info.get(QemuImg.BACKING_FILE_FORMAT);
        if (StringUtils.isNotBlank(backingFileFormat)) {
            volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.BACKING_FILE_FORMAT, backingFileFormat);
        }
        String clusterSize = info.get(QemuImg.CLUSTER_SIZE);
        if (StringUtils.isNotBlank(clusterSize)) {
            volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.CLUSTER_SIZE, clusterSize);
        }
        String fileFormat = info.get(QemuImg.FILE_FORMAT);
        if (StringUtils.isNotBlank(fileFormat)) {
            volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.FILE_FORMAT, fileFormat);
        }
        String encrypted = info.get(QemuImg.ENCRYPTED);
        if (StringUtils.isNotBlank(encrypted) && encrypted.equalsIgnoreCase("yes")) {
            volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.IS_ENCRYPTED, String.valueOf(Boolean.TRUE));
        }
        Boolean isLocked = isDiskFileLocked(storagePool, disk);
        volumeOnStorageTO.addDetail(VolumeOnStorageTO.Detail.IS_LOCKED, String.valueOf(isLocked));
    }

    private GetVolumesOnStorageAnswer addAllVolumes(final GetVolumesOnStorageCommand command, final KVMStoragePool storagePool, String keyword) {
        List<VolumeOnStorageTO> volumes = new ArrayList<>();

        List<KVMPhysicalDisk> disks = storagePool.listPhysicalDisks();
        if (StringUtils.isNotBlank(keyword)) {
            disks = disks.stream().filter(disk -> disk.getName().contains(keyword)).collect(Collectors.toList());
        }
        disks.sort(Comparator.comparing(KVMPhysicalDisk::getName));
        for (KVMPhysicalDisk disk : disks) {
            try {
                volumes.add(inspectVolume(storagePool, disk, false));
            } catch (CloudRuntimeException ignored) {
                logger.debug("Skipping a disk that cannot be inspected in the selected storage pool");
            }
        }
        return new GetVolumesOnStorageAnswer(command, volumes);
    }

    private boolean isDiskFileLocked(KVMStoragePool pool, KVMPhysicalDisk disk) {
        Map<String, String> info = getDiskFileInfo(pool, disk, false);
        return info == null;
    }

    private Map<String, String> getDiskFileInfo(KVMStoragePool pool, KVMPhysicalDisk disk, boolean secure) {
        if (!STORAGE_POOL_TYPES_SUPPORTED_BY_QEMU_IMG.contains(pool.getType())) {
            return new HashMap<>(); // unknown
        }
        try {
            QemuImg qemu = new QemuImg(30_000);
            QemuImgFile qemuFile = new QemuImgFile(disk.getPath(), disk.getFormat());
            return qemu.info(qemuFile, secure);
        } catch (QemuImgException | LibvirtException ex) {
            logger.debug("Unable to read file image metadata in the selected storage pool");
            return null;
        }
    }
}
