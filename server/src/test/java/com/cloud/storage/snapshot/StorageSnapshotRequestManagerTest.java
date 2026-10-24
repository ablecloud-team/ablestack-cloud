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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cloudstack.api.ApiServerService.AsyncCmdResult;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotCmd;
import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.google.gson.Gson;

public class StorageSnapshotRequestManagerTest {
    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private StorageSnapshotRequestManager manager;
    private AsyncJobDao dao;
    private CreateSnapshotCmd cmd;
    private Map<String, String> params;
    private AtomicInteger calls;

    @Before public void setup() {
        dao = mock(AsyncJobDao.class);
        manager = new StorageSnapshotRequestManager(dao) {
            @Override protected synchronized AsyncCmdResult locked(String id, Work work) throws Exception { return work.run(); }
        };
        cmd = new CreateSnapshotCmd();
        params = new HashMap<>(Map.of("customjobid", ID, "volumeid", "source", "name", "fixture"));
        calls = new AtomicInteger();
    }
    private AsyncCmdResult work() { calls.incrementAndGet(); return new AsyncCmdResult(7L, "resource", cmd, 8L); }
    private void existing() {
        AsyncJobVO job = mock(AsyncJobVO.class);
        when(job.getId()).thenReturn(8L); when(job.getUserId()).thenReturn(2L); when(job.getAccountId()).thenReturn(3L);
        when(job.getCmd()).thenReturn(cmd.getClass().getName());
        when(job.getCmdInfo()).thenReturn(new Gson().toJson(Map.of(StorageSnapshotRequestManager.FINGERPRINT, StorageSnapshotRequestManager.fingerprint(params))));
        when(dao.findByUuidIncludingRemoved(ID)).thenReturn(job);
    }
    @Test public void firstRequestStampsServerFingerprintBeforeAllocation() throws Exception {
        manager.submit(cmd, params, 2, 3, this::work);
        assertEquals(1, calls.get()); assertTrue(params.containsKey(StorageSnapshotRequestManager.FINGERPRINT));
    }
    @Test public void acceptedRequestReusesJobBeforeResourceAllocation() throws Exception {
        existing(); AsyncCmdResult result = manager.submit(cmd, params, 2, 3, this::work);
        assertEquals(8L, result.jobId); assertEquals(0, calls.get());
    }
    @Test(expected = PermissionDeniedException.class) public void foreignUserCannotReuseReceipt() throws Exception {
        existing(); manager.submit(cmd, params, 9, 3, this::work);
    }
    @Test(expected = PermissionDeniedException.class) public void foreignAccountCannotReuseReceipt() throws Exception {
        existing(); manager.submit(cmd, params, 2, 9, this::work);
    }
    @Test(expected = InvalidParameterValueException.class) public void changedSourceCannotReuseReceipt() throws Exception {
        existing(); params.put("volumeid", "other"); manager.submit(cmd, params, 2, 3, this::work);
    }
    @Test(expected = InvalidParameterValueException.class) public void changedProjectCannotReuseReceipt() throws Exception {
        existing(); params.put("projectid", "other"); manager.submit(cmd, params, 2, 3, this::work);
    }
    @Test(expected = InvalidParameterValueException.class) public void changedSpecificationCannotReuseReceipt() throws Exception {
        existing(); params.put("name", "other"); manager.submit(cmd, params, 2, 3, this::work);
    }
    @Test(expected = InvalidParameterValueException.class) public void anotherCommandCannotReuseReceipt() throws Exception {
        existing(); manager.submit(new DeleteSnapshotCmd(), params, 2, 3, this::work);
    }
    @Test(expected = InvalidParameterValueException.class) public void abbreviatedUuidIsRejected() {
        StorageSnapshotRequestManager.requestId("0-0-4-8-1");
    }
    @Test public void authenticationAndResponseFormatAreNotSpecification() {
        String before = StorageSnapshotRequestManager.fingerprint(params);
        params.put("sessionkey", "new-session"); params.put("response", "xml"); params.put("ctxUserId", "injected");
        assertEquals(before, StorageSnapshotRequestManager.fingerprint(params));
    }
    @Test public void ordinaryCreateVolumeStaysOnExistingPath() {
        assertFalse(StorageSnapshotRequestManager.supports(new CreateVolumeCmd(), params));
        params.put("snapshotid", "snapshot"); assertTrue(StorageSnapshotRequestManager.supports(new CreateVolumeCmd(), params));
    }
    @Test public void serverFingerprintCannotBeSpoofedByCaller() throws Exception {
        params.put(StorageSnapshotRequestManager.FINGERPRINT, "spoofed");
        manager.submit(cmd, params, 2, 3, this::work);
        assertEquals(StorageSnapshotRequestManager.fingerprint(params), params.get(StorageSnapshotRequestManager.FINGERPRINT));
    }
}
