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
package com.cloud.api.query;

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;

public class SnapshotCopyContractTest {
    private SnapshotJoinVO row;
    @Before public void setup() {
        row = mock(SnapshotJoinVO.class); when(row.getUuid()).thenReturn("snapshot"); when(row.getSnapshotStorePair()).thenReturn("1_Image_2");
        when(row.getDataCenterUuid()).thenReturn("zone"); when(row.getStoreId()).thenReturn(2L); when(row.getStoreUuid()).thenReturn("store");
        when(row.getStoreRole()).thenReturn(DataStoreRole.Image); when(row.getStoreState()).thenReturn(ObjectInDataStoreStateMachine.State.Ready); when(row.getSize()).thenReturn(1L << 30);
    }
    @Test public void stableReferenceIdDoesNotDependOnNameStateOrTagJoinRows() {
        Object id = SnapshotCopyContract.describe(row, true).get("id"); when(row.getStoreName()).thenReturn("renamed"); when(row.getStoreState()).thenReturn(ObjectInDataStoreStateMachine.State.Copying);
        assertEquals(id, SnapshotCopyContract.describe(row, true).get("id")); when(row.getSnapshotStorePair()).thenReturn("1_Primary_2"); assertNotEquals(id, SnapshotCopyContract.describe(row, true).get("id"));
    }
    @Test public void missingReferenceIdsAreDistinctAcrossZones() {
        when(row.getStoreId()).thenReturn(null); when(row.getStoreUuid()).thenReturn(null); when(row.getSnapshotStorePair()).thenReturn("1_UNKNOWN_zone_2");
        Object id = SnapshotCopyContract.describe(row, false).get("id"); when(row.getDataCenterUuid()).thenReturn("other"); assertNotEquals(id, SnapshotCopyContract.describe(row, false).get("id"));
    }
    @Test public void readyFactsDoNotFabricatePhysicalSizeOrZeroProgress() {
        Map<String,Object> copy = SnapshotCopyContract.describe(row, true); assertEquals("Ready",copy.get("availability")); assertEquals(1L<<30,copy.get("virtualsize")); assertNull(copy.get("downloadpercent")); assertFalse(copy.containsKey("physicalsize"));
    }
    @Test public void missingMetadataAndSizeRemainUnknown() {
        when(row.getStoreUuid()).thenReturn(null); when(row.getSize()).thenReturn(0L); Map<String,Object> copy=SnapshotCopyContract.describe(row,true);
        assertEquals("Unknown",copy.get("availability")); assertEquals(false,copy.get("datastoreknown")); assertNull(copy.get("virtualsize"));
        when(row.getStoreUuid()).thenReturn("store"); when(row.getStoreState()).thenReturn(null); assertEquals("Unknown",SnapshotCopyContract.describe(row,true).get("availability"));
    }
    @Test public void partialCopyPreservesRealZeroAndIntermediateProgress() {
        when(row.getDownloadState()).thenReturn(VMTemplateStorageResourceAssoc.Status.DOWNLOAD_IN_PROGRESS);
        for(int pct:new int[]{0,37,100}){when(row.getDownloadPercent()).thenReturn(pct);Map<String,Object> copy=SnapshotCopyContract.describe(row,true);assertEquals(pct,copy.get("downloadpercent"));assertEquals("NotReady",copy.get("availability"));}
    }
    @Test public void failureReasonAndDiagnosticsRespectVisibility() {
        when(row.getErrorString()).thenReturn("copy failed"); when(row.getDownloadState()).thenReturn(VMTemplateStorageResourceAssoc.Status.DOWNLOAD_ERROR);
        assertEquals("copy failed",SnapshotCopyContract.describe(row,true).get("error"));assertEquals("NotReady",SnapshotCopyContract.describe(row,false).get("availability"));assertNull(SnapshotCopyContract.describe(row,false).get("error"));
    }
    @Test public void explicitStoreFiltersResolveToTheCorrectRole() {
        assertEquals(Snapshot.LocationType.PRIMARY,SnapshotCopyContract.location(null,1L,null));assertEquals(Snapshot.LocationType.SECONDARY,SnapshotCopyContract.location(null,null,1L));assertNull(SnapshotCopyContract.location(null,null,null));
    }
    @Test public void conflictingFiltersFailRatherThanSilentlyChangeScope() {
        assertThrows(InvalidParameterValueException.class,()->SnapshotCopyContract.location(null,1L,1L));
        assertThrows(InvalidParameterValueException.class,()->SnapshotCopyContract.location(Snapshot.LocationType.PRIMARY,null,1L));
        assertThrows(InvalidParameterValueException.class,()->SnapshotCopyContract.location(Snapshot.LocationType.SECONDARY,1L,null));
    }
}
