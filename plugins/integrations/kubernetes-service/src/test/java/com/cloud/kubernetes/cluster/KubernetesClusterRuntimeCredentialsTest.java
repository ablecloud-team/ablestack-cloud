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
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.ApiConstants;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.UserAccount;
import com.cloud.utils.exception.CloudRuntimeException;

public class KubernetesClusterRuntimeCredentialsTest {
    private static final String UUID = "9db07460-d278-4a21-a9cb-09c81e5b2abd";
    private KubernetesClusterManagerImpl manager;
    private Account owner;
    private UserAccount user;
    private KubernetesClusterVO cluster;
    private ApiKeyPair key;
    private KubernetesClusterDetailsVO detail;

    @Before public void setup() {
        manager = Mockito.spy(new KubernetesClusterManagerImpl());
        manager.accountService = Mockito.mock(AccountService.class);
        manager.kubernetesClusterDetailsDao = Mockito.mock(KubernetesClusterDetailsDao.class);
        owner = Mockito.mock(Account.class);user = Mockito.mock(UserAccount.class);cluster = Mockito.mock(KubernetesClusterVO.class);key = Mockito.mock(ApiKeyPair.class);
        Mockito.when(owner.getAccountId()).thenReturn(7L);Mockito.when(owner.getDomainId()).thenReturn(6L);
        Mockito.when(user.getId()).thenReturn(8L);Mockito.when(user.getAccountId()).thenReturn(7L);
        Mockito.when(cluster.getId()).thenReturn(41L);Mockito.when(cluster.getAccountId()).thenReturn(7L);Mockito.when(cluster.getUuid()).thenReturn(UUID);Mockito.when(cluster.isCsiEnabled()).thenReturn(true);
        Mockito.when(key.getId()).thenReturn(12L);Mockito.when(key.getUserId()).thenReturn(8L);Mockito.when(key.getAccountId()).thenReturn(7L);Mockito.when(key.getDomainId()).thenReturn(6L);
        Mockito.when(key.getName()).thenReturn(KubernetesRuntimeKeyProfile.name(UUID,true));Mockito.when(key.getApiKey()).thenReturn("fixture-api");Mockito.when(key.getSecretKey()).thenReturn("fixture-secret");
        detail = new KubernetesClusterDetailsVO(41,KubernetesRuntimeKeyProfile.KEY_DETAIL,"12",false);
        Mockito.when(manager.accountService.getActiveUserAccount("mold-cks-"+UUID,6L)).thenReturn(user);
        Mockito.when(manager.accountService.getAccount(7L)).thenReturn(owner);
        Mockito.when(manager.accountService.getKeyPairById(12L)).thenReturn(key);
        Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL)).thenReturn(detail);
        List<ApiKeyPairPermissionVO> permissions=new ArrayList<>();
        for(Map<String,Object> rule:KubernetesRuntimeKeyProfile.request(8,UUID,true).getRules()) {
            ApiKeyPairPermissionVO permission=new ApiKeyPairPermissionVO(12,rule.get(ApiConstants.RULE).toString(),(RolePermissionEntity.Permission)rule.get(ApiConstants.PERMISSION),"fixture");
            permission.setSortOrder(permissions.size());permissions.add(permission);
        }
        Mockito.doReturn(permissions).when(manager.accountService).getAllExplicitKeyPairPermissions(12L);
    }

    @Test public void usesPersistedClusterKeyWithoutSelectingGlobalLatest() {
        org.junit.Assert.assertArrayEquals(new String[]{"fixture-api","fixture-secret"},manager.getClusterServiceUserKeys(owner,cluster));
        Mockito.verify(manager.accountService).getActiveUserAccount("mold-cks-"+UUID,6L);
        Mockito.verify(manager.accountService,Mockito.never()).createApiKeyAndSecretKey(Mockito.anyLong());
    }

    @Test public void newProfilePersistsOnlyKeyId() {
        Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL)).thenReturn(null);
        Mockito.doReturn(key).when(manager).createClusterServiceKey(8,cluster);
        manager.getClusterServiceUserKeys(owner,cluster);
        Mockito.verify(manager.kubernetesClusterDetailsDao).addDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL,"12",false);
    }

    @Test(expected=CloudRuntimeException.class) public void foreignExistingUserRejected() {
        Mockito.when(user.getAccountId()).thenReturn(99L);manager.getClusterServiceUserKeys(owner,cluster);
    }

    @Test(expected=CloudRuntimeException.class) public void revokedReferencedKeyFailsWithoutFallback() {
        Mockito.when(manager.accountService.getKeyPairById(12L)).thenReturn(null);manager.getClusterServiceUserKeys(owner,cluster);
    }

    @Test public void deleteLegacyClusterNeverRevokesSharedCredentials() {
        Mockito.when(manager.kubernetesClusterDetailsDao.findDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL)).thenReturn(null);
        manager.removeClusterServiceKeys(cluster);Mockito.verify(manager.accountService,Mockito.never()).deleteApiKey(Mockito.any(ApiKeyPair.class));
    }

    @Test public void deleteRevokesOnlyVerifiedClusterKey() {
        manager.removeClusterServiceKeys(cluster);Mockito.verify(manager.accountService).deleteApiKey(key);
        Mockito.verify(manager.kubernetesClusterDetailsDao).removeDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL);
    }

    @Test public void alreadyDeletedKeyCleanupIsIdempotent() {
        Mockito.when(manager.accountService.getKeyPairById(12L)).thenReturn(null);manager.removeClusterServiceKeys(cluster);
        Mockito.verify(manager.accountService,Mockito.never()).deleteApiKey(Mockito.any(ApiKeyPair.class));
        Mockito.verify(manager.kubernetesClusterDetailsDao).removeDetail(41,KubernetesRuntimeKeyProfile.KEY_DETAIL);
    }

    @Test(expected=CloudRuntimeException.class) public void deleteNeverRevokesForeignProfile() {
        Mockito.when(key.getName()).thenReturn("another-cluster");manager.removeClusterServiceKeys(cluster);
    }
}
