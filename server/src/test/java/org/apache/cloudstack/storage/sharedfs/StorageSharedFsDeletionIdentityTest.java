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
package org.apache.cloudstack.storage.sharedfs;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assert;
import org.junit.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
public class StorageSharedFsDeletionIdentityTest {
    private JsonObject scope(){return JsonParser.parseString("{\"sharedfsId\":7,\"sharedfsUuid\":\"11111111-2222-4333-8444-555555555555\",\"vmId\":60,\"policy\":\"PRESERVE_VOLUMES\",\"accountId\":2,\"domainId\":1,\"zoneId\":4}").getAsJsonObject();}
    private JsonObject row(long id){JsonObject v=JsonParser.parseString("{\"type\":\"DATADISK\",\"accountId\":2,\"domainId\":1,\"zoneId\":4,\"poolId\":90,\"sizeBytes\":21474836480,\"attachedVmId\":60,\"state\":\"Ready\"}").getAsJsonObject();v.addProperty("id",id);v.addProperty("uuid",new UUID(0,id).toString());return v;}
    private JsonArray rows(JsonObject... rows){JsonArray out=new JsonArray();for(JsonObject row:rows)out.add(row);return out;}
    private JsonObject freeze(JsonArray rows){return StorageSharedFsDeletionIdentity.freeze(scope(),rows);}
    @Test public void multipleDataWithSameReviewedTupleSurvivesExactCurrentValidation(){JsonArray data=rows(row(10),row(11));JsonObject plan=freeze(data);Assert.assertEquals(Set.of(10L,11L),StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),data));}
    @Test public void attachmentToNullIsAllowedAcrossDetachRetryButAnotherVmIsRejected(){JsonObject original=row(10);JsonObject plan=freeze(rows(original));JsonObject changed=original.deepCopy();changed.add("attachedVmId",JsonNull.INSTANCE);Assert.assertEquals(Set.of(10L),StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(changed)));changed.addProperty("attachedVmId",61);Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(changed)));}
    @Test public void nameStateAndExpectedAttachmentChangesDoNotChangeReviewHash(){JsonObject original=row(10);JsonObject changed=original.deepCopy();changed.addProperty("name","renamed");changed.addProperty("state","Expunged");changed.add("attachedVmId",JsonNull.INSTANCE);Assert.assertEquals(freeze(rows(original)).get("planHash"),freeze(rows(changed)).get("planHash"));}
    @Test public void everyProtectedTupleFieldChangedAfterApprovalRejectsTheCurrentRow(){for(String key:List.of("uuid","type","accountId","domainId","zoneId","poolId","sizeBytes")){JsonObject original=row(10),changed=original.deepCopy();JsonObject plan=freeze(rows(original));if(key.equals("uuid"))changed.addProperty(key,new UUID(0,20).toString());else if(key.equals("type"))changed.addProperty(key,"ROOT");else changed.addProperty(key,999);Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(changed)));}}
    @Test public void newOrMissingCurrentInventoryCannotBeSilentlyDeletedOrPreserved(){JsonObject plan=freeze(rows(row(10)));Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(row(10),row(11))));Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows()));}
    @Test public void replacedNumericIdWithAnotherUuidIsNotTheApprovedVolume(){JsonObject plan=freeze(rows(row(10)));JsonObject replacement=row(10);replacement.addProperty("uuid",new UUID(0,100).toString());Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(replacement)));}
    @Test public void tamperedManifestTupleOrPolicyCannotBorrowTheOldConsentHash(){JsonObject plan=freeze(rows(row(10)));plan.getAsJsonArray("volumes").get(0).getAsJsonObject().addProperty("sizeBytes",100);Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(row(10))));JsonObject another=freeze(rows(row(10)));another.addProperty("policy","DELETE_VOLUMES");Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(another,scope(),rows(row(10))));}
    @Test public void foreignServiceVmOwnerDomainZoneOrPolicyCannotUseTheApprovedManifest(){JsonObject plan=freeze(rows(row(10)));for(String key:List.of("sharedfsId","sharedfsUuid","vmId","accountId","domainId","zoneId","policy")){JsonObject other=scope();if(key.equals("sharedfsUuid"))other.addProperty(key,new UUID(0,20).toString());else if(key.equals("policy"))other.addProperty(key,"DELETE_VOLUMES");else other.addProperty(key,999);Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,other,rows(row(10))));}}
    @Test public void foreignOwnerDomainZoneAndRootAreRejectedBeforeAnyFrozenPlanExists(){for(String key:List.of("accountId","domainId","zoneId","type")){JsonObject bad=row(10);if(key.equals("type"))bad.addProperty(key,"ROOT");else bad.addProperty(key,999);Assert.assertThrows(CloudRuntimeException.class,()->freeze(rows(bad)));}}
    @Test public void duplicateNumericIdsOrUuidCannotProduceAReviewPlan(){Assert.assertThrows(CloudRuntimeException.class,()->freeze(rows(row(10),row(10))));JsonObject sameUuid=row(11);sameUuid.addProperty("uuid",row(10).get("uuid").getAsString());Assert.assertThrows(CloudRuntimeException.class,()->freeze(rows(row(10),sameUuid)));}
    @Test public void nullPoolForUnallocatedDataIsPinnedAndLaterAllocationNeedsFreshReview(){JsonObject original=row(10);original.add("poolId",JsonNull.INSTANCE);JsonObject plan=freeze(rows(original));StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(original));JsonObject changed=original.deepCopy();changed.addProperty("poolId",90);Assert.assertThrows(CloudRuntimeException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),rows(changed)));}
    @Test public void missingPoolKeyMalformedUuidOrStringIntegerFailsClosed(){for(String key:List.of("poolId","uuid","sizeBytes")){JsonObject bad=row(10);if(key.equals("poolId"))bad.remove(key);else bad.addProperty(key,key.equals("uuid")?"invalid":"21474836480");Assert.assertThrows(CloudRuntimeException.class,()->freeze(rows(bad)));}}
    @Test public void orderingIsDeterministicAndInputsAreNeverMutated(){JsonArray original=rows(row(11),row(10));String before=original.toString();JsonObject plan=freeze(original);Assert.assertEquals(freeze(rows(row(10),row(11))),plan);Assert.assertEquals(before,original.toString());Assert.assertThrows(UnsupportedOperationException.class,()->StorageSharedFsDeletionIdentity.requireCurrent(plan,scope(),original).clear());}
}
