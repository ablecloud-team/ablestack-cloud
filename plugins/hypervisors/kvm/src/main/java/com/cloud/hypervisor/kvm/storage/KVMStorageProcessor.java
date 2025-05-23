/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.storage;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;
import static com.cloud.utils.storage.S3.S3Utils.putFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.naming.ConfigurationException;

import org.apache.cloudstack.agent.directdownload.DirectDownloadAnswer;
import org.apache.cloudstack.agent.directdownload.DirectDownloadCommand;
import org.apache.cloudstack.direct.download.DirectDownloadHelper;
import org.apache.cloudstack.direct.download.DirectTemplateDownloader;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.command.CheckDataStoreStoragePolicyComplainceCommand;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.command.CreateObjectAnswer;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachAnswer;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.command.FlattenCmdAnswer;
import org.apache.cloudstack.storage.command.FlattenCommand;
import org.apache.cloudstack.storage.command.ForgetObjectCmd;
import org.apache.cloudstack.storage.command.IntroduceObjectCmd;
import org.apache.cloudstack.storage.command.ResignatureAnswer;
import org.apache.cloudstack.storage.command.ResignatureCommand;
import org.apache.cloudstack.storage.command.SnapshotAndCopyAnswer;
import org.apache.cloudstack.storage.command.SnapshotAndCopyCommand;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.formatinspector.Qcow2Inspector;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.cryptsetup.KeyFile;
import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuObject;
import org.apache.cloudstack.utils.qemu.QemuObject.EncryptFormat;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.DomainSnapshot;
import org.libvirt.LibvirtException;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.ErrorCode;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
import com.ceph.rbd.jna.RbdSnapInfo;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.PrimaryStorageDownloadAnswer;
import com.cloud.agent.api.to.DataObjectType;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.agent.properties.AgentProperties;
import com.cloud.agent.properties.AgentPropertiesFileHandler;
import com.cloud.exception.InternalErrorException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DeviceType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiscardType;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskProtocol;
import com.cloud.hypervisor.kvm.resource.wrapper.LibvirtUtilitiesHelper;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.JavaStorageLayer;
import com.cloud.storage.MigrationOptions;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageLayer;
import com.cloud.storage.Volume;
import com.cloud.storage.resource.StorageProcessor;
import com.cloud.storage.template.Processor;
import com.cloud.storage.template.Processor.FormatInfo;
import com.cloud.storage.template.QCOW2Processor;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.storage.template.TemplateLocation;
import com.cloud.utils.Pair;
import com.cloud.utils.UriUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.utils.storage.S3.S3Utils;
import com.cloud.vm.VmDetailConstants;


public class KVMStorageProcessor implements StorageProcessor {
    protected Logger logger = LogManager.getLogger(getClass());
    private final KVMStoragePoolManager storagePoolMgr;
    private final LibvirtComputingResource resource;
    private StorageLayer storageLayer;
    private String _createTmplPath;
    private String _manageSnapshotPath;
    private String _convertKvdoTemplate;
    private int _cmdsTimeout;

    private static final String MANAGE_SNAPSTHOT_CREATE_OPTION = "-c";
    private static final String NAME_OPTION = "-n";
    private static final String CEPH_MON_HOST = "mon_host";
    private static final String CEPH_AUTH_KEY = "key";
    private static final String CEPH_CLIENT_MOUNT_TIMEOUT = "client_mount_timeout";
    private static final String CEPH_DEFAULT_MOUNT_TIMEOUT = "30";
    /**
     * Time interval before rechecking virsh commands
     */
    private long waitDelayForVirshCommands = 1000l;

    public KVMStorageProcessor(final KVMStoragePoolManager storagePoolMgr, final LibvirtComputingResource resource) {
        this.storagePoolMgr = storagePoolMgr;
        this.resource = resource;
    }

    protected String getDefaultStorageScriptsDir() {
        return "scripts/storage/qcow2";
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        storageLayer = new JavaStorageLayer();
        storageLayer.configure("StorageLayer", params);

        String storageScriptsDir = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.STORAGE_SCRIPTS_DIR);

        _createTmplPath = Script.findScript(storageScriptsDir, "createtmplt.sh");
        if (_createTmplPath == null) {
            throw new ConfigurationException("Unable to find the createtmplt.sh");
        }

        _manageSnapshotPath = Script.findScript(storageScriptsDir, "managesnapshot.sh");
        if (_manageSnapshotPath == null) {
            throw new ConfigurationException("Unable to find the managesnapshot.sh");
        }

