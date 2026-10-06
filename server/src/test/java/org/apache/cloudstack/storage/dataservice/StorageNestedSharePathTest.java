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

import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonParser;

public class StorageNestedSharePathTest {
    private final StorageServiceManagerImpl manager = new StorageServiceManagerImpl();

    @Test public void explicitNestedDirectoryIsIndependentOfVisibleShareName() {
        Assert.assertEquals("/export/share/project-a", manager.resolveNestedSharePath(null, "project", "share/project-a", 7L, true));
        Assert.assertEquals("/export/share/project-a", manager.resolveNestedSharePath(null, "project", "share/project-a", 7L, false));
        Assert.assertEquals("/export/legacy", manager.resolveNestedSharePath(null, "legacy", null, 7L, true));
    }

    @Test public void rejectsTraversalAbsoluteEmptyAndAmbiguousPaths() {
        for (String path : new String[] {"", "/share", "../share", "share/../other", "share/./other", "share\\other", "share/with space"}) {
            Assert.assertThrows(path, InvalidParameterValueException.class, () -> manager.normalizeRelativeSharePath(path));
        }
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.resolveNestedSharePath(null, "child", "parent/child", null, true));
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.resolveNestedSharePath("/export/other", "child", "parent/child", 7L, true));
    }

    @Test public void pathChangeDiscardsStalePhysicalObservationButKeepsPolicy() {
        String config = manager.storeRelativeSharePath("{\"readOnly\":true,\"backingPath\":\"/old\",\"lastInspection\":{}}", "share/child");
        com.google.gson.JsonObject parsed = new JsonParser().parse(config).getAsJsonObject();
        Assert.assertTrue(parsed.get("readOnly").getAsBoolean());
        Assert.assertEquals("share/child", parsed.get("relativeSharePath").getAsString());
        Assert.assertFalse(parsed.has("backingPath"));
        Assert.assertFalse(parsed.has("lastInspection"));
    }
}
