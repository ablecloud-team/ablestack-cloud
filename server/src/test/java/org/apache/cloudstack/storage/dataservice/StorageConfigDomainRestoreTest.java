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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;

public class StorageConfigDomainRestoreTest {
    private JsonObject plan(JsonObject config) {
        JsonObject row = new JsonObject();row.addProperty("uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");row.addProperty("protocol", "SMB");row.addProperty("name", "reviewed");row.add("config", config);
        JsonObject change = new JsonObject();change.addProperty("kind", "file-shares");change.addProperty("sourceUuid", row.get("uuid").getAsString());change.add("desired", row);
        JsonArray creates = new JsonArray();creates.add(change);JsonObject plan = new JsonObject();plan.add("create", creates);plan.add("update", new JsonArray());plan.add("keep", new JsonArray());return plan;
    }
    @Test public void unknownOrWrongTypedLateResourceIsRejectedWithoutAnyManagerMutation() {
        StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);StorageConfigDomainRestore restore = new StorageConfigDomainRestore(manager);
        JsonObject config = new JsonObject();config.addProperty("executeScript", "untrusted");
        Assert.assertThrows(InvalidParameterValueException.class, () -> restore.validateBindings(plan(config)));
        config.remove("executeScript");config.addProperty("guestOk", "true");
        Assert.assertThrows(InvalidParameterValueException.class, () -> restore.validateBindings(plan(config)));Mockito.verifyNoInteractions(manager);
    }
    @Test public void safeRestoreBindingsNeverNeedAFormatOrCleanupFlag() {
        StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);
        JsonObject config = new JsonObject();config.addProperty("guestOk", false);config.addProperty("relativeSharePath", "smb/reviewed");
        new StorageConfigDomainRestore(manager).validateBindings(plan(config));Mockito.verifyNoInteractions(manager);
    }
}
