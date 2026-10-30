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
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupStart;
import org.apache.logging.log4j.LogManager;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.storage.Volume;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

public class LibvirtAblestackBackupStartContentionTest {
    @Test
    public void stalledLaunchDoesNotParkStatusAbortOrDuplicateStartThreads() throws Exception {
        ThirdPartyBackupStart.Plan plan = new ThirdPartyBackupStart.Plan();
        plan.jobId = "backup-Aa";
        plan.hostId = 4L;
        plan.backupPath = "/staging/ablestack-commvault/vm/2026.10.29.17.46.39";
        plan.manifest = ThirdPartyBackupManifest.create("ablestack-commvault", plan.jobId, "vm", "2026.10.29.17.46.39", "FULL",
                "host-4", plan.backupPath, "QCOW2", Collections.singletonList(new Backup.VolumeInfo("volume-1", "root.qcow2",
                        Volume.Type.ROOT, 1073741824L, 0L, null, null, null)), null, null);
        Method localLock = LibvirtAblestackBackupStartHelper.class.getDeclaredMethod("localLock", String.class);
        localLock.setAccessible(true);
        ReentrantLock lock = (ReentrantLock) localLock.invoke(null, plan.jobId);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockedLaunch = workers.submit(() -> {
                lock.lock();
                try {
                    held.countDown();
                    assertTrue(release.await(15, TimeUnit.SECONDS));
                } finally {
                    lock.unlock();
                }
                return null;
            });
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertEquals(plan.jobId.hashCode(), "backup-BB".hashCode());
            ReentrantLock otherJob = (ReentrantLock) localLock.invoke(null, "backup-BB");
            assertTrue(otherJob.tryLock());
            otherJob.unlock();
            for (String action : new String[]{"BACKUP_START_PREPARE", "BACKUP_START_STATUS", "BACKUP_START_ABORT"}) {
                workers.submit(() -> {
                    try (MockedStatic<LibvirtAblestackJobHistory> history = mockStatic(LibvirtAblestackJobHistory.class)) {
                        history.when(() -> LibvirtAblestackJobHistory.closed(plan.jobId)).thenReturn(false);
                        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                                () -> LibvirtAblestackBackupStartHelper.control(plan, action, LogManager.getLogger()));
                        assertTrue(error.getMessage().contains("retry shortly"));
                    }
                }).get(2, TimeUnit.SECONDS);
            }
            AtomicBoolean prepared = new AtomicBoolean();
            workers.submit(() -> {
                try (MockedStatic<LibvirtAblestackJobHistory> history = mockStatic(LibvirtAblestackJobHistory.class)) {
                    history.when(() -> LibvirtAblestackJobHistory.closed(plan.jobId)).thenReturn(false);
                    var answer = LibvirtAblestackBackupStartHelper.launch(new AblestackCommvaultTakeBackupCommand("vm", plan.backupPath),
                            LogManager.getLogger(), "trace", "commvault", plan.jobId, "vm", plan.backupPath, "FULL", new Gson().toJson(plan), () -> {
                                prepared.set(true);
                                return null;
                            });
                    assertFalse(answer.getResult());
                    assertTrue(answer.getDetails().contains("dispatch is unconfirmed"));
                }
            }).get(2, TimeUnit.SECONDS);
            assertFalse(prepared.get());
            release.countDown();
            blockedLaunch.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
