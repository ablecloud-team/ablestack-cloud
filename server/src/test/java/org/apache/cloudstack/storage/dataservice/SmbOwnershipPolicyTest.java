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

public class SmbOwnershipPolicyTest {
    @Test public void defaultsPreserveAuthenticatedIdentityAndDoNotChangeOwner() {
        JsonObject config = new JsonObject();config.addProperty("ownerUid", 1001001);config.addProperty("directoryMode", "0775");
        JsonObject result = SmbOwnershipPolicy.inheritance(config, null, null);
        Assert.assertEquals("AUTHENTICATED_USER", result.get("ownershipInheritance").getAsString());Assert.assertFalse(result.get("inheritGroup").getAsBoolean());
        Assert.assertEquals(1001001, result.get("ownerUid").getAsInt());Assert.assertEquals("0775", config.get("directoryMode").getAsString());
    }
    @Test public void parentOwnerGroupInheritanceNormalizesOnlyCurrentDirectoryMode() {
        JsonObject config = new JsonObject();config.addProperty("directoryMode", "0775");
        JsonObject result = SmbOwnershipPolicy.inheritance(config, "INHERIT_PARENT_OWNER", true);
        Assert.assertEquals("2775", result.get("directoryMode").getAsString());Assert.assertFalse(result.has("recursivePermission"));
    }
    @Test public void rejectsConflictingGuestForcedIdentityAndOrphanGroupFlag() {
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbOwnershipPolicy.inheritance(new JsonObject(), "AUTHENTICATED_USER", true));
        JsonObject guest = new JsonObject();guest.addProperty("guestOk", true);
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbOwnershipPolicy.inheritance(guest, "INHERIT_PARENT_OWNER", false));
        JsonObject forced = new JsonObject();forced.addProperty("posixOwnershipMode", "FORCED_UID_GID");
        Assert.assertThrows(InvalidParameterValueException.class, () -> SmbOwnershipPolicy.inheritance(forced, "INHERIT_PARENT_OWNER", false));
    }
}
