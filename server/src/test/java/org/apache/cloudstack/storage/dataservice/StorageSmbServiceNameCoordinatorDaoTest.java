// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbServiceNameCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameContextResponse;
import org.apache.cloudstack.api.response.StorageSmbServiceNameResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceConfigurationOperationDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageSmbServiceNameDaoImpl;

/** Native Samba rollback receipt -> real coordinator -> real DAO -> fresh query. */
public class StorageSmbServiceNameCoordinatorDaoTest {
    private Field datasource;
    private DataSource previous;
    private TransactionLegacy transaction;
    private StorageServiceInstanceDaoImpl instances;
    private StorageServiceConfigurationOperationDaoImpl operations;
    private StorageSmbServiceNameDaoImpl names;
    private StorageDirectoryPolicyDaoImpl policies;
    private StorageFileShareDaoImpl shares;
    private StorageAccessRuleDaoImpl rules;
    private StorageDirectoryPolicyVO boundPolicy;
    private StorageFileShareVO boundShare;
    private String policyBefore, shareBefore;
    private StorageServiceInstanceVO instance;
    private StorageServiceGuestCommandDispatcher guest;
    private StorageServiceConfigurationCoordinator coordinator;
    private StorageServiceConfigurationPreflight preflight;
    private JsonObject fixture;
    private final java.util.concurrent.atomic.AtomicReference<StorageServiceConfigurationPlan> plan=new java.util.concurrent.atomic.AtomicReference<>();

