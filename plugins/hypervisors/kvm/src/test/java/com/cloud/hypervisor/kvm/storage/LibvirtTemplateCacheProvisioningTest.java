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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.cloud.storage.Storage;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class LibvirtTemplateCacheProvisioningTest {
    private void copy(Storage.ProvisioningType requested,Storage.ProvisioningType effective,String preallocation)throws Exception{copy(requested,effective,preallocation,Storage.StoragePoolType.NetworkFilesystem,PhysicalDiskFormat.QCOW2);}
    private void copy(Storage.ProvisioningType requested,Storage.ProvisioningType effective,String preallocation,Storage.StoragePoolType sourceType,PhysicalDiskFormat sourceFormat) throws Exception {
        KVMStoragePool source=Mockito.mock(KVMStoragePool.class),destination=Mockito.mock(KVMStoragePool.class);Mockito.when(source.getType()).thenReturn(sourceType);Mockito.when(destination.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(destination.getDefaultFormat()).thenReturn(PhysicalDiskFormat.QCOW2);
        KVMPhysicalDisk input=new KVMPhysicalDisk("/synthetic/source.qcow2","source",source);input.setFormat(sourceFormat);input.setVirtualSize(20L*1024*1024*1024);input.setSize(1048576);KVMPhysicalDisk output=new KVMPhysicalDisk("/synthetic/cache.qcow2","cache",destination);output.setFormat(PhysicalDiskFormat.QCOW2);Mockito.when(destination.createPhysicalDisk(Mockito.eq("cache"),Mockito.any(Storage.ProvisioningType.class),Mockito.anyLong(),Mockito.isNull())).thenReturn(output);
        List<Script> scripts=new ArrayList<>();
        try(MockedStatic<KVMPhysicalDisk> rbdPath=Mockito.mockStatic(KVMPhysicalDisk.class);MockedStatic<Script> shell=Mockito.mockStatic(Script.class);MockedConstruction<Script> commands=Mockito.mockConstruction(Script.class,(mock,context)->scripts.add(mock));MockedConstruction<QemuImg> qemu=Mockito.mockConstruction(QemuImg.class,Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS),(mock,context)->Mockito.doReturn(Map.of(QemuImg.VIRTUAL_SIZE,Long.toString(input.getVirtualSize()))).when(mock).info(Mockito.any(QemuImgFile.class)))) {
            rbdPath.when(()->KVMPhysicalDisk.RBDStringBuilder(source,"/synthetic/source.qcow2")).thenReturn("rbd:synthetic-source");LibvirtStorageAdaptor adaptor=new LibvirtStorageAdaptor(null);shell.clearInvocations();KVMPhysicalDisk result=adaptor.copyPhysicalDisk(input,"cache",destination,300,null,null,requested);Assert.assertSame(output,result);Mockito.verify(destination).createPhysicalDisk("cache",effective,input.getVirtualSize(),null);
            if(effective==Storage.ProvisioningType.THIN){shell.verify(()->Script.runSimpleBashScript("cp -f /synthetic/source.qcow2 /synthetic/cache.qcow2",300));Assert.assertTrue(scripts.isEmpty());}
            else {shell.verifyNoInteractions();List<String> argv=new ArrayList<>();for(Script script:scripts)for(org.mockito.invocation.Invocation invocation:Mockito.mockingDetails(script).getInvocations())if(invocation.getMethod().getName().equals("add"))for(Object argument:invocation.getArguments())if(argument instanceof String)argv.add((String)argument);Assert.assertTrue(argv.toString(),argv.contains("convert"));Assert.assertTrue(argv.toString(),argv.contains("preallocation="+preallocation));Assert.assertTrue(argv.toString(),argv.contains("/synthetic/cache.qcow2"));Assert.assertFalse(argv.toString(),argv.contains("preallocation=off"));Mockito.verify(destination,Mockito.never()).createPhysicalDisk("cache",Storage.ProvisioningType.THIN,input.getVirtualSize(),null);}
        }
    }
    @Test public void nullTemplateCachePolicyAllocatesSparseFirstAndRealConvertArgvUsesMetadata()throws Exception{copy(null,Storage.ProvisioningType.SPARSE,"metadata");}
    @Test public void explicitSparseCannotBeOverwrittenByQcowSourceCopyFastPath()throws Exception{copy(Storage.ProvisioningType.SPARSE,Storage.ProvisioningType.SPARSE,"metadata");}
    @Test public void explicitFatAllocatesFatFirstAndRealConvertArgvUsesFull()throws Exception{copy(Storage.ProvisioningType.FAT,Storage.ProvisioningType.FAT,"full");}
    @Test public void unrelatedExplicitThinRequestRetainsItsExistingPolicyAndCopyBehavior()throws Exception{copy(Storage.ProvisioningType.THIN,Storage.ProvisioningType.THIN,"off");}
    @Test public void directoryArchiveCopyIsNotConvertedToAProvisionedDisk() {
        KVMStoragePool source=Mockito.mock(KVMStoragePool.class),destination=Mockito.mock(KVMStoragePool.class);Mockito.when(source.getType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);Mockito.when(destination.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(destination.getDefaultFormat()).thenReturn(PhysicalDiskFormat.QCOW2);KVMPhysicalDisk input=new KVMPhysicalDisk("/synthetic/source.tar","source",source);input.setFormat(PhysicalDiskFormat.TAR);KVMPhysicalDisk output=new KVMPhysicalDisk("/synthetic/dir","dir",destination);output.setFormat(PhysicalDiskFormat.DIR);Mockito.when(destination.createPhysicalDisk("dir",PhysicalDiskFormat.DIR,Storage.ProvisioningType.THIN,0,null)).thenReturn(output);
        try(MockedStatic<Script> shell=Mockito.mockStatic(Script.class);MockedConstruction<QemuImg> qemu=Mockito.mockConstruction(QemuImg.class)){Assert.assertSame(output,new LibvirtStorageAdaptor(null).copyPhysicalDisk(input,"dir",destination,300));Mockito.verify(destination).createPhysicalDisk("dir",PhysicalDiskFormat.DIR,Storage.ProvisioningType.THIN,0,null);shell.verify(()->Script.runSimpleBashScript("cp /synthetic/source.tar /synthetic/dir"));}
    }
    @Test public void realCreateAndConvertCommandsBothCarryMetadataOrFullBeforeAnyDiskExists()throws Exception {
        for(Storage.ProvisioningType policy:new Storage.ProvisioningType[]{Storage.ProvisioningType.SPARSE,Storage.ProvisioningType.FAT}) {
            KVMStoragePool source=Mockito.mock(KVMStoragePool.class),destination=Mockito.mock(KVMStoragePool.class);Mockito.when(source.getType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);Mockito.when(destination.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(destination.getDefaultFormat()).thenReturn(PhysicalDiskFormat.QCOW2);Mockito.when(destination.getLocalPath()).thenReturn("/synthetic");KVMPhysicalDisk input=new KVMPhysicalDisk("/synthetic/source.qcow2","source",source);input.setFormat(PhysicalDiskFormat.QCOW2);input.setVirtualSize(20L*1024*1024*1024);List<Script> scripts=new ArrayList<>();
            try(MockedStatic<Script> shell=Mockito.mockStatic(Script.class);MockedConstruction<Script> commands=Mockito.mockConstruction(Script.class,(mock,context)->scripts.add(mock));MockedConstruction<QemuImg> qemu=Mockito.mockConstruction(QemuImg.class,Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS),(mock,context)->Mockito.doReturn(Map.of(QemuImg.VIRTUAL_SIZE,Long.toString(input.getVirtualSize()))).when(mock).info(Mockito.any(QemuImgFile.class)))) {
                LibvirtStorageAdaptor adaptor=new LibvirtStorageAdaptor(null);shell.clearInvocations();Mockito.when(destination.createPhysicalDisk(Mockito.eq("cache"),Mockito.any(Storage.ProvisioningType.class),Mockito.anyLong(),Mockito.isNull())).thenAnswer(call->adaptor.createPhysicalDisk("cache",destination,PhysicalDiskFormat.QCOW2,call.getArgument(1),call.getArgument(2),null));
                Assert.assertNotNull(adaptor.copyPhysicalDisk(input,"cache",destination,300,null,null,policy));List<List<String>> all=new ArrayList<>();for(Script script:scripts){List<String> argv=new ArrayList<>();for(org.mockito.invocation.Invocation invocation:Mockito.mockingDetails(script).getInvocations())if(invocation.getMethod().getName().equals("add"))for(Object value:invocation.getArguments())if(value instanceof String)argv.add((String)value);all.add(argv);}
                String wanted="preallocation="+(policy==Storage.ProvisioningType.SPARSE?"metadata":"full");Assert.assertEquals(2,all.size());Assert.assertTrue(all.toString(),all.get(0).contains("create"));Assert.assertTrue(all.toString(),all.get(0).contains(wanted));Assert.assertTrue(all.toString(),all.get(1).contains("convert"));Assert.assertTrue(all.toString(),all.get(1).contains(wanted));shell.verifyNoInteractions();
            }
        }
    }
    @Test public void rawFileSourceUsesMetadataQcowConversionWithoutChangingSourceFormat()throws Exception{copy(null,Storage.ProvisioningType.SPARSE,"metadata",Storage.StoragePoolType.NetworkFilesystem,PhysicalDiskFormat.RAW);}
    @Test public void rbdRawSourceToSparseQcowPreservesBackendSourceAndUsesMetadataConversion()throws Exception{copy(Storage.ProvisioningType.SPARSE,Storage.ProvisioningType.SPARSE,"metadata",Storage.StoragePoolType.RBD,PhysicalDiskFormat.RAW);}
    @Test public void rbdRawSourceToFatQcowUsesFullConversion()throws Exception{copy(Storage.ProvisioningType.FAT,Storage.ProvisioningType.FAT,"full",Storage.StoragePoolType.RBD,PhysicalDiskFormat.RAW);}
    @Test public void rawFileNullOrSparseAndUnknownFormatRejectBeforeAnyAllocation() {
        for(PhysicalDiskFormat format:new PhysicalDiskFormat[]{PhysicalDiskFormat.RAW,null})for(Storage.ProvisioningType policy:new Storage.ProvisioningType[]{null,Storage.ProvisioningType.SPARSE}) {
            KVMStoragePool source=Mockito.mock(KVMStoragePool.class),destination=Mockito.mock(KVMStoragePool.class);Mockito.when(source.getType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);Mockito.when(destination.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(destination.getDefaultFormat()).thenReturn(format);KVMPhysicalDisk input=new KVMPhysicalDisk("/synthetic/source.raw","source",source);input.setFormat(PhysicalDiskFormat.RAW);
            try(MockedStatic<Script> shell=Mockito.mockStatic(Script.class);MockedConstruction<QemuImg> qemu=Mockito.mockConstruction(QemuImg.class)){LibvirtStorageAdaptor adaptor=new LibvirtStorageAdaptor(null);shell.clearInvocations();Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->adaptor.copyPhysicalDisk(input,"target",destination,300,null,null,policy));Mockito.verify(destination,Mockito.never()).createPhysicalDisk(Mockito.anyString(),Mockito.any(Storage.ProvisioningType.class),Mockito.anyLong(),Mockito.any());Assert.assertTrue(qemu.constructed().isEmpty());shell.verifyNoInteractions();}
        }
    }
}
