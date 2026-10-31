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
package org.apache.cloudstack.storage.datastore.driver;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.model.BucketCrossOriginConfiguration;
import com.amazonaws.services.s3.model.CORSRule;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.amazonaws.services.s3.model.BucketVersioningConfiguration;
import com.amazonaws.services.s3.model.DeleteBucketPolicyRequest;
import com.amazonaws.services.s3.model.SetBucketPolicyRequest;
import com.amazonaws.services.s3.model.GetBucketPolicyRequest;
import com.amazonaws.services.s3.model.SetBucketVersioningConfigurationRequest;
import com.cloud.agent.api.to.BucketTO;
import com.cloud.agent.api.to.DataStoreTO;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.BucketCredentials;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreAccountCredentialVO;
import com.cloud.storage.BucketVO;
import com.cloud.storage.dao.BucketDao;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.BaseObjectStoreDriverImpl;
import org.apache.cloudstack.storage.object.BucketObject;
import org.apache.commons.lang3.ObjectUtils;
import org.twonote.rgwadmin4j.model.BucketInfo;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.RgwAdminBuilder;

public class CephObjectStoreDriverImpl extends BaseObjectStoreDriverImpl implements org.apache.cloudstack.storage.object.ObjectStoreQuotaProvider {

    @Inject
    AccountDao _accountDao;

    @Inject
    AccountDetailsDao _accountDetailsDao;

    @Inject
    ObjectStoreDao _storeDao;

    @Inject
    BucketDao _bucketDao;

    @Inject
    ObjectStoreDetailsDao _storeDetailsDao;

    @Inject
    ObjectStoreAccountCredentialDao credentialDao;

