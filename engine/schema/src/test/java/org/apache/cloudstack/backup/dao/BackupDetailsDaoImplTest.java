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
package org.apache.cloudstack.backup.dao;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.backup.AblestackSharedRawBackup;
import org.apache.cloudstack.backup.BackupDetailVO;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BackupDetailsDaoImplTest {
    @Test
    public void staleProviderSnapshotCannotReplaceSharedRawOwnerOrRetirement() throws Exception {
        BackupDetailsDaoImpl dao = spy(new BackupDetailsDaoImpl());
        SearchBuilder<BackupDetailVO> builder = mock(SearchBuilder.class);
        SearchCriteria<BackupDetailVO> criteria = mock(SearchCriteria.class);
        var field = BackupDetailsDaoImpl.class.getDeclaredField("ordinaryDetailsSearch");
        field.setAccessible(true);
        field.set(dao, builder);
        when(builder.create()).thenReturn(criteria);
        doReturn(0).when(dao).expunge(criteria);
        doAnswer(call -> call.getArgument(0)).when(dao).persist(any(BackupDetailVO.class));
        List<BackupDetailVO> stale = new ArrayList<>();
        for (String key : List.of(AblestackSharedRawBackup.BACKUP_KEY, AblestackSharedRawBackup.BACKUP_KEY + ".released",
                AblestackSharedRawBackup.BACKUP_KEY + ".prepareFailed", AblestackSharedRawBackup.BACKUP_KEY + ".unstarted",
                AblestackSharedRawBackup.RETENTION_PENDING)) {
            stale.add(new BackupDetailVO(60L, key, "stale", false));
        }
        stale.add(new BackupDetailVO(60L, "provider.name", "NAS", false));
        try (var tx = mockStatic(Transaction.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallback.class)))
                    .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
            dao.saveDetails(stale);
        }
        ArgumentCaptor<BackupDetailVO> saved = ArgumentCaptor.forClass(BackupDetailVO.class);
        verify(dao).persist(saved.capture());
        assertEquals("provider.name", saved.getValue().getName());
        var query = ArgumentCaptor.forClass(Object[].class);
        verify(criteria).setParameters(org.mockito.ArgumentMatchers.eq("key"), query.capture());
        for (BackupDetailVO detail : stale.subList(0, stale.size() - 1)) {
            org.junit.Assert.assertTrue(List.of(query.getValue()).contains(detail.getName()));
        }
    }
}
