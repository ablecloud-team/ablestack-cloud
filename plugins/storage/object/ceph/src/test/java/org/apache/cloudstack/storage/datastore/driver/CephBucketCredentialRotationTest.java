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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import com.cloud.storage.dao.BucketDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialVO;
import org.junit.Before;
import org.junit.Test;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.model.S3Credential;
import org.twonote.rgwadmin4j.model.User;

public class CephBucketCredentialRotationTest {
    private final ObjectStoreAccountCredentialDao dao = mock(ObjectStoreAccountCredentialDao.class);
    private final BucketDao buckets = mock(BucketDao.class);
    private final RgwAdmin admin = mock(RgwAdmin.class);
    private final List<S3Credential> keys = new ArrayList<>();
    private ObjectStoreAccountCredentialVO database;
    private TestRotation service;
    private long clock = 100000L;
    private boolean loseCreate, failActivation, failCompletion, rejectCandidate;
    private class TestRotation extends CephBucketCredentialRotation {
        TestRotation() { super(dao, buckets, id -> admin, (store, account, access, secret) -> { if (rejectCandidate) throw new IllegalStateException("PRIVATE_SECRET"); }, (store, uid, access) -> admin.createS3Credential(uid, access, "provider-generated-secret")); }
        @Override protected Date now() { return new Date(clock); }
        @Override protected void inTransaction(Runnable action) {
            ObjectStoreAccountCredentialVO before = copy(database);
            try { action.run(); } catch (RuntimeException error) { database = before; throw error; }
        }
        ObjectStoreAccountCredentialVO run(long revision, String id, String action) { return rotateLocked(7, 11, revision, id, action, 5); }
    }
    private static ObjectStoreAccountCredentialVO copy(ObjectStoreAccountCredentialVO source) {
        ObjectStoreAccountCredentialVO result = new ObjectStoreAccountCredentialVO();
        try { for (Field f : ObjectStoreAccountCredentialVO.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); f.set(result, f.get(source)); }
        } } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        return result;
    }
    private S3Credential key(String access, String secret) {
        S3Credential key = mock(S3Credential.class); when(key.getAccessKey()).thenReturn(access); when(key.getSecretKey()).thenReturn(secret); return key;
    }
    @Before public void setup() throws Exception {
        database = new ObjectStoreAccountCredentialVO(7, 11, "owner");
        Field id = ObjectStoreAccountCredentialVO.class.getDeclaredField("id"); id.setAccessible(true); id.set(database, 1L);
        database.setAccessKey("old-access"); database.setSecretKey("old-secret"); database.setRevision(1); database.setState("Verified");
        keys.add(key("old-access", "old-secret"));
        User user = mock(User.class); when(user.getUserId()).thenReturn("owner"); when(user.getS3Credentials()).thenAnswer(i -> new ArrayList<>(keys));
        when(admin.getUserInfo("owner")).thenReturn(Optional.of(user));
        when(dao.findByAccountAndStore(7, 11)).thenAnswer(i -> copy(database));
        when(dao.listByAccount(7)).thenAnswer(i -> List.of(copy(database)));
        when(dao.update(anyLong(), any())).thenAnswer(i -> {
            ObjectStoreAccountCredentialVO row = i.getArgument(1);
            if (failActivation && "Grace".equals(row.getRotationPhase())) { failActivation = false; throw new IllegalStateException("PRIVATE_SQL"); }
            if (failCompletion && "Completed".equals(row.getRotationPhase())) { failCompletion = false; throw new IllegalStateException("PRIVATE_SQL"); }
            database = copy(row); return true;
        });
        when(admin.createS3Credential(anyString(), anyString(), anyString())).thenAnswer(i -> {
            assertEquals("Prepared", database.getRotationPhase()); assertNotNull(database.getPendingAccessKey()); assertNull(database.getPendingSecretKey());
            keys.add(key(i.getArgument(1), i.getArgument(2)));
            if (loseCreate) { loseCreate = false; throw new IllegalStateException("response lost"); }
            return new ArrayList<>(keys);
        });
        doAnswer(i -> { keys.removeIf(k -> k.getAccessKey().equals(i.getArgument(1))); return null; }).when(admin).removeS3Credential(anyString(), anyString());
        service = new TestRotation();
    }
    @Test public void stagesBeforeProviderAndActivatesAllAtOnce() {
        ObjectStoreAccountCredentialVO result = service.run(1, null, "rotate");
        assertEquals("Grace", result.getRotationPhase()); assertEquals(2, result.getRevision());
        assertEquals("old-access", result.getPreviousAccessKey()); assertNull(result.getPendingSecretKey()); assertEquals(2, keys.size());
        assertEquals(clock + 5000, result.getRetireAfter().getTime());
    }
    @Test public void lostCreateResponseRetriesSameCandidateWithoutDuplicateKey() {
        loseCreate = true; assertThrows(CloudRuntimeException.class, () -> service.run(1, null, "rotate"));
        assertEquals("old-access", database.getAccessKey()); assertEquals("Prepared", database.getRotationPhase());
        String candidate = database.getPendingAccessKey();
        service.run(1, database.getRotationUuid(), "retry"); assertEquals(candidate, database.getAccessKey()); assertEquals(2, keys.size());
        verify(admin).createS3Credential(anyString(), anyString(), anyString());
    }
    @Test public void databaseFailureKeepsCurrentKeyAndRetryIntent() {
        failActivation = true; assertThrows(CloudRuntimeException.class, () -> service.run(1, null, "rotate"));
        assertEquals("old-access", database.getAccessKey()); assertEquals(1, database.getRevision());
        assertEquals("RecoveryRequired", database.getRotationError()); assertEquals(2, keys.size());
        service.run(1, database.getRotationUuid(), "retry"); assertEquals(2, database.getRevision());
    }
    @Test public void candidateReadFailureNeverActivatesOrDeletesOldKey() {
        rejectCandidate = true; assertThrows(CloudRuntimeException.class, () -> service.run(1, null, "rotate"));
        assertEquals("old-access", database.getAccessKey()); verify(admin, never()).removeS3Credential(anyString(), anyString());
        rejectCandidate = false; service.run(1, database.getRotationUuid(), "retry"); assertEquals("Grace", database.getRotationPhase());
    }
    @Test public void refusesEarlyRetirement() {
        service.run(1, null, "rotate");
        assertThrows(IllegalArgumentException.class, () -> service.run(2, database.getRotationUuid(), "retire"));
        assertEquals(2, keys.size()); verify(admin, never()).removeS3Credential(anyString(), anyString());
    }
    @Test public void expiresOnlyTheTrackedOldKey() {
        service.run(1, null, "rotate"); keys.add(key("unrelated", "keep")); clock += 5001;
        service.run(2, database.getRotationUuid(), "retire");
        assertEquals("Completed", database.getRotationPhase()); assertNull(database.getPreviousSecretKey());
        assertEquals(2, keys.size()); assertTrue(keys.stream().anyMatch(k -> k.getAccessKey().equals("unrelated")));
        assertFalse(keys.stream().anyMatch(k -> k.getAccessKey().equals("old-access")));
    }
    @Test public void retirementDbFailureIsIdempotentAfterExternalDeletion() {
        service.run(1, null, "rotate"); clock += 5001; failCompletion = true;
        assertThrows(CloudRuntimeException.class, () -> service.run(2, database.getRotationUuid(), "retire"));
        assertEquals("Retiring", database.getRotationPhase()); assertEquals(1, keys.size());
        service.run(2, database.getRotationUuid(), "retry"); assertEquals("Completed", database.getRotationPhase());
        verify(admin).removeS3Credential("owner", "old-access");
    }
    @Test public void missingNewKeyNeverRevokesTheStillWorkingOldKey() {
        service.run(1, null, "rotate"); keys.removeIf(k -> !k.getAccessKey().equals("old-access")); clock += 5001;
        assertThrows(CloudRuntimeException.class, () -> service.run(2, database.getRotationUuid(), "retire"));
        assertEquals("old-access", keys.get(0).getAccessKey()); verify(admin, never()).removeS3Credential(anyString(), anyString());
    }
    @Test public void staleRevisionAndWrongOperationCannotWrite() {
        assertThrows(IllegalArgumentException.class, () -> service.run(0, null, "rotate"));
        assertThrows(IllegalArgumentException.class, () -> service.run(1, "wrong", "retry"));
        verify(admin, never()).createS3Credential(anyString(), anyString(), anyString());
    }
    @Test public void secondRotationDuringGraceIsRejected() {
        service.run(1, null, "rotate");
        assertThrows(IllegalArgumentException.class, () -> service.run(2, null, "rotate")); assertEquals(2, keys.size());
    }
    @Test public void sharedAliasKeyCannotBeRotatedOrRetired() {
        ObjectStoreAccountCredentialVO alias = new ObjectStoreAccountCredentialVO(7, 12, "owner"); alias.setAccessKey("old-access");
        when(dao.listByAccount(7)).thenAnswer(i -> List.of(copy(database), alias));
        assertThrows(IllegalArgumentException.class, () -> service.run(1, null, "rotate")); assertNull(database.getRotationUuid());
    }
    @Test public void providerErrorsNeverExposeKeyOrSql() {
        failActivation = true;
        String error = assertThrows(CloudRuntimeException.class, () -> service.run(1, null, "rotate")).getMessage();
        assertFalse(error.contains("PRIVATE_")); assertFalse(error.contains("old-secret"));
    }
}
