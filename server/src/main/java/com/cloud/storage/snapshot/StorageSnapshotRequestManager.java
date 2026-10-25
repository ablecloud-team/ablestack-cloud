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
package com.cloud.storage.snapshot;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiServerService.AsyncCmdResult;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.command.user.snapshot.ArchiveSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.ExtractSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.RevertSnapshotCmd;
import org.apache.cloudstack.api.command.user.template.CreateTemplateCmd;
import org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.commons.codec.digest.DigestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Reuse an accepted job before any repeated resource allocation or mutation. */
public class StorageSnapshotRequestManager {
    static final String FINGERPRINT = "ctxStorageSnapshotRequestFingerprint";
    private static final Set<String> TRANSIENT = Set.of("response", "sessionkey", "apikey", "signature", "signatureversion", "expires", "customjobid", "httpmethod");
    private final AsyncJobDao jobDao;

    public StorageSnapshotRequestManager(AsyncJobDao jobDao) { this.jobDao = jobDao; }

    @FunctionalInterface
    public interface Work { AsyncCmdResult run() throws Exception; }

    public static boolean supports(BaseAsyncCmd cmd, Map<String, String> params) {
        return cmd instanceof CreateSnapshotCmd || cmd instanceof DeleteSnapshotCmd || cmd instanceof RevertSnapshotCmd
                || cmd instanceof ArchiveSnapshotCmd || cmd instanceof CopySnapshotCmd || cmd instanceof ExtractSnapshotCmd
                || cmd instanceof AttachVolumeCmd || (params.containsKey("snapshotid") && (cmd instanceof CreateVolumeCmd || cmd instanceof CreateTemplateCmd));
    }

    static String requestId(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equals(value)) { throw new IllegalArgumentException(); }
            return id;
        } catch (RuntimeException invalid) { throw new InvalidParameterValueException("Snapshot customjobid must be a canonical UUID"); }
    }

    static String fingerprint(Map<String, String> params) {
        Map<String, String> canonical = new TreeMap<>();
        params.forEach((key, value) -> {
            if (!TRANSIENT.contains(key.toLowerCase()) && !key.toLowerCase().startsWith("ctx")) { canonical.put(key.toLowerCase(), value); }
        });
        return DigestUtils.sha256Hex(new Gson().toJson(canonical));
    }

    protected AsyncCmdResult locked(String id, Work work) throws Exception {
        GlobalLock lock = GlobalLock.getInternLock("storage-snapshot-request-" + id);
        boolean acquired = false;
        try {
            acquired = lock.lock(30);
            if (!acquired) { throw new CloudRuntimeException("Snapshot request is busy; query customjobid before any new request"); }
            return work.run();
        } finally {
            if (acquired) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    public AsyncCmdResult submit(BaseAsyncCmd cmd, Map<String, String> params, long userId, long accountId, Work work) throws Exception {
        String id = requestId(params.get(ApiConstants.CUSTOM_JOB_ID));
        String hash = fingerprint(params);
        return locked(id, () -> {
            AsyncJobVO previous = jobDao.findByUuidIncludingRemoved(id);
            if (previous != null) {
                if (previous.getUserId() != userId || previous.getAccountId() != accountId) {
                    throw new PermissionDeniedException("Snapshot request belongs to another caller");
                }
                JsonObject info = new Gson().fromJson(previous.getCmdInfo(), JsonObject.class);
                if (!cmd.getClass().getName().equals(previous.getCmd()) || info == null || !info.has(FINGERPRINT)
                        || !hash.equals(info.get(FINGERPRINT).getAsString())) {
                    throw new InvalidParameterValueException("Snapshot request UUID belongs to a different command, owner, project, target or specification");
                }
                // A replay needs only the existing job receipt. Resource UUIDs remain in its result.
                return new AsyncCmdResult(null, null, cmd, previous.getId());
            }
            params.put(FINGERPRINT, hash);
            return work.run();
        });
    }
}
