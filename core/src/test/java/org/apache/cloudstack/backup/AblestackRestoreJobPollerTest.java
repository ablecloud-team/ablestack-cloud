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

package org.apache.cloudstack.backup;

import com.cloud.utils.exception.CloudRuntimeException;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class AblestackRestoreJobPollerTest {
    @Test
    public void waitsUntilRestoreJobCompletes() {
        final AtomicInteger queryCount = new AtomicInteger();

        final BackupAnswer result = AblestackRestoreJobPoller.waitForCompletion("restore-job", 1, 0, () -> {
            final BackupAnswer answer = new BackupAnswer(null, true, "status");
            answer.setState(queryCount.incrementAndGet() == 1 ? "RUNNING" : "COMPLETED");
            return answer;
        });

        Assert.assertEquals("COMPLETED", result.getState());
        Assert.assertEquals(2, queryCount.get());
    }

    @Test
    public void toleratesTemporaryStatusQueryFailure() {
        final AtomicInteger queryCount = new AtomicInteger();

        final BackupAnswer result = AblestackRestoreJobPoller.waitForCompletion("restore-job", 1, 0, () -> {
            if (queryCount.incrementAndGet() == 1) {
                throw new IllegalStateException("agent is reconnecting");
            }
            final BackupAnswer answer = new BackupAnswer(null, true, "completed");
            answer.setState("COMPLETED");
            return answer;
        });

        Assert.assertEquals("COMPLETED", result.getState());
        Assert.assertEquals(2, queryCount.get());
    }

    @Test(expected = CloudRuntimeException.class)
    public void failsWhenRestoreJobReportsFailure() {
        AblestackRestoreJobPoller.waitForCompletion("restore-job", 1, 0, () -> {
            final BackupAnswer answer = new BackupAnswer(null, true, "restore failed");
            answer.setState("FAILED");
            return answer;
        });
    }
}
