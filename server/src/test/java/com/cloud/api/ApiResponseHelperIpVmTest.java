// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
package com.cloud.api;

import com.cloud.network.IpAddress;
import com.cloud.user.Account;
import com.cloud.utils.net.Ip;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.api.response.IPAddressResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ApiResponseHelperIpVmTest {
    private static final String IP = "10.61.31.18";
    private final ApiResponseHelper helper = new ApiResponseHelper();

    private IPAddressResponse render(boolean publicIp, IpAddress ip) {
        IPAddressResponse response = new IPAddressResponse();
        ReflectionTestUtils.invokeMethod(helper, "showVmInfoForSharedNetworks", publicIp, ip, response);
        return response;
    }

    private IpAddress ip() {
        IpAddress ip = mock(IpAddress.class);
        when(ip.getAddress()).thenReturn(new Ip(IP));
        when(ip.getNetworkId()).thenReturn(123L);
        return ip;
    }

    private void assertVm(IPAddressResponse response, String id, String type) {
        assertEquals(id, ReflectionTestUtils.getField(response, "virtualMachineId"));
        assertEquals(type, ReflectionTestUtils.getField(response, "virtualMachineType"));
    }

    @Test
    public void lookupExcludesRouterIpPlaceholdersWithoutChangingOtherNicLookups() {
        NicDao original = ApiDBUtils.s_nicDao;
        NicDao dao = mock(NicDao.class);
        NicVO bound = mock(NicVO.class);
        try {
            ApiDBUtils.s_nicDao = dao;
            when(dao.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L)).thenReturn(bound);
            assertEquals(bound, ApiDBUtils.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L));
            verify(dao).findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L);
            verifyNoMoreInteractions(dao);
        } finally {
            ApiDBUtils.s_nicDao = original;
        }
    }

    @Test
    public void publicIpAndUnallocatedSharedIpNeverInventVmIdentity() {
        try (MockedStatic<ApiDBUtils> lookup = Mockito.mockStatic(ApiDBUtils.class)) {
            assertVm(render(true, mock(IpAddress.class)), null, null);
            lookup.verifyNoInteractions();
            assertVm(render(false, ip()), null, null);
            lookup.verify(() -> ApiDBUtils.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L));
            lookup.verify(() -> ApiDBUtils.findByIp4AddressAndNetworkId(anyString(), anyLong()), never());
        }
    }

    @Test
    public void primarySharedUserIncludesExplicitTypeAndDisplayName() {
        try (MockedStatic<ApiDBUtils> lookup = Mockito.mockStatic(ApiDBUtils.class)) {
            NicVO nic = new NicVO("qa", 7L, 123L, VirtualMachine.Type.User);
            UserVmVO vm = mock(UserVmVO.class);
            when(vm.getUuid()).thenReturn("user-7");
            when(vm.getHostName()).thenReturn("host-7");
            when(vm.getDisplayName()).thenReturn("검토 VM");
            when(vm.getType()).thenReturn(VirtualMachine.Type.User);
            lookup.when(() -> ApiDBUtils.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L)).thenReturn(nic);
            lookup.when(() -> ApiDBUtils.findUserVmById(7L)).thenReturn(vm);
            IPAddressResponse response = render(false, ip());
            assertVm(response, "user-7", "User");
            assertEquals("검토 VM", ReflectionTestUtils.getField(response, "virtualMachineDisplayName"));
        }
    }

    @Test
    public void secondarySharedUserIncludesExplicitTypeAndNameFallback() {
        try (MockedStatic<ApiDBUtils> lookup = Mockito.mockStatic(ApiDBUtils.class)) {
            NicSecondaryIpVO secondary = mock(NicSecondaryIpVO.class);
            when(secondary.getVmId()).thenReturn(7L);
            UserVmVO vm = mock(UserVmVO.class);
            when(vm.getUuid()).thenReturn("user-7");
            when(vm.getHostName()).thenReturn("host-7");
            when(vm.getType()).thenReturn(VirtualMachine.Type.User);
            lookup.when(() -> ApiDBUtils.findSecondaryIpByIp4AddressAndNetworkId(IP, 123L)).thenReturn(secondary);
            lookup.when(() -> ApiDBUtils.findUserVmById(7L)).thenReturn(vm);
            IPAddressResponse response = render(false, ip());
            assertVm(response, "user-7", "User");
            assertEquals("host-7", ReflectionTestUtils.getField(response, "virtualMachineDisplayName"));
        }
    }

    @Test
    public void boundRouterIsReturnedAndMissingVmStaysUnlinked() {
        try (MockedStatic<ApiDBUtils> lookup = Mockito.mockStatic(ApiDBUtils.class)) {
            NicVO nic = new NicVO("qa", 8L, 123L, VirtualMachine.Type.DomainRouter);
            VMInstanceVO vm = mock(VMInstanceVO.class);
            when(vm.getUuid()).thenReturn("router-8");
            when(vm.getHostName()).thenReturn("r-8-VM");
            when(vm.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
            lookup.when(() -> ApiDBUtils.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L)).thenReturn(nic);
            lookup.when(() -> ApiDBUtils.findVMInstanceById(8L)).thenReturn(vm);
            assertVm(render(false, ip()), "router-8", "DomainRouter");
            lookup.when(() -> ApiDBUtils.findVMInstanceById(8L)).thenReturn(null);
            assertVm(render(false, ip()), null, null);
        }
    }

    @Test
    public void systemVmIdentityRemainsRootAdminOnly() {
        for (VirtualMachine.Type type : new VirtualMachine.Type[]{VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm}) {
            for (Account.Type role : new Account.Type[]{Account.Type.ADMIN, Account.Type.NORMAL}) {
                try (MockedStatic<ApiDBUtils> lookup = Mockito.mockStatic(ApiDBUtils.class);
                     MockedStatic<CallContext> context = Mockito.mockStatic(CallContext.class)) {
                    CallContext ctx = mock(CallContext.class);
                    Account account = mock(Account.class);
                    when(account.getType()).thenReturn(role);
                    when(ctx.getCallingAccount()).thenReturn(account);
                    context.when(CallContext::current).thenReturn(ctx);
                    NicVO nic = new NicVO("qa", 9L, 123L, type);
                    VMInstanceVO vm = mock(VMInstanceVO.class);
                    when(vm.getUuid()).thenReturn("system-9");
                    when(vm.getHostName()).thenReturn("s-9-VM");
                    when(vm.getType()).thenReturn(type);
                    lookup.when(() -> ApiDBUtils.findNonPlaceHolderByIp4AddressAndNetworkId(IP, 123L)).thenReturn(nic);
                    lookup.when(() -> ApiDBUtils.findVMInstanceById(9L)).thenReturn(vm);
                    IPAddressResponse response = render(false, ip());
                    assertVm(response, role == Account.Type.ADMIN ? "system-9" : null, role == Account.Type.ADMIN ? type.toString() : null);
                    if (role != Account.Type.ADMIN) lookup.verify(() -> ApiDBUtils.findVMInstanceById(9L), never());
                }
            }
        }
    }
}
