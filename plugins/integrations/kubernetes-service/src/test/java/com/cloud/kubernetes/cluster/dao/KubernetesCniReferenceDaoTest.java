// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.kubernetes.cluster.dao;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.utils.db.SearchCriteria;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class KubernetesCniReferenceDaoTest {
    @Test public void referenceQueryIncludesEveryNonremovedStateIncludingGcCandidates() {
        KubernetesClusterDaoImpl dao = spy(new KubernetesClusterDaoImpl());
        doReturn(1).when(dao).getCount(any());
        assertTrue(dao.hasActiveCniReferences(17L));
        ArgumentCaptor<SearchCriteria> sc = ArgumentCaptor.forClass(SearchCriteria.class); verify(dao).getCount(sc.capture());
        String where = sc.getValue().getWhereClause();
        assertTrue(where.contains("cni_config_id")); assertTrue(where.contains("removed"));
        assertFalse(where.contains("state")); assertFalse(where.contains("check_for_gc"));
    }
    @Test public void zeroActiveReferencesPermitDeletion() {
        KubernetesClusterDaoImpl dao = spy(new KubernetesClusterDaoImpl()); doReturn(0).when(dao).getCount(any()); assertFalse(dao.hasActiveCniReferences(17L));
    }
}
