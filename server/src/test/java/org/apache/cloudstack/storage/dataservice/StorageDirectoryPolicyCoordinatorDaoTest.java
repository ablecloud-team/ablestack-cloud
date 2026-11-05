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
            int acl=schema.indexOf("CREATE TABLE IF NOT EXISTS "+quote+"cloud"+quote+"."+quote+"storage_access_rule"+quote);
            assertTrue(acl>=0);s.execute(schema.substring(acl,schema.indexOf(';',acl)+1));
            s.execute("DELETE FROM storage_access_rule");
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



    /** Live #1457 SystemVM request/VERIFIED shape with real bound share + actual coordinator/SQL commit. */
    private void sourcePolicyRoundtrip(boolean resume, boolean pathChange) throws Exception {
        instance.setVmId(195L);instances.update(instance.getId(),instance);
        JsonObject nativeFixture;
        try(java.io.InputStream in=getClass().getResourceAsStream("/storage-config/storage-1457-source-verified.json")) {
            assertNotNull(in);nativeFixture=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject oldConfig=nativeFixture.getAsJsonObject("beforeShare").getAsJsonObject("config").deepCopy();
        StorageFileShareVO target=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
                "source-bound","/export/source-bound",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,oldConfig.toString()));
        StorageFileShareVO peer=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
                "peer","/export/peer",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{\"keep\":true}"));
        final long targetId=target.getId();final String peerConfig=peer.getConfigJson();
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd();
        field(cmd,cmd.getClass(),"id",targetId);
        if(pathChange)field(cmd,cmd.getClass(),"relativePath","projects/different");
        else field(cmd,cmd.getClass(),"clientCidrs","[\"10.10.254.0/24\"]");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        final int[] applies={0},acks={0};final boolean[] badReceipt={resume};
        org.mockito.Mockito.when(guest.dispatch(any())).thenAnswer(invocation->{
            StorageServiceGuestCommand request=invocation.getArgument(0);JsonObject value=new JsonObject();
            if(request.getOperation().equals("configuration capability show")) {
                value=nativeFixture.getAsJsonObject("capability").deepCopy();
            } else {
                JsonObject input=com.google.gson.JsonParser.parseString(request.getPayload()).getAsJsonObject();
                value=nativeFixture.getAsJsonObject("observation").deepCopy();
                value.add("operationid",input.get("operationid"));value.addProperty("instanceUuid",instance.getUuid());
                value.addProperty("baseRevision",3);value.addProperty("targetRevision",4);
                if(request.getOperation().equals("configuration operation apply")) {
                    applies[0]++;
                    if(!pathChange)assertTrue(input.get("onlineSmbClientAccess").getAsBoolean());
                }
                if(badReceipt[0]) {
                    JsonObject binding=new JsonObject();binding.addProperty("operationid","wrong-operation");
                    value.add("pathbindings",binding);
                }
                if(request.getOperation().equals("configuration operation commit")) {acks[0]++;value.addProperty("state","COMMITTED");}
            }
            return new StorageServiceGuestCommandResult(true,"live-source-receipt",value.toString());
        });
        java.util.function.Supplier<JsonObject> candidate=()->{
            StorageFileShareVO staged=plan.get().dao(StorageFileShareDao.class).findById(targetId);
            JsonObject changed=oldConfig.deepCopy();
            if(pathChange)changed.addProperty("relativeSharePath","projects/different");
            else changed=com.google.gson.JsonParser.parseString(StorageSmbClientAccessPolicy.merge(staged.getConfigJson(),
                    StorageSmbClientAccessPolicy.fields(StorageServiceConfigurationCoordinator.publicRequest(cmd)))).getAsJsonObject();
            staged.setConfigJson(changed.toString());plan.get().dao(StorageFileShareDao.class).update(targetId,staged);
            renderFileShares(plan.get(),StorageServiceInstance.Protocol.SMB);
            JsonObject response=new JsonObject();response.addProperty("id",staged.getUuid());response.addProperty("config",staged.getConfigJson());return response;
        };
        if(resume || pathChange) {
            try {
                coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,JsonObject.class,candidate,plan::set,
                        ()->renderFileShares(plan.get(),StorageServiceInstance.Protocol.SMB),null);
                fail("invalid/missing path receipt accepted");
            } catch(com.cloud.exception.InvalidParameterValueException expected) {
                assertEquals(resume?"DIRECTORY_BINDING_RECEIPT_MISMATCH":"DIRECTORY_BINDING_RECEIPT_MISSING",expected.getMessage());
            }
            assertEquals(oldConfig,com.google.gson.JsonParser.parseString(shares.findById(targetId).getConfigJson()));
            assertEquals(3,instances.findById(instance.getId()).getConfigurationRevision());
            assertNotNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            if(pathChange)return;
            badReceipt[0]=false;
            coordinator.resume(instances.findById(instance.getId()),operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()),
                    value->renderFileShares(value,StorageServiceInstance.Protocol.SMB));
        } else coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,JsonObject.class,candidate,plan::set,
                ()->renderFileShares(plan.get(),StorageServiceInstance.Protocol.SMB),null);
        JsonObject expected=oldConfig.deepCopy();expected.add("clientAccess",nativeFixture.getAsJsonObject("afterShare").getAsJsonObject("config").get("clientAccess"));
        assertEquals(expected,com.google.gson.JsonParser.parseString(shares.findById(targetId).getConfigJson()));
        assertEquals("/export/source-bound",shares.findById(targetId).getPath());assertEquals(Long.valueOf(77),shares.findById(targetId).getVolumeId());
        assertEquals(peerConfig,shares.findById(peer.getId()).getConfigJson());
        var operation=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertEquals("COMMITTED",operation.getState());assertEquals(4,instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.SMB,JsonObject.class,
                ()->{throw new AssertionError("duplicate source update reapplied");},plan::set,()->{},null);
        coordinator.resume(instances.findById(instance.getId()),operations.findById(operation.getId()),
                value->{throw new AssertionError("completed source recovery reapplied");});
        assertEquals(1,applies[0]);assertEquals(1,acks[0]);assertEquals(1,operations.listByInstance(instance.getId()).size());
    }
    @Test public void sourceOnlyVerifiedWithoutPathReceiptCommitsRealBoundShareAndDaoReload() throws Exception {
        sourcePolicyRoundtrip(false,false);
    }
    @Test public void sourceOnlyDurableResumeCommitsSameBoundShareWithoutSecondGuestApply() throws Exception {
        sourcePolicyRoundtrip(true,false);
    }
    @Test public void ordinaryBoundPathChangeStillRequiresReceipt() throws Exception {
        sourcePolicyRoundtrip(false,true);
    }

    private void renderFileShares(StorageServiceConfigurationPlan value, StorageServiceInstance.Protocol protocol) {
        JsonObject command=new JsonObject();com.google.gson.JsonArray rows=new com.google.gson.JsonArray();
        for(StorageFileShareVO row:value.dao(StorageFileShareDao.class).listByInstanceIdAndProtocol(instance.getId(),protocol)) {
            JsonObject rendered=new JsonObject();rendered.addProperty("uuid",row.getUuid());rendered.addProperty("state",row.getState().name());
            rendered.add("config",com.google.gson.JsonParser.parseString(row.getConfigJson()));rows.add(rendered);
        }
        command.add(protocol==StorageServiceInstance.Protocol.NFS?"exports":"shares",rows);
        value.commands.put(protocol==StorageServiceInstance.Protocol.NFS?"nfs export apply":"smb share apply",command);
    }
    /** Real SystemVM VERIFIED receipt shape; only database/request identities are remapped for isolation. */
    private void booleanDeleteRoundtrip(StorageServiceInstance.Protocol protocol, boolean last, String invalid, boolean resume) throws Exception {
        instance.setVmId(195L);instances.update(instance.getId(),instance);
        JsonObject nativeStatus;
        try(java.io.InputStream in=getClass().getResourceAsStream("/storage-config/storage-1455-delete-verified.json")) {
            assertNotNull(in);nativeStatus=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject nativeReceipt=nativeStatus.getAsJsonObject("pathbindings");
        java.util.Map<String,String> uuidMap=new java.util.HashMap<>();
        if(!last)for(com.google.gson.JsonElement element:nativeReceipt.getAsJsonArray("bindings")) {
            JsonObject binding=element.getAsJsonObject();String relative=binding.get("relativepath").getAsString();
            JsonObject config=new JsonObject();config.addProperty("pathBindingVersion",1);config.addProperty("relativeSharePath",relative);
            config.addProperty("volumeMountPath","/srv/ablestack-storage/volumes/"+binding.get("volumeuuid").getAsString());
            config.addProperty("backingPath",config.get("volumeMountPath").getAsString()+"/"+relative);config.addProperty("keep","원본");
            config.add("pathIdentity",binding.getAsJsonObject("pathIdentity").deepCopy());
            StorageFileShareVO row=shares.persist(new StorageFileShareVO(instance.getId(),protocol,"peer-"+uuidMap.size(),"/export/peer-"+uuidMap.size(),
                    77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,config.toString()));
            uuidMap.put(binding.get("resourceuuid").getAsString(),row.getUuid());
        }
        StorageFileShareVO target=shares.persist(new StorageFileShareVO(instance.getId(),protocol,"delete-alias","/export/delete-alias",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Ready,"{\"pathBindingVersion\":1,\"relativeSharePath\":\"epic1455/projects/team-a\",\"volumeMountPath\":\"/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277\",\"backingPath\":\"/srv/ablestack-storage/volumes/e070a0aa-dbf5-48c0-83e0-09f874446277/epic1455/projects/team-a\"}"));
        BaseStorageServiceConfigurationCmd cmd=protocol==StorageServiceInstance.Protocol.NFS
                ?new org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsExportCmd()
                :new org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbShareCmd();
        field(cmd,cmd.getClass(),"id",target.getId());field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        final boolean[] good={invalid==null};
        final int[] applyCount={0},ackCount={0};
        org.mockito.Mockito.when(guest.dispatch(any())).thenAnswer(invocation->{
            StorageServiceGuestCommand request=invocation.getArgument(0);JsonObject result=new JsonObject();
            if(request.getOperation().equals("configuration capability show")) {
                for(String key:new String[]{"nfsPosixIntentVersion","configurationPreflightVersion","configurationTransactionVersion",
                        "nestedFileSharePathVersion","nestedFileSharePathProtectionVersion"})result.addProperty(key,1);
            } else {
                JsonObject input=com.google.gson.JsonParser.parseString(request.getPayload()).getAsJsonObject();
                result=nativeStatus.deepCopy();result.add("operationid",input.get("operationid"));
                result.addProperty("baseRevision",3);result.addProperty("targetRevision",4);result.addProperty("instanceUuid",instance.getUuid());
                JsonObject receipt=result.getAsJsonObject("pathbindings");receipt.add("operationid",input.get("operationid"));
                receipt.addProperty("instanceUuid",instance.getUuid());com.google.gson.JsonArray bindings=new com.google.gson.JsonArray();
                if(!last)for(com.google.gson.JsonElement item:nativeReceipt.getAsJsonArray("bindings")) {
                    JsonObject binding=item.getAsJsonObject().deepCopy();binding.addProperty("resourceuuid",uuidMap.get(binding.get("resourceuuid").getAsString()));bindings.add(binding);
                }
                receipt.add("bindings",bindings);
                if(!good[0]) {
                    if("missing".equals(invalid))bindings.remove(0);
                    else if("operation".equals(invalid))receipt.addProperty("operationid","wrong-operation");
                    else if("volume".equals(invalid))bindings.get(0).getAsJsonObject().addProperty("volumeuuid","wrong-volume");
                    else if("inode".equals(invalid))bindings.get(0).getAsJsonObject().getAsJsonObject("pathIdentity").addProperty("inode",99999);
                }
                if(request.getOperation().equals("configuration operation apply"))applyCount[0]++;
                if(request.getOperation().equals("configuration operation commit")){ackCount[0]++;result.addProperty("state","COMMITTED");}
            }
            return new StorageServiceGuestCommandResult(true,"native-receipt-fixture",result.toString());
        });
        final long targetId=target.getId();
        java.util.function.Supplier<Boolean> candidate=()->{
            plan.get().dao(StorageFileShareDao.class).remove(targetId);renderFileShares(plan.get(),protocol);return true;
        };
        if(invalid==null)assertTrue(coordinator.execute(cmd,instance,protocol,Boolean.class,candidate,plan::set,()->{},null));
        else {
            try {coordinator.execute(cmd,instance,protocol,Boolean.class,candidate,plan::set,()->{},null);fail("bad receipt accepted");}
            catch(com.cloud.exception.InvalidParameterValueException expected) {
                assertTrue(expected.getMessage().startsWith("DIRECTORY_"));
            }
            assertNotNull(shares.findById(targetId));assertEquals(3,instances.findById(instance.getId()).getConfigurationRevision());
            assertNotNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            if(!resume)return;
            good[0]=true;
            // Reload the durable Boolean snapshot and recover this operation, without a second guest apply.
            coordinator.resume(instances.findById(instance.getId()),operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()),
                    value->renderFileShares(value,protocol));
        }
        StorageServiceConfigurationOperationVO op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertEquals("COMMITTED",op.getState());assertEquals("COMPLETED",op.getPhase());
        JsonObject saved=com.google.gson.JsonParser.parseString(op.getCandidateSnapshot()).getAsJsonObject();
        assertTrue(saved.get("response").isJsonPrimitive());assertTrue(saved.get("response").getAsBoolean());
        assertNull(shares.findById(targetId));assertEquals(last?0:2,shares.listByInstanceIdAndProtocol(instance.getId(),protocol).size());
        assertEquals(4,instances.findById(instance.getId()).getConfigurationRevision());assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        for(StorageFileShareVO peer:shares.listByInstanceIdAndProtocol(instance.getId(),protocol)) {
            JsonObject config=com.google.gson.JsonParser.parseString(peer.getConfigJson()).getAsJsonObject();
            assertEquals("원본",config.get("keep").getAsString());assertTrue(config.has("pathIdentity"));
        }
        assertTrue(coordinator.execute(cmd,instances.findById(instance.getId()),protocol,Boolean.class,
                ()->{throw new AssertionError("duplicate delete reapplied");},plan::set,()->{},null));
        coordinator.resume(instances.findById(instance.getId()),operations.findById(op.getId()),value->{throw new AssertionError("completed recovery reapplied");});
        assertEquals(1,applyCount[0]);assertEquals(1,ackCount[0]);assertEquals(1,operations.listByInstance(instance.getId()).size());
    }
    @Test public void booleanNfsDeleteCommitsWithActualVerifiedReceiptAndRemainingNestedPaths() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,false,null,false);
    }
    @Test public void booleanSmbDeletePreservesResultAndRemainingBindings() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.SMB,false,null,false);
    }
    @Test public void booleanLastBoundDeleteCommitsWithEmptyReceipt() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,true,null,false);
    }
    @Test public void booleanDeleteRejectsWrongOperationReceipt() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,false,"operation",false);
    }
    @Test public void booleanDeleteRejectsWrongVolumeReceipt() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,false,"volume",false);
    }
    @Test public void booleanDeleteRejectsChangedInodeReceipt() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,false,"inode",false);
    }
    @Test public void booleanDeleteRejectsMissingReceiptAndResumesSameDurableOperation() throws Exception {
        booleanDeleteRoundtrip(StorageServiceInstance.Protocol.NFS,false,"missing",true);
    }

    @Test public void fixedIdentityActualCommandCoordinatorDaoRoundtripKeepsAclAndLegacyFields() throws Exception {
        var row=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
                "fixed","/export/fixed",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,"{\"keep\":true,\"createMask\":\"0640\"}"));
        final long id=row.getId();
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd();
        field(cmd,cmd.getClass(),"id",id);field(cmd,cmd.getClass(),"fileIdentityMode","FIXED");
        field(cmd,cmd.getClass(),"fixedUid",7001L);field(cmd,cmd.getClass(),"fixedGid",7002L);
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        String result=coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,String.class,()->{
            var staged=plan.get().dao(StorageFileShareDao.class).findById(id);
            staged.setConfigJson(StorageSmbFileIdentity.merge(staged.getConfigJson(),StorageSmbFileIdentity.fields(StorageServiceConfigurationCoordinator.publicRequest(cmd))));
            plan.get().dao(StorageFileShareDao.class).update(id,staged);return staged.getUuid();
        },plan::set,()->{},null);
        var saved=com.google.gson.JsonParser.parseString(shares.findById(id).getConfigJson()).getAsJsonObject();
        assertEquals(7001,saved.getAsJsonObject("fileIdentity").get("uid").getAsInt());assertTrue(saved.get("keep").getAsBoolean());
        assertEquals("0640",saved.get("createMask").getAsString());
        assertEquals("COMMITTED",operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()).getState());
        assertEquals(result,coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.SMB,String.class,
                ()->{throw new AssertionError("duplicate fixed policy");},plan::set,()->{},null));
    }

    @Test public void clientSourcePolicyActualCoordinatorDaoRoundtripPreservesLargeExistingConfiguration() throws Exception {
        String original="{\"keep\":\""+ "보존".repeat(2400)+"\",\"fileIdentity\":{\"version\":1,\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002},\"directoryMode\":\"0770\"}";
        var row=shares.persist(new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,
                "source-policy","/export/source-policy",77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,original));
        final long id=row.getId();
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd();
        field(cmd,cmd.getClass(),"id",id);field(cmd,cmd.getClass(),"clientCidrs","[\"10.10.254.224\",\"10.10.13.22/24\"]");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        String expected=StorageSmbClientAccessPolicy.merge(original,StorageSmbClientAccessPolicy.fields(StorageServiceConfigurationCoordinator.publicRequest(cmd)));
        String result=coordinator.execute(cmd,instance,StorageServiceInstance.Protocol.SMB,String.class,()->{
            var staged=plan.get().dao(StorageFileShareDao.class).findById(id);
            staged.setConfigJson(StorageSmbClientAccessPolicy.merge(staged.getConfigJson(),StorageSmbClientAccessPolicy.fields(StorageServiceConfigurationCoordinator.publicRequest(cmd))));
            plan.get().dao(StorageFileShareDao.class).update(id,staged);return staged.getUuid();
        },plan::set,()->{},null);
        assertEquals(expected,shares.findById(id).getConfigJson());
        var saved=com.google.gson.JsonParser.parseString(shares.findById(id).getConfigJson()).getAsJsonObject();
        assertEquals(7001,saved.getAsJsonObject("fileIdentity").get("uid").getAsInt());
        assertEquals("0770",saved.get("directoryMode").getAsString());
        assertEquals("COMMITTED",operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()).getState());
        assertEquals(result,coordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.SMB,String.class,
                ()->{throw new AssertionError("duplicate source policy");},plan::set,()->{},null));
        assertEquals(1,operations.listByInstance(instance.getId()).size());
    }


    /** Actual SystemVM response + actual SQL DAOs; RPC/request identities alone are remapped. */
    private void nfsClientBindingRoundtrip(String invalid, boolean recover) throws Exception {
        instance.setVmId(211L);instances.update(instance.getId(),instance);
        JsonObject fixture;
        try(java.io.InputStream in=getClass().getResourceAsStream("/storage-config/storage-1460-bound-client-verified.json")) {
            assertNotNull(in);fixture=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject nativeResponse=fixture.getAsJsonObject("response");
        JsonObject before=fixture.getAsJsonObject("request").getAsJsonObject("before").getAsJsonObject("nfs export apply").deepCopy();
        JsonObject after=fixture.getAsJsonObject("request").getAsJsonObject("candidate").getAsJsonObject("nfs export apply").deepCopy();
        var rules=new org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDaoImpl();
        java.util.Map<String,Long> ids=new java.util.HashMap<>();
        for(com.google.gson.JsonElement element:before.getAsJsonArray("exports")) {
            JsonObject item=element.getAsJsonObject();
            StorageFileShareVO row=new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.NFS,
                    item.get("name").getAsString(),item.get("path").getAsString(),77L,"xfs",null,
                    StorageServiceInstance.ResourceState.Ready,item.get("config").toString());
            field(row,StorageFileShareVO.class,"uuid",item.get("uuid").getAsString());
            row=shares.persist(row);ids.put(row.getUuid(),row.getId());
        }
        JsonObject oldRule=before.getAsJsonArray("exports").get(0).getAsJsonObject().getAsJsonArray("acls").get(0).getAsJsonObject();
        StorageAccessRuleVO rule=new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,
                ids.get(before.getAsJsonArray("exports").get(0).getAsJsonObject().get("uuid").getAsString()),
                StorageServiceInstance.PrincipalType.CIDR,oldRule.get("principal").getAsString(),
                StorageServiceInstance.Permission.READ_WRITE,StorageServiceInstance.ResourceState.Ready,oldRule.get("config").toString());
        field(rule,StorageAccessRuleVO.class,"uuid",oldRule.get("uuid").getAsString());rule=rules.persist(rule);
        final long ruleId=rule.getId();
        for(JsonObject payload:new JsonObject[]{before,after}) {
            payload.addProperty("instanceId",instance.getId());payload.addProperty("instanceUuid",instance.getUuid());
            payload.remove("configurationOperationId");payload.remove("posixIntent");
            for(com.google.gson.JsonElement element:payload.getAsJsonArray("exports")) {
                JsonObject row=element.getAsJsonObject();row.addProperty("id",ids.get(row.get("uuid").getAsString()));row.addProperty("volumeId",77);
                for(com.google.gson.JsonElement acl:row.getAsJsonArray("acls"))acl.getAsJsonObject().addProperty("id",ruleId);
            }
        }
        java.util.Map<Long,String> configs=new java.util.HashMap<>();
        for(Long id:ids.values())configs.put(id,shares.findById(id).getConfigJson());
        var preflight=mock(StorageServiceConfigurationPreflight.class);
        JsonObject report=new JsonObject();report.addProperty("status","READY");report.addProperty("fingerprint","fixture");
        report.add("checks",new com.google.gson.JsonArray());report.add("intent",new JsonObject());
        JsonObject profile=new JsonObject();profile.addProperty("observationseconds",60);report.add("profile",profile);
        org.mockito.Mockito.when(preflight.observe(any(),any(),any())).thenReturn(report);
        var currentCoordinator=new StorageServiceConfigurationCoordinator(instances,operations,mock(StorageServiceProtocolDao.class),
                shares,mock(StorageBlockTargetDao.class),rules,guest,preflight,mock(StorageServiceRuntimeUpgradeDao.class)).withDirectoryPolicies(policies);
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd();
        field(cmd,cmd.getClass(),"id",ruleId);field(cmd,cmd.getClass(),"principal","10.10.254.250/32");
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",3L);
        final int[] calls={0,0,0};final boolean[] good={invalid==null};
        org.mockito.Mockito.when(guest.dispatch(any())).thenAnswer(invocation->{
            StorageServiceGuestCommand request=invocation.getArgument(0);JsonObject result=new JsonObject();
            if(request.getOperation().equals("configuration capability show")) {
                for(String key:new String[]{"nfsPosixIntentVersion","configurationPreflightVersion","configurationTransactionVersion",
                        "nestedFileSharePathVersion","nestedFileSharePathProtectionVersion"})result.addProperty(key,1);
            } else {
                JsonObject input=com.google.gson.JsonParser.parseString(request.getPayload()).getAsJsonObject();
                result=nativeResponse.deepCopy();result.add("operationid",input.get("operationid"));
                result.addProperty("instanceUuid",instance.getUuid());result.addProperty("baseRevision",3);result.addProperty("targetRevision",4);
                result.getAsJsonObject("nfsClientPolicy").add("operationid",input.get("operationid"));
                if(request.getOperation().equals("configuration operation resume")){calls[1]++;good[0]=true;}
                JsonObject receipt=result.getAsJsonObject("pathbindings");receipt.add("operationid",input.get("operationid"));
                receipt.addProperty("instanceUuid",instance.getUuid());
                if(!good[0]) {
                    if("missing".equals(invalid))result.remove("pathbindings");
                    if("operation".equals(invalid))receipt.addProperty("operationid","foreign");
                    if("volume".equals(invalid))receipt.getAsJsonArray("bindings").get(0).getAsJsonObject().addProperty("volumeuuid","foreign");
                    if("inode".equals(invalid))receipt.getAsJsonArray("bindings").get(0).getAsJsonObject().getAsJsonObject("pathIdentity").addProperty("inode",99999);
                }
                if(request.getOperation().equals("configuration operation apply"))calls[0]++;
                if(request.getOperation().equals("configuration operation commit")){calls[2]++;result.addProperty("state","COMMITTED");}
            }
            return new StorageServiceGuestCommandResult(true,"actual-systemvm-nfs-client-receipt",result.toString());
        });
        java.util.function.Supplier<JsonObject> candidate=()->{
            var view=plan.get().dao(StorageAccessRuleDao.class);StorageAccessRuleVO updated=view.findById(ruleId);
            JsonObject acl=after.getAsJsonArray("exports").get(0).getAsJsonObject().getAsJsonArray("acls").get(0).getAsJsonObject();
            updated.setPrincipal(acl.get("principal").getAsString());updated.setConfigJson(acl.get("config").toString());view.update(ruleId,updated);
            plan.get().commands.put("nfs export apply",after.deepCopy());
            JsonObject response=new JsonObject();response.addProperty("id",updated.getUuid());return response;
        };
        if(invalid==null)currentCoordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,JsonObject.class,candidate,plan::set,
                ()->plan.get().commands.put("nfs export apply",before.deepCopy()),null);
        else {
            try {currentCoordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,JsonObject.class,candidate,plan::set,
                    ()->plan.get().commands.put("nfs export apply",before.deepCopy()),null);fail("bad bound receipt committed");}
            catch(com.cloud.exception.InvalidParameterValueException expected){assertTrue(expected.getMessage().startsWith("DIRECTORY_"));}
            assertEquals(oldRule.get("principal").getAsString(),rules.findById(ruleId).getPrincipal());
            assertEquals(3,instances.findById(instance.getId()).getConfigurationRevision());
            assertNotNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            if(!recover)return;
            currentCoordinator.resume(instances.findById(instance.getId()),operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId()),value->{});
            assertEquals(1,calls[1]);
        }
        assertEquals("10.10.254.250/32",rules.findById(ruleId).getPrincipal());
        for(Long id:ids.values())assertEquals(configs.get(id),shares.findById(id).getConfigJson());
        var operation=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
        assertEquals("COMMITTED",operation.getState());assertEquals(4,instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        currentCoordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.NFS,JsonObject.class,
                ()->{throw new AssertionError("duplicate ACL reapplied");},plan::set,()->{},null);
        assertEquals(1,calls[0]);assertEquals(1,calls[2]);
        assertEquals(1,operations.listByInstance(instance.getId()).size());
    }
    @Test public void actualNfsClientReceiptCommitsLegacyAclAndBoundSiblingThroughRealDaos() throws Exception {nfsClientBindingRoundtrip(null,false);}
    @Test public void actualNfsClientReceiptMissingStillBlocksSqlCommit() throws Exception {nfsClientBindingRoundtrip("missing",false);}
    @Test public void actualNfsClientReceiptForeignOperationStillBlocksSqlCommit() throws Exception {nfsClientBindingRoundtrip("operation",false);}
    @Test public void actualNfsClientReceiptWrongVolumeStillBlocksSqlCommit() throws Exception {nfsClientBindingRoundtrip("volume",false);}
    @Test public void actualNfsClientReceiptWrongInodeStillBlocksSqlCommit() throws Exception {nfsClientBindingRoundtrip("inode",false);}
    @Test public void actualNfsClientVerifiedRecoveryRevalidatesReceiptWithoutSecondApply() throws Exception {nfsClientBindingRoundtrip("missing",true);}


    /** Real native preflight->VERIFIED result, real admission/coordinator and real SQL state. */
    private void onlineNfsDeleteNativeRoundtrip(boolean staleAck) throws Exception {
        JsonObject fixture;
        try(var in=getClass().getResourceAsStream("/storage-config/storage-1460-online-native.json")) {
            assertNotNull(in);fixture=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject nativeRequest=fixture.getAsJsonObject("request");
        JsonObject nativeQuery=nativeRequest.getAsJsonObject("preflight");
        JsonObject nativeReply=fixture.getAsJsonObject("response");
        instance=new StorageServiceInstanceVO("native-online-coordinator-test",null,1,2,13,1L,"SYSTEM_VM");
        field(instance,StorageServiceInstanceVO.class,"uuid",nativeRequest.get("instanceUuid").getAsString());
        instance.setVmId(211L);instance.setConfigurationRevision(0);instance=instances.persist(instance);
        JsonObject before=nativeRequest.getAsJsonObject("before").getAsJsonObject("nfs export apply");
        JsonObject after=nativeRequest.getAsJsonObject("candidate").getAsJsonObject("nfs export apply");
        var realRules=new org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDaoImpl();
        Long selectedRuleId=null;
        for(com.google.gson.JsonElement element:before.getAsJsonArray("exports")) {
            JsonObject item=element.getAsJsonObject();
            var row=new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.NFS,item.get("name").getAsString(),
                    item.get("path").getAsString(),77L,"xfs",null,StorageServiceInstance.ResourceState.Ready,item.get("config").toString());
            field(row,StorageFileShareVO.class,"uuid",item.get("uuid").getAsString());row=shares.persist(row);
            for(com.google.gson.JsonElement acl:item.getAsJsonArray("acls")) {
                JsonObject a=acl.getAsJsonObject();
                var rule=new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,row.getId(),
                        StorageServiceInstance.PrincipalType.CIDR,a.get("principal").getAsString(),
                        StorageServiceInstance.Permission.valueOf(a.get("permission").getAsString()),
                        StorageServiceInstance.ResourceState.Ready,a.get("config").toString());
                field(rule,StorageAccessRuleVO.class,"uuid",a.get("uuid").getAsString());rule=realRules.persist(rule);
                if(rule.getUuid().equals(nativeQuery.getAsJsonObject("nfsclientpolicy").get("ruleuuid").getAsString()))selectedRuleId=rule.getId();
            }
        }
        assertNotNull(selectedRuleId);final long ruleId=selectedRuleId;
        var protocolDao=mock(StorageServiceProtocolDao.class);
        java.util.List<StorageServiceProtocolVO> listeners=new java.util.ArrayList<>();
        for(com.google.gson.JsonElement number:nativeQuery.getAsJsonArray("ports")) {
            var listener=new StorageServiceProtocolVO(instance.getId(),StorageServiceInstance.Protocol.NFS,true,null,number.getAsInt());
            field(listener,StorageServiceProtocolVO.class,"id",1000L+number.getAsInt());
            listener.setConfigJson("{}");listeners.add(listener);
        }
        org.mockito.Mockito.when(protocolDao.listByInstanceId(instance.getId())).thenReturn(listeners);
        var volumeDao=mock(com.cloud.storage.dao.VolumeDao.class);
        var volume=mock(com.cloud.storage.VolumeVO.class);
        org.mockito.Mockito.when(volume.getUuid()).thenReturn(nativeQuery.getAsJsonArray("volumeuuids").get(0).getAsString());
        org.mockito.Mockito.when(volumeDao.findById(77L)).thenReturn(volume);
        var runtimeUpgrades=mock(StorageServiceRuntimeUpgradeDao.class);
        var admission=new StorageServiceConfigurationPreflight(protocolDao,shares,mock(StorageBlockTargetDao.class),realRules,
                volumeDao,runtimeUpgrades,guest,v->{});
        var exactCoordinator=new StorageServiceConfigurationCoordinator(instances,operations,protocolDao,shares,
                mock(StorageBlockTargetDao.class),realRules,guest,admission,runtimeUpgrades).withDirectoryPolicies(policies);
        final int[] calls={0,0};
        org.mockito.Mockito.when(guest.dispatch(any())).thenAnswer(invocation->{
            StorageServiceGuestCommand request=invocation.getArgument(0);
            JsonObject result;
            if(request.getOperation().equals("configuration capability show")) {
                result=new JsonObject();
                for(String key:new String[]{"configurationPreflightVersion","configurationTransactionVersion","nfsPosixIntentVersion",
                        "nestedFileSharePathVersion","nestedFileSharePathProtectionVersion","nfsClientPolicyVersion","nfsClientPolicyPreflightVersion"})
                    result.addProperty(key,1);
            } else {
                JsonObject input=com.google.gson.JsonParser.parseString(request.getPayload()).getAsJsonObject();
                if(request.getOperation().equals("configuration preflight show")) {
                    for(String key:new String[]{"instanceUuid","revision","protocol","action","resourceids","resourcesdigest","resourcecount",
                            "drainrequired","recursive","nfsclientpolicy"})
                        assertEquals("actual UI/API planning query "+key,nativeQuery.get(key),input.get(key));
                    result=fixture.getAsJsonObject("preflight").deepCopy();
                } else {
                    result=nativeReply.deepCopy();result.add("operationid",input.get("operationid"));
                    result.getAsJsonObject("nfsClientPolicy").add("operationid",input.get("operationid"));
                    result.getAsJsonObject("pathbindings").add("operationid",input.get("operationid"));
                    if(request.getOperation().equals("configuration operation apply")) {
                        calls[0]++;assertTrue(input.get("requireNfsClientPolicy").getAsBoolean());
                        assertEquals(fixture.getAsJsonObject("preflight").get("fingerprint"),
                                input.getAsJsonObject("preflight").get("expectedFingerprint"));
                    }
                    if(request.getOperation().equals("configuration operation commit")){calls[1]++;result.addProperty("state","COMMITTED");}
                }
            }
            return new StorageServiceGuestCommandResult(true,"actual-online-native-fixture",result.toString());
        });
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsAclCmd();
        field(cmd,cmd.getClass(),"id",ruleId);
        field(cmd,BaseStorageServiceConfigurationCmd.class,"expectedRevision",0L);
        field(cmd,BaseStorageServiceConfigurationCmd.class,"impactAck",staleAck?"stale":fixture.getAsJsonObject("preflight").get("fingerprint").getAsString());
        java.util.function.Supplier<Boolean> candidate=()->{
            plan.get().dao(StorageAccessRuleDao.class).remove(ruleId);
            plan.get().commands.put("nfs export apply",after.deepCopy());return true;
        };
        if(staleAck) {
            try {exactCoordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,Boolean.class,candidate,plan::set,
                    ()->plan.get().commands.put("nfs export apply",before.deepCopy()),null);fail("stale UI approval accepted");}
            catch(StorageServiceConfigurationPreflight.Rejected expected) { }
            assertNotNull(realRules.findById(ruleId));assertEquals(0,calls[0]);
            assertEquals(0,instances.findById(instance.getId()).getConfigurationRevision());
        } else {
            assertTrue(exactCoordinator.execute(cmd,instance,StorageServiceInstance.Protocol.NFS,Boolean.class,candidate,plan::set,
                    ()->plan.get().commands.put("nfs export apply",before.deepCopy()),null));
            assertNull(realRules.findById(ruleId));assertEquals(1,calls[0]);assertEquals(1,calls[1]);
            var op=operations.findByRequest(instance.getId(),cmd.getConfigurationRequestId());
            assertEquals("COMMITTED",op.getState());assertEquals(1,instances.findById(instance.getId()).getConfigurationRevision());
            assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            exactCoordinator.execute(cmd,instances.findById(instance.getId()),StorageServiceInstance.Protocol.NFS,Boolean.class,
                    ()->{throw new AssertionError("duplicate online delete reapplied");},plan::set,()->{},null);
            assertEquals(1,calls[0]);
        }
    }
    @Test public void nativeOnlinePreflightAndActualGuestReplyCommitAclDeleteThroughRealDaos() throws Exception {onlineNfsDeleteNativeRoundtrip(false);}
    @Test public void nativeOnlinePreflightStillRejectsStaleUiFingerprintBeforeGuestApply() throws Exception {onlineNfsDeleteNativeRoundtrip(true);}

}
