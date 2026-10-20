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

import java.util.*;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;

public class StorageProtocolRepresentativeTest {
    static StorageServiceProtocolVO row(long id,boolean enabled,String ip,int port) {
        StorageServiceProtocolVO row=new StorageServiceProtocolVO(7,StorageServiceInstance.Protocol.ISCSI,enabled,ip,port);
        ReflectionTestUtils.setField(row,"id",id);row.setState(StorageServiceInstance.ResourceState.Ready);return row;
    }
    @Test public void daoRepresentativeIsStableAcrossRandomRowOrdersAndKeepsOriginalExplicitPrimary() {
        List<StorageServiceProtocolVO> rows=new ArrayList<>(List.of(row(1,true,null,3260),row(2,true,"192.0.2.10",3261),row(3,true,"192.0.2.11",3260),row(4,false,"192.0.2.9",3260)));
        Random random=new Random(77);
        StorageServiceProtocolDaoImpl dao=Mockito.mock(StorageServiceProtocolDaoImpl.class,Mockito.CALLS_REAL_METHODS);
        Mockito.doAnswer(call->{Collections.shuffle(rows,random);return new ArrayList<>(rows);}).when(dao).listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.ISCSI);
        for(int n=0;n<100;n++)Assert.assertEquals(2,dao.findByInstanceIdAndProtocol(7,StorageServiceInstance.Protocol.ISCSI).getId());
        Assert.assertEquals(4,rows.size());Assert.assertNull(rows.stream().filter(r->r.getId()==1).findFirst().get().getListenIp());
    }
    @Test public void nullOnlyAndDisabledRowsStayDeterministicAndForeignRowsFailClosed() {
        StorageServiceProtocolVO wildcard=row(1,true,null,3260),specific=row(2,false,"192.0.2.10",3260);
        Assert.assertSame(wildcard,StorageServiceProtocolSelection.representative(7,StorageServiceInstance.Protocol.ISCSI,List.of(specific,wildcard)));
        Assert.assertSame(wildcard,StorageServiceProtocolSelection.representative(7,StorageServiceInstance.Protocol.ISCSI,List.of(wildcard)));
        StorageServiceProtocolVO foreign=new StorageServiceProtocolVO(8,StorageServiceInstance.Protocol.ISCSI,true,"192.0.2.1",3260);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceProtocolSelection.representative(7,StorageServiceInstance.Protocol.ISCSI,List.of(wildcard,foreign)));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceProtocolSelection.representative(7,StorageServiceInstance.Protocol.SMB,List.of(wildcard)));
    }
    @Test public void actualManagerHeaderListenersAndAliasesStayIdenticalAcrossIndependentRandomDaoReads() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl() {
            @Override protected void addProtocolEndpointNetworkMetadata(JsonObject endpoint,StorageServiceInstanceVO instance,String ip) { }
        };
        StorageServiceInstanceVO instance=new StorageServiceInstanceVO();ReflectionTestUtils.setField(instance,"id",7L);
        List<StorageServiceProtocolVO> rows=new ArrayList<>(List.of(row(1,true,null,3260),row(2,true,"192.0.2.10",3260),row(3,true,"192.0.2.11",3261)));
        StorageServiceProtocolDao dao=Mockito.mock(StorageServiceProtocolDao.class);Random random=new Random(101);
        Mockito.when(dao.listByInstanceIdAndProtocol(7L,StorageServiceInstance.Protocol.ISCSI)).thenAnswer(call->{Collections.shuffle(rows,random);return new ArrayList<>(rows);});
        ReflectionTestUtils.setField(manager,"storageServiceProtocolDao",dao);
        JsonObject expected=manager.buildBlockProtocolPayload(instance,StorageServiceInstance.Protocol.ISCSI);
        Assert.assertEquals("192.0.2.10",expected.get("listenIp").getAsString());Assert.assertEquals(2,expected.getAsJsonArray("listeners").size());
        for(int n=0;n<50;n++)Assert.assertEquals(expected,manager.buildBlockProtocolPayload(instance,StorageServiceInstance.Protocol.ISCSI));
        Mockito.verify(dao,Mockito.never()).findByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any());Mockito.verify(dao,Mockito.never()).persist(Mockito.any());
    }
}
