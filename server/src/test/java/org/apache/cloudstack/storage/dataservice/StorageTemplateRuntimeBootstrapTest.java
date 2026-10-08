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
package org.apache.cloudstack.storage.dataservice;
import java.util.*;
import com.google.gson.JsonObject;
import com.cloud.agent.api.*;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.junit.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageTemplateRuntimeBootstrapTest {
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        List<StorageServiceRuntimeFileType> writes=new ArrayList<>();
        @Override protected byte[] resource(String path){return "trusted-new-resource".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        @Override protected byte[] trustedKey(String key){return new byte[]{1};}
        @Override protected void transfer(StorageServiceInstanceVO instance,String transaction,StorageServiceRuntimeFileType type,String key,byte[] value,int from,int to,StorageServiceRuntimeUpgradeVO upgrade){writes.add(type);}
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance,StorageServiceRuntimeOperation operation,String transaction,JsonObject request){JsonObject result=new JsonObject();result.addProperty("success",true);return result;}
    }
    @Test public void helperWithExistingReadbackFlagStillUpdatesWhenItsInstalledHashDiffers(){
        Manager manager=new Manager();StorageServiceRuntimeHostDispatcher dispatcher=Mockito.mock(StorageServiceRuntimeHostDispatcher.class);VMInstanceDao vms=Mockito.mock(VMInstanceDao.class);VMInstanceVO vm=Mockito.mock(VMInstanceVO.class);Mockito.when(vms.findById(7L)).thenReturn(vm);Mockito.when(vm.getInstanceName()).thenReturn("same-vm");ReflectionTestUtils.setField(manager,"vmInstanceDao",vms);ReflectionTestUtils.setField(manager,"runtimeDispatcher",dispatcher);
        Mockito.when(dispatcher.dispatch(Mockito.eq(7L),Mockito.any())).thenReturn(new StorageServiceRuntimeHostAnswer(null,true,"capabilities","{\"signedRuntimeReadback\":true,\"updaterSha256\":\"older-module\"}"));
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(7L);StorageServiceRuntimeBundleVO bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(bundle.getSigningKeyId()).thenReturn("trusted-key");manager.ensureBootstrap(instance,bundle,"source-runtime");
        Assert.assertTrue(manager.writes.contains(StorageServiceRuntimeFileType.UPDATER_MODULE));Assert.assertTrue(manager.writes.contains(StorageServiceRuntimeFileType.UPDATER_ENTRY));Assert.assertTrue(manager.writes.contains(StorageServiceRuntimeFileType.TRUSTED_KEY));
    }
}
