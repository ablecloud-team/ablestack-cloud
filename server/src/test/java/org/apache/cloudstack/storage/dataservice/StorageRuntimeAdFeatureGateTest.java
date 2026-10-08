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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageRuntimeAdFeatureGateTest {
    private static final Set<String> IDENTITY=Set.of("SMB_ACTIVE_DIRECTORY","SMB_AD_IDENTITY");
    private StorageServiceRuntimeUpgradeManagerImpl manager;private StorageServiceInstanceVO instance;private StorageIdentityDomainDao domains;private StorageFileShareDao shares;private StorageAccessRuleDao rules;private StoragePosixDirectoryPolicyDao policies;private StorageServiceGuestCommandDispatcher guest;
    @Before public void setup(){
        manager=new StorageServiceRuntimeUpgradeManagerImpl();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(60L);Mockito.when(instance.getUuid()).thenReturn("11111111-2222-4333-8444-555555555555");
        domains=Mockito.mock(StorageIdentityDomainDao.class);shares=Mockito.mock(StorageFileShareDao.class);rules=Mockito.mock(StorageAccessRuleDao.class);policies=Mockito.mock(StoragePosixDirectoryPolicyDao.class);StorageServiceProtocolDao protocols=Mockito.mock(StorageServiceProtocolDao.class);guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager,"runtimeIdentityDomainDao",domains);ReflectionTestUtils.setField(manager,"fileShareDao",shares);ReflectionTestUtils.setField(manager,"accessRuleDao",rules);ReflectionTestUtils.setField(manager,"posixPolicyDao",policies);ReflectionTestUtils.setField(manager,"protocolDao",protocols);ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
    }
    private void domain(StorageServiceInstance.DomainJoinState state){StorageIdentityDomainVO row=Mockito.mock(StorageIdentityDomainVO.class);Mockito.when(row.getJoinState()).thenReturn(state);Mockito.when(domains.findByInstanceId(6L)).thenReturn(row);}
    private JsonObject declared(Set<String> features){JsonObject v=new JsonObject();JsonArray f=new JsonArray();new TreeSet<>(features).forEach(f::add);v.add("supportedFeatures",f);return v;}
    private void caps(String json){Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok",json));}
    private void nativeAll(){JsonObject c=declared(Set.of("SMB_ACTIVE_DIRECTORY","SMB_AD_IDENTITY","POSIX_AD_PRINCIPALS"));c.addProperty("success",true);c.addProperty("adIdentity",true);caps(c.toString());}
    private void smb(StorageServiceInstance.PrincipalType type,StorageServiceInstance.ResourceState state){StorageFileShareVO share=Mockito.mock(StorageFileShareVO.class);Mockito.when(share.getId()).thenReturn(7L);Mockito.when(share.getState()).thenReturn(StorageServiceInstance.ResourceState.Ready);Mockito.when(shares.listByInstanceIdAndProtocol(6L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(share));StorageAccessRuleVO rule=Mockito.mock(StorageAccessRuleVO.class);Mockito.when(rule.getPrincipalType()).thenReturn(type);Mockito.when(rule.getState()).thenReturn(state);Mockito.when(rules.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,7L)).thenReturn(List.of(rule));}
    @Test public void joinedJoiningLeavingErrorAndUnknownDomainProtectAdIdentityWhileNotJoinedDoesNot(){
        for(StorageServiceInstance.DomainJoinState state:new StorageServiceInstance.DomainJoinState[]{StorageServiceInstance.DomainJoinState.JOINED,StorageServiceInstance.DomainJoinState.JOINING,StorageServiceInstance.DomainJoinState.LEAVING,StorageServiceInstance.DomainJoinState.ERROR,null}){domain(state);Assert.assertTrue(manager.requiredRuntimeFeatures(instance).containsAll(IDENTITY));}
        domain(StorageServiceInstance.DomainJoinState.NOT_JOINED);Assert.assertTrue(manager.requiredRuntimeFeatures(instance).isEmpty());
    }
    @Test public void activeSmbAdUsersAndGroupsRequireBothSignedFeatures(){
        for(StorageServiceInstance.PrincipalType kind:new StorageServiceInstance.PrincipalType[]{StorageServiceInstance.PrincipalType.AD_USER,StorageServiceInstance.PrincipalType.AD_GROUP}){smb(kind,StorageServiceInstance.ResourceState.Ready);Assert.assertTrue(manager.requiredRuntimeFeatures(instance).containsAll(IDENTITY));}
    }
    @Test public void localDisabledAndDestroyedPrincipalsDoNotCreateActiveAdRequirement(){
        smb(StorageServiceInstance.PrincipalType.LOCAL_USER,StorageServiceInstance.ResourceState.Ready);Assert.assertTrue(manager.requiredRuntimeFeatures(instance).isEmpty());
        for(StorageServiceInstance.ResourceState state:new StorageServiceInstance.ResourceState[]{StorageServiceInstance.ResourceState.Disabled,StorageServiceInstance.ResourceState.Destroyed}){smb(StorageServiceInstance.PrincipalType.AD_USER,state);Assert.assertTrue(manager.requiredRuntimeFeatures(instance).isEmpty());}
    }
    @Test public void posixAccessAndDefaultAdEntriesRequireMappingIdentityAndDirectoryFeatures(){
        for(String field:new String[]{"accessEntries","defaultEntries"}){StoragePosixDirectoryPolicyVO p=Mockito.mock(StoragePosixDirectoryPolicyVO.class);Mockito.when(p.getState()).thenReturn("Ready");Mockito.when(p.getConfigJson()).thenReturn("{\""+field+"\":[{\"principalType\":\"AD_GROUP\"}]}");Mockito.when(policies.listByInstance(6L)).thenReturn(List.of(p));Set<String> required=manager.requiredRuntimeFeatures(instance);Assert.assertTrue(required.containsAll(IDENTITY));Assert.assertTrue(required.contains("POSIX_AD_PRINCIPALS"));Assert.assertTrue(required.contains("POSIX_DIRECTORY_POLICY"));}
    }
    @Test public void declaredCandidateCannotOverrideNativeFalseUnknownOrStringBoolean(){
        domain(StorageServiceInstance.DomainJoinState.JOINED);
        for(String json:new String[]{"{\"success\":true,\"adIdentity\":false}","{\"success\":true}","{\"success\":true,\"adIdentity\":\"true\"}"}){caps(json);CloudRuntimeException e=Assert.assertThrows(CloudRuntimeException.class,()->manager.requireSignedRuntimeFeatures(instance,declared(IDENTITY)));Assert.assertTrue(e.getMessage().contains("FEATURE_UNAVAILABLEBLOCK"));}
    }
    @Test public void nativeIdentityBooleanAloneCannotClaimAllRequiredFeatures(){domain(StorageServiceInstance.DomainJoinState.JOINED);caps("{\"success\":true,\"adIdentity\":true}");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireSignedRuntimeFeatures(instance,declared(IDENTITY)));}
    @Test public void nativeCapabilityAndSignedCandidateMustBothSupportActiveAdFeatures(){domain(StorageServiceInstance.DomainJoinState.JOINED);nativeAll();Assert.assertThrows(CloudRuntimeException.class,()->manager.requireSignedRuntimeFeatures(instance,declared(Set.of())));manager.requireSignedRuntimeFeatures(instance,declared(IDENTITY));}
    @Test public void localOnlyServiceDoesNotNeedAdProbeOrStopExistingService(){manager.requireSignedRuntimeFeatures(instance,declared(Set.of()));Mockito.verifyNoInteractions(guest);}
    @Test public void malformedPosixAclShapeBlocksRatherThanDroppingItsRequirement(){StoragePosixDirectoryPolicyVO p=Mockito.mock(StoragePosixDirectoryPolicyVO.class);Mockito.when(p.getState()).thenReturn("Ready");Mockito.when(p.getConfigJson()).thenReturn("{\"accessEntries\":{}}");Mockito.when(policies.listByInstance(6L)).thenReturn(List.of(p));Assert.assertThrows(CloudRuntimeException.class,()->manager.requiredRuntimeFeatures(instance));Mockito.verifyNoInteractions(guest);}
}
