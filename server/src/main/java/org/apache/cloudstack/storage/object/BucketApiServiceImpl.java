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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.command.user.bucket.CreateBucketCmd;
import org.apache.cloudstack.api.command.user.bucket.SyncBucketUsageCmd;
import org.apache.cloudstack.api.command.user.bucket.UpdateBucketCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;

import com.amazonaws.services.s3.internal.BucketNameUtils;
import com.amazonaws.services.s3.model.IllegalBucketNameException;
import com.cloud.agent.api.to.BucketTO;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.resourcelimit.ResourceLimitManagerImpl;
import com.cloud.storage.BucketVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.dao.BucketDao;
import com.cloud.usage.BucketStatisticsVO;
import com.cloud.usage.dao.BucketStatisticsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.datastore.db.BucketQuotaOperationDao;
import org.apache.cloudstack.context.CallContext;
import java.util.Objects;

public class BucketApiServiceImpl extends ManagerBase implements BucketApiService, Configurable {

    @Inject
    private ObjectStoreDao _objectStoreDao;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    private BucketDao _bucketDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private ResourceLimitManagerImpl resourceLimitManager;

    @Inject
    private BucketStatisticsDao _bucketStatisticsDao;
    @Inject
    ReservationDao reservationDao;

    @Inject
    private BucketQuotaOperationDao quotaOperations;

    private BucketQuotaCoordinator quotaCoordinator() {
        return new BucketQuotaCoordinator(_objectStoreDao, _bucketDao, quotaOperations, _dataStoreMgr,
                _accountMgr, resourceLimitManager, reservationDao);
    }

    private ScheduledExecutorService _executor = null;

    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 3;

    protected BucketApiServiceImpl() {

    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        _executor = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Bucket-Usage"));
        return true;
    }

    @Override
    public boolean start() {
        _executor.scheduleWithFixedDelay(new BucketUsageTask(), 60L, 3600L, TimeUnit.SECONDS);
        _executor.scheduleWithFixedDelay(new ManagedContextRunnable() {
            @Override protected void runInContext() {
                CallContext.registerSystemCallContextOnceOnly();
                try {
                    quotaCoordinator().recover();
                    for (ObjectStoreVO store : _objectStoreDao.listObjectStores()) {
                        if ("Glue".equals(store.getProviderName())) {
                            BucketQuotaCoordinator.withStoreLock(store.getId(), () -> {
                                ((org.apache.cloudstack.engine.subsystem.api.storage.ObjectStoreCredentialProvider)
                                    _dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Object).getDriver()).recoverCredentialRotations(store.getId());
                                return true;
                            });
                        }
                    }
                }
                catch (RuntimeException failure) { logger.warn("Bucket quota recovery will retry"); }
                finally { CallContext.unregister(); }
            }
        }, 30L, 30L, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        _executor.shutdown();
        return true;
    }

