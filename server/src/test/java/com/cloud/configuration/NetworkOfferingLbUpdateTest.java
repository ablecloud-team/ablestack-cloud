// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.
package com.cloud.configuration;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;

import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.network.Network.Service;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.utils.DomainHelper;

@RunWith(MockitoJUnitRunner.Silent.class)
public class NetworkOfferingLbUpdateTest {
    @InjectMocks private ConfigurationManagerImpl manager;
    @Mock private NetworkOfferingDao offerings;
    @Mock private NetworkOfferingDetailsDao details;
    @Mock private NetworkOfferingServiceMapDao services;
    @Mock private DomainHelper domainHelper;
    private MockedStatic<CallContext> context;
    private UpdateNetworkOfferingCmd command;
    private NetworkOfferingVO update;

    @Before
    public void setup() {
        context = mockStatic(CallContext.class);
        context.when(CallContext::current).thenReturn(mock(CallContext.class));
        command = mock(UpdateNetworkOfferingCmd.class);
        when(command.getId()).thenReturn(42L);
        when(command.getMaxconnections()).thenReturn(null);
        when(command.getKeepAliveEnabled()).thenReturn(null);
        when(command.getSortKey()).thenReturn(null);
        when(command.getDomainIds()).thenReturn(new ArrayList<>());
        when(command.getZoneIds()).thenReturn(new ArrayList<>());
        when(domainHelper.filterChildSubDomains(anyList())).thenReturn(new ArrayList<>());
        when(details.findDomainIds(42L)).thenReturn(new ArrayList<>());
        when(details.findZoneIds(42L)).thenReturn(new ArrayList<>());
        NetworkOfferingVO original = mock(NetworkOfferingVO.class);
        when(offerings.findById(42L)).thenReturn(original);
        update = mock(NetworkOfferingVO.class);
        when(update.getId()).thenReturn(42L);
        when(offerings.createForUpdate(42L)).thenReturn(update);
        when(offerings.update(42L, update)).thenReturn(true);
        when(services.areServicesSupportedByNetworkOffering(42L, Service.Lb)).thenReturn(true);
    }

    @After public void cleanup() { context.close(); }

    @Test public void keepaliveTrueAloneIsPersisted() {
        when(command.getKeepAliveEnabled()).thenReturn(true);
        manager.updateNetworkOffering(command);
        verify(update).setKeepAliveEnabled(true);
        verify(offerings).update(42L, update);
        verify(update, never()).setConcurrentConnections(anyInt());
    }

    @Test public void keepaliveFalseAloneIsPersisted() {
        when(command.getKeepAliveEnabled()).thenReturn(false);
        manager.updateNetworkOffering(command);
        verify(update).setKeepAliveEnabled(false);
        verify(offerings).update(42L, update);
    }

    @Test public void omittedKeepaliveDoesNotClearExistingValue() {
        when(command.getMaxconnections()).thenReturn(300);
        manager.updateNetworkOffering(command);
        verify(update).setConcurrentConnections(300);
        verify(update, never()).setKeepAliveEnabled(anyBoolean());
    }

    @Test public void unchangedRequestDoesNotWrite() {
        manager.updateNetworkOffering(command);
        verify(offerings, never()).update(42L, update);
    }

    @Test public void noLbServiceDoesNotReceiveLbFields() {
        when(services.areServicesSupportedByNetworkOffering(42L, Service.Lb)).thenReturn(false);
        when(command.getKeepAliveEnabled()).thenReturn(true);
        when(command.getMaxconnections()).thenReturn(300);
        manager.updateNetworkOffering(command);
        verify(update, never()).setConcurrentConnections(anyInt());
        verify(update, never()).setKeepAliveEnabled(anyBoolean());
    }

    @Test public void apiAndDatabaseIntersectionIsPreserved() {
        for (int value : new int[] {0, 1, Integer.MAX_VALUE}) {
            when(command.getMaxconnections()).thenReturn(value);
            manager.updateNetworkOffering(command);
            verify(update).setConcurrentConnections(value);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void negativeConnectionsFailBeforeDatabaseWrite() {
        when(command.getMaxconnections()).thenReturn(-1);
        manager.updateNetworkOffering(command);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void minimumSignedIntegerFailsBeforeDatabaseWrite() {
        when(command.getMaxconnections()).thenReturn(Integer.MIN_VALUE);
        manager.updateNetworkOffering(command);
    }

}
