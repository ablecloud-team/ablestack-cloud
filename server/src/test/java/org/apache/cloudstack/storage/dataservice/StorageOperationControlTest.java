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
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceOperationControlCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CancelStorageServiceOperationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DrainStorageServiceOperationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceOperationControlCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.context.CallContext;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageOperationControlTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
    }
    private Manager manager;
    private StorageServiceOperationVO operation;
    private StorageServiceOperationControlVO control;
    private StorageServiceOperationDao operations;
    private StorageServiceOperationControlDao controls;
    private Object oldEnabled;
    private StorageServiceGuestCommandDispatcher guest;
    @Before public void setup() {
        oldEnabled=ReflectionTestUtils.getField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value");
        ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",true);
        manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getId()).thenReturn(7L);Mockito.when(manager.instance.getName()).thenReturn("exact-name");
        operation=new StorageServiceOperationVO();ReflectionTestUtils.setField(operation,"id",11L);operation.setInstanceId(7);operation.setState("RUNNING");operation.setPhase("PREFLIGHT");operation.setRevision(8);
        control=new StorageServiceOperationControlVO();ReflectionTestUtils.setField(control,"id",22L);control.setOperationId(11);control.setInstanceId(7);control.setPolicyJson("{}");
        operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.findById(11L)).thenReturn(operation);Mockito.when(operations.listByInstance(7L)).thenReturn(List.of(operation));ReflectionTestUtils.setField(manager,"storageOperationDao",operations);
        controls=Mockito.mock(StorageServiceOperationControlDao.class);Mockito.when(controls.findByOperation(11L)).thenReturn(control);Mockito.when(controls.lockRow(22L,true)).thenReturn(control);Mockito.when(controls.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationControlDao",controls);
        guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        com.cloud.user.UserVO user=Mockito.mock(com.cloud.user.UserVO.class);Mockito.when(user.getId()).thenReturn(3L);CallContext.register(user,Mockito.mock(com.cloud.user.AccountVO.class));
    }
    @After public void cleanup() {CallContext.unregister();ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",oldEnabled);}
    private <T extends BaseStorageServiceOperationControlCmd> T request(T cmd) {
        ReflectionTestUtils.setField(cmd,"instanceId",7L);ReflectionTestUtils.setField(cmd,"operationId",11L);ReflectionTestUtils.setField(cmd,"expectedRevision",1L);return cmd;
    }
    private MockedStatic<Transaction> transaction() {
        MockedStatic<Transaction> mock=Mockito.mockStatic(Transaction.class);
        mock.when(()->Transaction.execute(Mockito.any(TransactionCallback.class))).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null));return mock;
    }
    @Test public void cancelRequestCasCannotWaitForOrKillAFormatterAndDoesNotRewriteTheWriter() {
        try(MockedStatic<Transaction> ignored=transaction()) {manager.storageServiceOperationControl(request(new CancelStorageServiceOperationCmd()));}
        Assert.assertTrue(control.isCancelRequested());Assert.assertEquals(2,control.getControlRevision());Assert.assertEquals(Long.valueOf(3),control.getCancelRequestedBy());
        Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());Mockito.verifyNoInteractions(guest);
    }
    @Test public void staleControlRevisionForeignScopeAndActiveApplyAreRejectedBeforeAnyEffect() {
        control.setControlRevision(2);
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(InvalidParameterValueException.class,()->manager.storageServiceOperationControl(request(new CancelStorageServiceOperationCmd())));}
        control.setControlRevision(1);operation.setInstanceId(99);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.storageServiceOperationControl(request(new GetStorageServiceOperationControlCmd())));
        operation.setInstanceId(7);operation.setPhase("APPLYING");
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(InvalidParameterValueException.class,()->manager.storageServiceOperationControl(request(new CancelStorageServiceOperationCmd())));}
        Assert.assertFalse(control.isCancelRequested());Mockito.verifyNoInteractions(guest);
    }
    @Test public void unsupportedNativeDrainCannotClaimReadyOrStopAService() {
        DrainStorageServiceOperationCmd cmd=request(new DrainStorageServiceOperationCmd());ReflectionTestUtils.setField(cmd,"confirmation","exact-name");
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(InvalidParameterValueException.class,()->manager.storageServiceOperationControl(cmd));}
        Assert.assertEquals("NOT_REQUESTED",control.getDrainState());Assert.assertEquals(1,control.getControlRevision());Mockito.verifyNoInteractions(guest);
    }
    @Test public void nativeLeaseScopeCannotBindToAnotherWriter() {
        JsonObject observed=new JsonObject();JsonObject scope=new JsonObject();scope.addProperty("instanceUuid","instance");scope.addProperty("operationUuid","foreign");scope.addProperty("revision",8);observed.add("scope",scope);
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireOperationReservationScope(manager.instance,operation,observed));
    }
    @Test public void explicitFormattingAndBackupRestoreActionsUseTheirOwnMaterializedResourceProfiles() {
        org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd format=Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd.class);Mockito.when(format.getImportMode()).thenReturn("FORMAT_IF_EMPTY");
        Assert.assertEquals(StorageOperationResourceBudget.Work.FILESYSTEM_FORMAT,manager.operationResourceWork("createstoragenfsexportresponse",format));
        Assert.assertEquals(StorageOperationResourceBudget.Work.BACKUP,manager.operationResourceWork("createstorageserviceconfigbackupresponse",null));
        Assert.assertEquals(StorageOperationResourceBudget.Work.RESTORE,manager.operationResourceWork("applystorageserviceconfigrestoreresponse",null));
        Assert.assertEquals(StorageOperationResourceBudget.Work.ROOT_UPGRADE,manager.operationResourceWork("ROOT_TEMPLATE_UPGRADE",null));
        Assert.assertEquals(StorageOperationResourceBudget.Work.RUNTIME_UPGRADE,manager.operationResourceWork("RUNTIME_UPGRADE",null));
        Assert.assertEquals(StorageOperationResourceBudget.Work.SCALE,manager.operationResourceWork("SHAREDFS_ONLINE_SCALE",null));
        Assert.assertTrue(manager.operationRequiresDrain(null,"createstorageiscsitargetresponse"));
    }
    private JsonObject lease() {
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        JsonObject lease=new JsonObject();lease.add("scope",manager.operationReservationScope(manager.instance,operation));lease.addProperty("reservationAcquired",true);lease.addProperty("logicalReservationOnly",true);lease.addProperty("leaseExpiresAt",System.currentTimeMillis()+90_000);
        JsonObject observed=new JsonObject();observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);observed.addProperty("memoryAvailableBytes",2L<<30);observed.addProperty("stagingFreeBytes",3L<<30);observed.addProperty("loadPerCpu",0.25);lease.add("observed",observed);return lease;
    }
    @Test public void expiredStaleUnknownAndNonLogicalLeasesCannotAuthorizeEffects() {
        JsonObject lease=lease();manager.requireFreshOperationLease(lease);
        lease.addProperty("leaseExpiresAt",System.currentTimeMillis()-1);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireFreshOperationLease(lease));
        lease.addProperty("leaseExpiresAt",System.currentTimeMillis()+90_000);lease.getAsJsonObject("observed").addProperty("generatedEpoch",1);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireFreshOperationLease(lease));
        JsonObject unknown=lease();unknown.getAsJsonObject("observed").remove("memoryAvailableBytes");Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireFreshOperationLease(unknown));
        JsonObject stringFlag=lease();stringFlag.addProperty("logicalReservationOnly","true");Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireFreshOperationLease(stringFlag));
        JsonObject physical=lease();physical.addProperty("logicalReservationOnly",false);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requireFreshOperationLease(physical));
    }
    @Test public void cancellationAndApplyPhaseShareTheSameControlRowCasBoundary() {
        control.setLeaseJson(lease().toString());control.setCancelRequested(true);
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(StorageOperationCancelledException.class,()->manager.enterStorageMutationBoundary(operation));}
        Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        control.setCancelRequested(false);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        try(MockedStatic<Transaction> ignored=transaction()) {manager.enterStorageMutationBoundary(operation);}
        Assert.assertEquals("APPLYING",operation.getPhase());Assert.assertFalse(manager.operationCancelable(operation));
    }

    @Test public void nativeResourceBlockersAreDurableAndCannotReachAWriterEffect() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);Mockito.when(manager.instance.getUuid()).thenReturn("instance");Mockito.when(manager.instance.getOperationControlPolicyJson()).thenReturn("{\"schemaVersion\":1,\"instanceUuid\":\"instance\",\"enabled\":true,\"revision\":1}");
        JsonObject policy=new JsonObject();policy.add("requirements",StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.CONFIGURATION,100,0,0,1,false).request());control.setPolicyJson(policy.toString());
        JsonObject reply=lease();reply.addProperty("success",false);reply.addProperty("reservationSupported",true);reply.addProperty("reservationAcquired",false);com.google.gson.JsonArray blockers=new com.google.gson.JsonArray();blockers.add("MEMORY_HEADROOM");reply.add("blockers",blockers);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);Assert.assertEquals("operation reservation acquire",command.getOperation());Assert.assertEquals(5,command.getTimeoutSeconds());return new StorageServiceGuestCommandResult(false,"resource headroom blocked",reply.toString());});
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.acquireOperationResourceReservation(manager.instance,operation));}
        Assert.assertEquals("MEMORY_HEADROOM",com.google.gson.JsonParser.parseString(control.getPolicyJson()).getAsJsonObject().getAsJsonArray("blockers").get(0).getAsString());
        Assert.assertFalse(com.google.gson.JsonParser.parseString(control.getLeaseJson()).getAsJsonObject().get("reservationAcquired").getAsBoolean());Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }

}
