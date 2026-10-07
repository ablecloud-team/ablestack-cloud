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

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Internal, typed API binding. Only current command parameters reach domain service validators. */
public final class StorageConfigCommandBinding {
    private StorageConfigCommandBinding() { }
    public static BaseCmd bind(Class<? extends BaseCmd> type, JsonObject parameters) {
        try {
            BaseCmd cmd = type.getDeclaredConstructor().newInstance();Map<String, Field> fields = new LinkedHashMap<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    Parameter parameter = field.getAnnotation(Parameter.class);
                    if (parameter != null) fields.put(parameter.name().toLowerCase(java.util.Locale.ROOT), field);
                }
            }
            for (Map.Entry<String, JsonElement> parameter : parameters.entrySet()) {
                Field field = fields.get(parameter.getKey().toLowerCase(java.util.Locale.ROOT));
                if (field == null) throw new InvalidParameterValueException("Unsupported configuration API parameter");
                JsonElement value = parameter.getValue();if (value == null || value.isJsonNull()) continue;
                Object converted;
                if (field.getType() == String.class) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new InvalidParameterValueException("Configuration text parameter has the wrong type");
                    converted = value.getAsString();
                    if (((String) converted).length() > (Set.of("accessentries", "defaultentries").contains(parameter.getKey().toLowerCase(java.util.Locale.ROOT)) ? 262144 : field.getAnnotation(Parameter.class).length())) throw new InvalidParameterValueException("Configuration parameter exceeds its bound");
                } else if (field.getType() == Boolean.class || field.getType() == boolean.class) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new InvalidParameterValueException("Configuration boolean parameter has the wrong type");
                    converted = value.getAsBoolean();
                } else if (field.getType() == Long.class || field.getType() == long.class || field.getType() == Integer.class || field.getType() == int.class) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new InvalidParameterValueException("Configuration numeric parameter has the wrong type");
                    long number;
                    try { number = new java.math.BigDecimal(value.getAsString()).longValueExact(); }
                    catch (ArithmeticException failure) { throw new InvalidParameterValueException("Configuration numeric parameter is not an integer"); }
                    if (field.getType() == Integer.class || field.getType() == int.class) {
                        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new InvalidParameterValueException("Configuration integer parameter exceeds its range");
                        converted = Integer.valueOf((int) number);
                    } else converted = Long.valueOf(number);
                } else throw new InvalidParameterValueException("Unsupported configuration parameter type");
                field.setAccessible(true);field.set(cmd, converted);
            }
            return cmd;
        } catch (ReflectiveOperationException failure) { throw new InvalidParameterValueException("Unable to construct the current configuration command"); }
    }
    public static JsonObject parameters(JsonObject resource, String kind) {
        JsonObject result = new JsonObject();
        Map<String, String> direct = Map.of("name", "name", "path", "path", "filesystem", "filesystem", "quota_bytes", "quotabytes",
                "principal_type", "principaltype", "principal", "principal", "permission", "permission",
                "relative_path", "relativepath", "listen_ip", "listenip", "port", "port");
        for (Map.Entry<String, String> field : direct.entrySet()) {
            if (resource.has(field.getKey()) && !resource.get(field.getKey()).isJsonNull()) result.add(field.getValue(), resource.get(field.getKey()).deepCopy());
        }
        if ("protocols".equals(kind) && resource.has("protocol")) result.add("protocol", resource.get("protocol").deepCopy());
        JsonObject config = resource.has("config") ? resource.getAsJsonObject("config") : new JsonObject();
        Set<String> informational = Set.of("posixPolicy", "volumeMode", "importMode", "posixPolicyUuid", "localAccount", "resourceKind", "type",
                "volumeName", "volumeUuid", "backstoreName", "devicePath", "blockBackstore", "schemaVersion", "managedDirectoryMode",
                "aclManaged", "crossProtocolPosix", "protocolMode", "idMappingMode", "securityType", "listenerGroups");
        for (Map.Entry<String, JsonElement> field : config.entrySet()) {
            String key = field.getKey();JsonElement value = field.getValue();if (value == null || value.isJsonNull() || informational.contains(key)) continue;
            String parameter = "relativeSharePath".equals(key) ? "relativepath" : "listenerGroupPorts".equals(key) ? "listenerports" :
                    "accessEntries".equals(key) ? "accessentries" : "defaultEntries".equals(key) ? "defaultentries" : key.toLowerCase(java.util.Locale.ROOT);
            if (value.isJsonArray() && Set.of("listenerports", "listenips").contains(parameter)) {
                java.util.List<String> values = new java.util.ArrayList<>();for (JsonElement item : value.getAsJsonArray()) values.add(item.getAsString());
                result.addProperty(parameter, String.join(",", values));
            } else if (value.isJsonArray() && Set.of("accessentries", "defaultentries").contains(parameter)) result.addProperty(parameter, value.toString());
            else result.add(parameter, value.deepCopy());
        }
        if ("protocols".equals(kind)) {
            for (String mode : new String[] {"protocolMode", "idMappingMode"}) if (config.has(mode) && !config.get(mode).isJsonNull()) result.add(mode.toLowerCase(java.util.Locale.ROOT), config.get(mode).deepCopy());
        }
        if ("file-shares".equals(kind)) {
            if (resource.has("posixPolicyUuid") && "NFS".equals(resource.has("protocol") ? resource.get("protocol").getAsString() : null)) {
                // The referenced common POSIX policy owns these attributes; do not reapply legacy NFS ownership.
                for (String key : new String[] {"owneruid", "ownergid", "mode", "recursivepermission"}) result.remove(key);
            }
            result.addProperty("importmode", "MOUNT_EXISTING");result.addProperty("createdirectory", false);// Cleanup defaults remain false; backup restore never requests volume deletion.
        }
        return result;
    }
}