        _cmdsTimeout = AgentPropertiesFileHandler.getPropertyValue(AgentProperties.CMDS_TIMEOUT) * 1000;
        return true;
    }

    @Override
    public SnapshotAndCopyAnswer snapshotAndCopy(final SnapshotAndCopyCommand cmd) {
        logger.info("'SnapshotAndCopyAnswer snapshotAndCopy(SnapshotAndCopyCommand)' not currently used for KVMStorageProcessor");

        return new SnapshotAndCopyAnswer();
    }

    @Override
    public ResignatureAnswer resignature(final ResignatureCommand cmd) {
        logger.info("'ResignatureAnswer resignature(ResignatureCommand)' not currently used for KVMStorageProcessor");

        return new ResignatureAnswer();
    }

    @Override
    public Answer copyTemplateToPrimaryStorage(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final TemplateObjectTO template = (TemplateObjectTO)srcData;
        final DataStoreTO imageStore = template.getDataStore();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)destData.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        final NfsTO nfsImageStore = (NfsTO)imageStore;
        final String tmplturl = nfsImageStore.getUrl() + File.separator + template.getPath();
        final int index = tmplturl.lastIndexOf("/");
        final String mountpoint = tmplturl.substring(0, index);
        String tmpltname = null;
        if (index < tmplturl.length() - 1) {
            tmpltname = tmplturl.substring(index + 1);
        }

        KVMPhysicalDisk tmplVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = storagePoolMgr.getStoragePoolByURI(mountpoint);

            /* Get template vol */
            if (tmpltname == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    return new PrimaryStorageDownloadAnswer("Failed to get volumes from pool: " + secondaryPool.getUuid());
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        tmplVol = disk;
                        break;
                    }
                }
            } else {
                tmplVol = secondaryPool.getPhysicalDisk(tmpltname);
            }

            if (tmplVol == null) {
                return new PrimaryStorageDownloadAnswer("Failed to get template from pool: " + secondaryPool.getUuid());
            }

            /* Copy volume to primary storage */
            tmplVol.setUseAsTemplate();
            logger.debug("Copying template to primary storage, template format is " + tmplVol.getFormat() );
            final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            KVMPhysicalDisk primaryVol = null;
            if (destData instanceof VolumeObjectTO) {
                final VolumeObjectTO volume = (VolumeObjectTO)destData;
                // pass along volume's target size if it's bigger than template's size, for storage types that copy template rather than cloning on deploy
                if (volume.getSize() != null && volume.getSize() > tmplVol.getVirtualSize()) {
                    logger.debug("Using configured size of " + toHumanReadableSize(volume.getSize()));
                    tmplVol.setSize(volume.getSize());
                    tmplVol.setVirtualSize(volume.getSize());
                } else {
                    logger.debug("Using template's size of " + toHumanReadableSize(tmplVol.getVirtualSize()));
                }
                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, volume.getUuid(), primaryPool, cmd.getWaitInMillSeconds());
            } else if (destData instanceof TemplateObjectTO) {
                TemplateObjectTO destTempl = (TemplateObjectTO)destData;

                Map<String, String> details = primaryStore.getDetails();

                String path = derivePath(primaryStore, destData, details);

                if (!storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details)) {
                    logger.warn("Failed to connect physical disk at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                }

                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, path != null ? path : destTempl.getUuid(), primaryPool, cmd.getWaitInMillSeconds());

                if (!storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path)) {
                    logger.warn("Failed to disconnect physical disk at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                }
            } else {
                primaryVol = storagePoolMgr.copyPhysicalDisk(tmplVol, UUID.randomUUID().toString(), primaryPool, cmd.getWaitInMillSeconds());
            }

            DataTO data = null;
            /**
             * Force the ImageFormat for RBD templates to RAW
             *
             */
            if (destData.getObjectType() == DataObjectType.TEMPLATE) {
                final TemplateObjectTO newTemplate = new TemplateObjectTO();
                newTemplate.setPath(primaryVol.getName());
                newTemplate.setSize(primaryVol.getSize());

                if(List.of(
                    StoragePoolType.RBD,
                    StoragePoolType.PowerFlex,
                    StoragePoolType.Linstor,
                    StoragePoolType.FiberChannel).contains(primaryPool.getType())) {
                    newTemplate.setFormat(ImageFormat.RAW);
                } else {
                    newTemplate.setFormat(ImageFormat.QCOW2);
                }
                data = newTemplate;
            } else if (destData.getObjectType() == DataObjectType.VOLUME) {
                final VolumeObjectTO volumeObjectTO = new VolumeObjectTO();
                volumeObjectTO.setPath(primaryVol.getName());
                volumeObjectTO.setSize(primaryVol.getSize());
                if (primaryVol.getFormat() == PhysicalDiskFormat.RAW) {
                    volumeObjectTO.setFormat(ImageFormat.RAW);
                } else if (primaryVol.getFormat() == PhysicalDiskFormat.QCOW2) {
                    volumeObjectTO.setFormat(ImageFormat.QCOW2);
                }
                data = volumeObjectTO;
            }
            return new CopyCmdAnswer(data);
        } catch (final CloudRuntimeException e) {
            return new CopyCmdAnswer(e.toString());
        } finally {
            try {
                if (secondaryPool != null) {
                    secondaryPool.delete();
                }
            } catch(final Exception e) {
                logger.debug("Failed to clean up secondary storage", e);
            }
        }
    }

    private String derivePath(PrimaryDataStoreTO primaryStore, DataTO destData, Map<String, String> details) {
        String path = null;
        if (primaryStore.getPoolType() == StoragePoolType.FiberChannel) {
            path = destData.getPath();
        } else {
            path = details != null ? details.get("managedStoreTarget") : null;
        }
        return path;
    }

    // this is much like PrimaryStorageDownloadCommand, but keeping it separate. copies template direct to root disk
    private KVMPhysicalDisk templateToPrimaryDownload(final String templateUrl, final KVMStoragePool primaryPool, final String volUuid, final Long size, final int timeout) {
        final int index = templateUrl.lastIndexOf("/");
        final String mountpoint = templateUrl.substring(0, index);
        String templateName = null;
        if (index < templateUrl.length() - 1) {
            templateName = templateUrl.substring(index + 1);
        }

        KVMPhysicalDisk templateVol = null;
        KVMStoragePool secondaryPool = null;
        try {
            secondaryPool = storagePoolMgr.getStoragePoolByURI(mountpoint);
            /* Get template vol */
            if (templateName == null) {
                secondaryPool.refresh();
                final List<KVMPhysicalDisk> disks = secondaryPool.listPhysicalDisks();
                if (disks == null || disks.isEmpty()) {
                    logger.error("Failed to get volumes from pool: " + secondaryPool.getUuid());
                    return null;
                }
                for (final KVMPhysicalDisk disk : disks) {
                    if (disk.getName().endsWith("qcow2")) {
                        templateVol = disk;
                        break;
                    }
                }
                if (templateVol == null) {
                    logger.error("Failed to get template from pool: " + secondaryPool.getUuid());
                    return null;
                }
            } else {
                templateVol = secondaryPool.getPhysicalDisk(templateName);
            }

            /* Copy volume to primary storage */

            if (size > templateVol.getSize()) {
                logger.debug("Overriding provided template's size with new size " + toHumanReadableSize(size));
                templateVol.setSize(size);
                templateVol.setVirtualSize(size);
            } else {
                logger.debug("Using templates disk size of " + toHumanReadableSize(templateVol.getVirtualSize()) + "since size passed was " + toHumanReadableSize(size));
            }

            final KVMPhysicalDisk primaryVol = storagePoolMgr.copyPhysicalDisk(templateVol, volUuid, primaryPool, timeout);
            return primaryVol;
        } catch (final CloudRuntimeException e) {
            logger.error("Failed to download template to primary storage", e);
            return null;
        } finally {
            if (secondaryPool != null) {
                secondaryPool.delete();
            }
        }
    }

    @Override
    public Answer cloneVolumeFromBaseTemplate(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final TemplateObjectTO template = (TemplateObjectTO)srcData;
        final DataStoreTO imageStore = template.getDataStore();
        final VolumeObjectTO volume = (VolumeObjectTO)destData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();
        KVMPhysicalDisk BaseVol = null;
        KVMStoragePool primaryPool = null;
        KVMPhysicalDisk vol = null;

        try {
            primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            String templatePath = template.getPath();

            if (primaryPool.getType() == StoragePoolType.CLVM) {
                templatePath = ((NfsTO)imageStore).getUrl() + File.separator + templatePath;
                vol = templateToPrimaryDownload(templatePath, primaryPool, volume.getUuid(), volume.getSize(), cmd.getWaitInMillSeconds());
            } if (storagePoolMgr.supportsPhysicalDiskCopy(primaryPool.getType())) {
                Map<String, String> details = primaryStore.getDetails();
                String path = derivePath(primaryStore, destData, details);

                if (!storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath, details)) {
                    logger.warn("Failed to connect base template volume [id: {}, name: {}, path:" +
                            " {}], in storage pool [id: {}, name: {}]", template.getUuid(),
                            template.getName(), templatePath, primaryStore.getUuid(), primaryStore.getName());
                }

                BaseVol = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath);
                if (BaseVol == null) {
                    logger.debug("Failed to get the physical disk for base template volume [id: {}, name: {}, path: {}]", template.getUuid(), template.getName(), templatePath);
                    throw new CloudRuntimeException(String.format("Failed to get the physical disk for base template volume [id: %s, name: %s, path: %s]", template.getUuid(), template.getName(), templatePath));
                }

                if (!storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details)) {
                    logger.warn("Failed to connect new volume at path: {}, in storage pool [id: {}, name: {}]", path, primaryStore.getUuid(), primaryStore.getName());
                }
                BaseVol.setDispName(template.getName());

                vol = storagePoolMgr.copyPhysicalDisk(BaseVol, path != null ? path : volume.getUuid(), primaryPool, cmd.getWaitInMillSeconds(), null, volume.getPassphrase(), volume.getProvisioningType());

                storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);
            } else {
                if (templatePath.contains("/mnt")) {
                    //upgrade issue, if the path contains path, need to extract the volume uuid from path
                    templatePath = templatePath.substring(templatePath.lastIndexOf(File.separator) + 1);
                }
                BaseVol = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), templatePath);
                vol = storagePoolMgr.createDiskFromTemplate(BaseVol, volume.getUuid(), volume.getProvisioningType(),
                        BaseVol.getPool(), volume.getSize(), cmd.getWaitInMillSeconds(), volume.getPassphrase());
            }
            if (vol == null) {
                return new CopyCmdAnswer(" Can't create storage volume on storage pool");
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(vol.getName());
            newVol.setSize(volume.getSize());
            if (vol.getQemuEncryptFormat() != null) {
                newVol.setEncryptFormat(vol.getQemuEncryptFormat().toString());
            }

            if (vol.getFormat() == PhysicalDiskFormat.RAW) {
                newVol.setFormat(ImageFormat.RAW);
            } else if (vol.getFormat() == PhysicalDiskFormat.QCOW2) {
                newVol.setFormat(ImageFormat.QCOW2);
            } else if (vol.getFormat() == PhysicalDiskFormat.DIR) {
                newVol.setFormat(ImageFormat.DIR);
            }

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to create volume: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    @Override
    public Answer copyVolumeFromImageCacheToPrimary(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)destStore;
        if (!(srcStore instanceof NfsTO)) {
            return new CopyCmdAnswer("can only handle nfs storage");
        }
        final NfsTO nfsStore = (NfsTO)srcStore;
        final String srcVolumePath = srcData.getPath();
        final String secondaryStorageUrl = nfsStore.getUrl();
        KVMStoragePool secondaryStoragePool = null;
        KVMStoragePool primaryPool = null;
        try {
            try {
                primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            } catch (final CloudRuntimeException e) {
                if (e.getMessage().contains("not found")) {
                    primaryPool =
                            storagePoolMgr.createStoragePool(primaryStore.getUuid(), primaryStore.getHost(), primaryStore.getPort(), primaryStore.getPath(), null,
                                    primaryStore.getPoolType());
                } else {
                    return new CopyCmdAnswer(e.getMessage());
                }
            }

            Map<String, String> details = cmd.getOptions2();

            String path = details != null ? details.get(DiskTO.IQN) : null;

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details);

            final String volumeName = UUID.randomUUID().toString();

            final int index = srcVolumePath.lastIndexOf(File.separator);
            final String volumeDir = srcVolumePath.substring(0, index);
            String srcVolumeName = srcVolumePath.substring(index + 1);

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl + File.separator + volumeDir);

            if (!srcVolumeName.endsWith(".qcow2") && srcFormat == ImageFormat.QCOW2) {
                srcVolumeName = srcVolumeName + ".qcow2";
            }

            final KVMPhysicalDisk volume = secondaryStoragePool.getPhysicalDisk(srcVolumeName);

            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));
            volume.setDispName(srcVol.getName());
            volume.setVmName(srcVol.getVmName());

            final KVMPhysicalDisk newDisk = storagePoolMgr.copyPhysicalDisk(volume, path != null ? path : volumeName, primaryPool, cmd.getWaitInMillSeconds());

            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);

            final VolumeObjectTO newVol = new VolumeObjectTO();

            newVol.setFormat(ImageFormat.valueOf(newDisk.getFormat().toString().toUpperCase()));
            newVol.setPath(path != null ? path : volumeName);

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromImageCacheToPrimary: ", e);

            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            if (secondaryStoragePool != null) {
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            }
        }
    }

    @Override
    public Answer copyVolumeFromPrimaryToSecondary(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final VolumeObjectTO destVol = (VolumeObjectTO)destData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final ImageFormat destFormat = destVol.getFormat();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)srcStore;
        if (!(destStore instanceof NfsTO)) {
            return new CopyCmdAnswer("can only handle nfs storage");
        }
        final NfsTO nfsStore = (NfsTO)destStore;
        final String srcVolumePath = srcData.getPath();
        final String destVolumePath = destData.getPath();
        final String secondaryStorageUrl = nfsStore.getUrl();
        KVMStoragePool secondaryStoragePool = null;

        try {
            final String volumeName = UUID.randomUUID().toString();

            final String destVolumeName = volumeName + "." + destFormat.getFileExtension();
            final KVMPhysicalDisk volume = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), srcVolumePath);
            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl);
            secondaryStoragePool.createFolder(destVolumePath);
            storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStorageUrl + File.separator + destVolumePath);
            storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, secondaryStoragePool, cmd.getWaitInMillSeconds());
            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(destVolumePath + File.separator + destVolumeName);
            newVol.setFormat(destFormat);
            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromPrimaryToSecondary: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            destVol.clearPassphrase();
            if (secondaryStoragePool != null) {
                storagePoolMgr.deleteStoragePool(secondaryStoragePool.getType(), secondaryStoragePool.getUuid());
            }
        }
    }

    @Override
    public Answer createTemplateFromVolume(final CopyCommand cmd) {
        Map<String, String> details = cmd.getOptions();

        // handle cases where the managed storage driver had to make a temporary volume from
        // the snapshot in order to support the copy
        if (details != null && (details.get(DiskTO.IQN) != null || details.get(DiskTO.PATH) != null)) {
            // use the managed-storage approach
            return createTemplateFromVolumeOrSnapshot(cmd);
        }

        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final int wait = cmd.getWaitInMillSeconds();
        final TemplateObjectTO template = (TemplateObjectTO)destData;
        final DataStoreTO imageStore = template.getDataStore();
        final VolumeObjectTO volume = (VolumeObjectTO)srcData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }
        final NfsTO nfsImageStore = (NfsTO)imageStore;

        KVMStoragePool secondaryStorage = null;
        KVMStoragePool primary;

        try {
            final String templateFolder = template.getPath();

            secondaryStorage = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl());

            primary = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            final KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());
            final String tmpltPath = secondaryStorage.getLocalPath() + File.separator + templateFolder;
            storageLayer.mkdirs(tmpltPath);
            final String templateName = UUID.randomUUID().toString();

            if (primary.getType() != StoragePoolType.RBD) {
                final Script command = new Script(_createTmplPath, wait, logger);
                command.add("-f", disk.getPath());
                command.add("-t", tmpltPath);
                command.add(NAME_OPTION, templateName + ".qcow2");

                final String result = command.execute();

                if (result != null) {
                    logger.debug("failed to create template: " + result);
                    return new CopyCmdAnswer(result);
                }
            } else {
                logger.debug("Converting RBD disk " + disk.getPath() + " into template " + templateName);
                final QemuImgFile srcFile =
                new QemuImgFile(KVMPhysicalDisk.RBDStringBuilder(primary.getSourceHost(), primary.getSourcePort(), primary.getAuthUserName(),
                primary.getAuthSecret(), disk.getPath()));
                srcFile.setFormat(PhysicalDiskFormat.RAW);

                final QemuImgFile destFile = new QemuImgFile(tmpltPath + "/" + templateName + ".qcow2");
                destFile.setFormat(PhysicalDiskFormat.QCOW2);

                final QemuImg q = new QemuImg(cmd.getWaitInMillSeconds());
                try {
                    q.convert(srcFile, destFile);
                } catch (final QemuImgException | LibvirtException e) {
                    final String message = "Failed to create new template while converting " + srcFile.getFileName() + " to " + destFile.getFileName() + " the error was: " +
                            e.getMessage();

                    throw new QemuImgException(message);
                }

                final File templateProp = new File(tmpltPath + "/template.properties");
                if (!templateProp.exists()) {
                    templateProp.createNewFile();
                }

                String templateContent = "filename=" + templateName + ".qcow2" + System.getProperty("line.separator");

                final DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy");
                final Date date = new Date();
                templateContent += "snapshot.name=" + dateFormat.format(date) + System.getProperty("line.separator");


                try(FileOutputStream templFo = new FileOutputStream(templateProp);){
                    templFo.write(templateContent.getBytes());
                    templFo.flush();
                } catch (final IOException e) {
                    throw e;
                }
            }

            final Map<String, Object> params = new HashMap<String, Object>();
            params.put(StorageLayer.InstanceConfigKey, storageLayer);
            final Processor qcow2Processor = new QCOW2Processor();

            qcow2Processor.configure("QCOW2 Processor", params);

            final FormatInfo info = qcow2Processor.process(tmpltPath, null, templateName);

            final TemplateLocation loc = new TemplateLocation(storageLayer, tmpltPath);
            loc.create(1, true, templateName);
            loc.addFormat(info);
            loc.save();

            final TemplateObjectTO newTemplate = new TemplateObjectTO();
            newTemplate.setPath(templateFolder + File.separator + templateName + ".qcow2");
            newTemplate.setSize(info.virtualSize);
            newTemplate.setPhysicalSize(info.size);
            newTemplate.setFormat(ImageFormat.QCOW2);
            newTemplate.setName(templateName);
            return new CopyCmdAnswer(newTemplate);

        } catch (final QemuImgException e) {
            logger.error(e.getMessage());
            return new CopyCmdAnswer(e.toString());
        } catch (final IOException e) {
            logger.debug("Failed to createTemplateFromVolume: ", e);
            return new CopyCmdAnswer(e.toString());
        } catch (final Exception e) {
            logger.debug("Failed to createTemplateFromVolume: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
            if (secondaryStorage != null) {
                secondaryStorage.delete();
            }
        }
    }

    @Override
    public Answer createTemplateFromSnapshot(CopyCommand cmd) {
        Map<String, String> details = cmd.getOptions();

        if (details != null && (details.get(DiskTO.IQN) != null || details.get(DiskTO.PATH) != null)) {
            // use the managed-storage approach
            return createTemplateFromVolumeOrSnapshot(cmd);
        }

        return new CopyCmdAnswer("operation not supported");
    }

    private Answer createTemplateFromVolumeOrSnapshot(CopyCommand cmd) {
        DataTO srcData = cmd.getSrcTO();

        final boolean isVolume;

        if (srcData instanceof VolumeObjectTO) {
            isVolume = true;
        }
        else if (srcData instanceof SnapshotObjectTO) {
            isVolume = false;
        }
        else {
            return new CopyCmdAnswer("unsupported object type");
        }

        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)srcData.getDataStore();

        DataTO destData = cmd.getDestTO();
        TemplateObjectTO template = (TemplateObjectTO)destData;
        DataStoreTO imageStore = template.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return new CopyCmdAnswer("unsupported protocol");
        }

        NfsTO nfsImageStore = (NfsTO)imageStore;

        KVMStoragePool secondaryStorage = null;

        try {
            // look for options indicating an overridden path or IQN.  Used when snapshots have to be
            // temporarily copied on the manaaged storage device before the actual copy to target object
            Map<String, String> details = cmd.getOptions();
            String path = details != null ? details.get(DiskTO.PATH) : null;
            if (path == null) {
                path = details != null ? details.get(DiskTO.IQN) : null;
                if (path == null) {
                    new CloudRuntimeException("The 'path' or 'iqn' field must be specified.");
                }
            }

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path, details);

            KVMPhysicalDisk srcDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);

            secondaryStorage = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl());

            String templateFolder = template.getPath();
            String tmpltPath = secondaryStorage.getLocalPath() + File.separator + templateFolder;

            storageLayer.mkdirs(tmpltPath);

            String templateName = UUID.randomUUID().toString();

            logger.debug("Converting " + srcDisk.getFormat().toString() + " disk " + srcDisk.getPath() + " into template " + templateName);

            String destName = templateFolder + "/" + templateName + ".qcow2";

            storagePoolMgr.copyPhysicalDisk(srcDisk, destName, secondaryStorage, cmd.getWaitInMillSeconds());

            File templateProp = new File(tmpltPath + "/template.properties");

            if (!templateProp.exists()) {
                templateProp.createNewFile();
            }

            String templateContent = "filename=" + templateName + ".qcow2" + System.getProperty("line.separator");

            DateFormat dateFormat = new SimpleDateFormat("MM_dd_yyyy");
            Date date = new Date();

            if (isVolume) {
                templateContent += "volume.name=" + dateFormat.format(date) + System.getProperty("line.separator");
            }
            else {
                templateContent += "snapshot.name=" + dateFormat.format(date) + System.getProperty("line.separator");
            }

            FileOutputStream templFo = new FileOutputStream(templateProp);

            templFo.write(templateContent.getBytes());
            templFo.flush();
            templFo.close();

            Map<String, Object> params = new HashMap<>();

            params.put(StorageLayer.InstanceConfigKey, storageLayer);

            Processor qcow2Processor = new QCOW2Processor();

            qcow2Processor.configure("QCOW2 Processor", params);

            FormatInfo info = qcow2Processor.process(tmpltPath, null, templateName);

            TemplateLocation loc = new TemplateLocation(storageLayer, tmpltPath);

            loc.create(1, true, templateName);
            loc.addFormat(info);
            loc.save();

            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), path);

            TemplateObjectTO newTemplate = new TemplateObjectTO();

            newTemplate.setPath(templateFolder + File.separator + templateName + ".qcow2");
            newTemplate.setSize(info.virtualSize);
            newTemplate.setPhysicalSize(info.size);
            newTemplate.setFormat(ImageFormat.QCOW2);
            newTemplate.setName(templateName);

            return new CopyCmdAnswer(newTemplate);
        } catch (Exception ex) {
            if (isVolume) {
                logger.debug("Failed to create template from volume: ", ex);
            }
            else {
                logger.debug("Failed to create template from snapshot: ", ex);
            }

            return new CopyCmdAnswer(ex.toString());
        } finally {
            if (secondaryStorage != null) {
                secondaryStorage.delete();
            }
        }
    }

    protected String copyToS3(final File srcFile, final S3TO destStore, final String destPath) throws InterruptedException {
        final String key = destPath + S3Utils.SEPARATOR + srcFile.getName();

        putFile(destStore, srcFile, destStore.getBucketName(), key).waitForCompletion();

        return key;
    }

    protected Answer copyToObjectStore(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO imageStore = destData.getDataStore();
        final NfsTO srcStore = (NfsTO)srcData.getDataStore();
        final String srcPath = srcData.getPath();
        final int index = srcPath.lastIndexOf(File.separator);
        final String srcSnapshotDir = srcPath.substring(0, index);
        final String srcFileName = srcPath.substring(index + 1);
        KVMStoragePool srcStorePool = null;
        File srcFile = null;
        try {
            srcStorePool = storagePoolMgr.getStoragePoolByURI(srcStore.getUrl() + File.separator + srcSnapshotDir);
            if (srcStorePool == null) {
                return new CopyCmdAnswer("Can't get store:" + srcStore.getUrl());
            }
            srcFile = new File(srcStorePool.getLocalPath() + File.separator + srcFileName);
            if (!srcFile.exists()) {
                return new CopyCmdAnswer("Can't find src file: " + srcPath);
            }
            String destPath = null;
            if (imageStore instanceof S3TO) {
                destPath = copyToS3(srcFile, (S3TO)imageStore, destData.getPath());
            } else {
                return new CopyCmdAnswer("Unsupported protocol");
            }
            final SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(destPath);
            return new CopyCmdAnswer(newSnapshot);
        } catch (final Exception e) {
            logger.error("failed to upload" + srcPath, e);
            return new CopyCmdAnswer("failed to upload" + srcPath + e.toString());
        } finally {
            try {
                if (srcFile != null) {
                    srcFile.delete();
                }
                if (srcStorePool != null) {
                    srcStorePool.delete();
                }
            } catch (final Exception e) {
                logger.debug("Failed to clean up:", e);
            }
        }
    }

    protected Answer backupSnapshotForObjectStore(final CopyCommand cmd) {
        final DataTO destData = cmd.getDestTO();
        final DataStoreTO imageStore = destData.getDataStore();
        final DataTO cacheData = cmd.getCacheTO();
        if (cacheData == null) {
            return new CopyCmdAnswer("Failed to copy to object store without cache store");
        }
        final DataStoreTO cacheStore = cacheData.getDataStore();
        ((SnapshotObjectTO)destData).setDataStore(cacheStore);
        final CopyCmdAnswer answer = (CopyCmdAnswer)backupSnapshot(cmd);
        if (!answer.getResult()) {
            return answer;
        }
        final SnapshotObjectTO snapshotOnCacheStore = (SnapshotObjectTO)answer.getNewData();
        snapshotOnCacheStore.setDataStore(cacheStore);
        ((SnapshotObjectTO)destData).setDataStore(imageStore);
        final CopyCommand newCpyCmd = new   CopyCommand(snapshotOnCacheStore, destData, cmd.getWaitInMillSeconds(), cmd.executeInSequence());
        return copyToObjectStore(newCpyCmd);
    }

    @Override
    public Answer backupSnapshot(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)snapshot.getDataStore();
        final SnapshotObjectTO destSnapshot = (SnapshotObjectTO)destData;
        final DataStoreTO imageStore = destData.getDataStore();

        if (!(imageStore instanceof NfsTO)) {
            return backupSnapshotForObjectStore(cmd);
        }
        final NfsTO nfsImageStore = (NfsTO)imageStore;

        final String secondaryStoragePoolUrl = nfsImageStore.getUrl();
        // NOTE: snapshot name is encoded in snapshot path
        final int index = snapshot.getPath().lastIndexOf("/");
        final boolean isCreatedFromVmSnapshot = index == -1; // -1 means the snapshot is created from existing vm snapshot

        final String snapshotName = snapshot.getPath().substring(index + 1);
        String descName = snapshotName;
        final String volumePath = snapshot.getVolume().getPath();
        String snapshotDestPath = null;
        String snapshotRelPath = null;
        final String vmName = snapshot.getVmName();
        KVMStoragePool secondaryStoragePool = null;
        Connect conn = null;
        KVMPhysicalDisk snapshotDisk = null;
        KVMStoragePool primaryPool = null;

        final VolumeObjectTO srcVolume = snapshot.getVolume();
        try {
            conn = LibvirtConnection.getConnectionByVmName(vmName);

            secondaryStoragePool = storagePoolMgr.getStoragePoolByURI(secondaryStoragePoolUrl);

            final String ssPmountPath = secondaryStoragePool.getLocalPath();
            snapshotRelPath = destSnapshot.getPath();

            snapshotDestPath = ssPmountPath + File.separator + snapshotRelPath;
            snapshotDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volumePath);
            primaryPool = snapshotDisk.getPool();

            long size = 0;
            /**
             * Since Ceph version Dumpling (0.67.X) librbd / Qemu supports converting RBD
             * snapshots to RAW/QCOW2 files directly.
             *
             * This reduces the amount of time and storage it takes to back up a snapshot dramatically
             */
            if (primaryPool.getType() == StoragePoolType.RBD) {
                final String rbdSnapshot = snapshotDisk.getPath() +  "@" + snapshotName;
                final String snapshotFile = snapshotDestPath + "/" + snapshotName;
                try {
                    logger.debug("Attempting to backup RBD snapshot " + rbdSnapshot);

                    final File snapDir = new File(snapshotDestPath);
                    logger.debug("Attempting to create " + snapDir.getAbsolutePath() + " recursively for snapshot storage");
                    FileUtils.forceMkdir(snapDir);

                    final QemuImgFile srcFile =
                            new QemuImgFile(KVMPhysicalDisk.RBDStringBuilder(primaryPool.getSourceHost(), primaryPool.getSourcePort(), primaryPool.getAuthUserName(),
                                    primaryPool.getAuthSecret(), rbdSnapshot));
                    srcFile.setFormat(snapshotDisk.getFormat());

                    final QemuImgFile destFile = new QemuImgFile(snapshotFile);
                    destFile.setFormat(PhysicalDiskFormat.QCOW2);

                    logger.debug("Backing up RBD snapshot " + rbdSnapshot + " to " + snapshotFile);
                    final QemuImg q = new QemuImg(cmd.getWaitInMillSeconds());
                    q.convert(srcFile, destFile);

                    final File snapFile = new File(snapshotFile);
                    if(snapFile.exists()) {
                        size = snapFile.length();
                    }

                    logger.debug("Finished backing up RBD snapshot " + rbdSnapshot + " to " + snapshotFile + " Snapshot size: " + toHumanReadableSize(size));
                } catch (final FileNotFoundException e) {
                    logger.error("Failed to open " + snapshotDestPath + ". The error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                } catch (final IOException e) {
                    logger.error("Failed to create " + snapshotDestPath + ". The error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                }  catch (final QemuImgException | LibvirtException e) {
                    logger.error("Failed to backup the RBD snapshot from " + rbdSnapshot +
                            " to " + snapshotFile + " the error was: " + e.getMessage());
                    return new CopyCmdAnswer(e.toString());
                }
            } else if (primaryPool.getType() == StoragePoolType.SharedMountPoint) {
                final Script command = new Script(_manageSnapshotPath, cmd.getWaitInMillSeconds(), logger);
                command.add("-b", isCreatedFromVmSnapshot ? snapshotDisk.getPath() + "@" + snapshot.getPath() : snapshot.getPath());
                command.add(NAME_OPTION, snapshotName);
                command.add("-p", snapshotDestPath);

                descName = UUID.randomUUID().toString();

                command.add("-t", descName);
                final String result = command.execute();
                if (result != null) {
                    logger.debug("Failed to backup snaptshot: " + result);
                    return new CopyCmdAnswer(result);
                }
                final File snapFile = new File(snapshotDestPath + "/" + descName);
                if(snapFile.exists()){
                    size = snapFile.length();
                }
            } else {
                final Script command = new Script(_manageSnapshotPath, cmd.getWaitInMillSeconds(), logger);

                command.add("-b", isCreatedFromVmSnapshot ? snapshotDisk.getPath() : snapshot.getPath());
                command.add(NAME_OPTION, snapshotName);
                command.add("-p", snapshotDestPath);

                descName = UUID.randomUUID().toString();

                command.add("-t", descName);
                final String result = command.execute();
                if (result != null) {
                    logger.debug("Failed to backup snaptshot: " + result);
                    return new CopyCmdAnswer(result);
                }
                final File snapFile = new File(snapshotDestPath + "/" + descName);
                if(snapFile.exists()){
                    size = snapFile.length();
                }
            }

            final SnapshotObjectTO newSnapshot = new SnapshotObjectTO();
            newSnapshot.setPath(snapshotRelPath + File.separator + descName);
            newSnapshot.setPhysicalSize(size);
            return new CopyCmdAnswer(newSnapshot);
        } catch (final LibvirtException | CloudRuntimeException e) {
            logger.debug("Failed to backup snapshot: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVolume.clearPassphrase();
            if (isCreatedFromVmSnapshot) {
                logger.debug("Ignoring removal of vm snapshot on primary as this snapshot is created from vm snapshot");
            } else if (primaryPool != null && primaryPool.getType() != StoragePoolType.RBD) {
                deleteSnapshotOnPrimary(cmd, snapshot, primaryPool);
            }

            try {
                if (secondaryStoragePool != null) {
                    secondaryStoragePool.delete();
                }
            } catch (final Exception ex) {
                logger.debug("Failed to delete secondary storage", ex);
            }
        }
    }

    private void deleteSnapshotOnPrimary(final CopyCommand cmd, final SnapshotObjectTO snapshot,
            KVMStoragePool primaryPool) {
        String snapshotPath = snapshot.getPath();
        String backupSnapshotAfterTakingSnapshot = null;
        boolean deleteSnapshotOnPrimary = true;
        if (cmd.getOptions() != null) {
            backupSnapshotAfterTakingSnapshot = cmd.getOptions().get(SnapshotInfo.BackupSnapshotAfterTakingSnapshot.key());
            deleteSnapshotOnPrimary = cmd.getOptions().get("typeDescription") == null;
        }

        if ((backupSnapshotAfterTakingSnapshot == null || BooleanUtils.toBoolean(backupSnapshotAfterTakingSnapshot)) && deleteSnapshotOnPrimary) {
            try {
                Files.deleteIfExists(Paths.get(snapshotPath));
            } catch (IOException ex) {
                logger.error("Failed to delete snapshot [{}] on primary storage [{}].", snapshot.getId(), snapshot.getName(), ex);
            }
        } else {
            logger.debug("This backup is temporary, not deleting snapshot [{}] on primary storage [{}]", snapshot.getId(), snapshot.getName());
        }
    }

    protected synchronized void attachOrDetachISO(final Connect conn, final String vmName, String isoPath, final boolean isAttach, Map<String, String> params, DataStoreTO store) throws
            LibvirtException, InternalErrorException {
        DiskDef iso = new DiskDef();
        boolean isUefiEnabled = MapUtils.isNotEmpty(params) && params.containsKey("UEFI");
        if (isoPath != null && isAttach) {
            final int index = isoPath.lastIndexOf("/");
            final String path = isoPath.substring(0, index);
            final String name = isoPath.substring(index + 1);
            KVMStoragePool storagePool;
            if (store instanceof PrimaryDataStoreTO) {
                PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO)store;
                storagePool = storagePoolMgr.getStoragePool(primaryDataStoreTO.getPoolType(), store.getUuid());
            } else {
                storagePool = storagePoolMgr.getStoragePoolByURI(path);
            }
            final KVMPhysicalDisk isoVol = storagePool.getPhysicalDisk(name);
            final DiskDef.DiskType isoDiskType = LibvirtComputingResource.getDiskType(isoVol);
            isoPath = isoVol.getPath();

            iso.defISODisk(isoPath, isUefiEnabled, isoDiskType);
        } else {
            iso.defISODisk(null, isUefiEnabled, DiskDef.DiskType.FILE);
        }

        final List<DiskDef> disks = resource.getDisks(conn, vmName);
        attachOrDetachDevice(conn, true, vmName, iso);
        if (!isAttach) {
            for (final DiskDef disk : disks) {
                if (disk.getDeviceType() == DiskDef.DeviceType.CDROM) {
                    resource.cleanupDisk(disk);
                }
            }

        }
    }

    @Override
    public Answer attachIso(final AttachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final TemplateObjectTO isoTO = (TemplateObjectTO)disk.getData();
        final DataStoreTO store = isoTO.getDataStore();

        try {
            String dataStoreUrl = getDataStoreUrlFromStore(store);
            final Connect conn = LibvirtConnection.getConnectionByVmName(cmd.getVmName());
            attachOrDetachISO(conn, cmd.getVmName(), dataStoreUrl + File.separator + isoTO.getPath(), true, cmd.getControllerInfo(), store);
        } catch (final LibvirtException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InternalErrorException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InvalidParameterValueException e) {
            return new Answer(cmd, false, e.toString());
        }

        return new Answer(cmd);
    }

    @Override
    public Answer dettachIso(final DettachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final TemplateObjectTO isoTO = (TemplateObjectTO)disk.getData();
        final DataStoreTO store = isoTO.getDataStore();

        try {
            String dataStoreUrl = getDataStoreUrlFromStore(store);
            final Connect conn = LibvirtConnection.getConnectionByVmName(cmd.getVmName());
            attachOrDetachISO(conn, cmd.getVmName(), dataStoreUrl + File.separator + isoTO.getPath(), false, cmd.getParams(), store);
        } catch (final LibvirtException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InternalErrorException e) {
            return new Answer(cmd, false, e.toString());
        } catch (final InvalidParameterValueException e) {
            return new Answer(cmd, false, e.toString());
        }

        return new Answer(cmd);
    }

    /**
     * Return data store URL from store
     */
    private String getDataStoreUrlFromStore(DataStoreTO store) {
        List<StoragePoolType> supportedPoolType = List.of(StoragePoolType.NetworkFilesystem, StoragePoolType.Filesystem);
        if (!(store instanceof NfsTO) && (!(store instanceof PrimaryDataStoreTO) || !supportedPoolType.contains(((PrimaryDataStoreTO) store).getPoolType()))) {
            logger.error(String.format("Unsupported protocol, store: %s", store.getUuid()));
            throw new InvalidParameterValueException("unsupported protocol");
        }

        if (store instanceof NfsTO) {
            NfsTO nfsStore = (NfsTO)store;
            return nfsStore.getUrl();
        } else if (store instanceof PrimaryDataStoreTO) {
            //In order to support directly downloaded ISOs
            StoragePoolType poolType = ((PrimaryDataStoreTO)store).getPoolType();
            String psHost = ((PrimaryDataStoreTO) store).getHost();
            String psPath = ((PrimaryDataStoreTO) store).getPath();
            if (StoragePoolType.NetworkFilesystem.equals(poolType)) {
                return "nfs://" + psHost + File.separator + psPath;
            } else if (StoragePoolType.Filesystem.equals(poolType)) {
                return StoragePoolType.Filesystem.toString().toLowerCase() + "://" + psHost + File.separator + psPath;
            }
        }
        return store.getUrl();
    }
    protected synchronized void attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final DiskDef xml)
            throws LibvirtException, InternalErrorException {
        attachOrDetachDevice(conn, attach, vmName, xml, 0l);
    }

    /**
     * Attaches or detaches a device (ISO or disk) to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param diskDef disk definition or iso to be attached or detached
     * @param waitDetachDevice value set in milliseconds to wait before assuming device removal failed
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDevice(final Connect conn, final boolean attach, final String vmName, final DiskDef diskDef, long waitDetachDevice)
            throws LibvirtException, InternalErrorException {
        Domain dm = null;
        String diskXml = diskDef.toString();
        String diskPath = diskDef.getDiskPath();
        try {
            dm = conn.domainLookupByName(vmName);

            if (attach) {
                logger.debug("Attaching device: " + diskXml);
                dm.attachDevice(diskXml);
                return;
            }
            logger.debug(String.format("Detaching device: [%s].", diskXml));
            dm.detachDevice(diskXml);
            long wait = waitDetachDevice;
            while (!checkDetachSuccess(diskPath, dm) && wait > 0) {
                wait = getWaitAfterSleep(dm, diskPath, wait);
            }
            if (wait <= 0) {
                throw new InternalErrorException(String.format("Could not detach volume after sending the command and waiting for [%s] milliseconds. Probably the VM does " +
                                "not support the sent detach command or the device is busy at the moment. Try again in a couple of minutes.",
                        waitDetachDevice));
            }
            logger.debug(String.format("The detach command was executed successfully. The device [%s] was removed from the VM instance with UUID [%s].",
                    diskPath, dm.getUUIDString()));
        } catch (final LibvirtException e) {
            if (attach) {
                logger.warn("Failed to attach device to " + vmName + ": " + e.getMessage());
            } else {
                logger.warn("Failed to detach device from " + vmName + ": " + e.getMessage());
            }
            throw e;
        } finally {
            if (dm != null) {
                try {
                    dm.free();
                } catch (final LibvirtException l) {
                    logger.trace("Ignoring libvirt error.", l);
                }
            }
        }
    }

    /**
     * Waits {@link #waitDelayForVirshCommands} milliseconds before checking again if the device has been removed.
     * @return The configured value in wait.detach.device reduced by {@link #waitDelayForVirshCommands}
     * @throws LibvirtException
     */
    private long getWaitAfterSleep(Domain dm, String diskPath, long wait) throws LibvirtException {
        try {
            wait -= waitDelayForVirshCommands;
            Thread.sleep(waitDelayForVirshCommands);
            logger.trace(String.format("Trying to detach device [%s] from VM instance with UUID [%s]. " +
                    "Waiting [%s] milliseconds before assuming the VM was unable to detach the volume.", diskPath, dm.getUUIDString(), wait));
        } catch (InterruptedException e) {
            throw new CloudRuntimeException(e);
        }
        return wait;
    }

    /**
     * Checks if the device has been removed from the instance
     * @param diskPath Path to the device that was removed
     * @param dm instance to be checked if the device was properly removed
     * @throws LibvirtException
     */
    protected boolean checkDetachSuccess(String diskPath, Domain dm) throws LibvirtException {
        LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
        parser.parseDomainXML(dm.getXMLDesc(0));
        List<DiskDef> disks = parser.getDisks();
        for (DiskDef diskDef : disks) {
            if (StringUtils.equals(diskPath, diskDef.getDiskPath())) {
                logger.debug(String.format("The hypervisor sent the detach command, but it is still possible to identify the device [%s] in the instance with UUID [%s].",
                        diskPath, dm.getUUIDString()));
                return false;
            }
        }
        return true;
    }

    /**
     * Attaches or detaches a disk to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param attachingDisk kvm physical disk
     * @param devId device id in instance
     * @param serial
     * @param bytesReadRate bytes read rate
     * @param bytesReadRateMax bytes read rate max
     * @param bytesReadRateMaxLength bytes read rate max length
     * @param bytesWriteRate bytes write rate
     * @param bytesWriteRateMax bytes write rate amx
     * @param bytesWriteRateMaxLength bytes write rate max length
     * @param iopsReadRate iops read rate
     * @param iopsReadRateMax iops read rate max
     * @param iopsReadRateMaxLength iops read rate max length
     * @param iopsWriteRate iops write rate
     * @param iopsWriteRateMax iops write rate max
     * @param iopsWriteRateMaxLength iops write rate max length
     * @param cacheMode cache mode
     * @param encryptDetails encrypt details
     * @param provider Storage Provider
     * @param krbdpath krbd physical host side path
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDisk(final Connect conn, final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk, final int devId,
                                                   final String serial, final Long bytesReadRate, final Long bytesReadRateMax, final Long bytesReadRateMaxLength,
                                                   final Long bytesWriteRate, final Long bytesWriteRateMax, final Long bytesWriteRateMaxLength, final Long iopsReadRate,
                                                   final Long iopsReadRateMax, final Long iopsReadRateMaxLength, final Long iopsWriteRate, final Long iopsWriteRateMax,
                                                   final Long iopsWriteRateMaxLength, final String cacheMode, final DiskDef.LibvirtDiskEncryptDetails encryptDetails,
                                                   final String provider, final String krbdpath, boolean shareable, boolean kvdoEnable, Map<String, String> details)
            throws LibvirtException, InternalErrorException {
        attachOrDetachDisk(conn, attach, vmName, attachingDisk, devId, serial, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength,
                bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength, iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate,
                iopsWriteRateMax, iopsWriteRateMaxLength, cacheMode, encryptDetails, provider, krbdpath, 0l, shareable, kvdoEnable, details);
    }

    /**
     *
     * Attaches or detaches a disk to an instance.
     * @param conn libvirt connection
     * @param attach boolean that determines whether the device will be attached or detached
     * @param vmName instance name
     * @param attachingDisk kvm physical disk
     * @param devId device id in instance
     * @param serial
     * @param bytesReadRate bytes read rate
     * @param bytesReadRateMax bytes read rate max
     * @param bytesReadRateMaxLength bytes read rate max length
     * @param bytesWriteRate bytes write rate
     * @param bytesWriteRateMax bytes write rate amx
     * @param bytesWriteRateMaxLength bytes write rate max length
     * @param iopsReadRate iops read rate
     * @param iopsReadRateMax iops read rate max
     * @param iopsReadRateMaxLength iops read rate max length
     * @param iopsWriteRate iops write rate
     * @param iopsWriteRateMax iops write rate max
     * @param iopsWriteRateMaxLength iops write rate max length
     * @param cacheMode cache mode
     * @param encryptDetails encrypt details
     * @param provider Storage Provider
     * @param krbdpath krbd physical host side path
     * @param waitDetachDevice value set in milliseconds to wait before assuming device removal failed
     * @throws LibvirtException
     * @throws InternalErrorException
     */
    protected synchronized void attachOrDetachDisk(final Connect conn, final boolean attach, final String vmName, final KVMPhysicalDisk attachingDisk, final int devId,
                                                   final String serial, final Long bytesReadRate, final Long bytesReadRateMax, final Long bytesReadRateMaxLength,
                                                   final Long bytesWriteRate, final Long bytesWriteRateMax, final Long bytesWriteRateMaxLength, final Long iopsReadRate,
                                                   final Long iopsReadRateMax, final Long iopsReadRateMaxLength, final Long iopsWriteRate, final Long iopsWriteRateMax,
                                                   final Long iopsWriteRateMaxLength, final String cacheMode, final DiskDef.LibvirtDiskEncryptDetails encryptDetails,
                                                   final String provider, final String krbdpath, long waitDetachDevice, boolean shareable, boolean kvdoEnable, Map<String, String> details)
            throws LibvirtException, InternalErrorException {

        List<DiskDef> disks = null;
        Domain dm = null;
        DiskDef diskdef = null;
        final KVMStoragePool attachingPool = attachingDisk.getPool();
        try {
            dm = conn.domainLookupByName(vmName);
            final LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
            final String domXml = dm.getXMLDesc(0);
            parser.parseDomainXML(domXml);
            disks = parser.getDisks();
            if (!attach) {
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    if (resource.getHypervisorType() == Hypervisor.HypervisorType.LXC) {
                        final String device = resource.mapRbdDevice(attachingDisk, kvdoEnable);
                        if (device != null) {
                            logger.debug("RBD device on host is: "+device);
                            attachingDisk.setPath(device);
                        } else {
                            throw new InternalErrorException("Error while mapping disk "+attachingDisk.getPath()+" on host");
                        }
                    }
                }

                for (final DiskDef disk : disks) {
                    final String file = disk.getDiskPath();
                    if(attachingPool.getType() == StoragePoolType.RBD && provider != null && !provider.isEmpty() && "ABLESTACK".equals(provider)) {
                        if(!kvdoEnable){
                            if (file != null && file.equalsIgnoreCase(krbdpath + "/" + attachingDisk.getPath())) {
                                diskdef = disk;
                                break;
                            }
                        }else{
                            if (file != null && file.contains(attachingDisk.getName().replace("-",""))) {
                                diskdef = disk;
                                break;
                            }
                        }
                    } else {
                        if (file != null && file.equalsIgnoreCase(attachingDisk.getPath())) {
                            diskdef = disk;
                            break;
                        }
                    }
                }
                if (diskdef == null) {
                    logger.warn(String.format("Could not find disk [%s] attached to VM instance with UUID [%s]. We will set it as detached in the database to ensure consistency.",
                            attachingDisk.getPath(), dm.getUUIDString()));
                    return;
                }
            } else {
                DiskDef.DiskBus busT = DiskDef.DiskBus.VIRTIO;
                for (final DiskDef disk : disks) {
                    if (disk.getDeviceType() == DeviceType.DISK) {
                        if (disk.getBusType() == DiskDef.DiskBus.SCSI) {
                            busT = DiskDef.DiskBus.SCSI;
                        }
                        break;
                    }
                }
                diskdef = new DiskDef();
                if (busT == DiskDef.DiskBus.SCSI) {
                    diskdef.setQemuDriver(true);
                    diskdef.setDiscard(DiscardType.UNMAP);
                }
                diskdef.setSerial(serial);
                if (attachingPool.getType() == StoragePoolType.RBD) {
                    if(resource.getHypervisorType() == Hypervisor.HypervisorType.LXC){
                        // For LXC, map image to host and then attach to Vm
                        final String device = resource.mapRbdDevice(attachingDisk, false);
                        if (device != null) {
                            logger.debug("RBD device on host is: "+device);
                            diskdef.defBlockBasedDisk(device, devId, busT);
                        } else {
                            throw new InternalErrorException("Error while mapping disk "+attachingDisk.getPath()+" on host");
                        }
                    } else {
                        if(provider != null && !provider.isEmpty() && "ABLESTACK".equals(provider)){
                            final String device = resource.mapRbdDevice(attachingDisk, kvdoEnable);
                            if (device != null) {
                                if(!kvdoEnable){
                                    logger.debug("RBD device on host is: " + device);
                                    diskdef.defBlockBasedDisk(krbdpath + "/" + attachingDisk.getPath(), devId);
                                }else{
                                    logger.debug("RBD device on host is: " + device);
                                    diskdef.defBlockBasedDisk(device, devId);
                                }
                            } else {
                                throw new InternalErrorException("Error while mapping RBD device on host");
                            }
                        } else {
                            diskdef.defNetworkBasedDisk(attachingDisk.getPath(), attachingPool.getSourceHost(), attachingPool.getSourcePort(), attachingPool.getAuthUserName(),
                            attachingPool.getUuid(), devId, busT, DiskProtocol.RBD, DiskDef.DiskFmtType.RAW);
                        }
                    }
                } else if (attachingPool.getType() == StoragePoolType.Gluster) {
                    final String mountpoint = attachingPool.getLocalPath();
                    final String path = attachingDisk.getPath();
                    final String glusterVolume = attachingPool.getSourceDir().replace("/", "");
                    diskdef.defNetworkBasedDisk(glusterVolume + path.replace(mountpoint, ""), attachingPool.getSourceHost(), attachingPool.getSourcePort(), null,
                            null, devId, busT, DiskProtocol.GLUSTER, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingPool.getType() == StoragePoolType.PowerFlex) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                    if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                        diskdef.setDiskFormatType(DiskDef.DiskFmtType.QCOW2);
                    }
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.QCOW2) {
                    diskdef.defFileBasedDisk(attachingDisk.getPath(), devId, busT, DiskDef.DiskFmtType.QCOW2);
                } else if (attachingDisk.getFormat() == PhysicalDiskFormat.RAW) {
                    diskdef.defBlockBasedDisk(attachingDisk.getPath(), devId, busT);
                    if (attachingPool.getType() == StoragePoolType.Linstor && resource.isQemuDiscardBugFree(busT)) {
                        diskdef.setDiscard(DiscardType.UNMAP);
                    }
                }

                if (encryptDetails != null) {
                    diskdef.setLibvirtDiskEncryptDetails(encryptDetails);
                }

                if ((bytesReadRate != null) && (bytesReadRate > 0)) {
                    diskdef.setBytesReadRate(bytesReadRate);
                }
                if ((bytesReadRateMax != null) && (bytesReadRateMax > 0)) {
                    diskdef.setBytesReadRateMax(bytesReadRateMax);
                }
                if ((bytesReadRateMaxLength != null) && (bytesReadRateMaxLength > 0)) {
                    diskdef.setBytesReadRateMaxLength(bytesReadRateMaxLength);
                }
                if ((bytesWriteRate != null) && (bytesWriteRate > 0)) {
                    diskdef.setBytesWriteRate(bytesWriteRate);
                }
                if ((bytesWriteRateMax != null) && (bytesWriteRateMax > 0)) {
                    diskdef.setBytesWriteRateMax(bytesWriteRateMax);
                }
                if ((bytesWriteRateMaxLength != null) && (bytesWriteRateMaxLength > 0)) {
                    diskdef.setBytesWriteRateMaxLength(bytesWriteRateMaxLength);
                }
                if ((iopsReadRate != null) && (iopsReadRate > 0)) {
                    diskdef.setIopsReadRate(iopsReadRate);
                }
                if ((iopsReadRateMax != null) && (iopsReadRateMax > 0)) {
                    diskdef.setIopsReadRateMax(iopsReadRateMax);
                }
                if ((iopsReadRateMaxLength != null) && (iopsReadRateMaxLength > 0)) {
                    diskdef.setIopsReadRateMaxLength(iopsReadRateMaxLength);
                }
                if ((iopsWriteRate != null) && (iopsWriteRate > 0)) {
                    diskdef.setIopsWriteRate(iopsWriteRate);
                }
                if ((iopsWriteRateMax != null) && (iopsWriteRateMax > 0)) {
                    diskdef.setIopsWriteRateMax(iopsWriteRateMax);
                }
                if ((iopsWriteRateMaxLength != null) && (iopsWriteRateMaxLength > 0)) {
                    diskdef.setIopsWriteRateMaxLength(iopsWriteRateMaxLength);
                }
                if(cacheMode != null) {
                    diskdef.setCacheMode(DiskDef.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
                }

                if(shareable) {
                    diskdef.setDiskFormatType(DiskDef.DiskFmtType.RAW);
                    diskdef.setSharable();
                }

                if(kvdoEnable) {
                    diskdef.setKvdoEnable();
                }

                diskdef.isIothreadsEnabled(details != null && details.containsKey(VmDetailConstants.IOTHREADS));

                String ioDriver = (details != null && details.containsKey(VmDetailConstants.IO_POLICY)) ? details.get(VmDetailConstants.IO_POLICY) : null;
                if (ioDriver != null) {
                    resource.setDiskIoDriver(diskdef, resource.getIoDriverForTheStorage(ioDriver.toUpperCase()));
                }
                diskdef.setPhysicalBlockIOSize(attachingPool.getSupportedPhysicalBlockSize());
                diskdef.setLogicalBlockIOSize(attachingPool.getSupportedLogicalBlockSize());
                attachingPool.customizeLibvirtDiskDef(diskdef);
            }

            attachOrDetachDevice(conn, attach, vmName, diskdef, waitDetachDevice);
        } finally {
            if (dm != null) {
                dm.free();
            }
            if(!attach && attachingPool.getType() == StoragePoolType.RBD && provider != null && !provider.isEmpty() && "ABLESTACK".equals(provider)){
                final String unmap = resource.unmapRbdDevice(attachingDisk, kvdoEnable);
                if (unmap == null) {
                    attachingDisk.setPath(krbdpath + "/" + attachingDisk.getPath());
                    logger.debug("Glue Block unmap device on host is: " + attachingDisk.getPath());
                } else {
                    throw new InternalErrorException("Error while Glue Block unmapping disk "+attachingDisk.getPath()+" on host");
                }
            }
        }
    }

    @Override
    public Answer attachVolume(final AttachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        final String vmName = cmd.getVmName();
        String serialType = vol.getUuid();
        if(vol.getShareable()) {
            serialType = vol.getPath();
        }
        final String serial = resource.diskUuidToSerial(serialType);

        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);
            DiskDef.LibvirtDiskEncryptDetails encryptDetails = null;
            if (vol.requiresEncryption()) {
                String secretUuid = resource.createLibvirtVolumeSecret(conn, vol.getPath(), vol.getPassphrase());
                encryptDetails = new DiskDef.LibvirtDiskEncryptDetails(secretUuid, QemuObject.EncryptFormat.enumValue(vol.getEncryptFormat()));
                vol.clearPassphrase();
            }

            storagePoolMgr.connectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath(), disk.getDetails());

            final KVMPhysicalDisk phyDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            final String volCacheMode = vol.getCacheMode() == null ? null : vol.getCacheMode().toString();
            logger.debug(String.format("Attaching physical disk %s with format %s", phyDisk.getPath(), phyDisk.getFormat()));

            attachOrDetachDisk(conn, true, vmName, phyDisk, disk.getDiskSeq().intValue(), serial,
                    vol.getBytesReadRate(), vol.getBytesReadRateMax(), vol.getBytesReadRateMaxLength(),
                    vol.getBytesWriteRate(), vol.getBytesWriteRateMax(), vol.getBytesWriteRateMaxLength(),
                    vol.getIopsReadRate(), vol.getIopsReadRateMax(), vol.getIopsReadRateMaxLength(),
                    vol.getIopsWriteRate(), vol.getIopsWriteRateMax(), vol.getIopsWriteRateMaxLength(), volCacheMode, encryptDetails,
                    primaryStore.getProvider(), primaryStore.getKrbdPath(), vol.getShareable(), vol.getKvdoEnable(), disk.getDetails());

            return new AttachAnswer(disk);
        } catch (final LibvirtException e) {
            logger.debug(String.format("Failed to attach volume [id: %d, uuid: %s, name: %s, path: %s], due to ",
                    vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            return new AttachAnswer(e.toString());
        } catch (final InternalErrorException e) {
            logger.debug(String.format("Failed to attach volume [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new AttachAnswer(e.toString());
        } catch (final CloudRuntimeException e) {
            logger.debug(String.format("Failed to attach volume: [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new AttachAnswer(e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    @Override
    public Answer dettachVolume(final DettachCommand cmd) {
        final DiskTO disk = cmd.getDisk();
        final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        final String vmName = cmd.getVmName();
        String serialType = vol.getUuid();
        if(vol.getShareable()) {
            serialType = vol.getPath();
        }
        final String serial = resource.diskUuidToSerial(serialType);
        long waitDetachDevice = cmd.getWaitDetachDevice();
        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);

            final KVMPhysicalDisk phyDisk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());
            final String volCacheMode = vol.getCacheMode() == null ? null : vol.getCacheMode().toString();

            attachOrDetachDisk(conn, false, vmName, phyDisk, disk.getDiskSeq().intValue(), serial,
                    vol.getBytesReadRate(), vol.getBytesReadRateMax(), vol.getBytesReadRateMaxLength(),
                    vol.getBytesWriteRate(), vol.getBytesWriteRateMax(), vol.getBytesWriteRateMaxLength(),
                    vol.getIopsReadRate(), vol.getIopsReadRateMax(), vol.getIopsReadRateMaxLength(),
                    vol.getIopsWriteRate(), vol.getIopsWriteRateMax(), vol.getIopsWriteRateMaxLength(), volCacheMode, null,
                    primaryStore.getProvider(), primaryStore.getKrbdPath(), waitDetachDevice, vol.getShareable(), vol.getKvdoEnable(), null);

            storagePoolMgr.disconnectPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), vol.getPath());

            return new DettachAnswer(disk);
        } catch (final LibvirtException | InternalErrorException | CloudRuntimeException e) {
            logger.debug(String.format("Failed to detach volume [id: %d, uuid: %s, name: %s, path: %s], due to ", vol.getId(), vol.getUuid(), vol.getName(), vol.getPath()), e);
            return new DettachAnswer(e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    /**
     * Create volume with backing file (linked clone)
     */
    protected KVMPhysicalDisk createLinkedCloneVolume(MigrationOptions migrationOptions, KVMStoragePool srcPool, KVMStoragePool primaryPool, VolumeObjectTO volume, PhysicalDiskFormat format, int timeout) {
        String srcBackingFilePath = migrationOptions.getSrcBackingFilePath();
        boolean copySrcTemplate = migrationOptions.isCopySrcTemplate();
        KVMPhysicalDisk srcTemplate = srcPool.getPhysicalDisk(srcBackingFilePath);
        KVMPhysicalDisk destTemplate;
        if (copySrcTemplate) {
            KVMPhysicalDisk copiedTemplate = storagePoolMgr.copyPhysicalDisk(srcTemplate, srcTemplate.getName(), primaryPool, 10000 * 1000);
            destTemplate = primaryPool.getPhysicalDisk(copiedTemplate.getPath());
        } else {
            destTemplate = primaryPool.getPhysicalDisk(srcBackingFilePath);
        }
        return storagePoolMgr.createDiskWithTemplateBacking(destTemplate, volume.getUuid(), format, volume.getSize(),
                primaryPool, timeout, volume.getPassphrase());
    }

    /**
     * Create full clone volume from VM snapshot
     */
    protected KVMPhysicalDisk createFullCloneVolume(MigrationOptions migrationOptions, VolumeObjectTO volume, KVMStoragePool primaryPool, PhysicalDiskFormat format) {
            logger.debug("For VM migration with full-clone volume: Creating empty stub disk for source disk " + migrationOptions.getSrcVolumeUuid() + " and size: " + toHumanReadableSize(volume.getSize()) + " and format: " + format);
        return primaryPool.createPhysicalDisk(volume.getUuid(), format, volume.getProvisioningType(), volume.getSize(), volume.getPassphrase());
    }

    @Override
    public Answer createVolume(final CreateObjectCommand cmd) {
        final VolumeObjectTO volume = (VolumeObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)volume.getDataStore();

        KVMStoragePool primaryPool = null;
        KVMPhysicalDisk vol = null;
        long disksize;
        try {
            primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            disksize = volume.getSize();
            PhysicalDiskFormat format;
            if (volume.getFormat() == null || StoragePoolType.RBD.equals(primaryStore.getPoolType())) {
                format = primaryPool.getDefaultFormat();
            } else {
                format = PhysicalDiskFormat.valueOf(volume.getFormat().toString().toUpperCase());
            }
            if (volume.getShareable()) {
                format = PhysicalDiskFormat.RAW;
            }

            MigrationOptions migrationOptions = volume.getMigrationOptions();
            if (migrationOptions != null) {
                int timeout = migrationOptions.getTimeout();

                if (migrationOptions.getType() == MigrationOptions.Type.LinkedClone) {
                    KVMStoragePool srcPool = getTemplateSourcePoolUsingMigrationOptions(primaryPool, migrationOptions);
                    vol = createLinkedCloneVolume(migrationOptions, srcPool, primaryPool, volume, format, timeout);
                } else if (migrationOptions.getType() == MigrationOptions.Type.FullClone) {
                    vol = createFullCloneVolume(migrationOptions, volume, primaryPool, format);
                }
            } else {
                vol = primaryPool.createPhysicalDisk(volume.getUuid(), format,
                        volume.getProvisioningType(), disksize, volume.getUsableSize(), volume.getPassphrase());
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            if(vol != null) {
                newVol.setPath(vol.getName());
                if (vol.getQemuEncryptFormat() != null) {
                    newVol.setEncryptFormat(vol.getQemuEncryptFormat().toString());
                }
                if (vol.getFormat() != null) {
                    format = vol.getFormat();
                }
            }
            newVol.setSize(volume.getSize());
            newVol.setFormat(ImageFormat.valueOf(format.toString().toUpperCase()));

            return new CreateObjectAnswer(newVol);
        } catch (final Exception e) {
            logger.debug("Failed to create volume: ", e);
            return new CreateObjectAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    /**
     * XML to take disk-only snapshot of the VM.<br><br>
     * 1st parameter: snapshot's name;<br>
     * 2nd parameter: disk's label (target.dev tag from VM's XML);<br>
     * 3rd parameter: absolute path to create the snapshot;<br>
     * 4th parameter: list of disks to avoid on snapshot {@link #TAG_AVOID_DISK_FROM_SNAPSHOT};
     */
    private static final String XML_CREATE_DISK_SNAPSHOT = "<domainsnapshot><name>%s</name><disks><disk name='%s' snapshot='external'><source file='%s'/></disk>%s</disks>"
      + "</domainsnapshot>";

    /**
     * XML to take full VM snapshot.<br><br>
     * 1st parameter: snapshot's name;<br>
     * 2nd parameter: domain's UUID;<br>
     */
    private static final String XML_CREATE_FULL_VM_SNAPSHOT = "<domainsnapshot><name>%s</name><domain><uuid>%s</uuid></domain></domainsnapshot>";

    /**
     * Tag to avoid disk from snapshot.<br><br>
     * 1st parameter: disk's label (target.dev tag from VM's XML);
     */
    private static final String TAG_AVOID_DISK_FROM_SNAPSHOT = "<disk name='%s' snapshot='no' />";

    /**
     * Virsh command to merge (blockcommit) snapshot into the base file.<br><br>
     * 1st parameter: VM's name;<br>
     * 2nd parameter: disk's label (target.dev tag from VM's XML);<br>
     * 3rd parameter: the absolute path of the base file;
     * 4th parameter: the flag '--delete', if Libvirt supports it. Libvirt started to support it on version <b>6.0.0</b>;
     */
    private static final String COMMAND_MERGE_SNAPSHOT = "virsh blockcommit %s %s --base %s --active --wait %s --pivot";

    /**
     * Flag to take disk-only snapshots from VM.<br><br>
     * Libvirt lib for java does not have the enum virDomainSnapshotCreateFlags.
     * @see <a href="https://libvirt.org/html/libvirt-libvirt-domain-snapshot.html">Module libvirt-domain-snapshot from libvirt</a>
     */
    private static final int VIR_DOMAIN_SNAPSHOT_CREATE_DISK_ONLY = 16;

    /**
     * Min rate between available pool and disk size to take disk snapshot.<br><br>
     * As we are copying the base disk to a folder in the same primary storage, we need at least once more disk size of available space in the primary storage, plus 5% as a
     * security margin.
     */
    private static final double MIN_RATE_BETWEEN_AVAILABLE_POOL_AND_DISK_SIZE_TO_TAKE_DISK_SNAPSHOT = 1.05;

    /**
     * Message that can occurs when using a QEMU binary that does not support live disk snapshot (e.g. CentOS 7 QEMU binaries).
     */
    private static final String LIBVIRT_OPERATION_NOT_SUPPORTED_MESSAGE = "Operation not supported";

    @Override
    public Answer createSnapshot(final CreateObjectCommand cmd) {
        final SnapshotObjectTO snapshotTO = (SnapshotObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)snapshotTO.getDataStore();
        final VolumeObjectTO volume = snapshotTO.getVolume();
        final String snapshotName = ("".equals(snapshotTO.getVmSnapshotName()) || snapshotTO.getVmSnapshotName() == null) ? UUID.randomUUID().toString() : snapshotTO.getVmSnapshotName();
        final String vmName = volume.getVmName();

        try {
            final Connect conn = LibvirtConnection.getConnectionByVmName(vmName);
            DomainInfo.DomainState state = null;
            Domain vm = null;
            if (vmName != null) {
                try {
                    vm = resource.getDomain(conn, vmName);
                    state = vm.getInfo().state;
                } catch (final LibvirtException e) {
                    logger.trace("Ignoring libvirt error.", e);
                }
            }

            if (state == DomainInfo.DomainState.VIR_DOMAIN_RUNNING && volume.requiresEncryption()) {
                throw new CloudRuntimeException("VM is running, encrypted volume snapshots aren't supported");
            }

            final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

            final KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());

            String diskPath = disk.getPath();
            String snapshotPath = diskPath + File.separator + snapshotName;

            if (state == DomainInfo.DomainState.VIR_DOMAIN_RUNNING && !primaryPool.isExternalSnapshot()) {

                validateAvailableSizeOnPoolToTakeVolumeSnapshot(primaryPool, disk);

                try {
                    snapshotPath = getSnapshotPathInPrimaryStorage(primaryPool.getLocalPath(), snapshotName);
                    String diskLabel = takeVolumeSnapshot(resource.getDisks(conn, vmName), snapshotName, diskPath, vm);
                    String convertResult = convertBaseFileToSnapshotFileInPrimaryStorageDir(primaryPool, disk, snapshotPath, volume, cmd.getWait());

                    mergeSnapshotIntoBaseFile(vm, diskLabel, diskPath, snapshotName, volume, conn);

                    validateConvertResult(convertResult, snapshotPath);
                } catch (LibvirtException e) {
                    if (!e.getMessage().contains(LIBVIRT_OPERATION_NOT_SUPPORTED_MESSAGE)) {
                        throw e;
                    }

                    logger.info(String.format("It was not possible to take live disk snapshot for volume [%s], in VM [%s], due to [%s]. We will take full snapshot of the VM"
                            + " and extract the disk instead. Consider upgrading your QEMU binary.", volume, vmName, e.getMessage()));

                    takeFullVmSnapshotForBinariesThatDoesNotSupportLiveDiskSnapshot(vm, snapshotName, vmName);
                    primaryPool.createFolder(TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR);
                    extractDiskFromFullVmSnapshot(disk, volume, snapshotPath, snapshotName, vmName, vm);
                }

                /*
                 * libvirt on RHEL6 doesn't handle resume event emitted from
                 * qemu
                 */
                vm = resource.getDomain(conn, vmName);
                state = vm.getInfo().state;
                if (state == DomainInfo.DomainState.VIR_DOMAIN_PAUSED) {
                    vm.resume();
                }
            } else {
                /**
                 * For RBD we can't use libvirt to do our snapshotting or any Bash scripts.
                 * libvirt also wants to store the memory contents of the Virtual Machine,
                 * but that's not possible with RBD since there is no way to store the memory
                 * contents in RBD.
                 *
                 * So we rely on the Java bindings for RBD to create our snapshot
                 *
                 * This snapshot might not be 100% consistent due to writes still being in the
                 * memory of the Virtual Machine, but if the VM runs a kernel which supports
                 * barriers properly (>2.6.32) this won't be any different then pulling the power
                 * cord out of a running machine.
                 */
                if (primaryPool.getType() == StoragePoolType.RBD) {
                    try {
                        Rados r = radosConnect(primaryPool);

                        final IoCTX io = r.ioCtxCreate(primaryPool.getSourceDir());
                        final Rbd rbd = new Rbd(io);
                        final RbdImage image = rbd.open(disk.getName());

                        logger.debug("Attempting to create RBD snapshot " + disk.getName() + "@" + snapshotName);
                        image.snapCreate(snapshotName);

                        rbd.close(image);
                        r.ioCtxDestroy(io);
                    } catch (final Exception e) {
                        logger.error("A RBD snapshot operation on " + disk.getName() + " failed. The error was: " + e.getMessage());
                    }
                } else if (primaryPool.getType() == StoragePoolType.CLVM) {
                    /* VM is not running, create a snapshot by ourself */
                    final Script command = new Script(_manageSnapshotPath, _cmdsTimeout, logger);
                    command.add(MANAGE_SNAPSTHOT_CREATE_OPTION, disk.getPath());
                    command.add(NAME_OPTION, snapshotName);
                    final String result = command.execute();
                    if (result != null) {
                        logger.debug("Failed to manage snapshot: " + result);
                        return new CreateObjectAnswer("Failed to manage snapshot: " + result);
                    }
                } else if (primaryPool.getType() == StoragePoolType.SharedMountPoint){
                    snapshotPath = getSnapshotPathInPrimaryStorageGFS(primaryPool.getLocalPath(), disk.getName()+ "@"+snapshotName);
                    String convertResult = convertBaseFileToSnapshotFileInPrimaryStorageDir(primaryPool, disk, snapshotPath, volume, cmd.getWait());
                    validateConvertResult(convertResult, snapshotPath);
                } else {
                    snapshotPath = getSnapshotPathInPrimaryStorage(primaryPool.getLocalPath(), disk.getName()+ "@"+snapshotName);
                    String convertResult = convertBaseFileToSnapshotFileInPrimaryStorageDir(primaryPool, disk, snapshotPath, volume, cmd.getWait());
                    validateConvertResult(convertResult, snapshotPath);
                }
            }

            final SnapshotObjectTO newSnapshot = new SnapshotObjectTO();

            newSnapshot.setPath(snapshotPath);
            return new CreateObjectAnswer(newSnapshot);
        } catch (CloudRuntimeException | LibvirtException | IOException ex) {
            String errorMsg = String.format("Failed take snapshot for volume [%s], in VM [%s], due to [%s].", volume, vmName, ex.getMessage());
            logger.error(errorMsg, ex);
            return new CreateObjectAnswer(errorMsg);
        } finally {
            volume.clearPassphrase();
        }
    }

    protected void deleteFullVmSnapshotAfterConvertingItToExternalDiskSnapshot(Domain vm, String snapshotName, VolumeObjectTO volume, String vmName) throws LibvirtException {
        logger.debug(String.format("Deleting full VM snapshot [%s] of VM [%s] as we already converted it to an external disk snapshot of the volume [%s].", snapshotName, vmName,
                volume));

        DomainSnapshot domainSnapshot = vm.snapshotLookupByName(snapshotName);
        domainSnapshot.delete(0);
    }

    protected void extractDiskFromFullVmSnapshot(KVMPhysicalDisk disk, VolumeObjectTO volume, String snapshotPath, String snapshotName, String vmName, Domain vm)
            throws LibvirtException {
        QemuImgFile srcFile = new QemuImgFile(disk.getPath(), disk.getFormat());
        QemuImgFile destFile = new QemuImgFile(snapshotPath, disk.getFormat());

        try {
            QemuImg qemuImg = new QemuImg(_cmdsTimeout);
            logger.debug(String.format("Converting full VM snapshot [%s] of VM [%s] to external disk snapshot of the volume [%s].", snapshotName, vmName, volume));
            qemuImg.convert(srcFile, destFile, null, snapshotName, true);
        } catch (QemuImgException qemuException) {
            String message = String.format("Could not convert full VM snapshot [%s] of VM [%s] to external disk snapshot of volume [%s] due to [%s].", snapshotName, vmName, volume,
                    qemuException.getMessage());

            logger.error(message, qemuException);
            throw new CloudRuntimeException(message, qemuException);
        } finally {
            deleteFullVmSnapshotAfterConvertingItToExternalDiskSnapshot(vm, snapshotName, volume, vmName);
        }
    }

    protected void takeFullVmSnapshotForBinariesThatDoesNotSupportLiveDiskSnapshot(Domain vm, String snapshotName, String vmName) throws LibvirtException {
        String vmUuid = vm.getUUIDString();

        long start = System.currentTimeMillis();
        vm.snapshotCreateXML(String.format(XML_CREATE_FULL_VM_SNAPSHOT, snapshotName, vmUuid));
        logger.debug(String.format("Full VM Snapshot [%s] of VM [%s] took [%s] seconds to finish.", snapshotName, vmName, (System.currentTimeMillis() - start)/1000));
    }

    protected void validateConvertResult(String convertResult, String snapshotPath) throws CloudRuntimeException, IOException {
        if (convertResult == null) {
            return;
        }

        Files.deleteIfExists(Paths.get(snapshotPath));
        throw new CloudRuntimeException(convertResult);
    }

    /**
     * Merges the snapshot into base file to keep volume and VM behavior after stopping - starting.
     * @param vm Domain of the VM;
     * @param diskLabel Disk label to manage snapshot and base file;
     * @param baseFilePath Path of the base file;
     * @param snapshotName Name of the snapshot;
     * @throws LibvirtException
     */
    protected void mergeSnapshotIntoBaseFile(Domain vm, String diskLabel, String baseFilePath, String snapshotName, VolumeObjectTO volume,
            Connect conn) throws LibvirtException {
        boolean isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit = LibvirtUtilitiesHelper.isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit(conn);
        String vmName = vm.getName();
        String mergeCommand = String.format(COMMAND_MERGE_SNAPSHOT, vmName, diskLabel, baseFilePath, isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit ? "--delete" : "");
        String mergeResult = Script.runSimpleBashScript(mergeCommand);

        if (mergeResult == null) {
            logger.debug(String.format("Successfully merged snapshot [%s] into VM [%s] %s base file.", snapshotName, vmName, volume));
            manuallyDeleteUnusedSnapshotFile(isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit, getSnapshotTemporaryPath(baseFilePath, snapshotName));
            return;
        }

        String errorMsg = String.format("Failed to merge snapshot [%s] into VM [%s] %s base file. Command [%s] resulted in [%s]. If the VM is stopped and then started, it"
          + " will start to write in the base file again. All changes made between the snapshot and the VM stop will be in the snapshot. If the VM is stopped, the snapshot must be"
          + " merged into the base file manually.", snapshotName, vmName, volume, mergeCommand, mergeResult);

        logger.warn(String.format("%s VM XML: [%s].", errorMsg, vm.getXMLDesc(0)));
        throw new CloudRuntimeException(errorMsg);
    }

    /**
     * Manually deletes the unused snapshot file.<br/>
     * This method is necessary due to Libvirt created the tag '--delete' on command 'virsh blockcommit' on version <b>1.2.9</b>, however it was only implemented on version
     *  <b>6.0.0</b>.
     * @param snapshotPath The unused snapshot file to manually delete.
     */
    protected void manuallyDeleteUnusedSnapshotFile(boolean isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit, String snapshotPath) {
        if (isLibvirtSupportingFlagDeleteOnCommandVirshBlockcommit) {
            logger.debug(String.format("The current Libvirt's version supports the flag '--delete' on command 'virsh blockcommit', we will skip the manually deletion of the"
                    + " unused snapshot file [%s] as it already was automatically deleted.", snapshotPath));
            return;
        }

        logger.debug(String.format("The current Libvirt's version does not supports the flag '--delete' on command 'virsh blockcommit', therefore we will manually delete the"
                + " unused snapshot file [%s].", snapshotPath));

        try {
            Files.deleteIfExists(Paths.get(snapshotPath));
            logger.debug(String.format("Manually deleted unused snapshot file [%s].", snapshotPath));
        } catch (IOException ex) {
            throw new CloudRuntimeException(String.format("Unable to manually delete unused snapshot file [%s] due to [%s].", snapshotPath, ex.getMessage()));
        }
    }

    /**
     * Creates the snapshot directory in the primary storage, if it does not exist; then, converts the base file (VM's old writing file) to the snapshot directory.
     * @param primaryPool Storage to create folder, if not exists;
     * @param baseFile Base file of VM, which will be converted;
     * @param snapshotPath Path to convert the base file;
     * @return null if the conversion occurs successfully or an error message that must be handled.
     */
    protected String convertBaseFileToSnapshotFileInPrimaryStorageDir(KVMStoragePool primaryPool,
            KVMPhysicalDisk baseFile, String snapshotPath, VolumeObjectTO volume, int wait) {
        try (KeyFile srcKey = new KeyFile(volume.getPassphrase())) {
            logger.debug(
                    String.format("Trying to convert volume [%s] (%s) to snapshot [%s].", volume, baseFile, snapshotPath));

            primaryPool.createFolder(TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR);
            convertTheBaseFileToSnapshot(baseFile, snapshotPath, wait, srcKey);
        } catch (QemuImgException | LibvirtException | IOException ex) {
            return String.format("Failed to convert %s snapshot of volume [%s] to [%s] due to [%s].", volume, baseFile,
                    snapshotPath, ex.getMessage());
        }

        logger.debug(String.format("Converted volume [%s] (from path \"%s\") to snapshot [%s].", volume, baseFile,
                snapshotPath));
        return null;
    }

    private void convertTheBaseFileToSnapshot(KVMPhysicalDisk baseFile, String snapshotPath, int wait, KeyFile srcKey)
            throws LibvirtException, QemuImgException {
        List<QemuObject> qemuObjects = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        QemuImageOptions qemuImageOpts = new QemuImageOptions(baseFile.getPath());
        if (srcKey.isSet()) {
            String srcKeyName = "sec0";
            qemuObjects.add(QemuObject.prepareSecretForQemuImg(baseFile.getFormat(), EncryptFormat.LUKS,
                    srcKey.toString(), srcKeyName, options));
            qemuImageOpts = new QemuImageOptions(baseFile.getFormat(), baseFile.getPath(), srcKeyName);
        }
        QemuImgFile srcFile = new QemuImgFile(baseFile.getPath());
        srcFile.setFormat(PhysicalDiskFormat.QCOW2);

        QemuImgFile destFile = new QemuImgFile(snapshotPath);
        destFile.setFormat(PhysicalDiskFormat.QCOW2);
        QemuImg q = new QemuImg(wait);
        q.convert(srcFile, destFile, options, qemuObjects, qemuImageOpts, null, true);
    }

    /**
     * Retrieves the path of the snapshot on primary storage snapshot's dir.
     * @param primaryStoragePath Path of the primary storage;
     * @param snapshotName Snapshot name;
     * @return the path of the snapshot in primary storage snapshot's dir.
     */
    protected String getSnapshotPathInPrimaryStorage(String primaryStoragePath, String snapshotName) {
        return String.format("%s%s%s%s%s", primaryStoragePath, File.separator, TemplateConstants.DEFAULT_SNAPSHOT_ROOT_DIR, File.separator, snapshotName);
    }

    protected String getSnapshotPathInPrimaryStorageGFS(String primaryStoragePath, String snapshotName) {
        return String.format("%s%s%s", primaryStoragePath, File.separator, snapshotName);
    }

    /**
     * Take a volume snapshot of the specified volume.
     * @param disks List of VM's disks;
     * @param snapshotName Name of the snapshot;
     * @param diskPath Path of the disk to take snapshot;
     * @param vm VM in which disk stay;
     * @return the disk label in VM's XML.
     * @throws LibvirtException
     */
    protected String takeVolumeSnapshot(List<DiskDef> disks, String snapshotName, String diskPath, Domain vm) throws LibvirtException{
        Pair<String, Set<String>> diskToSnapshotAndDisksToAvoid = getDiskToSnapshotAndDisksToAvoid(disks, diskPath, vm);
        String diskLabelToSnapshot = diskToSnapshotAndDisksToAvoid.first();
        String disksToAvoidsOnSnapshot = diskToSnapshotAndDisksToAvoid.second().stream().map(diskLabel -> String.format(TAG_AVOID_DISK_FROM_SNAPSHOT, diskLabel))
          .collect(Collectors.joining());
        String snapshotTemporaryPath = getSnapshotTemporaryPath(diskPath, snapshotName);

        String createSnapshotXmlFormated = String.format(XML_CREATE_DISK_SNAPSHOT, snapshotName, diskLabelToSnapshot, snapshotTemporaryPath, disksToAvoidsOnSnapshot);

        long start = System.currentTimeMillis();
        vm.snapshotCreateXML(createSnapshotXmlFormated, VIR_DOMAIN_SNAPSHOT_CREATE_DISK_ONLY);
        logger.debug(String.format("Snapshot [%s] took [%s] seconds to finish.", snapshotName, (System.currentTimeMillis() - start)/1000));

        return diskLabelToSnapshot;
    }

    /**
     * Retrieves the disk label to take snapshot and, in case that there is more than one disk attached to VM, the disk labels to avoid the snapshot;
     * @param disks List of VM's disks;
     * @param diskPath Path of the disk to take snapshot;
     * @param vm VM in which disks stay;
     * @return the label to take snapshot and the labels to avoid it. If the disk path not be found in VM's XML or be found more than once, it will throw a CloudRuntimeException.
     * @throws org.libvirt.LibvirtException
     */
    protected Pair<String, Set<String>> getDiskToSnapshotAndDisksToAvoid(List<DiskDef> disks, String diskPath, Domain vm) throws LibvirtException {
        String diskLabelToSnapshot = null;
        Set<String> disksToAvoid = new HashSet<>();

        for (DiskDef disk : disks) {
            String diskDefPath = disk.getDiskPath();

            if (StringUtils.isEmpty(diskDefPath)) {
                continue;
            }

            String diskLabel = disk.getDiskLabel();

            if (!diskPath.equals(diskDefPath)) {
                disksToAvoid.add(diskLabel);
                continue;
            }

            if (diskLabelToSnapshot != null) {
                throw new CloudRuntimeException(String.format("VM [%s] has more than one disk with path [%s]. VM's XML [%s].", vm.getName(), diskPath, vm.getXMLDesc(0)));
            }

            diskLabelToSnapshot = diskLabel;
        }

        if (diskLabelToSnapshot == null) {
            throw new CloudRuntimeException(String.format("VM [%s] has no disk with path [%s]. VM's XML [%s].", vm.getName(), diskPath, vm.getXMLDesc(0)));
        }

        return new Pair<>(diskLabelToSnapshot, disksToAvoid);
    }

    /**
     * Retrieves the temporary path of the snapshot.
     * @param diskPath Path of the disk to snapshot;
     * @param snapshotName Snapshot name;
     * @return the path of the disk replacing the disk with the snapshot.
     */
    protected String getSnapshotTemporaryPath(String diskPath, String snapshotName) {
        String[] diskPathSplitted = diskPath.split(File.separator);
        diskPathSplitted[diskPathSplitted.length - 1] = snapshotName;
        return String.join(File.separator, diskPathSplitted);
    }

    /**
     * Validate if the primary storage has enough capacity to take a disk snapshot, as the snapshot will duplicate the disk to backup.
     * @param primaryPool Primary storage to verify capacity;
     * @param disk Disk that will be snapshotted.
     */
    protected void validateAvailableSizeOnPoolToTakeVolumeSnapshot(KVMStoragePool primaryPool, KVMPhysicalDisk disk) {
        long availablePoolSize = primaryPool.getAvailable();
        String poolDescription = new ToStringBuilder(primaryPool, ToStringStyle.JSON_STYLE).append("uuid", primaryPool.getUuid()).append("localPath", primaryPool.getLocalPath())
                .toString();
        String diskDescription = new ToStringBuilder(disk, ToStringStyle.JSON_STYLE).append("name", disk.getName()).append("path", disk.getPath()).append("size", disk.getSize())
                .toString();

        if (isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(availablePoolSize, disk.getSize())) {
            throw new CloudRuntimeException(String.format("Pool [%s] available size [%s] must be at least once more of disk [%s] size, plus 5%%. Not taking snapshot.", poolDescription, availablePoolSize,
                diskDescription));
        }

        logger.debug(String.format("Pool [%s] has enough available size [%s] to take volume [%s] snapshot.", poolDescription, availablePoolSize, diskDescription));
    }

    protected boolean isAvailablePoolSizeDividedByDiskSizeLesserThanMinRate(long availablePoolSize, long diskSize) {
        return ((availablePoolSize * 1d) / (diskSize * 1d)) < MIN_RATE_BETWEEN_AVAILABLE_POOL_AND_DISK_SIZE_TO_TAKE_DISK_SNAPSHOT;
    }

    private Rados radosConnect(final KVMStoragePool primaryPool) throws RadosException {
        Rados r = new Rados(primaryPool.getAuthUserName());
        r.confSet(CEPH_MON_HOST, primaryPool.getSourceHost() + ":" + primaryPool.getSourcePort());
        r.confSet(CEPH_AUTH_KEY, primaryPool.getAuthSecret());
        r.confSet(CEPH_CLIENT_MOUNT_TIMEOUT, CEPH_DEFAULT_MOUNT_TIMEOUT);
        r.connect();
        logger.debug("Successfully connected to Ceph cluster at " + r.confGet(CEPH_MON_HOST));
        return r;
    }

    @Override
    public Answer deleteVolume(final DeleteCommand cmd) {
        final VolumeObjectTO vol = (VolumeObjectTO)cmd.getData();
        final PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO)vol.getDataStore();
        try {
            final KVMStoragePool pool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            try {
                pool.getPhysicalDisk(vol.getPath());
            } catch (final Exception e) {
                logger.debug(String.format("can't find volume: %s, return true", vol));
                return new Answer(null);
            }
            pool.deletePhysicalDisk(vol.getPath(), vol.getFormat());
            return new Answer(null);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to delete volume: ", e);
            return new Answer(null, false, e.toString());
        } finally {
            vol.clearPassphrase();
        }
    }

    @Override
    public Answer createVolumeFromSnapshot(final CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        final VolumeObjectTO volume = snapshot.getVolume();
        try {
            final DataTO destData = cmd.getDestTO();
            final PrimaryDataStoreTO pool = (PrimaryDataStoreTO)destData.getDataStore();
            final DataStoreTO imageStore = srcData.getDataStore();

            if (!(imageStore instanceof NfsTO || imageStore instanceof PrimaryDataStoreTO)) {
                return new CopyCmdAnswer("unsupported protocol");
            }

            final String snapshotFullPath = snapshot.getPath();
            final int index = snapshotFullPath.lastIndexOf("/");
            final String snapshotPath = snapshotFullPath.substring(0, index);
            final String snapshotName = snapshotFullPath.substring(index + 1);
            KVMPhysicalDisk disk = null;
            if (imageStore instanceof NfsTO) {
                disk = createVolumeFromSnapshotOnNFS(cmd, pool, imageStore, volume, snapshotPath, snapshotName);
            } else if (srcData.getDataStore().getRole() == DataStoreRole.Primary && pool.getPoolType() == StoragePoolType.RBD) {
                disk = createVolumeFromRBDSnapshot(cmd, destData, pool, imageStore, volume, snapshotName, disk);
            } else if (srcData.getDataStore().getRole() == DataStoreRole.Primary && pool.getPoolType() == StoragePoolType.SharedMountPoint){
                disk = createVolumeFromSnapshotOnSharedMountPoint(cmd, destData, pool, imageStore, volume, snapshotPath, snapshotName);
            }
            if (disk == null) {
                return new CopyCmdAnswer("Could not create volume from snapshot");
            }
            final VolumeObjectTO newVol = new VolumeObjectTO();
            newVol.setPath(disk.getName());
            newVol.setSize(disk.getVirtualSize());
            newVol.setFormat(ImageFormat.valueOf(disk.getFormat().toString().toUpperCase()));

            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to createVolumeFromSnapshot: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            volume.clearPassphrase();
        }
    }

    private List<StoragePoolType> storagePoolTypesToDeleteSnapshotFile = Arrays.asList(StoragePoolType.Filesystem, StoragePoolType.NetworkFilesystem,
            StoragePoolType.SharedMountPoint);

    private KVMPhysicalDisk createVolumeFromRBDSnapshot(CopyCommand cmd, DataTO destData,
            PrimaryDataStoreTO pool, DataStoreTO imageStore, VolumeObjectTO volume, String snapshotName, KVMPhysicalDisk disk) {
        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) imageStore;
        KVMStoragePool srcPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
        KVMPhysicalDisk snapshotDisk = srcPool.getPhysicalDisk(volume.getPath());
        KVMStoragePool destPool = storagePoolMgr.getStoragePool(pool.getPoolType(), pool.getUuid());
        VolumeObjectTO newVol = (VolumeObjectTO) destData;

        if (StoragePoolType.RBD.equals(primaryStore.getPoolType())) {
            logger.debug(String.format("Attempting to create volume from RBD snapshot %s", snapshotName));
            if (StoragePoolType.RBD.equals(pool.getPoolType())) {
                disk = createRBDvolumeFromRBDSnapshot(snapshotDisk, snapshotName, newVol.getUuid(),
                        PhysicalDiskFormat.RAW, newVol.getSize(), destPool, cmd.getWaitInMillSeconds());
                logger.debug(String.format("Created RBD volume %s from snapshot %s", disk, snapshotDisk));
            } else {
                Map<String, String> details = cmd.getOptions2();

                String path = details != null ? details.get(DiskTO.IQN) : null;

                storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path, details);

                snapshotDisk.setPath(snapshotDisk.getPath() + "@" + snapshotName);
                disk = storagePoolMgr.copyPhysicalDisk(snapshotDisk, path != null ? path : newVol.getUuid(),
                        destPool, cmd.getWaitInMillSeconds());

                storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path);
                logger.debug(String.format("Created RBD volume %s from snapshot %s", disk, snapshotDisk));

            }
        }
        return disk;
    }

    private KVMPhysicalDisk createVolumeFromSnapshotOnNFS(CopyCommand cmd, PrimaryDataStoreTO pool,
            DataStoreTO imageStore, VolumeObjectTO volume, String snapshotPath, String snapshotName) {
        NfsTO nfsImageStore = (NfsTO)imageStore;
        KVMStoragePool secondaryPool = storagePoolMgr.getStoragePoolByURI(nfsImageStore.getUrl() + File.separator + snapshotPath);
        KVMPhysicalDisk snapshotDisk = secondaryPool.getPhysicalDisk(snapshotName);
        if (volume.getFormat() == ImageFormat.RAW) {
            snapshotDisk.setFormat(PhysicalDiskFormat.RAW);
        } else if (volume.getFormat() == ImageFormat.QCOW2) {
            snapshotDisk.setFormat(PhysicalDiskFormat.QCOW2);
        }

        final String primaryUuid = pool.getUuid();
        final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(pool.getPoolType(), primaryUuid);
        final String volUuid = UUID.randomUUID().toString();

        Map<String, String> details = cmd.getOptions2();

        String path = cmd.getDestTO().getPath();
        if (path == null) {
            path = details != null ? details.get(DiskTO.PATH) : null;
            if (path == null) {
                path = details != null ? details.get(DiskTO.IQN) : null;
                if (path == null) {
                    new CloudRuntimeException("The 'path' or 'iqn' field must be specified.");
                }
            }
        }

        storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path, details);

        KVMPhysicalDisk disk = storagePoolMgr.copyPhysicalDisk(snapshotDisk, path != null ? path : volUuid, primaryPool, cmd.getWaitInMillSeconds());

        storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path);
        return disk;
    }

    private KVMPhysicalDisk createVolumeFromSnapshotOnSharedMountPoint(CopyCommand cmd, DataTO destData, PrimaryDataStoreTO pool,
            DataStoreTO imageStore, VolumeObjectTO volume, String snapshotPath, String snapshotName) {

        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) imageStore;
        KVMStoragePool srcPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());

        KVMPhysicalDisk snapshotDisk = srcPool.getPhysicalDisk(snapshotName);
        VolumeObjectTO newVol = (VolumeObjectTO) destData;

        final KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(pool.getPoolType(), pool.getUuid());

        if (volume.getFormat() == ImageFormat.QCOW2) {
            snapshotDisk.setFormat(PhysicalDiskFormat.QCOW2);
        }

        Map<String, String> details = cmd.getOptions2();

        String path = cmd.getDestTO().getPath();
        if (path == null) {
            path = details != null ? details.get(DiskTO.PATH) : null;
            if (path == null) {
                path = details != null ? details.get(DiskTO.IQN) : null;
                if (path == null) {
                    new CloudRuntimeException("The 'path' or 'iqn' field must be specified.");
                }
            }
        }

        storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path, details);

        KVMPhysicalDisk newDisk = storagePoolMgr.copyPhysicalDisk(snapshotDisk, path != null ? path : newVol.getUuid(), primaryPool, cmd.getWaitInMillSeconds());

        storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), path);
        return newDisk;
    }

    private KVMPhysicalDisk createRBDvolumeFromRBDSnapshot(KVMPhysicalDisk volume, String snapshotName, String name,
            PhysicalDiskFormat format, long size, KVMStoragePool destPool, int timeout) {

        KVMStoragePool srcPool = volume.getPool();
        KVMPhysicalDisk disk = null;
        String newUuid = name;

        disk = new KVMPhysicalDisk(destPool.getSourceDir() + "/" + newUuid, newUuid, destPool);
        disk.setFormat(format);
        disk.setSize(size > volume.getVirtualSize() ? size : volume.getVirtualSize());
        disk.setVirtualSize(size > volume.getVirtualSize() ? size : disk.getSize());

        try {

            Rados r = new Rados(srcPool.getAuthUserName());
            r.confSet("mon_host", srcPool.getSourceHost() + ":" + srcPool.getSourcePort());
            r.confSet("key", srcPool.getAuthSecret());
            r.confSet("client_mount_timeout", "30");
            r.connect();

            IoCTX io = r.ioCtxCreate(srcPool.getSourceDir());
            Rbd rbd = new Rbd(io);
            RbdImage srcImage = rbd.open(volume.getName());

            List<RbdSnapInfo> snaps = srcImage.snapList();
            boolean snapFound = false;
            for (RbdSnapInfo snap : snaps) {
                if (snapshotName.equals(snap.name)) {
                    snapFound = true;
                    break;
                }
            }

            if (!snapFound) {
                logger.debug(String.format("Could not find snapshot %s on RBD", snapshotName));
                return null;
            }
            if(!srcImage.snapIsProtected(snapshotName)){
                srcImage.snapProtect(snapshotName);
            }

            logger.debug(String.format("Try to clone snapshot %s on RBD", snapshotName));
            rbd.clone(volume.getName(), snapshotName, io, disk.getName(), LibvirtStorageAdaptor.RBD_FEATURES, 0);
            RbdImage diskImage = rbd.open(disk.getName());
            if (disk.getVirtualSize() > volume.getVirtualSize()) {
                diskImage.resize(disk.getVirtualSize());
            }

            // 스냅샷으로 볼륨 생성시 linked clone형식으로 변경
            // diskImage.flatten();
            rbd.close(diskImage);

            // srcImage.snapUnprotect(snapshotName);
            rbd.close(srcImage);
            r.ioCtxDestroy(io);
        } catch (RadosException | RbdException e) {
            logger.error(String.format("Failed due to %s", e.getMessage()), e);
            disk = null;
        }

        return disk;
    }

    @Override
    public Answer flattenFromRBDSnapshot(final FlattenCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final SnapshotObjectTO snapshot = (SnapshotObjectTO)srcData;
        final VolumeObjectTO volume = snapshot.getVolume();
        Integer cloneImageCount = 0;
        final DataStoreTO imageStore = srcData.getDataStore();
        final String snapshotFullPath = snapshot.getPath();
        final int index = snapshotFullPath.lastIndexOf("/");
        final String snapshotName = snapshotFullPath.substring(index + 1);
        PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) imageStore;

        KVMStoragePool srcPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
        KVMPhysicalDisk disk = new KVMPhysicalDisk(srcPool.getSourceDir() + "/" + volume.getUuid(), volume.getUuid(), srcPool);
        try {
            Rados r = new Rados(srcPool.getAuthUserName());
            r.confSet("mon_host", srcPool.getSourceHost() + ":" + srcPool.getSourcePort());
            r.confSet("key", srcPool.getAuthSecret());
            r.confSet("client_mount_timeout", "30");
            r.connect();

            IoCTX io = r.ioCtxCreate(srcPool.getSourceDir());
            Rbd rbd = new Rbd(io);
            RbdImage srcImage = rbd.open(disk.getName());

            List<RbdSnapInfo> snaps = srcImage.snapList();
            boolean snapFound = false;
            for (RbdSnapInfo snap : snaps) {
                if (snapshotName.equals(snap.name)) {
                    snapFound = true;
                    break;
                }
            }
            if (!snapFound) {
                logger.debug(String.format("[Flatten Command] Could not find snapshot %s on RBD", snapshotName));
                return new FlattenCmdAnswer(srcData, cmd, true, "Could not find snapshot");
            }

            List<String> listChildren = srcImage.listChildren(snapshotName);
            cloneImageCount = listChildren.size();
            logger.debug(String.format("[Flatten Command] The number of images to clone is [%s]", cloneImageCount));
            if(cloneImageCount > 0) {
                String[] volumeToFlatten = listChildren.get(0).split(File.separator);
                RbdImage diskImage = rbd.open(volumeToFlatten[1]);

                logger.debug(String.format("[Flatten Command] Try to flatten image %s on RBD", diskImage));
                diskImage.flatten();
            }
            boolean finalSuccess = false;
            if (cloneImageCount > 1) {
                finalSuccess = false;
            } else {
                finalSuccess = true;
                if (cloneImageCount == 1) {
                    logger.debug(String.format("[Flatten Command] Try to unprotect and remove snapshot %s on RBD", snapshotName));
                    srcImage.snapUnprotect(snapshotName);
                    srcImage.snapRemove(snapshotName);
                }
            }
            rbd.close(srcImage);
            r.ioCtxDestroy(io);
            return new FlattenCmdAnswer(srcData, cmd, finalSuccess, Integer.toString(cloneImageCount));

        } catch (final CloudRuntimeException | RadosException | RbdException e) {
            logger.debug("Failed to Flatten Rbd Volume From Snapshot: ", e);
            return new FlattenCmdAnswer(srcData, cmd, false, e.toString());
        }
    }

    @Override
    public Answer deleteSnapshot(final DeleteCommand cmd) {
        String snapshotFullName = "";
        SnapshotObjectTO snapshotTO = (SnapshotObjectTO) cmd.getData();
        VolumeObjectTO volume = snapshotTO.getVolume();
        try {
            PrimaryDataStoreTO primaryStore = (PrimaryDataStoreTO) snapshotTO.getDataStore();
            KVMStoragePool primaryPool = storagePoolMgr.getStoragePool(primaryStore.getPoolType(), primaryStore.getUuid());
            String snapshotFullPath = snapshotTO.getPath();
            String snapshotName = snapshotFullPath.substring(snapshotFullPath.lastIndexOf("/") + 1);
            snapshotFullName = snapshotName;
            if (primaryPool.getType() == StoragePoolType.RBD) {
                KVMPhysicalDisk disk = storagePoolMgr.getPhysicalDisk(primaryStore.getPoolType(), primaryStore.getUuid(), volume.getPath());
                snapshotFullName = disk.getName() + "@" + snapshotName;
                Rados r = radosConnect(primaryPool);
                IoCTX io = r.ioCtxCreate(primaryPool.getSourceDir());
                Rbd rbd = new Rbd(io);
                RbdImage image = rbd.open(disk.getName());
                try {
                    logger.info("Attempting to remove RBD snapshot " + snapshotFullName);
                    if (image.snapIsProtected(snapshotName)) {
                        logger.debug("Unprotecting RBD snapshot " + snapshotFullName);
                        image.snapUnprotect(snapshotName);
                    }
                    image.snapRemove(snapshotName);
                    logger.info("Snapshot " + snapshotFullName + " successfully removed from " +
                            primaryPool.getType().toString() + "  pool.");
                } catch (RbdException e) {
                    logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                        ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
                } finally {
                    rbd.close(image);
                    r.ioCtxDestroy(io);
                }

            } else if (storagePoolTypesToDeleteSnapshotFile.contains(primaryPool.getType())) {
                logger.info(String.format("Deleting snapshot (id=%s, name=%s, path=%s, storage type=%s) on primary storage", snapshotTO.getId(), snapshotTO.getName(),
                        snapshotTO.getPath(), primaryPool.getType()));
                deleteSnapshotFile(snapshotTO);
            } else {
                logger.warn("Operation not implemented for storage pool type of " + primaryPool.getType().toString());
                throw new InternalErrorException("Operation not implemented for storage pool type of " + primaryPool.getType().toString());
            }
            return new Answer(cmd, true, "Snapshot " + snapshotFullName + " removed successfully.");
        } catch (RadosException e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } catch (RbdException e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString() +
                ", RBD error: " + ErrorCode.getErrorMessage(e.getReturnValue()));
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } catch (Exception e) {
            logger.error("Failed to remove snapshot " + snapshotFullName + ", with exception: " + e.toString());
            return new Answer(cmd, false, "Failed to remove snapshot " + snapshotFullName);
        } finally {
            volume.clearPassphrase();
        }
    }

    /**
     * Deletes the snapshot's file.
     * @throws CloudRuntimeException If can't delete the snapshot file.
     */
    protected void deleteSnapshotFile(SnapshotObjectTO snapshotObjectTo) throws CloudRuntimeException {
        try {
            Files.deleteIfExists(Paths.get(snapshotObjectTo.getPath()));
            logger.debug(String.format("Deleted snapshot [%s].", snapshotObjectTo));
        } catch (IOException ex) {
            throw new CloudRuntimeException(String.format("Unable to delete snapshot [%s] due to [%s].", snapshotObjectTo, ex.getMessage()));
        }
    }

    @Override
    public Answer introduceObject(final IntroduceObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    @Override
    public Answer forgetObject(final ForgetObjectCmd cmd) {
        return new Answer(cmd, false, "not implememented yet");
    }

    @Override
    public Answer handleDownloadTemplateToPrimaryStorage(DirectDownloadCommand cmd) {
        final PrimaryDataStoreTO pool = cmd.getDestPool();
        DirectTemplateDownloader downloader;
        KVMPhysicalDisk template;
        KVMStoragePool destPool = null;

        try {
            logger.debug("Verifying temporary location for downloading the template exists on the host");
            String temporaryDownloadPath = resource.getDirectDownloadTemporaryDownloadPath();
            if (!isLocationAccessible(temporaryDownloadPath)) {
                String msg = "The temporary location path for downloading templates does not exist: " +
                        temporaryDownloadPath + " on this host";
                logger.error(msg);
                return new DirectDownloadAnswer(false, msg, true);
            }

            Long templateSize = null;
            if (StringUtils.isNotBlank(cmd.getUrl())) {
                String url = cmd.getUrl();
                templateSize = UriUtils.getRemoteSize(url, cmd.isFollowRedirects());
            }

            logger.debug("Checking for free space on the host for downloading the template with physical size: " + templateSize + " and virtual size: " + cmd.getTemplateSize());
            if (!isEnoughSpaceForDownloadTemplateOnTemporaryLocation(templateSize)) {
                String msg = String.format("Not enough space on the defined temporary location to download the template %s with id %d", cmd.getDestData(), cmd.getTemplateId());
                logger.error(msg);
                return new DirectDownloadAnswer(false, msg, true);
            }

            destPool = storagePoolMgr.getStoragePool(pool.getPoolType(), pool.getUuid());
            downloader = DirectDownloadHelper.getDirectTemplateDownloaderFromCommand(cmd, destPool.getLocalPath(), temporaryDownloadPath);
            logger.debug("Trying to download template");
            Pair<Boolean, String> result = downloader.downloadTemplate();
            if (!result.first()) {
                logger.warn("Couldn't download template");
                return new DirectDownloadAnswer(false, "Unable to download template", true);
            }
            String tempFilePath = result.second();
            if (!downloader.validateChecksum()) {
                logger.warn("Couldn't validate template checksum");
                return new DirectDownloadAnswer(false, "Checksum validation failed", false);
            }

            final TemplateObjectTO destTemplate = cmd.getDestData();
            String destTemplatePath = (destTemplate != null) ? destTemplate.getPath() : null;

            if (!storagePoolMgr.connectPhysicalDisk(pool.getPoolType(), pool.getUuid(), destTemplatePath, null)) {
                logger.warn(String.format("Unable to connect physical disk at path: %s, in storage pool [id: %d, uuid: %s, name: %s, path: %s]",
                        destTemplatePath, pool.getId(), pool.getUuid(), pool.getName(), pool.getPath()));
            }

            template = storagePoolMgr.createPhysicalDiskFromDirectDownloadTemplate(tempFilePath, destTemplatePath, destPool, cmd.getFormat(), cmd.getWaitInMillSeconds());

            String templatePath = template.getPath();
            if (templatePath != null) {
                try {
                    Qcow2Inspector.validateQcow2File(templatePath);
                } catch (RuntimeException e) {
                    try {
                        Files.deleteIfExists(Path.of(templatePath));
                    } catch (IOException ioException) {
                        logger.warn("Unable to remove file [name: {}, path: {}]; consider removing it manually.", template.getName(), templatePath, ioException);
                    }

                    logger.error("The downloaded file [{}] is not a valid QCOW2.", templatePath, e);
                    return new DirectDownloadAnswer(false, "The downloaded file is not a valid QCOW2. Ask the administrator to check the logs for more details.", true);
                }
            }

            if (!storagePoolMgr.disconnectPhysicalDisk(pool.getPoolType(), pool.getUuid(), destTemplatePath)) {
                logger.warn(String.format("Unable to disconnect physical disk at path: %s, in storage pool [id: %d, uuid: %s, name: %s, path: %s]", destTemplatePath, pool.getId(), pool.getUuid(), pool.getName(), pool.getUuid()));
            }
        } catch (CloudRuntimeException e) {
            logger.warn(String.format("Error downloading template %s with id %d due to: %s", cmd.getDestData(), cmd.getTemplateId(), e.getMessage()));
            return new DirectDownloadAnswer(false, "Unable to download template: " + e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            return new DirectDownloadAnswer(false, "Unable to create direct downloader: " + e.getMessage(), true);
        }

        return new DirectDownloadAnswer(true, template.getSize(), template.getName());
    }

    @Override
    public Answer copyVolumeFromPrimaryToPrimary(CopyCommand cmd) {
        final DataTO srcData = cmd.getSrcTO();
        final DataTO destData = cmd.getDestTO();
        final VolumeObjectTO srcVol = (VolumeObjectTO)srcData;
        final VolumeObjectTO destVol = (VolumeObjectTO)destData;
        final ImageFormat srcFormat = srcVol.getFormat();
        final ImageFormat destFormat = destVol.getFormat();
        final DataStoreTO srcStore = srcData.getDataStore();
        final DataStoreTO destStore = destData.getDataStore();
        final PrimaryDataStoreTO srcPrimaryStore = (PrimaryDataStoreTO)srcStore;
        final PrimaryDataStoreTO destPrimaryStore = (PrimaryDataStoreTO)destStore;
        final String srcVolumePath = srcData.getPath();
        final String destVolumePath = destData.getPath();
        KVMStoragePool destPool = null;

        try {
            logger.debug(String.format("Copying src volume (id: %d, uuid: %s, name: %s, format:" +
                            " %s, path: %s, primary storage: [id: %d, uuid: %s, name: %s, type: " +
                            "%s]) to dest volume (id: %d, uuid: %s, name: %s, format: %s, path: " +
                            "%s, primary storage: [id: %d, uuid: %s, name: %s, type: %s]).",
                    srcVol.getId(), srcVol.getUuid(), srcVol.getName(), srcFormat, srcVolumePath,
                    srcPrimaryStore.getId(), srcPrimaryStore.getUuid(), srcPrimaryStore.getName(),
                    srcPrimaryStore.getPoolType(), destVol.getId(), destVol.getUuid(), destVol.getName(),
                    destFormat, destVolumePath, destPrimaryStore.getId(), destPrimaryStore.getUuid(),
                    destPrimaryStore.getName(), destPrimaryStore.getPoolType()));

            if (srcPrimaryStore.isManaged()) {
                if (!storagePoolMgr.connectPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath, srcPrimaryStore.getDetails())) {
                    logger.warn(String.format("Failed to connect src volume %s, in storage pool %s", srcVol, srcPrimaryStore));
                }
            }

            final KVMPhysicalDisk volume = storagePoolMgr.getPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath);
            if (volume == null) {
                logger.debug("Failed to get physical disk for volume: " + srcVol);
                throw new CloudRuntimeException("Failed to get physical disk for volume at path: " + srcVolumePath);
            }

            volume.setFormat(PhysicalDiskFormat.valueOf(srcFormat.toString()));
            volume.setDispName(srcVol.getName());
            volume.setVmName(srcVol.getVmName());

            String destVolumeName = null;
            if (destPrimaryStore.isManaged()) {
                if (!storagePoolMgr.connectPhysicalDisk(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid(), destVolumePath, destPrimaryStore.getDetails())) {
                    logger.warn("Failed to connect dest volume {}, in storage pool {}", destVol, destPrimaryStore);
                }
                destVolumeName = derivePath(destPrimaryStore, destData, destPrimaryStore.getDetails());
            } else {
                final String volumeName = UUID.randomUUID().toString();
                destVolumeName = volumeName + "." + destFormat.getFileExtension();
            }

            destPool = storagePoolMgr.getStoragePool(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid());
            try {
                Volume.Type volumeType = srcVol.getVolumeType();

                if (srcVol.getPassphrase() != null && (Volume.Type.ROOT.equals(volumeType) || Volume.Type.DATADISK.equals(volumeType))) {
                    volume.setQemuEncryptFormat(QemuObject.EncryptFormat.LUKS);
                    storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, destPool, cmd.getWaitInMillSeconds(), srcVol.getPassphrase(), destVol.getPassphrase(), srcVol.getProvisioningType());
                } else {
                    storagePoolMgr.copyPhysicalDisk(volume, destVolumeName, destPool, cmd.getWaitInMillSeconds());
                }
            } catch (Exception e) { // Any exceptions while copying the disk, should send failed answer with the error message
                String errMsg = String.format("Failed to copy volume [uuid: %s, name: %s] to dest storage [id: %s, name: %s], due to %s",
                        srcVol.getUuid(), srcVol.getName(), destPrimaryStore.getUuid(), destPrimaryStore.getName(), e.toString());
                logger.debug(errMsg, e);
                throw new CloudRuntimeException(errMsg);
            } finally {
                if (srcPrimaryStore.isManaged()) {
                    storagePoolMgr.disconnectPhysicalDisk(srcPrimaryStore.getPoolType(), srcPrimaryStore.getUuid(), srcVolumePath);
                }

                if (destPrimaryStore.isManaged()) {
                    storagePoolMgr.disconnectPhysicalDisk(destPrimaryStore.getPoolType(), destPrimaryStore.getUuid(), destVolumePath);
                }
            }

            final VolumeObjectTO newVol = new VolumeObjectTO();
            String path = destPrimaryStore.isManaged() ? destVolumeName : destVolumePath + File.separator + destVolumeName;
            newVol.setPath(path);
            newVol.setFormat(destFormat);
            newVol.setEncryptFormat(destVol.getEncryptFormat());
            return new CopyCmdAnswer(newVol);
        } catch (final CloudRuntimeException e) {
            logger.debug("Failed to copyVolumeFromPrimaryToPrimary: ", e);
            return new CopyCmdAnswer(e.toString());
        } finally {
            srcVol.clearPassphrase();
            destVol.clearPassphrase();
        }
    }

    /**
     * True if location exists
     */
    private boolean isLocationAccessible(String temporaryDownloadPath) {
        File dir = new File(temporaryDownloadPath);
        return dir.exists();
    }

    /**
     * Perform a free space check on the host for downloading the direct download templates
     * @param templateSize template size obtained from remote server when registering the template (in bytes)
     */
    protected boolean isEnoughSpaceForDownloadTemplateOnTemporaryLocation(Long templateSize) {
        if (templateSize == null || templateSize == 0L) {
            logger.info("The server did not provide the template size, assuming there is enough space to download it");
            return true;
        }
        String cmd = String.format("df --output=avail %s -B 1 | tail -1", resource.getDirectDownloadTemporaryDownloadPath());
        String resultInBytes = Script.runSimpleBashScript(cmd);
        Long availableBytes;
        try {
            availableBytes = Long.parseLong(resultInBytes);
        } catch (NumberFormatException e) {
            String msg = "Could not parse the output " + resultInBytes + " as a number, therefore not able to check for free space";
            logger.error(msg, e);
            return false;
        }
        return availableBytes >= templateSize;
    }

    @Override
    public Answer checkDataStoreStoragePolicyCompliance(CheckDataStoreStoragePolicyComplainceCommand cmd) {
        logger.info("'CheckDataStoreStoragePolicyComplainceCommand' not currently applicable for KVMStorageProcessor");
        return new Answer(cmd,false,"Not currently applicable for KVMStorageProcessor");
    }

    @Override
    public Answer syncVolumePath(SyncVolumePathCommand cmd) {
        logger.info("SyncVolumePathCommand not currently applicable for KVMStorageProcessor");
        return new Answer(cmd, false, "Not currently applicable for KVMStorageProcessor");
    }

    /**
     * Determine if migration is using host-local source pool. If so, return this host's storage as the template source,
     * rather than remote host's
     * @param localPool The host-local storage pool being migrated to
     * @param migrationOptions The migration options provided with a migrating volume
     * @return
     */
    public KVMStoragePool getTemplateSourcePoolUsingMigrationOptions(KVMStoragePool localPool, MigrationOptions migrationOptions) {
        if (migrationOptions == null) {
            throw new CloudRuntimeException("Migration options cannot be null when choosing a storage pool for migration");
        }

        if (migrationOptions.getScopeType().equals(ScopeType.HOST)) {
            return localPool;
        }

        return storagePoolMgr.getStoragePool(migrationOptions.getSrcPoolType(), migrationOptions.getSrcPoolUuid());
    }
}
