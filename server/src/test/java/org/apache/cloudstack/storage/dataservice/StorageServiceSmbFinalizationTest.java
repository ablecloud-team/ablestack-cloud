// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;

import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceConfigurationOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.api.response.StorageServiceConfigurationOperationResponse;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.*;

public class StorageServiceSmbFinalizationTest {
    @Test public void lostGuestCommitAckKeepsReservationAndResumeNeverRepeatsSqlOrApply() throws Exception {
        StorageServiceInstanceVO instance = new StorageServiceInstanceVO();
        ReflectionTestUtils.setField(instance, "id", 8L);
        instance.setVmId(132L); instance.setConfigurationRevision(1L); instance.setActiveConfigurationOperationId(17L);
        StorageServiceConfigurationOperationVO op = new StorageServiceConfigurationOperationVO();
        ReflectionTestUtils.setField(op, "id", 17L);
        op.setInstanceId(8L); op.setProtocol("SMB"); op.setResourceType("EnableStorageServiceProtocolCmd");
        op.setBaseRevision(1L); op.setTargetRevision(2L); op.setState("RUNNING"); op.setBeforeSnapshot("{}"); op.setCandidateSnapshot("{}");
        StorageServiceInstanceDao instances = Mockito.mock(StorageServiceInstanceDao.class);
        StorageServiceConfigurationOperationDao operations = Mockito.mock(StorageServiceConfigurationOperationDao.class);
        StorageServiceGuestCommandDispatcher guest = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageServiceProtocolDao protocols = Mockito.mock(StorageServiceProtocolDao.class);
        StorageFileShareDao shares = Mockito.mock(StorageFileShareDao.class);
        StorageBlockTargetDao targets = Mockito.mock(StorageBlockTargetDao.class);
        StorageAccessRuleDao rules = Mockito.mock(StorageAccessRuleDao.class);
        Mockito.when(instances.findById(8L)).thenReturn(instance); Mockito.when(instances.lockRow(8L, true)).thenReturn(instance);
        Mockito.when(operations.findById(17L)).thenReturn(op);
        Mockito.when(operations.createForUpdate(17L)).thenAnswer(i -> new StorageServiceConfigurationOperationVO());
        Mockito.when(protocols.listByInstanceId(8L)).thenReturn(Collections.emptyList());
        StorageServiceConfigurationCoordinator coordinator = new StorageServiceConfigurationCoordinator(instances, operations, protocols,
                shares, targets, rules, guest, Mockito.mock(StorageServiceConfigurationPreflight.class), Mockito.mock(StorageServiceRuntimeUpgradeDao.class));
        StorageServiceConfigurationPlan plan = new StorageServiceConfigurationPlan(8L, protocols, shares, targets, rules, text -> { });
        AtomicInteger sql = new AtomicInteger(); plan.databaseActions.add(sql::incrementAndGet);
        Mockito.when(guest.dispatch(Mockito.any())).thenThrow(new CloudRuntimeException("SIMULATED_ACK_LOSS"));
        Method commit = StorageServiceConfigurationCoordinator.class.getDeclaredMethod("commitSmbListeners",
                StorageServiceInstanceVO.class, StorageServiceConfigurationOperationVO.class, StorageServiceConfigurationPlan.class);
        commit.setAccessible(true);
        try { commit.invoke(coordinator, instance, op, plan); fail(); }
        catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof CloudRuntimeException); }
        assertEquals(2L, instance.getConfigurationRevision()); assertEquals(Long.valueOf(17L), instance.getActiveConfigurationOperationId());
        assertEquals("RECOVERY_REQUIRED", op.getState()); assertEquals("SMB_FINALIZING", op.getPhase()); assertEquals(1, sql.get());

        JsonObject observed = new JsonObject(); observed.addProperty("instanceUuid", instance.getUuid());
        observed.addProperty("operationid", op.getUuid()); observed.addProperty("baseRevision", 1); observed.addProperty("targetRevision", 2);
        observed.addProperty("state", "COMMITTED"); observed.addProperty("phase", "COMPLETED");
        Mockito.reset(guest);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true, null, observed.toString()));
        // UI/API must offer finalize-only recovery even if the guest already committed.
        StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
        ReflectionTestUtils.setField(manager, "guestCommandDispatcher", guest);
        Method responseMethod = StorageServiceManagerImpl.class.getDeclaredMethod("configurationResponse", StorageServiceInstanceVO.class, StorageServiceConfigurationOperationVO.class);
        responseMethod.setAccessible(true);
        StorageServiceConfigurationOperationResponse response = (StorageServiceConfigurationOperationResponse)responseMethod.invoke(manager, instance, op);
        response.setResponseName("getstorageserviceconfigurationoperationresponse");
        JsonObject wire = JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response, "json"))
                .getAsJsonObject().getAsJsonObject("getstorageserviceconfigurationoperationresponse").getAsJsonObject("configurationoperation");
        assertTrue(wire.get("canresume").getAsBoolean()); assertEquals("SMB_FINALIZING", wire.get("phase").getAsString());
        coordinator.resume(instance, op, recovered -> fail("SQL and candidate recovery must not be replayed"));
        assertNull(instance.getActiveConfigurationOperationId()); assertEquals("COMMITTED", op.getState()); assertEquals(1, sql.get());
        coordinator.resume(instance, op, recovered -> fail());
        Mockito.verify(guest, Mockito.times(2)).dispatch(Mockito.any()); // read observation + finalize ACK
    }

    @Test public void onlySmbEndpointOperationsUseTheScopedFinalization() {
        StorageServiceConfigurationOperationVO op = new StorageServiceConfigurationOperationVO();
        op.setProtocol("SMB"); op.setResourceType("EnableStorageServiceProtocolCmd");
        assertTrue(StorageServiceConfigurationCoordinator.smbEndpointOperation(op));
        op.setResourceType("UpdateStorageSmbShareCmd"); assertFalse(StorageServiceConfigurationCoordinator.smbEndpointOperation(op));
        op.setProtocol("NFS"); op.setResourceType("EnableStorageServiceProtocolCmd"); assertFalse(StorageServiceConfigurationCoordinator.smbEndpointOperation(op));
    }
}
