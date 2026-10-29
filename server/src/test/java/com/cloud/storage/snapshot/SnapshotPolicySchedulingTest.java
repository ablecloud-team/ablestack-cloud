// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage.snapshot;

import java.time.Instant;
import java.util.Date;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.SnapshotScheduleVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.SnapshotScheduleDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.server.TaggedResourceService;
import com.cloud.utils.DateUtil.IntervalType;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises fresh-row decisions on both sides of the dispatch/update/completion race. */
public class SnapshotPolicySchedulingTest {
    private SnapshotSchedulerImpl scheduler;
    private SnapshotManagerImpl manager;
    private SnapshotPolicyVO policy;
    private SnapshotScheduleVO schedule;
    private SnapshotPolicyDao policies;
    private SnapshotScheduleDao schedules;
    private AsyncJobDao jobs;
    private MockedStatic<Transaction> transaction;
    private final Date now = date("2030-01-31T23:58:00Z");

    private static Date date(String instant) { return Date.from(Instant.parse(instant)); }

    @Before
    public void setup() {
        transaction = mockStatic(Transaction.class);
        transaction.when(() -> Transaction.execute(any(TransactionCallback.class)))
                .thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        scheduler = spy(new SnapshotSchedulerImpl());
        manager = new SnapshotManagerImpl();
        policies = mock(SnapshotPolicyDao.class);
        schedules = mock(SnapshotScheduleDao.class);
        jobs = mock(AsyncJobDao.class);
        VolumeDao volumes = mock(VolumeDao.class);
        scheduler._snapshotPolicyDao = policies;
        scheduler._snapshotScheduleDao = schedules;
        scheduler._asyncJobDao = jobs;
        scheduler._volsDao = volumes;
        manager._snapshotPolicyDao = policies;
        manager._snapshotScheduleDao = schedules;
        manager.snapshotAsyncJobDao = jobs;
        manager._snapSchedMgr = scheduler;
        manager.taggedResourceService = mock(TaggedResourceService.class);
        policy = spy(new SnapshotPolicyVO(10, "5", "UTC", IntervalType.HOURLY, 3, 2, 1, true));
        doReturn(20L).when(policy).getId();
        schedule = spy(new SnapshotScheduleVO(10, 20, date("2030-01-31T23:50:00Z")));
        doReturn(30L).when(schedule).getId();
        when(policies.lockRow(20L, true)).thenReturn(policy);
        when(policies.update(anyLong(), any())).thenReturn(true);
        when(schedules.findOneByVolumePolicy(10L, 20L)).thenReturn(schedule);
        when(schedules.lockRow(30L, true)).thenReturn(schedule);
        when(schedules.update(anyLong(), any())).thenReturn(true);
        when(schedules.expunge(anyLong())).thenReturn(true);
        when(volumes.findById(10L)).thenReturn(mock(VolumeVO.class));
        doReturn(now).when(scheduler).schedulerTime();
    }

    @After
    public void close() { transaction.close(); }

    private void running(JobInfo.Status status) {
        schedule.setAsyncJobId(40L);
        schedule.setSnapshotId(50L);
        AsyncJobVO job = mock(AsyncJobVO.class);
        when(job.getStatus()).thenReturn(status);
        when(jobs.findByIdIncludingRemoved(40L)).thenReturn(job);
    }

    private void edit(String value, String timezone, boolean active, boolean display) {
        manager.updateSnapshotPolicy(policy, value, timezone, IntervalType.HOURLY, 3, active, display, null, null);
    }

    @Test public void changedScheduleRecalculatesSameRow() {
        edit("15", "UTC", true, true);
        assertEquals(date("2030-02-01T00:15:00Z"), schedule.getScheduledTimestamp());
        verify(schedules, never()).persist(any());
    }

