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
import java.util.List;
import java.util.Objects;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
/** Only the identity policy is inherited; endpoint fields remain owned by their existing builders. */
final class StorageNfsIdentityMapping {
    static final String MODE = "nfs4IdentityMappingMode", REVISION = "nfs4IdentityPolicyRevision";
    final String mode; final long revision;
    StorageNfsIdentityMapping(String mode, long revision) {
        this.mode = valid(mode); this.revision = revision;
        if (revision < 0) throw new InvalidParameterValueException("Invalid NFS identity policy revision");
    }
    static String valid(String value) {
        if (!"NAME_DOMAIN".equals(value) && !"NUMERIC".equals(value))
            throw new InvalidParameterValueException("NFS identity mode must be NAME_DOMAIN or NUMERIC");
        return value;
    }
    static JsonObject config(String raw) {
        return raw == null || raw.trim().isEmpty() ? new JsonObject() : JsonParser.parseString(raw).getAsJsonObject();
    }
    static StorageNfsIdentityMapping from(JsonObject c) {
        return new StorageNfsIdentityMapping(c.has(MODE) ? c.get(MODE).getAsString() : "NAME_DOMAIN",
                c.has(REVISION) ? c.get(REVISION).getAsLong() : 0);
    }
    static StorageNfsIdentityMapping policy(List<StorageServiceProtocolVO> rows) {
        StorageNfsIdentityMapping result = null;
        for (StorageServiceProtocolVO row : rows) {
            StorageNfsIdentityMapping next = from(config(row.getConfigJson()));
            if (result != null && (!Objects.equals(result.mode, next.mode) || result.revision != next.revision))
                throw new InvalidParameterValueException("NFS_IDENTITY_POLICY_DRIFT");
            result = next;
        }
        return result == null ? new StorageNfsIdentityMapping("NAME_DOMAIN", 0) : result;
    }
    JsonObject overlay(String raw) {
        JsonObject c = config(raw); c.addProperty(MODE, mode); c.addProperty(REVISION, revision); return c;
    }
}
