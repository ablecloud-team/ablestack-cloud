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
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;

public class StorageConfigSemanticValidationTest {
    @Test public void versionRangeRejectsUnknownOlderAndFutureManager() {
        JsonObject manifest = new JsonObject();JsonObject range = new JsonObject();range.addProperty("minimumManagerVersion", "4.23.0");range.addProperty("maximumManagerVersionExclusive", "4.24.0");manifest.add("configurationCompatibility", range);
        StorageConfigSemanticValidation.compatibility(manifest, "4.23.0.0-Mold.Europa-202610011115");
        for (String version : new String[] {"unknown", "4.22.1", "4.24.0", "9.0.0"}) Assert.assertThrows(InvalidParameterValueException.class, () -> StorageConfigSemanticValidation.compatibility(manifest, version));
        Assert.assertThrows(InvalidParameterValueException.class, () -> StorageConfigSemanticValidation.compatibility(new JsonObject(), "4.23.0"));
    }
    @Test public void unsafeRecursivePermissionIsRejectedBeforeCurrentServiceCalls() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String kind : StorageConfigRestorePlan.ROW_KEYS.keySet()) entries.put("desired/"+kind+".json", "[]".getBytes(StandardCharsets.UTF_8));
        JsonObject share = new JsonObject();share.addProperty("uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");share.addProperty("protocol", "NFS");
        JsonObject config = new JsonObject();config.addProperty("recursivePermission", true);share.add("config", config);
        JsonArray shares = new JsonArray();shares.add(share);entries.put("desired/file-shares.json", shares.toString().getBytes(StandardCharsets.UTF_8));
        StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);
        Assert.assertThrows(InvalidParameterValueException.class, () -> StorageConfigSemanticValidation.validate(entries, manager));Mockito.verifyNoInteractions(manager);
    }
}
