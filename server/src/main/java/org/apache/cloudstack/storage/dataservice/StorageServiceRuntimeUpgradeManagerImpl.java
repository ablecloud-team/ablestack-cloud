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
import com.cloud.utils.db.GlobalLock;
import com.google.gson.JsonArray;
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
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpdateStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.DeleteStorageServiceRuntimeBundleCmd;
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
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao rootUpgradeDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao rootWriterDao;
    @Inject private SharedFSDao sharedFSDao;
    @Inject private StorageServiceRuntimeHostDispatcher runtimeDispatcher;
    @Inject private StorageServiceGuestCommandDispatcher guestCommandDispatcher;
    @Inject private VMInstanceDao vmInstanceDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao fileShareDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao protocolDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao accessRuleDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao posixPolicyDao;

    private StorageServiceRuntimeUpgradeResponse rootSerializedRuntime(StorageServiceInstanceVO instance,
            java.util.function.Supplier<StorageServiceRuntimeUpgradeResponse> action) {
        final GlobalLock lock = GlobalLock.getInternLock("StorageServiceWriter-" + instance.getId());
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Storage Service writer is busy");
            try {
                if (rootUpgradeDao.findActive(instance.getId()) != null || rootWriterDao.listByInstance(instance.getId()).stream()
                        .anyMatch(row -> row.getAction().startsWith("ROOT_TEMPLATE_") && java.util.Set.of("RUNNING","RECOVERY_REQUIRED").contains(row.getState()))) {
                    throw new CloudRuntimeException("ROOT template maintenance must finish or recover before runtime activation");
                }
                return action.get();
            } finally {lock.unlock();}
        } finally {lock.releaseRef();}
    }

    private StorageServiceInstanceVO rootRuntimeScope(long instanceId,String operationUuid) {
        UUID.fromString(operationUuid);StorageServiceInstanceVO instance=instanceDao.findById(instanceId);
        StorageServiceOperationVO operation=rootWriterDao.findByUuid(operationUuid);
        StorageServiceTemplateUpgradeVO upgrade=rootUpgradeDao.findActive(instanceId);
        if (instance==null || operation==null || operation.getInstanceId()!=instanceId || !"RUNNING".equals(operation.getState())
                || !operation.getAction().startsWith("ROOT_TEMPLATE_") || upgrade==null || !Long.valueOf(operation.getId()).equals(upgrade.getOperationId())) {
            throw new CloudRuntimeException("Signed runtime replay requires the reserved ROOT maintenance writer");
        }
        return instance;
    }
    protected JsonObject runtimePin(StorageServiceRuntimeBundleVO bundle) {
        JsonObject pin=new JsonObject();pin.addProperty("bundleUuid",bundle.getUuid());pin.addProperty("bundleVersion",bundle.getVersion());
        pin.addProperty("archiveSha256",bundle.getSha256());pin.addProperty("manifestSha256",bundle.getManifestSha256());pin.addProperty("signingKeyId",bundle.getSigningKeyId());
        pin.addProperty("runtimeAbiVersion",bundle.getRuntimeAbiVersion());pin.addProperty("desiredStateSchemaVersion",bundle.getDesiredStateSchemaVersion());return pin;
    }
    private StorageServiceRuntimeBundleVO pinnedBundle(JsonObject pin) {
        StorageServiceRuntimeBundleVO bundle=bundleDao.findByUuid(pin.get("bundleUuid").getAsString());
        if (bundle==null || bundle.getState()!=StorageServiceRuntimeBundleVO.State.AVAILABLE || !runtimePin(bundle).equals(pin)) throw new CloudRuntimeException("Pinned ROOT runtime catalog provenance changed or was revoked");
        return bundle;
    }
    @Override public JsonObject templateRuntimeCapabilities(long instanceId) {
        StorageServiceInstanceVO instance=instanceDao.findById(instanceId);
        if (instance==null || instance.getVmId()==null) throw new CloudRuntimeException("ROOT runtime VM is unavailable");
        JsonObject capability=invoke(instance,StorageServiceRuntimeOperation.CAPABILITIES,"root-cap-"+UUID.randomUUID(),null);
        capability.addProperty("expectedUpdaterSha256",sha256(resource("/storage-runtime/bootstrap/runtime_updater.py")));return capability;
    }
    protected String requireTemplateRuntimeHelper(StorageServiceInstanceVO instance) {
        JsonObject capability=templateRuntimeCapabilities(instance.getId());
        if (!capability.has("signedRuntimeReadback") || !capability.get("signedRuntimeReadback").getAsBoolean() || !capability.has("updaterSha256")
                || !capability.get("expectedUpdaterSha256").getAsString().equals(capability.get("updaterSha256").getAsString())) throw new CloudRuntimeException("ROOT signed runtime readback helper provenance is unavailable or differs");
        return capability.get("updaterSha256").getAsString();
    }
    @Override public JsonObject checkpointTemplateRuntime(long instanceId,String rootOperationUuid) {
        StorageServiceInstanceVO instance=rootRuntimeScope(instanceId,rootOperationUuid);
        if (instance.getCurrentRuntimeBundleId()==null || instance.getRuntimeVerifiedAt()==null) throw new CloudRuntimeException("Source ROOT has no previously verified signed runtime bundle");
        String helperSha=requireTemplateRuntimeHelper(instance);StorageServiceRuntimeBundleVO bundle=requireBundle(instance.getCurrentRuntimeBundleId());JsonObject pin=runtimePin(bundle);
        JsonObject result=new JsonObject();result.add("pin",pin);result.addProperty("updaterSha256",helperSha);result.add("verification",stagePinnedRuntime(instance,bundle,rootOperationUuid,"source",false));return result;
    }
    @Override public JsonObject restoreTemplateRuntime(long instanceId,JsonObject pin,String rootOperationUuid,String direction) {
        StorageServiceInstanceVO instance=rootRuntimeScope(instanceId,rootOperationUuid);
        if (!java.util.Set.of("target","previous").contains(direction)) throw new CloudRuntimeException("Invalid ROOT runtime replay direction");
        requireTemplateRuntimeHelper(instance);return stagePinnedRuntime(instance,pinnedBundle(pin),rootOperationUuid,direction,true);
    }
    @Override public JsonObject verifyTemplateRuntime(long instanceId,JsonObject pin,String rootOperationUuid,String direction) {
        StorageServiceInstanceVO instance=rootRuntimeScope(instanceId,rootOperationUuid);
        if (!java.util.Set.of("source","target","previous").contains(direction)) throw new CloudRuntimeException("Invalid ROOT runtime readback direction");
        String helperSha=requireTemplateRuntimeHelper(instance);StorageServiceRuntimeBundleVO bundle=pinnedBundle(pin);JsonObject request=runtimePin(bundle);
        String transaction="root-"+direction+"-"+rootOperationUuid;request.addProperty("transactionId",transaction);
        JsonObject observed=requireRuntimeReadback(invoke(instance,StorageServiceRuntimeOperation.READBACK,transaction,request),bundle);
        if (!observed.has("updaterSha256") || !helperSha.equals(observed.get("updaterSha256").getAsString())) throw new CloudRuntimeException("ROOT runtime helper changed during installed-code readback");return observed;
    }
    protected JsonObject requireRuntimeReadback(JsonObject result,StorageServiceRuntimeBundleVO bundle) {
        if (!result.has("success") || !result.get("success").getAsBoolean()
                || !result.has("signedRuntimeVerified") || !result.get("signedRuntimeVerified").getAsBoolean()
                || !result.has("installedFilesVerified") || !result.get("installedFilesVerified").getAsBoolean()
                || !result.has("entrypointsVerified") || !result.get("entrypointsVerified").getAsBoolean()
                || !bundle.getVersion().equals(result.has("currentVersion")?result.get("currentVersion").getAsString():null)
                || !bundle.getSha256().equals(result.has("archiveSha256")?result.get("archiveSha256").getAsString():null)
                || !bundle.getManifestSha256().equals(result.has("manifestSha256")?result.get("manifestSha256").getAsString():null)) {
            throw new CloudRuntimeException("Installed ROOT runtime code differs from its pinned signed bundle");
        }
        return result;
    }
    private JsonObject stagePinnedRuntime(StorageServiceInstanceVO instance,StorageServiceRuntimeBundleVO bundle,String operationUuid,String direction,boolean activate) {
        if (bundle.getServiceImpact()!=StorageServiceRuntimeBundleVO.ServiceImpact.NONE) throw new CloudRuntimeException("Pinned runtime requires additional template maintenance");
        byte[] archive=download(bundle.getArtifactUrl(),MAX_BUNDLE_BYTES),manifest=download(bundle.getManifestUrl(),MAX_MANIFEST_BYTES),signature=download(bundle.getSignatureUrl(),MAX_SIGNATURE_BYTES);
        JsonObject verified=new StorageServiceRuntimeBundleVerifier().verify(bundle,archive,manifest,signature,trustedKey(bundle.getSigningKeyId()));
        StorageRuntimeFeatureCompatibility.require(verified.getAsJsonObject("manifest"),requiredRuntimeFeatures(instance));
        String transaction="root-"+direction+"-"+operationUuid;ensureBootstrap(instance,bundle,transaction);
        JsonObject request=runtimePin(bundle);request.addProperty("transactionId",transaction);request.addProperty("totalSize",archive.length);request.addProperty("manifestSize",manifest.length);request.addProperty("signatureSize",signature.length);
        JsonObject started=invoke(instance,StorageServiceRuntimeOperation.BEGIN,transaction,request);String phase=started.has("phase")?started.get("phase").getAsString():null;
        if (java.util.Set.of("RECEIVING","RECEIVED").contains(phase)) {
            transfer(instance,transaction,StorageServiceRuntimeFileType.BUNDLE,null,archive,0,0,null);transfer(instance,transaction,StorageServiceRuntimeFileType.MANIFEST,null,manifest,0,0,null);transfer(instance,transaction,StorageServiceRuntimeFileType.SIGNATURE,null,signature,0,0,null);
            invoke(instance,StorageServiceRuntimeOperation.FINALIZE,transaction,request);invoke(instance,StorageServiceRuntimeOperation.VERIFY,transaction,request);phase="VERIFIED";
        }
        if (activate && ("VERIFIED".equals(phase) || "PREFLIGHT_OK".equals(phase))) {
            invoke(instance,StorageServiceRuntimeOperation.PREFLIGHT,transaction,request);invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,transaction,request);
        } else if (activate && ("ACTIVATING".equals(phase) || "COMPLETE".equals(phase))) {
            invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,transaction,request);
        }
        return requireRuntimeReadback(invoke(instance,StorageServiceRuntimeOperation.READBACK,transaction,request),bundle);
    }

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
        final String channel = cmd.getReleaseChannel() == null ? "stable" : cmd.getReleaseChannel().trim();
        if (!channel.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid runtime release channel");
        if (cmd.getReleaseNotes() != null && cmd.getReleaseNotes().length() > 4096) throw new IllegalArgumentException("Runtime release notes exceed 4096 characters");
        final StorageServiceRuntimeBundleVO existing = bundleDao.findByVersion(cmd.getVersion());
        if (existing != null) {
            if (!existing.getSha256().equalsIgnoreCase(cmd.getSha256()) ||
                    !existing.getManifestSha256().equalsIgnoreCase(cmd.getManifestSha256()) ||
                    !existing.getSigningKeyId().equals(cmd.getSigningKeyId()) ||
                    !existing.getRuntimeAbiVersion().equals(cmd.getRuntimeAbiVersion()) ||
                    !existing.getDesiredStateSchemaVersion().equals(cmd.getDesiredStateSchemaVersion()) ||
                    existing.getServiceImpact() != impact) {
                throw new CloudRuntimeException("Runtime bundle version already exists with different hashes");
            }
            return bundleResponse(existing);
        }
        final StorageServiceRuntimeBundleVO bundle = bundleDao.persist(new StorageServiceRuntimeBundleVO(
                cmd.getVersion(), cmd.getRuntimeAbiVersion(), cmd.getDesiredStateSchemaVersion(), impact,
                cmd.getArtifactUrl(), cmd.getManifestUrl(), cmd.getSignatureUrl(), cmd.getArtifactSize(),
                cmd.getSha256().toLowerCase(Locale.ROOT), cmd.getManifestSha256().toLowerCase(Locale.ROOT), cmd.getSigningKeyId()));
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("channel", channel);
        metadata.addProperty("releaseNotes", cmd.getReleaseNotes());
        bundle.setCatalogJson(metadata.toString());
        recordCatalogAudit(bundle, "REGISTERED", "Immutable runtime bundle registered");
        bundleDao.update(bundle.getId(), bundle);
        CallContext.current().setEventResourceId(bundle.getId());
        return bundleResponse(bundle);
    }

    @Override
    public StorageServiceRuntimeBundleResponse updateBundle(final UpdateStorageServiceRuntimeBundleCmd cmd) {
        if (cmd.getReason() == null || cmd.getReason().trim().isEmpty() || cmd.getReason().length() > 1024) {
            throw new IllegalArgumentException("A lifecycle reason of 1 to 1024 characters is required");
        }
        final StorageServiceRuntimeBundleVO.State target = StorageServiceRuntimeBundleVO.State.valueOf(cmd.getState().toUpperCase(java.util.Locale.ROOT));
        final GlobalLock lock = GlobalLock.getInternLock("StorageRuntimeCatalog-" + cmd.getId());
        try {
            if (!lock.lock(10)) throw new CloudRuntimeException("Runtime catalog entry is being changed");
            try {
                final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(cmd.getId());
                if (bundle == null || !bundle.getState().name().equalsIgnoreCase(cmd.getExpectedState())) {
                    throw new CloudRuntimeException("Runtime catalog state changed; refresh before retrying");
                }
                if (!catalogTransitionAllowed(bundle.getState(), target)) {
                    throw new IllegalArgumentException("Runtime catalog state transition is not allowed");
                }
                if (target == StorageServiceRuntimeBundleVO.State.VERIFIED || target == StorageServiceRuntimeBundleVO.State.AVAILABLE) {
                    final byte[] archive = download(bundle.getArtifactUrl(), MAX_BUNDLE_BYTES);
                    final byte[] manifest = download(bundle.getManifestUrl(), MAX_MANIFEST_BYTES);
                    final byte[] signature = download(bundle.getSignatureUrl(), MAX_SIGNATURE_BYTES);
                    final JsonObject verification = new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature,
                            trustedKey(bundle.getSigningKeyId()));
                    final JsonObject metadata = catalogMetadata(bundle);
                    metadata.add("verification", verification);
                    metadata.addProperty("verifiedAt", new Date().getTime());
                    if (target == StorageServiceRuntimeBundleVO.State.AVAILABLE) metadata.addProperty("publishedAt", new Date().getTime());
                    bundle.setCatalogJson(metadata.toString());
                }
                bundle.setState(target);
                recordCatalogAudit(bundle, target.name(), cmd.getReason());
                bundleDao.update(bundle.getId(), bundle);
                return bundleResponse(bundle);
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    protected boolean catalogTransitionAllowed(StorageServiceRuntimeBundleVO.State source, StorageServiceRuntimeBundleVO.State target) {
        if (source == StorageServiceRuntimeBundleVO.State.REVOKED) return false;
        if (source == target) return source != StorageServiceRuntimeBundleVO.State.REGISTERED;
        switch (source) {
            case REGISTERED: return target == StorageServiceRuntimeBundleVO.State.VERIFIED || target == StorageServiceRuntimeBundleVO.State.REVOKED;
            case VERIFIED: return target == StorageServiceRuntimeBundleVO.State.AVAILABLE || target == StorageServiceRuntimeBundleVO.State.REVOKED;
            case AVAILABLE: return target == StorageServiceRuntimeBundleVO.State.DISABLED || target == StorageServiceRuntimeBundleVO.State.DEPRECATED ||
                    target == StorageServiceRuntimeBundleVO.State.REVOKED;
            case DISABLED: return target == StorageServiceRuntimeBundleVO.State.AVAILABLE || target == StorageServiceRuntimeBundleVO.State.DEPRECATED ||
                    target == StorageServiceRuntimeBundleVO.State.REVOKED;
            case DEPRECATED: return target == StorageServiceRuntimeBundleVO.State.REVOKED;
            default: return false;
        }
    }

    @Override
    public boolean deleteBundle(final DeleteStorageServiceRuntimeBundleCmd cmd) {
        final GlobalLock lock = GlobalLock.getInternLock("StorageRuntimeCatalog-" + cmd.getId());
        try {
            if (!lock.lock(10)) throw new CloudRuntimeException("Runtime catalog entry is being changed");
            try {
                final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(cmd.getId());
                if (bundle == null) return true;
                final boolean referenced = instanceDao.listAll().stream().anyMatch(instance ->
                        java.util.Objects.equals(instance.getCurrentRuntimeBundleId(), bundle.getId()) ||
                        java.util.Objects.equals(instance.getPreviousRuntimeBundleId(), bundle.getId())) ||
                        upgradeDao.listAll().stream().anyMatch(upgrade -> upgrade.getBundleId() == bundle.getId());
                if (referenced || bundle.getState() != StorageServiceRuntimeBundleVO.State.REGISTERED) {
                    throw new CloudRuntimeException("Only unused REGISTERED bundles can be removed; lifecycle and rollback history must be retained");
                }
                recordCatalogAudit(bundle, "REMOVED", "Unused registered bundle removed");
                bundleDao.update(bundle.getId(), bundle);
                return bundleDao.remove(bundle.getId());
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    private JsonObject catalogMetadata(StorageServiceRuntimeBundleVO bundle) {
        return bundle.getCatalogJson() == null ? new JsonObject() : new JsonParser().parse(bundle.getCatalogJson()).getAsJsonObject();
    }

    private void recordCatalogAudit(StorageServiceRuntimeBundleVO bundle, String action, String reason) {
        final JsonObject metadata = catalogMetadata(bundle);
        final JsonArray audit = metadata.has("audit") ? metadata.getAsJsonArray("audit") : new JsonArray();
        final JsonObject entry = new JsonObject();
        entry.addProperty("action", action);
        entry.addProperty("reason", reason);
        entry.addProperty("userId", CallContext.current().getCallingUserId());
        entry.addProperty("at", new Date().getTime());
        audit.add(entry);
        metadata.add("audit", audit);
        bundle.setCatalogJson(metadata.toString());
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
            bundles.addAll(cmd.isCatalog() ? bundleDao.listAll() : bundleDao.listAvailable());
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
            response.setDetails("Runtime updater is available");
        } catch (final RuntimeException error) {
            response.setAvailable(false);
            response.setDetails(error.getMessage());
        }
        response.setObjectName("storageserviceruntimecapability");
        return response;
    }

    @Override
    public JsonObject verifyAvailableBundle(final Long bundleId) {
        final StorageServiceRuntimeBundleVO bundle = requireBundle(bundleId);
        if (bundle.getServiceImpact() != StorageServiceRuntimeBundleVO.ServiceImpact.NONE) {
            throw new CloudRuntimeException("New-service runtime requires template maintenance");
        }
        // Read-only validation: no upgrade row, SystemVM command or resource allocation.
        return new StorageServiceRuntimeBundleVerifier().verify(bundle,
                download(bundle.getArtifactUrl(), MAX_BUNDLE_BYTES),
                download(bundle.getManifestUrl(), MAX_MANIFEST_BYTES),
                download(bundle.getSignatureUrl(), MAX_SIGNATURE_BYTES), trustedKey(bundle.getSigningKeyId()));
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse preflight(final PreflightStorageServiceRuntimeUpgradeCmd cmd) {
        StorageServiceInstanceVO instance = requireInstance(cmd.getSharedFileSystemId());
        return rootSerializedRuntime(instance, () -> doPreflight(cmd));
    }

    private StorageServiceRuntimeUpgradeResponse doPreflight(final PreflightStorageServiceRuntimeUpgradeCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getSharedFileSystemId());
        final StorageServiceRuntimeBundleVO bundle = requireBundle(cmd.getBundleId());
        if (bundle.getServiceImpact() != StorageServiceRuntimeBundleVO.ServiceImpact.NONE) {
            throw new CloudRuntimeException("Runtime bundle requires SystemVM template maintenance: " + bundle.getServiceImpact());
        }
        final StorageServiceRuntimeUpgradeVO active = upgradeDao.findActiveByInstanceId(instance.getId());
        if (active != null) {
            throw new CloudRuntimeException("Another Storage Service runtime upgrade is active: " + active.getUuid());
        }
        final String transactionId = "runtime-" + UUID.randomUUID().toString();
        final StorageServiceRuntimeUpgradeVO upgrade = upgradeDao.persist(new StorageServiceRuntimeUpgradeVO(
                instance.getId(), bundle.getId(), instance.getCurrentRuntimeBundleId(), transactionId,
                CallContext.current().getCallingUserId()));
        CallContext.current().setEventResourceId(upgrade.getId());
        try {
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "DOWNLOADING", 5);
            final byte[] archive = download(bundle.getArtifactUrl(), MAX_BUNDLE_BYTES);
            final byte[] manifest = download(bundle.getManifestUrl(), MAX_MANIFEST_BYTES);
            final byte[] signature = download(bundle.getSignatureUrl(), MAX_SIGNATURE_BYTES);
            verifyBytes(archive, bundle.getSha256(), "runtime bundle");
            verifyBytes(manifest, bundle.getManifestSha256(), "runtime manifest");
            final JsonObject verified = new StorageServiceRuntimeBundleVerifier().verify(bundle, archive, manifest, signature,
                    trustedKey(bundle.getSigningKeyId()));
            StorageRuntimeFeatureCompatibility.require(verified.getAsJsonObject("manifest"), requiredRuntimeFeatures(instance));
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
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "VERIFYING", 55);
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
        StorageServiceInstanceVO instance = instanceDao.findById(requireUpgrade(cmd.getUpgradeId()).getInstanceId());
        return rootSerializedRuntime(instance, () -> doUpgrade(cmd));
    }

    private StorageServiceRuntimeUpgradeResponse doUpgrade(final UpgradeStorageServiceRuntimeCmd cmd) {
        final StorageServiceRuntimeUpgradeVO upgrade = requireUpgrade(cmd.getUpgradeId());
        if (upgrade.getState() != StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY) {
            throw new CloudRuntimeException("Runtime upgrade is not ready for activation");
        }
        final StorageServiceInstanceVO instance = instanceDao.findById(upgrade.getInstanceId());
        final StorageServiceRuntimeBundleVO bundle = requireBundle(upgrade.getBundleId());
        try {
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "ACTIVATING", 70);
            final JsonObject activated = invoke(instance, StorageServiceRuntimeOperation.ACTIVATE,
                    upgrade.getTransactionId(), request(upgrade, bundle));
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "VERIFYING", 85);
            final StorageServiceGuestCommandResult health = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    instance.getVmId(), "operation verify", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!runtimeHealthVerified(health)) {
                final JsonObject rolledBack = invoke(instance, StorageServiceRuntimeOperation.ROLLBACK,
                        upgrade.getTransactionId(), request(upgrade, bundle));
                upgrade.setRollbackResultJson(rolledBack.toString());
                update(upgrade, StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK, "ROLLED_BACK", 100);
                throw new CloudRuntimeException("Runtime activation health verification failed and previous runtime was restored: " + health.getDetails());
            }
            final Long previous = instance.getCurrentRuntimeBundleId();
            instance.setPreviousRuntimeBundleId(previous);
            instance.setCurrentRuntimeBundleId(bundle.getId());
            instance.setRuntimeState("VERIFIED");
            instance.setRuntimeVerifiedAt(new Date());
            instanceDao.update(instance.getId(), instance);
            final JsonObject verification = new JsonObject();
            verification.add("activation", activated);
            verification.addProperty("healthSuccess", true);
            verification.addProperty("healthResult", health.getResultJson());
            upgrade.setVerificationJson(verification.toString());
            upgrade.setCompleted(new Date());
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.COMPLETE, "COMPLETE", 100);
            return upgradeResponse(upgrade);
        } catch (final RuntimeException error) {
            if (upgrade.getState() != StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) fail(upgrade, error);
            throw error;
        }
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse rollback(final RollbackStorageServiceRuntimeUpgradeCmd cmd) {
        StorageServiceInstanceVO instance = instanceDao.findById(requireUpgrade(cmd.getUpgradeId()).getInstanceId());
        return rootSerializedRuntime(instance, () -> doRollback(cmd));
    }

    private StorageServiceRuntimeUpgradeResponse doRollback(final RollbackStorageServiceRuntimeUpgradeCmd cmd) {
        final StorageServiceRuntimeUpgradeVO upgrade = requireUpgrade(cmd.getUpgradeId());
        final StorageServiceInstanceVO instance = instanceDao.findById(upgrade.getInstanceId());
        final StorageServiceRuntimeBundleVO bundle = bundleDao.findById(upgrade.getBundleId());
        final StorageServiceRuntimeBundleVO previous = instance.getPreviousRuntimeBundleId() == null ? null : bundleDao.findById(instance.getPreviousRuntimeBundleId());
        if (bundle == null || previous != null && previous.getState() == StorageServiceRuntimeBundleVO.State.REVOKED) {
            throw new CloudRuntimeException("Rollback destination is revoked or unavailable");
        }
        if (previous == null) throw new CloudRuntimeException("No previous runtime bundle is available");
        final byte[] previousArchive = download(previous.getArtifactUrl(), MAX_BUNDLE_BYTES);
        final byte[] previousManifest = download(previous.getManifestUrl(), MAX_MANIFEST_BYTES);
        final byte[] previousSignature = download(previous.getSignatureUrl(), MAX_SIGNATURE_BYTES);
        final JsonObject previousVerified = new StorageServiceRuntimeBundleVerifier().verify(previous, previousArchive, previousManifest, previousSignature,
                trustedKey(previous.getSigningKeyId()));
        StorageRuntimeFeatureCompatibility.require(previousVerified.getAsJsonObject("manifest"), requiredRuntimeFeatures(instance));
        final JsonObject result = invoke(instance, StorageServiceRuntimeOperation.ROLLBACK,
                upgrade.getTransactionId(), request(upgrade, bundle));
        final Long current = instance.getCurrentRuntimeBundleId();
        instance.setCurrentRuntimeBundleId(instance.getPreviousRuntimeBundleId());
        instance.setPreviousRuntimeBundleId(current);
        instance.setRuntimeState("ROLLED_BACK");
        instance.setRuntimeVerifiedAt(new Date());
        instanceDao.update(instance.getId(), instance);
        upgrade.setRollbackResultJson(result.toString());
        upgrade.setCompleted(new Date());
        update(upgrade, StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK, "ROLLED_BACK", 100);
        return upgradeResponse(upgrade);
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
        final List<StorageServiceRuntimeUpgradeResponse> responses = new ArrayList<>();
        for (final StorageServiceRuntimeUpgradeVO upgrade : upgrades) responses.add(upgradeResponse(upgrade));
        final ListResponse<StorageServiceRuntimeUpgradeResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected java.util.Set<String> requiredRuntimeFeatures(final StorageServiceInstanceVO instance) {
        final java.util.Set<String> features = new java.util.HashSet<>();
        if (!posixPolicyDao.listByInstance(instance.getId()).isEmpty()) features.add("POSIX_DIRECTORY_POLICY");
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB}) {
            for (StorageFileShareVO share : fileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                JsonObject config = new JsonParser().parse(share.getConfigJson() == null ? "{}" : share.getConfigJson()).getAsJsonObject();
                features.addAll(StorageRuntimeFeatureCompatibility.shareFeatures(config, protocol));
                if (protocol == StorageServiceInstance.Protocol.SMB) {
                    for (StorageAccessRuleVO rule : accessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                        if ((rule.getPrincipalType() == StorageServiceInstance.PrincipalType.CIDR || rule.getPrincipalType() == StorageServiceInstance.PrincipalType.IP_ADDRESS)
                                && rule.getState() != StorageServiceInstance.ResourceState.Disabled && rule.getState() != StorageServiceInstance.ResourceState.Destroyed) {
                            features.add("SMB_NETWORK_ACL");
                        }
                    }
                }
            }
        }
        for (StorageServiceProtocolVO protocol : protocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            JsonObject config = new JsonParser().parse(protocol.getConfigJson() == null ? "{}" : protocol.getConfigJson()).getAsJsonObject();
            if (config.has("idMappingMode") && "NUMERIC".equals(config.get("idMappingMode").getAsString())) features.add("NFS_NUMERIC_IDENTITY");
        }
        return features;
    }

    protected boolean runtimeHealthVerified(StorageServiceGuestCommandResult result) {
        if(result == null || !result.isSuccess() || result.getResultJson() == null)return false;
        try {
            JsonObject health=new JsonParser().parse(result.getResultJson()).getAsJsonObject();
            return health.has("success") && health.get("success").getAsBoolean() && health.has("status") && "ok".equalsIgnoreCase(health.get("status").getAsString());
        } catch(RuntimeException invalid){return false;}
    }

    protected void ensureBootstrap(final StorageServiceInstanceVO instance, final StorageServiceRuntimeBundleVO bundle,
            final String transactionId) {
        final StorageServiceRuntimeHostAnswer capability = runtimeDispatcher.dispatch(instance.getVmId(), new StorageServiceRuntimeHostCommand(
                vmName(instance), StorageServiceRuntimeOperation.CAPABILITIES, transactionId, null, timeout()));
        JsonObject observedCapability=!capability.getResult() || capability.getResultJson()==null || capability.getResultJson().isBlank()?new JsonObject():new JsonParser().parse(capability.getResultJson()).getAsJsonObject();
        if (!capability.getResult() || !observedCapability.has("signedRuntimeReadback") || !observedCapability.get("signedRuntimeReadback").getAsBoolean()
                || !observedCapability.has("updaterSha256") || !sha256(resource("/storage-runtime/bootstrap/runtime_updater.py")).equals(observedCapability.get("updaterSha256").getAsString())) {
            transfer(instance, transactionId, StorageServiceRuntimeFileType.UPDATER_MODULE, null,
                    resource("/storage-runtime/bootstrap/runtime_updater.py"), 0, 4, null);
            transfer(instance, transactionId, StorageServiceRuntimeFileType.UPDATER_ENTRY, null,
                    resource("/storage-runtime/bootstrap/ablestack-storage-runtime-updater"), 4, 8, null);
        }
        // Existing updater binaries may still have unmanaged legacy entrypoints.
        // Bootstrap is idempotent when a valid managed release already exists.
        invoke(instance, StorageServiceRuntimeOperation.BOOTSTRAP, transactionId, null);
        transfer(instance, transactionId, StorageServiceRuntimeFileType.TRUSTED_KEY, bundle.getSigningKeyId(),
                trustedKey(bundle.getSigningKeyId()), 8, 10, null);
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
            command.setFileSha256(sha256(value));
            final StorageServiceRuntimeHostAnswer answer = runtimeDispatcher.dispatch(instance.getVmId(), command);
            if (!answer.getResult()) throw new CloudRuntimeException(answer.getDetails());
            offset += length;
            if (upgrade != null) {
                final int progress = fromProgress + (int) (((long) offset * (toProgress - fromProgress)) / value.length);
                update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "RECEIVING_" + type.name(), progress);
            }
        }
    }

    protected JsonObject invoke(final StorageServiceInstanceVO instance, final StorageServiceRuntimeOperation operation,
            final String transactionId, final JsonObject request) {
        final StorageServiceRuntimeHostAnswer answer = runtimeDispatcher.dispatch(instance.getVmId(), new StorageServiceRuntimeHostCommand(
                vmName(instance), operation, transactionId, request == null ? null : request.toString(), timeout()));
        if (!answer.getResult()) throw new CloudRuntimeException(answer.getDetails());
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

    protected java.nio.file.Path trustedKeyDirectory() {
        return java.nio.file.Paths.get(StorageServiceInstance.StorageServiceRuntimeTrustedKeyDirectory.value());
    }

    protected byte[] trustedKey(final String keyId) {
        requireIdentifier(keyId, "signingkeyid");
        final java.nio.file.Path directory = trustedKeyDirectory().toAbsolutePath().normalize();
        final java.nio.file.Path path = directory.resolve(keyId + ".pem").normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("Invalid runtime signing key path");
        if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (java.nio.file.Files.isSymbolicLink(path) || java.nio.file.Files.size(path) > 16384) {
                    throw new CloudRuntimeException("Runtime public key file is unsafe");
                }
                final byte[] value = java.nio.file.Files.readAllBytes(path);
                if (!new String(value, java.nio.charset.StandardCharsets.US_ASCII).trim().startsWith("-----BEGIN PUBLIC KEY-----")) {
                    throw new CloudRuntimeException("Only public verification keys are accepted");
                }
                return value;
            } catch (IOException error) {
                throw new CloudRuntimeException("Unable to read the approved runtime public verification key", error);
            }
        }
        return resource("/storage-runtime/trusted-keys/" + keyId + ".pem");
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
            final URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            final String scheme = uri.getScheme();
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
        response.setCatalog(bundle.getCatalogJson());
        final List<String> consumers = new ArrayList<>();
        for (StorageServiceInstanceVO instance : instanceDao.listAll()) {
            if (java.util.Objects.equals(instance.getCurrentRuntimeBundleId(), bundle.getId())) consumers.add(instance.getUuid());
        }
        response.setInstances(consumers);
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
