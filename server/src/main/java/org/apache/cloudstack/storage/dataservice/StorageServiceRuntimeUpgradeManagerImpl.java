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
    @Override public boolean operationControlLinked() { return true; }
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
    @Inject private com.cloud.vm.dao.UserVmDao runtimeUserVmDao;
    @Inject private com.cloud.host.dao.HostDao runtimeHostDao;
    @Inject private com.cloud.storage.dao.VolumeDao runtimeVolumeDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao fileShareDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao protocolDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao accessRuleDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao posixPolicyDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao runtimeIdentityDomainDao;

    @Inject private javax.inject.Provider<StorageService> operationControlService;

    /** The caller owns StorageServiceWriter; these primitives never reacquire it. */
    protected RuntimeResourceScope beginRuntimeResourceScope(StorageServiceRuntimeUpgradeVO upgrade, boolean rollback) {
        StorageService service = operationControlService.get();
        return new RuntimeResourceScope(service, service.beginRuntimeOperationControl(upgrade.getId(), rollback));
    }

    protected final class RuntimeResourceScope {
        private final StorageService service;
        private final String operationUuid;
        private boolean effectStarted;
        private boolean finished;
        RuntimeResourceScope(StorageService service, String operationUuid) {
            this.service = service; this.operationUuid = operationUuid;
        }
        void beforeEffect() {
            if (operationUuid != null) service.verifyManagedOperationControl(operationUuid);
            effectStarted = true;
        }
        void terminal(String state) {
            if (operationUuid != null) service.finishManagedOperationControl(operationUuid, state);
            finished = true;
        }
        void failed(RuntimeException original) {
            if (finished || operationUuid == null) return;
            String state = effectStarted ? "RECOVERY_REQUIRED"
                    : original instanceof StorageOperationCancelledException ? "CANCELLED" : "BLOCKED";
            try { service.finishManagedOperationControl(operationUuid, state); finished = true; }
            catch (RuntimeException cleanup) { original.addSuppressed(cleanup); }
        }
        boolean hasEffects() { return effectStarted; }
    }

    protected <T> T finishRuntimeResourceScope(RuntimeResourceScope scope, String state, java.util.function.Supplier<T> projection) {
        scope.terminal(state);
        return projection.get();
    }

    protected void failRuntimeControlled(StorageServiceRuntimeUpgradeVO upgrade, RuntimeResourceScope scope, RuntimeException error) {
        if (scope != null) scope.failed(error);
        if (scope != null && scope.hasEffects()) {
            upgrade.setErrorCode("RUNTIME_RECOVERY_REQUIRED");
            upgrade.setErrorMessage(error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage().substring(0, Math.min(1024, error.getMessage().length())));
            upgrade.setCompleted(null);
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.MANUAL_RECOVERY, "RECOVERY_REQUIRED", upgrade.getProgress() == null ? 0 : upgrade.getProgress());
        } else {
            fail(upgrade, error);
        }
    }

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

    /** Runtime mutation must not turn an incomplete/orphan formatter journal into implicit approval. */
    protected void requireRuntimeActivationSafety(StorageServiceInstanceVO instance) {
        for (com.cloud.storage.VolumeVO volume : runtimeVolumeDao.findByInstanceAndType(instance.getVmId(), com.cloud.storage.Volume.Type.DATADISK)) {
            if (volume.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK || volume.getState() != com.cloud.storage.Volume.State.Ready
                    || volume.getRemoved() != null || !java.util.Objects.equals(volume.getInstanceId(), instance.getVmId())
                    || volume.getAccountId() != instance.getAccountId() || volume.getDataCenterId() != instance.getDataCenterId()) {
                throw new CloudRuntimeException("Attached DATA formatter scope is unavailable; preserve VM/DATA");
            }
            UUID.fromString(volume.getUuid());JsonObject payload = new JsonObject();payload.addProperty("volumeUuid", volume.getUuid());
            StorageServiceGuestCommandResult status = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "volume operation status", payload.toString(), 5, Collections.emptySet()));
            if (status == null || !status.isSuccess() || status.getResultJson() == null || status.getResultJson().isBlank()) {
                throw new CloudRuntimeException("Formatter journal is unobserved; runtime mutation must preserve VM/DATA");
            }
            StorageFormatterLifecycleGate.requireIdle(JsonParser.parseString(status.getResultJson()).getAsJsonObject());
        }
    }

    /** Only server/agent platform versions and the current protected guest manifest are observations. */
    protected JsonObject freshConsumerObservation(StorageServiceInstanceVO instance) {
        VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        com.cloud.host.HostVO host = vm == null || vm.getHostId() == null ? null : runtimeHostDao.findById(vm.getHostId());
        JsonObject caps = invoke(instance, StorageServiceRuntimeOperation.CAPABILITIES, "consumer-" + UUID.randomUUID(), null);
        VMInstanceVO currentVm = vmInstanceDao.findById(instance.getVmId());
        com.cloud.host.HostVO currentHost = currentVm == null || currentVm.getHostId() == null ? null : runtimeHostDao.findById(currentVm.getHostId());
        if (vm == null || currentVm == null || !java.util.Objects.equals(vm.getHostId(), currentVm.getHostId())
                || !java.util.Objects.equals(host == null ? null : host.getVersion(), currentHost == null ? null : currentHost.getVersion())) {
            throw new CloudRuntimeException("Runtime agent binding changed during fresh consumer observation");
        }
        JsonObject observation = new JsonObject();
        observation.add("managerVersion", nullableVersion(com.cloud.server.ManagementServer.class.getPackage().getImplementationVersion()));
        observation.add("agentVersion", nullableVersion(host == null ? null : host.getVersion()));
        boolean helperVerified = Boolean.TRUE.equals(booleanValue(caps, "signedRuntimeReadback"))
                && sha256(resource("/storage-runtime/bootstrap/runtime_updater.py")).equals(stringValue(caps, "updaterSha256"));
        boolean recorded = helperVerified && caps.has("platformVersionKnown") && caps.get("platformVersionKnown").isJsonPrimitive()
                && caps.get("platformVersionKnown").getAsJsonPrimitive().isBoolean() && caps.has("platformVersion");
        boolean claimedKnown = recorded && Boolean.TRUE.equals(booleanValue(caps, "platformVersionKnown"));
        boolean known = claimedKnown && validDigest(stringValue(caps, "templateManifestSha256"))
                && stringValue(caps, "platformVersion") != null
                && java.util.Objects.equals(stringValue(caps, "platformVersion"), stringValue(caps, "productVersion"));
        if ((claimedKnown && !known) || (recorded && !claimedKnown && !caps.get("platformVersion").isJsonNull())) recorded = false;
        observation.add("templatePlatformVersion", nullableVersion(known ? stringValue(caps, "platformVersion") : null));
        observation.addProperty("platformVersionKnown", known);
        observation.addProperty("platformObservationRecorded", recorded);
        observation.addProperty("updaterVerified", helperVerified);
        observation.add("templateManifestSha256", nullableVersion(known ? stringValue(caps, "templateManifestSha256") : null));
        observation.addProperty("observedAtMillis", System.currentTimeMillis());
        return observation;
    }

    protected JsonObject sourceRootBinding(StorageServiceInstanceVO instance) {
        VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        List<com.cloud.storage.VolumeVO> roots = runtimeVolumeDao.findByInstanceAndType(instance.getVmId(), com.cloud.storage.Volume.Type.ROOT);
        if (vm == null || roots.size() != 1) throw new CloudRuntimeException("Exactly one current ROOT is required for signed runtime provenance");
        com.cloud.storage.VolumeVO root = roots.get(0);
        if (root.getVolumeType() != com.cloud.storage.Volume.Type.ROOT || root.getState() != com.cloud.storage.Volume.State.Ready || root.getRemoved() != null
                || !java.util.Objects.equals(root.getInstanceId(), instance.getVmId()) || root.getAccountId() != instance.getAccountId()
                || root.getDataCenterId() != instance.getDataCenterId() || root.getTemplateId() == null || root.getTemplateId() != vm.getTemplateId()
                || root.getUuid() == null || instance.getUuid() == null) throw new CloudRuntimeException("ROOT runtime provenance binding is unavailable");
        JsonObject binding = new JsonObject();binding.addProperty("instanceUuid", instance.getUuid());binding.addProperty("vmId", vm.getId());
        binding.addProperty("rootVolumeId", root.getId());binding.addProperty("rootVolumeUuid", root.getUuid());binding.addProperty("templateId", vm.getTemplateId());
        binding.addProperty("accountId", instance.getAccountId());binding.addProperty("zoneId", instance.getDataCenterId());return binding;
    }

    protected JsonObject versionCompatibility(StorageServiceInstanceVO instance, JsonObject manifest,
            StorageRuntimeVersionCompatibility.Mode mode, StorageRuntimeVersionCompatibility.RetainedPreviousEvidence evidence) {
        JsonObject observation = freshConsumerObservation(instance);
        JsonObject verdict = StorageRuntimeVersionCompatibility.evaluate(manifest, stringValue(observation, "managerVersion"),
                stringValue(observation, "agentVersion"), stringValue(observation, "templatePlatformVersion"), mode, evidence);
        verdict.add("observation", observation);StorageRuntimeVersionCompatibility.requireCompatible(verdict);return verdict;
    }

    private JsonObject signedManifest(StorageServiceRuntimeBundleVO bundle) {
        return new StorageServiceRuntimeBundleVerifier().verify(bundle, download(bundle.getArtifactUrl(), MAX_BUNDLE_BYTES),
                download(bundle.getManifestUrl(), MAX_MANIFEST_BYTES), download(bundle.getSignatureUrl(), MAX_SIGNATURE_BYTES),
                trustedKey(bundle.getSigningKeyId())).getAsJsonObject("manifest");
    }

    private JsonObject installedCheckpoint(StorageServiceInstanceVO instance, StorageServiceRuntimeBundleVO bundle, String transactionScope) {
        if (instance.getCurrentRuntimeBundleId() == null || instance.getCurrentRuntimeBundleId() != bundle.getId()
                || instance.getRuntimeVerifiedAt() == null || instance.getRuntimeVerifiedAt().getTime() <= 0) {
            throw new CloudRuntimeException("Installed runtime lacks its approved signed LKG receipt");
        }
        JsonObject binding = sourceRootBinding(instance);JsonObject observation = freshConsumerObservation(instance);
        if (!Boolean.TRUE.equals(booleanValue(observation, "updaterVerified"))) throw new CloudRuntimeException("Fresh source platform observer provenance is unavailable");
        if (!sourceRootBinding(instance).equals(binding)) throw new CloudRuntimeException("ROOT changed during fresh runtime observation");
        String helper = requireTemplateRuntimeHelper(instance);
        JsonObject verification = stagePinnedRuntime(instance, bundle, transactionScope, "source", false);
        if (!helper.equals(stringValue(verification, "updaterSha256")) || !sourceRootBinding(instance).equals(binding)) {
            throw new CloudRuntimeException("Source ROOT or updater changed during installed LKG checkpoint");
        }
        JsonObject pin = runtimePin(bundle), approved = pin.deepCopy();approved.addProperty("verifiedAtMillis", instance.getRuntimeVerifiedAt().getTime());
        JsonObject checkpoint = new JsonObject();checkpoint.add("pin", pin);checkpoint.addProperty("updaterSha256", helper);
        checkpoint.add("sourceRootBinding", binding);checkpoint.add("consumerObservation", observation);checkpoint.add("approvedInstalledLkg", approved);
        checkpoint.add("verification", verification);return checkpoint;
    }

    protected StorageRuntimeVersionCompatibility.RetainedPreviousEvidence retainedPreviousEvidence(StorageServiceInstanceVO instance,
            StorageServiceRuntimeBundleVO bundle, JsonObject checkpoint, Long expectedRootId, Long expectedTemplateId) {
        if (checkpoint == null || !checkpoint.has("pin") || !runtimePin(bundle).equals(checkpoint.get("pin"))
                || !checkpoint.has("sourceRootBinding") || !sourceRootBinding(instance).equals(checkpoint.get("sourceRootBinding"))) {
            throw new CloudRuntimeException("Retained previous runtime belongs to another pin or ROOT binding");
        }
        JsonObject binding = checkpoint.getAsJsonObject("sourceRootBinding");
        if ((expectedRootId != null && binding.get("rootVolumeId").getAsLong() != expectedRootId)
                || (expectedTemplateId != null && binding.get("templateId").getAsLong() != expectedTemplateId)) {
            throw new CloudRuntimeException("Retained previous runtime is not the original ROOT");
        }
        JsonObject approved = checkpoint.has("approvedInstalledLkg") ? checkpoint.getAsJsonObject("approvedInstalledLkg").deepCopy() : null;
        if (approved == null || !approved.has("verifiedAtMillis") || approved.get("verifiedAtMillis").getAsLong() <= 0) {
            throw new CloudRuntimeException("Retained previous runtime lacks a protected LKG approval receipt");
        }
        approved.remove("verifiedAtMillis");
        if (!runtimePin(bundle).equals(approved) || !checkpoint.has("verification") || !checkpoint.has("consumerObservation")) {
            throw new CloudRuntimeException("Retained previous runtime protected approval provenance differs");
        }
        JsonObject verification = requireRuntimeReadback(checkpoint.getAsJsonObject("verification"), bundle);
        if (!validDigest(stringValue(checkpoint, "updaterSha256"))
                || !sha256(resource("/storage-runtime/bootstrap/runtime_updater.py")).equals(stringValue(checkpoint, "updaterSha256"))
                || !java.util.Objects.equals(stringValue(checkpoint, "updaterSha256"), stringValue(verification, "updaterSha256"))) {
            throw new CloudRuntimeException("Retained previous runtime readback helper provenance differs");
        }
        JsonObject observation = checkpoint.getAsJsonObject("consumerObservation");
        if (!observation.has("observedAtMillis") || observation.get("observedAtMillis").getAsLong() <= 0
                || !Boolean.TRUE.equals(booleanValue(observation, "updaterVerified"))) throw new CloudRuntimeException("Retained source consumer observation is not protected");
        boolean originalUnknown = Boolean.TRUE.equals(booleanValue(observation, "platformObservationRecorded")) && !Boolean.TRUE.equals(booleanValue(observation, "platformVersionKnown"))
                && observation.has("templatePlatformVersion") && observation.get("templatePlatformVersion").isJsonNull();
        return new StorageRuntimeVersionCompatibility.RetainedPreviousEvidence(bundle.getVersion(), bundle.getManifestSha256(),
                stringValue(verification, "manifestSha256"), bundle.getSha256(), stringValue(verification, "archiveSha256"), true, true, true, true, originalUnknown);
    }

    private JsonObject originalRootCheckpoint(StorageServiceInstanceVO instance, StorageServiceRuntimeBundleVO bundle) {
        StorageServiceTemplateUpgradeVO root = rootUpgradeDao.findActive(instance.getId());
        if (root == null || root.getSnapshotJson() == null) throw new CloudRuntimeException("Original source ROOT runtime checkpoint is unavailable");
        JsonObject snapshot = JsonParser.parseString(root.getSnapshotJson()).getAsJsonObject();
        JsonObject checkpoint = snapshot.has("sourceSignedRuntime") ? snapshot.getAsJsonObject("sourceSignedRuntime") : snapshot.getAsJsonObject("signedRuntime");
        retainedPreviousEvidence(instance, bundle, checkpoint, root.getPreviousRootVolumeId(), root.getSourceTemplateId());return checkpoint;
    }
    private JsonObject genericRollbackCompatibility(StorageServiceInstanceVO instance, StorageServiceRuntimeUpgradeVO upgrade,
            StorageServiceRuntimeBundleVO previous) {
        if (previous == null || upgrade.getPreviousBundleId() == null || upgrade.getPreviousBundleId() != previous.getId()) {
            throw new CloudRuntimeException("Rollback destination differs from this upgrade's original previous bundle");
        }
        requireBundle(previous.getId());
        JsonObject preflight = upgrade.getPreflightJson() == null ? null : JsonParser.parseString(upgrade.getPreflightJson()).getAsJsonObject();
        JsonObject checkpoint = preflight == null ? null : preflight.getAsJsonObject("sourceSignedRuntime");
        JsonObject manifest = signedManifest(previous);requireSignedRuntimeFeatures(instance, manifest, previous);
        StorageRuntimeVersionCompatibility.RetainedPreviousEvidence evidence = retainedPreviousEvidence(instance, previous, checkpoint, null, null);
        return versionCompatibility(instance, manifest, StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK, evidence);
    }
    private JsonObject verifyGenericPrevious(StorageServiceInstanceVO instance, StorageServiceRuntimeUpgradeVO upgrade, StorageServiceRuntimeBundleVO previous) {
        String transaction = "root-source-" + upgrade.getTransactionId();JsonObject request = runtimePin(previous);request.addProperty("transactionId", transaction);
        return requireRuntimeReadback(invoke(instance, StorageServiceRuntimeOperation.READBACK, transaction, request), previous);
    }

    private static com.google.gson.JsonElement nullableVersion(String value) {return value == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value);}
    private static boolean validDigest(String value) {return value != null && value.matches("[a-f0-9]{64}");}

    protected JsonObject runtimeValidationHostBinding(StorageServiceInstanceVO instance) {
        VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        com.cloud.host.HostVO host = vm == null || vm.getHostId() == null ? null : runtimeHostDao.findById(vm.getHostId());
        if (vm == null || host == null || vm.getAccountId() != instance.getAccountId() || vm.getDataCenterId() != instance.getDataCenterId()
                || vm.getUuid() == null || host.getUuid() == null || host.getVersion() == null) {
            throw new CloudRuntimeException("Signed validation runtime host binding is unavailable");
        }
        JsonObject binding = new JsonObject();
        binding.addProperty("vmId", vm.getId());binding.addProperty("vmUuid", vm.getUuid());
        binding.addProperty("hostId", host.getId());binding.addProperty("hostUuid", host.getUuid());binding.addProperty("agentVersion", host.getVersion());
        binding.addProperty("accountId", instance.getAccountId());binding.addProperty("zoneId", instance.getDataCenterId());return binding;
    }

    protected String runtimeManifestCliSha256(JsonObject manifest) {
        if (!manifest.has("files") || !manifest.get("files").isJsonArray()) throw new CloudRuntimeException("Signed runtime file manifest is unavailable");
        String cli = null;
        for (com.google.gson.JsonElement entry : manifest.getAsJsonArray("files")) {
            if (!entry.isJsonObject()) throw new CloudRuntimeException("Signed runtime file manifest is malformed");
            JsonObject file = entry.getAsJsonObject();
            if ("ablestack-storagectl".equals(stringValue(file, "path"))) {
                if (cli != null || !validDigest(stringValue(file, "sha256"))) throw new CloudRuntimeException("Signed runtime CLI file hash is ambiguous or unavailable");
                cli = stringValue(file, "sha256");
            }
        }
        if (cli == null) throw new CloudRuntimeException("Signed runtime CLI file hash is unavailable");return cli;
    }

    @Override public JsonObject freshSignedRuntimeValidationProof(long instanceId, String expectedCliSha256) {
        if (!validDigest(expectedCliSha256)) throw new CloudRuntimeException("Validation profile CLI hash must be an exact SHA256");
        StorageServiceInstanceVO instance = instanceDao.findById(instanceId);
        if (instance == null || instance.getVmId() == null || instance.getCurrentRuntimeBundleId() == null
                || instance.getRuntimeVerifiedAt() == null || instance.getRuntimeVerifiedAt().getTime() <= 0) {
            throw new CloudRuntimeException("Validation fixture lacks an approved installed signed runtime receipt");
        }
        StorageServiceRuntimeBundleVO bundle = requireBundle(instance.getCurrentRuntimeBundleId());
        JsonObject pin = runtimePin(bundle), rootBinding = sourceRootBinding(instance), hostBinding = runtimeValidationHostBinding(instance);
        JsonObject manifest = signedManifest(bundle);
        StorageRuntimeFeatureCompatibility.advertised(manifest);
        JsonArray signedFeatures = manifest.has("supportedFeatures") ? manifest.getAsJsonArray("supportedFeatures").deepCopy() : new JsonArray();
        String cliSha = runtimeManifestCliSha256(manifest);
        if (!expectedCliSha256.equals(cliSha)) throw new CloudRuntimeException("Installed signed runtime CLI differs from the validation profile artifact");
        StorageServiceRuntimeUpgradeVO receipt = null;
        for (StorageServiceRuntimeUpgradeVO row : upgradeDao.listByInstanceId(instanceId)) {
            if (row.getInstanceId() != instanceId || !java.util.Objects.equals(row.getBundleId(), bundle.getId())
                    || row.getState() != StorageServiceRuntimeUpgradeVO.State.COMPLETE || row.getCompleted() == null
                    || row.getTransactionId() == null || row.getTransactionId().isBlank()) continue;
            if (receipt == null || row.getCompleted().after(receipt.getCompleted())) receipt = row;
        }
        if (receipt == null) throw new CloudRuntimeException("Validation fixture requires a completed normal signed runtime upgrade transaction for read-only verification");
        JsonObject consumer = freshConsumerObservation(instance);
        String updater = sha256(resource("/storage-runtime/bootstrap/runtime_updater.py"));
        if (!Boolean.TRUE.equals(booleanValue(consumer, "updaterVerified"))) throw new CloudRuntimeException("Validation fixture runtime observer is unverified");
        JsonObject request = pin.deepCopy();request.addProperty("transactionId", receipt.getTransactionId());
        JsonObject readback = requireRuntimeReadback(invoke(instance, StorageServiceRuntimeOperation.READBACK, receipt.getTransactionId(), request), bundle);
        if (!updater.equals(stringValue(readback, "updaterSha256"))) throw new CloudRuntimeException("Validation fixture signed runtime readback helper differs from the pinned observer");
        StorageServiceInstanceVO fresh = instanceDao.findById(instanceId);
        if (fresh == null || !java.util.Objects.equals(fresh.getCurrentRuntimeBundleId(), bundle.getId())
                || !java.util.Objects.equals(fresh.getVmId(), instance.getVmId()) || !java.util.Objects.equals(fresh.getUuid(), instance.getUuid())
                || !sourceRootBinding(fresh).equals(rootBinding) || !runtimeValidationHostBinding(fresh).equals(hostBinding)) {
            throw new CloudRuntimeException("Validation fixture runtime, ROOT, or host binding changed during readback");
        }
        pinnedBundle(pin);
        JsonObject proof = new JsonObject();proof.addProperty("schemaVersion", 1);proof.addProperty("readOnly", true);
        proof.addProperty("signedRuntimeVerified", true);proof.add("runtimePin", pin);proof.addProperty("actualCliSha256", cliSha);
        proof.add("signedSupportedFeatures", signedFeatures);
        proof.addProperty("cliHashEvidence", "SIGNED_READBACK_FILE_HASH_AND_ENTRYPOINT_BINDING");
        proof.addProperty("nativeFileHashesVerified", true);proof.addProperty("transactionId", receipt.getTransactionId());
        proof.addProperty("approvedRuntimeVerifiedAtMillis", instance.getRuntimeVerifiedAt().getTime());
        proof.add("consumerObservation", consumer);proof.add("sourceRootBinding", rootBinding);proof.add("hostBinding", hostBinding);
        proof.addProperty("updaterSha256", updater);proof.addProperty("observedAtMillis", System.currentTimeMillis());return proof;
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
        return installedCheckpoint(instance, requireBundle(instance.getCurrentRuntimeBundleId()), rootOperationUuid);
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
    private static long retainedApprovalId(JsonObject value, String name) {
        try {
            if (!value.has(name) || !value.get(name).isJsonPrimitive()
                    || !value.get(name).getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("not a typed numeric binding");
            }
            long result = value.get(name).getAsBigDecimal().longValueExact();
            if (result <= 0) throw new IllegalArgumentException("not a positive binding");
            return result;
        } catch (RuntimeException invalid) {
            throw new CloudRuntimeException("Retained latest runtime approval has an invalid " + name);
        }
    }

    private JsonObject requireRetainedLatestApproval(long instanceId, JsonObject pin, String operationUuid,
            JsonObject approval) {
        StorageServiceInstanceVO instance = instanceDao.findById(instanceId);
        StorageServiceOperationVO operation = rootWriterDao.findByUuid(operationUuid);
        StorageServiceTemplateUpgradeVO root = rootUpgradeDao.findActive(instanceId);
        if (instance == null || instance.getVmId() == null || operation == null || root == null
                || !operationUuid.equals(operation.getUuid()) || operation.getInstanceId() != instanceId
                || root.getInstanceId() != instanceId || !"RUNNING".equals(operation.getState())
                || !"ROOT_TEMPLATE_ROLLBACK".equals(operation.getAction())
                || !Long.valueOf(operation.getId()).equals(root.getOperationId()) || root.getSnapshotJson() == null
                || root.getTargetRootVolumeId() == null || root.getTargetRootVolumeId() == root.getPreviousRootVolumeId()) {
            throw new CloudRuntimeException("Retained latest runtime requires its active manual ROOT rollback writer");
        }
        UUID.fromString(operationUuid);
        SharedFSVO shared = sharedFSDao.findById(root.getSharedFilesystemId());
        if (shared == null || !instance.getVmId().equals(shared.getVmId()) || shared.getAccountId() != instance.getAccountId()
                || shared.getDomainId() != instance.getDomainId() || shared.getDataCenterId() == null
                || shared.getDataCenterId() != instance.getDataCenterId()) {
            throw new CloudRuntimeException("Retained latest runtime belongs to another SharedFS VM or tenant");
        }
        JsonObject snapshot = JsonParser.parseString(root.getSnapshotJson()).getAsJsonObject();
        if (!Boolean.TRUE.equals(booleanValue(snapshot, "manualSourcePrepared"))
                || !snapshot.has("manualRollbackGeneration") || !snapshot.get("manualRollbackGeneration").isJsonObject()
                || !snapshot.has("sourceCapture") || !snapshot.get("sourceCapture").isJsonObject()
                || !snapshot.has("identity") || !snapshot.get("identity").isJsonObject()
                || !snapshot.has("manualSourceSignedRuntime") || !snapshot.get("manualSourceSignedRuntime").isJsonObject()
                || !snapshot.has("manualSourceValidationProfile") || !snapshot.get("manualSourceValidationProfile").isJsonObject()) {
            throw new CloudRuntimeException("Retained latest runtime lacks its protected prepared source snapshot");
        }
        JsonObject scope = new JsonObject(); scope.addProperty("instanceUuid", instance.getUuid());
        scope.addProperty("templateUpgradeUuid", root.getUuid()); scope.addProperty("operationUuid", operationUuid);
        scope.addProperty("revision", operation.getRevision());
        if (operation.getRevision() <= 0 || !scope.equals(snapshot.getAsJsonObject("sourceCapture").get("scope"))
                || !scope.equals(snapshot.getAsJsonObject("identity").get("sourceRootScope"))) {
            throw new CloudRuntimeException("Retained latest runtime source capture has another ROOT scope or revision");
        }
        JsonObject expected = new JsonObject(); expected.add("rootScope", scope);
        expected.add("sourceRuntime", snapshot.get("manualSourceSignedRuntime").deepCopy());
        expected.add("sourceValidationProfile", snapshot.get("manualSourceValidationProfile").deepCopy());
        expected.addProperty("sourceRootVolumeId", root.getTargetRootVolumeId());
        expected.addProperty("sourceTemplateId", root.getTargetTemplateId());
        expected.addProperty("targetRootVolumeId", root.getPreviousRootVolumeId());
        expected.addProperty("targetTemplateId", root.getSourceTemplateId());
        if (approval == null || !expected.equals(approval)
                || retainedApprovalId(snapshot, "manualSourceRootVolumeId") != root.getTargetRootVolumeId()
                || retainedApprovalId(snapshot, "manualSourceTemplateId") != root.getTargetTemplateId()) {
            throw new CloudRuntimeException("Retained latest runtime approval differs from the protected DB snapshot");
        }
        for (String name : new String[]{"sourceRootVolumeId", "sourceTemplateId", "targetRootVolumeId", "targetTemplateId"}) {
            retainedApprovalId(approval, name);
        }
        JsonObject checkpoint = snapshot.getAsJsonObject("manualSourceSignedRuntime");
        StorageServiceRuntimeBundleVO bundle = pinnedBundle(pin);
        if (!pin.equals(checkpoint.get("pin")) || !checkpoint.has("approvedInstalledLkg")
                || !checkpoint.get("approvedInstalledLkg").isJsonObject() || !checkpoint.has("sourceRootBinding")
                || !checkpoint.get("sourceRootBinding").isJsonObject() || !checkpoint.has("verification")
                || !checkpoint.get("verification").isJsonObject()) {
            throw new CloudRuntimeException("Retained latest runtime signed source pin or approval is unavailable");
        }
        JsonObject installed = checkpoint.getAsJsonObject("approvedInstalledLkg").deepCopy();
        retainedApprovalId(installed, "verifiedAtMillis"); installed.remove("verifiedAtMillis");
        JsonObject observed = requireRuntimeReadback(checkpoint.getAsJsonObject("verification"), bundle);
        String helper = sha256(resource("/storage-runtime/bootstrap/runtime_updater.py"));
        if (!pin.equals(installed) || !helper.equals(stringValue(checkpoint, "updaterSha256"))
                || !helper.equals(stringValue(observed, "updaterSha256"))) {
            throw new CloudRuntimeException("Retained latest runtime source signed LKG or updater provenance changed");
        }
        JsonObject source = checkpoint.getAsJsonObject("sourceRootBinding");
        com.cloud.storage.VolumeVO sourceVolume = runtimeVolumeDao.findById(root.getTargetRootVolumeId());
        if (sourceVolume == null || sourceVolume.getRemoved() != null || sourceVolume.getState() != com.cloud.storage.Volume.State.Ready
                || sourceVolume.getVolumeType() != com.cloud.storage.Volume.Type.ROOT || sourceVolume.getPoolId() == null
                || sourceVolume.getAccountId() != instance.getAccountId() || sourceVolume.getDataCenterId() != instance.getDataCenterId()
                || sourceVolume.getTemplateId() == null || sourceVolume.getUuid() == null || sourceVolume.getTemplateId() != root.getTargetTemplateId()
                || !instance.getUuid().equals(stringValue(source, "instanceUuid"))
                || retainedApprovalId(source, "vmId") != instance.getVmId()
                || retainedApprovalId(source, "rootVolumeId") != root.getTargetRootVolumeId()
                || !sourceVolume.getUuid().equals(stringValue(source, "rootVolumeUuid"))
                || retainedApprovalId(source, "templateId") != root.getTargetTemplateId()
                || retainedApprovalId(source, "accountId") != instance.getAccountId()
                || retainedApprovalId(source, "zoneId") != instance.getDataCenterId()) {
            throw new CloudRuntimeException("Retained latest runtime source ROOT binding was replaced or belongs to another tenant");
        }
        VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        com.cloud.vm.UserVmVO userVm = runtimeUserVmDao.findById(instance.getVmId());
        com.cloud.storage.VolumeVO targetVolume = runtimeVolumeDao.findById(root.getPreviousRootVolumeId());
        if (vm == null || vm.getState() != com.cloud.vm.VirtualMachine.State.Running || vm.getHostId() == null
                || vm.getType() != com.cloud.vm.VirtualMachine.Type.User
                || userVm == null || !com.cloud.vm.UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())
                || vm.getHypervisorType() != com.cloud.hypervisor.Hypervisor.HypervisorType.KVM
                || vm.getTemplateId() != root.getSourceTemplateId() || targetVolume == null
                || targetVolume.getState() != com.cloud.storage.Volume.State.Ready || targetVolume.getRemoved() != null
                || targetVolume.getVolumeType() != com.cloud.storage.Volume.Type.ROOT || targetVolume.getPoolId() == null
                || !instance.getVmId().equals(targetVolume.getInstanceId()) || targetVolume.getAccountId() != instance.getAccountId()
                || targetVolume.getDataCenterId() != instance.getDataCenterId() || targetVolume.getTemplateId() == null
                || targetVolume.getTemplateId() != root.getSourceTemplateId()) {
            throw new CloudRuntimeException("Retained latest runtime current ROOT or host placement is unavailable");
        }
        JsonObject target = sourceRootBinding(instance);
        if (retainedApprovalId(target, "rootVolumeId") != root.getPreviousRootVolumeId()
                || retainedApprovalId(target, "templateId") != root.getSourceTemplateId()) {
            throw new CloudRuntimeException("Retained latest runtime is not running on the approved retained ROOT");
        }
        return target;
    }

    @Override public JsonObject restoreRetainedLatestTemplateRuntime(long instanceId, JsonObject pin,
            String operationUuid, JsonObject frozenSourceApproval) {
        JsonObject approved = frozenSourceApproval == null ? null : frozenSourceApproval.deepCopy();
        JsonObject frozenPin = pin == null ? null : pin.deepCopy();
        JsonObject target = requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved);
        StorageServiceInstanceVO instance = instanceDao.findById(instanceId);
        String helper = requireTemplateRuntimeHelper(instance);
        requireRuntimePackageFeatures(instance);
        JsonObject result = stagePinnedRuntime(instance, pinnedBundle(frozenPin), operationUuid, "retained-latest", true,
                () -> { if (!target.equals(requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved))) {
                    throw new CloudRuntimeException("Retained latest runtime approval changed before effects");
                } });
        if (!helper.equals(stringValue(result, "updaterSha256"))
                || !target.equals(requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved))) {
            throw new CloudRuntimeException("Retained latest runtime target or protected approval changed during activation");
        }
        return result;
    }

    @Override public JsonObject verifyRetainedLatestTemplateRuntime(long instanceId, JsonObject pin,
            String operationUuid, JsonObject frozenSourceApproval) {
        JsonObject approved = frozenSourceApproval == null ? null : frozenSourceApproval.deepCopy();
        JsonObject frozenPin = pin == null ? null : pin.deepCopy();
        JsonObject target = requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved);
        StorageServiceInstanceVO instance = instanceDao.findById(instanceId);
        StorageServiceRuntimeBundleVO bundle = pinnedBundle(frozenPin);
        String helper = requireTemplateRuntimeHelper(instance);
        JsonObject manifest = signedManifest(bundle); requireSignedRuntimeFeatures(instance, manifest);
        JsonObject compatible = versionCompatibility(instance, manifest, StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION, null);
        requireRuntimePackageFeatures(instance); requireRuntimeActivationSafety(instance);
        String transaction = "root-retained-latest-" + operationUuid;
        JsonObject request = runtimePin(bundle); request.addProperty("transactionId", transaction);
        if (!target.equals(requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved))) {
            throw new CloudRuntimeException("Retained latest runtime approval changed before readback");
        }
        JsonObject result = requireRuntimeReadback(invoke(instance, StorageServiceRuntimeOperation.READBACK, transaction, request), bundle);
        if (!helper.equals(stringValue(result, "updaterSha256"))
                || !target.equals(requireRetainedLatestApproval(instanceId, frozenPin, operationUuid, approved))) {
            throw new CloudRuntimeException("Retained latest runtime target or protected approval changed during verification");
        }
        result.add("consumerCompatibility", compatible); return result;
    }

    protected JsonObject requireRuntimeReadback(JsonObject result,StorageServiceRuntimeBundleVO bundle) {
        if (!Boolean.TRUE.equals(booleanValue(result, "success"))
                || !Boolean.TRUE.equals(booleanValue(result, "signedRuntimeVerified"))
                || !Boolean.TRUE.equals(booleanValue(result, "installedFilesVerified"))
                || !Boolean.TRUE.equals(booleanValue(result, "entrypointsVerified"))
                || !bundle.getVersion().equals(result.has("currentVersion")?result.get("currentVersion").getAsString():null)
                || !bundle.getSha256().equals(result.has("archiveSha256")?result.get("archiveSha256").getAsString():null)
                || !bundle.getManifestSha256().equals(result.has("manifestSha256")?result.get("manifestSha256").getAsString():null)) {
            throw new CloudRuntimeException("Installed ROOT runtime code differs from its pinned signed bundle");
        }
        return result;
    }
    private JsonObject stagePinnedRuntime(StorageServiceInstanceVO instance,StorageServiceRuntimeBundleVO bundle,String operationUuid,String direction,boolean activate) {
        return stagePinnedRuntime(instance, bundle, operationUuid, direction, activate, null);
    }
    private JsonObject stagePinnedRuntime(StorageServiceInstanceVO instance,StorageServiceRuntimeBundleVO bundle,
            String operationUuid,String direction,boolean activate,Runnable approvalGuard) {
        if (bundle.getServiceImpact()!=StorageServiceRuntimeBundleVO.ServiceImpact.NONE) throw new CloudRuntimeException("Pinned runtime requires additional template maintenance");
        byte[] archive=download(bundle.getArtifactUrl(),MAX_BUNDLE_BYTES),manifest=download(bundle.getManifestUrl(),MAX_MANIFEST_BYTES),signature=download(bundle.getSignatureUrl(),MAX_SIGNATURE_BYTES);
        JsonObject verified=new StorageServiceRuntimeBundleVerifier().verify(bundle,archive,manifest,signature,trustedKey(bundle.getSigningKeyId()));
        if (activate) requireSignedRuntimeFeatures(instance, verified.getAsJsonObject("manifest"));
        JsonObject checkpoint = activate && "previous".equals(direction) ? originalRootCheckpoint(instance, bundle) : null;
        StorageRuntimeVersionCompatibility.Mode mode = checkpoint == null ? StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION : StorageRuntimeVersionCompatibility.Mode.RETAINED_PREVIOUS_ROLLBACK;
        StorageRuntimeVersionCompatibility.RetainedPreviousEvidence evidence = checkpoint == null ? null : retainedPreviousEvidence(instance, bundle, checkpoint, null, null);
        JsonObject compatibility = activate ? versionCompatibility(instance, verified.getAsJsonObject("manifest"), mode, evidence) : null;
        requireRuntimeActivationSafety(instance);
        if (approvalGuard != null) approvalGuard.run();
        String transaction="root-"+direction+"-"+operationUuid;ensureBootstrap(instance,bundle,transaction);
        JsonObject request=runtimePin(bundle);request.addProperty("transactionId",transaction);request.addProperty("totalSize",archive.length);request.addProperty("manifestSize",manifest.length);request.addProperty("signatureSize",signature.length);
        JsonObject started=invoke(instance,StorageServiceRuntimeOperation.BEGIN,transaction,request);String phase=started.has("phase")?started.get("phase").getAsString():null;
        if (java.util.Set.of("RECEIVING","RECEIVED").contains(phase)) {
            transfer(instance,transaction,StorageServiceRuntimeFileType.BUNDLE,null,archive,0,0,null);transfer(instance,transaction,StorageServiceRuntimeFileType.MANIFEST,null,manifest,0,0,null);transfer(instance,transaction,StorageServiceRuntimeFileType.SIGNATURE,null,signature,0,0,null);
            invoke(instance,StorageServiceRuntimeOperation.FINALIZE,transaction,request);invoke(instance,StorageServiceRuntimeOperation.VERIFY,transaction,request);phase="VERIFIED";
        }
        if (activate && ("VERIFIED".equals(phase) || "PREFLIGHT_OK".equals(phase))) {
            invoke(instance,StorageServiceRuntimeOperation.PREFLIGHT,transaction,request);
            pinnedBundle(runtimePin(bundle));requireSignedRuntimeFeatures(instance, verified.getAsJsonObject("manifest"));
            compatibility = versionCompatibility(instance, verified.getAsJsonObject("manifest"), mode, evidence);
            requireRuntimeActivationSafety(instance); if (approvalGuard != null) approvalGuard.run();
            invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,transaction,request);
        } else if (activate && ("ACTIVATING".equals(phase) || "COMPLETE".equals(phase))) {
            pinnedBundle(runtimePin(bundle));requireSignedRuntimeFeatures(instance, verified.getAsJsonObject("manifest"));
            compatibility = versionCompatibility(instance, verified.getAsJsonObject("manifest"), mode, evidence);
            requireRuntimeActivationSafety(instance); if (approvalGuard != null) approvalGuard.run();
            invoke(instance,StorageServiceRuntimeOperation.ACTIVATE,transaction,request);
        }
        JsonObject readback = requireRuntimeReadback(invoke(instance,StorageServiceRuntimeOperation.READBACK,transaction,request),bundle);
        if (activate) requireRuntimePackageFeatures(instance);
        if (compatibility != null) readback.add("consumerCompatibility", compatibility);return readback;
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
            response.setConsumerObservation(freshConsumerObservation(instance).toString());
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
        requireRuntimeActivationSafety(instance);
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
            requireSignedRuntimeFeatures(instance, verified.getAsJsonObject("manifest"), bundle);
            final JsonObject consumerCompatibility = versionCompatibility(instance, verified.getAsJsonObject("manifest"), StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION, null);
            final JsonObject sourceCheckpoint = instance.getCurrentRuntimeBundleId() == null ? null
                    : installedCheckpoint(instance, requireBundle(instance.getCurrentRuntimeBundleId()), transactionId);
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
            result.add("targetRuntimePin", runtimePin(bundle));result.add("consumerCompatibility", consumerCompatibility);
            if (sourceCheckpoint != null) result.add("sourceSignedRuntime", sourceCheckpoint);
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
        RuntimeResourceScope control = null;
        try {
            JsonObject preflight = upgrade.getPreflightJson() == null ? null : JsonParser.parseString(upgrade.getPreflightJson()).getAsJsonObject();
            if (preflight == null || !preflight.has("targetRuntimePin") || !runtimePin(bundle).equals(preflight.get("targetRuntimePin"))) {
                throw new CloudRuntimeException("Runtime activation differs from its protected preflight pin");
            }
            if (preflight.has("sourceSignedRuntime") && !sourceRootBinding(instance).equals(preflight.getAsJsonObject("sourceSignedRuntime").get("sourceRootBinding"))) {
                throw new CloudRuntimeException("Source ROOT changed after runtime preflight");
            }
            JsonObject manifest = signedManifest(bundle);requireSignedRuntimeFeatures(instance, manifest, bundle);
            JsonObject compatibility = versionCompatibility(instance, manifest, StorageRuntimeVersionCompatibility.Mode.NEW_ACTIVATION, null);
            pinnedBundle(runtimePin(bundle));
            requireRuntimeActivationSafety(instance);
            control = beginRuntimeResourceScope(upgrade, false);
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "ACTIVATING", 70);
            control.beforeEffect();
            final JsonObject activated = invoke(instance, StorageServiceRuntimeOperation.ACTIVATE,
                    upgrade.getTransactionId(), request(upgrade, bundle));
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.RUNNING, "VERIFYING", 85);
            final StorageServiceGuestCommandResult health = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    instance.getVmId(), "operation verify", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!runtimeHealthVerified(health)) {
                StorageServiceRuntimeBundleVO previous = upgrade.getPreviousBundleId() == null ? null : bundleDao.findById(upgrade.getPreviousBundleId());
                JsonObject rollbackCompatibility = genericRollbackCompatibility(instance, upgrade, previous);requireRuntimeActivationSafety(instance);
                control.beforeEffect();
                final JsonObject rolledBack = invoke(instance, StorageServiceRuntimeOperation.ROLLBACK,
                        upgrade.getTransactionId(), request(upgrade, bundle));
                rolledBack.add("consumerCompatibility", rollbackCompatibility);rolledBack.add("installedPreviousReadback", verifyGenericPrevious(instance, upgrade, previous));
                requireRuntimePackageFeatures(instance);
                upgrade.setRollbackResultJson(rolledBack.toString());
                control.terminal("ROLLED_BACK");
                update(upgrade, StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK, "ROLLED_BACK", 100);
                throw new CloudRuntimeException("Runtime activation health verification failed and previous runtime was restored: " + health.getDetails());
            }
            requireRuntimePackageFeatures(instance);
            return finishRuntimeResourceScope(control, "COMPLETE", () -> {
            final Long previous = instance.getCurrentRuntimeBundleId();
            instance.setPreviousRuntimeBundleId(previous);
            instance.setCurrentRuntimeBundleId(bundle.getId());
            instance.setRuntimeState("VERIFIED");
            instance.setRuntimeVerifiedAt(new Date());
            if (!instanceDao.update(instance.getId(), instance)) throw new CloudRuntimeException("Verified runtime projection could not be persisted");
            final JsonObject verification = new JsonObject();
            verification.add("activation", activated);verification.add("consumerCompatibility", compatibility);
            verification.addProperty("healthSuccess", true);
            verification.addProperty("healthResult", health.getResultJson());
            upgrade.setVerificationJson(verification.toString());
            upgrade.setCompleted(new Date());
            update(upgrade, StorageServiceRuntimeUpgradeVO.State.COMPLETE, "COMPLETE", 100);
            return upgradeResponse(upgrade);
            });
        } catch (final RuntimeException error) {
            if (upgrade.getState() != StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK) failRuntimeControlled(upgrade, control, error);
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
        if (!java.util.Objects.equals(instance.getCurrentRuntimeBundleId(), bundle.getId())) {
            throw new CloudRuntimeException("Rollback source is no longer this upgrade's active bundle");
        }
        final JsonObject compatibility = genericRollbackCompatibility(instance, upgrade, previous);requireRuntimeActivationSafety(instance);
        RuntimeResourceScope control = null;
        try {
            control = beginRuntimeResourceScope(upgrade, true);
            control.beforeEffect();
        final JsonObject result = invoke(instance, StorageServiceRuntimeOperation.ROLLBACK,
                upgrade.getTransactionId(), request(upgrade, bundle));
        result.add("consumerCompatibility", compatibility);result.add("installedPreviousReadback", verifyGenericPrevious(instance, upgrade, previous));
        requireRuntimePackageFeatures(instance);
        return finishRuntimeResourceScope(control, "ROLLED_BACK", () -> {
        final Long current = instance.getCurrentRuntimeBundleId();
        instance.setCurrentRuntimeBundleId(instance.getPreviousRuntimeBundleId());
        instance.setPreviousRuntimeBundleId(current);
        instance.setRuntimeState("ROLLED_BACK");
        instance.setRuntimeVerifiedAt(new Date());
        if (!instanceDao.update(instance.getId(), instance)) throw new CloudRuntimeException("Verified runtime projection could not be persisted");
        upgrade.setRollbackResultJson(result.toString());
        upgrade.setCompleted(new Date());
        update(upgrade, StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK, "ROLLED_BACK", 100);
        return upgradeResponse(upgrade);
        });
        } catch (RuntimeException error) {
            failRuntimeControlled(upgrade, control, error);
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
        final List<StorageServiceRuntimeUpgradeResponse> responses = new ArrayList<>();
        for (final StorageServiceRuntimeUpgradeVO upgrade : upgrades) responses.add(upgradeResponse(upgrade));
        final ListResponse<StorageServiceRuntimeUpgradeResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    private static final java.util.Set<String> AD_IDENTITY_FEATURES = java.util.Set.of("SMB_ACTIVE_DIRECTORY", "SMB_AD_IDENTITY");
    private static boolean activeAdPrincipal(StorageAccessRuleVO rule) {
        return (rule.getPrincipalType() == StorageServiceInstance.PrincipalType.AD_USER || rule.getPrincipalType() == StorageServiceInstance.PrincipalType.AD_GROUP)
                && rule.getState() != StorageServiceInstance.ResourceState.Disabled && rule.getState() != StorageServiceInstance.ResourceState.Destroyed;
    }
    private static boolean posixAdPrincipals(JsonObject config) {
        for (String field : new String[] {"accessEntries", "defaultEntries"}) {
            if (!config.has(field)) continue;
            if (!config.get(field).isJsonArray()) throw new CloudRuntimeException("Active POSIX principal feature shape is invalid");
            for (com.google.gson.JsonElement row : config.getAsJsonArray(field)) {
                if (!row.isJsonObject() || !row.getAsJsonObject().has("principalType") || !row.getAsJsonObject().get("principalType").isJsonPrimitive()
                        || !row.getAsJsonObject().get("principalType").getAsJsonPrimitive().isString()) throw new CloudRuntimeException("Active POSIX principal feature shape is invalid");
                String kind = row.getAsJsonObject().get("principalType").getAsString();
                if ("AD_USER".equals(kind) || "AD_GROUP".equals(kind)) return true;
            }
        }
        return false;
    }
    protected StorageService runtimeDependencyService() {
        StorageService service = operationControlService == null ? null : operationControlService.get();
        if (service == null) throw new CloudRuntimeException("Runtime dependency service is unavailable");
        return service;
    }

    protected void requireRuntimePackageFeatures(StorageServiceInstanceVO instance) {
        runtimeDependencyService().verifyStoragePackageFeatures(instance.getId());
    }

    protected void requireSignedRuntimeFeatures(StorageServiceInstanceVO instance, JsonObject manifest) {
        requireSignedRuntimeFeatures(instance, manifest, null);
    }

    protected void requireSignedRuntimeFeatures(StorageServiceInstanceVO instance, JsonObject manifest, StorageServiceRuntimeBundleVO normalCandidate) {
        java.util.Set<String> required = requiredRuntimeFeatures(instance);
        if (normalCandidate != null && java.util.Objects.equals(instance.getCurrentRuntimeBundleId(), normalCandidate.getId())
                && required.stream().anyMatch(feature -> AD_IDENTITY_FEATURES.contains(feature) || "POSIX_AD_PRINCIPALS".equals(feature))) {
            java.util.Set<String> scoped = runtimeDependencyService().scopedValidatedRuntimeFeatures(instance.getId(), java.util.Set.copyOf(required));
            if (scoped == null) throw new CloudRuntimeException("Validated fixture feature requirements are unavailable");
            if (!scoped.equals(required)) {
                java.util.Set<String> exact = new java.util.HashSet<>(required);
                exact.removeAll(AD_IDENTITY_FEATURES);exact.remove("POSIX_AD_PRINCIPALS");exact.add("SMB_AD_IDENTITY_HANDLER");
                if (!scoped.equals(exact)) throw new CloudRuntimeException("Validated fixture cannot discard other runtime feature requirements");
                String candidateCli = runtimeManifestCliSha256(manifest);
                JsonObject currentProof = freshSignedRuntimeValidationProof(instance.getId(), candidateCli);
                if (!runtimePin(normalCandidate).equals(currentProof.getAsJsonObject("runtimePin"))
                        || !candidateCli.equals(stringValue(currentProof, "actualCliSha256"))) {
                    throw new CloudRuntimeException("Validated AD fixture runtime activation requires the exact current approved signed pin");
                }
                required = java.util.Set.copyOf(scoped);
            }
        }
        if (required.stream().anyMatch(feature -> AD_IDENTITY_FEATURES.contains(feature) || "POSIX_AD_PRINCIPALS".equals(feature))) {
            requireNativeAdFeatures(instance, required);
        }
        StorageRuntimeFeatureCompatibility.require(manifest, required);
        requireRuntimePackageFeatures(instance);
    }
    private static boolean trueCapability(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isBoolean() && object.get(key).getAsBoolean();
    }
    protected void requireNativeAdFeatures(StorageServiceInstanceVO instance, java.util.Set<String> required) {
        JsonObject request = new JsonObject();request.addProperty("instanceUuid", instance.getUuid());request.addProperty("operationUuid", UUID.randomUUID().toString());
        StorageServiceGuestCommandResult observed = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "identity capsule capabilities", request.toString(), 15, Collections.emptySet()));
        if (observed == null || !observed.isSuccess() || observed.getResultJson() == null || observed.getResultJson().isBlank()) {
            throw new CloudRuntimeException("FEATURE_UNAVAILABLEBLOCK: native AD capability is unobserved");
        }
        JsonObject caps = JsonParser.parseString(observed.getResultJson()).getAsJsonObject();
        if (!trueCapability(caps, "success") || !trueCapability(caps, "adIdentity")) {
            throw new CloudRuntimeException("FEATURE_UNAVAILABLEBLOCK: native AD identity transfer is unsupported");
        }
        java.util.Set<String> advertised = StorageRuntimeFeatureCompatibility.advertised(caps);
        for (String feature : required) {
            if ((AD_IDENTITY_FEATURES.contains(feature) || "POSIX_AD_PRINCIPALS".equals(feature)) && !advertised.contains(feature)) {
                throw new CloudRuntimeException("FEATURE_UNAVAILABLEBLOCK: native capability lacks " + feature);
            }
        }
    }

    protected java.util.Set<String> requiredRuntimeFeatures(final StorageServiceInstanceVO instance) {
        final java.util.Set<String> features = new java.util.HashSet<>();
        java.util.List<StoragePosixDirectoryPolicyVO> policies = posixPolicyDao.listByInstance(instance.getId());
        if (!policies.isEmpty()) features.add("POSIX_DIRECTORY_POLICY");
        StorageIdentityDomainVO domain = runtimeIdentityDomainDao.findByInstanceId(instance.getId());
        if (domain != null && domain.getJoinState() != StorageServiceInstance.DomainJoinState.NOT_JOINED) features.addAll(AD_IDENTITY_FEATURES);
        for (StoragePosixDirectoryPolicyVO policy : policies) {
            if ("Disabled".equalsIgnoreCase(policy.getState()) || "Destroyed".equalsIgnoreCase(policy.getState())) continue;
            JsonObject config = JsonParser.parseString(policy.getConfigJson() == null ? "{}" : policy.getConfigJson()).getAsJsonObject();
            if (posixAdPrincipals(config)) {features.addAll(AD_IDENTITY_FEATURES);features.add("POSIX_AD_PRINCIPALS");}
        }
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB}) {
            for (StorageFileShareVO share : fileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                JsonObject config = new JsonParser().parse(share.getConfigJson() == null ? "{}" : share.getConfigJson()).getAsJsonObject();
                features.addAll(StorageRuntimeFeatureCompatibility.shareFeatures(config, protocol));
                if (protocol == StorageServiceInstance.Protocol.SMB) {
                    for (StorageAccessRuleVO rule : accessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                        if (share.getState() != StorageServiceInstance.ResourceState.Disabled && share.getState() != StorageServiceInstance.ResourceState.Destroyed && activeAdPrincipal(rule)) features.addAll(AD_IDENTITY_FEATURES);
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
        java.util.Set<String> managed = runtimeDependencyService().requiredManagedOperationFeatures(instance.getId());
        if (managed == null) throw new CloudRuntimeException("Managed runtime feature requirements are unavailable");
        features.addAll(managed);
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
        upgrade.setState(state); upgrade.setPhase(phase); upgrade.setProgress(progress); if (!upgradeDao.update(upgrade.getId(), upgrade)) throw new CloudRuntimeException("Runtime upgrade state could not be persisted");
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
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsJsonPrimitive().isBoolean()
                ? object.get(name).getAsBoolean() : null;
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
