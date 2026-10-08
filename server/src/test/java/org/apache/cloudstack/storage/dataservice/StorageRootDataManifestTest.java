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
import java.util.*;
import com.google.gson.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.*;
public class StorageRootDataManifestTest {
    private JsonArray request(){JsonArray rows=new JsonArray();for(String kind:List.of("FILE_DATA","BLOCK_RAW","UNUSED")){JsonObject row=new JsonObject();row.addProperty("volumeUuid",kind);row.addProperty("kind",kind);row.addProperty("sizeBytes",1024);rows.add(row);}return rows;}
    private JsonObject actual(){JsonObject value=new JsonObject();value.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);value.addProperty("bootId","same-source-boot");JsonArray disks=request();for(JsonElement entry:disks){JsonObject row=entry.getAsJsonObject();row.addProperty("mappingStatus","EXACT");row.addProperty("matchedBy","VOLUME_SERIAL");row.addProperty("serial","serial-"+row.get("volumeUuid").getAsString());row.addProperty("observedDevicePath","/dev/vdb");if("FILE_DATA".equals(row.get("kind").getAsString())){row.addProperty("filesystem","xfs");row.addProperty("filesystemUuid","actual-xfs-uuid");}}value.add("volumes",disks);return value;}
    @Test public void legacySharesWithMissingStoredUuidUseTheirOneFreshActualFilesystemIdentity(){JsonObject frozen=StorageRootDataManifest.freeze(request(),actual(),Map.of(),System.currentTimeMillis());Assert.assertEquals("actual-xfs-uuid",StorageRootDataManifest.volume(frozen,"FILE_DATA").get("filesystemUuid").getAsString());Assert.assertEquals(3,frozen.getAsJsonArray("volumes").size());}
    @Test public void declaredUuidMustMatchTheActualDeviceRatherThanAnotherSharesMetadata(){Assert.assertThrows(CloudRuntimeException.class,()->StorageRootDataManifest.freeze(request(),actual(),Map.of("FILE_DATA",Set.of("other-volume-uuid")),System.currentTimeMillis()));}
    @Test public void staleDuplicateAmbiguousAndMissingSerialObservationsCannotFreeze(){
        JsonObject stale=actual();stale.addProperty("generatedEpoch",0);Assert.assertThrows(CloudRuntimeException.class,()->StorageRootDataManifest.freeze(request(),stale,Map.of(),System.currentTimeMillis()));
        JsonObject duplicate=actual();duplicate.getAsJsonArray("volumes").add(duplicate.getAsJsonArray("volumes").get(0).deepCopy());Assert.assertThrows(CloudRuntimeException.class,()->StorageRootDataManifest.freeze(request(),duplicate,Map.of(),System.currentTimeMillis()));
        for(String key:List.of("serial","mappingStatus","matchedBy")){JsonObject unknown=actual();unknown.getAsJsonArray("volumes").get(0).getAsJsonObject().remove(key);Assert.assertThrows(CloudRuntimeException.class,()->StorageRootDataManifest.freeze(request(),unknown,Map.of(),System.currentTimeMillis()));}
    }
    @Test public void kernelDeviceRenameIsAllowedWhileEveryStableDiskAndFileUuidMustRemain(){
        JsonObject expected=StorageRootDataManifest.freeze(request(),actual(),Map.of(),System.currentTimeMillis()),after=expected.deepCopy();for(JsonElement row:after.getAsJsonArray("volumes"))row.getAsJsonObject().addProperty("observedDevicePath","/dev/sdc");StorageRootDataManifest.requireSame(expected,after);
        StorageRootDataManifest.volume(after,"FILE_DATA").addProperty("filesystemUuid","formatted-other-fs");Assert.assertThrows(CloudRuntimeException.class,()->StorageRootDataManifest.requireSame(expected,after));
    }
}
