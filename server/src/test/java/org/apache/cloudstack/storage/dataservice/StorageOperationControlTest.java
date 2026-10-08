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
}
