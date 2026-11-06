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

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;

import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.*;
import org.apache.cloudstack.api.response.*;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeBundleVO.State;
import static org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeBundleVO.ValidationStatus;

/** Global catalogue policy. Native preparation/activation remains owned by the #1446 engine. */
public class StorageServiceRuntimeCatalogManagerImpl extends ManagerBase implements StorageServiceRuntimeCatalog {
    static final String PROFILE = "storage-runtime-abi1-schema1-layout2-control3";
    @Inject StorageServiceRuntimeBundleDao bundleDao;
    @Inject StorageServiceRuntimeBundleValidationDao validationDao;
    @Inject StorageServiceRuntimeBundleEventDao eventDao;
    @Inject StorageServiceRuntimeUpgradeDao upgradeDao;
    @Inject StorageServiceInstanceDao instanceDao;
    @Inject SharedFSDao sharedFSDao;
    @Inject VMInstanceDao vmDao;
    @Inject AsyncJobDao asyncJobDao;
    @Inject AccountManager accountManager;
    @Inject StorageServiceRuntimeUpgradeManagerImpl runtime;

    private void rootAdmin() {
        if (!accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId()))
            throw new CloudRuntimeException("ROOT_ADMIN_REQUIRED");
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("INVALID_CATALOG_IDENTIFIER");
    }
    private static <T> T tx(TransactionCallback<T> work) { return Transaction.execute(work); }
    StorageServiceRuntimeBundleVO metadata(Long id) {
        StorageServiceRuntimeBundleVO bundle = id == null ? null : bundleDao.findByIdIncludingRemoved(id);
        if (bundle == null) throw new CloudRuntimeException("RUNTIME_BUNDLE_NOT_FOUND");
        return bundle;
    }
    private void revision(StorageServiceRuntimeBundleVO b, Long expected) {
        if (expected == null || b.getStateRevision() != expected) throw new CloudRuntimeException("CATALOG_REVISION_CONFLICT");
    }
    private void increment(StorageServiceRuntimeBundleVO b) {
        b.setStateRevision(b.getStateRevision() + 1);
        bundleDao.update(b.getId(), b);
    }
    static String digest(byte[] bytes) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) b.append(String.format("%02x",value & 255));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    byte[] trustedKey(String keyId) {
        identifier(keyId);
        Path path = Paths.get("/etc/cloudstack/management/storage-runtime/trusted-keys", keyId + ".pem");
        try {
            if (Files.isRegularFile(path)) return Files.readAllBytes(path);
            return runtime.resource("/storage-runtime/trusted-keys/" + keyId + ".pem");
        } catch (java.io.IOException e) { throw new CloudRuntimeException("SIGNING_KEY_UNKNOWN"); }
    }
    private String trustFingerprint(StorageServiceRuntimeBundleVO b) {
        String pem = new String(trustedKey(b.getSigningKeyId()),StandardCharsets.US_ASCII);
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
        return digest(Base64.getDecoder().decode(base64));
    }
    private String verifierDigest() {
        return digest((digest(runtime.resource("/storage-runtime/bootstrap/runtime_updater.py")) + ":" +
                digest(runtime.resource("/storage-runtime/catalog_verifier.py"))).getBytes(StandardCharsets.US_ASCII));
    }
    private String metadataDigest(StorageServiceRuntimeBundleVO b) {
        return digest((b.getVersion()+"|"+b.getSha256()+"|"+b.getManifestSha256()+"|"+b.getSigningKeyId()+"|"+
                b.getRuntimeAbiVersion()+"|"+b.getDesiredStateSchemaVersion()+"|"+b.getServiceImpact()+"|"+b.getArtifactSize())
                .getBytes(StandardCharsets.UTF_8));
    }
    StorageServiceRuntimeBundleValidationVO receipt(StorageServiceRuntimeBundleVO b) {
        return b.getCurrentValidationId()==null ? null : validationDao.findById(b.getCurrentValidationId());
    }
    boolean verified(StorageServiceRuntimeBundleVO b) {
        StorageServiceRuntimeBundleValidationVO v=receipt(b);
        if (b.getValidationStatus()!=ValidationStatus.VERIFIED || v==null || !"VERIFIED".equals(v.getState()) ||
                !"COMPATIBLE".equals(v.getCompatibility()) || !PROFILE.equals(v.getProfileId())) return false;
        try {
            return metadataDigest(b).equals(v.getMetadataDigest()) && trustFingerprint(b).equals(v.getTrustFingerprint()) &&
                    verifierDigest().equals(v.getVerifierDigest());
        } catch (RuntimeException e) { return false; }
    }
    void requireDestination(StorageServiceRuntimeBundleVO b, boolean rollback) {
        rootAdmin();
        boolean allowed=rollback ? Arrays.asList(State.PUBLISHED,State.SUSPENDED,State.RETIRED).contains(b.getState()) :
                b.getState()==State.PUBLISHED;
        if (!allowed || !verified(b)) throw new CloudRuntimeException(rollback ? "CATALOG_ROLLBACK_BLOCKED" : "CATALOG_APPLY_BLOCKED");
    }
    private StorageServiceRuntimeBundleEventVO event(StorageServiceRuntimeBundleVO b, String request, String action,
            State from, long revision, String reason, String result) {
        StorageServiceRuntimeBundleEventVO e=new StorageServiceRuntimeBundleEventVO();
        e.setBundleId(b.getId());e.setRequestId(request);e.setAction(action);e.setFromState(from.name());e.setToState(b.getState().name());
        e.setFromRevision(revision);e.setToRevision(b.getStateRevision());e.setReason(reason);e.setResultCode(result);
        e.setActorUserId(CallContext.current().getCallingUserId());e.setAccountId(CallContext.current().getCallingAccount().getId());
        AsyncJobExecutionContext c=AsyncJobExecutionContext.getCurrentExecutionContext();
        if(c!=null && c.getJob()!=null)e.setJobId(c.getJob().getId());
        return eventDao.persist(e);
    }
    public StorageServiceRuntimeBundleResponse register(RegisterStorageServiceRuntimeBundleCmd cmd) {
        rootAdmin();
        identifier(cmd.getVersion());identifier(cmd.getRuntimeAbiVersion());identifier(cmd.getDesiredStateSchemaVersion());identifier(cmd.getSigningKeyId());
        if(cmd.getSha256()==null || !cmd.getSha256().matches("[a-fA-F0-9]{64}") ||
                cmd.getManifestSha256()==null || !cmd.getManifestSha256().matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("INVALID_RUNTIME_HASH");
        for(String location:Arrays.asList(cmd.getArtifactUrl(),cmd.getManifestUrl(),cmd.getSignatureUrl())) {
            java.net.URI uri;
            try { uri=java.net.URI.create(location); } catch(RuntimeException e) { throw new IllegalArgumentException("INVALID_ARTIFACT_URL"); }
            if(!Arrays.asList("http","https").contains(uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost()==null ||
                    uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException("INVALID_ARTIFACT_URL");
        }
        if(cmd.getArtifactSize()!=null && (cmd.getArtifactSize()<=0 || cmd.getArtifactSize()>64L*1024*1024))
            throw new IllegalArgumentException("INVALID_ARTIFACT_SIZE");
        StorageServiceRuntimeBundleVO.ServiceImpact impact=StorageServiceRuntimeBundleVO.ServiceImpact.valueOf(cmd.getServiceImpact().toUpperCase(Locale.ROOT));
        StorageServiceRuntimeBundleVO b;
        try {
            b=tx(ignored->{
                StorageServiceRuntimeBundleVO old=bundleDao.findVersionIncludingRemoved(cmd.getVersion());
                if(old!=null) { sameIdentity(old,cmd);return old; }
                StorageServiceRuntimeBundleVO created=bundleDao.persist(new StorageServiceRuntimeBundleVO(cmd.getVersion(),
                        cmd.getRuntimeAbiVersion(),cmd.getDesiredStateSchemaVersion(),impact,cmd.getArtifactUrl(),
                        cmd.getManifestUrl(),cmd.getSignatureUrl(),cmd.getArtifactSize(),cmd.getSha256().toLowerCase(Locale.ROOT),
                        cmd.getManifestSha256().toLowerCase(Locale.ROOT),cmd.getSigningKeyId()));
                event(created,"register-"+created.getUuid(),"REGISTER",State.DRAFT,0,"Registered unverified draft","COMPLETE");
                return created;
            });
        } catch(RuntimeException conflict) {
            b=bundleDao.findVersionIncludingRemoved(cmd.getVersion());
            if(b==null)throw conflict;
            sameIdentity(b,cmd);
        }
        return response(b,null);
    }
    private void sameIdentity(StorageServiceRuntimeBundleVO b,RegisterStorageServiceRuntimeBundleCmd c) {
        if(!b.getSha256().equalsIgnoreCase(c.getSha256()) || !b.getManifestSha256().equalsIgnoreCase(c.getManifestSha256()))
            throw new CloudRuntimeException("RUNTIME_VERSION_CONFLICT");
        if(!b.getRuntimeAbiVersion().equals(c.getRuntimeAbiVersion()) || !b.getDesiredStateSchemaVersion().equals(c.getDesiredStateSchemaVersion()) ||
                !b.getSigningKeyId().equals(c.getSigningKeyId()) || !b.getServiceImpact().name().equalsIgnoreCase(c.getServiceImpact()) ||
                (b.getArtifactSize()!=null && c.getArtifactSize()!=null && !Objects.equals(b.getArtifactSize(),c.getArtifactSize()))) throw new CloudRuntimeException("REGISTERED_METADATA_CONFLICT");
    }
    public ListResponse<StorageServiceRuntimeBundleResponse> list(ListStorageServiceRuntimeBundlesCmd cmd) {
        rootAdmin();
        String purpose=cmd.getPurpose()==null?"catalog":cmd.getPurpose();
        if(!Arrays.asList("catalog","apply").contains(purpose))throw new IllegalArgumentException("INVALID_CATALOG_PURPOSE");
        JsonObject capabilities=null;
        if(cmd.getSharedFileSystemId()!=null) {
            SharedFSVO fs=sharedFSDao.findById(cmd.getSharedFileSystemId());
            StorageServiceInstanceVO i=fs==null || fs.getVmId()==null?null:instanceDao.findByVmId(fs.getVmId());
            if(i!=null)try { capabilities=runtime.invoke(i,StorageServiceRuntimeOperation.CAPABILITIES,"catalog-read-"+i.getId(),null); }
            catch(RuntimeException unavailable) { capabilities=new JsonObject(); }
            else capabilities=new JsonObject();
        }
        List<StorageServiceRuntimeBundleResponse> rows=new ArrayList<>();
        List<StorageServiceRuntimeBundleVO> all=bundleDao.listAllIncludingRemoved();
        all.sort(Comparator.comparingLong(StorageServiceRuntimeBundleVO::getId).reversed());
        for(StorageServiceRuntimeBundleVO b:all) {
            if(cmd.getId()!=null && b.getId()!=cmd.getId())continue;
            if(cmd.getVersion()!=null && !cmd.getVersion().equals(b.getVersion()))continue;
            if(b.getState()==State.DELETED && cmd.getId()==null && !"DELETED".equals(cmd.getState()))continue;
            if(cmd.getState()!=null && !cmd.getState().equals(b.getState().name()))continue;
            if(cmd.getValidationStatus()!=null && !cmd.getValidationStatus().equals(b.getValidationStatus().name()))continue;
            if(cmd.getKeyword()!=null && !(b.getVersion()+" "+b.getSigningKeyId()).toLowerCase(Locale.ROOT).contains(cmd.getKeyword().toLowerCase(Locale.ROOT)))continue;
            if("apply".equals(purpose) && (b.getState()!=State.PUBLISHED || !verified(b)))continue;
            rows.add(response(b,capabilities));
        }
        return page(rows,cmd);
    }
    private <T extends org.apache.cloudstack.api.BaseResponse> ListResponse<T> page(List<T> rows,BaseListCmd cmd) {
        int count=rows.size(),start=(int)Math.min(count,cmd.getStartIndex()==null?0:cmd.getStartIndex());
        int end=(int)Math.min(count,(long)start+(cmd.getPageSizeVal()==null?50:cmd.getPageSizeVal()));
        ListResponse<T> response=new ListResponse<>();response.setResponses(rows.subList(start,end),count);return response;
    }
    private StorageServiceRuntimeBundleEventVO existingEvent(long bundleId,String request) {
        return eventDao.listAll().stream().filter(e->e.getBundleId()==bundleId && request.equals(e.getRequestId())).findFirst().orElse(null);
    }
    @Override public StorageServiceRuntimeCatalogRecordResponse validate(ValidateStorageServiceRuntimeBundleCmd cmd) {
        rootAdmin();identifier(cmd.getRequestId());
        StorageServiceRuntimeBundleValidationVO v=tx(ignored->{
            StorageServiceRuntimeBundleVO b=bundleDao.lockRow(cmd.getBundleId(),true);
            if(b==null)throw new CloudRuntimeException("RUNTIME_BUNDLE_NOT_FOUND");
            Optional<StorageServiceRuntimeBundleValidationVO> old=validationDao.listAll().stream()
                    .filter(x->x.getBundleId()==b.getId() && cmd.getRequestId().equals(x.getRequestId())).findFirst();
            if(old.isPresent())return old.get();
            revision(b,cmd.getExpectedRevision());
            if(!Arrays.asList(State.DRAFT,State.SUSPENDED,State.PUBLISHED).contains(b.getState()) ||
                    b.getValidationStatus()==ValidationStatus.RUNNING)throw new CloudRuntimeException("CATALOG_VALIDATION_BLOCKED");
            State from=b.getState();long previous=b.getStateRevision();
            StorageServiceRuntimeBundleValidationVO n=new StorageServiceRuntimeBundleValidationVO();
            n.setBundleId(b.getId());n.setRequestId(cmd.getRequestId());n.setObservedRevision(previous+1);
            n.setState("RUNNING");n.setPhase("VERIFYING_ARTIFACT");n.setMetadataDigest(metadataDigest(b));n.setProfileId(PROFILE);
            AsyncJobExecutionContext c=AsyncJobExecutionContext.getCurrentExecutionContext();
            if(c!=null && c.getJob()!=null)n.setOriginalJobId(c.getJob().getId());
            n=validationDao.persist(n);b.setCurrentValidationId(n.getId());b.setValidationStatus(ValidationStatus.RUNNING);increment(b);
            StorageServiceRuntimeBundleEventVO e=event(b,cmd.getRequestId(),"VALIDATE",from,previous,"Validate signed artifact","RUNNING");
            e.setValidationId(n.getId());eventDao.update(e.getId(),e);return n;
        });
        if(!"RUNNING".equals(v.getState()) || (v.getOriginalJobId()!=null && !Objects.equals(v.getOriginalJobId(),currentJob())))return validationResponse(v);
        final StorageServiceRuntimeBundleValidationVO started=v;
        StorageServiceRuntimeBundleVO snapshot=metadata(v.getBundleId());
        JsonObject result;
        String trust=null,verifier=null;
        try {
            trust=trustFingerprint(snapshot);verifier=verifierDigest();
            result=verifyArtifact(snapshot);
        } catch(RuntimeException unavailable) {
            result=new JsonObject();result.addProperty("success",false);result.addProperty("errorCode","VALIDATION_UNKNOWN");
            result.addProperty("message","Artifact or trusted verification input unavailable; inspect source and retry explicitly");
        }
        final JsonObject proof=result;final String fingerprint=trust,version=verifier;
        v=tx(ignored->{
            StorageServiceRuntimeBundleVO b=bundleDao.lockRow(started.getBundleId(),true);
            StorageServiceRuntimeBundleValidationVO current=validationDao.lockRow(started.getId(),true);
            boolean success=proof.has("success") && proof.get("success").getAsBoolean();
            current.setState(success?"VERIFIED": "VALIDATION_UNKNOWN".equals(text(proof,"errorCode"))?"UNKNOWN":"FAILED");
            current.setPhase("COMPLETE");current.setCompleted(new Date());current.setHeartbeat(new Date());
            current.setResultJson(proof.toString());current.setTrustFingerprint(fingerprint);current.setVerifierDigest(version);
            current.setCompatibility(success?text(proof,"compatibility"):"UNKNOWN");
            current.setErrorCode(text(proof,"errorCode"));current.setErrorMessage(text(proof,"message")==null?null:text(proof,"message").substring(0,Math.min(1024,text(proof,"message").length())));validationDao.update(current.getId(),current);
            if(Objects.equals(b.getCurrentValidationId(),current.getId()) && b.getStateRevision()==current.getObservedRevision()) {
                b.setValidationStatus(ValidationStatus.valueOf(current.getState()));increment(b);
            }
            StorageServiceRuntimeBundleEventVO e=existingEvent(b.getId(),cmd.getRequestId());
            e.setResultCode(current.getState());e.setToRevision(b.getStateRevision());eventDao.update(e.getId(),e);
            return current;
        });
        return validationResponse(v);
    }
    private Long currentJob() {
        AsyncJobExecutionContext c=AsyncJobExecutionContext.getCurrentExecutionContext();
        return c==null || c.getJob()==null?null:c.getJob().getId();
    }
    protected JsonObject verifyArtifact(StorageServiceRuntimeBundleVO b) {
        Path stage=null;
        Process process=null;
        try {
            stage=Files.createTempDirectory("storage-catalog-");
            Files.setPosixFilePermissions(stage,PosixFilePermissions.fromString("rwxr-xr-x"));
            Map<String,byte[]> inputs=new LinkedHashMap<>();
            inputs.put("bundle.tar.gz",runtime.download(b.getArtifactUrl(),64*1024*1024));
            inputs.put("manifest.json",runtime.download(b.getManifestUrl(),1024*1024));
            inputs.put("manifest.sig",runtime.download(b.getSignatureUrl(),16*1024));
            inputs.put("runtime_updater.py",runtime.resource("/storage-runtime/bootstrap/runtime_updater.py"));
            inputs.put("catalog_verifier.py",runtime.resource("/storage-runtime/catalog_verifier.py"));
            inputs.put(b.getSigningKeyId()+".pem",trustedKey(b.getSigningKeyId()));
            JsonObject metadata=new JsonObject();
            metadata.addProperty("version",b.getVersion());metadata.addProperty("sha256",b.getSha256());
            metadata.addProperty("manifestsha256",b.getManifestSha256());metadata.addProperty("signingkeyid",b.getSigningKeyId());
            metadata.addProperty("runtimeabiversion",b.getRuntimeAbiVersion());metadata.addProperty("desiredstateschemaversion",b.getDesiredStateSchemaVersion());
            metadata.addProperty("serviceimpact",b.getServiceImpact().name());metadata.addProperty("artifactsize",b.getArtifactSize());
            inputs.put("metadata.json",metadata.toString().getBytes(StandardCharsets.UTF_8));
            for(Map.Entry<String,byte[]> entry:inputs.entrySet()) {
                Path p=stage.resolve(entry.getKey());Files.write(p,entry.getValue());Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("r--r--r--"));
            }
            List<String> args=new ArrayList<>();
            if("root".equals(System.getProperty("user.name")))Collections.addAll(args,"/usr/sbin/runuser","-u","nobody","--");
            Collections.addAll(args,"/usr/bin/python3","-B",stage.resolve("catalog_verifier.py").toString(),stage.toString());
            process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new CloudRuntimeException("VALIDATION_WORKER_TIMEOUT");}
            byte[] output=process.getInputStream().readNBytes(16385);
            if(output.length>16384)throw new CloudRuntimeException("VALIDATION_OUTPUT_TOO_LARGE");
            return JsonParser.parseString(new String(output,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(java.io.IOException e){throw new CloudRuntimeException("VALIDATION_WORKER_UNAVAILABLE",e);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new CloudRuntimeException("VALIDATION_INTERRUPTED",e);}
        finally {
            if(process!=null && process.isAlive())process.destroyForcibly();
            if(stage!=null)try(java.util.stream.Stream<Path> paths=Files.walk(stage)){
                for(Path p:paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))Files.deleteIfExists(p);
            }catch(java.io.IOException cleanup){logger.warn("Unable to remove catalogue verification temporary directory",cleanup);}
        }
    }
    @Override public StorageServiceRuntimeBundleResponse transition(TransitionStorageServiceRuntimeBundleCmd cmd) {
        rootAdmin();identifier(cmd.getRequestId());
        String action=cmd.getAction().toUpperCase(Locale.ROOT);
        if(cmd.getReason()==null || cmd.getReason().trim().isEmpty() || cmd.getReason().length()>1024)
            throw new IllegalArgumentException("CATALOG_REASON_REQUIRED");
        boolean[] revoking={false};
        StorageServiceRuntimeBundleVO b=tx(ignored->{
            StorageServiceRuntimeBundleVO locked=bundleDao.lockRow(cmd.getBundleId(),true);
            if(locked==null)throw new CloudRuntimeException("RUNTIME_BUNDLE_NOT_FOUND");
            StorageServiceRuntimeBundleEventVO old=existingEvent(locked.getId(),cmd.getRequestId());
            if(old!=null){
                if(!action.equals(old.getAction()) || !cmd.getReason().equals(old.getReason()))throw new CloudRuntimeException("CATALOG_REQUEST_CONFLICT");
                return locked;
            }
            revision(locked,cmd.getExpectedRevision());
            if(locked.getValidationStatus()==ValidationStatus.RUNNING)throw new CloudRuntimeException("VALIDATION_RUNNING");
            State from=locked.getState();long previous=locked.getStateRevision();
            switch(action) {
                case "PUBLISH":
                    if(!Arrays.asList(State.DRAFT,State.SUSPENDED).contains(from) || !verified(locked))throw new CloudRuntimeException("CATALOG_PUBLISH_BLOCKED");
                    locked.setState(State.PUBLISHED);break;
                case "SUSPEND":
                    if(from!=State.PUBLISHED)throw new CloudRuntimeException("CATALOG_TRANSITION_BLOCKED");
                    locked.setState(State.SUSPENDED);break;
                case "RETIRE":
                    if(!Arrays.asList(State.DRAFT,State.PUBLISHED,State.SUSPENDED).contains(from))throw new CloudRuntimeException("CATALOG_TRANSITION_BLOCKED");
                    locked.setState(State.RETIRED);break;
                case "DELETE":
                    if(!Arrays.asList(State.DRAFT,State.RETIRED).contains(from) || !references(locked.getId()).isEmpty())
                        throw new CloudRuntimeException("CATALOG_DELETE_REFERENCED_OR_INVALID_STATE");
                    locked.setState(State.DELETED);locked.setRemoved(new Date());break;
                case "REVOKE":
                    if(Arrays.asList(State.DELETED,State.REVOKED).contains(from))throw new CloudRuntimeException("CATALOG_TRANSITION_BLOCKED");
                    locked.setState(State.REVOKING);revoking[0]=true;break;
                default:throw new IllegalArgumentException("UNSUPPORTED_CATALOG_ACTION");
            }
            increment(locked);event(locked,cmd.getRequestId(),action,from,previous,cmd.getReason(),revoking[0]?"PENDING_ACK":"COMPLETE");
            return locked;
        });
        if(revoking[0]) {
            boolean acknowledged=true;
            for(StorageServiceInstanceVO i:policyTargets(b.getId())) {
                try { syncPolicy(i); }catch(RuntimeException unavailable){acknowledged=false;}
            }
            final boolean complete=acknowledged;final long id=b.getId();
            b=tx(ignored->{
                StorageServiceRuntimeBundleVO locked=bundleDao.lockRow(id,true);
                if(locked.getState()==State.REVOKING && complete){locked.setState(State.REVOKED);increment(locked);}
                StorageServiceRuntimeBundleEventVO e=existingEvent(id,cmd.getRequestId());
                e.setToState(locked.getState().name());e.setToRevision(locked.getStateRevision());e.setResultCode(complete?"COMPLETE":"PENDING_ACK");eventDao.update(e.getId(),e);
                return locked;
            });
        }
        if(Arrays.asList("SUSPEND","RETIRE","PUBLISH").contains(action)) {
            for(StorageServiceInstanceVO i:policyTargets(b.getId())) {
                try { syncPolicy(i); } catch(RuntimeException pending) {
                    logger.info("Catalogue admission changed; native policy acknowledgement pending for instance {}",i.getUuid());
                }
            }
        }
        return response(b,null);
    }
    private List<StorageServiceInstanceVO> policyTargets(long id) {
        Set<Long> active=new HashSet<>();
        for(StorageServiceRuntimeUpgradeVO u:upgradeDao.listAll()) {
            if((u.getBundleId()==id || Objects.equals(u.getPreviousBundleId(),id)) && !terminal(u))active.add(u.getInstanceId());
        }
        return instanceDao.listAll().stream().filter(i->Objects.equals(i.getCurrentRuntimeBundleId(),id) ||
                Objects.equals(i.getPreviousRuntimeBundleId(),id) || active.contains(i.getId())).collect(Collectors.toList());
    }
    static boolean terminal(StorageServiceRuntimeUpgradeVO u) {
        return Arrays.asList(StorageServiceRuntimeUpgradeVO.State.COMPLETE,StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK,
                StorageServiceRuntimeUpgradeVO.State.FAILED).contains(u.getState());
    }
    void syncForOperation(StorageServiceInstanceVO i, StorageServiceRuntimeUpgradeVO operation) { syncPolicy(i,operation.getId()); }
    void syncPolicy(StorageServiceInstanceVO i) { syncPolicy(i,null); }
    private void syncPolicy(StorageServiceInstanceVO i,Long admittedOperation) {
        StorageServiceRuntimeUpgradeVO active=upgradeDao.findActiveByInstanceId(i.getId());
        if(active!=null && !Objects.equals(admittedOperation,active.getId()) && active.getState()!=StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY &&
                !"BOOTSTRAPPING".equals(active.getPhase()) && !active.getPhase().startsWith("RECEIVING") &&
                !"VERIFYING_BUNDLE".equals(active.getPhase()))throw new CloudRuntimeException("CATALOG_TARGET_OPERATION_UNCONFIRMED");
        StorageServiceRuntimeBundleVO current=i.getCurrentRuntimeBundleId()==null?null:metadata(i.getCurrentRuntimeBundleId());
        if(current==null && active!=null)current=metadata(active.getBundleId());
        if(current==null)throw new CloudRuntimeException("CATALOG_TARGET_RUNTIME_UNKNOWN");
        runtime.ensureBootstrap(i,current,"catalog-policy-"+i.getId());
        JsonObject request=new JsonObject();request.addProperty("instanceUuid",i.getUuid());
        JsonArray entries=new JsonArray();
        for(StorageServiceRuntimeBundleVO b:bundleDao.listAllIncludingRemoved()) {
            JsonObject e=new JsonObject();e.addProperty("bundleId",b.getUuid());e.addProperty("version",b.getVersion());
            e.addProperty("manifestSha256",b.getManifestSha256());e.addProperty("revision",b.getStateRevision());
            e.addProperty("state",b.getState().name());e.addProperty("validation",verified(b)?"VERIFIED":"UNKNOWN");entries.add(e);
        }
        // Merge bounded batches; no omitted entry can remove an older native revocation fence.
        for(int start=0;start<entries.size();start+=32) {
            JsonArray batch=new JsonArray();
            for(int n=start;n<Math.min(start+32,entries.size());n++)batch.add(entries.get(n));
            request.add("bundles",batch);
            JsonObject ack=runtime.invoke(i,StorageServiceRuntimeOperation.SYNC_CATALOG_POLICY,"catalog-policy-"+i.getId(),request);
            if(!"CATALOG_POLICY_SYNCED".equals(text(ack,"phase")) || !i.getUuid().equals(text(ack,"instanceUuid")) ||
                    !ack.has("revisions"))throw new CloudRuntimeException("CATALOG_POLICY_ACK_UNKNOWN");
            JsonObject revisions=ack.getAsJsonObject("revisions");
            for(com.google.gson.JsonElement entry:batch) {
                JsonObject expected=entry.getAsJsonObject();
                if(!revisions.has(text(expected,"version")) || revisions.get(text(expected,"version")).getAsLong()<expected.get("revision").getAsLong())
                    throw new CloudRuntimeException("CATALOG_POLICY_ACK_STALE");
            }
        }
    }

    List<StorageServiceRuntimeCatalogRecordResponse> references(long bundleId) {
        List<StorageServiceRuntimeCatalogRecordResponse> rows=new ArrayList<>();
        for(StorageServiceInstanceVO i:instanceDao.listAll()) {
            if(Objects.equals(i.getCurrentRuntimeBundleId(),bundleId))rows.add(usage(i,"CURRENT",i.getRuntimeState(),i.getUuid()));
            if(Objects.equals(i.getPreviousRuntimeBundleId(),bundleId))rows.add(usage(i,"PREVIOUS",i.getRuntimeState(),i.getUuid()));
        }
        for(StorageServiceRuntimeUpgradeVO u:upgradeDao.listAll()) {
            if(u.getBundleId()==bundleId || Objects.equals(u.getPreviousBundleId(),bundleId)) {
                StorageServiceInstanceVO i=instanceDao.findByIdIncludingRemoved(u.getInstanceId());
                StorageServiceRuntimeCatalogRecordResponse row=usage(i,"OPERATION",u.getState().name(),u.getUuid());
                row.setName(u.getTransactionId());row.setCreated(u.getStarted());row.setCompleted(u.getCompleted());rows.add(row);
            }
        }
        return rows;
    }
    private StorageServiceRuntimeCatalogRecordResponse usage(StorageServiceInstanceVO i,String type,String state,String id) {
        StorageServiceRuntimeCatalogRecordResponse response=new StorageServiceRuntimeCatalogRecordResponse();
        response.setId(type+"-"+id);response.setType(type);response.setState(state);response.setName(i==null?id:i.getUuid());
        if(i!=null) {
            response.setInstanceid(i.getUuid());com.cloud.vm.VMInstanceVO vm=i.getVmId()==null?null:vmDao.findByIdIncludingRemoved(i.getVmId());
            response.setVmid(vm==null?null:vm.getUuid());SharedFSVO fs=i.getVmId()==null?null:sharedFSDao.findByVm(i.getVmId());
            response.setSharedfilesystemid(fs==null?null:fs.getUuid());
        }
        response.setObjectName("storageserviceruntimecatalogrecord");return response;
    }
    private StorageServiceRuntimeBundleResponse response(StorageServiceRuntimeBundleVO b,JsonObject cap) {
        StorageServiceRuntimeBundleResponse response=new StorageServiceRuntimeBundleResponse();
        response.setId(b.getUuid());response.setName(b.getVersion());response.setVersion(b.getVersion());response.setState(b.getState().name());
        response.setRevision(b.getStateRevision());response.setRuntimeAbiVersion(b.getRuntimeAbiVersion());response.setDesiredStateSchemaVersion(b.getDesiredStateSchemaVersion());
        response.setServiceImpact(b.getServiceImpact().name());response.setArtifactUrl(b.getArtifactUrl());response.setArtifactSize(b.getArtifactSize());
        response.setManifesturl(b.getManifestUrl());response.setSignatureurl(b.getSignatureUrl());response.setSha256(b.getSha256());
        response.setManifestSha256(b.getManifestSha256());response.setSigningKeyId(b.getSigningKeyId());response.setCreated(b.getCreated());
        response.setValidationstatus(b.getValidationStatus().name());response.setReferencecount(references(b.getId()).size());response.setProfileid(PROFILE);
        StorageServiceRuntimeBundleValidationVO v=receipt(b);
        if(v!=null){response.setValidationid(v.getUuid());response.setValidationjson(v.getResultJson());}
        String compatibility=v==null?"UNKNOWN":v.getCompatibility();
        if(cap!=null) {
            compatibility=cap.has("runtimeAbiVersion") && cap.has("desiredStateSchemaVersion") ?
                    b.getRuntimeAbiVersion().equals(text(cap,"runtimeAbiVersion")) && b.getDesiredStateSchemaVersion().equals(text(cap,"desiredStateSchemaVersion")) &&
                    "NONE".equals(b.getServiceImpact().name())?"COMPATIBLE":"INCOMPATIBLE":"UNKNOWN";
        }
        if(compatibility==null)compatibility="UNKNOWN";
        response.setCompatibility(compatibility);
        boolean eligible=b.getState()==State.PUBLISHED && verified(b) && "COMPATIBLE".equals(compatibility);
        response.setEligible(eligible);
        response.setEligibilityreason(eligible?(cap==null?"PROFILE_COMPATIBLE":"TRUSTED_PREPARATION_REQUIRED"):
                b.getState()!=State.PUBLISHED?"NOT_PUBLISHED":!verified(b)?"VALIDATION_REQUIRED":"TARGET_"+compatibility);
        response.setObjectName("storageserviceruntimebundle");return response;
    }
    private StorageServiceRuntimeCatalogRecordResponse validationResponse(StorageServiceRuntimeBundleValidationVO v) {
        StorageServiceRuntimeCatalogRecordResponse response=new StorageServiceRuntimeCatalogRecordResponse();
        response.setId(v.getUuid());response.setBundleid(metadata(v.getBundleId()).getUuid());response.setRequestid(v.getRequestId());
        response.setState(v.getState());response.setPhase(v.getPhase());response.setCompatibility(v.getCompatibility());
        response.setDetailsjson(v.getResultJson());response.setErrorcode(v.getErrorCode());response.setErrormessage(v.getErrorMessage());
        response.setRevision(v.getObservedRevision());response.setCreated(v.getCreated());response.setCompleted(v.getCompleted());
        if(v.getOriginalJobId()!=null && asyncJobDao.findById(v.getOriginalJobId())!=null)response.setJobId(asyncJobDao.findById(v.getOriginalJobId()).getUuid());
        response.setObjectName("storageserviceruntimecatalogrecord");return response;
    }
    @Override public ListResponse<StorageServiceRuntimeCatalogRecordResponse> validations(ListStorageServiceRuntimeBundleValidationsCmd cmd) {
        rootAdmin();metadata(cmd.getBundleId());
        List<StorageServiceRuntimeCatalogRecordResponse> rows=new ArrayList<>();
        List<StorageServiceRuntimeBundleValidationVO> all=validationDao.listAll();all.sort(Comparator.comparing(StorageServiceRuntimeBundleValidationVO::getId).reversed());
        for(StorageServiceRuntimeBundleValidationVO v:all)if(v.getBundleId()==cmd.getBundleId()) {
            if("RUNNING".equals(v.getState()) && v.getOriginalJobId()!=null) {
                org.apache.cloudstack.framework.jobs.AsyncJob owner=asyncJobDao.findById(v.getOriginalJobId());
                if(owner==null || owner.getStatus()!=org.apache.cloudstack.jobs.JobInfo.Status.IN_PROGRESS) {
                    final Long id=v.getId();
                    v=tx(ignored->{
                        StorageServiceRuntimeBundleVO b=bundleDao.lockRow(cmd.getBundleId(),true);
                        StorageServiceRuntimeBundleValidationVO current=validationDao.lockRow(id,true);
                        if("RUNNING".equals(current.getState())) {
                            current.setState("UNKNOWN");current.setPhase("OBSERVATION_PENDING");current.setCompleted(new Date());
                            current.setErrorCode("VALIDATION_INTERRUPTED");current.setErrorMessage("Original job ended without a confirmed receipt; validate again explicitly");
                            validationDao.update(current.getId(),current);
                            if(Objects.equals(b.getCurrentValidationId(),current.getId())){b.setValidationStatus(ValidationStatus.UNKNOWN);increment(b);}
                            StorageServiceRuntimeBundleEventVO e=existingEvent(b.getId(),current.getRequestId());
                            if(e!=null){e.setResultCode("UNKNOWN");e.setToRevision(b.getStateRevision());eventDao.update(e.getId(),e);}
                        }
                        return current;
                    });
                }
            }
            rows.add(validationResponse(v));
        }
        return page(rows,cmd);
    }
    @Override public ListResponse<StorageServiceRuntimeCatalogRecordResponse> usages(ListStorageServiceRuntimeBundleUsagesCmd cmd) {
        rootAdmin();metadata(cmd.getBundleId());return page(references(cmd.getBundleId()),cmd);
    }
    @Override public ListResponse<StorageServiceRuntimeCatalogRecordResponse> events(ListStorageServiceRuntimeBundleEventsCmd cmd) {
        rootAdmin();metadata(cmd.getBundleId());List<StorageServiceRuntimeCatalogRecordResponse> rows=new ArrayList<>();
        List<StorageServiceRuntimeBundleEventVO> all=eventDao.listAll();all.sort(Comparator.comparing(StorageServiceRuntimeBundleEventVO::getId).reversed());
        for(StorageServiceRuntimeBundleEventVO e:all)if(e.getBundleId()==cmd.getBundleId()) {
            StorageServiceRuntimeCatalogRecordResponse response=new StorageServiceRuntimeCatalogRecordResponse();
            response.setId(e.getUuid());response.setBundleid(metadata(e.getBundleId()).getUuid());response.setAction(e.getAction());
            response.setState(e.getToState());response.setReason(e.getReason());response.setRevision(e.getToRevision());response.setCreated(e.getCreated());
            response.setActoruserid(e.getActorUserId());response.setRequestid(e.getRequestId());response.setErrorcode(e.getResultCode());
            JsonObject detail=new JsonObject();detail.addProperty("fromState",e.getFromState());detail.addProperty("toState",e.getToState());
            detail.addProperty("fromRevision",e.getFromRevision());detail.addProperty("toRevision",e.getToRevision());detail.addProperty("result",e.getResultCode());
            response.setDetailsjson(detail.toString());response.setObjectName("storageserviceruntimecatalogrecord");rows.add(response);
        }
        return page(rows,cmd);
    }
    private static String text(JsonObject object,String key) {
        return object.has(key) && !object.get(key).isJsonNull()?object.get(key).getAsString():null;
    }
}
