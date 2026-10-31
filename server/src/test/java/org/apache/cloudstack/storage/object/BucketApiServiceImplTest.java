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
package org.apache.cloudstack.storage.object;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.AccountManager;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.command.user.bucket.UpdateBucketCmd;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Rejections must precede any provider or DAO write, including the former null dereference. */
public class BucketApiServiceImplTest {
    private final BucketApiServiceImpl service = new BucketApiServiceImpl();
    private final BucketDao buckets = mock(BucketDao.class);
    private final AccountManager accounts = mock(AccountManager.class);
    private final UpdateBucketCmd cmd = mock(UpdateBucketCmd.class);
    public BucketApiServiceImplTest() {
        ReflectionTestUtils.setField(service, "_bucketDao", buckets);
        ReflectionTestUtils.setField(service, "_accountMgr", accounts);
        when(cmd.getId()).thenReturn(1L);
    }
    @Test public void missingBucketFailsBeforeBuildingBucketTO() throws Exception {
        try { service.updateBucket(cmd, null); fail(); } catch (InvalidParameterValueException expected) { }
        verifyNoInteractions(accounts);
    }
    @Test public void invalidQuotaFailsBeforeProviderWrite() throws Exception {
        when(buckets.findById(1L)).thenReturn(new BucketVO(1, 1, 1, "bucket", 1, false, false, false, "Private"));
        when(cmd.getQuota()).thenReturn(-1);
        try { service.updateBucket(cmd, null); fail(); } catch (InvalidParameterValueException expected) { }
        verify(buckets, never()).update(anyLong(), any());
    }
    @Test public void mixedQuotaAndProtectionChangesFailBeforeEitherWrite() throws Exception {
        when(buckets.findById(1L)).thenReturn(new BucketVO(1, 1, 1, "bucket", 1, false, false, false, "Private"));
        when(cmd.getQuota()).thenReturn(2); when(cmd.getEncryption()).thenReturn(true);
        try { service.updateBucket(cmd, null); fail(); } catch (InvalidParameterValueException expected) { }
        verify(buckets, never()).update(anyLong(), any());
    }
    @Test public void recoveryCannotIncludeOtherChanges() throws Exception {
        when(buckets.findById(1L)).thenReturn(new BucketVO(1, 1, 1, "bucket", 1, false, false, false, "Private"));
        when(cmd.isReconcile()).thenReturn(true); when(cmd.getQuota()).thenReturn(2);
        try { service.updateBucket(cmd, null); fail(); } catch (InvalidParameterValueException expected) { }
        verify(buckets, never()).update(anyLong(), any());
    }
}
