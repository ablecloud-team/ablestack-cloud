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
package com.cloud.vm;

import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StorageVmLifecycleEntryPointTest {
    private UserVmManagerImpl manager;
    private UserVmVO vm;
    private SharedFSService safety;
    private AccountManager accounts;
    private MockedStatic<CallContext> contexts;
    @Before public void setup() {
        manager=new UserVmManagerImpl();vm=mock(UserVmVO.class);when(vm.getId()).thenReturn(7L);when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        UserVmDao vms=mock(UserVmDao.class);when(vms.findById(7L)).thenReturn(vm);ReflectionTestUtils.setField(manager,"_vmDao",vms);
        accounts=mock(AccountManager.class);ReflectionTestUtils.setField(manager,"_accountMgr",accounts);
        safety=mock(SharedFSService.class);javax.inject.Provider<SharedFSService> provider=()->safety;ReflectionTestUtils.setField(manager,"storageFsLifecycleSafety",provider);
        contexts=mockStatic(CallContext.class);CallContext context=mock(CallContext.class);when(context.getCallingAccount()).thenReturn(mock(Account.class));contexts.when(CallContext::current).thenReturn(context);
        doThrow(new CloudRuntimeException("partial formatter preserves DATA")).when(safety).requireVmLifecycleSafety(anyLong(),anyString());
    }
    @After public void close() {contexts.close();}
    @Test public void directStopCannotReachOrchestrationWhileFormatterRecoveryIsPending() {Assert.assertThrows(CloudRuntimeException.class,()->manager.stopVirtualMachine(7L,false));verify(safety).requireVmLifecycleSafety(7L,"STOP");}
    @Test public void directDestroyIsBlockedBeforeStatsAndDataCleanup() {Assert.assertThrows(CloudRuntimeException.class,()->manager.destroyVm(7L,false));verify(safety).requireVmLifecycleSafety(7L,"DESTROY");}
    @Test public void directExpungeIsBlockedBeforeAcquiringOrRemovingVmResources() {Assert.assertThrows(CloudRuntimeException.class,()->manager.expunge(vm));verify(safety).requireVmLifecycleSafety(7L,"EXPUNGE");}
    @Test public void internalRebootRouteIsProtectedBeforeDiskStatisticsOrAgentCalls() {Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rebootVirtualMachine",1L,7L,false,false));verify(safety).requireVmLifecycleSafety(7L,"REBOOT");}
    @Test public void restoreCannotReplaceSharedFsRootBeforeItsDedicatedTemplateWorkflow() {Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreVMInternal(null,vm,null,null,false,null));verify(safety).requireVmLifecycleSafety(7L,"RESTORE");}
    @Test public void ordinaryUserVmDoesNotResolveOrProbeTheStorageService() {when(vm.getUserVmType()).thenReturn("User");manager.requireStorageVmLifecycleSafety(vm,"STOP");verifyNoInteractions(safety,accounts);}
    @Test public void unauthorizedStorageVmLifecycleCannotProbeItsGuest() {doThrow(new PermissionDeniedException("foreign owner")).when(accounts).checkAccess(any(),any(),eq(false),eq(vm));Assert.assertThrows(PermissionDeniedException.class,()->manager.stopVirtualMachine(7L,false));verifyNoInteractions(safety);}
    @Test public void actualStorageVmQueueScopeIsAuthorizedAndComesFromItsBoundSharedFs() {when(safety.getVmStorageServiceSyncId(7L)).thenReturn(8L);Assert.assertEquals(Long.valueOf(8L),manager.getStorageServiceSyncIdForVm(7L));verify(accounts).checkAccess(any(),any(),eq(false),eq(vm));}
    @Test public void ordinaryUserVmQueueDoesNotResolveTheSharedFsService() {when(vm.getUserVmType()).thenReturn("User");Assert.assertNull(manager.getStorageServiceSyncIdForVm(7L));verifyNoInteractions(safety,accounts);}

}
