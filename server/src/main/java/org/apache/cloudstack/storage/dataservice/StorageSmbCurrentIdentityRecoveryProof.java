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

import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Closed public evidence for preserving CURRENT identity while restoring SOURCE configuration. */
public final class StorageSmbCurrentIdentityRecoveryProof {
    public static final String MODE = "RETAIN_CURRENT_LOCAL_IDENTITY_RESTORE_SOURCE_CONFIG";
    public static final String REVIEW = "CURRENT_LOCAL_SMB_IDENTITY_RECOVERY_REVIEW";
    public static final String PHASE = "CURRENT_LOCAL_IDENTITY_RETAINED_SOURCE_CONFIG_ROLLED_BACK";
    private StorageSmbCurrentIdentityRecoveryProof() { }

    static JsonObject scope(JsonObject context) {
        JsonObject scope = new JsonObject();
        for (String field : Set.of("instanceUuid", "operationUuid", "revision")) scope.add(field, context.get(field).deepCopy());
        return scope;
    }
    static boolean literal(JsonObject object, String field, boolean expected) {
        JsonElement value = object == null ? null : object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean() == expected;
    }
    static String string(JsonObject object, String field) {
        JsonElement value = object == null ? null : object.get(field);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Missing typed CURRENT identity field: " + field);
        return value.getAsString();
    }
    static long number(JsonObject object, String field) {
        JsonElement value = object == null ? null : object.get(field);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Missing numeric CURRENT identity field: " + field);
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException invalid) { throw new CloudRuntimeException("Non-integral CURRENT identity field", invalid); }
    }
    static JsonObject object(JsonObject value, String field) {
        require(value != null && value.has(field) && value.get(field).isJsonObject(), "Missing CURRENT identity object: " + field);
        return value.getAsJsonObject(field);
    }
    static void base(JsonObject context, JsonObject result) {
        require(literal(result, "success", true) && scope(context).equals(result.get("scope")), "CURRENT identity result has a foreign scope");
    }
    static void review(JsonObject context, JsonObject result, double now) {
        base(context, result);
        require(REVIEW.equals(string(result, "kind")) && number(result, "schemaVersion") == 1 && literal(result, "sideEffects", false),
                "CURRENT identity review is not a read-only typed observation");
        for (String field : Set.of("sourceGeneration", "sourceConfigurationSha256", "rootVmBinding", "runtimePin"))
            require(context.get(field).equals(result.get(field)), "CURRENT identity review changed its protected " + field);
        require(string(result, "currentReviewHash").matches("[a-f0-9]{64}"), "CURRENT identity approval digest is invalid");
        JsonElement epoch = result.get("generatedEpoch");
        require(epoch != null && epoch.isJsonPrimitive() && epoch.getAsJsonPrimitive().isNumber(), "CURRENT review has no numeric freshness");
        double elapsed = now - epoch.getAsDouble();
        require(Double.isFinite(elapsed) && elapsed >= 0 && elapsed <= 60, "CURRENT identity review expired");
        publicFacts(result);
    }
    static JsonObject publicFacts(JsonObject review) {
        JsonObject facts = object(review, "currentFacts");
        require(facts.keySet().equals(Set.of("bootId", "publicNamespaceSids", "databases", "units", "listeners",
                "identityHolders", "configurationSha256", "sessions", "loadedDaemonSidVerified")), "CURRENT public facts are not closed");
        String boot = string(facts, "bootId");
        require(boot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && boot.equals(string(review, "bootId")) && literal(facts, "loadedDaemonSidVerified", false), "CURRENT boot/namespace observation is invalid");
        JsonObject databases = object(facts, "databases");
        require(databases.keySet().equals(Set.of("PASSDB", "SECRETS")), "CURRENT database set is not exact");
        for (String field : databases.keySet()) {
            JsonObject file = object(databases, field);
            require(literal(file, "present", true) && number(file, "device") >= 0 && number(file, "inode") > 0
                    && number(file, "uid") == 0 && number(file, "gid") == 0 && "0600".equals(string(file, "mode"))
                    && ("/var/lib/samba/private/" + ("PASSDB".equals(field) ? "passdb.tdb" : "secrets.tdb")).equals(string(file, "path")),
                    "CURRENT protected database identity is absent or foreign");
        }
        require(facts.has("units") && facts.get("units").isJsonArray() && facts.getAsJsonArray("units").size() > 0
                && facts.has("publicNamespaceSids") && facts.get("publicNamespaceSids").isJsonArray()
                && facts.getAsJsonArray("publicNamespaceSids").size() > 0, "CURRENT owned masters/namespace observations are absent");
        Set<String> namespaces = new java.util.HashSet<>();
        for (JsonElement value : facts.getAsJsonArray("publicNamespaceSids")) {
            require(value.isJsonObject(), "CURRENT local namespace is malformed");JsonObject namespace = value.getAsJsonObject();
            require(namespace.keySet().equals(Set.of("samNamespace", "machineSid"))
                    && string(namespace, "samNamespace").matches("[A-Z0-9][A-Z0-9_.-]{0,62}")
                    && namespaces.add(string(namespace, "samNamespace"))
                    && string(namespace, "machineSid").matches("S-1-5-21-[0-9]+-[0-9]+-[0-9]+"), "CURRENT local SAM namespace is unobserved");
            for (String field : string(namespace, "machineSid").substring(9).split("-")) {
                try { require(new java.math.BigInteger(field).signum() > 0 && new java.math.BigInteger(field).bitLength() <= 32, "CURRENT SAM component is invalid"); }
                catch (NumberFormatException invalid) { throw new CloudRuntimeException("CURRENT SAM component is malformed", invalid); }
            }
        }
        Set<String> units = new java.util.HashSet<>();Set<Long> smbPids = new java.util.HashSet<>();
        for (JsonElement value : facts.getAsJsonArray("units")) {
            require(value.isJsonObject(), "CURRENT master observation is malformed");JsonObject unit = value.getAsJsonObject();
            require(unit.keySet().equals(Set.of("unit", "pid", "startTicks", "executable", "vendorUnit", "argv", "cgroup", "configurationPath")),
                    "CURRENT master fields are incomplete");
            String name = string(unit, "unit");
            require(Set.of("smbd.service", "nmbd.service").contains(name) && units.add(name) && number(unit, "pid") > 0
                    && string(unit, "startTicks").matches("[1-9][0-9]{0,18}")
                    && ("0::/system.slice/" + name).equals(string(unit, "cgroup"))
                    && "/etc/samba/smb.conf".equals(string(unit, "configurationPath")), "CURRENT master scope is foreign");
            JsonObject executable = object(unit, "executable"), vendor = object(unit, "vendorUnit");
            String binaryPath = "/usr/sbin/" + name.substring(0, name.length() - 8);
            require(binaryPath.equals(string(executable, "path")) && string(executable, "sha256").matches("[a-f0-9]{64}")
                    && number(executable, "device") >= 0 && number(executable, "inode") > 0
                    && ("/lib/systemd/system/" + name).equals(string(vendor, "path"))
                    && string(vendor, "sha256").matches("[a-f0-9]{64}") && number(vendor, "device") >= 0 && number(vendor, "inode") > 0,
                    "CURRENT master package file identity is malformed");
            require(unit.has("argv") && unit.get("argv").isJsonArray() && unit.getAsJsonArray("argv").size() > 0
                    && unit.getAsJsonArray("argv").get(0).isJsonPrimitive()
                    && unit.getAsJsonArray("argv").get(0).getAsJsonPrimitive().isString()
                    && binaryPath.equals(unit.getAsJsonArray("argv").get(0).getAsString()), "CURRENT master argv is unavailable");
            for (JsonElement arg : unit.getAsJsonArray("argv")) require(arg.isJsonPrimitive() && arg.getAsJsonPrimitive().isString()
                    && Set.of(binaryPath, "--foreground", "--no-process-group", "-F").contains(arg.getAsString()), "CURRENT master argv is foreign");
            if ("smbd.service".equals(name)) smbPids.add(number(unit, "pid"));
        }
        require(units.contains("smbd.service"), "CURRENT owned default SMB master is absent");
        require(string(facts, "configurationSha256").matches("[a-f0-9]{64}")
                && facts.has("listeners") && facts.get("listeners").isJsonArray() && facts.getAsJsonArray("listeners").size() > 0
                && facts.has("identityHolders") && facts.get("identityHolders").isJsonArray(), "CURRENT listener/holder/configuration facts are malformed");
        for (JsonElement value : facts.getAsJsonArray("listeners")) {
            require(value.isJsonObject(), "CURRENT listener row is malformed");JsonObject listener = value.getAsJsonObject();
            require(listener.keySet().equals(Set.of("ip", "port", "state", "pids")) && number(listener, "port") == 445
                    && "LISTEN".equals(string(listener, "state")) && Set.of("0.0.0.0", "::").contains(string(listener, "ip"))
                    && listener.get("pids") != null && listener.get("pids").isJsonArray(), "CURRENT listener is foreign");
            Set<Long> owners = new java.util.HashSet<>();
            for (JsonElement pid : listener.getAsJsonArray("pids")) {
                JsonObject row = new JsonObject();row.add("pid", pid);require(owners.add(number(row, "pid")), "CURRENT listener owners are duplicate");
            }
            require(owners.equals(smbPids), "CURRENT listener owner PID is foreign");
        }
        for (JsonElement value : facts.getAsJsonArray("identityHolders")) {
            require(value.isJsonObject(), "CURRENT descriptor holder is malformed");JsonObject holder = value.getAsJsonObject();
            require(holder.keySet().equals(Set.of("pid", "fd", "path", "device", "inode", "deleted"))
                    && number(holder, "pid") > 0 && number(holder, "fd") >= 0 && literal(holder, "deleted", false), "CURRENT descriptor is unavailable or stale");
            JsonObject file = null;
            for (String field : databases.keySet()) if (string(object(databases, field), "path").equals(string(holder, "path"))) file = object(databases, field);
            require(file != null && number(file, "device") == number(holder, "device") && number(file, "inode") == number(holder, "inode"),
                    "CURRENT descriptor does not match the protected database inode");
        }
        JsonObject sessions = object(facts, "sessions");
        for (String field : Set.of("available", "lockingDatabasesAligned", "safeToRebind"))
            require(literal(sessions, field, true), "CURRENT sessions are unobserved");
        for (String field : Set.of("establishedTcpCount", "synRecvTcpCount", "smbSessionCount", "treeConnectionCount", "openFileCount", "byteLockOpenFileCount"))
            require(number(sessions, field) == 0, "Live CURRENT sessions prevent maintenance");
        JsonObject result = new JsonObject();result.addProperty("bootId", boot);result.addProperty("databaseCount", 2);
        result.addProperty("ownedMasterCount", facts.getAsJsonArray("units").size());result.addProperty("sessionsVerifiedEmpty", true);
        result.addProperty("namespaceObserved", true);result.addProperty("loadedDaemonSidVerified", false);return result;
    }
    static void stopped(JsonObject context, JsonObject review, JsonObject result) {
        base(context, result);
        require("CURRENT_LOCAL_SMB_IDENTITY_STOPPED".equals(string(result, "kind"))
                && string(review, "currentReviewHash").equals(string(result, "currentReviewHash"))
                && string(review, "bootId").equals(string(result, "bootId")) && literal(result, "currentStoppedVerified", true)
                && literal(result, "originalIdentityRestored", false) && literal(result, "automaticSmbExposure", false),
                "CURRENT identity STOP lacks its approved scope proof");
    }
    static void exported(JsonObject context, JsonObject result) {
        base(context, result);JsonObject receipt = object(result, "currentIdentityCheckpoint"), capsule = object(result, "capsule");
        JsonObject reference = object(result, "currentIdentityReference");
        require(receipt.keySet().equals(Set.of("kind", "scope", "capsuleSha256", "sourceConfigurationSha256", "checkpointRecordSha256"))
                && "CURRENT_LOCAL_SMB_AFTERSTOP_IDENTITY_CHECKPOINT".equals(string(receipt, "kind"))
                && scope(context).equals(receipt.get("scope")) && context.get("sourceConfigurationSha256").equals(receipt.get("sourceConfigurationSha256"))
                && string(capsule, "sha256").matches("[a-f0-9]{64}") && string(capsule, "sha256").equals(string(receipt, "capsuleSha256"))
                && reference.keySet().equals(Set.of("operationUuid", "sha256"))
                && context.get("operationUuid").equals(reference.get("operationUuid"))
                && string(reference, "sha256").matches("[a-f0-9]{64}") && string(reference, "sha256").equals(string(receipt, "checkpointRecordSha256")),
                "CURRENT checkpoint is not its independent AFTERSTOP encrypted authority");
    }
    static void retained(JsonObject context, JsonObject reference, JsonObject result) {
        base(context, result);
        require("LOCAL_IDENTITY_RETAINED_WITHOUT_SMB_CONFIG".equals(string(result, "kind"))
                && literal(result, "currentIdentityRetained", true) && literal(result, "originalIdentityRestored", false)
                && literal(result, "originalConfigurationSourceRestored", true) && literal(result, "automaticSmbExposure", false)
                && context.get("sourceGeneration").equals(result.get("generation"))
                && context.get("sourceConfigurationSha256").equals(result.get("sourceConfigurationSha256"))
                && reference.equals(result.get("currentIdentityReference")), "CURRENT identity retention was not verified against SOURCE configuration");
    }
    static void require(boolean value, String message) { if (!value) throw new CloudRuntimeException(message); }
}
