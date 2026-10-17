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

package org.apache.cloudstack.storage.sharedfs;

import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;

public class SharedFSDeletionPlanTest {
    @Test public void missingPolicyPreservesAndUnknownPolicyIsRejected() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();
        Assert.assertEquals(SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,service.deletionPolicy(null));
        Assert.assertThrows(InvalidParameterValueException.class,()->service.deletionPolicy("delete"));
    }
    @Test public void destructiveRemovalRequiresExactNameAndCurrentHashBeforeAnyPersistence() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();SharedFSDao filesystems=mock(SharedFSDao.class);VolumeDao volumes=mock(VolumeDao.class);
        ReflectionTestUtils.setField(service,"sharedFSDao",filesystems);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        SharedFSVO fs=mock(SharedFSVO.class);when(fs.getVmId()).thenReturn(null);when(fs.getId()).thenReturn(7L);when(fs.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");when(fs.getName()).thenReturn("fixture");when(fs.getAccountId()).thenReturn(2L);when(fs.getDomainId()).thenReturn(3L);when(fs.getDataCenterId()).thenReturn(4L);
        Assert.assertThrows(InvalidParameterValueException.class,()->service.prepareSharedFSDeletion(fs,"DELETE_VOLUMES","wrong",null));
        Assert.assertThrows(InvalidParameterValueException.class,()->service.prepareSharedFSDeletion(fs,"DELETE_VOLUMES","fixture","stale"));
        verify(filesystems,never()).update(anyLong(),any());verify(fs,never()).setDeletionPlanJson(anyString());
    }
    @Test public void hashChangesWithThePlannedVolumeCapacityButNotItsAttachmentState() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        SharedFS fs=mock(SharedFS.class);when(fs.getVmId()).thenReturn(null);when(fs.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");when(fs.getVolumeId()).thenReturn(42L);when(fs.getAccountId()).thenReturn(2L);when(fs.getId()).thenReturn(7L);when(fs.getDomainId()).thenReturn(3L);when(fs.getDataCenterId()).thenReturn(4L);
        VolumeVO volume=mock(VolumeVO.class);when(volumes.findById(42L)).thenReturn(volume);when(volume.getInstanceId()).thenReturn(null);when(volume.getAccountId()).thenReturn(2L);when(volume.getId()).thenReturn(42L);when(volume.getDomainId()).thenReturn(3L);when(volume.getDataCenterId()).thenReturn(4L);when(volume.getPoolId()).thenReturn(9L);when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(volume.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");when(volume.getSize()).thenReturn(100L);when(volume.getPoolId()).thenReturn(null);
        String first=service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES).get("planHash").getAsString();
        when(volume.getSize()).thenReturn(200L);
        Assert.assertNotEquals(first,service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES).get("planHash").getAsString());
    }
    @Test public void activeRuntimeUpgradeBlocksRemovalBeforeTheSuppliedAction() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao upgrades=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class);
        ReflectionTestUtils.setField(service,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(service,"storageRuntimeUpgradeDao",upgrades);
        ReflectionTestUtils.setField(service,"storageTemplateUpgradeDao",mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao.class));
        ReflectionTestUtils.setField(service,"storageOperationDao",mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class));
        ReflectionTestUtils.setField(service,"vmInstanceDao",mock(com.cloud.vm.dao.VMInstanceDao.class));ReflectionTestUtils.setField(service,"volumeDao",mock(VolumeDao.class));
        SharedFS fs=mock(SharedFS.class);when(fs.getVmId()).thenReturn(41L);
        org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO instance=mock(org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(8L);when(instances.findByVmId(41L)).thenReturn(instance);
        when(upgrades.findActiveByInstanceId(8L)).thenReturn(mock(org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeUpgradeVO.class));
        java.util.concurrent.atomic.AtomicBoolean action=new java.util.concurrent.atomic.AtomicBoolean();
        try (org.mockito.MockedStatic<com.cloud.utils.db.GlobalLock> locks=mockStatic(com.cloud.utils.db.GlobalLock.class)) {
            com.cloud.utils.db.GlobalLock lock=mock(com.cloud.utils.db.GlobalLock.class);
            locks.when(()->com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-8")).thenReturn(lock);when(lock.lock(30)).thenReturn(true);
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->service.withSharedFSWriterLock(fs,()->{action.set(true);return true;}));
            Assert.assertFalse(action.get());verify(lock).unlock();verify(lock).releaseRef();
        }
    }

    private SharedFSVO serviceIdentity() {
        SharedFSVO value=new SharedFSVO("fixture","",3L,2L,4L,"provider",SharedFS.Protocol.NFS,SharedFS.FileSystemType.XFS,5L);
        ReflectionTestUtils.setField(value,"id",7L);value.setVolumeId(42L);return value;
    }
    private VolumeVO dataIdentity(long id) {
        VolumeVO volume=mock(VolumeVO.class);when(volume.getId()).thenReturn(id);when(volume.getUuid()).thenReturn("22222222-2222-4222-8222-222222222222");when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);when(volume.getAccountId()).thenReturn(2L);when(volume.getDomainId()).thenReturn(3L);when(volume.getDataCenterId()).thenReturn(4L);when(volume.getPoolId()).thenReturn(9L);when(volume.getSize()).thenReturn(100L);when(volume.getInstanceId()).thenReturn(null);return volume;
    }
    @Test public void approvedDetachAndRemovedVolumeRetryUseIncludingRemovedExactIdentity() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        SharedFSVO fs=serviceIdentity();VolumeVO volume=dataIdentity(42L);when(volumes.findById(42L)).thenReturn(volume);when(volumes.findByIdIncludingRemoved(42L)).thenReturn(volume);
        fs.setDeletionPlanJson(service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.DELETE_VOLUMES).toString());fs.setDataVolumePolicy(SharedFS.DataVolumePolicy.DELETE_VOLUMES);
        when(volumes.findById(42L)).thenReturn(null);when(volume.getState()).thenReturn(Volume.State.Expunged);
        Assert.assertEquals(java.util.Set.of(42L),service.storedDeletionVolumeIds(fs));verify(volumes).findByIdIncludingRemoved(42L);
    }
    @Test public void changedPoolUuidAndMissingRowCannotReplayApprovedCleanup() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        SharedFSVO fs=serviceIdentity();VolumeVO volume=dataIdentity(42L);when(volumes.findById(42L)).thenReturn(volume);when(volumes.findByIdIncludingRemoved(42L)).thenReturn(volume);fs.setDeletionPlanJson(service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES).toString());
        when(volume.getPoolId()).thenReturn(999L);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->service.storedDeletionVolumeIds(fs));
        when(volume.getPoolId()).thenReturn(9L);when(volume.getUuid()).thenReturn("33333333-3333-4333-8333-333333333333");Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->service.storedDeletionVolumeIds(fs));
        when(volumes.findByIdIncludingRemoved(42L)).thenReturn(null);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->service.storedDeletionVolumeIds(fs));
    }
    @Test public void newlyReferencedDataRequiresAnotherReviewedPlan() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        SharedFSVO fs=serviceIdentity();VolumeVO volume=dataIdentity(42L);when(volumes.findById(42L)).thenReturn(volume);fs.setDeletionPlanJson(service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES).toString());fs.setVolumeId(43L);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->service.storedDeletionVolumeIds(fs));verify(volumes,never()).findByIdIncludingRemoved(anyLong());
    }

    @Test public void preservationAlsoRejectsAProvidedStalePreviewHash() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();SharedFSVO fs=serviceIdentity();
        com.google.gson.JsonObject plan=new com.google.gson.JsonObject();plan.addProperty("planHash","reviewed");
        Assert.assertThrows(InvalidParameterValueException.class,()->service.validateDeletionConfirmation(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,null,"stale",plan));
        service.validateDeletionConfirmation(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,null,"reviewed",plan);
        service.validateDeletionConfirmation(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES,null,null,plan);
    }

    @Test public void displayImpactsReuseAuthoritativeRootsAndProtocolReferencesWithoutChangingDataHash() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();SharedFSVO fs=serviceIdentity();fs.setVmId(60L);
        VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares=mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targets=mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        ReflectionTestUtils.setField(service,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(service,"storageFileShareDao",shares);ReflectionTestUtils.setField(service,"storageBlockTargetDao",targets);
        org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO instance=mock(org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO.class);when(instance.getId()).thenReturn(8L);when(instances.findByVmId(60L)).thenReturn(instance);
        VolumeVO a=dataIdentity(42L),b=dataIdentity(43L),root=dataIdentity(44L);
        when(b.getUuid()).thenReturn("33333333-3333-4333-8333-333333333333");when(root.getUuid()).thenReturn("44444444-4444-4444-8444-444444444444");when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(volumes.findById(42L)).thenReturn(a);when(volumes.findById(43L)).thenReturn(b);
        when(volumes.findByInstanceAndType(60L,Volume.Type.DATADISK)).thenReturn(java.util.List.of(a,b));when(volumes.findByInstanceAndType(60L,Volume.Type.ROOT)).thenReturn(java.util.List.of(root));
        org.apache.cloudstack.storage.dataservice.StorageFileShareVO nfs=mock(org.apache.cloudstack.storage.dataservice.StorageFileShareVO.class),smb=mock(org.apache.cloudstack.storage.dataservice.StorageFileShareVO.class);
        when(nfs.getVolumeId()).thenReturn(null);when(smb.getVolumeId()).thenReturn(43L);when(shares.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.List.of(nfs));when(shares.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.SMB)).thenReturn(java.util.List.of(smb));
        org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO iscsi=mock(org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO.class);when(iscsi.getVolumeId()).thenReturn(43L);when(targets.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.ISCSI)).thenReturn(java.util.List.of(iscsi));
        com.google.gson.JsonObject plan=service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES);
        Assert.assertEquals(2,plan.getAsJsonArray("volumes").size());Assert.assertEquals(1,plan.getAsJsonArray("rootVolumes").size());
        com.google.gson.JsonObject primary=plan.getAsJsonArray("volumes").get(0).getAsJsonObject(),extra=plan.getAsJsonArray("volumes").get(1).getAsJsonObject();
        Assert.assertEquals("DEFAULT_DATA",primary.get("role").getAsString());Assert.assertEquals("[\"NFS\"]",primary.get("protocols").toString());
        Assert.assertEquals("ADDITIONAL_DATA",extra.get("role").getAsString());Assert.assertEquals("[\"ISCSI\",\"SMB\"]",extra.get("protocols").toString());
        Assert.assertEquals("DELETE_WITH_VM",plan.getAsJsonArray("rootVolumes").get(0).getAsJsonObject().get("action").getAsString());
        when(root.getSize()).thenReturn(200L);Assert.assertEquals(plan.get("planHash"),service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES).get("planHash"));
    }

    @Test public void volumeLessNvmeSubsystemDoesNotBindDefaultDataButItsNamespaceBindsAdditionalData() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();SharedFSVO fs=serviceIdentity();fs.setVmId(60L);
        VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares=mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targets=mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        ReflectionTestUtils.setField(service,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(service,"storageFileShareDao",shares);ReflectionTestUtils.setField(service,"storageBlockTargetDao",targets);
        org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO instance=mock(org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(8L);when(instances.findByVmId(60L)).thenReturn(instance);
        VolumeVO primary=dataIdentity(42L),extra=dataIdentity(43L);when(extra.getUuid()).thenReturn("33333333-3333-4333-8333-333333333333");
        when(volumes.findById(42L)).thenReturn(primary);when(volumes.findById(43L)).thenReturn(extra);
        when(volumes.findByInstanceAndType(60L,Volume.Type.DATADISK)).thenReturn(java.util.List.of(primary,extra));
        org.apache.cloudstack.storage.dataservice.StorageFileShareVO nfs=mock(org.apache.cloudstack.storage.dataservice.StorageFileShareVO.class);
        when(nfs.getVolumeId()).thenReturn(42L);when(shares.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.List.of(nfs));
        org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO subsystem=mock(org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO.class),namespace=mock(org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO.class);
        when(subsystem.getVolumeId()).thenReturn(null);when(subsystem.getConfigJson()).thenReturn("{\"type\":\"subsystem\",\"allowAnyHost\":false}");
        when(namespace.getVolumeId()).thenReturn(43L);when(namespace.getConfigJson()).thenReturn("{\"type\":\"namespace\"}");
        when(targets.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NVME_OF)).thenReturn(java.util.List.of(subsystem,namespace));
        for (SharedFS.DataVolumePolicy policy:SharedFS.DataVolumePolicy.values()) {
            com.google.gson.JsonObject plan=service.createDeletionPlan(fs,policy);
            Assert.assertEquals(2,plan.getAsJsonArray("volumes").size());
            Assert.assertEquals("[\"NFS\"]",plan.getAsJsonArray("volumes").get(0).getAsJsonObject().get("protocols").toString());
            Assert.assertEquals("[\"NVME_OF\"]",plan.getAsJsonArray("volumes").get(1).getAsJsonObject().get("protocols").toString());
            when(targets.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NVME_OF)).thenReturn(java.util.List.of(namespace));
            com.google.gson.JsonObject withoutContainer=service.createDeletionPlan(fs,policy);
            Assert.assertEquals(plan,withoutContainer);
            Assert.assertEquals(java.util.Set.of(42L,43L),StorageSharedFsDeletionIdentity.requireCurrent(plan,service.deletionScope(fs,policy),withoutContainer.getAsJsonArray("volumes")));
            when(targets.listByInstanceIdAndProtocol(8L,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NVME_OF)).thenReturn(java.util.List.of(subsystem,namespace));
        }
    }

    @Test public void legacyVolumeLessFileSharesAndBlockTargetsStillUseDefaultDataForDisplay() {
        SharedFSServiceImpl service=new SharedFSServiceImpl();SharedFSVO fs=serviceIdentity();fs.setVmId(60L);
        VolumeDao volumes=mock(VolumeDao.class);ReflectionTestUtils.setField(service,"volumeDao",volumes);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao shares=mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao targets=mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class);
        ReflectionTestUtils.setField(service,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(service,"storageFileShareDao",shares);ReflectionTestUtils.setField(service,"storageBlockTargetDao",targets);
        org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO instance=mock(org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(8L);when(instances.findByVmId(60L)).thenReturn(instance);
        VolumeVO primary=dataIdentity(42L);when(volumes.findById(42L)).thenReturn(primary);when(volumes.findByInstanceAndType(60L,Volume.Type.DATADISK)).thenReturn(java.util.List.of(primary));
        org.apache.cloudstack.storage.dataservice.StorageFileShareVO legacyShare=mock(org.apache.cloudstack.storage.dataservice.StorageFileShareVO.class);
        when(legacyShare.getVolumeId()).thenReturn(null);
        for (org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol protocol:java.util.List.of(org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NFS,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.SMB)) {
            when(shares.listByInstanceIdAndProtocol(8L,protocol)).thenReturn(java.util.List.of(legacyShare));
        }
        org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO legacyBlock=mock(org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO.class);
        when(legacyBlock.getVolumeId()).thenReturn(null);when(legacyBlock.getConfigJson()).thenReturn("{}");
        for (org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol protocol:java.util.List.of(org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.ISCSI,org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NVME_OF)) {
            when(targets.listByInstanceIdAndProtocol(8L,protocol)).thenReturn(java.util.List.of(legacyBlock));
        }
        com.google.gson.JsonObject plan=service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES);
        Assert.assertEquals(1,plan.getAsJsonArray("volumes").size());
        Assert.assertEquals("[\"ISCSI\",\"NFS\",\"NVME_OF\",\"SMB\"]",plan.getAsJsonArray("volumes").get(0).getAsJsonObject().get("protocols").toString());
        when(legacyBlock.getConfigJson()).thenReturn(null);
        Assert.assertEquals(plan,service.createDeletionPlan(fs,SharedFS.DataVolumePolicy.PRESERVE_VOLUMES));
    }

}
