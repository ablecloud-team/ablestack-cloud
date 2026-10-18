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

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.After;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.utils.db.DbProperties;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Volume;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.projects.dao.ProjectDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;

/** Starts after a durable foundation; OS/hypervisor fixtures do not claim a VM creator or real protocol I/O test. */
public class StorageConfigInitialBaselineFlowTest {
    static final Gson G=new Gson();
    static final String TARGET=id(80), ANCHOR=id(7), BUNDLE=id(61), TEMPLATE=id(601), ZONE=id(903), POOL=id(9), ARTIFACT=id(900);
    static Path repository() {
        Path value=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(!Files.exists(value.resolve("systemvm/debian/usr/local/lib/ablestack-storage/config_generation.py"))) value=value.getParent();
        return value;
    }
    final Path repo=repository();
    static String id(int n){return java.util.UUID.nameUUIDFromBytes(("gate-c-"+n).getBytes(StandardCharsets.UTF_8)).toString();}
    static JsonObject obj(String s){return JsonParser.parseString(s).getAsJsonObject();}
    static <T>T copy(T v,Class<T> c){return G.fromJson(G.toJson(v),c);}
    static void set(Object o,String k,Object v){ReflectionTestUtils.setField(o,k,v);}
    static String sha(String s){return StorageConfigArchive.sha256(s.getBytes(StandardCharsets.UTF_8));}
    static String snapshot(long id){
        JsonObject x=new JsonObject();x.addProperty("schemaVersion",2);x.addProperty("instanceId",id);JsonArray a=new JsonArray();
        for(String n:List.of("storage_service_protocol","storage_file_share","storage_block_target","storage_identity_domain","storage_access_rule","storage_posix_directory_policy")){
            JsonObject t=new JsonObject();t.addProperty("table",n);t.add("columns",new JsonArray());t.add("rows",new JsonArray());a.add(t);
        }
        x.add("tables",a);return x.toString();
    }
    final Map<Long,StorageServiceInstanceVO> instanceRows=new LinkedHashMap<>();
    final Map<Long,StorageConfigArtifactVO> artifactRows=new LinkedHashMap<>();
    final Map<Long,StorageServiceOperationVO> operationRows=new LinkedHashMap<>();
    final Map<Long,VolumeVO> volumeRows=new LinkedHashMap<>();
    final List<String> commands=new ArrayList<>(), events=new ArrayList<>();
    final JsonArray commandCallers=new JsonArray();
    final AtomicLong nextOperation=new AtomicLong(100);
    final Path root=createTemp(), nativeCase=root.resolve("native");
    static Path createTemp(){try{return Files.createTempDirectory("storage-config-baseline-flow-");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
    StorageServiceInstanceDao instances;
    StorageConfigArtifactDao artifacts;
    StorageServiceOperationDao operations;
    StorageConfigArtifactStore store;
    StorageServiceConfiguration configuration;
    StorageServiceRuntimeUpgradeManager runtimes;
    JsonObject plan, metadata, runtimePin, allocation;
    StorageServiceOperationVO foundation;
    String initialUuid, archiveSha;
    boolean creatorStageInitialOnly;
    boolean existingInitial=true;
    @Rule public TestName testName=new TestName();
    final Map<String,String> sourceToTarget=new LinkedHashMap<>();
    Path renderedStore;
    final List<AutoCloseable> closeables=new ArrayList<>();

    static final String[] SOURCE_DATA={"5c53f9d6-4153-4e3d-a440-7d507f4a106b","c7ca4348-e835-46cb-ba65-f105264b8e52","48906777-5ce1-42a3-9690-054f73496f1d","076eae6e-b0b5-4a8a-885f-b594454bc925"};
    byte[] publicArchive()throws Exception{
        JsonObject resources=obj(Files.readString(repo.resolve("server/src/test/resources/storage-config/issue909-public-four-protocol-bindings.json"))).getAsJsonObject("publicSemanticResources");
        Map<String,byte[]> entries=new LinkedHashMap<>();
        JsonObject owner=new JsonObject();owner.addProperty("uuid",ANCHOR);entries.put("desired/instance.json",owner.toString().getBytes(StandardCharsets.UTF_8));
        for(String kind:StorageConfigRestorePlan.ROW_KEYS.keySet())entries.put("desired/"+kind+".json",(resources.has(kind)?resources.get(kind):new JsonArray()).toString().getBytes(StandardCharsets.UTF_8));
        JsonArray data=new JsonArray();for(int n=0;n<4;n++){JsonObject row=new JsonObject();row.addProperty("uuid",SOURCE_DATA[n]);row.addProperty("type","DATADISK");row.addProperty("state","Ready");row.addProperty("size",n==0?20L<<30:2L<<30);data.add(row);}
        entries.put("desired/volumes.json",data.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject metadata=new JsonObject();metadata.addProperty("desiredRevision",14);metadata.addProperty("sourceInstanceUuid",ANCHOR);
        JsonObject compatibility=new JsonObject();compatibility.addProperty("minimumManagerVersion","4.23.0");compatibility.addProperty("maximumManagerVersionExclusive","4.24.0");
        metadata.add("configurationCompatibility",compatibility);
        return StorageConfigArchive.create(entries,metadata);
    }
    Process startNativeTransport(String transport) throws Exception {
        ProcessBuilder child=new ProcessBuilder("/usr/bin/python3","-I","-B",
            repo.resolve("server/src/test/resources/storage-config/native-test-sandbox.py").toString(),
            "--repository-root",repo.toString(),"--owned-test-root",root.toString(),"--transport",transport);
        child.directory(root.toFile());
        child.environment().clear();
        child.environment().put("PATH","/usr/bin:/bin");
        return child.redirectError(root.resolve("native-safe-stderr.txt").toFile()).start();
    }
    JsonObject nativeRpcObject(String output,int exit) {
        String context="Native test RPC reply (exit="+exit+", stderr="+root.resolve("native-safe-stderr.txt")+")";
        JsonElement parsed;
        try { parsed=JsonParser.parseString(output); }
        catch(RuntimeException invalid) { throw new IllegalStateException(context+" is invalid JSON: "+invalid.getClass().getSimpleName()); }
        if(!parsed.isJsonObject())throw new IllegalStateException(context+" must be a JSON object");
        JsonObject observed=parsed.getAsJsonObject();
        if(exit!=0) {
            JsonElement success=observed.get("success");
            if(success==null||!success.isJsonPrimitive()||!success.getAsJsonPrimitive().isBoolean()||success.getAsBoolean())
                throw new IllegalStateException(context+" requires structured success=false for a nonzero exit");
        }
        return observed;
    }
    JsonObject generation(String operation,JsonObject request) throws Exception {
        Process child=startNativeTransport("baseline");
        JsonObject message=new JsonObject();message.addProperty("operation",operation);message.add("request",request==null?JsonNull.INSTANCE:request);
        try(var out=child.getOutputStream()){out.write((message.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        byte[] bytes=child.getInputStream().readAllBytes();int exit=child.waitFor();
        return nativeRpcObject(new String(bytes,StandardCharsets.UTF_8),exit);
    }
    JsonObject guest(String command,String body) throws Exception {
        commands.add(command);
        JsonObject call=new JsonObject();call.addProperty("command",command);JsonArray callers=new JsonArray();
        for(StackTraceElement f:Thread.currentThread().getStackTrace())if(f.getClassName().startsWith("org.apache.cloudstack.storage.dataservice.Storage"))
            callers.add(f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber());
        call.add("callers",callers);commandCallers.add(call);
        if(command.startsWith("operation generation ") && !command.equals("operation generation render-status"))
            return generation(command,body==null||body.isBlank()?null:obj(body));
        if(command.equals("operation generation render-status")){
            JsonObject x=new JsonObject();x.addProperty("success",true);x.addProperty("renderedGenerationSupported",true);
            x.addProperty("renderedGenerationSchemaVersion",1);x.addProperty("localSourceIdentityCheckpointSupported",true);
            JsonArray f=new JsonArray();f.add("RENDERED_CONFIG_GENERATION_HANDLER");x.add("supportedFeatures",f);return x;
        }
        if(command.equals("volume operation status")){
            JsonObject x=new JsonObject();x.addProperty("success",true);x.addProperty("status","NOT_STARTED");
            x.addProperty("formatterActive",false);x.addProperty("formatterObservationComplete",true);
            x.addProperty("terminationPending",false);return x;
        }
        if(command.equals("volume operation capabilities"))return obj("{\"success\":true,\"cloneInitialBaselineV1Supported\":true,\"expectedFilesystemUuidGuardSupported\":true}");
        if(command.equals("operation root-data inspect"))return rootData(obj(body));
        if(command.equals("operation observe")||command.equals("inventory"))return fresh(obj("{\"status\":\"ok\"}"));
        if(command.equals("operation verify")||command.equals("health"))return fresh(obj("{\"status\":\"ok\"}"));
        if(command.equals("volume attach inspect"))return fresh(rootDataRow(obj(body).get("volumeUuid").getAsString(),"UNUSED"));
        if(command.equals("operation preflight"))return obj("{\"success\":true}");
        throw new AssertionError("Unplanned OS effect: "+command);
    }
    JsonObject fresh(JsonObject value){value.addProperty("success",true);value.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);value.addProperty("bootId",boot);return value;}
    String boot;
    JsonObject rootData(JsonObject request){JsonObject value=fresh(new JsonObject());JsonArray data=new JsonArray();
        for(JsonElement e:request.getAsJsonArray("volumes")){JsonObject row=e.getAsJsonObject();data.add(rootDataRow(row.get("volumeUuid").getAsString(),row.get("kind").getAsString()));}
        value.add("volumes",data);return value;}
    JsonObject rootDataRow(String uuid,String kind){
        VolumeVO volume=volumeRows.values().stream().filter(v->uuid.equals(v.getUuid())).findFirst().get();
        int index=new ArrayList<>(volumeRows.keySet()).indexOf(volume.getId())-1;
        JsonObject row=new JsonObject();row.addProperty("volumeUuid",uuid);row.addProperty("sizeBytes",volume.getSize());row.addProperty("kind",kind);
        row.addProperty("mappingStatus","EXACT");row.addProperty("matchedBy","VOLUME_SERIAL");row.addProperty("serial",uuid.replace("-","").substring(0,20));
        row.addProperty("observedDevicePath","/dev/sd"+(char)('b'+index));row.add("wwn",JsonNull.INSTANCE);
        row.add("filesystem",index<2?new JsonPrimitive("ext4"):JsonNull.INSTANCE);row.add("filesystemUuid",index<2?new JsonPrimitive(id(9000+index)):JsonNull.INSTANCE);
        JsonArray mounts=new JsonArray();if(index<2){JsonObject mount=new JsonObject();mount.addProperty("source","/dev/sd"+(char)('b'+index));mount.addProperty("target","/srv/ablestack-storage/volumes/"+uuid);mount.addProperty("fstype","ext4");mount.addProperty("options","rw");mount.addProperty("uuid",id(9000+index));mounts.add(mount);}
        row.add("mounts",mounts);row.addProperty("signatureObservationAvailable",true);row.addProperty("blank",index>=2);return row;
    }
    void writeWitnessFixture()throws Exception{
        boot=Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip();
        JsonObject os=new JsonObject();os.addProperty("vmUuid",id(180));os.addProperty("bootId",boot);os.addProperty("rootVolumeUuid",id(500));os.addProperty("rootSizeBytes",5L<<30);
        JsonArray list=new JsonArray();for(VolumeVO v:volumeRows.values())if(v.getVolumeType()==Volume.Type.DATADISK){JsonObject identity=rootDataRow(v.getUuid(),"UNUSED"),row=new JsonObject();
            row.addProperty("volumeUuid",v.getUuid());row.addProperty("sizeBytes",v.getSize());for(String k:List.of("filesystem","filesystemUuid","mounts"))row.add(k,identity.get(k));list.add(row);}
        os.add("dataVolumes",list);Files.writeString(nativeCase.resolve("witness-os-fixture.json"),os.toString());
    }
    StorageServiceInstanceVO instance(long id,String uuid,Long vm){
        StorageServiceInstanceVO v=new StorageServiceInstanceVO();set(v,"id",id);set(v,"uuid",uuid);set(v,"name",id==80?"retained-data-restore-fixture":"live-anchor");
        set(v,"accountId",2L);set(v,"domainId",1L);set(v,"dataCenterId",1L);v.setVmId(vm);
        v.setState(StorageServiceInstance.State.Running);v.setProvider("StorageVm");v.setCurrentRuntimeBundleId(61L);v.setOperationControlPolicyJson("{}");return v;
    }
    VolumeVO volume(long id,String uuid,Volume.Type type,long size,Long template){
        VolumeVO v=G.fromJson("{}",VolumeVO.class);
        set(v,"id",id);set(v,"uuid",uuid);set(v,"name","existing-data-"+id);set(v,"instanceId",80L);set(v,"accountId",2L);
        set(v,"domainId",1L);set(v,"dataCenterId",1L);set(v,"poolId",9L);set(v,"path",uuid);set(v,"size",size);
        set(v,"volumeType",type);set(v,"state",Volume.State.Ready);set(v,"templateId",template);
        set(v,"format",Storage.ImageFormat.QCOW2);set(v,"diskOfferingId",71L);
        set(v,"provisioningType",Storage.ProvisioningType.SPARSE);set(v,"created",new Date(System.currentTimeMillis()-172800000));return v;
    }
    VMTemplateVO template(){
        VMTemplateVO t=Mockito.mock(VMTemplateVO.class);
        Mockito.when(t.getId()).thenReturn(601L);Mockito.when(t.getUuid()).thenReturn(TEMPLATE);
        Mockito.when(t.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);
        Mockito.when(t.getState()).thenReturn(com.cloud.template.VirtualMachineTemplate.State.Active);
        Mockito.when(t.isDynamicallyScalable()).thenReturn(true);Mockito.when(t.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(t.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        Mockito.when(t.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.amd64);Mockito.when(t.getChecksum()).thenReturn("{SHA-256}"+"a".repeat(64));
        Mockito.when(t.getDetails()).thenReturn(Map.of("fixture","genuine-system-boundary"));return t;
    }
    final Map<String,String> oldProperties=new LinkedHashMap<>();
    @Before public void setup() throws Exception {
        existingInitial=!String.valueOf(testName.getMethodName()).startsWith("newInitial");
        for(String name:List.of("cloudstack.storage.config.path","cloudstack.storage.identity.path","cloudstack.storage.rendered.validation.path","cloudstack.storage.rendered.keys.path"))oldProperties.put(name,System.getProperty(name));
        closeables.add(Mockito.mockStatic(com.cloud.utils.component.ComponentContext.class)); // Standalone DI boundary only; commands retain real parameter binding.
        Files.createDirectories(root);Files.createDirectories(nativeCase);Files.setPosixFilePermissions(nativeCase,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));Files.createDirectories(nativeCase.resolve("configuration"));
        Files.writeString(nativeCase.resolve("configuration/sharedfs-network.json"),
            "{\"schemaVersion\":1,\"mode\":\"STATIC\",\"interface\":\"eth0\",\"address\":\"10.10.13.226/16\"}");
        User actor=Mockito.mock(User.class);Mockito.when(actor.getId()).thenReturn(2L);
        Account account=Mockito.mock(Account.class);Mockito.when(account.getId()).thenReturn(2L);
        org.apache.cloudstack.context.CallContext.register(actor,account);
        var depot=Mockito.mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);
        Mockito.when(depot.getConfigStringValue(Mockito.anyString(),Mockito.any(),Mockito.any())).thenAnswer(c->
            c.<String>getArgument(0).equals("storage.service.verified.configuration.enabled")?"true":null);
        org.apache.cloudstack.framework.config.ConfigKey.init(depot);
        DbProperties.getDbProperties().setProperty("db.cloud.encrypt.secret","fixture-only-encryption-secret");
        com.cloud.utils.crypt.EncryptionSecretKeyChecker.initEncryptor("fixture-only-encryption-secret");
        renderedStore=root.resolve("rendered-artifacts");Files.createDirectories(renderedStore);
        Files.setPosixFilePermissions(renderedStore,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        System.setProperty("cloudstack.storage.rendered.validation.path",renderedStore.toString());
        System.setProperty("cloudstack.storage.identity.path",root.resolve("identity").toString());
        System.setProperty("cloudstack.storage.config.path",root.resolve("config-store").toString());
        System.setProperty("cloudstack.storage.rendered.keys.path",root.resolve("rendered-keys").toString());
        instanceRows.put(7L,instance(7,ANCHOR,null));instanceRows.put(80L,instance(80,TARGET,80L));
        for(long protectedId=8;protectedId<=13;protectedId++)instanceRows.put(protectedId,instance(protectedId,id((int)protectedId),null));
        instances=Mockito.mock(StorageServiceInstanceDao.class);
        Mockito.when(instances.findById(Mockito.anyLong())).thenAnswer(c->copy(instanceRows.get(c.<Long>getArgument(0)),StorageServiceInstanceVO.class));
        Mockito.when(instances.findByUuid(Mockito.anyString())).thenAnswer(c->instanceRows.values().stream().filter(v->v.getUuid().equals(c.getArgument(0))).findFirst().map(v->copy(v,StorageServiceInstanceVO.class)).orElse(null));
        Mockito.when(instances.findByVmId(80L)).thenAnswer(c->copy(instanceRows.get(80L),StorageServiceInstanceVO.class));
        Mockito.when(instances.listAll()).thenAnswer(c->instanceRows.values().stream().map(v->copy(v,StorageServiceInstanceVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(instances.lockRow(Mockito.anyLong(),Mockito.eq(true))).thenAnswer(c->copy(instanceRows.get(c.<Long>getArgument(0)),StorageServiceInstanceVO.class));
        Mockito.when(instances.update(Mockito.anyLong(),Mockito.any())).thenAnswer(c->{instanceRows.put(c.getArgument(0),copy(c.getArgument(1),StorageServiceInstanceVO.class));events.add("instance-persist");return true;});
        set(manager,"storageServiceInstanceDao",instances);
        AccountManager accounts=Mockito.mock(AccountManager.class);Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);
        set(manager,"storageAccountManager",accounts);set(manager,"configurationResourceManager",Mockito.mock(com.cloud.configuration.ConfigurationManager.class));
        VolumeDao volumes=Mockito.mock(VolumeDao.class);set(manager,"volumeDao",volumes);
        com.cloud.storage.VolumeApiService noAllocation=Mockito.mock(com.cloud.storage.VolumeApiService.class,invocation->{
            if(invocation.getMethod().getDeclaringClass()==Object.class)return "forbidden-volume-effect-boundary";
            if(invocation.getMethod().getName().equals("doesStoragePoolSupportDiskOffering"))return true;
            throw new AssertionError("Forbidden DATA allocation/provider call: "+invocation.getMethod().getName());
        });set(manager,"volumeApiService",noAllocation);
        byte[] zip=publicArchive();
        Map<String,byte[]> archive=StorageConfigArchive.validate(zip);
        JsonArray src=StorageConfigArchive.json(archive.get("desired/volumes.json")).getAsJsonArray();
        String initialSource=SOURCE_DATA[0];
        volumeRows.put(500L,volume(500,id(500),Volume.Type.ROOT,5L<<30,601L));
        long n=501;for(JsonElement e:src){JsonObject v=e.getAsJsonObject();String sourceId=v.get("uuid").getAsString(), targetId=existingInitial||!sourceId.equals(initialSource)?sourceId:id((int)n+1000);sourceToTarget.put(sourceId,targetId);
            VolumeVO data=volume(n,targetId,Volume.Type.DATADISK,v.get("size").getAsLong(),null);
            if(!existingInitial&&sourceId.equals(initialSource))set(data,"created",new Date());volumeRows.put(n,data);n++;}
        initialUuid=sourceToTarget.get(initialSource);
        Assert.assertEquals(5,volumeRows.size());
        Mockito.when(volumes.findByInstanceAndType(80L,Volume.Type.ROOT)).thenAnswer(c->volumeRows.values().stream().filter(v->v.getVolumeType()==Volume.Type.ROOT).map(v->copy(v,VolumeVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(volumes.findByInstanceAndType(80L,Volume.Type.DATADISK)).thenAnswer(c->volumeRows.values().stream().filter(v->v.getVolumeType()==Volume.Type.DATADISK&&(!creatorStageInitialOnly||v.getUuid().equals(initialUuid))).map(v->copy(v,VolumeVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(volumes.findByUuid(Mockito.anyString())).thenAnswer(c->volumeRows.values().stream().filter(v->v.getUuid().equals(c.getArgument(0))).findFirst().map(v->copy(v,VolumeVO.class)).orElse(null));
        Mockito.when(volumes.findById(Mockito.anyLong())).thenAnswer(c->copy(volumeRows.get(c.<Long>getArgument(0)),VolumeVO.class));
        VMTemplateDao templates=Mockito.mock(VMTemplateDao.class);VMTemplateVO template=template();
        Mockito.when(templates.findByUuid(TEMPLATE)).thenReturn(template);set(manager,"rootUpgradeTemplateDao",templates);
        UserVmDao vms=Mockito.mock(UserVmDao.class);UserVmVO vm=Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(80L);Mockito.when(vm.getUuid()).thenReturn(id(180));
        Mockito.when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vm.getDomainId()).thenReturn(1L);Mockito.when(vm.getTemplateId()).thenReturn(601L);Mockito.when(vm.getAccountId()).thenReturn(2L);Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vms.findById(80L)).thenReturn(vm);set(manager,"rootUpgradeVmDao",vms);VMInstanceDao allVms=Mockito.mock(VMInstanceDao.class);Mockito.when(allVms.findById(80L)).thenReturn(vm);set(manager,"vmInstanceDao",allVms);
        SharedFSDao shared=Mockito.mock(SharedFSDao.class);SharedFSVO fs=Mockito.mock(SharedFSVO.class);
        Mockito.when(fs.getId()).thenReturn(580L);Mockito.when(fs.getUuid()).thenReturn(id(580));Mockito.when(fs.getDomainId()).thenReturn(1L);
        Mockito.when(fs.getName()).thenReturn("retained-data-restore-fixture");Mockito.when(fs.getServiceOfferingId()).thenReturn(51L);
        Mockito.when(fs.getBackingVolumeMode()).thenReturn(existingInitial?SharedFS.BackingVolumeMode.EXISTING:SharedFS.BackingVolumeMode.NEW);Mockito.when(fs.getFsType()).thenReturn(SharedFS.FileSystemType.EXT4);
        Mockito.when(fs.getFsProviderName()).thenReturn("StorageVm");Mockito.when(fs.getVmId()).thenReturn(80L);Mockito.when(fs.getAccountId()).thenReturn(2L);Mockito.when(fs.getDataCenterId()).thenReturn(1L);
        long initialId=volumeRows.values().stream().filter(v->v.getUuid().equals(initialUuid)).findFirst().get().getId();
        Mockito.when(shared.createSearchCriteria()).thenReturn(Mockito.mock(SearchCriteria.class));
        Mockito.when(fs.getVolumeId()).thenReturn(initialId);Mockito.when(shared.findByVm(80L)).thenReturn(fs);set(manager,"sharedFSDao",shared);
        SharedFSService sharedService=Mockito.mock(SharedFSService.class);Mockito.when(sharedService.getSharedFSByUuid(id(580))).thenReturn(fs);set(manager,"configurationSharedFsService",sharedService);
        com.cloud.vm.dao.NicDao nics=Mockito.mock(com.cloud.vm.dao.NicDao.class);NicVO nic=Mockito.mock(NicVO.class);
        Mockito.when(nic.getId()).thenReturn(32L);Mockito.when(nic.getUuid()).thenReturn(id(32));Mockito.when(nic.getNetworkId()).thenReturn(20L);
        Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nic.getMacAddress()).thenReturn("02:00:00:00:00:80");
        Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.226");Mockito.when(nics.listByVmId(80L)).thenReturn(List.of(nic));set(manager,"nicDao",nics);
        com.cloud.network.dao.NetworkDao networks=Mockito.mock(com.cloud.network.dao.NetworkDao.class);com.cloud.network.dao.NetworkVO network=Mockito.mock(com.cloud.network.dao.NetworkVO.class);
        Mockito.when(network.getId()).thenReturn(20L);Mockito.when(network.getUuid()).thenReturn(id(20));Mockito.when(network.getGuruName()).thenReturn("DirectNetworkGuru");
        Mockito.when(network.getCidr()).thenReturn("10.10.0.0/16");Mockito.when(network.getGateway()).thenReturn("10.10.0.1");
        Mockito.when(networks.findById(20L)).thenReturn(network);Mockito.when(networks.findByUuid(id(20))).thenReturn(network);set(manager,"networkDao",networks);
        DataCenterDao zones=Mockito.mock(DataCenterDao.class);DataCenterVO zone=Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(1L);Mockito.when(zone.getUuid()).thenReturn(ZONE);
        Mockito.when(zones.findByUuid(ZONE)).thenReturn(zone);Mockito.when(zones.findById(1L)).thenReturn(zone);set(manager,"dataCenterDao",zones);
        PrimaryDataStoreDao pools=Mockito.mock(PrimaryDataStoreDao.class);StoragePoolVO pool=Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(9L);Mockito.when(pool.getDataCenterId()).thenReturn(1L);Mockito.when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        Mockito.when(pool.isShared()).thenReturn(true);Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);Mockito.when(pool.getUuid()).thenReturn(POOL);Mockito.when(pools.findById(9L)).thenReturn(pool);Mockito.when(pools.findByUuid(POOL)).thenReturn(pool);set(manager,"configurationStoragePoolDao",pools);
        DiskOfferingDao offerings=Mockito.mock(DiskOfferingDao.class);DiskOfferingVO offering=Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getId()).thenReturn(71L);Mockito.when(offerings.findById(71L)).thenReturn(offering);set(manager,"configurationDiskOfferingDao",offerings);
        set(manager,"configurationProjectDao",Mockito.mock(ProjectDao.class));
        StorageServiceRuntimeBundleDao bundles=Mockito.mock(StorageServiceRuntimeBundleDao.class);
        StorageConfigSignedRuntimeFixture signed=new StorageConfigSignedRuntimeFixture(repo,root,61L,BUNDLE);runtimes=signed;
        Mockito.when(bundles.findByUuid(BUNDLE)).thenAnswer(c->copy(signed.bundle,StorageServiceRuntimeBundleVO.class));
        Mockito.when(bundles.findById(61L)).thenAnswer(c->copy(signed.bundle,StorageServiceRuntimeBundleVO.class));
        set(manager,"storageRuntimeBundleDao",bundles);set(manager,"runtimeUpgradeManager",runtimes);
        set(signed,"bundleDao",bundles);set(signed,"instanceDao",instances);set(signed,"runtimeVolumeDao",volumes);set(signed,"vmInstanceDao",allVms);
        instanceRows.get(80L).setRuntimeVerifiedAt(new Date());
        Mockito.when(vm.getHostId()).thenReturn(3L);
        com.cloud.host.dao.HostDao hosts=Mockito.mock(com.cloud.host.dao.HostDao.class);com.cloud.host.HostVO host=Mockito.mock(com.cloud.host.HostVO.class);
        Mockito.when(host.getId()).thenReturn(3L);Mockito.when(host.getUuid()).thenReturn(id(3));Mockito.when(host.getVersion()).thenReturn("4.23.0.0");
        Mockito.when(hosts.findById(3L)).thenReturn(host);set(signed,"runtimeHostDao",hosts);
        StorageServiceRuntimeUpgradeDao installedUpgrades=Mockito.mock(StorageServiceRuntimeUpgradeDao.class);
        StorageServiceRuntimeUpgradeVO installed=G.fromJson("{}",StorageServiceRuntimeUpgradeVO.class);
        set(installed,"instanceId",80L);set(installed,"bundleId",61L);installed.setState(StorageServiceRuntimeUpgradeVO.State.COMPLETE);
        installed.setCompleted(new Date());set(installed,"transactionId","fixture-installed-transaction");
        Mockito.when(installedUpgrades.listByInstanceId(80L)).thenAnswer(c->List.of(copy(installed,StorageServiceRuntimeUpgradeVO.class)));
        set(signed,"upgradeDao",installedUpgrades);
        runtimePin=manager.configurationCloneRuntimePin(BUNDLE);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(c->{StorageServiceGuestCommand cmd=c.getArgument(0);JsonObject observed=guest(cmd.getOperation(),cmd.getPayload());return new StorageServiceGuestCommandResult(observed.get("success").getAsBoolean(),"fixture-OS-boundary",observed.toString());});
        set(manager,"guestCommandDispatcher",guest);
        set(manager,"storageIdentityDomainDao",Mockito.mock(StorageIdentityDomainDao.class));
        set(manager,"storageTemplateUpgradeDao",Mockito.mock(StorageServiceTemplateUpgradeDao.class));
        set(manager,"storageRuntimeUpgradeDao",Mockito.mock(StorageServiceRuntimeUpgradeDao.class));
        set(manager,"storageOperationControlDao",Mockito.mock(StorageServiceOperationControlDao.class));
        set(manager,"configurationVolumeDetailsDao",Mockito.mock(VolumeDetailsDao.class));
        operations=Mockito.mock(StorageServiceOperationDao.class);set(manager,"storageOperationDao",operations);
        Mockito.when(operations.readAdmission(Mockito.anyLong())).thenAnswer(c->{long instance=c.getArgument(0);
            long revision=operationRows.values().stream().filter(v->v.getInstanceId()==instance&&"COMPLETE".equals(v.getState())).mapToLong(StorageServiceOperationVO::getRevision).max().orElse(0);
            long running=operationRows.values().stream().filter(v->v.getInstanceId()==instance&&"RUNNING".equals(v.getState())).count();
            Long recovery=operationRows.values().stream().filter(v->v.getInstanceId()==instance&&"RECOVERY_REQUIRED".equals(v.getState())).map(StorageServiceOperationVO::getRevision).max(Long::compare).orElse(null);
            return new StorageServiceOperationDao.Admission(revision,running,recovery);});
        Mockito.when(operations.findByRequest(Mockito.anyLong(),Mockito.anyString())).thenAnswer(c->operationRows.values().stream().filter(o->o.getInstanceId()==c.<Long>getArgument(0)&&o.getRequestKey().equals(c.getArgument(1))).findFirst().map(o->copy(o,StorageServiceOperationVO.class)).orElse(null));
        Mockito.when(operations.persist(Mockito.any())).thenAnswer(c->{StorageServiceOperationVO o=c.getArgument(0);set(o,"id",nextOperation.incrementAndGet());operationRows.put(o.getId(),copy(o,StorageServiceOperationVO.class));events.add("operation-create:"+o.getAction());return o;});
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenAnswer(c->{operationRows.put(c.getArgument(0),copy(c.getArgument(1),StorageServiceOperationVO.class));events.add("operation-state:"+c.<StorageServiceOperationVO>getArgument(1).getPhase());return true;});
        Mockito.when(operations.findById(Mockito.anyLong())).thenAnswer(c->copy(operationRows.get(c.<Long>getArgument(0)),StorageServiceOperationVO.class));
        Mockito.when(operations.findByUuid(Mockito.anyString())).thenAnswer(c->operationRows.values().stream().filter(o->o.getUuid().equals(c.getArgument(0))).findFirst().map(o->copy(o,StorageServiceOperationVO.class)).orElse(null));
        artifacts=Mockito.mock(StorageConfigArtifactDao.class);set(manager,"storageConfigArtifactDao",artifacts);
        Mockito.when(artifacts.findById(900L)).thenAnswer(c->copy(artifactRows.get(900L),StorageConfigArtifactVO.class));
        Mockito.when(artifacts.lockRow(900L,true)).thenAnswer(c->copy(artifactRows.get(900L),StorageConfigArtifactVO.class));
        Mockito.when(artifacts.update(Mockito.eq(900L),Mockito.any())).thenAnswer(c->{artifactRows.put(900L,copy(c.getArgument(1),StorageConfigArtifactVO.class));events.add("artifact:"+obj(c.<StorageConfigArtifactVO>getArgument(1).getMetadataJson()).get("restoreState"));return true;});
        Mockito.when(artifacts.findByUuid(Mockito.anyString())).thenAnswer(c->artifactRows.values().stream().filter(v->v.getUuid().equals(c.getArgument(0))).findFirst().map(v->copy(v,StorageConfigArtifactVO.class)).orElse(null));
        Mockito.when(artifacts.findById(Mockito.anyLong())).thenAnswer(c->copy(artifactRows.get(c.<Long>getArgument(0)),StorageConfigArtifactVO.class));
        Mockito.when(artifacts.listByInstance(Mockito.anyLong())).thenAnswer(c->artifactRows.values().stream().filter(v->v.getInstanceId()==c.<Long>getArgument(0)).map(v->copy(v,StorageConfigArtifactVO.class)).collect(java.util.stream.Collectors.toList()));
        Mockito.when(artifacts.persist(Mockito.any())).thenAnswer(c->{StorageConfigArtifactVO v=c.getArgument(0);set(v,"id",1000L+artifactRows.size());artifactRows.put(v.getId(),copy(v,StorageConfigArtifactVO.class));return v;});
        Mockito.when(artifacts.promoteVerified(Mockito.anyLong(),Mockito.anyLong(),Mockito.nullable(Long.class),Mockito.anyLong(),Mockito.any())).thenAnswer(c->{
            long instance=c.getArgument(0),candidate=c.getArgument(1);
            artifactRows.values().stream().filter(v->v.getInstanceId()==instance&&"ACTIVE_LKG".equals(v.getState())).forEach(v->v.setState("SUPERSEDED"));
            artifactRows.get(candidate).setState("ACTIVE_LKG");c.<Runnable>getArgument(4).run();return true;});
        GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
        MockedStatic<GlobalLock> locks=Mockito.mockStatic(GlobalLock.class);locks.when(()->GlobalLock.getInternLock(Mockito.anyString())).thenReturn(lock);closeables.add(locks);
        MockedStatic<Transaction> tx=Mockito.mockStatic(Transaction.class);
        tx.when(()->Transaction.execute(Mockito.<TransactionCallback<Void>>any())).thenAnswer(c->c.<TransactionCallback<Void>>getArgument(0).doInTransaction(null));closeables.add(tx);
        MockedConstruction<StorageServiceDesiredSnapshot> snapshots=Mockito.mockConstruction(StorageServiceDesiredSnapshot.class,(mock,ctx)->
            Mockito.when(mock.capture(Mockito.anyLong())).thenAnswer(c->snapshot(c.getArgument(0))));closeables.add(snapshots);
        store=new StorageConfigArtifactStore(root.resolve("config-store"));store.write(ARTIFACT,zip);archiveSha=StorageConfigArchive.sha256(zip);
        StorageConfigArtifactVO artifact=new StorageConfigArtifactVO();set(artifact,"id",900L);set(artifact,"uuid",ARTIFACT);artifact.setInstanceId(7);artifact.setKind("IMPORT");
        artifact.setState("ARCHIVE_VALIDATED");artifact.setSha256(archiveSha);artifact.setSize(zip.length);artifact.setCreatedBy(2);artifact.setExpires(new Date(System.currentTimeMillis()+3600000));
        JsonObject blueprint=obj("{\"name\":\"retained-data-restore-fixture\",\"zoneid\":\""+ZONE+"\",\"templateid\":\""+TEMPLATE+"\",\"filesystem\":\"EXT4\",\"backingvolumemode\":\"EXISTING\",\"existingvolumeid\":\""+initialUuid+"\"}");
        if(!existingInitial){blueprint.addProperty("backingvolumemode","NEW");blueprint.remove("existingvolumeid");blueprint.addProperty("size",20);}
        blueprint.addProperty("storageid",POOL);blueprint.addProperty("networkid",id(20));blueprint.addProperty("networkmode","STATIC");blueprint.addProperty("ipcidr","10.10.13.226/16");blueprint.addProperty("gateway","10.10.0.1");
        JsonObject pin=obj("{\"schemaVersion\":1,\"selectionMode\":\"EXPLICIT_SYSTEM\",\"templateUuid\":\""+TEMPLATE+"\",\"templateChecksum\":\"{SHA-256}"+"a".repeat(64)+"\",\"templateDetailsSha256\":\""+StorageTemplateFixturePermit.detailsSha256(template)+"\",\"zoneUuid\":\""+ZONE+"\",\"hypervisor\":\"KVM\",\"architecture\":\"x86_64\"}");
        // Use the actual template architecture spelling without assuming the public enum representation.
        pin.addProperty("architecture",template.getArch().getType());
        plan=obj("{\"targetMode\":\"CREATE_NEW\",\"targetName\":\"retained-data-restore-fixture\",\"targetInstanceUuid\":\""+TARGET+"\",\"expectedRevision\":0,\"artifactSha256\":\""+archiveSha+"\",\"runtimeBundleUuid\":\""+BUNDLE+"\",\"requiredCredentials\":[]}");
        Map<String,byte[]> targetArchive=new LinkedHashMap<>(archive);
        for(String kind:StorageConfigRestorePlan.ROW_KEYS.keySet())targetArchive.put("desired/"+kind+".json","[]".getBytes(StandardCharsets.UTF_8));
        JsonObject targetOwner=new JsonObject();targetOwner.addProperty("uuid",TARGET);
        targetArchive.put("desired/instance.json",targetOwner.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray targetData=new JsonArray();for(VolumeVO disk:volumeRows.values())if(disk.getVolumeType()==Volume.Type.DATADISK){
            JsonObject row=new JsonObject();row.addProperty("uuid",disk.getUuid());row.addProperty("type","DATADISK");row.addProperty("state","Ready");row.addProperty("size",disk.getSize());targetData.add(row);
        }
        targetArchive.put("desired/volumes.json",targetData.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject resourceMapping=new JsonObject(),listenerMapping=new JsonObject(),diskMapping=new JsonObject();
        for(Map.Entry<String,String> value:sourceToTarget.entrySet())diskMapping.addProperty(value.getKey(),value.getValue());
        resourceMapping.add("createNew",blueprint.deepCopy());resourceMapping.add("volumes",diskMapping);listenerMapping.addProperty("10.10.13.223","PRIMARY");resourceMapping.add("listenerAddresses",listenerMapping);
        plan=StorageConfigRestorePlan.build(archive,targetArchive,resourceMapping,"CREATE_NEW",TARGET,0,"10.10.13.226");
        plan.addProperty("targetName","retained-data-restore-fixture");plan.addProperty("artifactSha256",archiveSha);plan.addProperty("runtimeBundleUuid",BUNDLE);
        Assert.assertEquals(16,plan.getAsJsonArray("create").size());Assert.assertEquals(plan.getAsJsonArray("blockers").toString(),0,plan.getAsJsonArray("blockers").size());
        new StorageConfigDomainRestore(manager).validateBindings(plan);plan.add("createNew",blueprint);plan.add("cloneTemplatePin",pin);plan.add("cloneRuntimePin",runtimePin);
        allocation=new JsonObject();JsonObject scope=obj("{\"targetInstanceUuid\":\""+TARGET+"\",\"accountId\":2,\"domainId\":1,\"projectId\":null,\"zoneUuid\":\""+ZONE+"\",\"baselineRevision\":0}");
        scope.addProperty("artifactUuid",ARTIFACT);scope.addProperty("artifactSha256",archiveSha);scope.addProperty("allocationNamespace",id(908));
        allocation.add("scope",scope);JsonArray aa=new JsonArray();JsonObject mapping=new JsonObject();
        for(VolumeVO v:volumeRows.values())if(v.getVolumeType()==Volume.Type.DATADISK){
            String sourceId=sourceToTarget.entrySet().stream().filter(e->e.getValue().equals(v.getUuid())).findFirst().get().getKey();
            JsonObject a=obj("{\"sourceUuid\":\""+v.getUuid()+"\",\"plannedUuid\":\""+v.getUuid()+"\",\"mode\":\"EXISTING\",\"initial\":"+v.getUuid().equals(initialUuid)+",\"poolUuid\":\""+POOL+"\",\"sizeBytes\":"+v.getSize()+",\"usage\":\"FILE\"}");
            a.addProperty("sourceUuid",sourceId);a.addProperty("mode",existingInitial||!v.getUuid().equals(initialUuid)?"EXISTING":"NEW");a.addProperty("provisioningType","SPARSE");
            a.addProperty("usage",v.getSize()==(20L<<30)||sourceId.equals(SOURCE_DATA[1])?"FILE":"BLOCK_RAW");
            if("FILE".equals(a.get("usage").getAsString()))a.addProperty("filesystem","EXT4");
            for(String field:scope.keySet())a.add(field,scope.get(field).deepCopy());aa.add(a);mapping.addProperty(sourceId,v.getUuid());
        }
        allocation.add("allocations",aa);allocation.add("volumeMappings",mapping);allocation.addProperty("planSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(allocation)));
        plan.add("volumeAllocationPlan",allocation);plan.add("volumeMappings",mapping);
        metadata=new JsonObject();metadata.add("plan",plan);metadata.addProperty("createdTargetInstanceUuid",TARGET);metadata.addProperty("runtimePreparedBundleUuid",BUNDLE);
        metadata.add("createdTargetTemplateBinding",manager.configurationCloneCreatedTemplateBinding(instanceRows.get(80L),pin));
        artifact.setMetadataJson(metadata.toString());artifactRows.put(900L,artifact);
        JsonObject execution=manager.bindConfigurationVolumeExecution(instanceRows.get(80L),artifact,allocation);metadata.add("volumeExecutionPlan",execution);
        // The creator receipt is produced from its legitimate ROOT+initial-DATA phase.
        metadata.addProperty("allocationNamespace",id(908));metadata.addProperty("plannedTargetInstanceUuid",TARGET);artifact.setMetadataJson(metadata.toString());
        JsonObject creator=new JsonObject();creator.addProperty("schemaVersion",1);creator.addProperty("kind","CONFIGURATION_CLONE_ALLOCATION");
        creator.add("scope",manager.configurationCloneAllocationScope(artifact,plan));
        creator.add("sharedFs",manager.configurationCloneSharedFsBinding(fs));
        if(existingInitial)creator.add("existingData",manager.configurationCloneExistingDataBinding(fs,plan));
        creatorStageInitialOnly=true;
        creator.add("vm",manager.configurationCloneVmBinding(fs,plan));
        creatorStageInitialOnly=false; // The successful foundation then attached all four reviewed DATA.
        metadata.add("cloneAllocation",creator);
        JsonObject observed=new JsonObject();observed.addProperty("volumeUuid",initialUuid);observed.addProperty("filesystem","EXT4");observed.addProperty("filesystemUuid",id(9000));metadata.add("cloneExistingFilesystem",observed);

        creatorStageInitialOnly=true;artifact.setMetadataJson(metadata.toString());artifactRows.put(900L,artifact);
        manager.requireConfigurationCloneAllocation(artifact,plan,creator);
        creatorStageInitialOnly=false;metadata=obj(artifact.getMetadataJson());
        JsonObject capability=obj("{\"hash\":\""+sha("fixture-review-token")+"\",\"user\":2,\"expires\":"+(System.currentTimeMillis()+60000)+",\"planSha256\":\""+sha(plan.toString())+"\",\"baselineSha256\":\""+sha(snapshot(7))+"\"}");
        metadata.addProperty("planState","PLANNED");metadata.add("planToken",capability);artifact.setMetadataJson(metadata.toString());artifactRows.put(900L,artifact);
        foundation=new StorageServiceOperationVO();set(foundation,"id",90L);foundation.setInstanceId(80);foundation.setAction("CLONE_FOUNDATION_PREPARE");
        foundation.setRequestKey("CLONE_FOUNDATION:"+ARTIFACT);foundation.setRevision(1);foundation.setCreatedBy(2);foundation.setState("COMPLETE_NO_CONFIG_CHANGE");foundation.setPhase("FOUNDATION_PREPARED");
        writeWitnessFixture();
        JsonObject fi=new JsonObject();fi.addProperty("artifactUuid",ARTIFACT);fi.addProperty("artifactSha256",archiveSha);fi.addProperty("targetInstanceUuid",TARGET);
        fi.addProperty("reviewedPlanSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(plan)));
        fi.add("volumeAllocationPlan",execution.deepCopy());fi.add("nativeSource",manager.configurationFoundationNativeSource(instanceRows.get(80L),foundation));
        foundation.setPreviousSnapshotJson(fi.toString());operationRows.put(90L,foundation);
        for(java.lang.reflect.Field field:StorageServiceManagerImpl.class.getDeclaredFields())
            if(field.getType().isInterface()&&field.getType().getSimpleName().endsWith("Dao")){field.setAccessible(true);if(field.get(manager)==null)field.set(manager,Mockito.mock(field.getType()));}
        ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).set(foundation);
        try{manager.prepareConfigurationVolumeAllocations(instanceRows.get(80L),artifact,execution);}
        finally{((ThreadLocal<?>)ReflectionTestUtils.getField(manager,"storageWriterOperation")).remove();}
        artifact.setMetadataJson(artifactRows.get(900L).getMetadataJson());

        configuration=new StorageServiceConfiguration(manager,artifacts,operations,store);
    }
    @Test public void completedFoundationBecomesVerifiedNativeBaselineBeforeTheProfile()throws Exception {completeBaselineAndProfile();}
    @Test public void newInitialAndRetainedAdditionalDataUseTheSameNormalInitialGenerationChain()throws Exception {completeBaselineAndProfile();}
    private void completeBaselineAndProfile()throws Exception{
        JsonObject historical=obj(artifactRows.get(900L).getMetadataJson()).getAsJsonObject("cloneAllocation").deepCopy();
        JsonObject execution=plan.deepCopy();execution.add("volumeAllocationPlan",obj(artifactRows.get(900L).getMetadataJson()).get("volumeExecutionPlan"));
        StorageServiceInstanceVO target=manager.prepareConfigurationCloneInitialBaseline(copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),execution);
        JsonObject after=generation("operation generation status",null);
        Assert.assertEquals("IN_SYNC",after.get("generationStatus").getAsString());Assert.assertEquals(1,after.get("runtimeRevision").getAsLong());Assert.assertTrue(after.get("pendingOperationUuid").isJsonNull());
        JsonObject receipt=obj(artifactRows.get(900L).getMetadataJson()).getAsJsonObject("cloneInitialBaseline");
        Assert.assertNotNull(receipt);Assert.assertEquals(historical,obj(artifactRows.get(900L).getMetadataJson()).get("cloneAllocation"));
        Assert.assertEquals(0,receipt.getAsJsonObject("authority").getAsJsonObject("execution").getAsJsonObject("scope").get("baselineRevision").getAsLong());
        Assert.assertFalse(obj(instanceRows.get(80L).getOperationControlPolicyJson()).has("renderedValidationProfile"));
        Assert.assertTrue(operationRows.values().stream().anyMatch(v->v.getAction().equals("verifyStorageServiceConfiguration")&&v.getState().equals("COMPLETE")));
        Assert.assertTrue(artifactRows.values().stream().anyMatch(v->"ACTIVE_LKG".equals(v.getState())&&v.getDesiredRevision()==1));
        manager.authorizeConfigurationCloneProfile(target,copy(artifactRows.get(900L),StorageConfigArtifactVO.class),execution);
        JsonObject policy=obj(instanceRows.get(80L).getOperationControlPolicyJson());Assert.assertTrue(policy.getAsJsonObject("renderedValidationProfile").get("enabled").getAsBoolean());
        // A second resume uses only its same completed normal verifier and immutable baseline.
        manager.prepareConfigurationCloneInitialBaseline(target,copy(artifactRows.get(900L),StorageConfigArtifactVO.class),execution);
        Assert.assertEquals(receipt,obj(artifactRows.get(900L).getMetadataJson()).get("cloneInitialBaseline"));
        Assert.assertEquals(5,volumeRows.size());
        List<String> trace=nativeActions();
        for(String action:List.of("begin","verify","commit","finish"))Assert.assertEquals(action,1,trace.stream().filter(action::equals).count());
        Assert.assertTrue(trace.stream().filter("clone-initial-inspect"::equals).count()>=5);

    }
    JsonObject executionPlan() {
        JsonObject value=plan.deepCopy();value.add("volumeAllocationPlan",obj(artifactRows.get(900L).getMetadataJson()).get("volumeExecutionPlan"));return value;
    }
    List<String> nativeActions()throws Exception{
        List<String> result=new ArrayList<>();for(String row:Files.readAllLines(nativeCase.resolve("native-transport-trace.jsonl")))result.add(obj(row).get("action").getAsString());return result;
    }
    void assertNoBaselineCommit()throws Exception{
        JsonObject status=generation("operation generation status",null);
        Assert.assertEquals(0,status.getAsJsonObject("generation").size());
        Assert.assertFalse(obj(instanceRows.get(80L).getOperationControlPolicyJson()).has("renderedValidationProfile"));
        Assert.assertFalse(obj(artifactRows.get(900L).getMetadataJson()).has("cloneInitialBaseline"));
        Assert.assertFalse(nativeActions().contains("commit"));
    }
    @Test public void orphanKernelTargetFailsBeforeTheOrdinaryBaselineBegins()throws Exception{
        JsonObject os=obj(Files.readString(nativeCase.resolve("witness-os-fixture.json")));os.addProperty("foreignIscsiTarget","iqn.2026-10.example:foreign");
        Files.writeString(nativeCase.resolve("witness-os-fixture.json"),os.toString());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.prepareConfigurationCloneInitialBaseline(
                copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan()));
        Assert.assertEquals(1,operationRows.size());assertNoBaselineCommit();
    }
    @Test public void missingPreparedReceiptCannotBeInferredFromTheAttachedDataCount()throws Exception{
        StorageConfigArtifactVO artifact=artifactRows.get(900L);JsonObject value=obj(artifact.getMetadataJson());value.getAsJsonObject("receipts").remove(sourceToTarget.get(SOURCE_DATA[1]));artifact.setMetadataJson(value.toString());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.prepareConfigurationCloneInitialBaseline(
                copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifact,StorageConfigArtifactVO.class),executionPlan()));
        Assert.assertEquals(1,operationRows.size());assertNoBaselineCommit();
    }
    @Test public void foreignPendingWriterCannotBecomeAnInitialBaseline()throws Exception{
        JsonObject foreign=new JsonObject();foreign.addProperty("instanceUuid",TARGET);foreign.addProperty("operationUuid",id(991));foreign.addProperty("revision",1);
        Assert.assertTrue(generation("operation generation begin",foreign).get("success").getAsBoolean());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.prepareConfigurationCloneInitialBaseline(
                copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan()));
        Assert.assertEquals(id(991),generation("operation generation status",null).get("pendingOperationUuid").getAsString());assertNoBaselineCommit();
    }
    @Test public void completedReceiptCannotAdoptAnotherFilesystemOnResume()throws Exception{
        manager.prepareConfigurationCloneInitialBaseline(copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan());
        JsonObject before=generation("operation generation status",null);
        JsonObject os=obj(Files.readString(nativeCase.resolve("witness-os-fixture.json")));os.getAsJsonArray("dataVolumes").get(1).getAsJsonObject().addProperty("filesystemUuid",id(8888));
        Files.writeString(nativeCase.resolve("witness-os-fixture.json"),os.toString());
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.prepareConfigurationCloneInitialBaseline(
                copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan()));
        Assert.assertEquals(before,generation("operation generation status",null));
        Assert.assertEquals(1,nativeActions().stream().filter("commit"::equals).count());
    }
    @Test public void lostCompletedOperationDoesNotAuthorizeAReplacementGeneration()throws Exception{
        manager.prepareConfigurationCloneInitialBaseline(copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan());
        operationRows.entrySet().removeIf(value->value.getValue().getAction().equals("verifyStorageServiceConfiguration"));
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->manager.prepareConfigurationCloneInitialBaseline(
                copy(instanceRows.get(80L),StorageServiceInstanceVO.class),copy(artifactRows.get(900L),StorageConfigArtifactVO.class),executionPlan()));
        Assert.assertEquals(1,nativeActions().stream().filter("commit"::equals).count());
    }
    @After public void close() throws Exception {
        for(int i=closeables.size()-1;i>=0;i--)closeables.get(i).close();
        org.apache.cloudstack.context.CallContext.unregister();
        for(Map.Entry<String,String> entry:oldProperties.entrySet())if(entry.getValue()==null)System.clearProperty(entry.getKey());else System.setProperty(entry.getKey(),entry.getValue());
    }
}
