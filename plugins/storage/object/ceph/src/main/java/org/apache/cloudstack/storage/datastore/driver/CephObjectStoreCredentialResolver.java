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

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.Account;
import com.cloud.user.AccountDetailVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialVO;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.impl.RgwAdminException;
import org.twonote.rgwadmin4j.model.S3Credential;
import org.twonote.rgwadmin4j.model.User;

/** Resolves one account/store pair against RGW without modifying account_details or rotating keys. */
public class CephObjectStoreCredentialResolver {
    private final AccountDao accounts;
    private final AccountDetailsDao legacyDetails;
    private final BucketDao buckets;
    private final ObjectStoreAccountCredentialDao credentials;
    private final LongFunction<RgwAdmin> admins;

    public CephObjectStoreCredentialResolver(AccountDao accounts, AccountDetailsDao legacyDetails, BucketDao buckets,
            ObjectStoreAccountCredentialDao credentials, LongFunction<RgwAdmin> admins) {
        this.accounts = accounts; this.legacyDetails = legacyDetails; this.buckets = buckets;
        this.credentials = credentials; this.admins = admins;
    }

    public ObjectStoreAccountCredentialVO resolve(long accountId, long storeId, boolean allowCreate) {
        GlobalLock lock = GlobalLock.getInternLock("object-store-credential-" + accountId + "-" + storeId);
        boolean acquired = false;
        try {
            acquired = lock.lock(10);
            if (!acquired) throw failure(storeId);
            return resolveLocked(accountId, storeId, allowCreate);
        } finally {
            if (acquired) lock.unlock();
            lock.releaseRef();
        }
    }

    // The distributed pair lock also serializes independent management servers.
    protected ObjectStoreAccountCredentialVO resolveLocked(long accountId, long storeId, boolean allowCreate) {
        Account account = accounts.findById(accountId);
        if (account == null || account.getRemoved() != null) throw failure(storeId);
        ObjectStoreAccountCredentialVO row = credentials.findByAccountAndStore(accountId, storeId);
        if (row == null) {
            row = credentials.persist(new ObjectStoreAccountCredentialVO(accountId, storeId, account.getUuid()));
            if (row == null) throw failure(storeId);
        }
        try {
            RgwAdmin admin = admins.apply(storeId);
            Optional<User> found = lookup(admin, account.getUuid());
            if (found.isEmpty()) {
                if (!allowCreate) throw failure(storeId);
                row.setProvisioningSource("CreateRequested"); save(row);
                try {
                    admin.createUser(account.getUuid());
                    row.setProvisioningSource("CreatedByCloud");
                } catch (RgwAdminException e) {
                    if (e.status() != 409) throw failure(storeId);
                    row.setProvisioningSource("Existing");
                }
                found = lookup(admin, account.getUuid());
            }
            User user = found.orElseThrow(() -> failure(storeId));
            if (!account.getUuid().equals(user.getUserId()) || (user.getSuspended() != null && user.getSuspended() != 0)) throw failure(storeId);
            List<S3Credential> keys = user.getS3Credentials();
            if (keys == null || keys.isEmpty()) throw failure(storeId);
            S3Credential selected = choose(keys, row, storeId, accountId);
            boolean changed = !Objects.equals(row.getAccessKey(), selected.getAccessKey())
                    || !Objects.equals(row.getSecretKey(), selected.getSecretKey());
            row.setAccessKey(selected.getAccessKey()); row.setSecretKey(selected.getSecretKey());
            row.setRevision(row.getRevision() + (changed ? 1 : 0)); row.setState("Verified"); row.setChecked(new Date());
            ObjectStoreAccountCredentialVO verified = row;
            inTransaction(() -> {
                save(verified);
                // Compatibility projection only. Provider verification remains the authority.
                for (BucketVO bucket : buckets.listByObjectStoreIdAndAccountId(storeId, accountId)) {
                    if (!Objects.equals(bucket.getAccessKey(), verified.getAccessKey()) || !Objects.equals(bucket.getSecretKey(), verified.getSecretKey())) {
                        bucket.setAccessKey(verified.getAccessKey()); bucket.setSecretKey(verified.getSecretKey());
                        if (!buckets.update(bucket.getId(), bucket)) throw failure(storeId);
                    }
                }
            });
            return row;
        } catch (RuntimeException e) {
            row.setState("NeedsVerification"); row.setChecked(new Date());
            // Preserve evidence of a requested/new external user. Never compensate by deleting RGW users.
            try { save(row); } catch (RuntimeException ignored) { /* The caller receives a safe failure. */ }
            throw failure(storeId);
        }
    }

    protected void inTransaction(Runnable task) {
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) { task.run(); }
        });
    }

    private Optional<User> lookup(RgwAdmin admin, String uid) {
        try { return admin.getUserInfo(uid); }
        catch (RgwAdminException e) {
            if (e.status() == 404) return Optional.empty();
            throw e;
        }
    }

    private S3Credential choose(List<S3Credential> keys, ObjectStoreAccountCredentialVO row, long storeId, long accountId) {
        Optional<S3Credential> existing = matching(keys, row.getAccessKey(), row.getSecretKey());
        if (existing.isPresent()) return existing.get();
        for (BucketVO bucket : buckets.listByObjectStoreIdAndAccountId(storeId, accountId)) {
            existing = matching(keys, bucket.getAccessKey(), bucket.getSecretKey());
            if (existing.isPresent()) return existing.get();
        }
        AccountDetailVO access = legacyDetails.findDetail(accountId, "ceph-rgw-accesskey");
        AccountDetailVO secret = legacyDetails.findDetail(accountId, "ceph-rgw-secretkey");
        existing = matching(keys, access == null ? null : access.getValue(), secret == null ? null : secret.getValue());
        return existing.orElseGet(() -> keys.stream().filter(this::complete).findFirst().orElseThrow(() -> failure(storeId)));
    }
    private Optional<S3Credential> matching(List<S3Credential> keys, String access, String secret) {
        return keys.stream().filter(this::complete).filter(k -> Objects.equals(access, k.getAccessKey()) && Objects.equals(secret, k.getSecretKey())).findFirst();
    }
    private boolean complete(S3Credential key) {
        return key != null && key.getAccessKey() != null && !key.getAccessKey().isBlank()
                && key.getSecretKey() != null && !key.getSecretKey().isBlank();
    }
    private void save(ObjectStoreAccountCredentialVO row) {
        if (!credentials.update(row.getId(), row)) throw failure(row.getObjectStoreId());
    }
    private CloudRuntimeException failure(long storeId) {
        return new CloudRuntimeException("Unable to verify credentials for object storage " + storeId + "; retry after checking the storage connection");
    }
}
