// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;

/** Shared with Python through its standard-library SQLite helper. */
final class LibvirtAblestackJobHistory {
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT).getParent();

    private LibvirtAblestackJobHistory() { }

    static JsonObject read(String id) throws IOException {
        if (Files.notExists(ROOT.resolve("job-history.sqlite"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { return null; }
        return execute(ROOT, id, "read");
    }

    static boolean closed(String id) throws IOException {
        JsonObject record = read(id);
        return record != null && record.has("closedReason") && !record.get("closedReason").isJsonNull()
                && !record.get("closedReason").getAsString().isBlank();
    }

    static JsonObject retiredRestore(String id) throws IOException {
        JsonObject record = read(id);
        return record != null && record.has("kind") && "RESTORE".equals(record.get("kind").getAsString()) ? record : null;
    }

    static JsonObject compactRestore(String id) throws IOException {
        return execute(ROOT, id, "compact-restore");
    }

    static void compact(String id) throws IOException {
        if (Files.notExists(ROOT, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { return; }
        execute(ROOT, id, "compact");
    }

    static JsonObject execute(Path root, String id, String action) throws IOException {
        String helper = Script.findScript("/usr/share/cloudstack-common/scripts/vm/hypervisor/kvm", "thirdparty_job_history.py");
        if (helper == null) { helper = Script.findScript("scripts/vm/hypervisor/kvm", "thirdparty_job_history.py"); }
        if (helper == null) { throw new IOException("Host job history helper is unavailable"); }
        return execute(root, id, action, Path.of(helper));
    }

    static JsonObject execute(Path root, String id, String action, Path helper) throws IOException {
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(30000,
                "python3", helper.toString(), action, "--root", root.toString(), "--job-id", id);
        if (result.first() != 0) { throw new IOException("Host job history " + action + " failed: " + result.second()); }
        try {
            var value = new Gson().fromJson(result.second().trim(), com.google.gson.JsonElement.class);
            if (value == null || value.isJsonNull()) { return null; }
            if (!value.isJsonObject()) { throw new IOException("Host job history response is not an object"); }
            JsonObject record = value.getAsJsonObject();
            if (record != null && (!record.has("version") || record.get("version").getAsInt() != 1
                    || !record.has("jobId") || !id.equals(record.get("jobId").getAsString()))) {
                throw new IOException("Host job history belongs to another job");
            }
            return record;
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("Host job history response is unconfirmed", e);
        }
    }
}