    @Test public void unchangedResubmitAndRetentionOnlyLeavePendingTimeUntouched() {
        Date before = schedule.getScheduledTimestamp();
        manager.updateSnapshotPolicy(policy, "5", "UTC", IntervalType.HOURLY, 5, true, true, null, null);
        assertEquals(before, schedule.getScheduledTimestamp());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void timeZoneChangeRecalculatesAtCurrentClock() {
        policy.setInterval((short) IntervalType.DAILY.ordinal());
        policy.setSchedule("5:9");
        manager.updateSnapshotPolicy(policy, "5:9", "Asia/Seoul", IntervalType.DAILY, 3, true, true, null, null);
        assertEquals(date("2030-02-01T00:05:00Z"), schedule.getScheduledTimestamp());
    }

    @Test public void runningJobSurvivesEditStopResumeAndRestart() {
        running(JobInfo.Status.IN_PROGRESS);
        Date before = schedule.getScheduledTimestamp();
        edit("15", "Asia/Seoul", true, true);
        edit("15", "Asia/Seoul", false, true);
        scheduler.reconcilePolicy(20, false);
        edit("15", "Asia/Seoul", true, true);
        assertEquals(Long.valueOf(40), schedule.getAsyncJobId());
        assertEquals(Long.valueOf(50), schedule.getSnapshotId());
        assertEquals(before, schedule.getScheduledTimestamp());
        verify(schedules, never()).update(anyLong(), any());
        verify(schedules, never()).expunge(anyLong());
    }

    @Test public void unknownJobStatusIsPreserved() {
        running(null);
        scheduler.reconcilePolicy(20, true);
        assertEquals(Long.valueOf(40), schedule.getAsyncJobId());
    }

    @Test public void existingPolicyMayBeEditedWhileItsVolumeIsSnapshotting() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Snapshotting);
        manager.validatePolicyVolumeState(volume, true, true);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void newPolicyIsStillRejectedWhileVolumeIsSnapshotting() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Snapshotting);
        manager.validatePolicyVolumeState(volume, false, true);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void editingPolicyDoesNotPermitDestroyedVolume() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Destroy);
        manager.validatePolicyVolumeState(volume, true, true);
    }

    @Test public void successfulCompletionUsesLatestPolicy() {
        running(JobInfo.Status.SUCCEEDED);
        policy.setSchedule("25");
        scheduler.scheduleNextSnapshotJob(schedule);
        assertEquals(date("2030-02-01T00:25:00Z"), schedule.getScheduledTimestamp());
        assertNull(schedule.getAsyncJobId());
        assertNull(schedule.getSnapshotId());
    }

    @Test public void failedJobRetriesAtNextFutureSlot() {
        running(JobInfo.Status.FAILED);
        scheduler.scheduleNextSnapshotJob(schedule);
        assertEquals(date("2030-02-01T00:05:00Z"), schedule.getScheduledTimestamp());
        assertNull(schedule.getAsyncJobId());
    }

    @Test public void expiredJobCanBeReconciled() {
        schedule.setAsyncJobId(40L);
        scheduler.reconcilePolicy(20, false);
        assertNull(schedule.getAsyncJobId());
        assertTrue(schedule.getScheduledTimestamp().after(now));
    }

    @Test public void staleCompletionCannotEraseNewerJob() {
        running(JobInfo.Status.IN_PROGRESS);
        SnapshotScheduleVO stale = spy(new SnapshotScheduleVO(10, 20, now));
        doReturn(30L).when(stale).getId();
        stale.setAsyncJobId(39L);
        scheduler.scheduleNextSnapshotJob(stale);
        assertEquals(Long.valueOf(40), schedule.getAsyncJobId());
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void restartPreservesFutureAndOverduePendingTimes() {
        for (Date timestamp : new Date[] {date("2030-01-31T23:50:00Z"), date("2030-02-01T00:05:00Z")}) {
            schedule.setScheduledTimestamp(timestamp);
            assertEquals(timestamp, scheduler.reconcilePolicy(20, false));
        }
        verify(schedules, never()).update(anyLong(), any());
    }

    @Test public void stoppingPendingPolicyRemovesSchedule() {
        edit("5", "UTC", false, true);
        verify(schedules).expunge(30L);
    }

    @Test public void hidingPendingPolicyRemovesSchedule() {
        edit("5", "UTC", true, false);
        verify(schedules).expunge(30L);
    }

    @Test public void resumedPolicyGetsOneFutureSchedule() {
        policy.setActive(false);
        when(schedules.findOneByVolumePolicy(10L, 20L)).thenReturn(null);
        when(schedules.persist(any())).thenAnswer(i -> i.getArgument(0));
        edit("5", "UTC", true, true);
        verify(schedules).persist(argThat(s -> s.getScheduledTimestamp().equals(date("2030-02-01T00:05:00Z"))));
    }

    @Test public void staleDueCandidateCannotDispatchAfterScheduleEdit() {
        schedule.setScheduledTimestamp(date("2030-02-01T00:05:00Z"));
        scheduler.executeScheduledSnapshot(schedule, now);
        verify(scheduler, never()).submitSnapshotJob(any(), any());
    }

    @Test public void staleDueCandidateCannotDispatchAfterDelete() {
        when(policies.lockRow(20L, true)).thenReturn(null);
        scheduler.executeScheduledSnapshot(schedule, now);
        verify(scheduler, never()).submitSnapshotJob(any(), any());
        verify(schedules).expunge(30L);
    }

    @Test public void repeatedPollCannotDispatchAcceptedJobAgain() {
        running(JobInfo.Status.IN_PROGRESS);
        scheduler.executeScheduledSnapshot(schedule, now);
        verify(scheduler, never()).submitSnapshotJob(any(), any());
    }

    @Test public void acceptedJobUsesDurableQueueAndKeepsSnapshotReference() throws Exception {
        scheduler._dispatcher = mock(com.cloud.api.ApiDispatcher.class);
        scheduler._asyncMgr = mock(org.apache.cloudstack.framework.jobs.AsyncJobManager.class);
        scheduler._asyncDispatcher = mock(org.apache.cloudstack.framework.jobs.AsyncJobDispatcher.class);
        scheduler.taggedResourceService = mock(TaggedResourceService.class);
        when(scheduler._asyncDispatcher.getName()).thenReturn("ApiAsyncJobDispatcher");
        doAnswer(i -> {
            ((org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd) i.getArgument(0)).setEntityId(50L);
            return null;
        }).when(scheduler._dispatcher).dispatchCreateCmd(any(), anyMap());
        when(scheduler._asyncMgr.submitAsyncJob(any(), eq("SnapshotPolicy"), eq(20L))).thenReturn(40L);
        try (MockedStatic<com.cloud.utils.component.ComponentContext> components = mockStatic(com.cloud.utils.component.ComponentContext.class);
             MockedStatic<com.cloud.event.ActionEventUtils> events = mockStatic(com.cloud.event.ActionEventUtils.class)) {
            scheduler.submitSnapshotJob(schedule, mock(VolumeVO.class));
        }
        assertEquals(Long.valueOf(40), schedule.getAsyncJobId());
        assertEquals(Long.valueOf(50), schedule.getSnapshotId());
        verify(scheduler._asyncMgr, never()).submitAsyncJob(any());
        verify(schedules).update(30L, schedule);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void deletingRunningPolicyRejectsBeforeTagsOrPolicyRemoval() {
        running(JobInfo.Status.IN_PROGRESS);
        try {
            manager.deletePolicy(20L);
        } finally {
            verify(policies, never()).remove(anyLong());
            verifyNoInteractions(manager.taggedResourceService);
        }
    }

    @Test public void deletingFinishedPolicyRemovesScheduleAndPolicy() {
        running(JobInfo.Status.SUCCEEDED);
        when(policies.remove(20L)).thenReturn(true);
        assertTrue(manager.deletePolicy(20L));
        verify(schedules).expunge(30L);
        verify(policies).remove(20L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void scheduleWriteFailurePropagatesToOuterTransaction() {
        when(schedules.update(anyLong(), any())).thenReturn(false);
        edit("15", "UTC", true, true);
    }

    private void next(IntervalType interval, String value, String timezone, String start, String expected) {
        policy.setInterval((short) interval.ordinal());
        policy.setSchedule(value);
        policy.setTimezone(timezone);
        doReturn(date(start)).when(scheduler).schedulerTime();
        assertEquals(date(expected), scheduler.getNextScheduledTime(policy));
    }

    @Test public void dayAndMonthAndYearBoundaries() {
        next(IntervalType.DAILY, "5:0", "UTC", "2030-12-31T23:59:00Z", "2031-01-01T00:05:00Z");
        next(IntervalType.WEEKLY, "5:0:1", "UTC", "2030-06-08T23:59:00Z", "2030-06-09T00:05:00Z");
        next(IntervalType.MONTHLY, "5:0:1", "UTC", "2030-12-31T23:59:00Z", "2031-01-01T00:05:00Z");
    }

    @Test public void springDstGapUsesExistingCalendarNormalization() {
        next(IntervalType.DAILY, "30:2", "America/New_York", "2030-03-10T05:00:00Z", "2030-03-10T07:30:00Z");
    }

    @Test public void autumnDstOverlapUsesStandardTimeOccurrence() {
        next(IntervalType.DAILY, "30:1", "America/New_York", "2030-11-03T04:00:00Z", "2030-11-03T06:30:00Z");
    }
}
