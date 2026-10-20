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
import com.google.gson.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assert;import org.junit.Test;
public class StorageRenderedValidationUiFixtureTest {
    final long now=System.currentTimeMillis();
    JsonObject binding(){
        JsonObject b=new JsonObject(),volumes=new JsonObject();b.add("volumes",volumes);
        for(int i=0;i<5;i++){JsonObject row=new JsonObject();row.addProperty("type",i==0?"ROOT":"DATADISK");
            row.addProperty("provisioningType","SPARSE");row.addProperty("attachedToFixture",true);row.addProperty("ownerAndZoneVerified",true);
            row.addProperty("newDataWithoutBacking",i>0);volumes.add(String.format("00000000-0000-4000-8000-%012d",i),row);}
        return b;
    }
    @Test public void exactNewSparseRootAndFourUnbackedOwnedDataAllowOnlyTestPreparation(){
        StorageRenderedValidationUiFixture.requireNewData(binding(),now-1000,now);
        JsonObject b=binding();for(JsonElement row:b.getAsJsonObject("volumes").asMap().values())row.getAsJsonObject().addProperty("provisioningType","FAT");
        StorageRenderedValidationUiFixture.requireNewData(b,now-1000,now);
    }
    @Test public void thinClonedReusedForeignOrUnattachedDataRemainDenied(){
        for(String key:new String[]{"provisioningType","newDataWithoutBacking","ownerAndZoneVerified","attachedToFixture"}){
            JsonObject b=binding(),row=b.getAsJsonObject("volumes").getAsJsonObject("00000000-0000-4000-8000-000000000001");
            if(key.equals("provisioningType"))row.addProperty(key,"THIN");else row.addProperty(key,false);
            Assert.assertThrows(key,CloudRuntimeException.class,()->StorageRenderedValidationUiFixture.requireNewData(b,now-1000,now));
        }
    }
    @Test public void wrongDataCountDuplicateRootAndOldVmCannotAdoptOriginalFixture(){
        JsonObject b=binding();b.getAsJsonObject("volumes").remove("00000000-0000-4000-8000-000000000004");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationUiFixture.requireNewData(b,now-1000,now));
        JsonObject c=binding();c.getAsJsonObject("volumes").getAsJsonObject("00000000-0000-4000-8000-000000000001").addProperty("type","ROOT");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationUiFixture.requireNewData(c,now-1000,now));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRenderedValidationUiFixture.requireNewData(binding(),now-25L*3600000,now));
    }
}
