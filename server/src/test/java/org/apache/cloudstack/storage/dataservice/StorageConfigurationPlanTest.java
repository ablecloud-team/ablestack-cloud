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
import java.lang.reflect.Field;
import java.util.Collections;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageConfigurationPlanTest {
    private StorageFileShareDao shares;
    private StorageServiceConfigurationPlan plan;
    private StorageFileShareVO original;
    @Before public void setup() throws Exception {
        StorageServiceProtocolDao protocols = mock(StorageServiceProtocolDao.class);
        shares = mock(StorageFileShareDao.class);
        StorageBlockTargetDao targets = mock(StorageBlockTargetDao.class);
        StorageAccessRuleDao rules = mock(StorageAccessRuleDao.class);
        original = new StorageFileShareVO(9, StorageServiceInstance.Protocol.NFS, "preserve", "/export/preserve",
                8L, "xfs", null, StorageServiceInstance.ResourceState.Ready, "{\"mode\":\"0750\"}");
        Field id = StorageFileShareVO.class.getDeclaredField("id"); id.setAccessible(true); id.set(original, 7L);
        when(shares.listByInstanceIdAndProtocol(9, StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.singletonList(original));
        when(shares.listByInstanceIdAndProtocol(9, StorageServiceInstance.Protocol.SMB)).thenReturn(Collections.emptyList());
        when(protocols.listByInstanceId(9)).thenReturn(Collections.emptyList());
        when(targets.listByInstanceIdAndProtocol(eq(9L), any())).thenReturn(Collections.emptyList());
        when(rules.listByResource(any(), anyLong())).thenReturn(Collections.emptyList());
        plan = new StorageServiceConfigurationPlan(9, protocols, shares, targets, rules, text -> { });
    }
    @Test public void candidateEditCannotPublishOrMutateTheOriginalBeforeVerifiedCommit() {
        StorageFileShareDao view = plan.dao(StorageFileShareDao.class);
        StorageFileShareVO detached = view.findById(7L);
        assertNotSame(original, detached);
        detached.setName("candidate"); view.update(7L, detached);
        assertEquals("preserve", original.getName());
        verify(shares, never()).update(anyLong(), any());
        StorageFileShareVO updated = new StorageFileShareVO();
        when(shares.createForUpdate(7L)).thenReturn(updated);
        when(shares.update(7L, updated)).thenReturn(true);
        plan.commit();
        assertEquals("candidate", updated.getName());
        assertEquals("{\"mode\":\"0750\"}", updated.getConfigJson());
        verify(shares).update(7L, updated);
    }
    @Test public void candidateDeleteRemainsInDatabaseUntilCommit() {
        StorageFileShareDao view = plan.dao(StorageFileShareDao.class);
        view.remove(7L);
        assertEquals(0, view.listByInstanceIdAndProtocol(9, StorageServiceInstance.Protocol.NFS).size());
        verify(shares, never()).remove(7L);
        plan.commit();
        verify(shares).remove(7L);
    }
    @Test public void snapshotLimitCountsUtf8BytesBeforeAnyMutation() {
        StorageServiceConfigurationPlan.bounded("보".repeat(349525));
        try { StorageServiceConfigurationPlan.bounded("보".repeat(349526)); fail("UTF-8 overflow must reject"); }
        catch (CloudRuntimeException expected) { }
        verify(shares, never()).update(anyLong(), any());
        verify(shares, never()).remove(anyLong());
    }
}
