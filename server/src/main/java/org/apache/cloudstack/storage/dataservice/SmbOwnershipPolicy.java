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

import java.util.Locale;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;

/** Creation ownership is independent of protocol authentication and existing files. */
public final class SmbOwnershipPolicy {
    private SmbOwnershipPolicy() { }
    public static JsonObject forced(final JsonObject current, final String requested, final Long ownerUid, final Long ownerGid) {
        final JsonObject result = current.deepCopy();
        final String mode = requested == null ? (result.has("posixOwnershipMode") ? result.get("posixOwnershipMode").getAsString() : "AUTHENTICATED_USER")
                : requested.trim().toUpperCase(Locale.ROOT);
        if (!"AUTHENTICATED_USER".equals(mode) && !"FORCED_UID_GID".equals(mode)) throw new InvalidParameterValueException("Unknown SMB POSIX ownership mode");
        if (ownerUid != null) { protectedId(ownerUid);result.addProperty("ownerUid", ownerUid); }
        if (ownerGid != null) { protectedId(ownerGid);result.addProperty("ownerGid", ownerGid); }
        if ("FORCED_UID_GID".equals(mode)) {
            if (!result.has("ownerUid") || !result.has("ownerGid")) throw new InvalidParameterValueException("Forced SMB ownership requires owneruid and ownergid");
            protectedId(result.get("ownerUid").getAsLong());protectedId(result.get("ownerGid").getAsLong());
            if (result.has("ownershipInheritance") && "INHERIT_PARENT_OWNER".equals(result.get("ownershipInheritance").getAsString())) {
                throw new InvalidParameterValueException("Forced identity and parent-owner inheritance cannot be combined");
            }
            if (result.has("guestOk") && result.get("guestOk").getAsBoolean()) throw new InvalidParameterValueException("Forced identity with guest access is unsupported");
        }
        result.addProperty("posixOwnershipMode", mode);return result;
    }

    private static void protectedId(final Long value) {
        if (value == null || value < 10000 || value == 65534 || value > Integer.MAX_VALUE) {
            throw new InvalidParameterValueException("Forced identity UID/GID must be at least 10000 and must not use protected SystemVM IDs");
        }
    }

    public static JsonObject inheritance(final JsonObject current, final String requested, final Boolean inheritGroup) {
        final JsonObject result = current.deepCopy();
        final String mode = requested == null ? (result.has("ownershipInheritance") ? result.get("ownershipInheritance").getAsString() : "AUTHENTICATED_USER")
                : requested.trim().toUpperCase(Locale.ROOT);
        if (!"AUTHENTICATED_USER".equals(mode) && !"INHERIT_PARENT_OWNER".equals(mode)) {
            throw new InvalidParameterValueException("Unknown SMB parent ownership inheritance mode");
        }
        final boolean group = inheritGroup == null ? result.has("inheritGroup") && result.get("inheritGroup").getAsBoolean() : inheritGroup;
        if (group && !"INHERIT_PARENT_OWNER".equals(mode)) throw new InvalidParameterValueException("Parent group inheritance requires parent-owner inheritance");
        if ("INHERIT_PARENT_OWNER".equals(mode)) {
            if (result.has("posixOwnershipMode") && "FORCED_UID_GID".equals(result.get("posixOwnershipMode").getAsString())) {
                throw new InvalidParameterValueException("Parent-owner inheritance and forced UID/GID cannot be combined");
            }
            if (result.has("guestOk") && result.get("guestOk").getAsBoolean()) {
                throw new InvalidParameterValueException("Parent-owner inheritance requires authenticated account ACLs; guest is unsupported");
            }
        }
        if (group) {
            final int directory = Integer.parseInt(result.has("directoryMode") ? result.get("directoryMode").getAsString() : "0770", 8);
            result.addProperty("directoryMode", String.format(Locale.ROOT, "%04o", directory | 02000));
        }
        result.addProperty("ownershipInheritance", mode);result.addProperty("inheritGroup", group);return result;
    }
}
