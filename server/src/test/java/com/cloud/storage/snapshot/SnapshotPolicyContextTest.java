// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import com.cloud.storage.SnapshotScheduleVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import static org.mockito.Mockito.*;

public class SnapshotPolicyContextTest {
    SnapshotPolicy policy; VolumeVO volume; SnapshotScheduleVO schedule; SnapshotDao snapshots;
    @Before public void prepare() {
        policy = mock(SnapshotPolicy.class); volume = mock(VolumeVO.class); schedule = mock(SnapshotScheduleVO.class); snapshots = mock(SnapshotDao.class);
        when(policy.getId()).thenReturn(7L); when(policy.getVolumeId()).thenReturn(8L); when(policy.getAccountId()).thenReturn(9L);
        when(policy.isActive()).thenReturn(true); when(policy.isDisplay()).thenReturn(true);
        when(volume.getUuid()).thenReturn("volume"); when(schedule.getAsyncJobId()).thenReturn(null); when(schedule.getScheduledTimestamp()).thenReturn(new Date(1000));
    }
    AsyncJobVO job(long id, JobInfo.Status status) {
        AsyncJobVO job = mock(AsyncJobVO.class); when(job.getId()).thenReturn(id); when(job.getUuid()).thenReturn("job-"+id);
        when(job.getAccountId()).thenReturn(9L); when(job.getInstanceType()).thenReturn("Snapshot"); when(job.getInstanceId()).thenReturn(11L);
        when(job.getCmd()).thenReturn(org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd.class.getName());
        when(job.getCmdInfo()).thenReturn("{\"policyid\":\"7\",\"volumeid\":\"8\",\"secretkey\":\"NEVER_EXPOSE\"}");
        when(job.getStatus()).thenReturn(status); when(job.getCreated()).thenReturn(new Date(500)); return job;
    }
    Map<String, Object> read(AsyncJobVO current, AsyncJobVO... jobs) { return SnapshotPolicyContext.read(policy, volume, schedule, current, Arrays.asList(jobs), snapshots, 300, new Date(2000)); }
    @SuppressWarnings("unchecked") Map<String, Object> latest(Map<String, Object> result) { return (Map<String, Object>)result.get("latestjob"); }
    @Test public void queuedTimeIsNotACompletionOrPredictedTime() {
        Map<String,Object> r=read(null); Assert.assertEquals("SCHEDULED",r.get("schedulestate")); Assert.assertEquals(new Date(1000),r.get("nextscheduled"));
        Assert.assertFalse(r.containsKey("latestjob")); Assert.assertEquals(50,r.get("historylimit")); Assert.assertEquals("RETAINED_SCHEDULER_JOBS",r.get("historysource"));
    }
    @Test public void pausedNeverReturnsStaleNextSchedule() {
        when(policy.isDisplay()).thenReturn(false); Map<String,Object> r=read(null); Assert.assertEquals("PAUSED",r.get("schedulestate")); Assert.assertFalse(r.containsKey("nextscheduled")); Assert.assertEquals(false,r.get("schedulingenabled"));
    }
    @Test public void inactiveAlsoStopsScheduling() { when(policy.isActive()).thenReturn(false); Assert.assertEquals("PAUSED",read(null).get("schedulestate")); }
    @Test public void assignedOccurrenceIsNotNextFutureRun() {
        when(schedule.getAsyncJobId()).thenReturn(12L); Map<String,Object> r=read(job(12,JobInfo.Status.IN_PROGRESS));
        Assert.assertEquals("ASSIGNED",r.get("schedulestate")); Assert.assertFalse(r.containsKey("nextscheduled")); Assert.assertEquals("job-12",r.get("currentjobid")); Assert.assertEquals("IN_PROGRESS",latest(r).get("status"));
    }
    @Test public void expiredAssignedJobRemainsUnknown() { when(schedule.getAsyncJobId()).thenReturn(12L); Map<String,Object> r=read(null); Assert.assertEquals(false,r.get("currentjobknown")); Assert.assertFalse(r.containsKey("latestjob")); }
    @Test public void failureExposesOnlyCodeAndSanitizedFacts() {
        AsyncJobVO j=job(12,JobInfo.Status.FAILED); when(j.getResultCode()).thenReturn(530); when(j.getResult()).thenReturn("SECRET_RESULT");
        Map<String,Object> r=read(null,j); Assert.assertEquals(530,latest(r).get("errorcode")); Assert.assertEquals("FAILED",latest(r).get("status"));
        Assert.assertFalse(r.toString().contains("SECRET")); Assert.assertFalse(r.toString().contains("NEVER_EXPOSE")); verify(j,never()).getResult();
    }
    @Test public void exactIdentityRejectsOtherPolicyVolumeOwnerOrResource() {
        AsyncJobVO j=job(12,JobInfo.Status.SUCCEEDED);
        for(String args:Arrays.asList("{}","{\"policyid\":\"70\",\"volumeid\":\"8\"}","{\"policyid\":\"7\",\"volumeid\":\"80\"}","broken")) { when(j.getCmdInfo()).thenReturn(args); Assert.assertFalse(read(null,j).containsKey("latestjob")); }
        j=job(13,JobInfo.Status.SUCCEEDED); when(j.getAccountId()).thenReturn(90L); Assert.assertFalse(read(null,j).containsKey("latestjob"));
        j=job(14,JobInfo.Status.SUCCEEDED); when(j.getInstanceType()).thenReturn("Volume"); Assert.assertFalse(read(null,j).containsKey("latestjob"));
    }
    @Test public void numericJsonIsAcceptedAfterExactIdentityCheck() { AsyncJobVO j=job(12,JobInfo.Status.SUCCEEDED); when(j.getCmdInfo()).thenReturn("{\"policyid\":7,\"volumeid\":8}"); Assert.assertEquals("SUCCEEDED",latest(read(null,j)).get("status")); }
    @Test public void latestUsesPersistedJobOrder() { Assert.assertEquals("job-22",latest(read(job(12,JobInfo.Status.SUCCEEDED),job(22,JobInfo.Status.FAILED),job(11,JobInfo.Status.SUCCEEDED))).get("id")); }
    @Test public void generatedSnapshotRequiresCurrentSameOwnerVolumeAndNotRemoved() {
        SnapshotVO s=mock(SnapshotVO.class); when(s.getUuid()).thenReturn("snapshot"); when(s.getAccountId()).thenReturn(9L); when(s.getVolumeId()).thenReturn(8L); when(snapshots.findByIdIncludingRemoved(11L)).thenReturn(s);
        AsyncJobVO j=job(12,JobInfo.Status.SUCCEEDED); Assert.assertEquals("snapshot",latest(read(null,j)).get("snapshotid"));
        when(s.getRemoved()).thenReturn(new Date()); Assert.assertFalse(latest(read(null,j)).containsKey("snapshotid"));
        when(s.getRemoved()).thenReturn(null); when(s.getAccountId()).thenReturn(90L); Assert.assertFalse(latest(read(null,j)).containsKey("snapshotid"));
        when(s.getAccountId()).thenReturn(9L); when(s.getVolumeId()).thenReturn(80L); Assert.assertFalse(latest(read(null,j)).containsKey("snapshotid"));
    }
    @Test public void absentScheduleAndCancelledJobsAreUnknown() {
        Map<String,Object> r=SnapshotPolicyContext.read(policy,volume,null,null,Collections.singletonList(job(12,JobInfo.Status.CANCELLED)),snapshots,300,new Date());
        Assert.assertEquals("UNKNOWN",r.get("schedulestate")); Assert.assertEquals("UNKNOWN",latest(r).get("status")); Assert.assertFalse(r.containsKey("nextscheduled"));
    }
    @Test public void changedSourceOwnerIsRejectedBeforeReadingJobs() {
        try(org.mockito.MockedStatic<org.apache.cloudstack.context.CallContext> ctx = mockStatic(org.apache.cloudstack.context.CallContext.class)) {
            ctx.when(org.apache.cloudstack.context.CallContext::current).thenReturn(mock(org.apache.cloudstack.context.CallContext.class));
            SnapshotManagerImpl manager = new SnapshotManagerImpl();
            manager._snapshotPolicyDao = mock(com.cloud.storage.dao.SnapshotPolicyDao.class);
            manager._volsDao = mock(com.cloud.storage.dao.VolumeDao.class);
            manager._accountMgr = mock(com.cloud.user.AccountManager.class);
            manager.snapshotAsyncJobDao = mock(org.apache.cloudstack.framework.jobs.dao.AsyncJobDao.class);
            com.cloud.storage.SnapshotPolicyVO p = mock(com.cloud.storage.SnapshotPolicyVO.class);
            when(manager._snapshotPolicyDao.findById(7L)).thenReturn(p); when(p.getAccountId()).thenReturn(9L); when(p.getVolumeId()).thenReturn(8L);
            when(manager._volsDao.findById(8L)).thenReturn(volume); when(volume.getAccountId()).thenReturn(90L);
            try { manager.getSnapshotPolicyContext(7L); Assert.fail("changed owner accepted"); } catch(com.cloud.exception.InvalidParameterValueException expected) { verifyNoInteractions(manager.snapshotAsyncJobDao); }
        }
    }
    @Test public void pausedPolicyReadsCompletedRetainedJobsWithoutSearchingEncryptedArguments() {
        try(org.mockito.MockedStatic<org.apache.cloudstack.context.CallContext> ctx = mockStatic(org.apache.cloudstack.context.CallContext.class)) {
            ctx.when(org.apache.cloudstack.context.CallContext::current).thenReturn(mock(org.apache.cloudstack.context.CallContext.class));
            SnapshotManagerImpl manager = new SnapshotManagerImpl();
            manager._snapshotPolicyDao = mock(com.cloud.storage.dao.SnapshotPolicyDao.class);
            manager._volsDao = mock(com.cloud.storage.dao.VolumeDao.class);
            manager._accountMgr = mock(com.cloud.user.AccountManager.class);
            manager._snapshotScheduleDao = mock(com.cloud.storage.dao.SnapshotScheduleDao.class);
            manager._snapshotDao = snapshots;
            manager._configDao = mock(org.apache.cloudstack.framework.config.dao.ConfigurationDao.class);
            manager.snapshotAsyncJobDao = mock(org.apache.cloudstack.framework.jobs.dao.AsyncJobDao.class);
            com.cloud.storage.SnapshotPolicyVO p = mock(com.cloud.storage.SnapshotPolicyVO.class);
            when(manager._snapshotPolicyDao.findById(7L)).thenReturn(p); when(p.getId()).thenReturn(7L); when(p.getAccountId()).thenReturn(9L); when(p.getVolumeId()).thenReturn(8L);
            when(manager._volsDao.findById(8L)).thenReturn(volume); when(volume.getId()).thenReturn(8L); when(volume.getAccountId()).thenReturn(9L);
            new org.apache.cloudstack.framework.jobs.dao.AsyncJobDaoImpl();
            new com.cloud.storage.dao.SnapshotDaoImpl();
            when(manager.snapshotAsyncJobDao.createSearchBuilder()).thenAnswer(invocation -> new com.cloud.utils.db.SearchBuilder<>(AsyncJobVO.class));
            when(snapshots.createSearchBuilder()).thenAnswer(invocation -> new com.cloud.utils.db.SearchBuilder<>(SnapshotVO.class));
            AsyncJobVO completed = job(12,JobInfo.Status.FAILED); when(completed.getRemoved()).thenReturn(new Date());
            when(manager.snapshotAsyncJobDao.searchIncludingRemoved(any(),any(),isNull(),eq(false))).thenReturn(Collections.singletonList(completed));
            Map<String,Object> r=manager.getSnapshotPolicyContext(7L);
            Assert.assertEquals("FAILED",latest(r).get("status")); Assert.assertEquals("PAUSED",r.get("schedulestate"));
            verify(manager.snapshotAsyncJobDao,never()).search(any(),any());
            verify(manager.snapshotAsyncJobDao).searchIncludingRemoved(argThat(sc -> !sc.getWhereClause("async_job").contains("job_cmd_info")),any(),isNull(),eq(false));
        }
    }
}