    private static final String ACCESS_KEY = "accesskey";
    private static final String SECRET_KEY = "secretkey";


    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public Bucket createBucket(Bucket bucket, boolean objectLock) {
        String bucketName = bucket.getName();
        long storeId = bucket.getObjectStoreId();
        long accountId = bucket.getAccountId();
        AmazonS3 s3client = getS3Client(storeId, accountId);

        try {
            if (s3client.doesBucketExistV2(bucketName)) {
                throw new CloudRuntimeException("Bucket already exists with the name: " + bucketName);
            }
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() != 404) {
                throw new CloudRuntimeException(e);
            }
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
        try {
            s3client.createBucket(bucketName);
            ObjectStoreAccountCredentialVO credential = credentialResolver().resolve(accountId, storeId, false);
            String accessKey = credential.getAccessKey();
            String secretKey = credential.getSecretKey();
            ObjectStoreVO store = _storeDao.findById(storeId);
            BucketVO bucketVO = _bucketDao.createForUpdate(bucket.getId());
            bucketVO.setAccessKey(accessKey);
            bucketVO.setSecretKey(secretKey);
            bucketVO.setBucketURL(store.getUrl() + "/" + bucketName);
            _bucketDao.update(bucket.getId(), bucketVO);

            // Create two CORS rules.
            List<CORSRule.AllowedMethods> ruleAM = new ArrayList<CORSRule.AllowedMethods>();
            ruleAM.add(CORSRule.AllowedMethods.GET);
            ruleAM.add(CORSRule.AllowedMethods.PUT);
            ruleAM.add(CORSRule.AllowedMethods.POST);
            ruleAM.add(CORSRule.AllowedMethods.HEAD);
            ruleAM.add(CORSRule.AllowedMethods.DELETE);
            CORSRule rule = new CORSRule().withId("CORSRule")
                    .withAllowedMethods(ruleAM)
                    .withAllowedHeaders(Arrays.asList("*"))
                    .withAllowedOrigins(Arrays.asList("*"))
                    .withMaxAgeSeconds(3000)
                    .withExposedHeaders(Arrays.asList("ETag", "x-amz-server-side-encryption", "x-amz-request-id", "x-amz-id-2"));

                    List<CORSRule> rules = new ArrayList<CORSRule>();
            rules.add(rule);

            // Add the rules to a new CORS configuration.
            BucketCrossOriginConfiguration configuration = new BucketCrossOriginConfiguration();
            configuration.setRules(rules);

            try {
                // Add the configuration to the bucket.
                s3client.setBucketCrossOriginConfiguration(bucketName, configuration);
                configuration = s3client.getBucketCrossOriginConfiguration(bucketName);
                logger.debug("Configuration has " + configuration.getRules().size() + " rules\n");

                for (CORSRule r : configuration.getRules()) {
                    logger.debug("Rule ID: " + r.getId());
                    logger.debug("MaxAgeSeconds: " + r.getMaxAgeSeconds());
                    logger.debug("AllowedMethod: " + r.getAllowedMethods());
                    logger.debug("AllowedOrigins: " + r.getAllowedOrigins());
                    logger.debug("AllowedHeaders: " + r.getAllowedHeaders());
                    logger.debug("ExposeHeader: " + r.getExposedHeaders());
                }

            } catch (AmazonServiceException e) {
                throw new CloudRuntimeException(e);
            } catch (SdkClientException e) {
                throw new CloudRuntimeException(e);
            }

            return bucket;
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public List<Bucket> listBuckets(long storeId) {
        RgwAdmin rgwAdmin = getRgwAdminClient(storeId);
        List<Bucket> bucketsList = new ArrayList<>();
        try {
            List<String> buckets = rgwAdmin.listBucket();
            for(String name : buckets) {
                Bucket bucket = new BucketObject();
                bucket.setName(name);
                bucketsList.add(bucket);
            }
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
        return bucketsList;
    }

    @Override
    public boolean deleteBucket(BucketTO bucket, long storeId) {
        RgwAdmin rgwAdmin = getRgwAdminClient(storeId);

        try {
            rgwAdmin.removeBucket(bucket.getName());
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
        return true;
    }

    @Override
    public AccessControlList getBucketAcl(BucketTO bucket, long storeId) {
        return null;
    }

    @Override
    public void setBucketAcl(BucketTO bucket, AccessControlList acl, long storeId) {

    }

    @Override
    public void setBucketPolicy(BucketTO bucket, String policy, long storeId) {
        String policyConfig;

        if (policy.equalsIgnoreCase("public")) {
            logger.debug("Setting public policy on bucket " + bucket.getName());
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            builder.append("    \"Statement\": [\n");
            builder.append("        {\n");
            builder.append("            \"Action\": [\n");
            builder.append("                \"s3:GetBucketLocation\",\n");
            builder.append("                \"s3:ListBucket\"\n");
            builder.append("            ],\n");
            builder.append("            \"Effect\": \"Allow\",\n");
            builder.append("            \"Principal\": \"*\",\n");
            builder.append("            \"Resource\": \"arn:aws:s3:::" + bucket.getName() + "\"\n");
            builder.append("        },\n");
            builder.append("        {\n");
            builder.append("            \"Action\": \"s3:GetObject\",\n");
            builder.append("            \"Effect\": \"Allow\",\n");
            builder.append("            \"Principal\": \"*\",\n");
            builder.append("            \"Resource\": \"arn:aws:s3:::" + bucket.getName() + "/*\"\n");
            builder.append("        }\n");
            builder.append("    ],\n");
            builder.append("    \"Version\": \"2012-10-17\"\n");
            builder.append("}\n");
            policyConfig = builder.toString();
        } else {
            logger.debug("Setting private policy on bucket " + bucket.getName());
            policyConfig = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        }

        AmazonS3 client = getS3Client(storeId, bucket.getAccountId());
        client.setBucketPolicy(new SetBucketPolicyRequest(bucket.getName(), policyConfig));
    }

    @Override
    public BucketPolicy getBucketPolicy(BucketTO bucket, long storeId) {
        AmazonS3 client = getS3Client(storeId, bucket.getAccountId());
        return client.getBucketPolicy(new GetBucketPolicyRequest(bucket.getName()));
    }

    @Override
    public void deleteBucketPolicy(BucketTO bucket, long storeId) {
        AmazonS3 client = getS3Client(storeId, bucket.getAccountId());
        client.deleteBucketPolicy(new DeleteBucketPolicyRequest(bucket.getName()));
    }

    private CephBucketQuotaClient quotaClient(long storeId) {
        Map<String, String> details = _storeDetailsDao.getDetails(storeId);
        return new CephBucketQuotaClient(_storeDao.findById(storeId).getUrl(), details.get(ACCESS_KEY), details.get(SECRET_KEY));
    }

    @Override
    public org.apache.cloudstack.storage.object.BucketQuotaState readBucketQuota(Bucket bucket) {
        return quotaClient(bucket.getObjectStoreId()).read(bucket.getName(), _accountDao.findById(bucket.getAccountId()).getUuid());
    }

    @Override
    public void writeBucketQuota(Bucket bucket, org.apache.cloudstack.storage.object.BucketQuotaState quota) {
        quotaClient(bucket.getObjectStoreId()).write(bucket.getName(), _accountDao.findById(bucket.getAccountId()).getUuid(), quota);
    }

    @Override
    public void deleteEmptyBucket(Bucket bucket) {
        // Standard S3 delete refuses live objects, versions and delete markers. Never use admin purge here.
        getS3Client(bucket.getObjectStoreId(), bucket.getAccountId()).deleteBucket(bucket.getName());
    }

    protected CephObjectStoreCredentialResolver credentialResolver() {
        return new CephObjectStoreCredentialResolver(_accountDao, _accountDetailsDao, _bucketDao, credentialDao, this::getRgwAdminClient);
    }

    @Override
    public boolean createUser(long accountId, long storeId) {
        credentialResolver().resolve(accountId, storeId, true);
        return true;
    }

    @Override
    public BucketCredentials getBucketCredentials(Bucket bucket) {
        try {
            ObjectStoreAccountCredentialVO row = credentialResolver().resolve(bucket.getAccountId(), bucket.getObjectStoreId(), false);
            return new BucketCredentials(row.getAccessKey(), row.getSecretKey(), row.getState(), row.getChecked())
                    .withRotation(row.getRevision(), row.getRotationUuid(), row.getRotationPhase(), row.getRotationError() != null, row.getRetireAfter());
        } catch (CloudRuntimeException e) {
            return new BucketCredentials(null, null, "NeedsVerification", null);
        }
    }

    private CephBucketCredentialRotation rotationManager() {
        return new CephBucketCredentialRotation(credentialDao, _bucketDao, this::getRgwAdminClient, (store, account, access, secret) -> {
            AmazonS3 client = getS3Client(getStoreURL(store), access, secret);
            try {
                for (BucketVO bucket : _bucketDao.listByObjectStoreIdAndAccountId(store, account)) {
                    if (bucket.getState() == Bucket.State.Created) {
                        client.listObjectsV2(new com.amazonaws.services.s3.model.ListObjectsV2Request().withBucketName(bucket.getName()).withMaxKeys(1));
                    }
                }
            } finally { client.shutdown(); }
        }, (store, uid, candidate) -> {
            Map<String, String> details = _storeDetailsDao.getDetails(store);
            new CephBucketKeyClient(getStoreURL(store), details.get(ACCESS_KEY), details.get(SECRET_KEY)).create(uid, candidate);
        });
    }

    @Override
    public BucketCredentials rotateBucketCredentials(Bucket bucket, long revision, String operationId, String action, int graceSeconds) {
        credentialResolver().resolve(bucket.getAccountId(), bucket.getObjectStoreId(), false);
        rotationManager().rotate(bucket.getAccountId(), bucket.getObjectStoreId(), revision, operationId, action, graceSeconds);
        return getBucketCredentials(bucket);
    }

    @Override
    public void recoverCredentialRotations(long storeId) {
        for (ObjectStoreAccountCredentialVO row : credentialDao.listByStore(storeId)) {
            if (("Grace".equals(row.getRotationPhase()) || "Retiring".equals(row.getRotationPhase())) &&
                    row.getRetireAfter() != null && !new java.util.Date().before(row.getRetireAfter())) {
                try { rotationManager().rotate(row.getAccountId(), storeId, row.getRevision(), row.getRotationUuid(), "retry", 300); }
                catch (RuntimeException error) { logger.warn("Credential retirement requires retry for account {} and store {}", row.getAccountId(), storeId); }
            }
        }
    }

    @Override
    public void validateAdminCredentials(String url, Map<String, String> details) {
        try {
            new RgwAdminBuilder().accessKey(details.get(ACCESS_KEY)).secretKey(details.get(SECRET_KEY)).endpoint(url.replaceAll("/+$", "") + "/admin").build().listBucket();
        } catch (RuntimeException error) {
            throw new CloudRuntimeException("Unable to verify candidate administrator credentials; previous connection is retained");
        }
    }

    @Override
    public boolean setBucketEncryption(BucketTO bucket, long storeId) {
        return false;
    }

    @Override
    public boolean deleteBucketEncryption(BucketTO bucket, long storeId) {
        return false;
    }

    @Override
    public boolean setBucketVersioning(BucketTO bucket, long storeId) {
        AmazonS3 client = getS3Client(storeId, bucket.getAccountId());
        try {
            BucketVersioningConfiguration configuration =
                    new BucketVersioningConfiguration().withStatus("Enabled");

            SetBucketVersioningConfigurationRequest setBucketVersioningConfigurationRequest =
                    new SetBucketVersioningConfigurationRequest(bucket.getName(), configuration);
            client.setBucketVersioningConfiguration(setBucketVersioningConfigurationRequest);
            return true;
        } catch (AmazonS3Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public boolean deleteBucketVersioning(BucketTO bucket, long storeId) {
        AmazonS3 client = getS3Client(storeId, bucket.getAccountId());
        try {
            BucketVersioningConfiguration configuration =
                    new BucketVersioningConfiguration().withStatus("Suspended");

            SetBucketVersioningConfigurationRequest setBucketVersioningConfigurationRequest =
                    new SetBucketVersioningConfigurationRequest(bucket.getName(), configuration);

            client.setBucketVersioningConfiguration(setBucketVersioningConfigurationRequest);
            return true;
        } catch (AmazonS3Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public void setBucketQuota(BucketTO bucket, long storeId, long size) {
        RgwAdmin rgwAdmin = getRgwAdminClient(storeId);

        try {
            rgwAdmin.setIndividualBucketQuota(null, bucket.getName(), -1, size * 1024 * 1024);
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public Map<String, Long> getAllBucketsUsage(long storeId) {
        RgwAdmin rgwAdmin = getRgwAdminClient(storeId);
        try {
            List<BucketInfo> bucketinfo = rgwAdmin.listBucketInfo();
            Map<String, Long> bucketsusage = new HashMap<String, Long>();
            for (BucketInfo bucket: bucketinfo) {
                BucketInfo.Usage usage = bucket.getUsage();
                if (!ObjectUtils.isEmpty(usage) && usage.getRgwMain() != null){
                    bucketsusage.put(bucket.getBucket(), usage.getRgwMain().getSize_kb());
                }
            }
            return bucketsusage;
        } catch (Exception e) {
            throw new CloudRuntimeException(e);
        }
    }

    protected RgwAdmin getRgwAdminClient(long storeId) {
        ObjectStoreVO store = _storeDao.findById(storeId);
        Map<String, String> storeDetails = _storeDetailsDao.getDetails(storeId);
        String url = store.getUrl();
        String accessKey = storeDetails.get(ACCESS_KEY);
        String secretKey = storeDetails.get(SECRET_KEY);
        RgwAdmin admin = new RgwAdminBuilder()
                .accessKey(accessKey)
                .secretKey(secretKey)
                .endpoint(url + "/admin")
                .build();
        if (admin == null) {
            throw new CloudRuntimeException("Error while creating Ceph RGW client");
        }
        return admin;
    }

    private String getStoreURL(long storeId) {
        ObjectStoreVO store = _storeDao.findById(storeId);
        String url = store.getUrl();
        return url;
    }

    protected AmazonS3 getS3Client(long storeId, long accountId) {
        String url = getStoreURL(storeId);
        ObjectStoreAccountCredentialVO credential = credentialResolver().resolve(accountId, storeId, false);
        String accessKey = credential.getAccessKey();
        String secretKey = credential.getSecretKey();
        return this.getS3Client(url, accessKey, secretKey);
    }
    protected AmazonS3 getS3Client(String url, String accessKey, String secretKey) {
        AmazonS3 client = AmazonS3ClientBuilder.standard()
                .enablePathStyleAccess()
                .withCredentials(
                        new AWSStaticCredentialsProvider(
                                new BasicAWSCredentials(accessKey, secretKey)))
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(url, "us-east-1"))
                .build();

        if (client == null) {
            throw new CloudRuntimeException("Error while creating Ceph RGW S3 client");
        }
        return client;
    }

    @Override
    public void flattenAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {
    }
}
