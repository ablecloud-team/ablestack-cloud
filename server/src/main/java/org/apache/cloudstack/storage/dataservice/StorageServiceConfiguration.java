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
        if("BACKUP".equals(request.getConfigAction())&&Boolean.TRUE.equals(request.getIncludeAdIdentity())){
            return manager.executeProtectedIdentityConfiguration(instance,request,StorageServiceConfigArtifactResponse.class,()->backup(instance,request));
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
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Void>) status -> {
            StorageConfigArtifactVO locked = artifacts.lockRow(row.getId(), true);
            if (locked == null) throw new CloudRuntimeException("Configuration artifact disappeared before its metadata update");
            JsonObject current = metadata(locked);
            // Allocation receipts are independently CAS-updated. A stale phase snapshot cannot erase them.
            if (current.has("receipts")) metadata.add("receipts", current.get("receipts").deepCopy());
            locked.setMetadataJson(metadata.toString());locked.setState(state);locked.setUpdated(new Date());
            if (!artifacts.update(locked.getId(), locked)) throw new CloudRuntimeException("Configuration artifact metadata update failed");
            row.setMetadataJson(locked.getMetadataJson());row.setState(state);row.setUpdated(locked.getUpdated());
            return null;
        });
    }
    private JsonObject publicRow(StorageConfigArtifactVO row) {
        JsonObject result = new JsonObject();result.addProperty("id", row.getUuid());result.addProperty("kind", row.getKind());
        result.addProperty("state", row.getExpires() != null && row.getExpires().before(new Date()) && !"ACTIVE_LKG".equals(row.getState()) ? "EXPIRED" : row.getState());result.addProperty("desiredRevision", row.getDesiredRevision());result.addProperty("size", row.getSize());
        result.addProperty("sha256", row.getSha256());result.addProperty("created", row.getCreated().getTime());
        if (row.getExpires() != null) result.addProperty("expires", row.getExpires().getTime());
        JsonObject metadata = metadata(row);metadata.remove("downloadToken");metadata.remove("planToken");metadata.remove("adIdentityCipherReference");metadata.remove("adIdentitySourceUsages");
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
    private JsonObject resolveAdSemanticSource(Map<String,byte[]> archive,StorageConfigArtifactVO imported) {
        byte[] encoded=archive.get(StorageAdSemanticSource.ZIP_ENTRY);if(encoded==null)return null;
        JsonObject descriptor=StorageAdSemanticSource.validateDescriptor(StorageConfigArchive.json(encoded).getAsJsonObject());
        StorageConfigArtifactVO original=artifacts.findByUuid(descriptor.get("ownerArtifactUuid").getAsString());
        if(original==null||!"BACKUP".equals(original.getKind())||!Set.of("COMPLETE","PARTIAL").contains(original.getState())
                ||original.getExpires()==null||!original.getExpires().after(new Date())||!original.getSha256().equals(imported.getSha256()))throw new InvalidParameterValueException("AD identity source is not an available original managed backup archive");
        StorageServiceInstanceVO owner=manager.requireInstance(original.getInstanceId());JsonObject originalMetadata=metadata(original);
        if(!owner.getUuid().equals(descriptor.get("sourceInstanceUuid").getAsString())||!"VERIFIED_ENCRYPTED_FULL_IDENTITY".equals(originalMetadata.has("adIdentityCoverage")?originalMetadata.get("adIdentityCoverage").getAsString():null)
                ||!descriptor.equals(originalMetadata.get("adIdentitySourceDescriptor"))||!originalMetadata.has("adIdentityCipherReference"))throw new InvalidParameterValueException("AD identity backup issuer, service owner or coverage changed");
        StorageServiceOperationVO operation=operations.listByInstance(owner.getId()).stream().filter(value->descriptor.get("sourceOperationUuid").getAsString().equals(value.getUuid())).findFirst().orElse(null);
        if(operation==null||!"COMPLETE".equals(operation.getState()))throw new InvalidParameterValueException("AD identity source operation is incomplete or requires recovery");
        JsonObject reference=StorageAdSemanticSource.validateManagedReference(descriptor,originalMetadata.getAsJsonObject("adIdentityCipherReference"));
        StorageConfigArtifactStore identityStore=new StorageConfigArtifactStore(Path.of(System.getProperty("cloudstack.storage.identity.path","/var/lib/cloudstack-management/storage-identity-capsules")));
        byte[] protectedKey=identityStore.read(reference.get("keyId").getAsString(),reference.get("keyDataSha256").getAsString());
        java.security.PrivateKey key=StorageIdentityCapsule.unwrapProtectedPrivateKey(protectedKey);
        JsonObject capsule=StorageAdSemanticSource.authenticate(identityStore,descriptor,reference,imported.getSha256(),original.getSha256(),key);
        JsonObject authority=new JsonObject();authority.add("descriptor",descriptor);authority.add("reference",reference);authority.add("capsule",capsule);return authority;
    }
    private void validateSemanticArchive(Map<String, byte[]> entries, StorageServiceInstanceVO instance) {validateSemanticArchive(entries,instance,false);}
    private void validateSemanticArchive(Map<String, byte[]> entries, StorageServiceInstanceVO instance,boolean authenticatedAdSource) {
        StorageConfigSemanticValidation.validate(entries, manager,authenticatedAdSource);
        StorageConfigSemanticValidation.compatibility(StorageConfigArchive.json(entries.get("manifest.json")).getAsJsonObject(),
                manager.configurationInstanceMetadata(instance).get("productVersion").getAsString());
    }
    private StorageServiceConfigArtifactResponse backup(StorageServiceInstanceVO instance, StorageConfigRequest request) {
        if(Boolean.TRUE.equals(request.getIncludeAdIdentity())){
            if(!Boolean.TRUE.equals(request.getMaintenanceWindow())||!instance.getName().equals(request.getConfirmation()))throw new InvalidParameterValueException("Protected identity backup requires approved SERVICE interruption and the exact instance name");
            JsonObject source=manager.requiredAdServiceSourceIdentity(instance);
            JsonElement ad=source.get("publicAdPreStopCaptured");
            if(ad==null||!ad.isJsonPrimitive()||!ad.getAsJsonPrimitive().isBoolean()||!ad.getAsBoolean())throw new CloudRuntimeException("Protected AD identity backup requires the full typed stopped AD source checkpoint");
        }
        StorageConfigArtifactVO row = create(instance, request, "BACKUP");
        JsonObject metadata = new JsonObject();metadata.addProperty("phase", "COLLECTING_DESIRED");
        update(row, metadata, "CREATING");
        try {
            long before = revision(instance.getId());String snapshot = manager.captureConfigurationSnapshot(instance.getId());
            JsonObject service = manager.configurationInstanceMetadata(instance);
            Map<String, byte[]> entries = new LinkedHashMap<>(StorageConfigSemantic.export(snapshot, service, manager.configurationVolumeMetadata(snapshot)));
            JsonArray required = requiredCredentials(entries);
            if(Boolean.TRUE.equals(request.getIncludeAdIdentity())){
                JsonObject reference=manager.retainAdSemanticSource(instance,row.getUuid()),descriptor=StorageAdSemanticSource.validateDescriptor(reference.getAsJsonObject("descriptor"));
                entries.put(StorageAdSemanticSource.ZIP_ENTRY,descriptor.toString().getBytes(StandardCharsets.UTF_8));
                metadata.add("adIdentityCipherReference",reference);metadata.add("adIdentitySourceDescriptor",descriptor);
                metadata.addProperty("adIdentityCoverage","VERIFIED_ENCRYPTED_FULL_IDENTITY");
            }
            metadata.addProperty("sourceInstanceUuid", instance.getUuid());metadata.addProperty("desiredRevision", before);
            metadata.addProperty("createdAt", System.currentTimeMillis());metadata.addProperty("credentialCoverage", required.size() == 0 ? "FULL" : "REQUIRES_REENTRY");
            metadata.add("requiredCredentials", required);JsonObject collectors = new JsonObject();
            boolean includeRuntime = !Boolean.FALSE.equals(request.getIncludeRuntime());
            if (includeRuntime) {
                for (String command : new String[] {"inventory", "health", "sessions"}) {
                    metadata.addProperty("phase", "COLLECTING_" + command.toUpperCase(java.util.Locale.ROOT));update(row, metadata, "CREATING");
                    JsonObject observed = manager.observeConfigurationRuntime(instance, command);
                    JsonObject collector = new JsonObject();
                    collector.addProperty("success", observed.has("success") && observed.get("success").getAsBoolean());
                    collector.addProperty("status", observed.has("status") ? observed.get("status").getAsString() : "UNKNOWN");
                    collectors.add(command, collector);
                    entries.put("runtime/" + command + ".json", observed.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            if (before != revision(instance.getId()) || !snapshot.equals(manager.captureConfigurationSnapshot(instance.getId()))) {
                throw new CloudRuntimeException("Configuration changed while collecting backup");
            }
            String runtimeStatus = runtimeCollectionStatus(includeRuntime, collectors);
            metadata.addProperty("runtimeStatus", runtimeStatus);metadata.add("runtimeCollectors", collectors);metadata.addProperty("phase", "COMPLETE");
            versionMetadata(metadata, instance);
            JsonObject publicManifest=metadata.deepCopy();publicManifest.remove("adIdentityCipherReference");
            byte[] archive = StorageConfigArchive.create(entries, publicManifest);
            row.setSha256(StorageConfigArchive.sha256(archive));row.setSize(archive.length);row.setDesiredRevision(before);store.write(row.getUuid(), archive);
            update(row, metadata, "AVAILABLE".equals(runtimeStatus) ? "COMPLETE" : "PARTIAL");return response(compactRow(row), row.getUuid());
        } catch (RuntimeException failure) {
            metadata.addProperty("phase", "EXPORT_FAILED");metadata.addProperty("errorCode", "CONFIG_EXPORT_FAILED");update(row, metadata, "FAILED");throw failure;
        }
    }
    static String runtimeCollectionStatus(boolean requested, JsonObject collectors) {
        if (!requested) return "NOT_REQUESTED";
        long successful = collectors.entrySet().stream().filter(entry -> entry.getValue().getAsJsonObject().has("success")
                && entry.getValue().getAsJsonObject().get("success").getAsBoolean()).count();
        if (successful == 0) return "UNAVAILABLE";
        return collectors.size() == 3 && successful == 3 ? "AVAILABLE" : "PARTIAL";
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
            JsonObject authority=resolveAdSemanticSource(entries,row);validateSemanticArchive(entries,instance,authority!=null);
            if(authority!=null){metadata.add("adIdentitySourceDescriptor",authority.get("descriptor").deepCopy());metadata.addProperty("adIdentityCoverage","VERIFIED_ENCRYPTED_FULL_IDENTITY");}
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
        JsonObject sourceAuthority=resolveAdSemanticSource(archive,row);validateSemanticArchive(archive,source,sourceAuthority!=null);
        StorageServiceInstanceVO target = request.getTargetInstanceId() == null ? source : manager.requireInstance(request.getTargetInstanceId());
        JsonObject mappings = request.getMapping() == null ? new JsonObject() : new com.google.gson.JsonParser().parse(request.getMapping()).getAsJsonObject();
        String mode = request.getTargetMode() == null ? "RESTORE_EXISTING" : request.getTargetMode();
        String snapshot = manager.captureConfigurationSnapshot(target.getId());
        Map<String, byte[]> current = StorageConfigSemantic.export(snapshot, manager.configurationInstanceMetadata(target), manager.configurationVolumeMetadata(snapshot));
        long revision = revision(target.getId());String targetUuid = target.getUuid();JsonObject blueprint = null;
        JsonObject allocationPlan = null;
        JsonObject metadata = metadata(row);
        JsonObject requestedVolumeMapping = new JsonObject();
        if ("CREATE_NEW".equals(mode)) {
            if (!mappings.has("createNew") || !mappings.get("createNew").isJsonObject()) throw new InvalidParameterValueException("New-service blueprint requires explicit zone/network/offering/storage mapping");
            blueprint = mappings.getAsJsonObject("createNew");
            if(sourceAuthority!=null)manager.requireFreshStorageIdentityTemplateBlueprint(blueprint);
            if (!metadata.has("createdTargetInstanceUuid")) manager.preflightConfigurationNewService(blueprint);
            for (String field : new String[] {"createNew", "volumes", "newVolumes", "initialVolumeSourceUuid", "runtimeBundleUuid"}) {
                if (mappings.has(field)) requestedVolumeMapping.add(field, mappings.get(field).deepCopy());
            }
            if (!mappings.has("runtimeBundleUuid") || !mappings.has("initialVolumeSourceUuid")) {
                throw new InvalidParameterValueException("New-service plan requires a compatible runtime bundle and explicit initial volume source mapping");
            }
            if (metadata.has("createdTargetInstanceUuid")) {
                JsonObject previous = metadata.getAsJsonObject("plan");
                if (!requestedVolumeMapping.equals(metadata.get("requestedVolumeMapping")) || !previous.has("volumeAllocationPlan")) {
                    throw new InvalidParameterValueException("A partially created clone must retain its exact reviewed allocation scope and mapping");
                }
                allocationPlan = previous.getAsJsonObject("volumeAllocationPlan").deepCopy();
                StorageConfigurationVolumePlan.requireFrozen(allocationPlan);
                targetUuid = previous.get("targetInstanceUuid").getAsString();
            } else {
                String namespace = metadata.has("allocationNamespace") ? metadata.get("allocationNamespace").getAsString() : UUID.randomUUID().toString();
                targetUuid = metadata.has("plannedTargetInstanceUuid") ? metadata.get("plannedTargetInstanceUuid").getAsString() : UUID.randomUUID().toString();
                allocationPlan = manager.buildConfigurationVolumePlan(archive, mappings, row, namespace, targetUuid);
                metadata.addProperty("allocationNamespace", namespace);metadata.addProperty("plannedTargetInstanceUuid", targetUuid);
                metadata.add("requestedVolumeMapping", requestedVolumeMapping.deepCopy());
            }
            mappings.add("volumes", allocationPlan.getAsJsonObject("volumeMappings").deepCopy());
            StorageConfigRestorePlan.validateCloneInitialVolume(archive, mappings);
            manager.preflightConfigurationRuntimeBundle(mappings.get("runtimeBundleUuid").getAsString());
            revision = 0;current = new LinkedHashMap<>();
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
            plan.add("volumeAllocationPlan", allocationPlan.deepCopy());
        }
        if(sourceAuthority!=null){
            JsonObject descriptor=sourceAuthority.getAsJsonObject("descriptor");
            if(!"CREATE_NEW".equals(mode)&&!target.getUuid().equals(descriptor.get("sourceInstanceUuid").getAsString()))throw new InvalidParameterValueException("Existing AD identity restore must target its exact original service");
            JsonArray blockers=plan.getAsJsonArray("blockers");for(int i=blockers.size()-1;i>=0;i--)if(blockers.get(i).isJsonPrimitive()&&"SMB_AD_DEFERRED".equals(blockers.get(i).getAsString()))blockers.remove(i);
            plan.add("adIdentitySourceDescriptor",descriptor.deepCopy());plan.addProperty("adIdentityRestoreRequiresMaintenance",true);
        }
        plan.addProperty("artifactSha256", row.getSha256());if (blueprint == null) plan.addProperty("targetName", target.getName());
        JsonArray required=requiredCredentials(archive);
        if(sourceAuthority!=null)for(int i=required.size()-1;i>=0;i--)if("SMB_LOCAL".equals(required.get(i).getAsJsonObject().get("kind").getAsString()))required.remove(i);
        if(sourceAuthority!=null)for(JsonElement value:StorageConfigRestorePlan.resources(archive).get("identity-domain")){
            JsonObject domain=value.getAsJsonObject();if(domain.has("domain_name")&&!domain.get("domain_name").getAsString().isBlank()){
                JsonObject item=new JsonObject();item.add("ruleUuid",domain.get("uuid").deepCopy());item.addProperty("kind","SMB_AD_REJOIN");item.addProperty("coverage","REQUIRES_REENTRY");JsonArray fields=new JsonArray();fields.add("username");fields.add("password");item.add("fields",fields);required.add(item);
            }
        }
        plan.add("requiredCredentials",required);
        StorageConfigRestorePlan.reviewForcedFileExecute(plan, mappings);
        new StorageConfigDomainRestore(manager).validateBindings(plan);
        if (blueprint != null) {
            JsonArray directories = new JsonArray();
            for (JsonElement item : plan.getAsJsonArray("create")) {
                JsonObject change = item.getAsJsonObject();String kind = change.get("kind").getAsString();
                if (!Set.of("file-shares", "posix-directory-policies").contains(kind)) continue;
                JsonObject directory = new JsonObject();directory.add("sourceUuid", change.get("sourceUuid").deepCopy());
                directory.addProperty("relativePath", StorageConfigDomainRestore.directoryRelativePath(kind, change.getAsJsonObject("desired")));
                directory.addProperty("dataPolicy", "CREATE_MISSING_DIRECTORY_ONLY");directories.add(directory);
            }
            plan.add("directoryPreparation", directories);
        }
        if (metadata.has("createdTargetInstanceUuid")) {
            // Repeat review retains domain identities too; partially created resources cannot be rebound by a new plan.
            plan = metadata.getAsJsonObject("plan").deepCopy();
        }
        metadata.add("plan", plan);
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
    private JsonObject requirePlanCapability(StorageConfigArtifactVO row,JsonObject metadata,StorageConfigRequest request) {
        if(!"PLANNED".equals(metadata.has("planState")?metadata.get("planState").getAsString():"")||!metadata.has("plan")||!metadata.has("planToken"))throw new InvalidParameterValueException("A validated configuration plan is required");
        JsonObject plan=metadata.getAsJsonObject("plan"),capability=metadata.getAsJsonObject("planToken");
        if (request.getPlanToken() == null || !plan.get("targetName").getAsString().equals(request.getConfirmation())
                || capability.get("user").getAsLong() != CallContext.current().getCallingUserId()
                || capability.get("expires").getAsLong() < System.currentTimeMillis()
                || !capability.get("hash").getAsString().equals(StorageConfigArchive.sha256(request.getPlanToken().getBytes(StandardCharsets.UTF_8)))
                || !capability.get("planSha256").getAsString().equals(StorageConfigArchive.sha256(plan.toString().getBytes(StandardCharsets.UTF_8)))
                || !row.getSha256().equals(plan.get("artifactSha256").getAsString())) {
            throw new InvalidParameterValueException("Configuration plan confirmation or capability changed");
        }
        return plan;
    }
    public void validateApprovedMaintenancePlan(StorageServiceInstanceVO source,StorageConfigRequest request) {
        StorageConfigArtifactVO row=row(source,request);JsonObject metadata=metadata(row),plan=requirePlanCapability(row,metadata,request),capability=metadata.getAsJsonObject("planToken");
        if("CREATE_NEW".equals(plan.get("targetMode").getAsString())||!source.getUuid().equals(plan.get("targetInstanceUuid").getAsString()))throw new InvalidParameterValueException("Maintenance requires an existing-target plan for this same instance");
        if(revision(source.getId())!=plan.get("expectedRevision").getAsLong()||!capability.get("baselineSha256").getAsString().equals(StorageConfigArchive.sha256(manager.captureConfigurationSnapshot(source.getId()).getBytes(StandardCharsets.UTF_8))))throw new InvalidParameterValueException("Configuration changed after planning; a new dry-run is required");
        JsonObject credentials=request.getCredentials()==null?new JsonObject():com.google.gson.JsonParser.parseString(request.getCredentials()).getAsJsonObject();StorageConfigRestorePlan.requireCredentials(plan.getAsJsonArray("requiredCredentials"),credentials);
    }
    private StorageServiceConfigArtifactResponse applyLocked(StorageServiceInstanceVO source, StorageConfigRequest request) {
        StorageConfigArtifactVO row = row(source, request);JsonObject metadata = metadata(row);
        JsonObject plan=requirePlanCapability(row,metadata,request);JsonObject capability=metadata.getAsJsonObject("planToken");
        Map<String,byte[]> sourceArchive=StorageConfigArchive.validate(store.read(row.getUuid(),row.getSha256()));JsonObject sourceAuthority=resolveAdSemanticSource(sourceArchive,row);
        if(plan.has("adIdentitySourceDescriptor")){
            if(sourceAuthority==null||!plan.get("adIdentitySourceDescriptor").equals(sourceAuthority.get("descriptor"))||!Boolean.TRUE.equals(request.getMaintenanceWindow()))throw new InvalidParameterValueException("AD restore source authority or explicit maintenance approval changed after planning");
        } else if(sourceAuthority!=null)throw new InvalidParameterValueException("AD source requires a newly reviewed protected restore plan");
        boolean createNew="CREATE_NEW".equals(plan.get("targetMode").getAsString());
        if(createNew&&sourceAuthority!=null)manager.requireFreshStorageIdentityTemplateBlueprint(plan.getAsJsonObject("createNew"));
        final StorageServiceInstanceVO existingTarget=createNew?null:manager.configurationInstanceByUuid(plan.get("targetInstanceUuid").getAsString());
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
            if (!plan.has("volumeAllocationPlan")) throw new InvalidParameterValueException("Clone requires a newly reviewed allocation plan; legacy plans cannot allocate additional DATA");
            StorageConfigurationVolumePlan.requireFrozen(plan.getAsJsonObject("volumeAllocationPlan"));
            if (metadata.has("createdTargetInstanceUuid")) selectedTarget = manager.configurationInstanceByUuid(metadata.get("createdTargetInstanceUuid").getAsString());
            else {
                manager.preflightConfigurationNewService(plan.getAsJsonObject("createNew"));
                selectedTarget = manager.createConfigurationNewService(plan.getAsJsonObject("createNew"));
                metadata.addProperty("createdTargetInstanceUuid", selectedTarget.getUuid());metadata.addProperty("restoreState", "TARGET_CREATED");update(row, metadata, row.getState());
            }
            if (!plan.get("runtimeBundleUuid").getAsString().equals(metadata.has("runtimePreparedBundleUuid") ? metadata.get("runtimePreparedBundleUuid").getAsString() : null)) {
                manager.upgradeConfigurationNewServiceRuntime(selectedTarget, plan.get("runtimeBundleUuid").getAsString());
                metadata.add("runtimePreparedBundleUuid", plan.get("runtimeBundleUuid").deepCopy());
                update(row, metadata, row.getState());
            }
            executionPlan.addProperty("targetInstanceUuid", selectedTarget.getUuid());executionPlan.addProperty("expectedRevision", 0);
            JsonObject realized = manager.bindConfigurationVolumeExecution(selectedTarget, row, plan.getAsJsonObject("volumeAllocationPlan"));
            if (metadata.has("volumeExecutionPlan") && !realized.equals(metadata.get("volumeExecutionPlan"))) throw new CloudRuntimeException("Clone execution realization changed after persistence");
            metadata.add("volumeExecutionPlan", realized.deepCopy());
            executionPlan.add("volumeAllocationPlan", realized.deepCopy());
            executionPlan.add("volumeMappings", realized.getAsJsonObject("volumeMappings").deepCopy());
            metadata.addProperty("restoreState", "TARGET_PREPARED");update(row, metadata, row.getState());
        }
        final StorageServiceInstanceVO target = selectedTarget;final JsonObject reviewed = executionPlan;
            java.util.function.Supplier<StorageServiceConfigArtifactResponse> change=() -> {
            String current = manager.captureConfigurationSnapshot(target.getId());
            if (!createNew && (revision(target.getId()) != plan.get("expectedRevision").getAsLong()
                    || !capability.get("baselineSha256").getAsString().equals(StorageConfigArchive.sha256(current.getBytes(StandardCharsets.UTF_8))))) {
                throw new InvalidParameterValueException("Configuration changed after planning; a new dry-run is required");
            }
            if (createNew) {
                manager.prepareConfigurationInitialVolume(target, reviewed.getAsJsonObject("createNew"));
                manager.prepareConfigurationVolumeAllocations(target, row, reviewed.getAsJsonObject("volumeAllocationPlan"));
            }
            if(sourceAuthority==null)manager.checkpointConfigurationIdentity(target);
            metadata.remove("planToken");metadata.addProperty("restoreState", "APPLYING");update(row, metadata, row.getState());
            new StorageConfigDomainRestore(manager).apply(target, reviewed, credentials);
            metadata.addProperty("restoreState", "VERIFYING");update(row, metadata, row.getState());
            return response(compactRow(row), row.getUuid());
        };
            if(sourceAuthority!=null)manager.executeProtectedIdentityConfiguration(target,request,sourceAuthority,StorageServiceConfigArtifactResponse.class,change);
            else manager.executeDesiredChange(manager.configurationTargetCommand(target.getId(),request.getBaseCmd()),StorageServiceConfigArtifactResponse.class,change);
            // Native probe, exact desired/runtime verification and LKG promotion have all returned.
            metadata.addProperty("restoreState", "COMPLETE");metadata.addProperty("restoredAt", System.currentTimeMillis());
            updateRestoredArtifact(row, metadata);return response(compactRow(row), row.getUuid());
        } catch (RuntimeException failure) {
            metadata.addProperty("restoreState", "FAILED");
            metadata.addProperty("errorCode", "CONFIG_RESTORE_FAILED");
            update(row, metadata, row.getState());
            throw failure;
        }
    }
    void updateRestoredArtifact(StorageConfigArtifactVO stale, JsonObject metadata) {
        StorageConfigArtifactVO current = artifacts.findById(stale.getId());
        if (current == null || current.getInstanceId() != stale.getInstanceId()) throw new CloudRuntimeException("Restored artifact disappeared before completion");
        // LKG promotion may have superseded the source point. Preserve its current lifecycle and expiry.
        update(current, metadata, current.getState());
        stale.setState(current.getState());stale.setExpires(current.getExpires());stale.setMetadataJson(current.getMetadataJson());
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
            public Boolean getMaintenanceWindow() { return original.getMaintenanceWindow(); }
            public String getPlanToken() { return original.getPlanToken(); }
        };
    }
    public void promoteVerified(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
        promoteVerified(instance, operation, () -> { });
    }

    public void promoteVerified(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, Runnable completeOperation) {
        StorageConfigArtifactVO previous = artifacts.listByInstance(instance.getId()).stream()
                .filter(row -> "RESTORE_POINT".equals(row.getKind()) && "ACTIVE_LKG".equals(row.getState()))
                .findFirst().orElse(null);
        Long expectedActiveId = previous == null ? null : previous.getId();
        long expectedActiveRevision = previous == null ? 0 : previous.getDesiredRevision();
        manager.verifyReconciledStorageDesiredState(instance);
        String snapshot = manager.captureConfigurationSnapshot(instance.getId());
        if (!snapshot.equals(operation.getSnapshotJson())) throw new CloudRuntimeException("Verified configuration changed before restore-point promotion");
        Map<String, byte[]> entries = StorageConfigSemantic.export(snapshot, manager.configurationInstanceMetadata(instance), manager.configurationVolumeMetadata(snapshot));
        JsonObject metadata = new JsonObject();metadata.addProperty("verification", "VERIFIED_SUCCESS");
        metadata.addProperty("sourceInstanceUuid", instance.getUuid());metadata.addProperty("operationUuid", operation.getUuid());
        metadata.addProperty("desiredRevision", operation.getRevision());metadata.addProperty("verifiedAt", System.currentTimeMillis());
        JsonObject nativeGeneration = manager.nativeConfigurationGeneration(instance, null, "status");
        if (!nativeGeneration.has("runtimeRevision") || nativeGeneration.get("runtimeRevision").getAsLong() != operation.getRevision()
                || !nativeGeneration.has("generation") || !operation.getUuid().equals(nativeGeneration.getAsJsonObject("generation").get("operationUuid").getAsString())) {
            throw new CloudRuntimeException("Native runtime generation differs from the desired revision");
        }
        metadata.addProperty("runtimeRevision", operation.getRevision());metadata.add("nativeGeneration", nativeGeneration.get("generation").deepCopy());
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
            if (!artifacts.promoteVerified(instance.getId(), point.getId(), expectedActiveId, expectedActiveRevision, completeOperation)) {
                throw new CloudRuntimeException("Active verified configuration changed before restore-point promotion");
            }
        } catch (RuntimeException failure) {
            try { store.remove(point.getUuid()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            point.setState("FAILED");point.setExpires(new Date(System.currentTimeMillis() + 168 * 3600000L));artifacts.update(point.getId(), point);throw failure;
        }
    }
    public void failInterruptedCandidates(StorageServiceOperationVO operation) {
        for (StorageConfigArtifactVO point : artifacts.listByInstance(operation.getInstanceId())) {
            if (!"RESTORE_POINT".equals(point.getKind()) || !"CANDIDATE".equals(point.getState())
                    || !java.util.Objects.equals(point.getSourceOperationId(), operation.getId())) continue;
            JsonObject audit = metadata(point);
            try { store.remove(point.getUuid());audit.addProperty("cleanupState", "CLEANED"); }
            catch (RuntimeException pending) { audit.addProperty("cleanupState", "PENDING"); }
            audit.addProperty("recoveryState", operation.getState());
            point.setExpires(new Date(System.currentTimeMillis() + 168 * 3600000L));update(point, audit, "FAILED");
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
    public void retainAdSemanticSourceUsage(JsonObject authority,StorageServiceInstanceVO target,StorageServiceOperationVO operation) {
        JsonObject descriptor=StorageAdSemanticSource.validateDescriptor(authority.getAsJsonObject("descriptor"));
        StorageConfigArtifactVO original=artifacts.findByUuid(descriptor.get("ownerArtifactUuid").getAsString());
        if(original==null)throw new CloudRuntimeException("Semantic identity source disappeared before reservation");
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Void>) status->{
            StorageConfigArtifactVO locked=artifacts.lockRow(original.getId(),true);JsonObject value=locked==null?null:metadata(locked);
            if(locked==null||!Set.of("COMPLETE","PARTIAL").contains(locked.getState())||locked.getExpires()==null||!locked.getExpires().after(new Date())||!descriptor.equals(value.get("adIdentitySourceDescriptor"))||!authority.get("reference").equals(value.get("adIdentityCipherReference")))throw new CloudRuntimeException("Original semantic identity authority expired or changed before effects");
            JsonObject usages=value.has("adIdentitySourceUsages")?value.getAsJsonObject("adIdentitySourceUsages"):new JsonObject(),scope=new JsonObject();scope.addProperty("targetInstanceId",target.getId());scope.addProperty("targetInstanceUuid",target.getUuid());scope.addProperty("operationUuid",operation.getUuid());scope.addProperty("revision",operation.getRevision());
            if(usages.has(operation.getUuid())&&!scope.equals(usages.get(operation.getUuid())))throw new CloudRuntimeException("Semantic source recovery reservation changed scope");
            usages.add(operation.getUuid(),scope);value.add("adIdentitySourceUsages",usages);locked.setMetadataJson(value.toString());
            if(!artifacts.update(locked.getId(),locked))throw new CloudRuntimeException("Semantic source recovery reservation could not be persisted");return null;
        });
    }
    protected boolean semanticSourceInUse(JsonObject metadata) {
        if(!metadata.has("adIdentitySourceUsages"))return false;JsonObject usages=metadata.getAsJsonObject("adIdentitySourceUsages");
        for(Map.Entry<String,JsonElement> item:usages.entrySet()){
            if(!item.getValue().isJsonObject())throw new CloudRuntimeException("Semantic source recovery reservation is malformed");JsonObject scope=item.getValue().getAsJsonObject();
            if(!scope.keySet().equals(Set.of("targetInstanceId","targetInstanceUuid","operationUuid","revision"))||!item.getKey().equals(scope.get("operationUuid").getAsString()))throw new CloudRuntimeException("Semantic source recovery reservation has a foreign scope");
            for(String field:Set.of("targetInstanceId","revision"))if(!scope.get(field).isJsonPrimitive()||!scope.get(field).getAsJsonPrimitive().isNumber()||!scope.get(field).getAsString().matches("[1-9][0-9]*"))throw new CloudRuntimeException("Semantic source recovery reservation numeric scope is invalid");
            for(String field:Set.of("targetInstanceUuid","operationUuid"))if(!scope.get(field).isJsonPrimitive()||!scope.get(field).getAsJsonPrimitive().isString()||!scope.get(field).getAsString().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("Semantic source recovery reservation UUID is invalid");
            StorageServiceInstanceVO target=manager.requireInstance(scope.get("targetInstanceId").getAsLong());if(!target.getUuid().equals(scope.get("targetInstanceUuid").getAsString()))throw new CloudRuntimeException("Semantic source recovery reservation target changed");
            StorageServiceOperationVO operation=operations.listByInstance(target.getId()).stream().filter(value->item.getKey().equals(value.getUuid())).findFirst().orElse(null);
            if(operation==null||operation.getRevision()!=scope.get("revision").getAsLong()||!Set.of("COMPLETE","ROLLED_BACK","CANCELLED","BLOCKED","RECONCILED_SUPERSEDED").contains(operation.getState()))return true;
        }
        return false;
    }
    private void cleanupExpired(StorageServiceInstanceVO instance) {
        Date now = new Date();
        for (StorageConfigArtifactVO row : artifacts.listByInstance(instance.getId())) {
            if ("ACTIVE_LKG".equals(row.getState()) || row.getExpires() == null || !row.getExpires().before(now)) continue;
            JsonObject metadata = metadata(row);boolean pending = false;
            try {if(semanticSourceInUse(metadata)){metadata.addProperty("identityCleanupState","RECOVERY_HELD");update(row,metadata,row.getState());continue;}}
            catch(RuntimeException unavailable){metadata.addProperty("identityCleanupState","RECOVERY_SCOPE_UNAVAILABLE");update(row,metadata,row.getState());continue;}
            if(metadata.has("adIdentityCipherReference")){
                JsonObject reference=metadata.getAsJsonObject("adIdentityCipherReference");String sourceOperation=reference.get("sourceOperationUuid").getAsString();
                StorageServiceOperationVO operation=operations.listByInstance(instance.getId()).stream().filter(value->sourceOperation.equals(value.getUuid())).findFirst().orElse(null);
                if(operation==null||!Set.of("COMPLETE","ROLLED_BACK","CANCELLED","BLOCKED","RECONCILED_SUPERSEDED").contains(operation.getState())){
                    metadata.addProperty("identityCleanupState","RECOVERY_HELD");update(row,metadata,row.getState());continue;
                }
                try {StorageAdSemanticSource.remove(new StorageConfigArtifactStore(Path.of(System.getProperty("cloudstack.storage.identity.path","/var/lib/cloudstack-management/storage-identity-capsules"))),reference);metadata.remove("adIdentityCipherReference");}
                catch(RuntimeException cleanup){pending=true;}
            }
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
        for (String key : new String[] {"phase", "runtimeStatus", "credentialCoverage", "restoreState", "errorCode", "verifiedAt", "adIdentityCoverage", "adIdentitySourceDescriptor"}) {
            if (metadata.has(key)) summary.add(key, metadata.get(key).deepCopy());
        }
        result.add("metadata", summary);return result;
    }
    private StorageServiceConfigArtifactResponse response(JsonObject result, String id) {
        StorageServiceConfigArtifactResponse response = new StorageServiceConfigArtifactResponse();response.setResult(result.toString());response.setId(id);
        response.setObjectName("storageserviceconfiguration");return response;
    }
}
