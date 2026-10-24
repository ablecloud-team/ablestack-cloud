/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.storage.datastore;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotDeletionReferenceTest {
    @InjectMocks private ObjectInDataStoreManagerImpl manager = new ObjectInDataStoreManagerImpl();
    @Mock private SnapshotDataStoreDao snapshotDataStoreDao;
    @Mock private SnapshotInfo snapshot;
    @Mock private DataStore store;
    @Mock private SnapshotDataStoreVO reference;

    @Test
    public void selectsExactReferenceInsteadOfFirstStoreMatch() {
        Mockito.when(snapshot.getDataStoreRefId()).thenReturn(42L);
        Mockito.when(snapshot.getId()).thenReturn(3L);
        Mockito.when(store.getId()).thenReturn(9L);
        Mockito.when(store.getRole()).thenReturn(DataStoreRole.Image);
        Mockito.when(reference.getSnapshotId()).thenReturn(3L);
        Mockito.when(reference.getDataStoreId()).thenReturn(9L);
        Mockito.when(reference.getRole()).thenReturn(DataStoreRole.Image);
        Mockito.when(snapshotDataStoreDao.findById(42L)).thenReturn(reference);
        Assert.assertSame(reference, manager.findObject(snapshot, store));
        Mockito.verify(snapshotDataStoreDao, Mockito.never()).findByStoreSnapshot(Mockito.any(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test(expected = CloudRuntimeException.class)
    public void refusesReferenceFromAnotherSnapshot() {
        Mockito.when(snapshot.getDataStoreRefId()).thenReturn(42L);
        Mockito.when(snapshot.getId()).thenReturn(3L);
        Mockito.when(reference.getSnapshotId()).thenReturn(77L);
        Mockito.when(snapshotDataStoreDao.findById(42L)).thenReturn(reference);
        manager.findObject(snapshot, store);
    }
}
