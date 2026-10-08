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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRenderedCredentialBindingsTest {
    private JsonObject canonical(){return JsonParser.parseString("{\"desired-state/smb-share-apply.json\":{\"shares\":[{\"uuid\":\"share-A\",\"acls\":[{\"uuid\":\"acl-A\"}]},{\"uuid\":\"share-B\",\"acls\":[{\"uuid\":\"acl-B\"}]}]}}").getAsJsonObject();}
    private JsonObject refs(){return JsonParser.parseString("{\"SMB\":{\"share-A\":{\"kind\":\"IDENTITY_CHECKPOINT\",\"targetCredentialVersion\":{\"artifactUuid\":\"encrypted-A\",\"aclUuids\":[\"acl-A\"]}},\"share-B\":{\"kind\":\"IDENTITY_CHECKPOINT\"}}}").getAsJsonObject();}
    @Test public void shareUuidRefsResolveAclUuidTransientInputWithoutPuttingPasswordInPublicGeneration(){JsonObject c=canonical(),r=refs(),t=JsonParser.parseString("{\"SMB\":{\"acl-A\":{\"password\":\"synthetic-only\"}}}").getAsJsonObject();StorageRenderedCredentialBindings.validateSmb(c,r,t);Assert.assertFalse(c.toString().contains("synthetic"));Assert.assertFalse(r.toString().contains("synthetic"));}
    @Test public void shareAsAclForeignAclAndOldCheckpointCannotAuthorizeAnotherNewPassword(){for(String id:new String[]{"share-A","foreign-acl","acl-B"}){JsonObject t=new JsonObject(),smb=new JsonObject(),value=new JsonObject();value.addProperty("password","synthetic-only");smb.add(id,value);t.add("SMB",smb);Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedCredentialBindings.validateSmb(canonical(),refs(),t));}}
    @Test public void foreignShareReferenceAndDuplicateAclAcrossSharesAreRejected(){JsonObject r=refs();r.getAsJsonObject("SMB").add("foreign-share",new JsonObject());Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedCredentialBindings.validateSmb(canonical(),r,new JsonObject()));JsonObject c=canonical();c.getAsJsonObject("desired-state/smb-share-apply.json").getAsJsonArray("shares").get(1).getAsJsonObject().getAsJsonArray("acls").get(0).getAsJsonObject().addProperty("uuid","acl-A");Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedCredentialBindings.validateSmb(c,refs(),new JsonObject()));}
}
