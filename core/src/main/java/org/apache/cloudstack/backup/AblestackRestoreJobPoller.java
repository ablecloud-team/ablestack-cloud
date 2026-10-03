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

import com.cloud.agent.api.Answer;
import com.cloud.utils.exception.CloudRuntimeException;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AblestackRestoreJobPoller {
    private static final Logger LOGGER = LogManager.getLogger(AblestackRestoreJobPoller.class);
    private static final long POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);

    private AblestackRestoreJobPoller() {
    }

    public static BackupAnswer waitForCompletion(final String restoreJobId, final int timeoutSeconds,
            final Callable<Answer> statusQuery) {
        return waitForCompletion(restoreJobId, timeoutSeconds, POLL_INTERVAL_MS, statusQuery);
    }

    static BackupAnswer waitForCompletion(final String restoreJobId, final int timeoutSeconds, final long pollIntervalMs,
            final Callable<Answer> statusQuery) {
        final long effectiveTimeoutSeconds = Math.max(1, timeoutSeconds);
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(effectiveTimeoutSeconds);
        Exception lastQueryFailure = null;
        BackupAnswer lastAnswer = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final Answer answer = statusQuery.call();
                if (answer instanceof BackupAnswer) {
                    lastAnswer = (BackupAnswer) answer;
                    final String state = normalizeState(lastAnswer);
                    if (lastQueryFailure != null) {
                        LOGGER.info("Restore job [{}] status polling resumed after a temporary agent communication failure.", restoreJobId);
                    }
                    if ("COMPLETED".equals(state)) {
                        return lastAnswer;
                    }
                    if (isFailureState(state)) {
                        throw new CloudRuntimeException(String.format("Restore job [%s] failed in state [%s]: %s",
                                restoreJobId, state, lastAnswer.getDetails()));
                    }
                }
                lastQueryFailure = null;
            } catch (CloudRuntimeException e) {
                throw e;
            } catch (Exception e) {
                if (lastQueryFailure == null) {
                    LOGGER.warn("Restore job [{}] status polling is temporarily unavailable and will be retried: {}",
                            restoreJobId, e.getMessage());
                } else {
                    LOGGER.trace("Restore job [{}] status polling remains unavailable: {}", restoreJobId, e.getMessage());
                }
                lastQueryFailure = e;
            }
            sleepUntilNextPoll(restoreJobId, pollIntervalMs);
        }
        final String lastState = lastAnswer != null ? normalizeState(lastAnswer) : "UNKNOWN";
        final String reason = lastQueryFailure != null ? lastQueryFailure.getMessage()
                : lastAnswer != null ? lastAnswer.getDetails() : "No status response received";
        throw new CloudRuntimeException(String.format("Timed out waiting for restore job [%s]. Last state [%s]: %s",
                restoreJobId, lastState, reason));
    }

    private static String normalizeState(final BackupAnswer answer) {
        final String state = answer.getState() != null ? answer.getState() : answer.getDetails();
        return state != null ? state.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static boolean isFailureState(final String state) {
        return "FAILED".equals(state) || "CANCELED".equals(state) || "CANCELLED".equals(state)
                || "INTERRUPTED".equals(state);
    }

    private static void sleepUntilNextPoll(final String restoreJobId, final long pollIntervalMs) {
        try {
            Thread.sleep(Math.max(0, pollIntervalMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException("Interrupted while waiting for restore job " + restoreJobId, e);
        }
    }
}
