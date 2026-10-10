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

import java.util.UUID;
import com.google.gson.JsonObject;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.User;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ConfigureStorageServiceControlPolicyCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StorageInstanceControlPolicyTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;
        boolean supported=true,metrics=true,foreignGeneration;
        int reads;
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return instance;}
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO owner,StorageServiceOperationVO own) { }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO owner) { }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO owner,String command,JsonObject request,int timeout) {
            Assert.assertEquals("operation reservation status",command);Assert.assertEquals(5,timeout);reads++;
            JsonObject response=new JsonObject();response.addProperty("reservationSupported",supported);response.addProperty("reservationAcquired",false);response.addProperty("logicalReservationOnly",true);response.addProperty("drainSupported",false);
            if(metrics){JsonObject observed=new JsonObject();observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);observed.addProperty("memoryAvailableBytes",2L<<30);observed.addProperty("stagingFreeBytes",3L<<30);observed.addProperty("loadPerCpu",0.5);response.add("observed",observed);}return response;
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO owner,StorageServiceOperationVO op,String action) {
            Assert.assertEquals("status",action);JsonObject response=new JsonObject();response.addProperty("generationStatus","IN_SYNC");JsonObject generation=new JsonObject();generation.addProperty("instanceUuid",foreignGeneration?UUID.randomUUID().toString():instance.getUuid());generation.addProperty("revision",9);response.add("generation",generation);return response;
        }
    }
    private Manager manager;
    private StorageServiceInstanceDao instances;
    private Object previousFlag;
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;
    private MockedStatic<com.cloud.utils.db.DbProperties> properties;
    @Before public void setup() {
        previousFlag=ReflectionTestUtils.getField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value");ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",true);
        java.util.Properties testProperties=new java.util.Properties();testProperties.setProperty("db.cloud.encrypt.secret","unit-test-only-key");properties=mockStatic(com.cloud.utils.db.DbProperties.class);properties.when(com.cloud.utils.db.DbProperties::getDbProperties).thenReturn(testProperties);
        manager=new Manager();manager.instance=new StorageServiceInstanceVO("fixture","",2L,3L,4L,5L,"provider");ReflectionTestUtils.setField(manager.instance,"id",7L);manager.instance.setVmId(8L);
        instances=mock(StorageServiceInstanceDao.class);when(instances.lockRow(7L,true)).thenReturn(manager.instance);when(instances.update(eq(7L),any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);ReflectionTestUtils.setField(manager,"storageOperationControlDao",mock(StorageServiceOperationControlDao.class));
        Account account=mock(Account.class);when(account.getId()).thenReturn(3L);User user=mock(User.class);when(user.getId()).thenReturn(31L);CallContext.register(user,account);
        GlobalLock lock=mock(GlobalLock.class);when(lock.lock(30)).thenReturn(true);locks=mockStatic(GlobalLock.class);locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);
        transactions=mockStatic(Transaction.class);transactions.when(()->Transaction.execute(any(TransactionCallback.class))).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));
    }
    @After public void close() {properties.close();transactions.close();locks.close();CallContext.unregister();ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",previousFlag);}
    private ConfigureStorageServiceControlPolicyCmd request(boolean enabled,long revision) {
        ConfigureStorageServiceControlPolicyCmd cmd=new ConfigureStorageServiceControlPolicyCmd();ReflectionTestUtils.setField(cmd,"instanceId",7L);ReflectionTestUtils.setField(cmd,"enabled",enabled);ReflectionTestUtils.setField(cmd,"expectedPolicyRevision",revision);ReflectionTestUtils.setField(cmd,"confirmation","fixture");ReflectionTestUtils.setField(cmd,"idempotencyKey","stable-policy-key");return cmd;
    }
    @Test public void globalSwitchDoesNotOptInLegacyInstancesOrProbeTheirGuest() {
        Assert.assertFalse(manager.operationControlEnabled(manager.instance));Assert.assertNull(manager.startOperationControl(manager.instance,new StorageServiceOperationVO()));Assert.assertEquals(0,manager.reads);verifyNoInteractions(instances);
    }
    @Test public void exactNativeObservationEnablesOnlyThisInstanceWithPolicyRevisionCas() {
        manager.configureStorageServiceControlPolicy(request(true,0));Assert.assertTrue(manager.operationControlEnabled(manager.instance));Assert.assertEquals(1L,manager.instanceControlPolicy(manager.instance).get("revision").getAsLong());
        StorageServiceInstanceVO other=new StorageServiceInstanceVO("old","",2L,3L,4L,5L,"provider");Assert.assertFalse(manager.operationControlEnabled(other));Assert.assertFalse(manager.instanceControlPolicy(manager.instance).getAsJsonObject("nativeCapabilities").get("drainSupported").getAsBoolean());
    }
    @Test public void supportedButNullMetricsLegacyGetterCannotEnableBeforeDatabaseChanges() {
        manager.metrics=false;Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageServiceControlPolicy(request(true,0)));Assert.assertNull(manager.instance.getOperationControlPolicyJson());verify(instances,never()).update(anyLong(),any());
    }
    @Test public void unsupportedOrForeignGenerationCannotPromoteOptIn() {
        manager.supported=false;Assert.assertThrows(InvalidParameterValueException.class,()->manager.configureStorageServiceControlPolicy(request(true,0)));manager.supported=true;manager.foreignGeneration=true;Assert.assertThrows(InvalidParameterValueException.class,()->manager.configureStorageServiceControlPolicy(request(true,0)));verify(instances,never()).update(anyLong(),any());
    }
    @Test public void sameActorRequestReplayDoesNotRepeatGuestReadsOrChangeRevision() {
        ConfigureStorageServiceControlPolicyCmd cmd=request(true,0);manager.configureStorageServiceControlPolicy(cmd);manager.configureStorageServiceControlPolicy(cmd);Assert.assertEquals(1,manager.reads);verify(instances,times(1)).update(anyLong(),any());
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.configureStorageServiceControlPolicy(request(false,0)));
    }
    @Test public void stalePolicyAndWrongConfirmationAreRejectedBeforeGuestObservation() {
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.configureStorageServiceControlPolicy(request(true,1)));ConfigureStorageServiceControlPolicyCmd cmd=request(true,0);ReflectionTestUtils.setField(cmd,"confirmation","foreign");Assert.assertThrows(InvalidParameterValueException.class,()->manager.configureStorageServiceControlPolicy(cmd));Assert.assertEquals(0,manager.reads);verify(instances,never()).update(anyLong(),any());
    }
    @Test public void foreignInstancePolicyCannotBeActivatedAndMasterOffKeepsItInactive() {
        JsonObject policy=new JsonObject();policy.addProperty("schemaVersion",1);policy.addProperty("instanceUuid",UUID.randomUUID().toString());policy.addProperty("enabled",true);policy.addProperty("revision",1);manager.instance.setOperationControlPolicyJson(policy.toString());Assert.assertThrows(CloudRuntimeException.class,()->manager.operationControlEnabled(manager.instance));
        policy.addProperty("instanceUuid",manager.instance.getUuid());manager.instance.setOperationControlPolicyJson(policy.toString());ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",false);Assert.assertFalse(manager.operationControlEnabled(manager.instance));
    }
    @Test public void runtimeCoverageTracksTheActuallyLoadedImplementationWhileRootAndScaleAreLinked() {
        StorageServiceRuntimeUpgradeManager runtime=mock(StorageServiceRuntimeUpgradeManager.class);ReflectionTestUtils.setField(manager,"runtimeUpgradeManager",runtime);
        JsonObject old=com.google.gson.JsonParser.parseString((String)ReflectionTestUtils.getField(manager.instanceControlPolicyResponse(manager.instance),"resultJson")).getAsJsonObject().getAsJsonObject("coverage");
        Assert.assertEquals("PENDING",old.get("RUNTIME_UPGRADE").getAsString());Assert.assertEquals("LINKED",old.get("ROOT_UPGRADE").getAsString());Assert.assertEquals("LINKED",old.get("SCALE").getAsString());
        when(runtime.operationControlLinked()).thenReturn(true);
        JsonObject full=com.google.gson.JsonParser.parseString((String)ReflectionTestUtils.getField(manager.instanceControlPolicyResponse(manager.instance),"resultJson")).getAsJsonObject().getAsJsonObject("coverage");Assert.assertEquals("LINKED",full.get("RUNTIME_UPGRADE").getAsString());Assert.assertEquals(0,manager.reads);
    }

}
