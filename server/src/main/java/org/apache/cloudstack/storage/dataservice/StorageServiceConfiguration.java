// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

/** Artifact collection and validation are separate from the semantic restore execution boundary. */
public final class StorageServiceConfiguration {
    private static final Set<String> READ_ACTIONS = Set.of("BACKUPS", "IMPORTS", "POINTS", "LKG");
    private final StorageServiceManagerImpl manager;
    private final StorageConfigArtifactDao artifacts;
    private final StorageServiceOperationDao operations;
    private final StorageConfigArtifactStore store;
    public StorageServiceConfiguration(StorageServiceManagerImpl manager, StorageConfigArtifactDao artifacts, StorageServiceOperationDao operations) {
        this(manager, artifacts, operations, new StorageConfigArtifactStore(Path.of(System.getProperty("cloudstack.storage.config.path",
                "/var/lib/cloudstack-management/storage-config-artifacts"))));
    }
    public StorageServiceConfiguration(StorageServiceManagerImpl manager, StorageConfigArtifactDao artifacts,
            StorageServiceOperationDao operations, StorageConfigArtifactStore store) {
        this.manager = manager;this.artifacts = artifacts;this.operations = operations;this.store = store;
    }
    public StorageServiceConfigArtifactResponse execute(StorageConfigRequest request) {
        if (!READ_ACTIONS.contains(request.getConfigAction())) manager.requireConfigurationAdministrator();
        StorageServiceInstanceVO instance = manager.requireInstance(request.getInstanceId());
        if (READ_ACTIONS.contains(request.getConfigAction())) return response(list(instance, request.getConfigAction()), null);
        if ("VERIFY_BASELINE".equals(request.getConfigAction())) {
            manager.executeDesiredChange(request.getBaseCmd(), StorageServiceConfigArtifactResponse.class, () -> {
                manager.verifyReconciledStorageDesiredState(instance);
                JsonObject evidence = new JsonObject();evidence.addProperty("verification", "NATIVE_PROBES_PENDING_FINAL_PROMOTION");
                return response(evidence, instance.getUuid());
            });
            return response(list(instance, "LKG"), instance.getUuid());
        }
        if ("APPLY".equals(request.getConfigAction())) return apply(instance, request);
        if ("RESTORE_LKG".equals(request.getConfigAction())) return apply(instance, lastKnownGoodRequest(instance, request));
        GlobalLock lock = GlobalLock.getInternLock("StorageServiceWriter-" + instance.getId());
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Storage Service writer is busy");
            try {
                cleanupExpired(instance);
                switch (request.getConfigAction()) {
                    case "BACKUP": return backup(instance, request);
                    case "UPLOAD": return upload(instance, request);
                    case "VALIDATE": return validate(instance, request);
                    case "DOWNLOAD": return download(instance, request);
                    case "PLAN": return plan(instance, request);
                    case "PLAN_LKG": return plan(instance, lastKnownGoodRequest(instance, request));
                    case "DELETE_BACKUP": case "DELETE_IMPORT": return delete(instance, request);
                    default: throw new InvalidParameterValueException("Configuration action is not registered for execution");
                }
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }
    private long revision(long instanceId) {
        return operations.listByInstance(instanceId).stream().filter(row -> "COMPLETE".equals(row.getState()))
                .mapToLong(StorageServiceOperationVO::getRevision).max().orElse(0);
    }
    private StorageConfigArtifactVO row(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        if (request.getArtifactId() == null) throw new InvalidParameterValueException("Configuration artifact ID is required");
        StorageConfigArtifactVO row = artifacts.findById(request.getArtifactId());
        if (row == null || row.getInstanceId() != instance.getId()) throw new InvalidParameterValueException("Configuration artifact is outside the requested service");
        if ("DELETED".equals(row.getState()) || (row.getExpires() != null && row.getExpires().before(new Date()))) {
            throw new InvalidParameterValueException("Configuration artifact is deleted or expired");
        }
        return row;
    }
    private int retention(StorageConfigRequest request) {
        int hours = request.getRetentionHours() == null ? 168 : request.getRetentionHours();
        if (hours < 1 || hours > 2160) throw new InvalidParameterValueException("Configuration retention must be 1 through 2160 hours");
        return hours;
    }
    private StorageConfigArtifactVO create(StorageServiceInstanceVO instance, StorageConfigRequest request, String kind) {
        StorageConfigArtifactVO row = new StorageConfigArtifactVO();row.setInstanceId(instance.getId());row.setKind(kind);
        row.setState("CREATING");row.setCreatedBy(CallContext.current().getCallingUserId());row.setDesiredRevision(revision(instance.getId()));
        row.setExpires(new Date(System.currentTimeMillis() + retention(request) * 3600000L));
        return artifacts.persist(row);
    }
    private JsonObject metadata(StorageConfigArtifactVO row) {
        return row.getMetadataJson() == null ? new JsonObject() : new com.google.gson.JsonParser().parse(row.getMetadataJson()).getAsJsonObject();
    }
    private void update(StorageConfigArtifactVO row, JsonObject metadata, String state) {
        row.setMetadataJson(metadata.toString());row.setState(state);row.setUpdated(new Date());artifacts.update(row.getId(), row);
    }
    private JsonObject publicRow(StorageConfigArtifactVO row) {
        JsonObject result = new JsonObject();result.addProperty("id", row.getUuid());result.addProperty("kind", row.getKind());
        result.addProperty("state", row.getExpires() != null && row.getExpires().before(new Date()) && !"ACTIVE_LKG".equals(row.getState()) ? "EXPIRED" : row.getState());result.addProperty("desiredRevision", row.getDesiredRevision());result.addProperty("size", row.getSize());
        result.addProperty("sha256", row.getSha256());result.addProperty("created", row.getCreated().getTime());
        if (row.getExpires() != null) result.addProperty("expires", row.getExpires().getTime());
        JsonObject metadata = metadata(row);metadata.remove("downloadToken");metadata.remove("planToken");
        result.add("metadata", metadata);return result;
    }
    private JsonObject list(StorageServiceInstanceVO instance, String action) {
        JsonArray rows = new JsonArray();
        for (StorageConfigArtifactVO row : artifacts.listByInstance(instance.getId())) {
            if ("DELETED".equals(row.getState())) continue;
            if ("BACKUPS".equals(action) && !"BACKUP".equals(row.getKind())) continue;
            if ("IMPORTS".equals(action) && !"IMPORT".equals(row.getKind())) continue;
            if (Set.of("LKG", "POINTS").contains(action) && !"RESTORE_POINT".equals(row.getKind())) continue;
            if ("LKG".equals(action) && !"ACTIVE_LKG".equals(row.getState())) continue;
            rows.add(publicRow(row));
        }
        JsonObject result = new JsonObject();result.add("artifacts", rows);result.addProperty("count", rows.size());return result;
    }
    private JsonArray requiredCredentials(Map<String, byte[]> entries) {
        JsonArray required = new JsonArray();
        byte[] access = entries.get("desired/access-rules.json");if (access == null) return required;
        for (JsonElement value : StorageConfigArchive.json(access).getAsJsonArray()) {
            JsonObject rule = value.getAsJsonObject();JsonObject config = rule.has("config") ? rule.getAsJsonObject("config") : new JsonObject();
            boolean local = rule.has("principal_type") && "LOCAL_USER".equals(rule.get("principal_type").getAsString());
            boolean chap = config.has("chapEnabled") && config.get("chapEnabled").getAsBoolean();
            boolean mutual = config.has("mutualChapEnabled") && config.get("mutualChapEnabled").getAsBoolean();
            boolean dhchap = config.has("dhChapEnabled") && config.get("dhChapEnabled").getAsBoolean();
            boolean ctrl = config.has("dhChapCtrlEnabled") && config.get("dhChapCtrlEnabled").getAsBoolean();
            if (local || chap || mutual || dhchap || ctrl) {
                JsonObject item = new JsonObject();item.add("resourceUuid", rule.get("resourceUuid").deepCopy());item.add("ruleUuid", rule.get("uuid").deepCopy());
                item.add("principal", rule.get("principal").deepCopy());item.addProperty("coverage", "REQUIRES_REENTRY");
                item.addProperty("kind", local ? "SMB_LOCAL" : chap || mutual ? "ISCSI_CHAP" : "NVME_DHCHAP");
                JsonArray fields = new JsonArray();if (local) fields.add("password");if (chap) fields.add("chapsecret");
                if (mutual) fields.add("mutualchapsecret");if (dhchap) fields.add("dhchapkey");if (ctrl) fields.add("dhchapctrlkey");
                item.add("fields", fields);required.add(item);
            }
        }
        return required;
    }
    private void versionMetadata(JsonObject metadata, StorageServiceInstanceVO instance) {
        JsonObject identity = manager.configurationInstanceMetadata(instance);
        if (identity.has("productVersion")) metadata.add("productVersion", identity.get("productVersion").deepCopy());
        JsonObject compatibility = new JsonObject();compatibility.addProperty("minimumManagerVersion", "4.23.0");
        compatibility.addProperty("maximumManagerVersionExclusive", "4.24.0");metadata.add("configurationCompatibility", compatibility);
    }
    private void validateSemanticArchive(Map<String, byte[]> entries, StorageServiceInstanceVO instance) {
        StorageConfigSemanticValidation.validate(entries, manager);
        StorageConfigSemanticValidation.compatibility(StorageConfigArchive.json(entries.get("manifest.json")).getAsJsonObject(),
                manager.configurationInstanceMetadata(instance).get("productVersion").getAsString());
    }
    private StorageServiceConfigArtifactResponse backup(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        StorageConfigArtifactVO row = create(instance, request, "BACKUP");
        JsonObject metadata = new JsonObject();metadata.addProperty("phase", "COLLECTING_DESIRED");
        update(row, metadata, "CREATING");
        try {
            long before = revision(instance.getId());String snapshot = manager.captureConfigurationSnapshot(instance.getId());
            JsonObject service = manager.configurationInstanceMetadata(instance);
            Map<String, byte[]> entries = new LinkedHashMap<>(StorageConfigSemantic.export(snapshot, service, manager.configurationVolumeMetadata(snapshot)));
            JsonArray required = requiredCredentials(entries);
            metadata.addProperty("sourceInstanceUuid", instance.getUuid());metadata.addProperty("desiredRevision", before);
            metadata.addProperty("createdAt", System.currentTimeMillis());metadata.addProperty("credentialCoverage", required.size() == 0 ? "FULL" : "REQUIRES_REENTRY");
            metadata.add("requiredCredentials", required);boolean partial = false;
            if (!Boolean.FALSE.equals(request.getIncludeRuntime())) {
                for (String command : new String[] {"inventory", "health", "sessions"}) {
                    metadata.addProperty("phase", "COLLECTING_" + command.toUpperCase(java.util.Locale.ROOT));update(row, metadata, "CREATING");
                    JsonObject observed = manager.observeConfigurationRuntime(instance, command);
                    if (!observed.has("success") || !observed.get("success").getAsBoolean()) partial = true;
                    entries.put("runtime/" + command + ".json", observed.toString().getBytes(StandardCharsets.UTF_8));
                }
            } else partial = true;
            if (before != revision(instance.getId()) || !snapshot.equals(manager.captureConfigurationSnapshot(instance.getId()))) {
                throw new CloudRuntimeException("Configuration changed while collecting backup");
            }
            metadata.addProperty("runtimeStatus", partial ? "UNAVAILABLE_OR_PARTIAL" : "AVAILABLE");metadata.addProperty("phase", "COMPLETE");
            versionMetadata(metadata, instance);
            byte[] archive = StorageConfigArchive.create(entries, metadata);
            row.setSha256(StorageConfigArchive.sha256(archive));row.setSize(archive.length);row.setDesiredRevision(before);store.write(row.getUuid(), archive);
            update(row, metadata, partial ? "PARTIAL" : "COMPLETE");return response(compactRow(row), row.getUuid());
        } catch (RuntimeException failure) {
            metadata.addProperty("phase", "EXPORT_FAILED");metadata.addProperty("errorCode", "CONFIG_EXPORT_FAILED");update(row, metadata, "FAILED");throw failure;
        }
    }
    private StorageServiceConfigArtifactResponse upload(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        if (request.getData() == null || request.getSha256() == null || !request.getSha256().matches("[0-9a-f]{64}")) throw new InvalidParameterValueException("Configuration upload and SHA-256 are required");
        int index = request.getChunkIndex() == null ? 0 : request.getChunkIndex();
        int count = request.getChunkCount() == null ? 1 : request.getChunkCount();
        if (count < 1 || count > 64 || index < 0 || index >= count) throw new InvalidParameterValueException("Configuration upload chunk sequence is invalid");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(request.getData()); }
        catch (IllegalArgumentException failure) { throw new InvalidParameterValueException("Invalid configuration upload encoding"); }
        if (bytes.length == 0 || bytes.length > 192 * 1024) throw new InvalidParameterValueException("Configuration chunk size exceeds limit");
        StorageConfigArtifactVO row;
        JsonObject metadata;
        if (request.getArtifactId() == null) {
            if (index != 0) throw new InvalidParameterValueException("First configuration chunk must use index zero");
            row = create(instance, request, "IMPORT");row.setSha256(request.getSha256());metadata = new JsonObject();
            metadata.addProperty("chunkCount", count);metadata.add("chunks", new JsonObject());update(row, metadata, "UPLOADING");
        } else {
            row = row(instance, request);metadata = metadata(row);
            if (!"IMPORT".equals(row.getKind()) || !Set.of("UPLOADING", "QUARANTINED").contains(row.getState())
                    || !row.getSha256().equals(request.getSha256()) || metadata.get("chunkCount").getAsInt() != count) {
                throw new InvalidParameterValueException("Configuration upload scope or manifest changed");
            }
        }
        JsonObject chunks = metadata.getAsJsonObject("chunks");String key = String.valueOf(index);String plainSha = StorageConfigArchive.sha256(bytes);
        if (chunks.has(key)) {
            if (!plainSha.equals(chunks.getAsJsonObject(key).get("plainSha256").getAsString())) throw new InvalidParameterValueException("Configuration chunk retry changed its contents");
        } else {
            if (row.getSize() + bytes.length > StorageConfigArchive.MAX_ARCHIVE_BYTES) throw new InvalidParameterValueException("Configuration upload size exceeds limit");
            String plain = Base64.getEncoder().encodeToString(bytes);
            String encrypted = com.cloud.utils.crypt.DBEncryptionUtil.encrypt(plain);
            if (encrypted.equals(plain) || !plain.equals(com.cloud.utils.crypt.DBEncryptionUtil.decrypt(encrypted))) {
                throw new CloudRuntimeException("Protected configuration quarantine requires management encryption");
            }
            byte[] ciphertext = encrypted.getBytes(StandardCharsets.UTF_8);
            store.write(StorageConfigArtifactStore.chunkIdentity(row.getUuid(), index), ciphertext);
            JsonObject part = new JsonObject();part.addProperty("plainSha256", plainSha);part.addProperty("encryptedSha256", StorageConfigArchive.sha256(ciphertext));
            part.addProperty("size", bytes.length);chunks.add(key, part);row.setSize(row.getSize() + bytes.length);
            update(row, metadata, "UPLOADING");
        }
        if ("QUARANTINED".equals(row.getState())) return response(publicRow(row), row.getUuid());
        if (chunks.size() == count) {
            java.io.ByteArrayOutputStream combined = new java.io.ByteArrayOutputStream();
            try {
                for (int item = 0; item < count; item++) {
                    JsonObject part = chunks.getAsJsonObject(String.valueOf(item));
                    byte[] ciphertext = store.read(StorageConfigArtifactStore.chunkIdentity(row.getUuid(), item), part.get("encryptedSha256").getAsString());
                    byte[] decoded = Base64.getDecoder().decode(com.cloud.utils.crypt.DBEncryptionUtil.decrypt(new String(ciphertext, StandardCharsets.UTF_8)));
                    if (decoded.length != part.get("size").getAsInt() || !StorageConfigArchive.sha256(decoded).equals(part.get("plainSha256").getAsString())) {
                        throw new CloudRuntimeException("Configuration quarantine chunk integrity changed");
                    }
                    if (combined.size() + decoded.length > StorageConfigArchive.MAX_ARCHIVE_BYTES) throw new CloudRuntimeException("Configuration upload size exceeds limit");
                    combined.write(decoded, 0, decoded.length);
                }
                byte[] archive = combined.toByteArray();
                if (!StorageConfigArchive.sha256(archive).equals(row.getSha256())) throw new InvalidParameterValueException("Configuration upload checksum does not match");
                // Secret/path/size validation runs before writing the assembled plaintext archive.
                StorageConfigArchive.validate(archive);store.write(row.getUuid(), archive);
                metadata.addProperty("phase", "QUARANTINED");update(row, metadata, "QUARANTINED");
            } catch (RuntimeException failure) {
                metadata.addProperty("phase", "UPLOAD_REJECTED");metadata.addProperty("errorCode", "CONFIG_UPLOAD_INVALID");update(row, metadata, "REJECTED");throw failure;
            } finally {
                boolean pending = false;
                for (int item = 0; item < count; item++) {
                    try { store.remove(StorageConfigArtifactStore.chunkIdentity(row.getUuid(), item)); }
                    catch (RuntimeException failure) { pending = true; }
                }
                metadata.addProperty("chunkCleanupPending", pending);update(row, metadata, row.getState());
            }
        }
        return response(publicRow(row), row.getUuid());
    }
    private StorageServiceConfigArtifactResponse validate(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(instance, request);
        if (!"IMPORT".equals(row.getKind())) throw new InvalidParameterValueException("Only quarantined configuration imports can be validated");
        JsonObject metadata = metadata(row);
        try {
            Map<String, byte[]> entries = StorageConfigArchive.validate(store.read(row.getUuid(), row.getSha256()));
            validateSemanticArchive(entries, instance);
            metadata.add("manifest", StorageConfigArchive.json(entries.get("manifest.json")));
            metadata.add("requiredCredentials", requiredCredentials(entries));metadata.addProperty("phase", "ARCHIVE_VALIDATED");
            update(row, metadata, "ARCHIVE_VALIDATED");
        } catch (RuntimeException failure) {
            metadata.addProperty("phase", "VALIDATION_FAILED");metadata.addProperty("errorCode", "CONFIG_IMPORT_INVALID");update(row, metadata, "REJECTED");throw failure;
        }
        return response(publicRow(row), row.getUuid());
    }
    private StorageServiceConfigArtifactResponse download(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(instance, request);
        if (!"BACKUP".equals(row.getKind()) || !Set.of("COMPLETE", "PARTIAL").contains(row.getState())) throw new InvalidParameterValueException("Configuration backup is not downloadable");
        JsonObject metadata = metadata(row);JsonObject result = publicRow(row);
        if (request.getDownloadToken() == null) {
            String token = UUID.randomUUID().toString() + UUID.randomUUID().toString();JsonObject issued = new JsonObject();
            issued.addProperty("hash", StorageConfigArchive.sha256(token.getBytes(StandardCharsets.UTF_8)));issued.addProperty("user", CallContext.current().getCallingUserId());
            issued.addProperty("expires", System.currentTimeMillis() + 60000);metadata.add("downloadToken", issued);update(row, metadata, row.getState());
            result.addProperty("downloadToken", token);result.addProperty("downloadExpires", issued.get("expires").getAsLong());
        } else {
            JsonObject issued = metadata.has("downloadToken") ? metadata.getAsJsonObject("downloadToken") : null;
            if (issued == null || issued.get("expires").getAsLong() < System.currentTimeMillis() || issued.get("user").getAsLong() != CallContext.current().getCallingUserId()
                    || !issued.get("hash").getAsString().equals(StorageConfigArchive.sha256(request.getDownloadToken().getBytes(StandardCharsets.UTF_8)))) {
                throw new InvalidParameterValueException("Configuration download token is invalid or expired");
            }
            byte[] bytes = store.read(row.getUuid(), row.getSha256());metadata.remove("downloadToken");update(row, metadata, row.getState());
            result.addProperty("data", Base64.getEncoder().encodeToString(bytes));result.addProperty("filename", "storage-config-" + row.getUuid() + ".zip");
        }
        return response(result, row.getUuid());
    }
    private StorageServiceConfigArtifactResponse plan(StorageServiceInstanceVO source, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(source, request);
        if (!Set.of("BACKUP", "IMPORT", "RESTORE_POINT").contains(row.getKind())
                || !Set.of("COMPLETE", "PARTIAL", "ARCHIVE_VALIDATED", "PLANNED", "ACTIVE_LKG", "SUPERSEDED").contains(row.getState())) {
            throw new InvalidParameterValueException("Configuration artifact has not passed validation");
        }
        Map<String, byte[]> archive = StorageConfigArchive.validate(store.read(row.getUuid(), row.getSha256()));
        validateSemanticArchive(archive, source);
        StorageServiceInstanceVO target = request.getTargetInstanceId() == null ? source : manager.requireInstance(request.getTargetInstanceId());
        JsonObject mappings = request.getMapping() == null ? new JsonObject() : new com.google.gson.JsonParser().parse(request.getMapping()).getAsJsonObject();
        String mode = request.getTargetMode() == null ? "RESTORE_EXISTING" : request.getTargetMode();
        String snapshot = manager.captureConfigurationSnapshot(target.getId());
        Map<String, byte[]> current = StorageConfigSemantic.export(snapshot, manager.configurationInstanceMetadata(target), manager.configurationVolumeMetadata(snapshot));
        long revision = revision(target.getId());String targetUuid = target.getUuid();JsonObject blueprint = null;
        if ("CREATE_NEW".equals(mode)) {
            if (!mappings.has("createNew") || !mappings.get("createNew").isJsonObject()) throw new InvalidParameterValueException("New-service blueprint requires explicit zone/network/offering/storage mapping");
            blueprint = mappings.getAsJsonObject("createNew");manager.preflightConfigurationNewService(blueprint);
            if (!mappings.has("runtimeBundleUuid") || !mappings.has("initialVolumeSourceUuid")) {
                throw new InvalidParameterValueException("New-service plan requires a compatible runtime bundle and explicit initial volume source mapping");
            }
            String initialMapping = mappings.get("initialVolumeSourceUuid").getAsString();
            if (mappings.has("volumes") && "NEW".equals(mappings.getAsJsonObject("volumes").has(initialMapping)
                    ? mappings.getAsJsonObject("volumes").get(initialMapping).getAsString() : null)) {
                mappings.getAsJsonObject("volumes").addProperty(initialMapping, UUID.randomUUID().toString());
            }
            StorageConfigRestorePlan.validateCloneInitialVolume(archive, mappings);
            manager.preflightConfigurationAdditionalVolumes(blueprint, mappings.getAsJsonObject("volumes"), mappings.get("initialVolumeSourceUuid").getAsString());
            manager.preflightConfigurationRuntimeBundle(mappings.get("runtimeBundleUuid").getAsString());
            targetUuid = UUID.randomUUID().toString();revision = 0;current = new LinkedHashMap<>();
            for (String kind : StorageConfigRestorePlan.ROW_KEYS.keySet()) current.put("desired/" + kind + ".json", "[]".getBytes(StandardCharsets.UTF_8));
            JsonArray volumes = new JsonArray();
            JsonObject mappedVolumes = mappings.has("volumes") ? mappings.getAsJsonObject("volumes") : new JsonObject();
            for (Map.Entry<String, JsonElement> mapping : mappedVolumes.entrySet()) {
                if (!mapping.getValue().isJsonPrimitive() || !mapping.getValue().getAsJsonPrimitive().isString()) throw new InvalidParameterValueException("New-service volume mapping requires a planned or existing UUID");
                JsonObject volume = new JsonObject();volume.add("uuid", mapping.getValue().deepCopy());volume.addProperty("planned", true);volumes.add(volume);
            }
            current.put("desired/volumes.json", volumes.toString().getBytes(StandardCharsets.UTF_8));
        }
        JsonObject plan = StorageConfigRestorePlan.build(archive, current, mappings, mode, targetUuid, revision);
        if (blueprint != null) {
            plan.add("createNew", blueprint.deepCopy());plan.addProperty("targetName", blueprint.get("name").getAsString());
            plan.addProperty("plannedTargetIdentity", true);
            plan.add("runtimeBundleUuid", mappings.get("runtimeBundleUuid").deepCopy());
            plan.add("initialVolumeSourceUuid", mappings.get("initialVolumeSourceUuid").deepCopy());
        }
        plan.addProperty("artifactSha256", row.getSha256());if (blueprint == null) plan.addProperty("targetName", target.getName());
        plan.add("requiredCredentials", requiredCredentials(archive));
        new StorageConfigDomainRestore(manager).validateBindings(plan);
        JsonObject metadata = metadata(row);metadata.add("plan", plan);
        String token = UUID.randomUUID().toString() + UUID.randomUUID().toString();JsonObject capability = new JsonObject();
        capability.addProperty("hash", StorageConfigArchive.sha256(token.getBytes(StandardCharsets.UTF_8)));
        capability.addProperty("user", CallContext.current().getCallingUserId());capability.addProperty("expires", System.currentTimeMillis() + 300000);
        capability.addProperty("planSha256", StorageConfigArchive.sha256(plan.toString().getBytes(StandardCharsets.UTF_8)));
        capability.addProperty("baselineSha256", StorageConfigArchive.sha256(snapshot.getBytes(StandardCharsets.UTF_8)));
        metadata.add("planToken", capability);metadata.addProperty("phase", "DRY_RUN_REVIEW");
        metadata.addProperty("planState", plan.getAsJsonArray("blockers").size() == 0 ? "PLANNED" : "PLAN_BLOCKED");
        update(row, metadata, row.getState());
        JsonObject result = publicRow(row);
        if (plan.getAsJsonArray("blockers").size() == 0) result.addProperty("planToken", token);
        return response(result, row.getUuid());
    }
    private StorageServiceConfigArtifactResponse apply(StorageServiceInstanceVO source, StorageConfigRequest request) {
        if (request.getArtifactId() == null) throw new InvalidParameterValueException("Configuration artifact ID is required");
        GlobalLock lock = GlobalLock.getInternLock("StorageConfigurationApply-" + request.getArtifactId());
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Configuration restore is already being applied");
            try { return applyLocked(source, request); }
            finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }
    private StorageServiceConfigArtifactResponse applyLocked(StorageServiceInstanceVO source, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(source, request);JsonObject metadata = metadata(row);
        if (!"PLANNED".equals(metadata.has("planState") ? metadata.get("planState").getAsString() : "")
                || !metadata.has("plan") || !metadata.has("planToken")) throw new InvalidParameterValueException("A validated configuration plan is required");
        JsonObject plan = metadata.getAsJsonObject("plan");JsonObject capability = metadata.getAsJsonObject("planToken");
        boolean createNew = "CREATE_NEW".equals(plan.get("targetMode").getAsString());
        final StorageServiceInstanceVO existingTarget = createNew ? null : manager.configurationInstanceByUuid(plan.get("targetInstanceUuid").getAsString());
        if (request.getPlanToken() == null || !plan.get("targetName").getAsString().equals(request.getConfirmation())
                || capability.get("user").getAsLong() != CallContext.current().getCallingUserId()
                || capability.get("expires").getAsLong() < System.currentTimeMillis()
                || !capability.get("hash").getAsString().equals(StorageConfigArchive.sha256(request.getPlanToken().getBytes(StandardCharsets.UTF_8)))
                || !capability.get("planSha256").getAsString().equals(StorageConfigArchive.sha256(plan.toString().getBytes(StandardCharsets.UTF_8)))
                || !row.getSha256().equals(plan.get("artifactSha256").getAsString())) {
            throw new InvalidParameterValueException("Configuration plan confirmation or capability changed");
        }
        JsonObject credentials = request.getCredentials() == null ? new JsonObject() : new com.google.gson.JsonParser().parse(request.getCredentials()).getAsJsonObject();
        StorageConfigRestorePlan.requireCredentials(plan.getAsJsonArray("requiredCredentials"), credentials);
        // Consume the capability under the artifact lock BEFORE allocating any Cloud resource.
        // A failed preparation retains its explicit target provenance and requires a fresh review.
        metadata.remove("planToken");metadata.addProperty("planState", "CONSUMED");
        metadata.addProperty("restoreState", "PREPARING");update(row, metadata, row.getState());
        try {
        StorageServiceInstanceVO selectedTarget = existingTarget;
        JsonObject executionPlan = plan.deepCopy();
        if (createNew) {
            String baseline = manager.captureConfigurationSnapshot(source.getId());
            if (!capability.get("baselineSha256").getAsString().equals(StorageConfigArchive.sha256(baseline.getBytes(StandardCharsets.UTF_8)))) {
                throw new InvalidParameterValueException("Source configuration changed after clone planning");
            }
            manager.preflightConfigurationRuntimeBundle(plan.get("runtimeBundleUuid").getAsString());
            if (!metadata.has("createdTargetInstanceUuid")) manager.preflightConfigurationAdditionalVolumes(plan.getAsJsonObject("createNew"),
                    plan.getAsJsonObject("volumeMappings"), plan.get("initialVolumeSourceUuid").getAsString());
            if (metadata.has("createdTargetInstanceUuid")) selectedTarget = manager.configurationInstanceByUuid(metadata.get("createdTargetInstanceUuid").getAsString());
            else {
                manager.preflightConfigurationNewService(plan.getAsJsonObject("createNew"));
                selectedTarget = manager.createConfigurationNewService(plan.getAsJsonObject("createNew"));
                metadata.addProperty("createdTargetInstanceUuid", selectedTarget.getUuid());metadata.addProperty("restoreState", "TARGET_CREATED");update(row, metadata, row.getState());
            }
            if (!"TARGET_PREPARED".equals(metadata.has("restoreState") ? metadata.get("restoreState").getAsString() : "")) {
                manager.upgradeConfigurationNewServiceRuntime(selectedTarget, plan.get("runtimeBundleUuid").getAsString());
            }
            executionPlan.addProperty("targetInstanceUuid", selectedTarget.getUuid());executionPlan.addProperty("expectedRevision", 0);
            String initial = plan.get("initialVolumeSourceUuid").getAsString();
            executionPlan.getAsJsonObject("volumeMappings").addProperty(initial, manager.configurationInitialVolume(selectedTarget).getUuid());
            metadata.addProperty("restoreState", "TARGET_PREPARED");update(row, metadata, row.getState());
        }
        final StorageServiceInstanceVO target = selectedTarget;final JsonObject reviewed = executionPlan;
            manager.executeDesiredChange(manager.configurationTargetCommand(target.getId(), request.getBaseCmd()), StorageServiceConfigArtifactResponse.class, () -> {
            String current = manager.captureConfigurationSnapshot(target.getId());
            if (!createNew && (revision(target.getId()) != plan.get("expectedRevision").getAsLong()
                    || !capability.get("baselineSha256").getAsString().equals(StorageConfigArchive.sha256(current.getBytes(StandardCharsets.UTF_8))))) {
                throw new InvalidParameterValueException("Configuration changed after planning; a new dry-run is required");
            }
            if (createNew) manager.prepareConfigurationAdditionalVolumes(target, reviewed.getAsJsonObject("volumeMappings"), reviewed.get("initialVolumeSourceUuid").getAsString());
            manager.checkpointConfigurationIdentity(target);
            metadata.remove("planToken");metadata.addProperty("restoreState", "APPLYING");update(row, metadata, row.getState());
            new StorageConfigDomainRestore(manager).apply(target, reviewed, credentials);
            metadata.addProperty("restoreState", "VERIFYING");update(row, metadata, row.getState());
            return response(compactRow(row), row.getUuid());
        });
            // Native probe, exact desired/runtime verification and LKG promotion have all returned.
            metadata.addProperty("restoreState", "COMPLETE");metadata.addProperty("restoredAt", System.currentTimeMillis());
            update(row, metadata, row.getState());return response(compactRow(row), row.getUuid());
        } catch (RuntimeException failure) {
            metadata.addProperty("restoreState", "FAILED");
            metadata.addProperty("errorCode", "CONFIG_RESTORE_FAILED");
            update(row, metadata, row.getState());
            throw failure;
        }
    }
    private StorageConfigRequest lastKnownGoodRequest(StorageServiceInstanceVO instance, StorageConfigRequest original) {
        StorageConfigArtifactVO point = artifacts.listByInstance(instance.getId()).stream()
                .filter(row -> "RESTORE_POINT".equals(row.getKind()) && "ACTIVE_LKG".equals(row.getState()))
                .max(java.util.Comparator.comparingLong(StorageConfigArtifactVO::getDesiredRevision))
                .orElseThrow(() -> new InvalidParameterValueException("No verified last-known-good configuration exists"));
        if (original.getArtifactId() != null && !original.getArtifactId().equals(point.getId())) {
            throw new InvalidParameterValueException("The active last-known-good configuration changed");
        }
        return new StorageConfigRequest() {
            public org.apache.cloudstack.api.BaseCmd getBaseCmd() { return original.getBaseCmd(); }
            public String getConfigAction() { return original.getConfigAction(); }
            public Long getInstanceId() { return instance.getId(); }
            public Long getArtifactId() { return point.getId(); }
            public String getMapping() { return original.getMapping(); }
            public String getTargetMode() { return "RESTORE_EXISTING"; }
            public String getCredentials() { return original.getCredentials(); }
            public String getConfirmation() { return original.getConfirmation(); }
            public String getPlanToken() { return original.getPlanToken(); }
        };
    }
    public void promoteVerified(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
        manager.verifyReconciledStorageDesiredState(instance);
        String snapshot = manager.captureConfigurationSnapshot(instance.getId());
        if (!snapshot.equals(operation.getSnapshotJson())) throw new CloudRuntimeException("Verified configuration changed before restore-point promotion");
        Map<String, byte[]> entries = StorageConfigSemantic.export(snapshot, manager.configurationInstanceMetadata(instance), manager.configurationVolumeMetadata(snapshot));
        JsonObject metadata = new JsonObject();metadata.addProperty("verification", "VERIFIED_SUCCESS");
        metadata.addProperty("sourceInstanceUuid", instance.getUuid());metadata.addProperty("operationUuid", operation.getUuid());
        metadata.addProperty("desiredRevision", operation.getRevision());metadata.addProperty("verifiedAt", System.currentTimeMillis());
        metadata.addProperty("runtimeStatus", "AVAILABLE");JsonArray required = requiredCredentials(entries);
        metadata.add("requiredCredentials", required);metadata.addProperty("credentialCoverage", required.size() == 0 ? "FULL" : "REQUIRES_REENTRY");
        versionMetadata(metadata, instance);
        byte[] archive = StorageConfigArchive.create(entries, metadata);
        StorageConfigArtifactVO candidate = new StorageConfigArtifactVO();candidate.setInstanceId(instance.getId());candidate.setKind("RESTORE_POINT");
        candidate.setState("CANDIDATE");candidate.setDesiredRevision(operation.getRevision());candidate.setSourceOperationId(operation.getId());
        candidate.setCreatedBy(operation.getCreatedBy());candidate.setMetadataJson(metadata.toString());candidate.setSha256(StorageConfigArchive.sha256(archive));
        candidate.setSize(archive.length);candidate = artifacts.persist(candidate);
        final StorageConfigArtifactVO point = candidate;
        try {
            store.write(point.getUuid(), archive);
            com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
                for (StorageConfigArtifactVO previous : artifacts.listByInstance(instance.getId())) {
                    if ("RESTORE_POINT".equals(previous.getKind()) && "ACTIVE_LKG".equals(previous.getState())) {
                        previous.setState("SUPERSEDED");previous.setUpdated(new Date());previous.setExpires(new Date(System.currentTimeMillis() + 168 * 3600000L));artifacts.update(previous.getId(), previous);
                    }
                }
                point.setState("ACTIVE_LKG");point.setUpdated(new Date());artifacts.update(point.getId(), point);return true;
            });
        } catch (RuntimeException failure) {
            try { store.remove(point.getUuid()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            point.setState("FAILED");artifacts.update(point.getId(), point);throw failure;
        }
    }
    private StorageServiceConfigArtifactResponse delete(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(instance, request);
        String kind = "DELETE_BACKUP".equals(request.getConfigAction()) ? "BACKUP" : "IMPORT";
        if (!kind.equals(row.getKind())) throw new InvalidParameterValueException("Configuration artifact type does not match");
        // Retain the protected artifact until normal retention cleanup; metadata deletion cannot touch DATA.
        JsonObject metadata = metadata(row);metadata.remove("downloadToken");metadata.remove("planToken");update(row, metadata, "DELETED");
        return response(publicRow(row), row.getUuid());
    }
    private void cleanupExpired(StorageServiceInstanceVO instance) {
        Date now = new Date();
        for (StorageConfigArtifactVO row : artifacts.listByInstance(instance.getId())) {
            if ("ACTIVE_LKG".equals(row.getState()) || row.getExpires() == null || !row.getExpires().before(now)) continue;
            JsonObject metadata = metadata(row);boolean pending = false;
            try { store.remove(row.getUuid()); } catch (RuntimeException cleanup) { pending = true; }
            if (metadata.has("chunks")) for (String index : metadata.getAsJsonObject("chunks").keySet()) {
                try { store.remove(StorageConfigArtifactStore.chunkIdentity(row.getUuid(), Integer.parseInt(index))); }
                catch (RuntimeException cleanup) { pending = true; }
            }
            metadata.remove("downloadToken");metadata.remove("planToken");metadata.addProperty("cleanupState", pending ? "PENDING" : "CLEANED");
            update(row, metadata, pending ? "EXPIRY_CLEANUP_PENDING" : "EXPIRED");
        }
    }
    private JsonObject compactRow(StorageConfigArtifactVO row) {
        JsonObject result = publicRow(row);result.remove("metadata");
        JsonObject metadata = metadata(row);JsonObject summary = new JsonObject();
        for (String key : new String[] {"phase", "runtimeStatus", "credentialCoverage", "restoreState", "errorCode", "verifiedAt"}) {
            if (metadata.has(key)) summary.add(key, metadata.get(key).deepCopy());
        }
        result.add("metadata", summary);return result;
    }
    private StorageServiceConfigArtifactResponse response(JsonObject result, String id) {
        StorageServiceConfigArtifactResponse response = new StorageServiceConfigArtifactResponse();response.setResult(result.toString());response.setId(id);
        response.setObjectName("storageserviceconfiguration");return response;
    }
}
