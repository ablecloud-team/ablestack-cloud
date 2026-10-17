// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class UserVmUserDataResetTest {
    @Spy @InjectMocks private UserVmManagerImpl manager;
    @Mock private UserVmDao vmDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private UserDataDao userDataDao;
    @Mock private UserDataManager userDataManager;
    @Mock private AccountManager accountManager;
    @Mock private NetworkModel networkModel;
    @Mock private NetworkDao networkDao;
    @Mock private NicDao nicDao;
    private UserVmVO vm;
    private ResetVMUserDataCmd command;
    private NicVO first;
    private NicVO second;

    @Before public void setup() throws Exception {
        CallContext.register(mock(User.class), mock(Account.class));
        vm = new UserVmVO(7L, "i-1-7-VM", "reset", 3L, HypervisorType.KVM, 1L, false, false, 1L, 1L, 1L, 1L,
                "old-body", 11L, "{TAG=old}", "reset");
        vm.setState(VirtualMachine.State.Stopped);
        first = mock(NicVO.class); second = mock(NicVO.class);
        command = mock(ResetVMUserDataCmd.class);
        lenient().when(command.getId()).thenReturn(7L);
        lenient().when(command.getUserData()).thenReturn("candidate");
        lenient().when(command.getUserdataId()).thenReturn(22L);
        lenient().when(command.getUserdataDetails()).thenReturn(Map.of("TAG", "new"));
        lenient().when(userDataDao.findById(22L)).thenReturn(mock(UserDataVO.class));
        lenient().when(vmDao.findById(7L)).thenReturn(vm);
        lenient().when(vmDao.acquireInLockTable(7L, 1)).thenReturn(vm);
        lenient().when(templateDao.findByIdIncludingRemoved(3L)).thenReturn(mock(VMTemplateVO.class));
        lenient().doReturn("candidate").when(manager).finalizeUserData(anyString(), anyLong(), any());
        lenient().when(userDataManager.validateUserData(eq("candidate"), any())).thenReturn("new-body");
        lenient().when(vmDao.createForUpdate(7L)).thenReturn(new UserVmVO());
        lenient().when(vmDao.update(eq(7L), any(UserVmVO.class))).thenReturn(true);
    }
    @After public void close() { CallContext.unregister(); }
    private void targets() {
        doReturn(Arrays.asList(first, second)).when(manager).userDataResetTargets(vm);
    }
    private void unchanged() {
        assertEquals("old-body", vm.getUserData()); assertEquals(Long.valueOf(11L), vm.getUserDataId());
        assertEquals("{TAG=old}", vm.getUserDataDetails());
        verify(vmDao, never()).update(anyLong(), any(UserVmVO.class));
    }
    @Test public void allProvidersAcceptBeforeOnlyUserDataFieldsAreSaved() throws Exception {
        targets();
        doAnswer(call -> { verify(vmDao, never()).update(anyLong(), any(UserVmVO.class));
            assertEquals("new-body", ((UserVm) call.getArgument(1)).getUserData()); return true;
        }).when(manager).applyUserData(eq(HypervisorType.KVM), eq(vm), any(), eq(true));
        manager.resetVMUserData(command);
        verify(vmDao).update(eq(7L), argThat(change -> "new-body".equals(change.getUserData()) &&
                Long.valueOf(22L).equals(change.getUserDataId()) && "{TAG=new}".equals(change.getUserDataDetails())));
        verify(vmDao).releaseFromLockTable(7L);
    }
    @Test public void oneNicSuccessCannotHideAnotherFailureAndAllNicsAreRestored() throws Exception {
        targets();
        doReturn(true).when(manager).applyUserData(HypervisorType.KVM, vm, first, true);
        doReturn(false, true).when(manager).applyUserData(HypervisorType.KVM, vm, second, true);
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command));
        assertTrue(error.getMessage().contains("Previous provider data was restored")); unchanged();
        verify(manager, times(2)).applyUserData(HypervisorType.KVM, vm, first, true);
        verify(manager, times(2)).applyUserData(HypervisorType.KVM, vm, second, true);
        verify(vmDao).releaseFromLockTable(7L);
    }
    @Test public void uncertainTimeoutRestoresEvenTheFailingNic() throws Exception {
        targets();
        doThrow(new ResourceUnavailableException("timeout", Network.class, 1L)).doReturn(true)
                .when(manager).applyUserData(HypervisorType.KVM, vm, first, true);
        doReturn(true).when(manager).applyUserData(HypervisorType.KVM, vm, second, true);
        assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command)); unchanged();
        verify(manager).applyUserData(HypervisorType.KVM, vm, second, true);
    }
    @Test public void restorationFailureIsReportedAndDoesNotPublishCandidate() throws Exception {
        targets();
        doReturn(false).when(manager).applyUserData(eq(HypervisorType.KVM), eq(vm), any(), eq(true));
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command));
        assertTrue(error.getMessage().contains("restoration could not be confirmed")); unchanged();
        verify(manager).applyUserData(HypervisorType.KVM, vm, second, true);
    }
    @Test public void concurrentResetFailsWithoutMutatingOrReleasingAnotherLock() {
        when(vmDao.acquireInLockTable(7L, 1)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command)); unchanged();
        verify(vmDao, never()).releaseFromLockTable(anyLong());
    }
    @Test public void freshRunningStateRejectsWithoutProviderOrDbMutation() {
        vm.setState(VirtualMachine.State.Running);
        assertThrows(InvalidParameterValueException.class, () -> manager.resetVMUserData(command)); unchanged();
        verify(vmDao).releaseFromLockTable(7L);
    }
    @Test public void stateChangeAfterProviderAcceptanceRestoresPreviousValues() throws Exception {
        targets();
        doAnswer(call -> { vm.setState(VirtualMachine.State.Starting); return true; }).when(manager)
                .applyUserData(eq(HypervisorType.KVM), eq(vm), any(), eq(true));
        assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command)); unchanged();
    }
    @Test public void persistenceFailureRestoresProvidersAndReleasesLock() throws Exception {
        targets();
        doReturn(true).when(manager).applyUserData(eq(HypervisorType.KVM), eq(vm), any(), eq(true));
        when(vmDao.update(eq(7L), any(UserVmVO.class))).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> manager.resetVMUserData(command));
        assertEquals("old-body", vm.getUserData()); verify(vmDao).releaseFromLockTable(7L);
        verify(manager, times(2)).applyUserData(HypervisorType.KVM, vm, first, true);
    }
    @Test public void unsupportedNetworkRejectsBeforeCandidatePublication() {
        when(nicDao.listByVmId(7L)).thenReturn(Collections.emptyList());
        assertThrows(InvalidParameterValueException.class, () -> manager.resetVMUserData(command)); unchanged();
    }
    @Test public void configDriveOnlyOnSecondaryNicDoesNotQualify() {
        NetworkVO network = mock(NetworkVO.class); UserDataServiceProvider provider = mock(UserDataServiceProvider.class);
        when(nicDao.listByVmId(7L)).thenReturn(Arrays.asList(first));
        when(first.getNetworkId()).thenReturn(9L); when(networkDao.findById(9L)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(10L);
        when(networkModel.areServicesSupportedByNetworkOffering(10L, Network.Service.UserData)).thenReturn(true);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(provider);
        when(provider.getProvider()).thenReturn(Network.Provider.ConfigDrive);
        assertThrows(InvalidParameterValueException.class, () -> manager.userDataResetTargets(vm));
        when(first.isDefaultNic()).thenReturn(true);
        assertEquals(Arrays.asList(first), manager.userDataResetTargets(vm));
    }
    @Test public void missingProviderRejectsBeforeAnyNicIsApplied() {
        NetworkVO network = mock(NetworkVO.class);
        when(nicDao.listByVmId(7L)).thenReturn(Arrays.asList(first));
        when(first.getNetworkId()).thenReturn(9L); when(networkDao.findById(9L)).thenReturn(network);
        when(network.getNetworkOfferingId()).thenReturn(10L);
        when(networkModel.areServicesSupportedByNetworkOffering(10L, Network.Service.UserData)).thenReturn(true);
        assertThrows(InvalidParameterValueException.class, () -> manager.resetVMUserData(command)); unchanged();
    }
    @Test public void resetProvidersReceiveTheExplicitCandidateFlag() throws Exception {
        targets();
        NetworkVO network = mock(NetworkVO.class);
        UserDataServiceProvider provider = mock(UserDataServiceProvider.class);
        when(networkDao.findById(0L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(0L, Network.Service.UserData)).thenReturn(true);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(provider);
        when(provider.saveUserData(any(), any(), any())).thenAnswer(call -> {
            VirtualMachineProfile profile = call.getArgument(2);
            assertEquals(Boolean.TRUE, profile.getParameter(com.cloud.network.rules.UserdataToRouterRules.RESET_CANDIDATE));
            return true;
        });
        manager.resetVMUserData(command);
        verify(provider, times(2)).saveUserData(any(), any(), any());
    }

}
