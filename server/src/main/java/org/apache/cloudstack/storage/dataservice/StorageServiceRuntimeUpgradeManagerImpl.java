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
package org.apache.cloudstack.storage.dataservice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeBundlesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeUpgradesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RegisterStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;

import com.cloud.agent.api.StorageServiceRuntimeFileType;
import com.cloud.agent.api.StorageServiceRuntimeHostAnswer;
import com.cloud.agent.api.StorageServiceRuntimeHostCommand;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageServiceRuntimeUpgradeManagerImpl extends ManagerBase implements StorageServiceRuntimeUpgradeManager {
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_BUNDLE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 16 * 1024;

    @Inject private StorageServiceRuntimeBundleDao bundleDao;
    @Inject private StorageServiceRuntimeUpgradeDao upgradeDao;
    @Inject private StorageServiceInstanceDao instanceDao;
    @Inject private SharedFSDao sharedFSDao;
    @Inject private StorageServiceRuntimeHostDispatcher runtimeDispatcher;
    @Inject private StorageServiceGuestCommandDispatcher guestCommandDispatcher;
    @Inject private VMInstanceDao vmInstanceDao;
    @Inject private org.apache.cloudstack.framework.jobs.dao.AsyncJobDao asyncJobDao;

    @Override
    public StorageServiceRuntimeBundleResponse register(final RegisterStorageServiceRuntimeBundleCmd cmd) {
        requireIdentifier(cmd.getVersion(), "version");
        requireIdentifier(cmd.getRuntimeAbiVersion(), "runtime ABI version");
        requireIdentifier(cmd.getDesiredStateSchemaVersion(), "desired-state schema version");
        requireIdentifier(cmd.getSigningKeyId(), "signing key ID");
        requireSha256(cmd.getSha256(), "bundle SHA-256");
        requireSha256(cmd.getManifestSha256(), "manifest SHA-256");
        final StorageServiceRuntimeBundleVO.ServiceImpact impact;
        try {
            impact = StorageServiceRuntimeBundleVO.ServiceImpact.valueOf(cmd.getServiceImpact().toUpperCase(Locale.ROOT));
        } catch (final RuntimeException error) {
            throw new IllegalArgumentException("Invalid Storage Service runtime service impact", error);
        }
        validateArtifactUrl(cmd.getArtifactUrl());
        validateArtifactUrl(cmd.getManifestUrl());
        validateArtifactUrl(cmd.getSignatureUrl());
        final StorageServiceRuntimeBundleVO existing = bundleDao.findByVersion(cmd.getVersion());
        if (existing != null) {
            if (!existing.getSha256().equalsIgnoreCase(cmd.getSha256()) ||
                    !existing.getManifestSha256().equalsIgnoreCase(cmd.getManifestSha256())) {
                throw new CloudRuntimeException("Runtime bundle version already exists with different hashes");
            }
            return bundleResponse(existing);
        }
        final StorageServiceRuntimeBundleVO bundle = bundleDao.persist(new StorageServiceRuntimeBundleVO(
                cmd.getVersion(), cmd.getRuntimeAbiVersion(), cmd.getDesiredStateSchemaVersion(), impact,
                cmd.getArtifactUrl(), cmd.getManifestUrl(), cmd.getSignatureUrl(), cmd.getArtifactSize(),
                cmd.getSha256().toLowerCase(Locale.ROOT), cmd.getManifestSha256().toLowerCase(Locale.ROOT), cmd.getSigningKeyId()));
        CallContext.current().setEventResourceId(bundle.getId());
        return bundleResponse(bundle);
    }

    @Override
    public ListResponse<StorageServiceRuntimeBundleResponse> listBundles(final ListStorageServiceRuntimeBundlesCmd cmd) {
        final List<StorageServiceRuntimeBundleVO> bundles = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(cmd.getId());
            if (bundle != null) bundles.add(bundle);
        } else if (cmd.getVersion() != null) {
            final StorageServiceRuntimeBundleVO bundle = bundleDao.findByVersion(cmd.getVersion());
            if (bundle != null) bundles.add(bundle);
        } else {
            bundles.addAll(bundleDao.listAvailable());
        }
        final List<StorageServiceRuntimeBundleResponse> responses = new ArrayList<>();
        for (final StorageServiceRuntimeBundleVO bundle : bundles) responses.add(bundleResponse(bundle));
        final ListResponse<StorageServiceRuntimeBundleResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageServiceRuntimeCapabilityResponse capabilities(final GetStorageServiceRuntimeUpgradeCapabilitiesCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getSharedFileSystemId());
        final StorageServiceRuntimeCapabilityResponse response = new StorageServiceRuntimeCapabilityResponse();
        response.setInstanceId(instance.getUuid());
        try {
            final JsonObject result = invoke(instance, StorageServiceRuntimeOperation.CAPABILITIES,
                    "capabilities-" + instance.getId(), null);
            response.setAvailable(true);
            response.setCurrentVersion(stringValue(result, "currentVersion"));
            response.setPreviousVersion(stringValue(result, "previousVersion"));
            response.setRuntimeAbiVersion(stringValue(result, "runtimeAbiVersion"));
            response.setDesiredStateSchemaVersion(stringValue(result, "desiredStateSchemaVersion"));
            response.setEntrypointsManaged(booleanValue(result, "entrypointsManaged"));
            response.setUpdaterVersion(result.has("updaterVersion") ? result.get("updaterVersion").getAsInt() : 1);
            response.setHostTransportVersion(result.has("hostTransportVersion") ? result.get("hostTransportVersion").getAsInt() : 1);
            response.setIntegrityJson(result.has("integrity") ? result.get("integrity").toString() : "{}");
            response.setDetails("Runtime updater is available");
        } catch (final RuntimeException error) {
            response.setAvailable(false);
            response.setDetails(error.getMessage());
        }
        response.setObjectName("storageserviceruntimecapability");
        return response;
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse preflight(final PreflightStorageServiceRuntimeUpgradeCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getSharedFileSystemId());
        final StorageServiceRuntimeBundleVO bundle = requireBundle(cmd.getBundleId());
        if (instance.getCurrentRuntimeBundleId()!=null && instance.getCurrentRuntimeBundleId().equals(bundle.getId()))
            throw new CloudRuntimeException("RUNTIME_ALREADY_CURRENT");
        if (bundle.getServiceImpact() != StorageServiceRuntimeBundleVO.ServiceImpact.NONE) {
            throw new CloudRuntimeException("Runtime bundle requires SystemVM template maintenance: " + bundle.getServiceImpact());
        }
        final String transactionId = cmd.getRequestId() == null ? "runtime-" + UUID.randomUUID() : cmd.getRequestId();
        requireIdentifier(transactionId, "request ID");
        final boolean[] created = {false};
        final StorageServiceRuntimeUpgradeVO upgrade = com.cloud.utils.db.Transaction.execute(
                (com.cloud.utils.db.TransactionCallback<StorageServiceRuntimeUpgradeVO>) ignored -> {
            instanceDao.lockRow(instance.getId(), true);
            final StorageServiceRuntimeUpgradeVO same = upgradeDao.findByTransactionId(transactionId);
            if (same != null) {
                if (same.getInstanceId()!=instance.getId() || same.getBundleId()!=bundle.getId())
                    throw new CloudRuntimeException("Runtime request ID conflicts with an existing request");
                return same;
            }
            final StorageServiceRuntimeUpgradeVO active = upgradeDao.findActiveByInstanceId(instance.getId());
            if (active != null) throw new CloudRuntimeException("Another runtime operation is active: " + active.getUuid());
            created[0]=true;
            return upgradeDao.persist(new StorageServiceRuntimeUpgradeVO(instance.getId(), bundle.getId(),
                    instance.getCurrentRuntimeBundleId(), transactionId, CallContext.current().getCallingUserId()));
        });
        if (!created[0]) return upgradeResponse(upgrade);
        CallContext.current().setEventResourceId(upgrade.getId());
        JsonObject preparationOwner=new JsonObject();
        org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext context=
                org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.getCurrentExecutionContext();
        if (context!=null && context.getJob()!=null) preparationOwner.addProperty("originalJobId",context.getJob().getId());
        upgrade.setPreflightJson(preparationOwner.toString());
        upgradeDao.update(upgrade.getId(),upgrade);
        try {
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "DOWNLOADING", 5);
            final byte[] archive = download(bundle.getArtifactUrl(), MAX_BUNDLE_BYTES);
            final byte[] manifest = download(bundle.getManifestUrl(), MAX_MANIFEST_BYTES);
            final byte[] signature = download(bundle.getSignatureUrl(), MAX_SIGNATURE_BYTES);
            verifyBytes(archive, bundle.getSha256(), "runtime bundle");
            verifyBytes(manifest, bundle.getManifestSha256(), "runtime manifest");
            if (bundle.getArtifactSize() != null && bundle.getArtifactSize() != archive.length) {
                throw new CloudRuntimeException("Runtime bundle size differs from registered metadata");
            }
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "BOOTSTRAPPING", 10);
            ensureBootstrap(instance, bundle, transactionId);
            final JsonObject begin = request(upgrade, bundle);
            begin.addProperty("archiveSha256", bundle.getSha256());
            begin.addProperty("manifestSha256", bundle.getManifestSha256());
            begin.addProperty("totalSize", archive.length);
            begin.addProperty("manifestSize", manifest.length);
            begin.addProperty("signatureSize", signature.length);
            invoke(instance, StorageServiceRuntimeOperation.BEGIN, transactionId, begin);
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "RECEIVING", 15);
            transfer(instance, transactionId, StorageServiceRuntimeFileType.BUNDLE, null, archive, 15, 40, upgrade);
            transfer(instance, transactionId, StorageServiceRuntimeFileType.MANIFEST, null, manifest, 40, 48, upgrade);
            transfer(instance, transactionId, StorageServiceRuntimeFileType.SIGNATURE, null, signature, 48, 52, upgrade);
            invoke(instance, StorageServiceRuntimeOperation.FINALIZE, transactionId, request(upgrade, bundle));
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "VERIFYING_BUNDLE", 55);
            invoke(instance, StorageServiceRuntimeOperation.VERIFY, transactionId, request(upgrade, bundle));
            final JsonObject result = invoke(instance, StorageServiceRuntimeOperation.PREFLIGHT, transactionId, request(upgrade, bundle));
            upgrade.setPreflightJson(result.toString());
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY, "PREFLIGHT_OK", 60);
            return upgradeResponse(upgrade);
        } catch (final RuntimeException error) {
            fail(upgrade, error);
            throw error;
        }
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse upgrade(final UpgradeStorageServiceRuntimeCmd cmd) {
        final StorageServiceRuntimeUpgradeVO upgrade = requireUpgrade(cmd.getUpgradeId());
        if (upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.COMPLETE ||
                upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) return upgradeResponse(upgrade);
        final StorageServiceInstanceVO instance = instanceDao.findById(upgrade.getInstanceId());
        final StorageServiceRuntimeBundleVO bundle = requireBundle(upgrade.getBundleId());
        boolean activate = upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY;
        if (!activate && upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING &&
                !(upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.RUNNING &&
                java.util.Arrays.asList("ACTIVATING","VERIFYING").contains(upgrade.getPhase())))
            throw new CloudRuntimeException("Runtime upgrade is not ready for activation or observation");
        reserveWriter(instance,upgrade,"ACTIVATING");
        try {
            JsonObject activated = activate ? invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,
                    upgrade.getTransactionId(),request(upgrade,bundle)) :
                    invoke(instance,StorageServiceRuntimeOperation.STATUS,upgrade.getTransactionId(),request(upgrade,bundle));
            if (!activate && activated.has("workerActive") && activated.get("workerActive").getAsBoolean()) {
                observationPending(upgrade,new CloudRuntimeException("Native worker is still active"));
                return upgradeResponse(upgrade);
            }
            String phase=stringValue(activated,"phase");
            if ("ROLLED_BACK".equals(phase)) return finishRollback(instance,upgrade,activated);
            if ("ROLLING_BACK".equals(phase)) return finishRollback(instance,upgrade,invoke(instance,
                    StorageServiceRuntimeOperation.ROLLBACK,upgrade.getTransactionId(),request(upgrade,bundle)));
            if ("REJECTED".equals(phase)) { fail(upgrade,new CloudRuntimeException("Native activation rejected before pointer switch")); return upgradeResponse(upgrade); }
            if ("PREFLIGHT_OK".equals(phase)) {
                update(upgrade,StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY,"PREFLIGHT_OK",60);
                return upgradeResponse(upgrade);
            }
            if (!java.util.Arrays.asList("ACTIVATED","COMPLETE").contains(phase))
                throw new CloudRuntimeException("Runtime native phase remains uncertain: " + phase);
            update(upgrade,StorageServiceRuntimeUpgradeVO.State.RUNNING,"VERIFYING",85);
            StorageServiceGuestCommandResult health=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    instance.getVmId(),"health","",timeout(),Collections.emptySet()));
            if (!health.isSuccess()) {
                JsonObject rollback=request(upgrade,bundle);
                rollback.addProperty("restoreConfiguration",true);
                JsonObject result=invoke(instance,StorageServiceRuntimeOperation.ROLLBACK,upgrade.getTransactionId(),rollback);
                finishRollback(instance,upgrade,result);
                throw new CloudRuntimeException("Runtime health failed; previous code and configuration restored: " + health.getDetails());
            }
            JsonObject confirmation=request(upgrade,bundle);
            confirmation.addProperty("confirmActivation",true);
            JsonObject confirmed=invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,upgrade.getTransactionId(),confirmation);
            instance.setPreviousRuntimeBundleId(upgrade.getPreviousBundleId());
            instance.setCurrentRuntimeBundleId(bundle.getId());
            instance.setRuntimeState("VERIFIED");instance.setRuntimeVerifiedAt(new Date());
            instanceDao.update(instance.getId(),instance);
            JsonObject verification=new JsonObject();
            verification.add("activation",confirmed);
            verification.addProperty("healthSuccess",true);
            upgrade.setVerificationJson(verification.toString());
            upgrade.setErrorCode(null);upgrade.setErrorMessage(null);upgrade.setCompleted(new Date());
            update(upgrade,StorageServiceRuntimeUpgradeVO.State.COMPLETE,"COMPLETE",100);
            return upgradeResponse(upgrade);
        } catch (RuntimeException error) {
            if (error instanceof NativeRuntimeRejected && upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) {
                try {
                    JsonObject nativeState=invoke(instance,StorageServiceRuntimeOperation.STATUS,upgrade.getTransactionId(),request(upgrade,bundle));
                    String nativePhase=stringValue(nativeState,"phase");
                    if ("ACTIVATED".equals(nativePhase) || "ACTIVATING".equals(nativePhase)) {
                        JsonObject restore=request(upgrade,bundle);restore.addProperty("restoreConfiguration",true);
                        finishRollback(instance,upgrade,invoke(instance,StorageServiceRuntimeOperation.ROLLBACK,upgrade.getTransactionId(),restore));
                    } else if ("ROLLED_BACK".equals(nativePhase)) finishRollback(instance,upgrade,nativeState);
                    else if ("PREFLIGHT_OK".equals(nativePhase) || "REJECTED".equals(nativePhase)) fail(upgrade,error);
                    else observationPending(upgrade,error);
                } catch (RuntimeException observationError) { observationPending(upgrade,observationError); }
            } else if (upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) observationPending(upgrade,error);
            if (upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) {
                upgrade.setErrorCode("ACTIVATION_REJECTED_ROLLED_BACK");
                upgrade.setErrorMessage(error.getMessage()==null?"Runtime verification failed":error.getMessage().substring(0,Math.min(1024,error.getMessage().length())));
                upgradeDao.update(upgrade.getId(),upgrade);
            }
            throw error;
        }
    }

    private void reserveWriter(StorageServiceInstanceVO instance,StorageServiceRuntimeUpgradeVO upgrade,String phase) {
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) ignored -> {
            StorageServiceInstanceVO locked=instanceDao.lockRow(instance.getId(),true);
            if (locked.getActiveConfigurationOperationId()!=null) throw new CloudRuntimeException("CONFIGURATION_WRITER_ACTIVE");
            StorageServiceRuntimeUpgradeVO other=upgradeDao.findActiveByInstanceId(instance.getId());
            if (other!=null && other.getId()!=upgrade.getId()) throw new CloudRuntimeException("RUNTIME_WRITER_ACTIVE");
            update(upgrade,StorageServiceRuntimeUpgradeVO.State.RUNNING,phase,70);
            return true;
        });
    }

    private void observationPending(StorageServiceRuntimeUpgradeVO upgrade,RuntimeException error) {
        upgrade.setErrorCode("RUNTIME_OBSERVATION_PENDING");
        upgrade.setErrorMessage("Native result not confirmed; observe the same operation before any new change");
        update(upgrade,StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING,"OBSERVATION_PENDING",
                upgrade.getProgress()==null?70:upgrade.getProgress());
    }

    private StorageServiceRuntimeUpgradeResponse finishRollback(StorageServiceInstanceVO instance,
            StorageServiceRuntimeUpgradeVO upgrade,JsonObject result) {
        String version=stringValue(result,"currentVersion");
        StorageServiceRuntimeBundleVO actual=version==null?null:bundleDao.findByVersion(version);
        JsonObject preflight=upgrade.getPreflightJson()==null?new JsonObject():JsonParser.parseString(upgrade.getPreflightJson()).getAsJsonObject();
        if (actual==null && !(upgrade.getPreviousBundleId()==null && version!=null &&
                version.equals(stringValue(preflight,"previousVersion")) &&
                java.util.Arrays.asList("template-baseline","verified-baseline").contains(stringValue(result,"currentOrigin"))))
            throw new CloudRuntimeException("Native rollback version has no verified template baseline mapping");
        instance.setCurrentRuntimeBundleId(actual==null?null:actual.getId());
        instance.setPreviousRuntimeBundleId(upgrade.getBundleId());
        instance.setRuntimeState("ROLLED_BACK");instance.setRuntimeVerifiedAt(new Date());
        instanceDao.update(instance.getId(),instance);
        upgrade.setRollbackResultJson(result.toString());upgrade.setCompleted(new Date());
        upgrade.setErrorCode(null);upgrade.setErrorMessage(null);
        update(upgrade,StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK,"ROLLED_BACK",100);
        return upgradeResponse(upgrade);
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse rollback(final RollbackStorageServiceRuntimeUpgradeCmd cmd) {
        StorageServiceRuntimeUpgradeVO upgrade=requireUpgrade(cmd.getUpgradeId());
        if (upgrade.getState()==StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) return upgradeResponse(upgrade);
        if (upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.COMPLETE &&
                upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING)
            throw new CloudRuntimeException("Only a confirmed current runtime operation can be rolled back");
        StorageServiceInstanceVO instance=instanceDao.findById(upgrade.getInstanceId());
        StorageServiceRuntimeBundleVO bundle=requireBundle(upgrade.getBundleId());
        if (instance.getCurrentRuntimeBundleId()!=null && !instance.getCurrentRuntimeBundleId().equals(bundle.getId()))
            throw new CloudRuntimeException("ROLLBACK_STALE_OPERATION");
        reserveWriter(instance,upgrade,"ROLLING_BACK");
        try {
            return finishRollback(instance,upgrade,invoke(instance,StorageServiceRuntimeOperation.ROLLBACK,
                    upgrade.getTransactionId(),request(upgrade,bundle)));
        } catch (RuntimeException error) {
            observationPending(upgrade,error);
            throw error;
        }
    }

    @Override
    public ListResponse<StorageServiceRuntimeUpgradeResponse> listUpgrades(final ListStorageServiceRuntimeUpgradesCmd cmd) {
        final List<StorageServiceRuntimeUpgradeVO> upgrades = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageServiceRuntimeUpgradeVO upgrade = upgradeDao.findById(cmd.getId());
            if (upgrade != null) upgrades.add(upgrade);
        } else if (cmd.getSharedFileSystemId() != null) {
            upgrades.addAll(upgradeDao.listByInstanceId(requireInstance(cmd.getSharedFileSystemId()).getId()));
        } else {
            upgrades.addAll(upgradeDao.listAll());
        }
        upgrades.sort(java.util.Comparator.comparingLong(StorageServiceRuntimeUpgradeVO::getId).reversed());
        final List<StorageServiceRuntimeUpgradeResponse> responses = new ArrayList<>();
        for (final StorageServiceRuntimeUpgradeVO upgrade : upgrades) {
            observeInterruptedPreparation(upgrade);
            responses.add(upgradeResponse(upgradeDao.findById(upgrade.getId())));
        }
        final ListResponse<StorageServiceRuntimeUpgradeResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    private void observeInterruptedPreparation(StorageServiceRuntimeUpgradeVO upgrade) {
        if (upgrade.getState()!=StorageServiceRuntimeUpgradeVO.State.RUNNING ||
                java.util.Arrays.asList("ACTIVATING","VERIFYING","ROLLING_BACK").contains(upgrade.getPhase()) ||
                upgrade.getPreflightJson()==null) return;
        JsonObject owner=JsonParser.parseString(upgrade.getPreflightJson()).getAsJsonObject();
        if (!owner.has("originalJobId")) return;
        org.apache.cloudstack.framework.jobs.AsyncJob job=asyncJobDao.findById(owner.get("originalJobId").getAsLong());
        if (job!=null && job.getStatus()==org.apache.cloudstack.jobs.JobInfo.Status.IN_PROGRESS) return;
        StorageServiceInstanceVO instance=instanceDao.findById(upgrade.getInstanceId());
        StorageServiceRuntimeBundleVO bundle=requireBundle(upgrade.getBundleId());
        try {
            JsonObject nativeState=invoke(instance,StorageServiceRuntimeOperation.STATUS,upgrade.getTransactionId(),request(upgrade,bundle));
            if (nativeState.has("workerActive") && nativeState.get("workerActive").getAsBoolean()) return;
            if ("PREFLIGHT_OK".equals(stringValue(nativeState,"phase"))) {
                finishPreparationObservation(upgrade,nativeState);
                return;
            }
        } catch (RuntimeException unavailable) {
            // Only staging is reconciled here. No activation, rollback or native cleanup from a read.
        }
        finishPreparationObservation(upgrade,null);
    }

    private void finishPreparationObservation(StorageServiceRuntimeUpgradeVO snapshot,JsonObject nativeState) {
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) ignored -> {
            StorageServiceRuntimeUpgradeVO current=upgradeDao.lockRow(snapshot.getId(),true);
            if (current==null || current.getState()!=snapshot.getState() ||
                    !java.util.Objects.equals(current.getPhase(),snapshot.getPhase())) return false;
            if (nativeState!=null) {
                current.setPreflightJson(nativeState.toString());
                update(current,StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY,"PREFLIGHT_OK",60);
            } else fail(current,new CloudRuntimeException("PREPARATION_INTERRUPTED: original async job ended before preflight completed; current runtime was not activated"));
            return true;
        });
    }

    protected void ensureBootstrap(final StorageServiceInstanceVO instance, final StorageServiceRuntimeBundleVO bundle,
            final String transactionId) {
        final StorageServiceRuntimeHostAnswer capability = runtimeDispatcher.dispatch(instance.getVmId(), new StorageServiceRuntimeHostCommand(
                vmName(instance), StorageServiceRuntimeOperation.CAPABILITIES, transactionId, null, timeout()));
        if (!capability.getResult()) throw new CloudRuntimeException("Runtime updater unavailable; aligned SystemVM template maintenance required");
        JsonObject observed = JsonParser.parseString(capability.getResultJson()).getAsJsonObject();
        if (!observed.has("hostTransportVersion") || observed.get("hostTransportVersion").getAsInt()<2)
            throw new CloudRuntimeException("Atomic runtime host transport version 2 is required");
        byte[] updaterModule=resource("/storage-runtime/bootstrap/runtime_updater.py");
        if (observed.has("updaterVersion") && observed.get("updaterVersion").getAsInt()>2)
            throw new CloudRuntimeException("Newer trusted updater requires aligned management support");
        if (!observed.has("updaterVersion") || observed.get("updaterVersion").getAsInt()<2 ||
                !sha256(updaterModule).equals(stringValue(observed,"updaterSha256"))) {
            transfer(instance, transactionId, StorageServiceRuntimeFileType.UPDATER_MODULE, null,
                    updaterModule, 0, 4, null);
            transfer(instance, transactionId, StorageServiceRuntimeFileType.UPDATER_ENTRY, null,
                    resource("/storage-runtime/bootstrap/ablestack-storage-runtime-updater"), 4, 8, null);
        }
        JsonObject bootstrap = new JsonObject();
        bootstrap.addProperty("transactionId",transactionId);
        bootstrap.add("baselineProfile", JsonParser.parseString(new String(
                resource("/storage-runtime/bootstrap/runtime-baseline-profile.json"), java.nio.charset.StandardCharsets.UTF_8)));
        invoke(instance, StorageServiceRuntimeOperation.BOOTSTRAP, transactionId, bootstrap);
        transfer(instance, transactionId, StorageServiceRuntimeFileType.TRUSTED_KEY, bundle.getSigningKeyId(),
                resource("/storage-runtime/trusted-keys/" + bundle.getSigningKeyId() + ".pem"), 8, 10, null);
    }

    protected void transfer(final StorageServiceInstanceVO instance, final String transactionId,
            final StorageServiceRuntimeFileType type, final String keyId, final byte[] value,
            final int fromProgress, final int toProgress, final StorageServiceRuntimeUpgradeVO upgrade) {
        int offset = 0;
        while (offset < value.length) {
            final int length = Math.min(StorageServiceRuntimeHostCommand.MAX_RAW_CHUNK_BYTES, value.length - offset);
            final byte[] chunk = new byte[length];
            System.arraycopy(value, offset, chunk, 0, length);
            final StorageServiceRuntimeHostCommand command = new StorageServiceRuntimeHostCommand(vmName(instance), transactionId,
                    type, keyId, offset, length, sha256(chunk), Base64.getEncoder().encodeToString(chunk),
                    offset == 0, offset + length == value.length, timeout());
            command.setFileMetadata(sha256(value), value.length);
            final StorageServiceRuntimeHostAnswer answer = runtimeDispatcher.dispatch(instance.getVmId(), command);
            if (!answer.getResult()) throw new CloudRuntimeException(answer.getDetails());
            offset += length;
            if (upgrade != null) {
                final int progress = fromProgress + (int) (((long) offset * (toProgress - fromProgress)) / value.length);
                update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "RECEIVING_" + type.name(), progress);
            }
        }
    }

    private static class NativeRuntimeRejected extends CloudRuntimeException {
        NativeRuntimeRejected(String details) { super(details); }
    }

    protected JsonObject invoke(final StorageServiceInstanceVO instance, final StorageServiceRuntimeOperation operation,
            final String transactionId, final JsonObject request) {
        final StorageServiceRuntimeHostAnswer answer = runtimeDispatcher.dispatch(instance.getVmId(), new StorageServiceRuntimeHostCommand(
                vmName(instance), operation, transactionId, request == null ? null : request.toString(), timeout()));
        if (!answer.getResult()) {
            if (answer.getResultJson()!=null) {
                try {
                    JsonObject rejected=JsonParser.parseString(answer.getResultJson()).getAsJsonObject();
                    if (rejected.has("errorCode")) throw new NativeRuntimeRejected(answer.getDetails());
                } catch (com.google.gson.JsonParseException ignored) { }
            }
            throw new CloudRuntimeException(answer.getDetails());
        }
        if (answer.getResultJson() == null || answer.getResultJson().trim().isEmpty()) return new JsonObject();
        return new JsonParser().parse(answer.getResultJson().trim()).getAsJsonObject();
    }

    protected byte[] download(final String location, final int maximum) {
        validateArtifactUrl(location);
        try {
            final URL url = URI.create(location).toURL();
            final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new CloudRuntimeException("Runtime artifact download returned HTTP " + connection.getResponseCode());
            }
            final int declared = connection.getContentLength();
            if (declared > maximum) throw new CloudRuntimeException("Runtime artifact exceeds the maximum size");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                    if (output.size() > maximum) throw new CloudRuntimeException("Runtime artifact exceeds the maximum size");
                }
                return output.toByteArray();
            }
        } catch (final IOException | IllegalArgumentException error) {
            throw new CloudRuntimeException("Unable to download Storage Service runtime artifact: " + error.getMessage(), error);
        }
    }

    protected byte[] resource(final String path) {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) throw new CloudRuntimeException("Storage Service runtime bootstrap resource is unavailable: " + path);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } catch (final IOException error) {
            throw new CloudRuntimeException("Unable to read Storage Service runtime bootstrap resource: " + path, error);
        }
    }

    private StorageServiceInstanceVO requireInstance(final Long sharedFileSystemId) {
        final SharedFSVO sharedFS = sharedFSDao.findById(sharedFileSystemId);
        if (sharedFS == null || sharedFS.getVmId() == null) throw new CloudRuntimeException("Shared FileSystem has no Storage Service System VM");
        final StorageServiceInstanceVO instance = instanceDao.findByVmId(sharedFS.getVmId());
        if (instance == null || instance.getVmId() == null) throw new CloudRuntimeException("Storage Service instance is unavailable");
        return instance;
    }

    private StorageServiceRuntimeBundleVO requireBundle(final Long id) {
        final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(id);
        if (bundle == null || bundle.getState() != StorageServiceRuntimeBundleVO.State.AVAILABLE) throw new CloudRuntimeException("Runtime bundle is unavailable");
        return bundle;
    }

    private StorageServiceRuntimeUpgradeVO requireUpgrade(final Long id) {
        final StorageServiceRuntimeUpgradeVO upgrade = upgradeDao.findById(id);
        if (upgrade == null) throw new CloudRuntimeException("Runtime upgrade transaction is unavailable");
        return upgrade;
    }

    private void update(final StorageServiceRuntimeUpgradeVO upgrade, final StorageServiceRuntimeUpgradeVO.State state,
            final String phase, final int progress) {
        upgrade.setState(state); upgrade.setPhase(phase); upgrade.setProgress(progress); upgradeDao.update(upgrade.getId(), upgrade);
    }

    private void fail(final StorageServiceRuntimeUpgradeVO upgrade, final RuntimeException error) {
        upgrade.setErrorCode("RUNTIME_UPGRADE_FAILED");
        upgrade.setErrorMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage().substring(0, Math.min(1024, error.getMessage().length())));
        upgrade.setCompleted(new Date());
        update(upgrade, StorageServiceRuntimeUpgradeVO.State.FAILED, "FAILED", 100);
    }

    private JsonObject request(final StorageServiceRuntimeUpgradeVO upgrade, final StorageServiceRuntimeBundleVO bundle) {
        final JsonObject request = new JsonObject();
        request.addProperty("transactionId", upgrade.getTransactionId());
        request.addProperty("bundleVersion", bundle.getVersion());
        request.addProperty("keyId", bundle.getSigningKeyId());
        request.addProperty("runtimeAbiVersion", bundle.getRuntimeAbiVersion());
        request.addProperty("desiredStateSchemaVersion", bundle.getDesiredStateSchemaVersion());
        request.addProperty("serviceImpact", bundle.getServiceImpact().name());
        StorageServiceInstanceVO instance = instanceDao.findById(upgrade.getInstanceId());
        request.addProperty("instanceUuid", instance.getUuid());
        return request;
    }

    private String vmName(final StorageServiceInstanceVO instance) {
        final VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        if (vm == null || vm.getInstanceName() == null) {
            throw new CloudRuntimeException("Storage Service System VM is unavailable: " + instance.getVmId());
        }
        return vm.getInstanceName();
    }

    private int timeout() { return StorageServiceInstance.StorageServiceCommandTimeout.value(); }

    private void validateArtifactUrl(final String value) {
        try {
            final String scheme = URI.create(value).getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) throw new IllegalArgumentException();
        } catch (final RuntimeException error) {
            throw new IllegalArgumentException("Runtime artifact URL must use HTTP or HTTPS", error);
        }
    }

    private void requireIdentifier(final String value, final String field) {
        if (value == null || !VERSION_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + field);
    }

    private void requireSha256(final String value, final String field) {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) throw new IllegalArgumentException("Invalid " + field);
    }

    private void verifyBytes(final byte[] value, final String expected, final String name) {
        if (!sha256(value).equalsIgnoreCase(expected)) throw new CloudRuntimeException(name + " SHA-256 does not match registered metadata");
    }

    private String sha256(final byte[] value) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            final StringBuilder result = new StringBuilder(64);
            for (final byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String stringValue(final JsonObject object, final String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private Boolean booleanValue(final JsonObject object, final String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsBoolean() : null;
    }

    private StorageServiceRuntimeBundleResponse bundleResponse(final StorageServiceRuntimeBundleVO bundle) {
        final StorageServiceRuntimeBundleResponse response = new StorageServiceRuntimeBundleResponse();
        response.setId(bundle.getUuid()); response.setVersion(bundle.getVersion()); response.setRuntimeAbiVersion(bundle.getRuntimeAbiVersion());
        response.setDesiredStateSchemaVersion(bundle.getDesiredStateSchemaVersion()); response.setServiceImpact(bundle.getServiceImpact().name());
        response.setArtifactUrl(bundle.getArtifactUrl()); response.setArtifactSize(bundle.getArtifactSize()); response.setSha256(bundle.getSha256());
        response.setManifestSha256(bundle.getManifestSha256()); response.setSigningKeyId(bundle.getSigningKeyId()); response.setState(bundle.getState().name());
        response.setCreated(bundle.getCreated()); response.setObjectName("storageserviceruntimebundle"); return response;
    }

    private StorageServiceRuntimeUpgradeResponse upgradeResponse(final StorageServiceRuntimeUpgradeVO upgrade) {
        final StorageServiceRuntimeUpgradeResponse response = new StorageServiceRuntimeUpgradeResponse();
        final StorageServiceInstanceVO instance = instanceDao.findById(upgrade.getInstanceId());
        final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(upgrade.getBundleId());
        response.setId(upgrade.getUuid()); response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setBundleId(bundle == null ? null : bundle.getUuid()); response.setBundleVersion(bundle == null ? null : bundle.getVersion());
        response.setState(upgrade.getState().name()); response.setPhase(upgrade.getPhase()); response.setProgress(upgrade.getProgress());
        response.setTransactionId(upgrade.getTransactionId()); response.setPreflightJson(upgrade.getPreflightJson());
        response.setVerificationJson(upgrade.getVerificationJson()); response.setRollbackResultJson(upgrade.getRollbackResultJson());
        response.setErrorCode(upgrade.getErrorCode()); response.setErrorMessage(upgrade.getErrorMessage()); response.setStarted(upgrade.getStarted());
        response.setCompleted(upgrade.getCompleted()); response.setObjectName("storageserviceruntimeupgrade"); return response;
    }
}
