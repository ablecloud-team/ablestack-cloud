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
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Assert;
import org.junit.Test;
public class StoragePermissionPreviewTokenTest {
    private JsonObject value(String name,int number){JsonObject value=new JsonObject();value.addProperty(name,number);return value;}
    @Test public void sameActorAndExactInodeIntentAreValidAcrossManagementNodesWithTheSameKey(){JsonObject intent=value("volume",1),identity=value("inode",2);String token=StoragePermissionPreviewToken.issue(intent,identity,7,1000,"protected-test-key");Assert.assertEquals(intent,StoragePermissionPreviewToken.verify(token,intent,identity,7,2000,"protected-test-key").get("intent"));}
    @Test public void replacedInodeDifferentPolicyOrActorAndExpiredApprovalAreRejected(){JsonObject intent=value("volume",1),identity=value("inode",2);String token=StoragePermissionPreviewToken.issue(intent,identity,7,1000,"protected-test-key");Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token,intent,value("inode",3),7,2000,"protected-test-key"));Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token,value("volume",2),identity,7,2000,"protected-test-key"));Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token,intent,identity,8,2000,"protected-test-key"));Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token,intent,identity,7,601000,"protected-test-key"));}
    @Test public void forgedSignatureAndWrongManagementKeyAreRejected(){JsonObject intent=value("volume",1),identity=value("inode",2);String token=StoragePermissionPreviewToken.issue(intent,identity,7,1000,"protected-test-key");Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token+"x",intent,identity,7,2000,"protected-test-key"));Assert.assertThrows(InvalidParameterValueException.class,()->StoragePermissionPreviewToken.verify(token,intent,identity,7,2000,"foreign-key"));}
}
