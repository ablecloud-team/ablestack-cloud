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
package com.cloud.kubernetes.cluster;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.ApiConstants;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.utils.exception.CloudRuntimeException;

public class KubernetesRuntimeKeyProfileTest {
    private static final String CLUSTER = "9db07460-d278-4a21-a9cb-09c81e5b2abd";

    private ApiKeyPair key(boolean csi) {
        ApiKeyPair key = Mockito.mock(ApiKeyPair.class);
        Mockito.when(key.getUserId()).thenReturn(8L);
        Mockito.when(key.getAccountId()).thenReturn(7L);
        Mockito.when(key.getDomainId()).thenReturn(6L);
        Mockito.when(key.getName()).thenReturn(KubernetesRuntimeKeyProfile.name(CLUSTER, csi));
        Mockito.when(key.getApiKey()).thenReturn("fixture-api");
        Mockito.when(key.getSecretKey()).thenReturn("fixture-secret");
        return key;
    }

    private List<ApiKeyPairPermissionVO> permissions(boolean csi) {
        List<ApiKeyPairPermissionVO> result = new ArrayList<>();
        for (Map<String, Object> rule : KubernetesRuntimeKeyProfile.request(8, CLUSTER, csi).getRules()) {
            ApiKeyPairPermissionVO value = new ApiKeyPairPermissionVO(1, rule.get(ApiConstants.RULE).toString(),
                    (RolePermissionEntity.Permission) rule.get(ApiConstants.PERMISSION), "fixture");
            value.setSortOrder(result.size());
            result.add(value);
        }
        return result;
    }

    private void validate(ApiKeyPair key, boolean csi, List<ApiKeyPairPermissionVO> permissions) {
        KubernetesRuntimeKeyProfile.validateForUse(key, 8, 7, 6, CLUSTER, csi, permissions);
    }

    @Test public void csiProfileAllowsAuditedVolumeAndSnapshotCommands() {
        validate(key(true), true, permissions(true));
        Assert.assertTrue(KubernetesRuntimeKeyProfile.commands(true).contains("listVolumes"));
        Assert.assertTrue(KubernetesRuntimeKeyProfile.commands(true).contains("createSnapshot"));
    }

    @Test public void nonCsiProfileHasNoVolumeOrSnapshotAccess() {
        validate(key(false), false, permissions(false));
        Assert.assertFalse(KubernetesRuntimeKeyProfile.commands(false).contains("listVolumes"));
        Assert.assertFalse(KubernetesRuntimeKeyProfile.commands(false).contains("deleteVolume"));
        Assert.assertFalse(KubernetesRuntimeKeyProfile.commands(false).contains("createSnapshot"));
    }

    @Test public void newBaseAndCsiKeysIncludeTheRequiredVpcAclListRead() {
        Assert.assertTrue(KubernetesRuntimeKeyProfile.commands(false).contains("listNetworkACLLists"));
        Assert.assertTrue(KubernetesRuntimeKeyProfile.commands(true).contains("listNetworkACLLists"));
    }
    @Test public void exactPreviousProfilesRemainUsableWithoutWideningTheirRules() {
        for (boolean csi : new boolean[]{false,true}) {
            List<ApiKeyPairPermissionVO> legacy = permissions(csi);
            legacy.removeIf(p -> p.getRule().getRuleString().equals("listNetworkACLLists"));
            validate(key(csi), csi, legacy);
            Assert.assertFalse(legacy.stream().anyMatch(p -> p.getRule().getRuleString().equals("listNetworkACLLists")));
        }
    }
    @Test(expected = CloudRuntimeException.class) public void legacyProfileWithAnotherMissingCommandStillFailsClosed() {
        List<ApiKeyPairPermissionVO> legacy = permissions(false);
        legacy.removeIf(p -> p.getRule().getRuleString().equals("listNetworkACLLists") || p.getRule().getRuleString().equals("listVirtualMachines"));
        validate(key(false),false,legacy);
    }
    @Test(expected = CloudRuntimeException.class) public void sharedOrDifferentClusterKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.getName()).thenReturn("shared-kubeadmin");validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void differentAccountKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.getAccountId()).thenReturn(99L);validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void differentUserKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.getUserId()).thenReturn(99L);validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void expiredKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.hasEndDatePassed()).thenReturn(true);validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void removedKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.getRemoved()).thenReturn(new Date());validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void futureKeyRejected() {
        ApiKeyPair key = key(true);Mockito.when(key.getStartDate()).thenReturn(new Date(System.currentTimeMillis()+60000));validate(key,true,permissions(true));
    }

    @Test(expected = CloudRuntimeException.class) public void missingVolumePermissionRejected() {
        List<ApiKeyPairPermissionVO> values = permissions(true);values.removeIf(p->p.getRule().getRuleString().equals("listVolumes"));validate(key(true),true,values);
    }

    @Test(expected = CloudRuntimeException.class) public void extraAdminPermissionRejected() {
        List<ApiKeyPairPermissionVO> values = permissions(true);values.add(new ApiKeyPairPermissionVO(1,"deleteAccount",RolePermissionEntity.Permission.ALLOW,"fixture"));validate(key(true),true,values);
    }

    @Test(expected = CloudRuntimeException.class) public void denyBeforeAllowRejected() {
        List<ApiKeyPairPermissionVO> values = permissions(true);values.get(values.size()-1).setSortOrder(-1);validate(key(true),true,values);
    }

    @Test public void keyRequestNamesProfileAndKeepsDenyAllLast() {
        Assert.assertEquals(Long.valueOf(8),KubernetesRuntimeKeyProfile.request(8,CLUSTER,true).getUserId());
        List<Map<String,Object>> values=KubernetesRuntimeKeyProfile.request(8,CLUSTER,true).getRules();
        Assert.assertEquals("*",values.get(values.size()-1).get(ApiConstants.RULE).toString());
        Assert.assertEquals(RolePermissionEntity.Permission.DENY,values.get(values.size()-1).get(ApiConstants.PERMISSION));
    }
}
