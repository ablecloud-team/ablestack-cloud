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

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongFunction;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialVO;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.model.S3Credential;
import org.twonote.rgwadmin4j.model.User;

/** Persist candidate intent before RGW; never replace/revoke unrelated keys. */
public class CephBucketCredentialRotation {
    @FunctionalInterface public interface Verifier { void verify(long storeId, long accountId, String access, String secret); }
    @FunctionalInterface public interface Creator { void create(long storeId, String uid, String candidateAccess); }
    private final Creator creator;
    private final ObjectStoreAccountCredentialDao credentials;
    private final BucketDao buckets;
    private final LongFunction<RgwAdmin> admins;
    private final Verifier verifier;
    private final SecureRandom random = new SecureRandom();
    public CephBucketCredentialRotation(ObjectStoreAccountCredentialDao credentials, BucketDao buckets,
            LongFunction<RgwAdmin> admins, Verifier verifier, Creator creator) {
        this.credentials = credentials; this.buckets = buckets; this.admins = admins; this.verifier = verifier; this.creator = creator;
    }
    public ObjectStoreAccountCredentialVO rotate(long account, long store, long revision, String id, String action, int grace) {
        if (!List.of("rotate", "retry", "retire").contains(action) || grace < 5 || grace > 86400) {
            throw new IllegalArgumentException("Use rotate, retry or retire and a grace period of 5..86400 seconds");
        }
        GlobalLock lock = GlobalLock.getInternLock("object-store-credential-" + account + "-" + store);
        boolean acquired = false;
        try {
            acquired = lock.lock(10);
            if (!acquired) throw failure();
            return rotateLocked(account, store, revision, id, action, grace);
        } finally { if (acquired) lock.unlock(); lock.releaseRef(); }
    }
    protected ObjectStoreAccountCredentialVO rotateLocked(long account, long store, long revision, String id, String action, int grace) {
        ObjectStoreAccountCredentialVO row = credentials.findByAccountAndStore(account, store);
        if (row == null || revision != row.getRevision()) throw new IllegalArgumentException("Credential revision changed; refresh before retrying");
        if ("rotate".equals(action)) {
            if (row.getRotationPhase() != null && !"Completed".equals(row.getRotationPhase())) {
                throw new IllegalArgumentException("Finish the existing credential rotation first");
            }
            requireKey(keys(row), row.getAccessKey(), row.getSecretKey());
            requireUnshared(row, row.getAccessKey());
            row.setRotationUuid(UUID.randomUUID().toString()); row.setRotationPhase("Prepared");
            row.setRotationGraceSeconds(grace); row.setRotationError(null); row.setRetireAfter(null);
            row.setPendingAccessKey(randomKey(20)); row.setPendingSecretKey(null);
            save(row); // Durable intent precedes external side effects.
        } else if (id == null || !Objects.equals(id, row.getRotationUuid())) {
            throw new IllegalArgumentException("The current rotation ID is required for retry or retirement");
        }
        if ("retire".equals(action) && "Prepared".equals(row.getRotationPhase())) {
            throw new IllegalArgumentException("Candidate activation must complete before old-key retirement");
        }
        if (List.of("Grace", "Retiring").contains(Objects.toString(row.getRotationPhase(), "")) &&
                row.getRetireAfter() != null && now().before(row.getRetireAfter())) {
            if ("retire".equals(action)) throw new IllegalArgumentException("The old-key grace period has not elapsed");
            return row;
        }
        try {
            if ("Prepared".equals(row.getRotationPhase())) activate(row);
            row = credentials.findByAccountAndStore(account, store);
            if (("Grace".equals(row.getRotationPhase()) || "Retiring".equals(row.getRotationPhase())) &&
                    row.getRetireAfter() != null && !now().before(row.getRetireAfter())) retire(row);
            return credentials.findByAccountAndStore(account, store);
        } catch (RuntimeException error) {
            // A rolled-back Java instance is not authoritative. Retain both key intents from the database.
            ObjectStoreAccountCredentialVO current = credentials.findByAccountAndStore(account, store);
            if (current != null) {
                current.setRotationError("RecoveryRequired");
                try { save(current); } catch (RuntimeException ignored) { /* Persisted stage still allows retry. */ }
            }
            throw failure();
        }
    }
    private void activate(ObjectStoreAccountCredentialVO row) {
        List<S3Credential> keys = keys(row);
        requireKey(keys, row.getAccessKey(), row.getSecretKey());
        S3Credential candidate = keys.stream().filter(k -> Objects.equals(k.getAccessKey(), row.getPendingAccessKey())).findFirst().orElse(null);
        if (candidate == null) {
            creator.create(row.getObjectStoreId(), row.getRgwUserId(), row.getPendingAccessKey());
            candidate = keys(row).stream().filter(k -> Objects.equals(k.getAccessKey(), row.getPendingAccessKey())).findFirst().orElseThrow(this::failure);
        }
        if (candidate.getSecretKey() == null || candidate.getSecretKey().isBlank() ||
                (row.getPendingSecretKey() != null && !Objects.equals(row.getPendingSecretKey(), candidate.getSecretKey()))) throw failure();
        row.setPendingSecretKey(candidate.getSecretKey()); save(row);
        verifier.verify(row.getObjectStoreId(), row.getAccountId(), row.getPendingAccessKey(), row.getPendingSecretKey());
        inTransaction(() -> {
            ObjectStoreAccountCredentialVO update = credentials.findByAccountAndStore(row.getAccountId(), row.getObjectStoreId());
            if (!"Prepared".equals(update.getRotationPhase()) || !Objects.equals(row.getRotationUuid(), update.getRotationUuid())) throw failure();
            update.setPreviousAccessKey(update.getAccessKey()); update.setPreviousSecretKey(update.getSecretKey());
            update.setAccessKey(update.getPendingAccessKey()); update.setSecretKey(update.getPendingSecretKey());
            update.setPendingAccessKey(null); update.setPendingSecretKey(null); update.setRevision(update.getRevision() + 1);
            update.setRotationPhase("Grace"); update.setRotationError(null); update.setState("Verified"); update.setChecked(now());
            update.setRetireAfter(new Date(now().getTime() + update.getRotationGraceSeconds().longValue() * 1000));
            save(update);
            for (BucketVO bucket : buckets.listByObjectStoreIdAndAccountId(update.getObjectStoreId(), update.getAccountId())) {
                BucketVO projection = buckets.createForUpdate(bucket.getId());
                projection.setAccessKey(update.getAccessKey()); projection.setSecretKey(update.getSecretKey());
                if (!buckets.update(bucket.getId(), projection)) throw failure();
            }
        });
    }
    private void retire(ObjectStoreAccountCredentialVO row) {
        requireKey(keys(row), row.getAccessKey(), row.getSecretKey());
        verifier.verify(row.getObjectStoreId(), row.getAccountId(), row.getAccessKey(), row.getSecretKey());
        String oldAccess = row.getPreviousAccessKey();
        if (oldAccess == null || Objects.equals(oldAccess, row.getAccessKey())) throw failure();
        requireUnshared(row, oldAccess);
        row.setRotationPhase("Retiring"); save(row);
        List<S3Credential> keys = keys(row);
        if (keys.stream().anyMatch(k -> Objects.equals(oldAccess, k.getAccessKey()))) {
            requireKey(keys, oldAccess, row.getPreviousSecretKey());
            admins.apply(row.getObjectStoreId()).removeS3Credential(row.getRgwUserId(), oldAccess);
        }
        if (keys(row).stream().anyMatch(k -> Objects.equals(oldAccess, k.getAccessKey()))) throw failure();
        row.setPreviousAccessKey(null); row.setPreviousSecretKey(null); row.setRotationError(null);
        row.setRotationPhase("Completed"); save(row);
    }
    private void requireUnshared(ObjectStoreAccountCredentialVO row, String access) {
        for (BucketVO other : buckets.listByAccountId(row.getAccountId())) {
            if (other.getObjectStoreId() != row.getObjectStoreId() && Objects.equals(access, other.getAccessKey())) {
                throw new IllegalArgumentException("This key is referenced by a bucket in another object store; separate that credential before rotating");
            }
        }
        for (ObjectStoreAccountCredentialVO other : credentials.listByAccount(row.getAccountId())) {
            if (other.getObjectStoreId() != row.getObjectStoreId() &&
                    (Objects.equals(access, other.getAccessKey()) || Objects.equals(access, other.getPreviousAccessKey()) ||
                    Objects.equals(access, other.getPendingAccessKey()))) {
                throw new IllegalArgumentException("This key is shared by another object store; separate that credential before rotating");
            }
        }
    }
    private List<S3Credential> keys(ObjectStoreAccountCredentialVO row) {
        User user = admins.apply(row.getObjectStoreId()).getUserInfo(row.getRgwUserId()).orElseThrow(this::failure);
        if (!row.getRgwUserId().equals(user.getUserId()) || (user.getSuspended() != null && user.getSuspended() != 0) || user.getS3Credentials() == null) throw failure();
        return user.getS3Credentials();
    }
    private boolean hasKey(List<S3Credential> keys, String access, String secret) {
        return access != null && secret != null && keys.stream().anyMatch(k -> Objects.equals(access, k.getAccessKey()) && Objects.equals(secret, k.getSecretKey()));
    }
    private void requireKey(List<S3Credential> keys, String access, String secret) { if (!hasKey(keys, access, secret)) throw failure(); }
    protected Date now() { return new Date(); }
    protected void inTransaction(Runnable action) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) { action.run(); }
        });
    }
    private String randomKey(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }
    private void save(ObjectStoreAccountCredentialVO row) { if (!credentials.update(row.getId(), row)) throw failure(); }
    private CloudRuntimeException failure() { return new CloudRuntimeException("Credential rotation requires recovery; the previous connection is retained until activation. Refresh and retry the same rotation ID"); }
}
