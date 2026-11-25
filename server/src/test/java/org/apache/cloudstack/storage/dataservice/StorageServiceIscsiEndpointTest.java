// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;

import java.util.ArrayList;
import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageServiceIscsiEndpointTest {
    static class Harness extends StorageServiceManagerImpl {
        final List<StorageServiceProtocolVO> rows=new ArrayList<>();
        final List<StorageBlockTargetVO> targets=new ArrayList<>();
        final StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        final StorageBlockTargetDao targetDao=mock(StorageBlockTargetDao.class);
        int applies; boolean fail;
        Harness() {
            when(instance.getId()).thenReturn(8L);
            StorageServiceProtocolDao dao=mock(StorageServiceProtocolDao.class);
            when(dao.listByInstanceIdAndProtocol(8L,StorageServiceInstance.Protocol.ISCSI)).thenAnswer(i->new ArrayList<>(rows));
            when(dao.remove(anyLong())).thenAnswer(i->rows.removeIf(r->r.getId()==(Long)i.getArgument(0)));
            when(targetDao.listByInstanceIdAndProtocol(8L,StorageServiceInstance.Protocol.ISCSI)).thenAnswer(i->targets);
            ReflectionTestUtils.setField(this,"storageServiceProtocolDao",dao);
            ReflectionTestUtils.setField(this,"storageBlockTargetDao",targetDao);
        }
        StorageServiceProtocolVO listener(String ip,Integer port) {
            StorageServiceProtocolVO row=new StorageServiceProtocolVO(8,StorageServiceInstance.Protocol.ISCSI,true,ip,port);
            ReflectionTestUtils.setField(row,"id",(long)rows.size()+1);
            row.setState(StorageServiceInstance.ResourceState.Ready);rows.add(row);return row;
        }
        void target(String config) {
            StorageBlockTargetVO target=mock(StorageBlockTargetVO.class);
            when(target.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);
            when(target.getConfigJson()).thenReturn(config);targets.add(target);
        }
        @Override protected void applyIscsiDesiredState(StorageServiceInstanceVO instance) {
            applies++;
            if(fail)throw new CloudRuntimeException("CONTROLLED_APPLY_FAILURE");
        }
        boolean remove(String ip,Integer port) { return deleteStorageServiceEndpoint(instance,StorageServiceInstance.Protocol.ISCSI,ip,port); }
    }
    @Test public void unusedWildcardRemovalKeepsExplicitListenerTargetsAndAllDataResources() {
        Harness h=new Harness();var old=h.listener(null,null);var keep=h.listener("192.0.2.1",3261);
        h.target("{\"listenerGroupPorts\":[3261]}");
        assertTrue(h.remove(" 0.0.0.0 ",3260));assertEquals(List.of(keep),h.rows);
        assertTrue(keep.isEnabled());assertEquals(1,h.applies);
        verify(h.targetDao,never()).remove(anyLong());
        verify(h.targetDao,never()).update(anyLong(),any());
    }
    @Test public void deletingAnUnusedPrimaryIpListenerDoesNotRemoveTheNic() {
        Harness h=new Harness();h.listener("192.0.2.1",3262);h.listener("192.0.2.1",3261);
        h.target("{\"listenerGroupPorts\":[3261]}");
        assertTrue(h.remove("192.0.2.1",3262));assertEquals(1,h.rows.size());
        assertEquals(Integer.valueOf(3261),h.rows.get(0).getPort());
    }
    @Test public void usedDefaultExplicitAndAllPortsStayProtectedEvenWithAnotherListener() {
        for(String config:List.of("{}","{\"listenerGroupPorts\":[3260]}","{\"endpointMode\":\"ALL\",\"listenerGroupPorts\":[3261]}")) {
            Harness h=new Harness();h.listener(null,null);h.listener("192.0.2.1",3261);h.target(config);
            try {h.remove("0.0.0.0",3260);fail("used listener");}
            catch(StorageServiceConfigurationValidationException e){assertEquals("ISCSI_LISTENER_IN_USE",e.getMessage());}
            assertEquals(2,h.rows.size());assertEquals(0,h.applies);
        }
    }
    @Test public void invalidMissingAndAmbiguousIdentitiesCannotRemoveAnotherTuple() {
        Harness h=new Harness();h.listener(null,null);
        for(Object[] request:new Object[][]{{"bad",3260,"ISCSI_LISTENER_INVALID_ADDRESS"},{"0.0.0.0",0,"ISCSI_LISTENER_INVALID_PORT"},
                {"0.0.0.0",65536,"ISCSI_LISTENER_INVALID_PORT"},{"192.0.2.1",3260,"ISCSI_LISTENER_NOT_FOUND"}}) {
            try {h.remove((String)request[0],(Integer)request[1]);fail("identity");}
            catch(StorageServiceConfigurationValidationException e){assertEquals(request[2],e.getMessage());}
        }
        h.listener("0.0.0.0",3260);
        try {h.remove("0.0.0.0",3260);fail("ambiguous");}
        catch(StorageServiceConfigurationValidationException e){assertEquals("ISCSI_LISTENER_IDENTITY_AMBIGUOUS",e.getMessage());}
        assertEquals(2,h.rows.size());assertEquals(0,h.applies);
    }
    @Test public void applyFailureRestoresEnabledStateBeforeReturningOriginalFailure() {
        Harness h=new Harness();var row=h.listener(null,null);h.listener("192.0.2.1",3261);
        h.target("{\"listenerGroupPorts\":[3261]}");h.fail=true;
        try {h.remove("0.0.0.0",3260);fail("failure");}
        catch(CloudRuntimeException e){assertEquals("CONTROLLED_APPLY_FAILURE",e.getMessage());}
        assertTrue(h.rows.contains(row));assertTrue(row.isEnabled());
        assertEquals(StorageServiceInstance.ResourceState.Ready,row.getState());
    }
}
