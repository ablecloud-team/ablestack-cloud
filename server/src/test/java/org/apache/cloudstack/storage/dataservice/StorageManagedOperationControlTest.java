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

import java.util.List;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageManagedOperationControlTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;int acquired;int renewed;int released;boolean releaseFailure;
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return instance;}
        @Override public String captureConfigurationSnapshot(long id) {return "{\"tables\":[]}";}
        @Override protected JsonObject rootResourceBinding(StorageServiceInstanceVO instance) {return JsonParser.parseString("{\"rootVolumeUuid\":\"frozen-root\",\"templateId\":3}").getAsJsonObject();}
        @Override protected void acquireOperationResourceReservation(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) {acquired++;}
        @Override protected void renewOperationResourceReservation(StorageServiceOperationVO operation) {renewed++;}
        @Override protected void releaseOperationResourceReservation(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) {released++;if(releaseFailure)throw new CloudRuntimeException("native lease release lost");}
    }
    private Manager manager;private StorageServiceOperationDao operations;private StorageServiceOperationControlDao controls;private StorageServiceRuntimeUpgradeDao upgrades;private StorageServiceRuntimeBundleDao bundles;
    private StorageServiceRuntimeUpgradeVO upgrade;private StorageServiceRuntimeBundleVO bundle;private StorageServiceOperationVO operation;private StorageServiceOperationControlVO control;private Object oldEnabled;
    @Before public void setup() {
        oldEnabled=ReflectionTestUtils.getField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value");ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",true);
        manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getId()).thenReturn(7L);Mockito.when(manager.instance.getUuid()).thenReturn("instance");enabled(true);
        operations=Mockito.mock(StorageServiceOperationDao.class);controls=Mockito.mock(StorageServiceOperationControlDao.class);upgrades=Mockito.mock(StorageServiceRuntimeUpgradeDao.class);bundles=Mockito.mock(StorageServiceRuntimeBundleDao.class);
        ReflectionTestUtils.setField(manager,"storageOperationDao",operations);ReflectionTestUtils.setField(manager,"storageOperationControlDao",controls);ReflectionTestUtils.setField(manager,"storageRuntimeUpgradeDao",upgrades);ReflectionTestUtils.setField(manager,"storageRuntimeBundleDao",bundles);
        Mockito.when(operations.listByInstance(7L)).thenAnswer(call->operation==null?List.of():List.of(operation));Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        Mockito.when(operations.persist(Mockito.any())).thenAnswer(call->{operation=call.getArgument(0);ReflectionTestUtils.setField(operation,"id",11L);return operation;});
        Mockito.when(operations.findByUuid(Mockito.anyString())).thenAnswer(call->operation!=null&&operation.getUuid().equals(call.getArgument(0))?operation:null);Mockito.when(operations.findById(11L)).thenAnswer(call->operation);
        Mockito.when(operations.findByRequest(Mockito.eq(7L),Mockito.anyString())).thenAnswer(call->operation!=null&&operation.getRequestKey().equals(call.getArgument(1))?operation:null);
        Mockito.when(controls.findByOperation(11L)).thenAnswer(call->control);Mockito.when(controls.persist(Mockito.any())).thenAnswer(call->{control=call.getArgument(0);ReflectionTestUtils.setField(control,"id",22L);return control;});Mockito.when(controls.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);Mockito.when(controls.lockRow(22L,true)).thenAnswer(call->control);
        upgrade=new StorageServiceRuntimeUpgradeVO(7,8L,9L,"attempt-"+UUID.randomUUID(),3L);ReflectionTestUtils.setField(upgrade,"id",10L);upgrade.setPreflightJson("{\"targetRuntimePin\":{\"sha256\":\"frozen-target\"}}");Mockito.when(upgrades.findById(10L)).thenReturn(upgrade);
        bundle=new StorageServiceRuntimeBundleVO("version","abi","schema",StorageServiceRuntimeBundleVO.ServiceImpact.NONE,"url","manifest","sig",1000L,"a".repeat(64),"b".repeat(64),"key");Mockito.when(bundles.findById(Mockito.anyLong())).thenReturn(bundle);
        com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(3L);CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));
    }
    private void enabled(boolean value) {Mockito.when(manager.instance.getOperationControlPolicyJson()).thenReturn("{\"schemaVersion\":1,\"instanceUuid\":\"instance\",\"enabled\":"+value+",\"revision\":1}");}
    @After public void cleanup() {manager.endStorageWriterHeartbeat();CallContext.unregister();ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",oldEnabled);}
    private MockedStatic<Transaction> transaction() {MockedStatic<Transaction> mock=Mockito.mockStatic(Transaction.class);mock.when(()->Transaction.execute(Mockito.any(TransactionCallback.class))).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));return mock;}
    @Test public void optedOutRuntimeIsReadonlyWithoutRowLeaseOrArtifactObservation() {enabled(false);Assert.assertNull(manager.beginRuntimeOperationControl(10L,false));Assert.assertEquals(0,manager.acquired);Mockito.verifyNoInteractions(bundles);Mockito.verify(operations,Mockito.never()).persist(Mockito.any());}
    @Test public void lifecycleRowPinsAttemptRootBundleAndActualArchiveBudgetAndSurvivesPolicyOff() {
        String uuid=manager.beginRuntimeOperationControl(10L,false);Assert.assertEquals(operation.getUuid(),uuid);Assert.assertEquals("RUNTIME_UPGRADE",operation.getAction());Assert.assertEquals(1,manager.acquired);
        JsonObject policy=JsonParser.parseString(control.getPolicyJson()).getAsJsonObject();Assert.assertEquals(1000,policy.get("artifactBytes").getAsLong());Assert.assertEquals(128L*1024*1024+3000+2*policy.get("declarationBytes").getAsLong(),policy.getAsJsonObject("requirements").get("stagingRequiredBytes").getAsLong());Assert.assertEquals("frozen-root",policy.getAsJsonObject("managedScope").getAsJsonObject("rootBinding").get("rootVolumeUuid").getAsString());
        enabled(false);ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",false);Assert.assertEquals(uuid,manager.beginRuntimeOperationControl(10L,false));Assert.assertEquals(2,manager.acquired);Mockito.verify(operations,Mockito.times(1)).persist(Mockito.any());
    }
    @Test public void replayCannotSubstituteRuntimeTargetPinBeforeAnotherLease() {manager.beginRuntimeOperationControl(10L,false);upgrade.setPreflightJson("{\"targetRuntimePin\":{\"sha256\":\"foreign\"}}");Assert.assertThrows(CloudRuntimeException.class,()->manager.beginRuntimeOperationControl(10L,false));Assert.assertEquals(1,manager.acquired);}
    @Test public void unknownArchiveSizeCannotReserveZeroBytes() {Mockito.when(bundles.findById(Mockito.anyLong())).thenReturn(new StorageServiceRuntimeBundleVO());Assert.assertThrows(CloudRuntimeException.class,()->manager.beginRuntimeOperationControl(10L,false));Mockito.verify(operations,Mockito.never()).persist(Mockito.any());Assert.assertEquals(0,manager.acquired);}
    @Test public void cancellationCannotWinAfterTheSameRowMutationCas() {
        String uuid=manager.beginRuntimeOperationControl(10L,false);control.setCancelRequested(true);try(MockedStatic<Transaction> ignored=transaction()){Assert.assertThrows(StorageOperationCancelledException.class,()->manager.verifyManagedOperationControl(uuid));}Assert.assertEquals("PREFLIGHT",operation.getPhase());
    }
    @Test public void recoveryHoldsLeaseAndTerminalReleaseFailureCannotPromoteComplete() {
        String uuid=manager.beginRuntimeOperationControl(10L,false);manager.finishManagedOperationControl(uuid,"RECOVERY_REQUIRED");Assert.assertEquals(0,manager.released);Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
        manager.beginRuntimeOperationControl(10L,false);manager.releaseFailure=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.finishManagedOperationControl(uuid,"COMPLETE"));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }
    @Test public void unknownOrForeignExecutionCannotFinishAnotherManagedWriter() {manager.beginRuntimeOperationControl(10L,false);Assert.assertThrows(CloudRuntimeException.class,()->manager.finishManagedOperationControl(UUID.randomUUID().toString(),"COMPLETE"));Assert.assertEquals(0,manager.released);}
    @Test public void scaleSuspensionRetainsTheExactWriterForRecoveryAndReleasePrecedesTerminal() {
        String uuid=manager.beginRuntimeOperationControl(10L,false);manager.suspendManagedOperationControl(uuid);Assert.assertEquals(operation,manager.managedOperation(uuid));Assert.assertEquals(1,manager.released);manager.resumeManagedOperationControl(uuid);manager.finishManagedOperationControl(uuid,"ROLLED_BACK");Assert.assertEquals(2,manager.released);Assert.assertEquals("ROLLED_BACK",operation.getState());
    }
}
