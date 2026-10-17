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
        boolean finalizationWitnessValid=true;
        @Override protected void requireOperationFinalizationWitness(StorageServiceInstanceVO value,StorageServiceOperationVO row) {
            if(!finalizationWitnessValid)throw new com.cloud.utils.exception.CloudRuntimeException("Exact target witness unavailable");
        }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override public String captureConfigurationSnapshot(long id) {return "{\"tables\":[]}";}
        @Override protected JsonObject operationReadiness(StorageServiceInstanceVO value) {
            JsonObject result=new JsonObject();result.add("protocols",new com.google.gson.JsonArray());result.add("volumes",new com.google.gson.JsonArray());return result;
        }

    }
    private Manager manager;
    private StorageServiceOperationVO operation;
    private StorageServiceOperationControlVO control;
    private StorageServiceOperationDao operations;
    private StorageServiceOperationControlDao controls;
    private Object oldEnabled;
    private Object oldFloor;
    private Object oldDepot;
    private StorageServiceGuestCommandDispatcher guest;
    @Before public void setup() {
        oldEnabled=ReflectionTestUtils.getField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value");
        oldFloor=ReflectionTestUtils.getField(StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb,"_value");
        oldDepot=ReflectionTestUtils.getField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot");
        ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb,"_value",256);
        ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",true);
        manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getId()).thenReturn(7L);Mockito.when(manager.instance.getName()).thenReturn("exact-name");
        operation=new StorageServiceOperationVO();ReflectionTestUtils.setField(operation,"id",11L);operation.setInstanceId(7);operation.setState("RUNNING");operation.setPhase("PREFLIGHT");operation.setRevision(8);
        control=new StorageServiceOperationControlVO();ReflectionTestUtils.setField(control,"id",22L);control.setOperationId(11);control.setInstanceId(7);control.setPolicyJson("{}");
        operations=Mockito.mock(StorageServiceOperationDao.class);Mockito.when(operations.findById(11L)).thenReturn(operation);org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(operations,7L,List.of(operation));ReflectionTestUtils.setField(manager,"storageOperationDao",operations);
        controls=Mockito.mock(StorageServiceOperationControlDao.class);Mockito.when(controls.findByOperation(11L)).thenReturn(control);Mockito.when(controls.lockRow(22L,true)).thenReturn(control);Mockito.when(controls.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationControlDao",controls);
        guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);Mockito.when(instances.findById(7L)).thenReturn(manager.instance);ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);

        com.cloud.user.UserVO user=Mockito.mock(com.cloud.user.UserVO.class);Mockito.when(user.getId()).thenReturn(3L);CallContext.register(user,Mockito.mock(com.cloud.user.AccountVO.class));
    }
    @After public void cleanup() {CallContext.unregister();ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationControlEnabled,"_value",oldEnabled);ReflectionTestUtils.setField(StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb,"_value",oldFloor);ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",oldDepot);}
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

    @Test public void explicitRootDowntimeScopeDoesNotInventNativeSessionDrain() {
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");operation.setAction("ROOT_TEMPLATE_UPGRADE");
        StorageServiceTemplateUpgradeVO root=new StorageServiceTemplateUpgradeVO();root.setInstanceId(7L);root.setOperationId(11L);root.setState("RUNNING");
        JsonObject policy=new JsonObject();policy.add("requirements",StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.ROOT_UPGRADE,100,0,0,1,true).request());control.setPolicyJson(policy.toString());
        manager.startRootOperationControl(manager.instance,root,operation);
        JsonObject updated=com.google.gson.JsonParser.parseString(control.getPolicyJson()).getAsJsonObject();Assert.assertFalse(updated.getAsJsonObject("requirements").get("requireSessionDrain").getAsBoolean());Assert.assertFalse(updated.get("drainSupported").getAsBoolean());
        JsonObject request=manager.operationReservationRequest(manager.instance,operation);Assert.assertEquals(root.getUuid(),request.get("templateUpgradeUuid").getAsString());Assert.assertEquals(operation.getUuid(),request.getAsJsonObject("maintenanceScope").get("operationUuid").getAsString());
        root.setOperationId(99L);Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.startRootOperationControl(manager.instance,root,operation));Mockito.verifyNoInteractions(guest);
    }

    @Test public void suspendedCrossBootLeaseCannotBeRenewedUntilExplicitReacquisition() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);operation.setAction("SHAREDFS_ONLINE_SCALE");control.setLeaseJson(lease().toString());
        JsonObject policy=new JsonObject();policy.add("requirements",StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.SCALE,100,0,0,1,false).request());control.setPolicyJson(policy.toString());
        Mockito.when(operations.findByUuid(operation.getUuid())).thenReturn(operation);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);if(command.getOperation().equals("operation preflight"))return new StorageServiceGuestCommandResult(true,"typed",typedReply(manager.operationReadinessRequest(manager.instance,operation,control)).toString());JsonObject reply=lease();reply.addProperty("success",true);reply.addProperty("reservationSupported",true);if(command.getOperation().equals("operation reservation release"))reply.addProperty("reservationAcquired",false);return new StorageServiceGuestCommandResult(true,"lease",reply.toString());});
        manager.beginStorageWriterHeartbeat(operation);
        try(MockedStatic<Transaction> ignored=transaction()) {
            manager.suspendManagedOperationControl(operation.getUuid());manager.renewOperationResourceReservation(operation);Mockito.verify(guest,Mockito.times(1)).dispatch(Mockito.any());
            manager.resumeManagedOperationControl(operation.getUuid());manager.renewOperationResourceReservation(operation);Mockito.verify(guest,Mockito.times(4)).dispatch(Mockito.any());Assert.assertEquals(operation,manager.managedOperation(operation.getUuid()));
        } finally {manager.endStorageWriterHeartbeat();}
    }

    private JsonObject typedRequest() {
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        JsonObject policy=StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,0,0,0,0,0,false,256);
        control.setPolicyJson(policy.toString());
        return manager.operationReadinessRequest(manager.instance,operation,control);
    }
    private JsonObject typedReply(JsonObject request) {
        JsonObject result=new JsonObject();result.addProperty("mode","TYPED_READINESS_V1");
        result.addProperty("instanceUuid","instance");result.addProperty("success",true);result.addProperty("compatible",true);result.addProperty("readOnly",true);
        result.add("scope",manager.operationReservationScope(manager.instance,operation));result.add("requirements",request.get("requirements").deepCopy());
        result.addProperty("bootId","11111111-1111-4111-8111-111111111111");result.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        JsonObject observed=new JsonObject();observed.addProperty("sessionObservationKind","TCP_CONNECTIONS_ONLY");observed.addProperty("memoryAvailableBytes",4L<<30);observed.addProperty("stagingFreeBytes",4L<<30);observed.addProperty("loadPerCpu",0.1);observed.addProperty("activeSessions",0);
        observed.add("protocolHealth",new JsonObject());observed.add("backingVolumes",new com.google.gson.JsonArray());result.add("observed",observed);result.add("blockers",new com.google.gson.JsonArray());return result;
    }
    private void checkTyped(JsonObject request,JsonObject reply) {
        manager.requireTypedReadiness(manager.instance,operation,request,new StorageServiceGuestCommandResult(true,"typed public observation",reply.toString()),reply);
    }

    @Test public void publicControlScopeAndStrictDrainCapabilityDoNotInventGracefulDrain() {
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        control.setPolicyJson("{\"drainRequired\":true,\"drainSupported\":\"true\"}");
        JsonObject result=manager.operationControlJson(manager.instance,operation,control);
        Assert.assertEquals("instance",result.get("instanceUuid").getAsString());Assert.assertFalse(result.get("drainSupported").getAsBoolean());
        Assert.assertEquals("APPROVED_MAINTENANCE_PLAN_REQUIRED",result.get("maintenanceAlternative").getAsString());
        control.setPolicyJson("{\"drainRequired\":false,\"drainSupported\":false}");
        Assert.assertEquals("NONE",manager.operationControlJson(manager.instance,operation,control).get("maintenanceAlternative").getAsString());
        control.setInstanceId(99L);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.operationControlJson(manager.instance,operation,control));
    }

    @Test public void sourceIncomingAndFloorAreFrozenWithoutAnySecretBodyPersistence() {
        Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        Mockito.when(manager.instance.getOperationControlPolicyJson()).thenReturn("{\"schemaVersion\":1,\"instanceUuid\":\"instance\",\"enabled\":true,\"revision\":1}");
        Mockito.when(controls.findByOperation(11L)).thenReturn(null);Mockito.when(controls.persist(Mockito.any())).thenAnswer(call->call.getArgument(0));
        org.apache.cloudstack.framework.config.impl.ConfigDepotImpl depot=Mockito.mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);
        Mockito.when(depot.getConfigStringValue(Mockito.eq(StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb.key()),Mockito.eq(org.apache.cloudstack.framework.config.ConfigKey.Scope.Global),Mockito.isNull())).thenReturn("16384");
        ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",depot);
        org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd();
        ReflectionTestUtils.setField(cmd,"password","SYNTHETIC_NOT_AN_OPERATOR_SECRET");
        StorageServiceOperationControlVO prepared=manager.startOperationControl(manager.instance,operation,cmd);
        JsonObject policy=com.google.gson.JsonParser.parseString(prepared.getPolicyJson()).getAsJsonObject();
        Assert.assertTrue(policy.get("incomingBytes").getAsLong()>0);
        Assert.assertEquals(16384L<<20,policy.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong());
        Assert.assertFalse(prepared.getPolicyJson().contains("SYNTHETIC_NOT_AN_OPERATOR_SECRET"));
        Assert.assertEquals(0,policy.get("renderedBytes").getAsLong());Assert.assertTrue(policy.get("renderedPending").getAsBoolean());
        Mockito.when(depot.getConfigStringValue(Mockito.eq(StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb.key()),Mockito.eq(org.apache.cloudstack.framework.config.ConfigKey.Scope.Global),Mockito.isNull())).thenReturn("256");
        Assert.assertEquals(Integer.valueOf(256),StorageServiceInstance.StorageServiceOperationMinimumMemoryAvailableMb.value());
        Assert.assertEquals(16384L<<20,policy.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong());
        Mockito.verifyNoInteractions(guest);
    }

    @Test public void typedReadinessRequiresScopeFreshMetricsAndExactRequest() {
        JsonObject request=typedRequest(),reply=typedReply(request);
        try(MockedStatic<Transaction> ignored=transaction()) {checkTyped(request,reply);}
        JsonObject stored=com.google.gson.JsonParser.parseString(control.getPolicyJson()).getAsJsonObject();
        Assert.assertEquals("TYPED_READINESS_V1",stored.get("preflightMode").getAsString());
        Assert.assertEquals(0,stored.getAsJsonObject("observed").get("activeSessions").getAsLong());
        JsonObject foreign=reply.deepCopy();foreign.getAsJsonObject("scope").addProperty("operationUuid","foreign");
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(request,foreign));
        JsonObject stale=reply.deepCopy();stale.addProperty("generatedEpoch",1);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(request,stale));
        JsonObject unknown=reply.deepCopy();unknown.getAsJsonObject("observed").addProperty("loadPerCpu",Double.NaN);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(request,unknown));
        JsonObject legacy=reply.deepCopy();legacy.addProperty("mode","LEGACY_FIXED_FLOORS");
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(request,legacy));
        JsonObject stopped=reply.deepCopy();stopped.getAsJsonObject("observed").getAsJsonObject("protocolHealth").addProperty("NFS","STOPPED_AS_APPROVED");
        JsonObject nfsRequest=request.deepCopy();nfsRequest.getAsJsonObject("readiness").getAsJsonArray("protocols").add("NFS");
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(nfsRequest,stopped));}
    }

    @Test public void blockedTypedReadinessPersistsOnlyClosedPublicBlockersBeforeRejectingEffects() {
        JsonObject request=typedRequest(),reply=typedReply(request);reply.addProperty("success",false);reply.addProperty("compatible",false);
        reply.getAsJsonObject("observed").addProperty("activeSessions",3);
        reply.getAsJsonArray("blockers").add("ACTIVE_SESSIONS");
        reply.addProperty("privateBody","SYNTHETIC_PRIVATE_NOT_TO_PERSIST");
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->checkTyped(request,reply));}
        Assert.assertTrue(control.getPolicyJson().contains("ACTIVE_SESSIONS"));
        Assert.assertFalse(control.getPolicyJson().contains("SYNTHETIC_PRIVATE_NOT_TO_PERSIST"));
        Mockito.verifyNoInteractions(guest);Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }


    private JsonObject cleanupStatus(boolean active,boolean expired,boolean emptyScope) {
        JsonObject result=lease();result.addProperty("success",true);result.addProperty("reservationSupported",true);
        result.addProperty("reservationAcquired",active);result.addProperty("expired",expired);
        if(emptyScope)result.add("scope",com.google.gson.JsonNull.INSTANCE);return result;
    }
    private JsonObject nativeReleaseAck() {
        JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("reservationSupported",true);
        result.addProperty("reservationAcquired",false);result.add("scope",manager.operationReservationScope(manager.instance,operation));
        result.addProperty("drainSupported",false);result.addProperty("logicalReservationOnly",true);
        result.add("blockers",new com.google.gson.JsonArray());com.google.gson.JsonArray features=new com.google.gson.JsonArray();features.add("LOGICAL_RESOURCE_RESERVATION");result.add("supportedFeatures",features);return result;
    }
    @Test public void scopedFinalReleaseOccursBeforeCompletionAndSuspendsFurtherRenewals() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);control.setLeaseJson(lease().toString());operation.setResultJson("{\"_response\":\"done\"}");
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        java.util.List<String> calls=new java.util.ArrayList<>();java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);calls.add(command.getOperation());
            Assert.assertEquals("RUNNING",operation.getState());
            JsonObject reply;
            if(command.getOperation().endsWith("release")){released.set(true);reply=nativeReleaseAck();Assert.assertFalse(reply.has("observed"));}
            else reply=cleanupStatus(!released.get(),false,released.get());
            return new StorageServiceGuestCommandResult(true,"public",reply.toString());
        });
        try(MockedStatic<Transaction> ignored=transaction()) {
            manager.finalizeOperationResourceReservation(manager.instance,operation);manager.renewOperationResourceReservation(operation);
        }
        Assert.assertEquals(List.of("operation reservation status","operation reservation release","operation reservation status"),calls);
        Assert.assertFalse(com.google.gson.JsonParser.parseString(control.getLeaseJson()).getAsJsonObject().get("reservationAcquired").getAsBoolean());
        Assert.assertFalse(com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().get("_leaseCleanupPending").getAsBoolean());
    }
    @Test public void unknownReleaseIsNotBlindlyResentAndFreshAbsenceCanFinalize() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);control.setLeaseJson(lease().toString());operation.setResultJson("{\"_response\":\"done\"}");
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        java.util.concurrent.atomic.AtomicBoolean lost=new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);
            if(command.getOperation().endsWith("release")){lost.set(true);return new StorageServiceGuestCommandResult(false,"unknown",null);}
            return new StorageServiceGuestCommandResult(true,"public",cleanupStatus(!lost.get(),false,lost.get()).toString());
        });
        try(MockedStatic<Transaction> ignored=transaction()) {
            Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));
            Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().get("_leaseCleanupPending").getAsBoolean());
            manager.releaseOperationResourceReservation(manager.instance,operation);
            operation.setState("RECOVERY_REQUIRED");manager.finalizeOperationResourceReservation(manager.instance,operation);
        }
        Mockito.verify(guest,Mockito.times(3)).dispatch(Mockito.any());
    }
    @Test public void expiredOwnedLeaseMayCloseButForeignOrMissingWitnessNeverMay() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);control.setLeaseJson(lease().toString());operation.setResultJson("{\"_response\":\"done\"}");
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        JsonObject status=cleanupStatus(false,true,false);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"public",status.toString()));
        manager.finalizationWitnessValid=false;
        Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));Mockito.verifyNoInteractions(guest);
        manager.finalizationWitnessValid=true;status.getAsJsonObject("scope").addProperty("operationUuid","foreign");
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"public",status.toString()));
        Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));
        status=cleanupStatus(false,true,false);JsonObject ack=nativeReleaseAck(),absent=cleanupStatus(false,false,true);
        java.util.List<String> cleanupCalls=new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger step=new java.util.concurrent.atomic.AtomicInteger();
        String[] replies={status.toString(),ack.toString(),absent.toString()};
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);cleanupCalls.add(command.getOperation());
            return new StorageServiceGuestCommandResult(true,"public",replies[step.getAndIncrement()]);
        });
        try(MockedStatic<Transaction> ignored=transaction()) {manager.finalizeOperationResourceReservation(manager.instance,operation);}
        Assert.assertEquals(List.of("operation reservation status","operation reservation release","operation reservation status"),cleanupCalls);
        Assert.assertFalse(ack.has("observed"));
        Mockito.verify(guest,Mockito.times(4)).dispatch(Mockito.any());
    }
    @Test public void cleanupIntentAndReceiptPersistenceFailuresCannotPublishSuccess() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);control.setLeaseJson(lease().toString());operation.setResultJson("{\"_response\":\"done\"}");
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);
        Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));Mockito.verifyNoInteractions(guest);
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        JsonObject absent=cleanupStatus(false,false,true);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"public",absent.toString()));
        Mockito.when(controls.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));}
        Assert.assertEquals("RUNNING",operation.getState());
        Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().get("_leaseCleanupPending").getAsBoolean());
    }

    @Test public void readOnlyRecoveryFinalizesCleanupWithoutNativeCommitOrSourceReplay() {
        Manager recovery=new Manager() {
            @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO value,StorageServiceOperationVO row,String action) {
                Assert.assertEquals("status",action);return new JsonObject();
            }
            @Override protected void requireRollbackNativeWriterIdle(StorageServiceInstanceVO value) { }
            @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO value) { }
            @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO value) { }
        };
        recovery.instance=manager.instance;
        ReflectionTestUtils.setField(recovery,"storageOperationDao",operations);
        ReflectionTestUtils.setField(recovery,"storageOperationControlDao",controls);
        ReflectionTestUtils.setField(recovery,"guestCommandDispatcher",guest);
        operation.setState("RECOVERY_REQUIRED");operation.setAction("CONFIGURATION_BACKUP_READ_ONLY");
        operation.setPreviousSnapshotJson("{}");operation.setSnapshotJson("{}");operation.setResultJson("{\"_response\":\"artifact\"}");
        control.setLeaseJson(lease().toString());Mockito.when(manager.instance.getVmId()).thenReturn(7L);
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        java.util.List<String> calls=new java.util.ArrayList<>();
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);calls.add(command.getOperation());
            String result=command.getOperation().equals("operation verify")?"{\"success\":true,\"status\":\"ok\"}":cleanupStatus(false,false,true).toString();
            return new StorageServiceGuestCommandResult(true,"public",result);
        });
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertTrue(recovery.recoverOperationFinalization(manager.instance,operation));}
        Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());
        Assert.assertEquals(List.of("operation verify","operation reservation status"),calls);
        Assert.assertEquals(8,operation.getRevision());
        operation.setState("RECOVERY_REQUIRED");operation.setSnapshotJson(null);operation.setResultJson("{}");
        control.setLeaseJson(lease().toString());calls.clear();
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertTrue(recovery.recoverOperationFinalization(manager.instance,operation));}
        Assert.assertEquals("BLOCKED",operation.getState());Assert.assertFalse(operation.getResultJson().contains("_response"));
        Assert.assertEquals(List.of("operation verify","operation reservation status"),calls);
        operation.setState("RECOVERY_REQUIRED");operation.setAction("updatestoragenfsexportresponse");
        operation.setResultJson("{\"_leaseCleanupSource\":true,\"_leaseCleanupTerminal\":\"ROLLED_BACK\",\"_leaseCleanupPending\":true}");
        control.setLeaseJson(lease().toString());calls.clear();
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertTrue(recovery.recoverOperationFinalization(manager.instance,operation));}
        Assert.assertEquals("ROLLED_BACK",operation.getState());Assert.assertEquals(List.of("operation verify","operation reservation status"),calls);
        Mockito.verify(operations,Mockito.never()).persist(Mockito.any());
    }

    @Test public void committedRenderedTargetUsesDurableProofInsteadOfRenewingAnExpiredLease() {
        operation.setState("RECOVERY_REQUIRED");operation.setResultJson("{\"_targetResourceBindings\":{},\"_response\":\"done\"}");
        JsonObject expired=lease();expired.addProperty("leaseExpiresAt",1);control.setLeaseJson(expired.toString());
        manager.requireCommittedOperationResources(manager.instance,operation);manager.renewOperationResourceReservation(operation);Mockito.verifyNoInteractions(guest);
        manager.finalizationWitnessValid=false;
        Assert.assertThrows(RuntimeException.class,()->manager.requireCommittedOperationResources(manager.instance,operation));
        control.setInstanceId(99);manager.finalizationWitnessValid=true;
        Assert.assertThrows(RuntimeException.class,()->manager.requireCommittedOperationResources(manager.instance,operation));
        Mockito.verifyNoInteractions(guest);
    }

    @Test public void stoppedErrorAndDesiredOnlyBackupsKeepManagementOnlyContract() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);Mockito.when(manager.instance.getUuid()).thenReturn("instance");
        Mockito.when(manager.instance.getOperationControlPolicyJson()).thenReturn("{\"schemaVersion\":1,\"instanceUuid\":\"instance\",\"enabled\":true,\"revision\":1}");
        StorageConfigRequest desiredOnly=new StorageConfigRequest() {
            public org.apache.cloudstack.api.BaseCmd getBaseCmd(){return null;}
            public String getConfigAction(){return "BACKUP";}
            public Boolean getIncludeRuntime(){return false;}
        };
        Mockito.when(manager.instance.getState()).thenReturn(StorageServiceInstance.State.Running);
        Assert.assertFalse(manager.managedReadOnlyBackup(manager.instance,desiredOnly));
        StorageConfigRequest withRuntime=new StorageConfigRequest() {
            public org.apache.cloudstack.api.BaseCmd getBaseCmd(){return null;}
            public String getConfigAction(){return "BACKUP";}
        };
        for(StorageServiceInstance.State state:List.of(StorageServiceInstance.State.Stopped,StorageServiceInstance.State.Error)) {
            Mockito.when(manager.instance.getState()).thenReturn(state);Assert.assertFalse(manager.managedReadOnlyBackup(manager.instance,withRuntime));
        }
        com.cloud.vm.dao.VMInstanceDao vms=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);
        com.cloud.vm.VMInstanceVO vm=Mockito.mock(com.cloud.vm.VMInstanceVO.class);
        ReflectionTestUtils.setField(manager,"vmInstanceDao",vms);Mockito.when(vms.findById(7L)).thenReturn(vm);
        Mockito.when(manager.instance.getState()).thenReturn(StorageServiceInstance.State.Running);
        Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);
        Assert.assertFalse(manager.managedReadOnlyBackup(manager.instance,withRuntime));
        Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);
        Assert.assertTrue(manager.managedReadOnlyBackup(manager.instance,withRuntime));Mockito.verifyNoInteractions(guest);
    }
    @Test public void releaseAckWithoutFreshPostStatusMustRemainUnknown() {
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);control.setLeaseJson(lease().toString());operation.setResultJson("{\"_response\":\"done\"}");
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        JsonObject active=cleanupStatus(true,false,false),ack=nativeReleaseAck();
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"status",active.toString()),
                new StorageServiceGuestCommandResult(true,"ack",ack.toString()),new StorageServiceGuestCommandResult(false,"unknown",null));
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(RuntimeException.class,()->manager.finalizeOperationResourceReservation(manager.instance,operation));}
        Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject().get("_leaseCleanupPending").getAsBoolean());
        Assert.assertTrue(com.google.gson.JsonParser.parseString(control.getLeaseJson()).getAsJsonObject().get("reservationAcquired").getAsBoolean());
        Mockito.verify(guest,Mockito.times(3)).dispatch(Mockito.any());
    }

    @Test public void sourceCleanupUnknownRetainsRecoveryWithoutBlindReleaseAndLeaseRejectionStaysBlocked() {
        operation.setState("BLOCKED");control.setLeaseJson("{\"reservationAcquired\":false}");manager.finalizationWitnessValid=false;
        manager.finalizeRestoredSourceResources(manager.instance,operation);Assert.assertEquals("BLOCKED",operation.getState());
        Mockito.verifyNoInteractions(guest);Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        manager.finalizationWitnessValid=true;Mockito.when(manager.instance.getVmId()).thenReturn(7L);
        operation.setState("ROLLED_BACK");operation.setResultJson("{\"_response\":\"failed\"}");control.setLeaseJson(lease().toString());
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        JsonObject status=cleanupStatus(true,false,false);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"status",status.toString()),
                new StorageServiceGuestCommandResult(false,"unknown",null));
        try(MockedStatic<Transaction> ignored=transaction()) {Assert.assertThrows(RuntimeException.class,()->manager.finalizeRestoredSourceResources(manager.instance,operation));}
        Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
        JsonObject result=com.google.gson.JsonParser.parseString(operation.getResultJson()).getAsJsonObject();
        Assert.assertTrue(result.get("_leaseCleanupSource").getAsBoolean());Assert.assertEquals("ROLLED_BACK",result.get("_leaseCleanupTerminal").getAsString());
        manager.releaseOperationResourceReservation(manager.instance,operation);
        Mockito.verify(guest,Mockito.times(2)).dispatch(Mockito.any());
    }

    @Test public void preparedBudgetKeepsOptedOutDispatchButRejectsForeignOptedInOwner() {
        operation.setPhase("APPLYING");operation.setInstanceId(99L);
        manager.beginStorageWriterHeartbeat(operation);
        try {
            Mockito.when(controls.findByOperation(11L)).thenReturn(null);
            manager.requirePreparedResourceBudget(manager.instance,0);
            Mockito.when(controls.findByOperation(11L)).thenReturn(control);
            Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.requirePreparedResourceBudget(manager.instance,0));
            Mockito.verifyNoInteractions(guest);Mockito.verify(operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        } finally {manager.endStorageWriterHeartbeat();}
    }
}
