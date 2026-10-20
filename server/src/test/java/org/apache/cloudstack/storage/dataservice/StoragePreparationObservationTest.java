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
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/** Read-only orchestration must never turn transport faults into ordinary startup waiting. */
public class StoragePreparationObservationTest {
    static void inject(Object target,String name,Object value)throws Exception {
        Field field=StorageServiceManagerImpl.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static class Harness extends StorageServiceManagerImpl {
        final StorageServicePreparationStateTest.Fixture fixture=new StorageServicePreparationStateTest.Fixture();
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO("issue909-test","",1,2,3,null,"NATIVE");
        final List<String> calls=new ArrayList<>();
        final StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        StorageServiceGuestCommandResult health;boolean formatterFailure,held;int generations;
        Harness()throws Exception {
            instance.setVmId(10L);fixture.health.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
            health=new StorageServiceGuestCommandResult(true,"completed",fixture.health.toString());
            VMInstanceVO vm=new VMInstanceVO();vm.setState(VirtualMachine.State.Running);
            com.cloud.vm.dao.VMInstanceDao vmDao=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);
            Mockito.when(vmDao.findById(10L)).thenReturn(vm);inject(this,"vmInstanceDao",vmDao);
            Mockito.when(operations.listSummariesByInstance(Mockito.anyLong())).thenReturn(Collections.emptyList());
            inject(this,"storageOperationDao",operations);
            inject(this,"storageRuntimeUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class));
            inject(this,"storageTemplateUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao.class));
            Mockito.when(dispatcher.dispatch(Mockito.any())).thenAnswer(call->{calls.add("health");return health;});
            inject(this,"guestCommandDispatcher",dispatcher);
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO ignored,StorageServiceOperationVO operation,String action) {
            calls.add("generation:"+(++generations));return generations==1?fixture.before.deepCopy():fixture.after.deepCopy();
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO ignored,String command,JsonObject request,int timeout) {
            calls.add(command);
            if("operation maintenance status".equals(command)){JsonObject value=fixture.maintenance.deepCopy();value.addProperty("bootHeld",held);return value;}
            if("operation writer-idle".equals(command))return fixture.idle.deepCopy();
            throw new AssertionError("Unexpected command: "+command);
        }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO ignored) {
            calls.add("formatters");if(formatterFailure)throw new CloudRuntimeException("private formatter observation detail");
        }
        @Override protected long rootDesiredRevision(long ignored){return 0;}
        JsonObject observe(){return storagePreparationObservation(instance,"INITIAL_CONFIG",null);}
        void liveHealth(){
            JsonObject live=fixture.health.deepCopy();live.remove("bootId");live.remove("bootReconcile");live.remove("cached");
            health=new StorageServiceGuestCommandResult(true,"completed",live.toString());
        }
        void noWriterPersisted(){Mockito.verify(operations,Mockito.never()).persist(Mockito.any(StorageServiceOperationVO.class));}
    }
    @Test public void healthAndIdleAreLastInsideTheBootFenceAndEmptyConfigurationMayBecomeReady()throws Exception {
        Harness h=new Harness();JsonObject observed=h.observe();Assert.assertTrue(observed.get("ready").getAsBoolean());
        Assert.assertEquals(List.of("generation:1","operation maintenance status","formatters","health","operation writer-idle","generation:2"),h.calls);
        JsonObject diagnostic=JsonParser.parseString(observed.get("diagnostics").getAsString()).getAsJsonObject();
        Assert.assertEquals("CACHE",diagnostic.get("healthOrigin").getAsString());Assert.assertEquals(StorageServicePreparationStateTest.BOOT,diagnostic.get("nativeBootBefore").getAsString());
        Assert.assertEquals(diagnostic.get("nativeBootBefore"),diagnostic.get("nativeBootAfter"));Assert.assertFalse(diagnostic.has("failedObservation"));h.noWriterPersisted();
    }
    @Test public void successfulLiveHealthMayWaitButFailedRpcCannotBeTreatedAsThatSameWire()throws Exception {
        Harness h=new Harness();h.liveHealth();JsonObject waiting=h.observe();Assert.assertEquals("WAITING_BOOT",waiting.get("status").getAsString());
        Assert.assertEquals("HEALTH_BOOT_METADATA_PENDING",waiting.get("reason").getAsString());h.noWriterPersisted();
        h=new Harness();h.liveHealth();h.health=new StorageServiceGuestCommandResult(false,"private transport detail",h.health.getResultJson());
        JsonObject failed=h.observe();Assert.assertEquals("OBSERVATION_UNAVAILABLE",failed.get("status").getAsString());
        Assert.assertEquals("HEALTH_RPC_OBSERVATION_FAILED",failed.get("reason").getAsString());Assert.assertEquals(1,h.generations);
        Assert.assertFalse(failed.toString().contains("private transport detail"));
        Assert.assertEquals("HEALTH",JsonParser.parseString(failed.get("diagnostics").getAsString()).getAsJsonObject().get("failedObservation").getAsString());h.noWriterPersisted();
    }
    @Test public void nullMalformedArrayAndExceptionHealthRemainExplicitObservationErrors()throws Exception {
        for(String invalid:new String[]{"{bad-json","[]","null"}){
            Harness h=new Harness();h.health=new StorageServiceGuestCommandResult(true,"completed",invalid);
            JsonObject result=h.observe();Assert.assertEquals(invalid,"HEALTH_OBSERVATION_INVALID",result.get("reason").getAsString());Assert.assertEquals(1,h.generations);h.noWriterPersisted();
        }
        Harness h=new Harness();h.health=null;Assert.assertEquals("HEALTH_RPC_OBSERVATION_FAILED",h.observe().get("reason").getAsString());
        h=new Harness();Mockito.when(h.dispatcher.dispatch(Mockito.any())).thenThrow(new CloudRuntimeException("private RPC detail"));
        JsonObject result=h.observe();Assert.assertEquals("HEALTH_RPC_OBSERVATION_FAILED",result.get("reason").getAsString());Assert.assertFalse(result.toString().contains("private RPC detail"));h.noWriterPersisted();
    }
    @Test public void liveHealthDoesNotHideProtectedMaintenanceOrUnobservedFormatter()throws Exception {
        Harness h=new Harness();h.liveHealth();h.held=true;Assert.assertEquals("BOOT_MAINTENANCE_HELD",h.observe().get("reason").getAsString());h.noWriterPersisted();
        h=new Harness();h.liveHealth();h.formatterFailure=true;JsonObject result=h.observe();
        Assert.assertEquals("BLOCKED",result.get("status").getAsString());Assert.assertEquals("FORMATTER_ACTIVE_OR_UNOBSERVED",result.get("reason").getAsString());
        Assert.assertFalse(result.toString().contains("private formatter observation detail"));h.noWriterPersisted();
    }
}
