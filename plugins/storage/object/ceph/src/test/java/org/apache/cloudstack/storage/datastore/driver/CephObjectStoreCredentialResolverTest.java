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
package org.apache.cloudstack.storage.datastore.driver;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.AccountVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialVO;
import org.junit.Before;
import org.junit.Test;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.impl.RgwAdminException;
import org.twonote.rgwadmin4j.model.S3Credential;
import org.twonote.rgwadmin4j.model.User;

public class CephObjectStoreCredentialResolverTest {
    private final AccountDao accounts = mock(AccountDao.class);
    private final AccountDetailsDao details = mock(AccountDetailsDao.class);
    private final BucketDao buckets = mock(BucketDao.class);
    private final ObjectStoreAccountCredentialDao dao = mock(ObjectStoreAccountCredentialDao.class);
    private final RgwAdmin admin = mock(RgwAdmin.class);
    private final Map<Long, ObjectStoreAccountCredentialVO> rows = new HashMap<>();
    private CephObjectStoreCredentialResolver resolver;
    private User user;
    private S3Credential keyA;
    private S3Credential keyB;

    @Before public void setup() throws Exception {
        AccountVO owner = mock(AccountVO.class); when(owner.getUuid()).thenReturn("owner");
        when(accounts.findById(1L)).thenReturn(owner);
        when(buckets.listByObjectStoreIdAndAccountId(anyLong(), anyLong())).thenReturn(Collections.emptyList());
        when(dao.findByAccountAndStore(eq(1L), anyLong())).thenAnswer(i -> rows.get(i.getArgument(1)));
        when(dao.persist(any(ObjectStoreAccountCredentialVO.class))).thenAnswer(i -> {
            ObjectStoreAccountCredentialVO row = i.getArgument(0);
            Field id = ObjectStoreAccountCredentialVO.class.getDeclaredField("id"); id.setAccessible(true); id.set(row, row.getObjectStoreId());
            rows.put(row.getObjectStoreId(), row); return row;
        });
        when(dao.update(anyLong(), any(ObjectStoreAccountCredentialVO.class))).thenReturn(true);
        keyA = key("access-A", "secret-A"); keyB = key("access-B", "secret-B");
        user = new User(); user.setUserId("owner"); user.setSuspended(0); user.setS3Credentials(Arrays.asList(keyA, keyB));
        when(admin.getUserInfo("owner")).thenReturn(Optional.of(user));
        resolver = spy(new CephObjectStoreCredentialResolver(accounts, details, buckets, dao, ignored -> admin));
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(resolver).inTransaction(any());
    }
    private S3Credential key(String access, String secret) {
        S3Credential key = mock(S3Credential.class); when(key.getAccessKey()).thenReturn(access); when(key.getSecretKey()).thenReturn(secret); return key;
    }
    private ObjectStoreAccountCredentialVO resolve(long store, boolean create) { return resolver.resolveLocked(1, store, create); }
    private void rejected(boolean create) {
        try { resolve(11, create); fail("verification must fail"); }
        catch (CloudRuntimeException e) { assertFalse(e.getMessage().contains("PRIVATE")); assertNull(e.getCause()); }
        assertEquals("NeedsVerification", rows.get(11L).getState());
    }
    @Test public void firstUserCreationPreservesEveryAccountDetail() {
        when(admin.getUserInfo("owner")).thenThrow(new RgwAdminException(404)).thenReturn(Optional.of(user));
        ObjectStoreAccountCredentialVO row = resolve(11, true);
        assertEquals("Verified", row.getState()); assertEquals("CreatedByCloud", row.getProvisioningSource());
        verify(admin).createUser("owner"); verify(details, never()).persist(anyLong(), anyMap());
    }
    @Test public void existingRgwUserRepairsMissingCloudBindingWithoutCreatingOrRotating() {
        ObjectStoreAccountCredentialVO row = resolve(11, true);
        assertEquals("access-A", row.getAccessKey()); assertEquals(1, row.getRevision());
        verify(admin, never()).createUser(anyString()); verify(details, never()).persist(anyLong(), anyMap());
    }
    @Test public void staleCloudKeyIsReconciledAgainstActualRgwKeys() {
        ObjectStoreAccountCredentialVO row = resolve(11, false); row.setAccessKey("stale"); row.setSecretKey("old");
        row = resolve(11, false); assertEquals("access-A", row.getAccessKey()); assertEquals(2, row.getRevision());
    }
    @Test public void twoStoresKeepIndependentValidKeysForOneAccount() {
        ObjectStoreAccountCredentialVO a = resolve(11, false); ObjectStoreAccountCredentialVO b = resolve(12, false);
        b.setAccessKey("access-B"); b.setSecretKey("secret-B");
        assertEquals("access-A", resolve(11, false).getAccessKey()); assertEquals("access-B", resolve(12, false).getAccessKey());
        assertEquals(2, rows.size()); assertEquals("access-A", a.getAccessKey());
    }
    @Test public void forbiddenNeverMeansMissingUserAndNeverLeaksProviderError() {
        when(admin.getUserInfo("owner")).thenThrow(new RgwAdminException(403, "PRIVATE-key")); rejected(true);
        verify(admin, never()).createUser(anyString());
    }
    @Test public void timeoutNeverCreatesAnotherExternalUser() {
        when(admin.getUserInfo("owner")).thenThrow(new RuntimeException("PRIVATE-timeout")); rejected(true);
        verify(admin, never()).createUser(anyString());
    }
    @Test public void missingUserReadDoesNotProvision() {
        when(admin.getUserInfo("owner")).thenThrow(new RgwAdminException(404)); rejected(false);
        verify(admin, never()).createUser(anyString());
    }
    @Test public void userWithoutKeysIsNotSilentlyRecreated() {
        user.setS3Credentials(Collections.emptyList()); rejected(true); verify(admin, never()).createUser(anyString());
    }
    @Test public void suspendedUserIsNotReportedAsVerified() { user.setSuspended(1); rejected(false); }
    @Test public void wrongUserIdentityIsRejected() { user.setUserId("someone-else"); rejected(false); }
    @Test public void bucketSnapshotIsOnlyUpdatedAfterProviderVerification() {
        BucketVO bucket = new BucketVO(1, 1, 11, "bucket", 1, false, false, false, "Private");
        bucket.setAccessKey("legacy"); bucket.setSecretKey("legacy-secret");
        when(buckets.listByObjectStoreIdAndAccountId(11, 1)).thenReturn(Collections.singletonList(bucket));
        when(buckets.update(anyLong(), any(BucketVO.class))).thenReturn(true);
        resolve(11, false); assertEquals("access-A", bucket.getAccessKey()); verify(buckets).update(bucket.getId(), bucket);
    }
    @Test public void databaseFailureAfterUserCreationDoesNotDeleteExternalUser() {
        when(admin.getUserInfo("owner")).thenThrow(new RgwAdminException(404)).thenReturn(Optional.of(user));
        when(dao.update(anyLong(), any(ObjectStoreAccountCredentialVO.class))).thenReturn(true, false, true);
        rejected(true); verify(admin).createUser("owner"); verify(admin, never()).removeUser(anyString());
        assertEquals("CreatedByCloud", rows.get(11L).getProvisioningSource());
    }
}
