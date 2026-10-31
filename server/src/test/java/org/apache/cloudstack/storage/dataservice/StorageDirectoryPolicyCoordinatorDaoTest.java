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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.upgrade.dao.EuropaStorageConfigurationUpgrade;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsIdentityMappingCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceConfigurationOperationDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDaoImpl;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageDirectoryPolicyCmd;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real coordinator, real instance reservation and real operation DAO against the unchanged VARCHAR(32). */
public class StorageDirectoryPolicyCoordinatorDaoTest {
    private Field datasource;
    private DataSource previous;
    private TransactionLegacy transaction;
    private StorageServiceInstanceDaoImpl instances;
    private StorageServiceConfigurationOperationDaoImpl operations;
    private StorageServiceInstanceVO instance;
    private StorageServiceGuestCommandDispatcher guest;
    private StorageServiceConfigurationCoordinator coordinator;
    private StorageDirectoryPolicyDaoImpl policies;
    private StorageFileShareDaoImpl shares;
    private final java.util.concurrent.atomic.AtomicReference<StorageServiceConfigurationPlan> plan=new java.util.concurrent.atomic.AtomicReference<>();

    @Before public void open() throws Exception {
        String url = System.getProperty("configuration.dao.test.url");
        Assume.assumeTrue("Dedicated loopback database required", url != null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds = new MysqlDataSource(); ds.setURL(url); ds.setUser("root");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String schema;
            try (java.io.InputStream in = getClass().getResourceAsStream("/META-INF/db/schema-Diplo-After.sql")) {
                assertNotNull(in); schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            char quote = 96;
            String marker = "CREATE TABLE IF NOT EXISTS " + quote + "cloud" + quote + "." + quote + "storage_service_instance" + quote;
            int start = schema.indexOf(marker); assertTrue(start >= 0);
            s.execute(schema.substring(start, schema.indexOf(';', start) + 1));
            // Existing runtime columns of the deployed VO, only in the dedicated test schema.
            for (String column : new String[] {"current_runtime_bundle_id BIGINT UNSIGNED NULL",
                    "previous_runtime_bundle_id BIGINT UNSIGNED NULL", "runtime_state VARCHAR(32) NULL", "runtime_verified_at DATETIME NULL"})
                s.execute("ALTER TABLE storage_service_instance ADD COLUMN IF NOT EXISTS " + column);
            EuropaStorageConfigurationUpgrade.migrate(c);
            int fs=schema.indexOf("CREATE TABLE IF NOT EXISTS "+quote+"cloud"+quote+"."+quote+"storage_file_share"+quote);
            assertTrue(fs>=0);s.execute(schema.substring(fs,schema.indexOf(';',fs)+1));
            String ddl;
            try(java.io.InputStream in=getClass().getResourceAsStream("/META-INF/db/schema-sharedfs-1452-directory-policy.sql")) {
                assertNotNull(in);ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            s.execute(ddl);s.execute(ddl);
            s.execute("DELETE FROM storage_directory_policy");
            s.execute("DELETE FROM storage_file_share");
            s.execute("DELETE FROM storage_service_configuration_operation");
            s.execute("DELETE FROM storage_service_instance");
            try (java.sql.ResultSet rs = s.executeQuery("SELECT @@sql_mode")) {
                assertTrue(rs.next()); assertTrue(rs.getString(1).contains("STRICT"));
            }
        }
        datasource = TransactionLegacy.class.getDeclaredField("s_ds"); datasource.setAccessible(true);
        previous = (DataSource) datasource.get(null); datasource.set(null, ds);
        transaction = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        instances = new StorageServiceInstanceDaoImpl();
        operations = new StorageServiceConfigurationOperationDaoImpl();
        instance = new StorageServiceInstanceVO("identity-coordinator-test", null, 1, 2, 13, 1L, "SYSTEM_VM");
        instance.setVmId(null); instance.setConfigurationRevision(3);
        instance = instances.persist(instance);
        guest = mock(StorageServiceGuestCommandDispatcher.class);
        policies=new StorageDirectoryPolicyDaoImpl();shares=new StorageFileShareDaoImpl();
        StorageServiceConfigurationPreflight preflight=mock(StorageServiceConfigurationPreflight.class);
        JsonObject report=new JsonObject();report.addProperty("status","READY");report.addProperty("fingerprint","fixture");
        report.add("checks",new com.google.gson.JsonArray());report.add("intent",new JsonObject());
        JsonObject profile=new JsonObject();profile.addProperty("observationseconds",60);report.add("profile",profile);
        org.mockito.Mockito.when(preflight.observe(any(),any(),any())).thenReturn(report);
        coordinator = new StorageServiceConfigurationCoordinator(instances,operations,
                mock(StorageServiceProtocolDao.class),shares,mock(StorageBlockTargetDao.class),mock(StorageAccessRuleDao.class),
                guest,preflight,mock(StorageServiceRuntimeUpgradeDao.class)).withDirectoryPolicies(policies);

    }
    @After public void close() throws Exception {
        if (transaction != null) transaction.close();
        if (datasource != null) datasource.set(null, previous);
    }
    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }

    @Test public void actualCoordinatorReservationCommitAndDaoReloadPreserveCommonReferenceAndLargeMetadata() throws Exception {
        StorageFileShareVO nfs=new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.NFS,"nfs","/export/team",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Ready,"{\"keep\":true}");
        nfs=shares.persist(nfs);
        CreateStorageDirectoryPolicyCmd cmd=new CreateStorageDirectoryPolicyCmd();
        field(cmd,cmd.getClass(),"instanceId",instance.getId());field(cmd,cmd.getClass(),"volumeId",77L);field(cmd,cmd.getClass(),"path","/export/team");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        final long shareId=nfs.getId();
        final String metadata="{\"한글\":\""+ "보존😀".repeat(4000)+"\"}";
        java.util.function.Supplier<String> candidate=()-> {
            StorageDirectoryPolicyVO p=new StorageDirectoryPolicyVO();p.setInstanceId(instance.getId());p.setVolumeId(77);
            p.setCanonicalPath("export/team");p.setCanonicalHash("a".repeat(64));p.setOwnerUid(1001);p.setOwnerGid(1001);p.setMode("0770");
            p.setMetadataJson(metadata);
            p=plan.get().dao(StorageDirectoryPolicyDao.class).persist(p);
            assertEquals(0,policies.findById(p.getId()).getRevision()); // real durable reservation is not a ready policy
            StorageFileShareVO share=plan.get().dao(StorageFileShareDao.class).findById(shareId);
            share.setConfigJson("{\"keep\":true,\"directoryPolicyUuid\":\""+p.getUuid()+"\"}");
            plan.get().dao(StorageFileShareDao.class).update(shareId,share);
            return p.getUuid();
        };
        String id=coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,String.class,candidate,plan::set,()->{},null);
        assertEquals(1,policies.findByUuid(id).getRevision());
        assertEquals(metadata,policies.findByUuid(id).getMetadataJson());
        assertTrue(shares.findById(shareId).getConfigJson().contains(id));
        assertTrue(shares.findById(shareId).getConfigJson().contains("\"keep\":true"));
        StorageServiceConfigurationOperationVO op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertEquals("DirectoryPosixPolicy",op.getResourceType());assertEquals("COMMITTED",op.getState());
        assertEquals(4,instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        assertEquals(id,coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.NFS,String.class,
                ()->{throw new AssertionError("duplicate must not prepare a new candidate");},plan::set,()->{},null));
        assertEquals(1,policies.listByInstanceId(instance.getId()).size());
        StorageDirectoryPolicyVO duplicate=new StorageDirectoryPolicyVO();duplicate.setInstanceId(instance.getId());duplicate.setVolumeId(77);
        duplicate.setCanonicalPath("export/team");duplicate.setCanonicalHash("a".repeat(64));duplicate.setMode("0770");
        try {policies.persist(duplicate);fail("canonical path duplicate must fail actual unique index");}catch(javax.persistence.EntityExistsException expected) {
            Throwable root=expected;
            while(root.getCause()!=null) root=root.getCause();
            assertTrue(root instanceof java.sql.SQLIntegrityConstraintViolationException);
        }
        assertEquals(id,policies.listByInstanceId(instance.getId()).get(0).getUuid());
    }
    @Test public void actualSmbCoordinatorPersistsOptionalInheritanceAndLeavesCommonPosixUnchanged() throws Exception {
        StorageDirectoryPolicyVO policy=new StorageDirectoryPolicyVO();policy.setInstanceId(instance.getId());policy.setVolumeId(77);
        policy.setCanonicalPath("export/inherit");policy.setCanonicalHash("b".repeat(64));policy.setOwnerUid(100001);policy.setOwnerGid(200001);
        policy.setMode("2770");policy.setMetadataJson("{\"filesystemuuid\":\"fs\",\"inode\":42}");policy=policies.persist(policy);
        final String policyUuid=policy.getUuid();
        StorageFileShareVO share=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
            "smb","/export/smb",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,
            "{\"keep\":true,\"directoryPolicyUuid\":\""+policyUuid+"\"}"));
        final long shareId=share.getId();
        for(boolean inherit:new boolean[]{true,false}) {
            var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd();
            field(cmd,cmd.getClass(),"id",shareId);field(cmd,cmd.getClass(),"inheritOwner",inherit);
            StorageServiceInstanceVO current=instances.findById(instance.getId());
            field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",current.getConfigurationRevision());
            java.util.function.Supplier<String> candidate=()->{
                StorageFileShareVO staged=plan.get().dao(StorageFileShareDao.class).findById(shareId);
                staged.setConfigJson(StorageSmbParentInheritance.merge(staged.getConfigJson(),cmd.getInheritOwner()));
                StorageSmbParentInheritance.requirePolicy(staged);
                plan.get().dao(StorageFileShareDao.class).update(shareId,staged);return staged.getUuid();
            };
            String result=coordinator.execute(cmd,current,StorageServiceInstance.Protocol.SMB,String.class,candidate,plan::set,()->{},null);
            var reloaded=com.google.gson.JsonParser.parseString(shares.findById(shareId).getConfigJson()).getAsJsonObject();
            assertEquals(inherit,reloaded.get("inheritOwner").getAsBoolean());assertTrue(reloaded.get("keep").getAsBoolean());
            assertEquals(policyUuid,reloaded.get("directoryPolicyUuid").getAsString());assertEquals("2770",policies.findByUuid(policyUuid).getMode());
            assertEquals(100001,policies.findByUuid(policyUuid).getOwnerUid());assertEquals(200001,policies.findByUuid(policyUuid).getOwnerGid());
            var op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());assertEquals("COMMITTED",op.getState());assertTrue(op.getResourceType().length()<=32);
            assertEquals(result,coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.SMB,String.class,
                ()->{throw new AssertionError("duplicate request reapplied");},plan::set,()->{},null));
        }
    }
    @Test public void adSqlLeaseBlocksNewReservationButNotCompletedReplay() throws Exception {
        var domains=mock(org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao.class);
        var domain=new StorageIdentityDomainVO(instance.getId(),"ablestack.local",null,null,
                StorageServiceInstance.DomainJoinState.JOINING,"UNKNOWN","{\"adJoin\":{\"state\":\"RUNNING\"}}");
        org.mockito.Mockito.when(domains.findByInstanceId(instance.getId())).thenReturn(domain);
        coordinator.withAdDomains(domains);
        CreateStorageDirectoryPolicyCmd cmd=new CreateStorageDirectoryPolicyCmd();
        field(cmd,cmd.getClass(),"instanceId",instance.getId());field(cmd,cmd.getClass(),"volumeId",77L);
        field(cmd,cmd.getClass(),"path","/export/ad-lease");field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        try {
            coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,String.class,
                    ()->{throw new AssertionError("candidate must not run during AD apply");},plan::set,()->{},null);
            fail("AD lease accepted a second writer");
        } catch(CloudRuntimeException expected) { assertEquals("AD_DOMAIN_RECOVERY_REQUIRED",expected.getMessage()); }
        assertNull(operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()));
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        domain.setConfigJson("{\"adJoin\":{\"state\":\"COMMITTED\"}}");
        assertEquals("saved",coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,String.class,()->"saved",plan::set,()->{},null));
        domain.setConfigJson("{\"adJoin\":{\"state\":\"RUNNING\"}}");
        assertEquals("saved",coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.NFS,String.class,
                ()->{throw new AssertionError("completed replay must not execute");},plan::set,()->{},null));
    }


    @Test public void actualSmbCreationCoordinatorDaoRoundtripPreservesOtherConfigurationAndDuplicateKey() throws Exception {
        StorageFileShareVO share=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
            "creation-mode","/export/modes",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,
            "{\"keep\":\"원본\",\"directoryMode\":\"2770\",\"ownerUid\":100001}"));
        final long id=share.getId();
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd();
        field(cmd,cmd.getClass(),"id",id);field(cmd,cmd.getClass(),"createMask","0640");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        java.util.function.Supplier<String> candidate=()->{
            StorageFileShareVO staged=plan.get().dao(StorageFileShareDao.class).findById(id);
            staged.setConfigJson(StorageSmbCreationPolicy.merge(staged.getConfigJson(),
                StorageSmbCreationPolicy.fields(StorageServiceConfigurationCoordinator.publicRequest(cmd))));
            plan.get().dao(StorageFileShareDao.class).update(id,staged);return staged.getUuid();
        };
        coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,String.class,candidate,plan::set,()->{},null);
        JsonObject value=com.google.gson.JsonParser.parseString(shares.findById(id).getConfigJson()).getAsJsonObject();
        assertEquals("0640",value.get("createMask").getAsString());assertEquals("2770",value.get("directoryMode").getAsString());
        assertEquals(100001,value.get("ownerUid").getAsInt());assertEquals("원본",value.get("keep").getAsString());
        org.junit.Assert.assertFalse(value.has("forceCreateMode"));
        assertEquals("COMMITTED",operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()).getState());
        coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.SMB,String.class,
            ()->{throw new AssertionError("duplicate mode request reapplied");},plan::set,()->{},null);
        assertEquals(value.toString(),shares.findById(id).getConfigJson());
    }
    @Test public void actualCoordinatorVerifiedPathReceiptCommitsIdentityAndDaoReloadWithoutChangingAlias() throws Exception {
        instance.setVmId(195L);instances.update(instance.getId(),instance);
        final String volume="11111111-2222-4333-8444-555555555555";
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd();
        field(cmd,cmd.getClass(),"instanceId",instance.getId());field(cmd,cmd.getClass(),"relativePath","projects/team");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        org.mockito.Mockito.when(guest.dispatch(any())).thenAnswer(invocation->{
            StorageServiceGuestCommand request=invocation.getArgument(0);
            JsonObject result=new JsonObject();
            if(request.getOperation().equals("configuration capability show")) {
                for(String key:new String[]{"nfsPosixIntentVersion","configurationPreflightVersion","configurationTransactionVersion",
                        "nestedFileSharePathVersion","nestedFileSharePathProtectionVersion"}) result.addProperty(key,1);
            } else {
                JsonObject input=com.google.gson.JsonParser.parseString(request.getPayload()).getAsJsonObject();
                result.add("operationid",input.get("operationid"));result.addProperty("state","VERIFIED");
                if(request.getOperation().equals("configuration operation apply")) {
                    JsonObject row=input.getAsJsonObject("candidate").getAsJsonObject("nfs export apply").getAsJsonArray("exports").get(0).getAsJsonObject();
                    JsonObject binding=new JsonObject();binding.add("resourceuuid",row.get("uuid"));
                    binding.addProperty("volumeuuid",volume);binding.addProperty("relativepath","projects/team");
                    JsonObject identity=new JsonObject();identity.addProperty("filesystemUuid","fs-uuid");
                    identity.addProperty("volumeRootInode",128);identity.addProperty("inode",9001);binding.add("pathIdentity",identity);
                    JsonObject receipt=new JsonObject();receipt.add("operationid",input.get("operationid"));receipt.add("instanceUuid",input.get("instanceUuid"));
                    com.google.gson.JsonArray bindings=new com.google.gson.JsonArray();bindings.add(binding);receipt.add("bindings",bindings);
                    result.add("pathbindings",receipt);
                }
            }
            return new StorageServiceGuestCommandResult(true,"fixture",result.toString());
        });
        java.util.function.Supplier<JsonObject> candidate=()->{
            JsonObject config=new JsonObject();config.addProperty("pathBindingVersion",1);config.addProperty("relativeSharePath","projects/team");
            config.addProperty("volumeMountPath","/srv/ablestack-storage/volumes/"+volume);
            config.addProperty("backingPath","/srv/ablestack-storage/volumes/"+volume+"/projects/team");config.addProperty("keep","원본");
            var row=plan.get().dao(StorageFileShareDao.class).persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.NFS,
                    "public-alias","/export/public-alias",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,config.toString()));
            JsonObject command=new JsonObject();com.google.gson.JsonArray rows=new com.google.gson.JsonArray();
            JsonObject rendered=new JsonObject();rendered.addProperty("uuid",row.getUuid());rendered.addProperty("state","Ready");rendered.add("config",config);
            rows.add(rendered);command.add("exports",rows);plan.get().commands.put("nfs export apply",command);
            JsonObject response=new JsonObject();response.addProperty("id",row.getUuid());response.addProperty("config",row.getConfigJson());return response;
        };
        JsonObject result=coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,JsonObject.class,candidate,plan::set,()->{},null);
        var reloaded=shares.findByUuid(result.get("id").getAsString());
        JsonObject config=com.google.gson.JsonParser.parseString(reloaded.getConfigJson()).getAsJsonObject();
        assertEquals(9001,config.getAsJsonObject("pathIdentity").get("inode").getAsInt());assertEquals("원본",config.get("keep").getAsString());
        assertEquals("/export/public-alias",reloaded.getPath());
        assertEquals(config,com.google.gson.JsonParser.parseString(result.get("config").getAsString()));
        var op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertEquals("COMMITTED",op.getState());assertTrue(op.getCandidateSnapshot().contains("9001"));
        assertEquals(result,coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.NFS,JsonObject.class,
                ()->{throw new AssertionError("duplicate path creation");},plan::set,()->{},null));
        assertEquals(1,shares.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS).size());
    }

}