    @Override
    public String getConfigComponentName() {
        return BucketApiService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                DefaultMaxAccountBuckets,
                DefaultMaxAccountObjectStorage,
                DefaultMaxProjectBuckets,
                DefaultMaxProjectObjectStorage,
                DefaultMaxDomainBuckets,
                DefaultMaxDomainObjectStorage
        };
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_CREATE, eventDescription = "creating bucket", create = true)
    public Bucket allocBucket(CreateBucketCmd cmd) throws ResourceAllocationException {
        try {
            BucketNameUtils.validateBucketName(cmd.getBucketName());
        } catch (IllegalBucketNameException e) {
            logger.error("Invalid Bucket Name: " +cmd.getBucketName(), e);
            throw new InvalidParameterValueException("Invalid Bucket Name: "+e.getMessage());
        }
        return quotaCoordinator().allocate(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_CREATE, eventDescription = "creating bucket", async = true)
    public Bucket createBucket(CreateBucketCmd cmd) {
        return quotaCoordinator().create(cmd.getEntityId());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_DELETE, eventDescription = "deleting bucket")
    public boolean deleteBucket(long bucketId, Account caller) throws ResourceAllocationException {
        Bucket bucket = _bucketDao.findById(bucketId);
        if (bucket == null) {
            throw new InvalidParameterValueException("Unable to find bucket with ID: " + bucketId);
        }
        _accountMgr.checkAccess(caller, null, true, bucket);
        return quotaCoordinator().delete((BucketVO)bucket);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_UPDATE, eventDescription = "updating bucket")
    public boolean updateBucket(UpdateBucketCmd cmd, Account caller) throws ResourceAllocationException {
        BucketVO bucket = _bucketDao.findById(cmd.getId());
        if (bucket == null) { throw new InvalidParameterValueException("Unable to find bucket"); }
        _accountMgr.checkAccess(caller, null, true, bucket);
        if (cmd.isReconcile()) {
            if (cmd.getQuota() != null || cmd.getEncryption() != null || cmd.getVersioning() != null || cmd.getPolicy() != null) {
                throw new InvalidParameterValueException("Reconciliation cannot be combined with property changes");
            }
            return quotaCoordinator().reconcile(bucket, cmd.getExpectedQuotaOperationId());
        }
        boolean otherChange = cmd.getEncryption() != null && cmd.getEncryption() != bucket.isEncryption()
                || cmd.getVersioning() != null && cmd.getVersioning() != bucket.isVersioning()
                || cmd.getPolicy() != null && !Objects.equals(cmd.getPolicy(), bucket.getPolicy());
        boolean quotaChange = cmd.getQuota() != null && !Objects.equals(cmd.getQuota(), bucket.getQuota());
        if (cmd.getQuota() != null) { BucketQuotaCoordinator.quotaBytes(cmd.getQuota()); }
        if (quotaChange && otherChange) {
            throw new InvalidParameterValueException("Apply quota changes separately from other bucket properties");
        }
        if (quotaChange) { return quotaCoordinator().update(bucket, cmd.getQuota()); }
        return BucketQuotaCoordinator.withStoreLock(bucket.getObjectStoreId(), () -> {
            org.apache.cloudstack.storage.datastore.db.BucketQuotaOperationVO pending = quotaOperations.findActiveByBucket(bucket.getId());
            if (pending != null) { throw new BucketQuotaException(pending.getUuid(), true); }
            ObjectStoreEntity objectStore = (ObjectStoreEntity)_dataStoreMgr.getDataStore(bucket.getObjectStoreId(), DataStoreRole.Object);
            BucketTO to = new BucketTO(_bucketDao.findById(bucket.getId()));
            BucketVO update = _bucketDao.createForUpdate(bucket.getId());
            if (cmd.getEncryption() != null && cmd.getEncryption() != bucket.isEncryption()) {
                boolean ok = cmd.getEncryption() ? objectStore.setBucketEncryption(to) : objectStore.deleteBucketEncryption(to);
                if (!ok) { throw new CloudRuntimeException("Unable to update bucket encryption"); }
                update.setEncryption(cmd.getEncryption());
            }
            if (cmd.getVersioning() != null && cmd.getVersioning() != bucket.isVersioning()) {
                boolean ok = cmd.getVersioning() ? objectStore.setBucketVersioning(to) : objectStore.deleteBucketVersioning(to);
                if (!ok) { throw new CloudRuntimeException("Unable to update bucket versioning"); }
                update.setVersioning(cmd.getVersioning());
            }
            if (cmd.getPolicy() != null && !Objects.equals(cmd.getPolicy(), bucket.getPolicy())) {
                objectStore.setBucketPolicy(to, cmd.getPolicy()); update.setPolicy(cmd.getPolicy());
            }
            if (otherChange && !_bucketDao.update(bucket.getId(), update)) { throw new CloudRuntimeException("Unable to update bucket properties"); }
            return true;
        });
    }

    public void getBucketUsage() {
        //ToDo track usage one last time when object store or bucket is removed
        List<ObjectStoreVO> objectStores = _objectStoreDao.listObjectStores();
        for(ObjectStoreVO objectStoreVO: objectStores) {
            ObjectStoreEntity  objectStore = (ObjectStoreEntity)_dataStoreMgr.getDataStore(objectStoreVO.getId(), DataStoreRole.Object);
            Map<String, Long> bucketSizes = objectStore.getAllBucketsUsage();
            List<BucketVO> buckets = _bucketDao.listByObjectStoreId(objectStoreVO.getId());
            for(BucketVO bucket : buckets) {
                Long size = bucketSizes.get(bucket.getName());
                if( size != null){
                    BucketVO update = _bucketDao.createForUpdate(bucket.getId());
                    update.setSize(size);
                    _bucketDao.update(bucket.getId(), update);
                }
            }
        }
    }

    private BucketVO credentialBucket(long id, Account caller) {
        BucketVO bucket = _bucketDao.findById(id);
        if (bucket == null || bucket.getState() != Bucket.State.Created) throw new InvalidParameterValueException("A created bucket is required");
        _accountMgr.checkAccess(caller, null, true, bucket);
        return bucket;
    }
    private org.apache.cloudstack.engine.subsystem.api.storage.ObjectStoreCredentialProvider credentialProvider(Bucket bucket) {
        return (org.apache.cloudstack.engine.subsystem.api.storage.ObjectStoreCredentialProvider)
                _dataStoreMgr.getDataStore(bucket.getObjectStoreId(), DataStoreRole.Object).getDriver();
    }
    private org.apache.cloudstack.api.response.BucketCredentialResponse credentialResponse(BucketVO bucket, BucketCredentials credential, boolean reveal) {
        if (credential.getAccessKey() == null || credential.getSecretKey() == null) {
            throw new InvalidParameterValueException("Unable to verify bucket credentials; check the storage connection and retry");
        }
        ObjectStoreVO store = _objectStoreDao.findById(bucket.getObjectStoreId());
        String url = "Glue".equals(store.getProviderName()) ? store.getUrl().replaceAll("/+$", "") + "/" + bucket.getName() : bucket.getBucketURL();
        return new org.apache.cloudstack.api.response.BucketCredentialResponse(bucket, url, credential, reveal,
                _bucketDao.listByObjectStoreIdAndAccountId(bucket.getObjectStoreId(), bucket.getAccountId()));
    }
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_CREDENTIAL_READ, eventDescription = "read bucket credential metadata or explicitly revealed credentials")
    public org.apache.cloudstack.api.response.BucketCredentialResponse getCredentials(long id, Account caller, boolean reveal) {
        BucketVO bucket = credentialBucket(id, caller);
        CallContext.current().setEventDetails("Bucket Id: " + bucket.getUuid() + ", reveal: " + reveal);
        return credentialResponse(bucket, credentialProvider(bucket).getBucketCredentials(bucket), reveal);
    }
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_BUCKET_CREDENTIAL_ROTATE, eventDescription = "rotate account and store bucket credentials")
    public org.apache.cloudstack.api.response.BucketCredentialResponse rotateCredentials(long id, Account caller, long revision,
            String operationId, String action, int graceSeconds) {
        BucketVO bucket = credentialBucket(id, caller);
        CallContext.current().setEventDetails("Bucket Id: " + bucket.getUuid() + ", revision: " + revision + ", operation: " + operationId);
        return BucketQuotaCoordinator.withStoreLock(bucket.getObjectStoreId(), () -> {
            if (quotaOperations.hasActiveForStore(bucket.getObjectStoreId())) throw new InvalidParameterValueException("Reconcile pending quota operations before rotating keys");
            return credentialResponse(bucket, credentialProvider(bucket).rotateBucketCredentials(bucket, revision, operationId, action, graceSeconds), false);
        });
    }

    @Override
    public Bucket syncBucketUsage(SyncBucketUsageCmd cmd, Account caller) {
        BucketVO bucket = _bucketDao.findById(cmd.getId());
        if (bucket == null) {
            throw new InvalidParameterValueException("Unable to find bucket with ID: " + cmd.getId());
        }
        _accountMgr.checkAccess(caller, null, true, bucket);
        ObjectStoreVO objectStoreVO = _objectStoreDao.findById(bucket.getObjectStoreId());
        if (objectStoreVO == null) {
            throw new InvalidParameterValueException("Unable to find object store with ID: " + bucket.getObjectStoreId());
        }
        ObjectStoreEntity objectStore = (ObjectStoreEntity)_dataStoreMgr.getDataStore(objectStoreVO.getId(), DataStoreRole.Object);
        Map<String, Long> bucketSizes = objectStore.getAllBucketsUsage();
        updateBucketUsage(bucket, bucketSizes.getOrDefault(bucket.getName(), 0L), true);
        return _bucketDao.findById(bucket.getId());
    }

    private void updateBucketUsage(BucketVO bucket, long size, boolean updateStats) {
        BucketVO update = _bucketDao.createForUpdate(bucket.getId());
        update.setSize(size);
        _bucketDao.update(bucket.getId(), update);

        if (!updateStats) {
            return;
        }

        BucketStatisticsVO bucketStatisticsVO = _bucketStatisticsDao.findBy(bucket.getAccountId(), bucket.getId());
        if(bucketStatisticsVO != null) {
            bucketStatisticsVO.setSize(size);
            _bucketStatisticsDao.update(bucketStatisticsVO.getId(), bucketStatisticsVO);
        } else {
            bucketStatisticsVO = new BucketStatisticsVO(bucket.getAccountId(), bucket.getId());
            bucketStatisticsVO.setSize(size);
            _bucketStatisticsDao.persist(bucketStatisticsVO);
        }
    }

    private class BucketUsageTask extends ManagedContextRunnable {
        public BucketUsageTask() {
        }

        @Override
        protected void runInContext() {
            GlobalLock scanLock = GlobalLock.getInternLock("BucketUsage");
            try {
                if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                    try {
                        List<ObjectStoreVO> objectStores = _objectStoreDao.listObjectStores();
                        for(ObjectStoreVO objectStoreVO: objectStores) {
                            logger.debug("Getting bucket usage for Object Store \"{}\"", objectStoreVO.getName());
                            ObjectStoreEntity  objectStore = (ObjectStoreEntity)_dataStoreMgr.getDataStore(objectStoreVO.getId(), DataStoreRole.Object);
                            Map<String, Long> bucketSizes;
                            try {
                                bucketSizes = objectStore.getAllBucketsUsage();
                            } catch (CloudRuntimeException e) {
                                logger.error(String.format("Failed to get bucket usage for Object Store \"%s\". Skipping this store.", objectStoreVO.getName()), e);
                                continue;
                            }
                            List<BucketVO> buckets = _bucketDao.listByObjectStoreId(objectStoreVO.getId());
                            Long objectStoreUsedBytes = 0L;
                            for(BucketVO bucket : buckets) {
                                Long size = bucketSizes.get(bucket.getName());
                                if (size != null) {
                                    objectStoreUsedBytes += size;
                                    updateBucketUsage(bucket, size, true);
                                }
                            }
                            ObjectStoreVO update = _objectStoreDao.createForUpdate(objectStoreVO.getId());
                            update.setUsedSize(objectStoreUsedBytes);
                            _objectStoreDao.update(objectStoreVO.getId(), update);
                        }
                        logger.debug("Completed updating bucket usage for all object stores");
                    } catch (Exception e) {
                        logger.error("Error while fetching bucket usage", e);
                    } finally {
                        scanLock.unlock();
                    }
                }
            } finally {
                scanLock.releaseRef();
            }
        }
    }
}
