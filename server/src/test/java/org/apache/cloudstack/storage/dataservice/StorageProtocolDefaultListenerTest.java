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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiTargetCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageProtocolDefaultListenerTest {
    private static final long INSTANCE_ID = 77L;

    private static class Manager extends StorageServiceManagerImpl {
        private StorageServiceInstanceVO fixture;
        private int backingPreparations;

        @Override
        protected <T> T executeDesiredChange(BaseCmd cmd, Class<T> response, Supplier<T> change) {
            // Test the real doCreate body; checkpoint orchestration is unchanged
            // and outside this port producer fixture.
            return change.get();
        }

        @Override
        protected StorageServiceInstanceVO requireInstance(Long id) {
            return fixture;
        }

        @Override
        protected void validateStorageServiceBackingVolume(StorageServiceInstanceVO instance, Long id, String resource) {
            // A compatible own RAW resource is a read-only prerequisite here.
        }

        @Override
        protected void validateIscsiBackingVolumeAvailable(StorageServiceInstanceVO instance, Long id, Long excluded) {
        }

        @Override
        protected VolumeVO prepareIscsiBackingVolume(StorageServiceInstanceVO instance, Long id) {
            backingPreparations++;
            return null;
        }
    }

    private static class Fixture {
        private final Manager manager = new Manager();
        private final StorageServiceProtocolDao dao = Mockito.mock(StorageServiceProtocolDao.class);
        private final StorageBlockTargetDao targets = Mockito.mock(StorageBlockTargetDao.class);
        private final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        private final Map<StorageServiceInstance.Protocol, List<StorageServiceProtocolVO>> rows =
                new EnumMap<>(StorageServiceInstance.Protocol.class);
        private int inserts;
        private long nextId = 1;

        Fixture() {
            Mockito.when(instance.getId()).thenReturn(INSTANCE_ID);
            Mockito.when(instance.getUuid()).thenReturn("3480bb2c-99ee-42f2-91cf-715ded5dd35f");
            manager.fixture = instance;
            ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", dao);
            ReflectionTestUtils.setField(manager, "storageBlockTargetDao", targets);
            Mockito.when(dao.listByInstanceIdAndProtocol(Mockito.eq(INSTANCE_ID), Mockito.any())).thenAnswer(call ->
                    new ArrayList<>(list(call.getArgument(1))));
            Mockito.when(dao.findByInstanceIdAndProtocol(Mockito.eq(INSTANCE_ID), Mockito.any())).thenAnswer(call -> {
                List<StorageServiceProtocolVO> values = list(call.getArgument(1));
                return values.isEmpty() ? null : values.get(0);
            });
            Mockito.when(dao.persist(Mockito.any())).thenAnswer(call -> {
                StorageServiceProtocolVO row = call.getArgument(0);
                ReflectionTestUtils.setField(row, "id", nextId++);
                list(row.getProtocol()).add(row);inserts++;
                return row;
            });
            Mockito.when(dao.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);
        }

        List<StorageServiceProtocolVO> list(StorageServiceInstance.Protocol protocol) {
            return rows.computeIfAbsent(protocol, key -> new ArrayList<>());
        }

        StorageServiceProtocolVO add(StorageServiceInstance.Protocol protocol, boolean enabled, String ip, Integer port) {
            StorageServiceProtocolVO row = new StorageServiceProtocolVO(INSTANCE_ID, protocol, enabled, ip, port);
            ReflectionTestUtils.setField(row, "id", nextId++);list(protocol).add(row);
            return row;
        }
    }

    @Test
    public void newEndpointRowsUseTheirOwnProtocolDefaults() {
        StorageServiceInstance.Protocol[] protocols = {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB,
                StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF};
        int[] ports = {2049, 445, 3260, 4420};
        for (int i = 0; i < protocols.length; i++) {
            Fixture fixture = new Fixture();fixture.manager.ensureProtocol(fixture.instance, protocols[i]);
            Assert.assertEquals(1, fixture.inserts);
            StorageServiceProtocolVO persisted = fixture.list(protocols[i]).get(0);
            Assert.assertEquals(Integer.valueOf(ports[i]), persisted.getPort());
            Assert.assertTrue(persisted.isEnabled());
            Assert.assertEquals(StorageServiceInstance.ResourceState.Ready, persisted.getState());
            if (protocols[i] == StorageServiceInstance.Protocol.ISCSI || protocols[i] == StorageServiceInstance.Protocol.NVME_OF) {
                JsonArray listeners = fixture.manager.buildBlockProtocolPayload(fixture.instance, protocols[i]).getAsJsonArray("listeners");
                Assert.assertEquals(1, listeners.size());
                Assert.assertEquals(ports[i], listeners.get(0).getAsJsonObject().get("port").getAsInt());
            }
        }
    }

    @Test
    public void existingCustomBlockRowsAreNotRewrittenWhenDefaultEndpointIsAdded() {
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[]{
                StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF}) {
            Fixture fixture = new Fixture();int customPort = protocol == StorageServiceInstance.Protocol.ISCSI ? 3261 : 4421;
            StorageServiceProtocolVO custom = fixture.add(protocol, true, "127.0.0.9", customPort);
            fixture.manager.ensureProtocol(fixture.instance, protocol);
            Assert.assertEquals(customPort, custom.getPort().intValue());Assert.assertEquals("127.0.0.9", custom.getListenIp());
            Assert.assertTrue(custom.isEnabled());Assert.assertEquals(1, fixture.inserts);
            Assert.assertEquals(2, fixture.list(protocol).size());
            Mockito.verify(fixture.dao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        }
    }

    @Test
    public void existingDefaultNullPortIsReusedAndNormalizesOnlyInPayload() {
        Fixture fixture = new Fixture();StorageServiceProtocolVO existing = fixture.add(StorageServiceInstance.Protocol.ISCSI, true, null, null);
        fixture.manager.ensureProtocol(fixture.instance, StorageServiceInstance.Protocol.ISCSI);
        Assert.assertEquals(0, fixture.inserts);Assert.assertNull(existing.getPort());
        JsonObject payload = fixture.manager.buildBlockProtocolPayload(fixture.instance, StorageServiceInstance.Protocol.ISCSI);
        Assert.assertEquals(3260, payload.getAsJsonArray("listeners").get(0).getAsJsonObject().get("port").getAsInt());
        Mockito.verify(fixture.dao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
    }

    @Test
    public void smbEnabledCustomRowAndDisabledOldestRowKeepExistingBehavior() {
        Fixture enabled = new Fixture();StorageServiceProtocolVO custom = enabled.add(StorageServiceInstance.Protocol.SMB, true, "127.0.0.9", 1445);
        enabled.manager.ensureProtocol(enabled.instance, StorageServiceInstance.Protocol.SMB);
        Assert.assertEquals(0, enabled.inserts);Assert.assertEquals(1445, custom.getPort().intValue());
        Mockito.verify(enabled.dao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        Fixture disabled = new Fixture();StorageServiceProtocolVO oldest = disabled.add(StorageServiceInstance.Protocol.SMB, false, "127.0.0.8", 2445);
        disabled.add(StorageServiceInstance.Protocol.SMB, false, "127.0.0.7", 3445);
        disabled.manager.ensureProtocol(disabled.instance, StorageServiceInstance.Protocol.SMB);
        Assert.assertEquals(0, disabled.inserts);Assert.assertTrue(oldest.isEnabled());
        Assert.assertEquals(2445, oldest.getPort().intValue());Assert.assertEquals("127.0.0.8", oldest.getListenIp());
        Mockito.verify(disabled.dao).update(oldest.getId(), oldest);
    }

    @Test
    public void wrongRequestedPortIsRejectedBeforeTargetPreparationAndPersistence() {
        Fixture fixture = new Fixture();CreateStorageIscsiTargetCmd cmd = Mockito.mock(CreateStorageIscsiTargetCmd.class);
        Mockito.when(cmd.getInstanceId()).thenReturn(INSTANCE_ID);Mockito.when(cmd.getEndpointMode()).thenReturn("LISTENER_GROUP");
        Mockito.when(cmd.getListenerPorts()).thenReturn("3261");Mockito.when(cmd.getBackstoreType()).thenReturn("BLOCK");
        try {
            fixture.manager.createStorageIscsiTarget(cmd);
            Assert.fail("Nonexistent listener group was accepted");
        } catch (InvalidParameterValueException expected) {
            Assert.assertTrue(expected.getMessage().contains("listener port group"));
        }
        Assert.assertEquals(0, fixture.inserts);Assert.assertEquals(0, fixture.manager.backingPreparations);
        Mockito.verify(fixture.targets, Mockito.never()).persist(Mockito.any());
    }

    private Path actualCli() {
        String explicit = System.getProperty("cloudstack.storage.iscsi.listener.cli");
        if (explicit != null) return new File(explicit).toPath();
        Path directory = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("systemvm/debian/usr/local/bin/ablestack-storagectl");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new AssertionError("Pinned native CLI is unavailable");
    }

    private JsonObject actualNativeSelection(JsonObject payload, JsonObject target) throws Exception {
        String script = String.join("\n", "import ast,json,sys", "from pathlib import Path",
                "request=json.load(sys.stdin)", "text=Path(sys.argv[1]).read_text()",
                "begin=text.index('apply_iscsi_targets() {');function=text[begin:text.index('apply_nvmeof_subsystems() {',begin)]",
                "code=function[function.index(\"<<'PY'\\n\")+len(\"<<'PY'\\n\"):function.rindex('\\nPY')]",
                "names={'configured_listeners','target_listener_ports','selected_listeners_for_target'}",
                "nodes=[n for n in ast.parse(code).body if isinstance(n,ast.FunctionDef) and n.name in names]",
                "assert len(nodes)==3", "scope={'payload':request['payload']}",
                "exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-c265-listener-selection>','exec'),scope)",
                "try:", " selected=scope['selected_listeners_for_target'](request['target'],scope['configured_listeners']())",
                " print(json.dumps({'accepted':True,'ports':[r['port']for r in selected]}))",
                "except SystemExit:", " print(json.dumps({'accepted':False,'error':'LISTENER_GROUP_UNAVAILABLE'}))");
        JsonObject request = new JsonObject();request.add("payload", payload);request.add("target", target);
        Process process = new ProcessBuilder("python3", "-c", script, actualCli().toString()).start();
        try (java.io.OutputStream input = process.getOutputStream()) {
            input.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {process.destroyForcibly();Assert.fail("Native selection deadline exceeded");}
        byte[] output = process.getInputStream().readAllBytes();byte[] errors = process.getErrorStream().readAllBytes();
        Assert.assertEquals("Actual native selection fixture failed", 0, process.exitValue());
        Assert.assertEquals("Actual native selection emitted unexpected stderr", 0, errors.length);
        return JsonParser.parseString(new String(output, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    public void actualManagerPersistedDefaultPayloadMatchesPinnedNativeSelection() throws Exception {
        Fixture fixture = new Fixture();fixture.manager.validateIscsiListenerPortsExist(fixture.instance, "3260");
        fixture.manager.ensureProtocol(fixture.instance, StorageServiceInstance.Protocol.ISCSI);
        JsonObject payload = fixture.manager.buildBlockProtocolPayload(fixture.instance, StorageServiceInstance.Protocol.ISCSI);
        JsonObject target = new JsonObject();target.addProperty("targetName", "iqn.2026-05.local.storage:fixture");
        target.add("config", JsonParser.parseString(fixture.manager.buildIscsiTargetConfigJson(null, null, "BLOCK", null,
                "LISTENER_GROUP", "3260")).getAsJsonObject());
        JsonObject result = actualNativeSelection(payload, target);Assert.assertTrue(result.get("accepted").getAsBoolean());
        Assert.assertEquals(3260, result.getAsJsonArray("ports").get(0).getAsInt());
        JsonObject wrongPayload = payload.deepCopy();wrongPayload.getAsJsonArray("listeners").get(0).getAsJsonObject().addProperty("port", 2049);
        Assert.assertFalse(actualNativeSelection(wrongPayload, target).get("accepted").getAsBoolean());
        JsonObject missingGroup = target.deepCopy();JsonArray missing = new JsonArray();missing.add(3261);
        missingGroup.getAsJsonObject("config").add("listenerGroupPorts", missing);
        Assert.assertFalse(actualNativeSelection(payload, missingGroup).get("accepted").getAsBoolean());
    }
    private static class PermissionManager extends StorageServiceManagerImpl {
        private final Map<Long, StorageBlockTargetVO> targetRows = new java.util.HashMap<>();
        private final Map<Long, StorageAccessRuleVO> aclRows = new java.util.HashMap<>();
        private StorageServiceInstanceVO instance;
        private int applied;

        @Override
        protected <T> T executeDesiredChange(BaseCmd cmd, Class<T> response, Supplier<T> change) {
            return change.get(); // Outer checkpoint is unchanged and outside this fixture.
        }
        @Override
        protected StorageBlockTargetVO requireBlockTarget(Long id, StorageServiceInstance.Protocol protocol) {
            return targetRows.get(id);
        }
        @Override
        protected StorageAccessRuleVO requireBlockAcl(Long id, StorageServiceInstance.Protocol protocol) {
            return aclRows.get(id);
        }
        @Override
        protected StorageServiceInstanceVO requireInstance(Long id) {
            return instance;
        }
        @Override
        protected void applyIscsiDesiredState(StorageServiceInstanceVO instance, Map<Long, JsonObject> secrets) {
            applied++;
        }
    }

    private static class PermissionFixture {
        private final PermissionManager manager = new PermissionManager();
        private final org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao acls =
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);
        private final StorageBlockTargetDao targets = Mockito.mock(StorageBlockTargetDao.class);
        private final Map<Long, List<StorageAccessRuleVO>> rules = new java.util.HashMap<>();
        private final StorageBlockTargetVO first;
        private final StorageBlockTargetVO second;
        private final String iqn = "iqn.2026-10.local.storage:permissions";
        private final String c1 = "iqn.2026-10.example.client:one";
        private final String c2 = "iqn.2026-10.example.client:two";

        PermissionFixture() {
            manager.instance = Mockito.mock(StorageServiceInstanceVO.class);
            Mockito.when(manager.instance.getId()).thenReturn(INSTANCE_ID);
            first = new StorageBlockTargetVO(INSTANCE_ID, StorageServiceInstance.Protocol.ISCSI, iqn, "0", 1L,
                    StorageServiceInstance.ResourceState.Ready, "{}");
            second = new StorageBlockTargetVO(INSTANCE_ID, StorageServiceInstance.Protocol.ISCSI, iqn, "1", 2L,
                    StorageServiceInstance.ResourceState.Ready, "{}");
            ReflectionTestUtils.setField(first, "id", 101L);
            ReflectionTestUtils.setField(second, "id", 102L);
            manager.targetRows.put(101L, first);
            manager.targetRows.put(102L, second);
            ReflectionTestUtils.setField(manager, "storageBlockTargetDao", targets);
            ReflectionTestUtils.setField(manager, "storageAccessRuleDao", acls);
            Mockito.when(targets.listByInstanceIdAndProtocol(INSTANCE_ID, StorageServiceInstance.Protocol.ISCSI))
                    .thenReturn(java.util.Arrays.asList(first, second));
            Mockito.when(acls.listByResource(Mockito.eq(StorageServiceInstance.AccessResourceType.BLOCK_TARGET), Mockito.anyLong()))
                    .thenAnswer(call -> rules.getOrDefault(call.getArgument(1), java.util.Collections.emptyList()));
        }

        StorageAccessRuleVO add(long id, long target, String principal, StorageServiceInstance.Permission permission,
                StorageServiceInstance.ResourceState state) {
            StorageAccessRuleVO row = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target,
                    StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN, principal, permission, state, "{}");
            ReflectionTestUtils.setField(row, "id", id);
            manager.aclRows.put(id, row);
            rules.computeIfAbsent(target, key -> new ArrayList<>()).add(row);
            return row;
        }
    }

    @Test
    public void wholeIqnActualManagerPayloadEnforcesRwAndRoOnEveryLun() throws Exception {
        PermissionFixture f = new PermissionFixture();
        f.add(201, 101, f.c1, StorageServiceInstance.Permission.READ_WRITE, StorageServiceInstance.ResourceState.Ready);
        f.add(202, 102, f.c2, StorageServiceInstance.Permission.READ_ONLY, StorageServiceInstance.ResourceState.Ready);
        JsonArray values = new JsonArray();
        for (StorageBlockTargetVO row : java.util.Arrays.asList(f.first, f.second)) {
            JsonObject target = new JsonObject();
            target.addProperty("uuid", row.getUuid());
            target.addProperty("targetName", row.getTargetName());
            target.addProperty("lunOrNamespace", row.getLunOrNamespace());
            target.addProperty("volumeUuid", "fixture-serial");
            target.addProperty("volumeName", "fixture-serial");
            target.addProperty("volumeSizeBytes", 65536);
            target.add("acls", f.manager.createIscsiTargetAclJson(row, null));
            Assert.assertEquals(2, target.getAsJsonArray("acls").size());
            values.add(target);
        }
        JsonObject request = new JsonObject();
        request.add("targets", values);
        String explicit = System.getProperty("cloudstack.storage.iscsi.permission.fixture");
        Path fixture = explicit == null ? actualCli().getParent().getParent().getParent().getParent().getParent().resolve("test/TestStorageIscsiLunPermissions.py")
                : new File(explicit).toPath();
        Process process = new ProcessBuilder("python3", fixture.toString(), "--manager-payload").start();
        try (java.io.OutputStream input = process.getOutputStream()) {
            input.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            Assert.fail("Manager permission producer exceeded its bounded deadline");
        }
        byte[] output = process.getInputStream().readAllBytes();
        byte[] errors = process.getErrorStream().readAllBytes();
        Assert.assertEquals(0, process.exitValue());
        Assert.assertEquals(0, errors.length);
        JsonObject result = JsonParser.parseString(new String(output, StandardCharsets.UTF_8)).getAsJsonObject();
        Assert.assertTrue(result.get("success").getAsBoolean());
        Assert.assertEquals(4, result.getAsJsonArray("mappings").size());
        for (com.google.gson.JsonElement element : result.getAsJsonArray("mappings")) {
            JsonObject mapping = element.getAsJsonObject();
            Assert.assertEquals(f.c2.equals(mapping.get("principal").getAsString()) ? 1 : 0,
                    mapping.get("writeProtect").getAsInt());
        }
    }

    @Test
    public void conflictingPermissionSerializerAndCreateRejectBeforePersistenceOrApply() {
        PermissionFixture f = new PermissionFixture();
        f.add(201, 101, f.c1, StorageServiceInstance.Permission.READ_ONLY, StorageServiceInstance.ResourceState.Ready);
        f.add(202, 102, f.c1, StorageServiceInstance.Permission.READ_WRITE, StorageServiceInstance.ResourceState.Ready);
        try {
            f.manager.createIscsiTargetAclJson(f.first, null);
            Assert.fail("Conflicting merged permission was accepted");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("permissions"));
        }
        org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd command =
                Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd.class);
        Mockito.when(command.getTargetId()).thenReturn(102L);
        Mockito.when(command.getInitiatorIqn()).thenReturn(f.c1);
        Mockito.when(command.getPermission()).thenReturn("READ_WRITE");
        try {
            f.manager.createStorageIscsiAcl(command);
            Assert.fail("Conflicting create was accepted");
        } catch (InvalidParameterValueException expected) {
            Assert.assertTrue(expected.getMessage().contains("permissions"));
        }
        Mockito.verify(f.acls, Mockito.never()).persist(Mockito.any());
        Assert.assertEquals(0, f.manager.applied);
    }

    @Test
    public void conflictingUpdateLeavesOriginalRuleAndDaoUnchanged() {
        PermissionFixture f = new PermissionFixture();
        f.add(201, 101, f.c1, StorageServiceInstance.Permission.READ_ONLY, StorageServiceInstance.ResourceState.Ready);
        StorageAccessRuleVO current = f.add(202, 102, f.c2, StorageServiceInstance.Permission.READ_ONLY, StorageServiceInstance.ResourceState.Ready);
        org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd command =
                Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd.class);
        Mockito.when(command.getId()).thenReturn(202L);
        Mockito.when(command.getInitiatorIqn()).thenReturn(f.c1);
        Mockito.when(command.getPermission()).thenReturn("READ_WRITE");
        try {
            f.manager.updateStorageIscsiAcl(command);
            Assert.fail("Conflicting update was accepted");
        } catch (InvalidParameterValueException expected) {
            Assert.assertTrue(expected.getMessage().contains("permissions"));
        }
        Assert.assertEquals(f.c2, current.getPrincipal());
        Assert.assertEquals(StorageServiceInstance.Permission.READ_ONLY, current.getPermission());
        Mockito.verify(f.acls, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        Assert.assertEquals(0, f.manager.applied);
    }

    @Test
    public void inactiveSiblingCannotDenyOrLeakAnActiveWholeIqnGrant() {
        PermissionFixture f = new PermissionFixture();
        f.add(201, 101, f.c1, StorageServiceInstance.Permission.READ_WRITE, StorageServiceInstance.ResourceState.Disabled);
        f.add(202, 102, f.c1, StorageServiceInstance.Permission.READ_ONLY, StorageServiceInstance.ResourceState.Ready);
        f.manager.validateIscsiAclPermissionScope(f.second, null, f.c1, StorageServiceInstance.Permission.READ_ONLY);
        JsonArray result = f.manager.createIscsiTargetAclJson(f.first, null);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("READ_ONLY", result.get(0).getAsJsonObject().get("permission").getAsString());
        f.second.setState(StorageServiceInstance.ResourceState.Disabled);
        Assert.assertEquals(0, f.manager.createIscsiTargetAclJson(f.first, null).size());
    }

}
