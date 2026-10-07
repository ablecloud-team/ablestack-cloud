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

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;

/** Share-local creation options. Existing objects are never chmod'ed by this policy. */
public final class SmbCreationPolicy {
    private SmbCreationPolicy() { }

    public static JsonObject merge(final JsonObject current, final String createMask, final String forceCreateMode,
            final String directoryMask, final String forceDirectoryMode, final Boolean inheritPermissions,
            final Boolean confirmFileExecute) {
        final JsonObject result = current.deepCopy();
        setMode(result, "createMask", createMask, "0660");
        setMode(result, "forceCreateMode", forceCreateMode, "0000");
        setMode(result, "directoryMask", directoryMask, "0770");
        setMode(result, "forceDirectoryMode", forceDirectoryMode, "0000");
        if (inheritPermissions != null || !result.has("inheritPermissions")) {
            result.addProperty("inheritPermissions", Boolean.TRUE.equals(inheritPermissions));
        }
        final int fileForce = mode(result, "forceCreateMode");
        final int directoryForce = mode(result, "forceDirectoryMode");
        if ((fileForce & ~mode(result, "createMask")) != 0 || (directoryForce & ~mode(result, "directoryMask")) != 0) {
            throw new InvalidParameterValueException("Forced mode must be contained by the corresponding mask");
        }
        if (result.get("inheritPermissions").getAsBoolean() && (fileForce != 0 || directoryForce != 0)) {
            throw new InvalidParameterValueException("Parent permission inheritance cannot be combined with forced creation modes");
        }
        final boolean changed = forceCreateMode != null && (!current.has("forceCreateMode")
                || !result.get("forceCreateMode").equals(current.get("forceCreateMode")));
        if ((fileForce & 0111) != 0 && changed && !Boolean.TRUE.equals(confirmFileExecute)) {
            throw new InvalidParameterValueException("Forcing execute permission on regular files requires confirmfileexecute=true");
        }
        return result;
    }

    private static int mode(final JsonObject config, final String key) {
        return Integer.parseInt(config.get(key).getAsString(), 8);
    }

    private static void setMode(final JsonObject config, final String key, final String input, final String defaultValue) {
        final String value = input == null ? (config.has(key) ? config.get(key).getAsString() : defaultValue) : input.trim();
        if (!value.matches("0?[0-7]{3}")) {
            throw new InvalidParameterValueException(key + " must be octal 0000 through 0777");
        }
        config.addProperty(key, String.format(java.util.Locale.ROOT, "%04o", Integer.parseInt(value, 8)));
    }
}
