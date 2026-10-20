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
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;import org.junit.Test;
public class StorageInitialPreparationAdmissionTest {
    static class Harness extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO("test","",1,2,3,null,"NATIVE");
        boolean checked;
        Harness(){instance.setVmId(10L);}
        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd cmd){return 1L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected long rootDesiredRevision(long instance){return 0;}
        @Override protected void requireInitialPreparationReady(StorageServiceInstanceVO i){checked=true;throw new CloudRuntimeException("BOOT_RECONCILING");}
        void apply(AtomicBoolean changed){executeDesiredChange(new org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd(),String.class,()->{changed.set(true);return "changed";});}
    }
    @Test public void verifiedPrivateCloneBaselineContextsDoNotBecomeAStartupGate() throws Exception {
        Harness h=new Harness();Assert.assertTrue(h.initialPreparationRequired(h.instance,1L));
        for(String name:new String[]{"configurationCloneRestoreMaintenance","configurationCloneInitialBaseline"}){
            java.lang.reflect.Field field=StorageServiceManagerImpl.class.getDeclaredField(name);field.setAccessible(true);
            @SuppressWarnings("unchecked") ThreadLocal<JsonObject> trusted=(ThreadLocal<JsonObject>)field.get(h);
            trusted.set(new JsonObject());
            try{Assert.assertFalse(name,h.initialPreparationRequired(h.instance,1L));}finally{trusted.remove();}
            Assert.assertTrue("A public command cannot manufacture the private verified context",h.initialPreparationRequired(h.instance,1L));
        }
    }
    @Test public void initialBootNotReadyRejectsBeforeDesiredWriterPersistenceAndBeforeAnyDesiredChange(){
        Harness h=new Harness();AtomicBoolean changed=new AtomicBoolean();
        Assert.assertThrows(CloudRuntimeException.class,()->h.apply(changed));Assert.assertTrue(h.checked);Assert.assertFalse(changed.get());
        // No operation DAO or snapshot dependencies were provided: reaching writer persistence would fail this test.
    }
}
