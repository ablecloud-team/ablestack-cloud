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
package com.cloud.storage.snapshot;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.exception.InvalidParameterValueException;

public class SnapshotRestoreContractTest {
    private static final long GIB = 1L << 30;
    @Test public void pureRestoreUsesCapturedSizeWithoutInput() {
        SnapshotRestoreContract.validate(GIB, null, null, true, null, null, null);
    }
    @Test public void matchingRequestIsNotSilentlyChanged() {
        SnapshotRestoreContract.validate(GIB, 1L, null, true, null, null, null);
        SnapshotRestoreContract.validate(GIB, 1L, GIB, false, 100L, 200L, null);
    }
    @Test public void sizeChangesAndInvalidSizesAreRejected() {
        for (Long size : new Long[]{0L, -1L, 2L, Long.MAX_VALUE}) {
            assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(GIB, size, null, true, null, null, null));
        }
        assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(0, null, null, true, null, null, null));
    }
    @Test public void fixedAndCustomOfferingCannotAdvertiseDifferentFinalCapacity() {
        for (Long size : new Long[]{0L, GIB / 2, GIB * 2}) {
            assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(GIB, null, size, false, null, null, null));
        }
    }
    @Test public void ignoredIopsAndUnsupportedEncryptionCannotSucceed() {
        assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(GIB, null, null, true, 100L, null, null));
        assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(GIB, null, null, true, null, 200L, null));
        assertThrows(InvalidParameterValueException.class, () -> SnapshotRestoreContract.validate(GIB, null, null, true, null, null, 5L));
    }
    @Test public void contextUsesSnapshotSizeEvenWhenOriginalVolumeChangedOrWasDeleted() {
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.getUuid()).thenReturn("captured-offering"); when(offering.getName()).thenReturn("fixed 1GiB");
        Map<String, Object> context = SnapshotRestoreContract.describe(GIB, offering);
        assertEquals(GIB, context.get("size")); assertEquals("captured-offering", context.get("diskofferingid")); assertEquals(true, context.get("available"));
        assertEquals(false, SnapshotRestoreContract.describe(GIB, null).get("available"));
        assertEquals(false, SnapshotRestoreContract.describe(0, offering).get("available"));
    }
}
