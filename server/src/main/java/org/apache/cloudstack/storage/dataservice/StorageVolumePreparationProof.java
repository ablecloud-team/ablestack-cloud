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

import java.util.Objects;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A filesystem header cannot attest successful formatting or authorize a forward mount. */
public final class StorageVolumePreparationProof {
    private StorageVolumePreparationProof() { }

    public static void project(JsonObject history, JsonObject current) {
        boolean complete = completionProven(history, current);
        history.addProperty("filesystemCompletionProven", complete);
        history.addProperty("resumeAllowed", complete);
        if (!complete) history.addProperty("resumeBlocker", "FORMATTER_COMPLETION_OR_CURRENT_IDENTITY_UNVERIFIED");
        else history.remove("resumeBlocker");
    }

    public static void requireCompleted(JsonObject history, JsonObject current) {
        if (!completionProven(history, current)) throw new CloudRuntimeException("Forward resume requires a durable successful formatter receipt and matching current serial/filesystem; partial DATA remains untouched");
    }

    public static boolean completionProven(JsonObject history, JsonObject current) {
        try {
            if (history == null || current == null || !flag(history, "success")
                    || !history.has("formatterActive") || flag(history, "formatterActive")
                    || flag(history, "terminationPending") || !"EXACT".equals(text(current, "mappingStatus"))
                    || !"VOLUME_SERIAL".equals(text(current, "matchedBy"))) return false;
            JsonObject operation = object(history, "operation"), receipt = object(operation, "formatterSuccessReceipt");
            if (operation == null || receipt == null || !flag(operation, "formatStarted")
                    || flag(operation, "terminationPending") || number(receipt, "schemaVersion") != 1
                    || number(receipt, "formatterExitCode") != 0 || number(operation, "formatterExitCode") != 0
                    || !positiveEpoch(receipt, "verifiedEpoch") || text(current, "filesystemUuid") == null
                    || !Set.of("xfs", "ext4").contains(text(current, "filesystem").toLowerCase(java.util.Locale.ROOT))) return false;
            for (String field : new String[] {"volumeUuid", "filesystemUuid", "filesystem"}) {
                if (!Objects.equals(text(current, field), text(operation, field))
                        || !Objects.equals(text(current, field), text(receipt, field))) return false;
            }
            return Objects.equals(text(current, "serial"), text(receipt, "serial"))
                    && number(current, "sizeBytes") == number(receipt, "sizeBytes");
        } catch (RuntimeException malformed) {return false;}
    }

    private static JsonObject object(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonObject() ? value.getAsJsonObject(field) : null;
    }
    private static boolean flag(JsonObject value, String field) {
        if (value == null || !value.has(field)) return false;
        JsonElement element = value.get(field);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Malformed formatter proof flag");
        return element.getAsBoolean();
    }
    private static boolean positiveEpoch(JsonObject value, String field) {
        return value != null && value.has(field) && value.get(field).isJsonPrimitive()
                && value.get(field).getAsJsonPrimitive().isNumber() && value.get(field).getAsBigDecimal().signum() > 0;
    }
    private static long number(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive() || !value.get(field).getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Missing formatter proof field");
        return value.get(field).getAsBigDecimal().longValueExact();
    }
    private static String text(JsonObject value, String field) {
        JsonElement element = value == null ? null : value.get(field);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