    @Before public void setup() throws Exception {
        String url=System.getProperty("configuration.dao.test.url");
        Assume.assumeTrue("Dedicated local database required",url!=null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds=new MysqlDataSource();ds.setURL(url);ds.setUser("root");
        try(Connection c=ds.getConnection();Statement s=c.createStatement()){
            String schema;
            try(var in=getClass().getResourceAsStream("/META-INF/db/schema-Diplo-After.sql")){
                assertNotNull(in);schema=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            String marker="CREATE TABLE IF NOT EXISTS "+(char)96+"cloud"+(char)96+"."+(char)96+"storage_service_instance"+(char)96;
            int start=schema.indexOf(marker);assertTrue(start>=0);s.execute(schema.substring(start,schema.indexOf(';',start)+1));
            for(String column:new String[]{"current_runtime_bundle_id BIGINT UNSIGNED NULL","previous_runtime_bundle_id BIGINT UNSIGNED NULL","runtime_state VARCHAR(32) NULL","runtime_verified_at DATETIME NULL"})
                s.execute("ALTER TABLE storage_service_instance ADD COLUMN IF NOT EXISTS "+column);
            com.cloud.upgrade.dao.EuropaStorageConfigurationUpgrade.migrate(c);
            com.cloud.upgrade.dao.EuropaSharedFSDeletionUpgrade.migrate(c);
            com.cloud.upgrade.dao.EuropaSmbServiceNamesUpgrade.migrate(c);
            com.cloud.upgrade.dao.EuropaSmbServiceNamesUpgrade.migrate(c);
            for (String table : new String[]{"storage_file_share", "storage_access_rule"}) {
                String ddlMarker = "CREATE TABLE IF NOT EXISTS "+(char)96+"cloud"+(char)96+"."+(char)96+table+(char)96;
                int offset = schema.indexOf(ddlMarker); assertTrue(offset >= 0);
                s.execute(schema.substring(offset, schema.indexOf(';', offset)+1));
            }
            try (var in=getClass().getResourceAsStream("/META-INF/db/schema-sharedfs-1452-directory-policy.sql")) {
                assertNotNull(in); s.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            s.execute("DELETE FROM storage_access_rule");
            s.execute("DELETE FROM storage_directory_policy");
            s.execute("DELETE FROM storage_file_share");
            s.execute("DELETE FROM storage_smb_service_name");
            s.execute("DELETE FROM storage_service_configuration_operation");
            s.execute("DELETE FROM storage_service_instance");
        }
        datasource=TransactionLegacy.class.getDeclaredField("s_ds");datasource.setAccessible(true);
        previous=(DataSource)datasource.get(null);datasource.set(null,ds);
        transaction=TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        instances=new StorageServiceInstanceDaoImpl();operations=new StorageServiceConfigurationOperationDaoImpl();names=new StorageSmbServiceNameDaoImpl();
        instance=new StorageServiceInstanceVO("smb-name-native-dao-test",null,1,2,13,1L,"SYSTEM_VM");
        instance.setVmId(221L);instance.setConfigurationRevision(3);instance=instances.persist(instance);
        try(var in=getClass().getResourceAsStream("/storage-config/smb-ad-names-rollback-actual.json")){
            assertNotNull(in);fixture=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        policies=spy(new StorageDirectoryPolicyDaoImpl()); shares=spy(new StorageFileShareDaoImpl());
        rules=spy(new StorageAccessRuleDaoImpl());
        boundPolicy=new StorageDirectoryPolicyVO();boundPolicy.setInstanceId(instance.getId());boundPolicy.setVolumeId(77);
        boundPolicy.setUuid("748e9a0f-9833-32fb-821a-235b8c2fad60");
        boundPolicy.setCanonicalPath("export/ad1453r5/issue1465-ad5");
        boundPolicy.setCanonicalHash(org.apache.commons.codec.digest.DigestUtils.sha256Hex(boundPolicy.getCanonicalPath()));
        boundPolicy.setOwnerUid(10004);boundPolicy.setOwnerGid(10004);boundPolicy.setMode("2770");
        JsonObject metadata=new JsonObject();metadata.addProperty("aclPolicyVersion",1);metadata.addProperty("filesystemuuid","9c8ddfe9-3e23-46f0-9476-5c794b004841");metadata.addProperty("inode",159);boundPolicy.setMetadataJson(metadata.toString());
        boundPolicy=policies.persist(boundPolicy);
        JsonObject config=new JsonObject();config.addProperty("directoryPolicyUuid",boundPolicy.getUuid());
        config.addProperty("relativePath",boundPolicy.getCanonicalPath());
        config.addProperty("volumeMountPath","/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277");
        boundShare=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
            "i1465ad5","/export/ad1453r5/issue1465-ad5",77L,"XFS",null,StorageServiceInstance.ResourceState.Ready,config.toString()));
        policyBefore=new Gson().toJson(policies.findById(boundPolicy.getId()),StorageDirectoryPolicyVO.class);
        shareBefore=new Gson().toJson(shares.findById(boundShare.getId()),StorageFileShareVO.class);
        guest=mock(StorageServiceGuestCommandDispatcher.class);
        preflight=mock(StorageServiceConfigurationPreflight.class);
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"fingerprint\":\"fixture\",\"checks\":[],\"intent\":{},\"profile\":{\"observationseconds\":60}}").getAsJsonObject();
        when(preflight.observe(any(),any(),any())).thenReturn(report);
        coordinator=new StorageServiceConfigurationCoordinator(instances,operations,mock(StorageServiceProtocolDao.class),
            shares,mock(StorageBlockTargetDao.class),rules,
            guest,preflight,mock(StorageServiceRuntimeUpgradeDao.class)).withSmbServiceNames(names).withDirectoryPolicies(policies);
    }
    @After public void close() throws Exception {
        if(names!=null)for(StorageSmbServiceNameVO row:names.listAll())names.remove(row.getId());
        if(transaction!=null)transaction.close();
        if(datasource!=null)datasource.set(null,previous);
    }
    private static void field(Object target,Class<?> type,String name,Object value) throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private StorageSmbServiceNameVO original(){
        JsonObject before=fixture.getAsJsonObject("request").getAsJsonObject("previous");
        StorageSmbServiceNameVO row=new StorageSmbServiceNameVO();row.setInstanceId(instance.getId());
        row.setUuid(before.get("uuid").getAsString());row.setFqdn(before.get("fqdn").getAsString());
        row.setNameKind("ALIAS");row.setBindingType("EXPLICIT");row.setEndpointUuid(before.get("endpointUuid").getAsString());
        row.setCanonicalHash(org.apache.commons.codec.digest.DigestUtils.sha256Hex(row.getFqdn()));
        row.setState(StorageServiceInstance.ResourceState.Ready);row.setRevision(3);
        row.setDesiredJson(StorageSmbServiceNames.withoutOwnership(before).toString());
        row.setOwnershipJson(before.get("ownership").toString());return names.persist(row);
    }
    private UpdateStorageSmbServiceNameCmd command(StorageSmbServiceNameVO row) throws Exception {
        UpdateStorageSmbServiceNameCmd cmd=new UpdateStorageSmbServiceNameCmd();
        field(cmd,BaseStorageSmbServiceNameCmd.class,"instanceId",instance.getId());
        field(cmd,BaseStorageSmbServiceNameCmd.class,"id",row.getId());
        field(cmd,BaseStorageSmbServiceNameCmd.class,"username","one-use-operator");
        field(cmd,BaseStorageSmbServiceNameCmd.class,"password","never-persist-this");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        return cmd;
    }
    private StorageServiceConfigurationOperationVO run(boolean tampered) throws Exception {
        StorageSmbServiceNameVO row=original();
        UpdateStorageSmbServiceNameCmd cmd=command(row);
        when(guest.dispatch(any())).thenAnswer(call->{
            StorageServiceGuestCommand input=call.getArgument(0);
            JsonObject result=new JsonObject();
            if("configuration capability show".equals(input.getOperation())){
                for(String k:new String[]{"configurationTransactionVersion","configurationPreflightVersion","smbAdServiceNamesVersion","directoryAclReceiptsVersion","nestedFileSharePathVersion","nestedFileSharePathProtectionVersion"})result.addProperty(k,1);
            }else{
                JsonObject payload=JsonParser.parseString(input.getPayload()).getAsJsonObject();
                JsonObject proof=fixture.getAsJsonObject("rollbackReceipt").deepCopy();
                proof.add("operationid",payload.get("operationid"));proof.addProperty("instanceUuid",instance.getUuid());
                JsonObject request=payload.getAsJsonObject("candidate").getAsJsonObject(StorageSmbServiceNames.OPERATION);
                proof.addProperty("requestHash",StorageSmbServiceNames.wireHash(request));
                if(tampered)proof.getAsJsonArray("renewals").get(0).getAsJsonObject().addProperty("computerGuid","foreign-computer");
                result.addProperty("state","ROLLED_BACK");result.add("operationid",payload.get("operationid"));
                result.addProperty("instanceUuid",instance.getUuid());result.add("smbServiceNameRollback",proof);
                result.add("originalfailure",JsonParser.parseString("{\"code\":\"TEST_POST_RENAME_FAILURE\",\"phase\":\"APPLYING\"}"));
                result.add("rollback",JsonParser.parseString("{\"state\":\"SUCCEEDED\"}"));
            }
            return new StorageServiceGuestCommandResult(true,"native-samba-fixture",result.toString());
        });
        try{
            coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,StorageSmbServiceNameResponse.class,()->{
                JsonObject request=fixture.getAsJsonObject("request").deepCopy();
                request.addProperty("operationid",plan.get().operationUuid);request.addProperty("instanceUuid",instance.getUuid());
                StorageSmbServiceNameDao view=plan.get().dao(StorageSmbServiceNameDao.class);
                StorageSmbServiceNameVO candidate=view.findById(row.getId());
                candidate.setFqdn(request.getAsJsonObject("entry").get("fqdn").getAsString());
                candidate.setDesiredJson(StorageSmbServiceNames.withoutOwnership(request.getAsJsonObject("entry")).toString());
                candidate.setRevision(4);view.update(candidate.getId(),candidate);
                plan.get().commands.put(StorageSmbServiceNames.OPERATION,request);
                return StorageSmbServiceNames.response(instance,candidate,new JsonObject());
            },plan::set,()->{},null);
            fail("A rolled back native operation is not a successful configuration commit");
        }catch(CloudRuntimeException expected){ }
        StorageServiceConfigurationOperationVO op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertNotNull(op);
        StorageSmbServiceNameVO fresh=names.findById(row.getId());
        assertNotSame(row,fresh);
        assertEquals(row.getDesiredJson(),fresh.getDesiredJson());assertEquals(3,fresh.getRevision());
        assertEquals(row.getFqdn(),fresh.getFqdn());assertEquals(row.getCanonicalHash(),fresh.getCanonicalHash());
        assertEquals(3,instances.findById(instance.getId()).getConfigurationRevision());
        if(!tampered){
            assertEquals("ROLLED_BACK",op.getState());assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            JsonObject renewed=fixture.getAsJsonObject("rollbackReceipt").getAsJsonArray("renewals").get(0).getAsJsonObject().getAsJsonObject("ownership");
            assertEquals(renewed,JsonParser.parseString(fresh.getOwnershipJson()));
            assertEquals(renewed,StorageSmbServiceNames.entry(fresh).get("ownership")); // later request uses the fresh identity
            assertTrue(fresh.getUpdated().getTime()>0);
        }else{
            assertEquals("RECOVERY_REQUIRED",op.getState());assertEquals(Long.valueOf(op.getId()),instances.findById(instance.getId()).getActiveConfigurationOperationId());
            assertEquals(row.getOwnershipJson(),fresh.getOwnershipJson());
        }
        assertFalse(op.getCandidateSnapshot().contains("never-persist-this"));
        assertFalse(op.getCandidateSnapshot().contains("one-use-operator"));
        assertEquals(StorageSmbServiceNames.TYPE,op.getResourceType());
        return op;
    }
    private void createThroughRealCandidate(boolean tampered) throws Exception { createThroughRealCandidate(tampered, false); }
    private void createThroughRealCandidate(boolean tampered, boolean resume) throws Exception {
        boolean[] corrupt={tampered};
        JsonObject live;
        try(var in=getClass().getResourceAsStream("/storage-config/smb-ad-names-bound-actual.json")) {
            assertNotNull(in); live=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        fixture.add("createReceipt",live.get("smbServiceNameReceipt").deepCopy());
        JsonObject observedEntry=live.getAsJsonObject("smbServiceNameReceipt").getAsJsonObject("entry");
        JsonObject previewEntry=StorageSmbServiceNames.withoutOwnership(observedEntry);previewEntry.remove("uuid");
        fixture.getAsJsonObject("createBefore").getAsJsonObject("entry").add("entry",previewEntry);
        fixture.getAsJsonObject("createBefore").getAsJsonObject("entry").add("ownership",observedEntry.get("ownership").deepCopy());
        JsonObject query=fixture.getAsJsonObject("createRequest").deepCopy();
        JsonObject seed=fixture.getAsJsonObject("createBefore").getAsJsonObject("entry").getAsJsonObject("entry").deepCopy();
        query.add("entry",seed);query.remove("operationid");
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"fingerprint\":\"fixture\",\"checks\":[],\"intent\":{},\"profile\":{\"observationseconds\":60}}").getAsJsonObject();
        report.getAsJsonObject("intent").add("smbadnames",query);
        report.add("namespreview",fixture.get("createBefore"));when(preflight.observe(any(),any(),any())).thenReturn(report);
        when(guest.dispatch(any())).thenAnswer(call->{
            StorageServiceGuestCommand input=call.getArgument(0);JsonObject result=new JsonObject();
            if("configuration capability show".equals(input.getOperation())){
                for(String key:new String[]{"configurationTransactionVersion","configurationPreflightVersion","smbAdServiceNamesVersion","directoryAclReceiptsVersion","nestedFileSharePathVersion","nestedFileSharePathProtectionVersion"})result.addProperty(key,1);
            }else{
                JsonObject payload=JsonParser.parseString(input.getPayload()).getAsJsonObject();
                result.add("operationid",payload.get("operationid"));result.addProperty("instanceUuid",instance.getUuid());
                if("configuration operation apply".equals(input.getOperation()) || "configuration operation show".equals(input.getOperation())){
                    JsonObject command=payload.has("candidate") ? payload.getAsJsonObject("candidate").getAsJsonObject(StorageSmbServiceNames.OPERATION)
                        : JsonParser.parseString(operations.listByInstance(instance.getId()).get(0).getCandidateSnapshot()).getAsJsonObject().getAsJsonObject("smbServiceNameCommand");
                    String id=command.getAsJsonObject("entry").get("uuid").getAsString();
                    assertEquals(StorageServiceInstance.ResourceState.Creating,names.findByUuid(id).getState());
                    JsonObject receipt=live.getAsJsonObject("smbServiceNameReceipt").deepCopy();
                    // Only operation/instance/resource IDs and their request hash are local-test identities.
                    receipt.add("operationid",payload.get("operationid"));
                    receipt.addProperty("instanceUuid",instance.getUuid());receipt.addProperty("requestHash",StorageSmbServiceNames.wireHash(command));
                    receipt.getAsJsonObject("entry").addProperty("uuid",id);
                    if(corrupt[0])receipt.getAsJsonObject("entry").getAsJsonObject("ownership").addProperty("computerGuid","foreign");
                    result.addProperty("state","VERIFIED");result.add("smbServiceNameReceipt",receipt);
                }else result.addProperty("state","COMMITTED");
            }
            return new StorageServiceGuestCommandResult(true,"native-create-fixture",result.toString());
        });
        CreateStorageSmbServiceNameCmd cmd=new CreateStorageSmbServiceNameCmd();
        field(cmd,BaseStorageSmbServiceNameCmd.class,"instanceId",instance.getId());
        field(cmd,BaseStorageSmbServiceNameCmd.class,"fqdn",seed.get("fqdn").getAsString());
        field(cmd,BaseStorageSmbServiceNameCmd.class,"username","one-use-operator");
        field(cmd,BaseStorageSmbServiceNameCmd.class,"password","never-persist-this");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        StorageSmbServiceNameResponse response=null;
        try{
            response=coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,StorageSmbServiceNameResponse.class,
                ()->new StorageSmbServiceNames(plan.get().dao(StorageSmbServiceNameDao.class),mock(StorageServiceProtocolDao.class),mock(StorageIdentityDomainDao.class),guest,x->"10.10.13.220")
                    .candidate(instance,plan.get(),cmd),plan::set,()->{},null);
            assertFalse("Foreign ownership was committed",tampered);
        }catch(CloudRuntimeException error){if(!tampered)throw error;}
        StorageServiceConfigurationOperationVO op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertNotNull(op);assertEquals(1,names.listByInstanceId(instance.getId()).size());
        if(resume) {
            assertEquals("RECOVERY_REQUIRED",op.getState()); corrupt[0]=false;
            String uuid=op.getUuid(); long id=op.getId();
            coordinator.resume(instances.findById(instance.getId()),op,value->{});
            op=operations.findById(id); assertEquals(uuid,op.getUuid());assertEquals(1,operations.listByInstance(instance.getId()).size());
        }
        StorageSmbServiceNameVO stored=names.listByInstanceId(instance.getId()).get(0);
        if(tampered && !resume){
            assertEquals("RECOVERY_REQUIRED",op.getState());assertEquals(StorageServiceInstance.ResourceState.Creating,stored.getState());
            assertEquals(Long.valueOf(op.getId()),instances.findById(instance.getId()).getActiveConfigurationOperationId());
        }else{
            assertEquals("COMMITTED",op.getState());assertEquals(StorageServiceInstance.ResourceState.Ready,stored.getState());
            assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            assertEquals(4,instances.findById(instance.getId()).getConfigurationRevision());
            assertEquals(fixture.getAsJsonObject("createReceipt").get("entry").getAsJsonObject().get("ownership"),JsonParser.parseString(stored.getOwnershipJson()));
            if(response==null)response=StorageSmbServiceNames.response(instance,stored,JsonParser.parseString(stored.getObservedJson()).getAsJsonObject());
            response.setResponseName("createstoragesmbservicenameresponse");
            assertTrue(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json").contains(stored.getUuid()));
        }
        assertEquals(policyBefore,new Gson().toJson(policies.findById(boundPolicy.getId()),StorageDirectoryPolicyVO.class));
        assertEquals(shareBefore,new Gson().toJson(shares.findById(boundShare.getId()),StorageFileShareVO.class));
        verify(policies,never()).update(anyLong(),any());verify(shares,never()).update(anyLong(),any());
        verify(rules,never()).update(anyLong(),any());
        assertFalse(op.getCandidateSnapshot().contains("never-persist-this"));assertFalse(op.getCandidateSnapshot().contains("one-use-operator"));
    }
    @Test public void nativeBoundReceiptRecoversSameReservationWithoutRewritingPolicyOrShare() throws Exception {createThroughRealCandidate(true,true);}
    private StorageServiceConfigurationPlan boundPlan() {
        StorageServiceConfigurationPlan candidate=new StorageServiceConfigurationPlan(instance.getId(),
            mock(StorageServiceProtocolDao.class),shares,mock(StorageBlockTargetDao.class),rules,text->{});
        candidate.directoryPolicies(policies);candidate.smbServiceNames(names);return candidate;
    }
    @Test public void namesScopeRejectsRealShareDeltaOrAdditionalGuestCommand() {
        for(boolean extraCommand:new boolean[]{false,true}) {
            StorageServiceConfigurationPlan candidate=boundPlan();
            StorageServiceConfigurationOperationVO op=new StorageServiceConfigurationOperationVO();
            op.setProtocol("SMB");op.setResourceType(StorageSmbServiceNames.TYPE);op.setBeforeSnapshot(candidate.snapshot());
            JsonObject command=new JsonObject();command.addProperty("action","CREATE");
            JsonObject saved=new JsonObject();saved.add("smbServiceNameCommand",command);op.setCandidateSnapshot(saved.toString());
            candidate.commands.put(StorageSmbServiceNames.OPERATION,command);
            if(extraCommand)candidate.commands.put("smb share apply",new JsonObject());
            else {
                StorageFileShareVO changed=candidate.dao(StorageFileShareDao.class).findById(boundShare.getId());
                changed.setConfigJson("{}");candidate.dao(StorageFileShareDao.class).update(changed.getId(),changed);
            }
            try {StorageSmbServiceNames.namesOnly(candidate,op);fail("Names-only exemption accepted an unrelated delta");}
            catch(CloudRuntimeException error){assertEquals("SMB_NAME_PLAN_SCOPE_MISMATCH",error.getMessage());}
        }
    }
    @Test public void actualSmbChangeStillRequiresCompleteDirectoryAclReceipt() {
        for(boolean forged:new boolean[]{false,true}) {
            StorageServiceConfigurationPlan candidate=boundPlan();StorageServiceConfigurationOperationVO op=new StorageServiceConfigurationOperationVO();
            op.setProtocol("SMB");op.setResourceType("UpdateStorageSmbShareCmd");
            op.setBeforeSnapshot(candidate.snapshot());op.setCandidateSnapshot("{}");
            assertFalse(StorageSmbServiceNames.namesOnly(candidate,op));
            JsonObject observed=new JsonObject();observed.addProperty("instanceUuid",instance.getUuid());
            if(forged) {
                JsonObject proof=new JsonObject();proof.addProperty("version",1);proof.addProperty("operationid",op.getUuid());
                proof.addProperty("baseRevision",op.getBaseRevision());proof.addProperty("targetRevision",op.getTargetRevision());
                proof.add("instanceUuid",observed.get("instanceUuid"));proof.add("entries",new JsonArray());observed.add("directoryAclReceipts",proof);
            }
            try {StorageDirectoryAclReceipts.apply(candidate,op,observed);fail("Missing/empty actual ACL receipt accepted");}
            catch(CloudRuntimeException error){assertEquals("DIRECTORY_ACL_RECEIPT_MISMATCH",error.getMessage());}
        }
    }
    @Test public void initialCreatingReservationGuestReceiptAndReadySqlCommitRoundTrip() throws Exception {createThroughRealCandidate(false);}
    @Test public void foreignOwnershipCannotPromoteCreatingOrReleaseReservation() throws Exception {createThroughRealCandidate(true);}
    @Test public void actualNativeRollbackRenewalSurvivesCoordinatorSqlAndFreshQuery() throws Exception {run(false);}
    @Test public void foreignIdentityCannotRenewMetadataOrReleaseTheReservation() throws Exception {run(true);}
    @Test public void actualNativeCanonicalHashMatchesGuest() {
        assertEquals(fixture.getAsJsonObject("rollbackReceipt").get("requestHash").getAsString(),
            StorageSmbServiceNames.wireHash(fixture.getAsJsonObject("request")));
    }
    @Test public void mediumtextTimestampAndUniqueNameRoundTrip() throws Exception {
        StorageSmbServiceNameVO row=original();
        String large="{\"한글\":\""+ "보존😀".repeat(4000)+"\"}";
        StorageSmbServiceNameVO update=names.createForUpdate(row.getId());update.setObservedJson(large);update.setUpdated(new java.util.Date());
        assertTrue(names.update(row.getId(),update));StorageSmbServiceNameVO fresh=names.findById(row.getId());
        assertEquals(large,fresh.getObservedJson());assertTrue(fresh.getUpdated().getTime()>0);
        StorageSmbServiceNameVO duplicate=new StorageSmbServiceNameVO();
        duplicate.setInstanceId(row.getInstanceId());duplicate.setFqdn(row.getFqdn());
        duplicate.setCanonicalHash(row.getCanonicalHash());duplicate.setState(StorageServiceInstance.ResourceState.Ready);
        try{names.persist(duplicate);fail("Duplicate instance/FQDN hash was accepted");}
        catch(javax.persistence.EntityExistsException expected){
            Throwable cause=expected;while(cause.getCause()!=null)cause=cause.getCause();
            assertTrue(cause.getMessage(),cause.getMessage().contains("uk_smb_name_instance"));
        }
    }
    @Test public void contextAndEmptyOrPopulatedListUseTheirRealSerializerContracts() throws Exception {
        ListResponse<StorageSmbServiceNameResponse> listing=new ListResponse<>();
        StorageSmbServiceNameVO row=original();
        listing.setResponses(java.util.List.of(StorageSmbServiceNames.response(instance,row,new JsonObject())),1);
        listing.setResponseName("liststoragesmbservicenamesresponse");
        String list=com.cloud.api.response.ApiResponseSerializer.toSerializedString(listing,"json");
        JsonObject context=fixture.getAsJsonObject("before").getAsJsonObject("entry").getAsJsonObject("computer").deepCopy();
        context.addProperty("status","OBSERVED");
        StorageSmbServiceNameContextResponse separate=new StorageSmbServiceNameContextResponse();separate.setContext(context);
        separate.setResponseName("getstoragesmbservicenamecontextresponse");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(separate,"json");
        JsonObject parsed=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("getstoragesmbservicenamecontextresponse")
            .getAsJsonObject("servicenamecontext").getAsJsonObject("context");
        assertEquals(context.get("guid"),parsed.get("guid"));assertEquals(context.get("fqdn"),parsed.get("fqdn"));
        assertEquals(context.get("realm"),parsed.get("realm"));
        assertTrue(list,list.contains(row.getUuid()));assertFalse(list,list.contains("servicenamecontext"));
        ListResponse<StorageSmbServiceNameResponse> empty=new ListResponse<>();empty.setResponses(java.util.List.of(),0);
        empty.setResponseName("liststoragesmbservicenamesresponse");
        String emptyWire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(empty,"json");
        assertFalse(emptyWire,JsonParser.parseString(emptyWire).getAsJsonObject().getAsJsonObject("liststoragesmbservicenamesresponse").has("context"));
        for(Field f:StorageSmbServiceNameResponse.class.getDeclaredFields())assertFalse(f.getName().equals("jobid"));
        JsonObject report=new JsonObject();report.addProperty("status","READY");report.addProperty("revision",3);
        report.addProperty("observedat",System.currentTimeMillis()/1000.0);report.addProperty("expiresat",System.currentTimeMillis()/1000.0+30);
        report.addProperty("fingerprint","native-service-name-check");report.add("checks",new JsonArray());
        report.add("namespreview",fixture.get("before"));report.add("impact",JsonParser.parseString("{\"class\":\"NO_SERVICE_RESTART\",\"method\":\"SMB_AD_NAMES_ONLY\",\"ackrequired\":true,\"drainrequired\":false,\"connections\":0,\"affectedcount\":1}"));
        StorageServiceConfigurationPreflightResponse admission=new StorageServiceConfigurationPreflightResponse(report);
        admission.setResponseName("preflightstorageserviceconfigurationresponse");
        String preflight=com.cloud.api.response.ApiResponseSerializer.toSerializedString(admission,"json");
        assertTrue(preflight,JsonParser.parseString(preflight).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse")
            .getAsJsonObject("configurationpreflight").has("namespreview"));
        String output=System.getProperty("smb.names.wire");
        if(output!=null){
            assertTrue(output.startsWith("/root/work/artifacts/issue1466-implementation/"));
            JsonObject all=new JsonObject();all.add("context",JsonParser.parseString(wire));all.add("list",JsonParser.parseString(list));all.add("emptyList",JsonParser.parseString(emptyWire));all.add("preflight",JsonParser.parseString(preflight));
            java.nio.file.Files.writeString(java.nio.file.Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(all));
        }
    }
}
