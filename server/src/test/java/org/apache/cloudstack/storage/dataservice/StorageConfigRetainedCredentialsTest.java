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

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;

/** Exercises the actual KEEP credential bridge without domain CRUD or guest commands. */
public class StorageConfigRetainedCredentialsTest {
    private static final class Manager extends StorageServiceManagerImpl {
        @Override protected void requireConfigurationAdministrator() { }
    }

    private static final class Fixture implements AutoCloseable {
        final Manager manager = new Manager();
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final StorageAccessRuleVO rule = Mockito.mock(StorageAccessRuleVO.class);
        final StorageFileShareVO share = Mockito.mock(StorageFileShareVO.class);
        final StorageBlockTargetVO target = Mockito.mock(StorageBlockTargetVO.class);
        final StorageAccessRuleDao rules = Mockito.mock(StorageAccessRuleDao.class);
        final StorageFileShareDao shares = Mockito.mock(StorageFileShareDao.class);
        final StorageBlockTargetDao targets = Mockito.mock(StorageBlockTargetDao.class);
        final JsonObject desired = new JsonObject(), config = new JsonObject(), secrets = new JsonObject();
        final String protocol;

        Fixture(String value) {
            protocol = value;
            Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getVmId()).thenReturn(8L);
            Mockito.when(rule.getId()).thenReturn(20L);Mockito.when(rule.getUuid()).thenReturn("55555555-5555-4555-8555-555555555555");
            Mockito.when(rule.getResourceId()).thenReturn(10L);
            Mockito.when(rule.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);
            Mockito.when(rule.getPermission()).thenReturn(StorageServiceInstance.Permission.READ_WRITE);
            Mockito.when(share.getId()).thenReturn(10L);Mockito.when(share.getInstanceId()).thenReturn(7L);
            Mockito.when(share.getProtocol()).thenReturn(StorageServiceInstance.Protocol.SMB);
            Mockito.when(share.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);
            Mockito.when(target.getId()).thenReturn(10L);Mockito.when(target.getInstanceId()).thenReturn(7L);
            Mockito.when(target.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);
            StorageServiceInstance.AccessResourceType resourceType;
            StorageServiceInstance.PrincipalType principalType;
            String principal;
            if ("SMB".equals(protocol)) {
                resourceType = StorageServiceInstance.AccessResourceType.FILE_SHARE;
                principalType = StorageServiceInstance.PrincipalType.LOCAL_USER;principal = "alice";
                config.addProperty("localAccount", true);secrets.addProperty("password", "synthetic-retained-input");
            } else if ("ISCSI".equals(protocol)) {
                resourceType = StorageServiceInstance.AccessResourceType.BLOCK_TARGET;
                principalType = StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN;principal = "iqn.2026-10.local.test:client";
                Mockito.when(target.getProtocol()).thenReturn(StorageServiceInstance.Protocol.ISCSI);
                config.addProperty("chapEnabled", true);config.addProperty("chapUsername", "alice");config.addProperty("mutualChapEnabled", false);
                secrets.addProperty("chapsecret", "synthetic-retained-input");
            } else {
                resourceType = StorageServiceInstance.AccessResourceType.BLOCK_TARGET;
                principalType = StorageServiceInstance.PrincipalType.NVME_HOST_NQN;principal = "nqn.2026-10.local.test:client";
                Mockito.when(target.getProtocol()).thenReturn(StorageServiceInstance.Protocol.NVME_OF);
                config.addProperty("dhChapEnabled", true);config.addProperty("dhChapCtrlEnabled", false);
                secrets.addProperty("dhchapkey", "synthetic-retained-input");
            }
            Mockito.when(rule.getResourceType()).thenReturn(resourceType);Mockito.when(rule.getPrincipalType()).thenReturn(principalType);
            Mockito.when(rule.getPrincipal()).thenReturn(principal);Mockito.when(rule.getConfigJson()).thenAnswer(call -> config.toString());
            desired.addProperty("uuid", "33333333-3333-4333-8333-333333333333");
            desired.addProperty("resourceUuid", "22222222-2222-4222-8222-222222222222");
            desired.addProperty("resource_type", resourceType.name());desired.addProperty("principal_type", principalType.name());
            desired.addProperty("principal", principal);desired.addProperty("permission", "READ_WRITE");desired.addProperty("state", "Ready");
            desired.add("config", config.deepCopy());
            Mockito.when(rules.findById(20L)).thenReturn(rule);Mockito.when(shares.findById(10L)).thenReturn(share);
            Mockito.when(targets.findById(10L)).thenReturn(target);
            ReflectionTestUtils.setField(manager, "storageAccessRuleDao", rules);
            ReflectionTestUtils.setField(manager, "storageFileShareDao", shares);
            ReflectionTestUtils.setField(manager, "storageBlockTargetDao", targets);
            manager.beginConfigurationBatch(7L);
        }

