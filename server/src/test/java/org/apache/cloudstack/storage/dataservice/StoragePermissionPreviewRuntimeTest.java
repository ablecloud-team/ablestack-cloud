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
import java.util.UUID;
import com.cloud.storage.VolumeVO;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.utils.db.DbProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd;
import org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StoragePermissionPreviewRuntimeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;VolumeVO volume;int applies;long inode=128;JsonObject lastApply;
        @Override public Long getStorageServiceSyncId(BaseCmd cmd){return 6L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected VolumeVO requireVolume(Long id){return volume;}
        @Override protected void validateStorageServiceBackingVolume(StorageServiceInstanceVO instance,Long volume,String resource){ }
        @Override protected JsonObject posixPreviewBacking(StorageServiceInstanceVO instance,VolumeVO volume,StorageFileShareVO export){JsonObject value=new JsonObject();value.addProperty("volumeMountPath","/srv/ablestack-storage/volumes/"+volume.getUuid());value.addProperty("filesystemUuid","filesystem-original");value.addProperty("serial","serial-original");return value;}
        boolean applied;boolean receiptVerified=true;boolean freshInodeChanged;boolean forgedOwner;
        @Override protected JsonObject dispatchPosixDirectoryCommand(StorageServiceInstanceVO instance,String action,JsonObject request) {
            if (action.equals("apply")) {applies++;lastApply=request.deepCopy();applied=true;}
            JsonObject value=new JsonObject();value.addProperty("success",true);value.addProperty("effectiveUid",applied&&!forgedOwner?0:65534);value.addProperty("effectiveGid",applied?0:65534);
            value.addProperty("effectiveMode",applied?"0770":"0775");value.addProperty("canonicalPath",request.get("volumeMountPath").getAsString()+(request.get("relativePath").getAsString().isEmpty()?"":"/"+request.get("relativePath").getAsString()));
            value.addProperty("filesystemUuid","filesystem-original");value.addProperty("device",2049);value.addProperty("inode",inode+(applied&&action.equals("inspect")&&freshInodeChanged?1:0));value.add("acl",new JsonArray());
            for(String field:List.of("uuid","instanceUuid","volumeUuid","volumeMountPath","relativePath","revision"))value.add(field,request.get(field));
            JsonObject identity=new JsonObject();for(String field:new String[]{"filesystemUuid","device","inode","effectiveUid","effectiveGid","effectiveMode"})identity.add(field,value.get(field));identity.addProperty("aclSha256","a".repeat(64));value.add("directoryIdentity",identity);
            if(action.equals("apply")){value.addProperty("postApplyReceiptVerified",receiptVerified);value.addProperty("alreadyEffective",false);}return value;
        }
    }
    private Manager manager;private StoragePosixDirectoryPolicyDao policies;private String oldKey;
    @Before public void setup(){manager=new Manager();
        manager.instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(manager.instance.getId()).thenReturn(6L);
        Mockito.when(manager.instance.getVmId()).thenReturn(7L);
        Mockito.when(manager.instance.getUuid()).thenReturn(UUID.randomUUID().toString());
        manager.volume=Mockito.mock(VolumeVO.class);
        Mockito.when(manager.volume.getUuid()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(manager.volume.getSize()).thenReturn(1024L);
        policies=Mockito.mock(StoragePosixDirectoryPolicyDao.class);
        Mockito.when(policies.persist(Mockito.any())).thenAnswer(call->call.getArgument(0));
        ReflectionTestUtils.setField(manager,"storagePosixPolicyDao",policies);
        StorageFileShareDao shares=Mockito.mock(StorageFileShareDao.class);
        Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of());
        ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        User user=Mockito.mock(User.class);
        Account account=Mockito.mock(Account.class);
        Mockito.when(user.getId()).thenReturn(11L);
        Mockito.when(account.getId()).thenReturn(2L);
        CallContext.register(user,account);
        oldKey=DbProperties.getDbProperties().getProperty("db.cloud.encrypt.secret");
        DbProperties.getDbProperties().setProperty("db.cloud.encrypt.secret","permission-test-only-key");
        }
    @After public void cleanup(){CallContext.unregister();if(oldKey==null)DbProperties.getDbProperties().remove("db.cloud.encrypt.secret");else DbProperties.getDbProperties().setProperty("db.cloud.encrypt.secret",oldKey);}
    private CreateStoragePosixDirectoryPolicyCmd cmd(boolean preview){CreateStoragePosixDirectoryPolicyCmd cmd=new CreateStoragePosixDirectoryPolicyCmd();ReflectionTestUtils.setField(cmd,"instanceId",6L);ReflectionTestUtils.setField(cmd,"volumeId",30L);ReflectionTestUtils.setField(cmd,"relativePath",".");ReflectionTestUtils.setField(cmd,"ownerUid",0L);ReflectionTestUtils.setField(cmd,"ownerGid",0L);ReflectionTestUtils.setField(cmd,"directoryMode","0770");ReflectionTestUtils.setField(cmd,"applyOwner",true);ReflectionTestUtils.setField(cmd,"rootSquash",false);ReflectionTestUtils.setField(cmd,"preview",preview);return cmd;}
    private StoragePosixDirectoryPolicyResponse execute(CreateStoragePosixDirectoryPolicyCmd cmd){return ReflectionTestUtils.invokeMethod(manager,"doExecuteStoragePosixDirectoryPolicy",cmd);}
    private JsonObject preview(){StoragePosixDirectoryPolicyResponse response=execute(cmd(true));return new JsonParser().parse((String)ReflectionTestUtils.getField(response,"preview")).getAsJsonObject();}
    @Test public void rootPreviewPreservesDataAndReturnsExactStatSuggestedRootPresetAndEpochExpiry(){JsonObject value=preview();Assert.assertEquals(2,value.get("schemaVersion").getAsInt());Assert.assertEquals(65534,value.getAsJsonObject("current").get("uid").getAsInt());Assert.assertEquals(0,value.getAsJsonObject("suggested").get("owneruid").getAsInt());Assert.assertEquals("0770",value.getAsJsonObject("suggested").get("mode").getAsString());Assert.assertFalse(value.get("recursiveAllowed").getAsBoolean());Assert.assertTrue(value.get("expiresAt").getAsLong()>System.currentTimeMillis());Assert.assertEquals(0,manager.applies);Mockito.verify(policies,Mockito.never()).persist(Mockito.any());}
    @Test public void exactApprovalAppliesOneInodeAndCarriesNativeCas(){JsonObject value=preview();CreateStoragePosixDirectoryPolicyCmd apply=cmd(false);ReflectionTestUtils.setField(apply,"previewToken",value.get("previewToken").getAsString());ReflectionTestUtils.setField(apply,"applyConfirmation",true);execute(apply);Assert.assertEquals(1,manager.applies);Assert.assertEquals(value.getAsJsonObject("expected").get("directoryIdentity"),manager.lastApply.get("expectedDirectoryIdentity"));Assert.assertTrue(manager.lastApply.get("allowFilesystemRoot").getAsBoolean());}
    @Test public void replacedInodeOrMissingConfirmationCannotChangeOwner(){JsonObject value=preview();CreateStoragePosixDirectoryPolicyCmd apply=cmd(false);ReflectionTestUtils.setField(apply,"previewToken",value.get("previewToken").getAsString());Assert.assertThrows(InvalidParameterValueException.class,()->execute(apply));ReflectionTestUtils.setField(apply,"applyConfirmation",true);manager.inode=129;Assert.assertThrows(InvalidParameterValueException.class,()->execute(apply));Assert.assertEquals(0,manager.applies);Mockito.verify(policies,Mockito.never()).persist(Mockito.any());}
    private void approvedApply() {JsonObject value=preview();CreateStoragePosixDirectoryPolicyCmd apply=cmd(false);ReflectionTestUtils.setField(apply,"previewToken",value.get("previewToken").getAsString());ReflectionTestUtils.setField(apply,"applyConfirmation",true);execute(apply);}
    @Test public void postApplyOwnerAndFreshIdentityAreStoredInsteadOfTheBeforePreviewGuard() {
        approvedApply();org.mockito.ArgumentCaptor<StoragePosixDirectoryPolicyVO> row=org.mockito.ArgumentCaptor.forClass(StoragePosixDirectoryPolicyVO.class);Mockito.verify(policies,Mockito.atLeastOnce()).update(Mockito.anyLong(),row.capture());
        JsonObject effective=JsonParser.parseString(row.getValue().getEffectiveJson()).getAsJsonObject();Assert.assertEquals(0,effective.getAsJsonObject("directoryIdentity").get("effectiveUid").getAsInt());
        Assert.assertEquals(65534,manager.lastApply.getAsJsonObject("expectedDirectoryIdentity").get("effectiveUid").getAsInt());
    }
    @Test public void aSuccessfulApplyWithoutProtectedReceiptCannotPromoteReady() {manager.receiptVerified=false;Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->approvedApply());}
    @Test public void aDirectoryReplacementAfterApplyCannotPromoteReady() {manager.freshInodeChanged=true;Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->approvedApply());}
    @Test public void aConsistentButWrongAppliedOwnerCannotPromoteReady() {manager.forgedOwner=true;Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->approvedApply());}

}
