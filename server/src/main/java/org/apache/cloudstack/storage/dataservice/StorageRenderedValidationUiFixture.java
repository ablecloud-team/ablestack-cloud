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
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
/** The UI preparation path only creates fresh, exact four-DATA TEST evidence. It cannot adopt original DATA. */
public final class StorageRenderedValidationUiFixture {
    private StorageRenderedValidationUiFixture() { }
    public static void requireNewData(JsonObject binding,long vmCreated,long now) {
        if(vmCreated<=0||vmCreated<now-24L*60*60*1000||vmCreated>now+5000)throw denied();
        if(binding==null||!binding.has("volumes")||!binding.get("volumes").isJsonObject())throw denied();
        JsonObject volumes=binding.getAsJsonObject("volumes");int roots=0,data=0;
        for(java.util.Map.Entry<String,JsonElement> entry:volumes.entrySet()) {
            if(!entry.getKey().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!entry.getValue().isJsonObject())throw denied();
            JsonObject row=entry.getValue().getAsJsonObject();
            if(!java.util.Set.of("SPARSE","FAT").contains(text(row,"provisioningType"))||!yes(row,"attachedToFixture")||!yes(row,"ownerAndZoneVerified"))throw denied();
            if("ROOT".equals(text(row,"type")))roots++;
            else if("DATADISK".equals(text(row,"type"))&&yes(row,"newDataWithoutBacking"))data++;
            else throw denied();
        }
        if(roots!=1||data!=4)throw denied();
    }
    private static String text(JsonObject v,String k){return v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isString()?v.get(k).getAsString():"";}
    private static boolean yes(JsonObject v,String k){return v.has(k)&&v.get(k).isJsonPrimitive()&&v.get(k).getAsJsonPrimitive().isBoolean()&&v.get(k).getAsBoolean();}
    private static CloudRuntimeException denied(){return new CloudRuntimeException("UI validation approval requires one new owned SPARSE/FAT ROOT and exactly four new unbacked disposable DATA disks");}
}