        void retain() {manager.retainConfigurationRestoreCredentials(instance, 20L, 10L, protocol, desired, secrets);}

        @SuppressWarnings("unchecked")
        StorageServiceManagerImpl.ConfigurationBatch batch() {
            return ((ThreadLocal<StorageServiceManagerImpl.ConfigurationBatch>)ReflectionTestUtils.getField(manager, "configurationBatch")).get();
        }

        void assertEmpty() {
            Assert.assertTrue(batch().smbCredentials.isEmpty());Assert.assertTrue(batch().iscsiCredentials.isEmpty());
            Assert.assertTrue(batch().nvmeCredentials.isEmpty());
        }

        void assertNoWrites() {
            Mockito.verify(rules, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
            Mockito.verify(rules, Mockito.never()).persist(Mockito.any());
            Mockito.verify(shares, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
            Mockito.verify(targets, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
            Mockito.verify(rule, Mockito.never()).setConfigJson(Mockito.anyString());
            Mockito.verify(rule, Mockito.never()).setPermission(Mockito.any());
            Mockito.verify(rule, Mockito.never()).setPrincipal(Mockito.anyString());
            Mockito.verify(rule, Mockito.never()).setState(Mockito.any());
        }

        @Override public void close() {manager.abortConfigurationBatch();}
    }

    @Test public void sourceAndTargetUuidsDifferButExactLiveAclReceivesOnlyPrivateBatchInput() {
        for (String protocol : new String[] {"SMB", "ISCSI", "NVME_OF"}) try (Fixture f = new Fixture(protocol)) {
            JsonObject reviewed = f.desired.deepCopy(), values = f.secrets.deepCopy();String originalConfig = f.rule.getConfigJson();
            f.retain();
            if ("SMB".equals(protocol)) Assert.assertEquals("synthetic-retained-input", f.batch().smbCredentials.get(20L));
            else if ("ISCSI".equals(protocol)) Assert.assertEquals("synthetic-retained-input", f.batch().iscsiCredentials.get(20L).get("chapSecret").getAsString());
            else Assert.assertEquals("synthetic-retained-input", f.batch().nvmeCredentials.get(20L).get("dhChapKey").getAsString());
            Assert.assertEquals(reviewed, f.desired);Assert.assertEquals(values, f.secrets);Assert.assertEquals(originalConfig, f.rule.getConfigJson());
            f.assertNoWrites();
        }
    }

    @Test public void mutualChapAndControllerSecretsUseTheNormalCamelCaseTransientContract() {
        for (String protocol : new String[] {"ISCSI", "NVME_OF"}) try (Fixture f = new Fixture(protocol)) {
            String flag = "ISCSI".equals(protocol) ? "mutualChapEnabled" : "dhChapCtrlEnabled";
            f.config.addProperty(flag, true);
            if ("ISCSI".equals(protocol)) f.config.addProperty("mutualChapUsername", "server");
            f.desired.add("config", f.config.deepCopy());
            f.secrets.addProperty("ISCSI".equals(protocol) ? "mutualchapsecret" : "dhchapctrlkey", "synthetic-mutual-input");
            f.retain();
            JsonObject values = "ISCSI".equals(protocol) ? f.batch().iscsiCredentials.get(20L) : f.batch().nvmeCredentials.get(20L);
            Assert.assertEquals("synthetic-mutual-input", values.get("ISCSI".equals(protocol) ? "mutualChapSecret" : "dhChapCtrlKey").getAsString());
            f.assertNoWrites();
        }
    }

    @Test public void foreignOwnerPrincipalPermissionResourceTypeAndAuthenticationDriftAreRejectedBeforeCollection() {
        for (String fault : new String[] {"ownerId", "instance", "principal", "principalType", "permission", "resourceType", "authPolicy"}) {
            try (Fixture f = new Fixture("SMB")) {
                if ("ownerId".equals(fault)) Mockito.when(f.rule.getResourceId()).thenReturn(11L);
                if ("instance".equals(fault)) Mockito.when(f.share.getInstanceId()).thenReturn(9L);
                if ("principal".equals(fault)) Mockito.when(f.rule.getPrincipal()).thenReturn("foreign");
                if ("principalType".equals(fault)) Mockito.when(f.rule.getPrincipalType()).thenReturn(StorageServiceInstance.PrincipalType.AD_USER);
                if ("permission".equals(fault)) Mockito.when(f.rule.getPermission()).thenReturn(StorageServiceInstance.Permission.READ_ONLY);
                if ("resourceType".equals(fault)) Mockito.when(f.rule.getResourceType()).thenReturn(StorageServiceInstance.AccessResourceType.BLOCK_TARGET);
                if ("authPolicy".equals(fault)) f.config.addProperty("localAccount", false);
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();f.assertNoWrites();
            }
        }
    }

    @Test public void inactiveOrChangedLiveRuleAndOwnerCannotAcceptTargetCredentials() {
        for (StorageServiceInstance.ResourceState state : new StorageServiceInstance.ResourceState[] {
                StorageServiceInstance.ResourceState.Disabled, StorageServiceInstance.ResourceState.Error, StorageServiceInstance.ResourceState.Destroyed}) {
            try (Fixture f = new Fixture("SMB")) {
                Mockito.when(f.rule.getState()).thenReturn(state);
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
            try (Fixture f = new Fixture("ISCSI")) {
                Mockito.when(f.target.getState()).thenReturn(state);
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
        }
    }

    @Test public void malformedEmptyUnknownAndOversizedSecretsAreRejectedWithoutCollection() {
        JsonArray badValues = new JsonArray();
        badValues.add(JsonNull.INSTANCE);badValues.add(new JsonPrimitive(3));badValues.add(new JsonPrimitive(true));
        badValues.add(new JsonObject());badValues.add(new JsonArray());badValues.add("");badValues.add("   ");badValues.add("x".repeat(4097));
        badValues.add("synthetic\nline");badValues.add("synthetic\rline");badValues.add("synthetic\0byte");
        for (String protocol : new String[] {"SMB", "ISCSI", "NVME_OF"}) {
            String field = "SMB".equals(protocol) ? "password" : "ISCSI".equals(protocol) ? "chapsecret" : "dhchapkey";
            for (JsonElement value : badValues) try (Fixture f = new Fixture(protocol)) {
                f.secrets.add(field, value.deepCopy());
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();f.assertNoWrites();
            }
            try (Fixture f = new Fixture(protocol)) {
                f.secrets.remove(field);
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
            try (Fixture f = new Fixture(protocol)) {
                f.secrets.addProperty("unknown", "synthetic-extra");
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
        }
    }

    @Test public void disabledAuthenticationAndMissingEnabledCounterpartNeverCollectPartialSecrets() {
        for (String protocol : new String[] {"ISCSI", "NVME_OF"}) {
            try (Fixture f = new Fixture(protocol)) {
                f.config.addProperty("ISCSI".equals(protocol) ? "chapEnabled" : "dhChapEnabled", false);
                f.desired.add("config", f.config.deepCopy());
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
            try (Fixture f = new Fixture(protocol)) {
                f.config.addProperty("ISCSI".equals(protocol) ? "mutualChapEnabled" : "dhChapCtrlEnabled", true);
                if ("ISCSI".equals(protocol)) f.config.addProperty("mutualChapUsername", "server");
                f.desired.add("config", f.config.deepCopy());
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
        }
    }

    @Test public void chapUsernamesMustRemainLiteralStringsEvenWhenCurrentAndReviewedMalformedValuesMatch() {
        for (JsonElement value : new JsonElement[] {new JsonPrimitive(42), new JsonPrimitive(true), new JsonObject(), new JsonArray()}) {
            try (Fixture f = new Fixture("ISCSI")) {
                f.config.add("chapUsername", value.deepCopy());f.desired.add("config", f.config.deepCopy());
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
            try (Fixture f = new Fixture("ISCSI")) {
                f.config.addProperty("mutualChapEnabled", true);f.config.add("mutualChapUsername", value.deepCopy());
                f.desired.add("config", f.config.deepCopy());f.secrets.addProperty("mutualchapsecret", "synthetic-mutual-input");
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
        }
    }

    @Test public void stringBooleanPolicyAndMissingActiveBatchFailClosed() {
        for (String protocol : new String[] {"SMB", "ISCSI", "NVME_OF"}) {
            try (Fixture f = new Fixture(protocol)) {
                f.config.addProperty("SMB".equals(protocol) ? "localAccount" : "ISCSI".equals(protocol) ? "chapEnabled" : "dhChapEnabled", "true");
                f.desired.add("config", f.config.deepCopy());
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();
            }
            try (Fixture f = new Fixture(protocol)) {
                f.manager.abortConfigurationBatch();
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertNoWrites();
            }
            try (Fixture f = new Fixture(protocol)) {
                Mockito.when(f.instance.getId()).thenReturn(9L);
                Assert.assertThrows(InvalidParameterValueException.class, f::retain);f.assertEmpty();f.assertNoWrites();
            }
        }
    }
}
