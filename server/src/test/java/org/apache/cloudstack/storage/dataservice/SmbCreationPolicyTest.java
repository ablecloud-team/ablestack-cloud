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
import com.google.gson.JsonObject;

public class SmbCreationPolicyTest {
    @Test public void defaultsPreserveLegacyModes() {
        JsonObject value = SmbCreationPolicy.merge(new JsonObject(), null, null, null, null, null, null);
        Assert.assertEquals("0660", value.get("createMask").getAsString());
        Assert.assertEquals("0000", value.get("forceCreateMode").getAsString());
        Assert.assertEquals("0770", value.get("directoryMask").getAsString());
    }
    @Test public void forcingFileExecuteRequiresConfirmationOnlyWhenChanged() {
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbCreationPolicy.merge(new JsonObject(), "0775", "0775", "0775", "0775", false, false));
        JsonObject confirmed = SmbCreationPolicy.merge(new JsonObject(), "0775", "0775", "0775", "0775", false, true);
        Assert.assertEquals(confirmed, SmbCreationPolicy.merge(confirmed, "0775", "0775", "0775", "0775", false, false));
    }
    @Test public void rejectsMaskConflictsInheritanceAndNonOctalInjection() {
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbCreationPolicy.merge(new JsonObject(), "0600", "0060", null, null, false, true));
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbCreationPolicy.merge(new JsonObject(), null, "0600", null, null, true, true));
        for (String mode : new String[] {"1777", "0888", "0775\nforce user=root", "", "-1", "10000"}) {
            Assert.assertThrows(InvalidParameterValueException.class, () -> SmbCreationPolicy.merge(new JsonObject(), mode, null, null, null, null, true));
        }
    }
    @Test public void normalizationDoesNotChangeExistingDirectoryOptions() {
        JsonObject original = new JsonObject();original.addProperty("directoryMode", "2775");original.addProperty("crossProtocol", true);
        JsonObject result = SmbCreationPolicy.merge(original, "640", null, null, null, false, false);
        Assert.assertEquals("0640", result.get("createMask").getAsString());
        Assert.assertEquals("2775", result.get("directoryMode").getAsString());
        Assert.assertFalse(original.has("createMask"));
    }
}
