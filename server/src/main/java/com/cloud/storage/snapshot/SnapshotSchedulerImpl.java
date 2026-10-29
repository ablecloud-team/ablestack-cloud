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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.jobs.AsyncJobDispatcher;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.managed.context.ManagedContextTimerTask;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDispatcher;
import com.cloud.api.ApiGsonHelper;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.server.ResourceTag;
import com.cloud.server.TaggedResourceService;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.SnapshotScheduleVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.SnapshotScheduleDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.DateUtil.IntervalType;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.TestClock;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Component
public class SnapshotSchedulerImpl extends ManagerBase implements SnapshotScheduler {

    @Inject
    protected AsyncJobDao _asyncJobDao;
    @Inject
    protected SnapshotDao _snapshotDao;
    @Inject
    protected SnapshotScheduleDao _snapshotScheduleDao;
    @Inject
    protected SnapshotPolicyDao _snapshotPolicyDao;
    @Inject
    protected AsyncJobManager _asyncMgr;
    @Inject
    protected VolumeDao _volsDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected ApiDispatcher _dispatcher;
    @Inject
    protected AccountDao _acctDao;
    @Inject
    protected SnapshotApiService _snapshotService;
    @Inject
    protected VMSnapshotDao _vmSnapshotDao;
    @Inject
    protected VMSnapshotManager _vmSnaphostManager;
    @Inject
    public TaggedResourceService taggedResourceService;

    protected AsyncJobDispatcher _asyncDispatcher;

    private static final int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5;    // 5 seconds
    private int _snapshotPollInterval;
    private Timer _testClockTimer;
    private Date _currentTimestamp;
    private TestClock _testTimerTask;

    public AsyncJobDispatcher getAsyncJobDispatcher() {
        return _asyncDispatcher;
    }

    public void setAsyncJobDispatcher(final AsyncJobDispatcher dispatcher) {
        _asyncDispatcher = dispatcher;
    }

    protected Date schedulerTime() {
        // API changes must not calculate against the previous (up to 300 seconds old) poll.
        return _testTimerTask == null ? new Date() : new Date(_currentTimestamp.getTime());
    }

    protected Date getNextScheduledTime(SnapshotPolicyVO policy) {
        final IntervalType type = DateUtil.getIntervalType(policy.getInterval());
        return DateUtil.getNextRunTime(type, policy.getSchedule(), policy.getTimezone(), schedulerTime());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void poll(final Date currentTimestamp) {
        // We don't maintain the time. The timer task does.
        _currentTimestamp = currentTimestamp;

        GlobalLock scanLock = GlobalLock.getInternLock("snapshot.poll");
        try {
            if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                try {
                    scheduleNextSnapshotJobsIfNecessary();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }

        scanLock = GlobalLock.getInternLock("snapshot.poll");
        try {
            if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                try {
                    scheduleSnapshots();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }

        try {
            deleteExpiredVMSnapshots();
        }
        catch (Exception e) {
            logger.warn("Error in expiring Instance Snapshots", e);
        }
    }

    private void scheduleNextSnapshotJobsIfNecessary() {
        List<SnapshotScheduleVO> snapshotSchedules = _snapshotScheduleDao.getSchedulesAssignedWithAsyncJob();
        logger.info("Verifying the current state of [{}] Snapshot schedules and scheduling next jobs, if necessary.", snapshotSchedules.size());
        for (SnapshotScheduleVO snapshotSchedule : snapshotSchedules) {
            scheduleNextSnapshotJobIfNecessary(snapshotSchedule);
        }
    }

    protected void scheduleNextSnapshotJobIfNecessary(SnapshotScheduleVO snapshotSchedule) {
        Long asyncJobId = snapshotSchedule.getAsyncJobId();
        AsyncJobVO asyncJob = _asyncJobDao.findByIdIncludingRemoved(asyncJobId);

        if (asyncJob == null) {
            logger.debug("The async job [{}] of snapshot schedule [{}] does not exist anymore. Considering it as finished and scheduling the next snapshot job.",
                    asyncJobId, snapshotSchedule);
            scheduleNextSnapshotJob(snapshotSchedule);
            return;
        }

        JobInfo.Status status = asyncJob.getStatus();

        if (JobInfo.Status.SUCCEEDED.equals(status)) {
            logger.debug("Last job of schedule [{}] succeeded; scheduling the next snapshot job.", snapshotSchedule);
        } else if (JobInfo.Status.FAILED.equals(status)) {
            logger.debug("Last job of schedule [{}] failed with [{}]; scheduling a new snapshot job.", snapshotSchedule, asyncJob.getResult());
        } else {
            logger.debug("Schedule [{}] is still in progress, skipping next job scheduling.", snapshotSchedule);
            return;
        }

        scheduleNextSnapshotJob(snapshotSchedule);
    }

    @DB
    protected void deleteExpiredVMSnapshots() {
        Date now = new Date();
        List<VMSnapshotVO> vmSnapshots = _vmSnapshotDao.listAll();
        for (VMSnapshotVO vmSnapshot : vmSnapshots) {
            long accountId = vmSnapshot.getAccountId();
            int expiration_interval_hours = VMSnapshotManager.VMSnapshotExpireInterval.valueIn(accountId);
            if (expiration_interval_hours < 0 ) {
                continue;
            }
            Date creationTime = vmSnapshot.getCreated();
            long diffInHours = TimeUnit.MILLISECONDS.toHours(now.getTime() - creationTime.getTime());
            if (diffInHours >= expiration_interval_hours) {
                if (logger.isDebugEnabled()){
                    logger.debug("Deleting expired Instance Snapshot: {}", vmSnapshot);
                }
                _vmSnaphostManager.deleteVMSnapshot(vmSnapshot.getId());
            }
        }
    }

    @DB
    protected void scheduleSnapshots() {
        String displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, _currentTimestamp);
        logger.debug(String.format("Snapshot scheduler is being called at [%s].", displayTime));

        final List<SnapshotScheduleVO> snapshotsToBeExecuted = _snapshotScheduleDao.getSchedulesToExecute(_currentTimestamp);
        logger.debug(String.format("There are [%s] scheduled snapshots to be executed at [%s].", snapshotsToBeExecuted.size(), displayTime));

        for (final SnapshotScheduleVO snapshotToBeExecuted : snapshotsToBeExecuted) {
            try {
                executeScheduledSnapshot(snapshotToBeExecuted, _currentTimestamp);
            } catch (final Exception e) {
                logger.error("The scheduling of snapshot [{}] failed.", snapshotToBeExecuted, e);
            }
        }
    }

    protected void executeScheduledSnapshot(SnapshotScheduleVO candidate, Date dueBefore) {
        if (candidate.getPolicyId() <= 0) {
            return;
        }
        Transaction.execute((TransactionCallback<Void>) status -> {
            // All mutators use this order. The due-list is only a hint, never authorization to dispatch.
            SnapshotPolicyVO policy = _snapshotPolicyDao.lockRow(candidate.getPolicyId(), true);
            SnapshotScheduleVO schedule = _snapshotScheduleDao.lockRow(candidate.getId(), true);
            if (schedule == null || !Objects.equals(schedule.getPolicyId(), candidate.getPolicyId())
                    || !Objects.equals(schedule.getVolumeId(), candidate.getVolumeId())) {
                return null;
            }
            if (policy == null || !policy.isActive() || !policy.isDisplay()) {
                if (!SnapshotScheduleJobGuard.inProgress(schedule, _asyncJobDao)) {
                    _snapshotScheduleDao.expunge(schedule.getId());
                }
                return null;
            }
            if (schedule.getAsyncJobId() != null || schedule.getScheduledTimestamp() == null
                    || !schedule.getScheduledTimestamp().before(dueBefore)) {
                return null;
            }
            VolumeVO volume = _volsDao.findByIdIncludingRemoved(schedule.getVolumeId());
            if (!canSnapshotBeScheduled(schedule, volume)) {
                return null;
            }
            submitSnapshotJob(schedule, volume);
            return null;
        });
    }

    protected void submitSnapshotJob(SnapshotScheduleVO snapshotToBeExecuted, VolumeVO volume) {
        final long snapshotScheId = snapshotToBeExecuted.getId();
        final long policyId = snapshotToBeExecuted.getPolicyId();
        final long volumeId = snapshotToBeExecuted.getVolumeId();
        final Long eventId =
            ActionEventUtils.onScheduledActionEvent(User.UID_SYSTEM, volume.getAccountId(), EventTypes.EVENT_SNAPSHOT_CREATE, "creating snapshot for volume Id:" +
                volume.getUuid(), volumeId, ApiCommandResourceType.Volume.toString(), true, 0);

        logger.trace("Mapping parameters required to generate a CreateSnapshotCmd for snapshot [{}].", snapshotToBeExecuted);
        final Map<String, String> params = new HashMap<String, String>();
        params.put(ApiConstants.VOLUME_ID, "" + volumeId);
        params.put(ApiConstants.POLICY_ID, "" + policyId);
        params.put("ctxUserId", "1");
        params.put("ctxAccountId", "" + volume.getAccountId());
        params.put("ctxStartEventId", String.valueOf(eventId));
        List<? extends ResourceTag> resourceTags = taggedResourceService.listByResourceTypeAndId(ResourceTag.ResourceObjectType.SnapshotPolicy, policyId);
        if (resourceTags != null && !resourceTags.isEmpty()) {
            int tagNumber = 0;
            for (ResourceTag resourceTag : resourceTags) {
                params.put("tags[" + tagNumber + "].key", resourceTag.getKey());
                params.put("tags[" + tagNumber + "].value", resourceTag.getValue());
                tagNumber++;
            }
        }

        logger.trace("Generating a CreateSnapshotCmd for snapshot [{}] with parameters: [{}].", snapshotToBeExecuted, params.toString());
        final CreateSnapshotCmd cmd = new CreateSnapshotCmd();
        ComponentContext.inject(cmd);
        try {
            _dispatcher.dispatchCreateCmd(cmd, params);
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to allocate a scheduled snapshot", e);
        }
        params.put("id", "" + cmd.getEntityId());
        params.put("ctxStartEventId", "1");

        final Date scheduledTimestamp = snapshotToBeExecuted.getScheduledTimestamp();
        String displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, scheduledTimestamp);
        logger.debug("Scheduling snapshot [{}] for volume [{}] at [{}].", snapshotToBeExecuted, volume, displayTime);
        AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, volume.getAccountId(), CreateSnapshotCmd.class.getName(),
                ApiGsonHelper.getBuilder().create().toJson(params), cmd.getEntityId(),
                cmd.getApiResourceType() != null ? cmd.getApiResourceType().toString() : null, null);
        job.setDispatcher(_asyncDispatcher.getName());
        // Durable queue submission joins this transaction. Workers cannot start before
        // snapshot allocation, the job and its schedule reference are committed together.
        final long jobId = _asyncMgr.submitAsyncJob(job, "SnapshotPolicy", policyId);
        logger.debug("Scheduled snapshot [{}] for volume [{}] as job [{}].", snapshotToBeExecuted, volume, job);

        snapshotToBeExecuted.setAsyncJobId(jobId);
        snapshotToBeExecuted.setSnapshotId(cmd.getEntityId());
        if (!_snapshotScheduleDao.update(snapshotScheId, snapshotToBeExecuted)) {
            throw new CloudRuntimeException("Unable to persist the snapshot schedule job reference");
        }
    }

    /**
     * Verifies if a snapshot for a volume can be scheduled or not based on volume and account status, and removes it from the snapshot scheduler if its policy was removed.
     *
     * @param snapshotToBeScheduled the snapshot to be scheduled
     * @param volume the volume associated with the snapshot to be scheduled
     * @return <code>true</code> if the snapshot can be scheduled, and <code>false</code> otherwise.
     */
    protected boolean canSnapshotBeScheduled(final SnapshotScheduleVO snapshotToBeScheduled, final VolumeVO volume) {
        if (volume == null || volume.getRemoved() != null) {
            logger.warn("Skipping snapshot [{}] for volume [{}] because it has been removed. Having a snapshot scheduled for a volume that has been "
                    + "removed is an inconsistency; please, check your database.", snapshotToBeScheduled, volume);
            return false;
        }

        if (volume.getPoolId() == null) {
            logger.debug("Skipping snapshot [{}] for volume [{}] because it is not attached to any storage pool.", snapshotToBeScheduled, volume);
            return false;
        }

        if (isAccountRemovedOrDisabled(snapshotToBeScheduled, volume)) {
            return false;
        }

        if (_snapshotPolicyDao.findById(snapshotToBeScheduled.getPolicyId()) == null) {
            logger.debug("Snapshot's policy [{}] for volume [{}] has been removed; " +
                    "therefore, this snapshot will be removed from the snapshot scheduler.",
                    snapshotToBeScheduled.getPolicyId(), volume);
            _snapshotScheduleDao.remove(snapshotToBeScheduled.getId());
            return false;
        }

        logger.debug("Snapshot [{}] for volume [{}] can be executed.", snapshotToBeScheduled, volume);
        return true;
    }

    protected boolean isAccountRemovedOrDisabled(final SnapshotScheduleVO snapshotToBeExecuted, final VolumeVO volume) {
        Account volAcct = _acctDao.findById(volume.getAccountId());

        if (volAcct == null) {
            logger.debug(String.format("Skipping snapshot [%s] for volume [%s] because its account [%s] has been removed.",
                    snapshotToBeExecuted, volume, volume.getAccountId()));
            return true;
        }

        if (volAcct.getState() == Account.State.DISABLED) {
            logger.debug("Skipping snapshot [{}] for volume [{}] because its account [{}] is disabled.", snapshotToBeExecuted, volume, volAcct);
            return true;
        }

        return false;
    }

    protected Date scheduleNextSnapshotJob(final SnapshotScheduleVO snapshotSchedule) {
        if (snapshotSchedule == null || snapshotSchedule.getPolicyId() <= 0) {
            return null;
        }
        return Transaction.execute((TransactionCallback<Date>) status -> {
            SnapshotPolicyVO policy = _snapshotPolicyDao.lockRow(snapshotSchedule.getPolicyId(), true);
            SnapshotScheduleVO current = _snapshotScheduleDao.lockRow(snapshotSchedule.getId(), true);
            if (current == null || !Objects.equals(current.getPolicyId(), snapshotSchedule.getPolicyId())
                    || !Objects.equals(current.getAsyncJobId(), snapshotSchedule.getAsyncJobId())) {
                return null;
            }
            // A stale completion must never clear a newer job or a schedule changed by an API call.
            return reconcileSchedule(policy, current, true);
        });
    }

    @Override
    @DB
    public Date scheduleNextSnapshotJob(final SnapshotPolicyVO policy) {
        if (policy == null || policy.getId() <= 0) {
            return null;
        }
        return reconcilePolicy(policy.getId(), true);
    }

    protected Date reconcilePolicy(long policyId, boolean replacePending) {
        return Transaction.execute((TransactionCallback<Date>) status -> {
            SnapshotPolicyVO policy = _snapshotPolicyDao.lockRow(policyId, true);
            if (policy == null) {
                return null;
            }
            SnapshotScheduleVO schedule = _snapshotScheduleDao.findOneByVolumePolicy(policy.getVolumeId(), policyId);
            if (schedule != null) {
                schedule = _snapshotScheduleDao.lockRow(schedule.getId(), true);
            }
            return reconcileSchedule(policy, schedule, replacePending);
        });
    }

    protected Date reconcileSchedule(SnapshotPolicyVO policy, SnapshotScheduleVO schedule, boolean replacePending) {
        if (SnapshotScheduleJobGuard.inProgress(schedule, _asyncJobDao)) {
            // Policy edits/stop/restart preserve the accepted job. Its completion uses the latest policy.
            return schedule.getScheduledTimestamp();
        }
        if (policy == null || !policy.isDisplay() || !policy.isActive() || _volsDao.findById(policy.getVolumeId()) == null) {
            if (schedule != null && !_snapshotScheduleDao.expunge(schedule.getId())) {
                throw new CloudRuntimeException("Unable to remove inactive snapshot schedule");
            }
            return null;
        }
        if (!replacePending && schedule != null && schedule.getAsyncJobId() == null) {
            // Restart preserves both future and overdue pending work instead of skipping it.
            return schedule.getScheduledTimestamp();
        }
        Date next = getNextScheduledTime(policy);
        if (schedule == null) {
            if (_snapshotScheduleDao.persist(new SnapshotScheduleVO(policy.getVolumeId(), policy.getId(), next)) == null) {
                throw new CloudRuntimeException("Unable to create snapshot schedule");
            }
        } else {
            schedule.setScheduledTimestamp(next);
            schedule.setAsyncJobId(null);
            schedule.setSnapshotId(null);
            if (!_snapshotScheduleDao.update(schedule.getId(), schedule)) {
                throw new CloudRuntimeException("Unable to update snapshot schedule");
            }
        }
        return next;
    }

    @Override
    public void scheduleOrCancelNextSnapshotJobOnDisplayChange(final SnapshotPolicyVO policy, boolean previousDisplay) {
        if (policy.isDisplay() != previousDisplay) {
            scheduleNextSnapshotJob(policy);
        }
    }


    @Override
    @DB
    public boolean removeSchedule(final Long volumeId, final Long policyId) {
        return Transaction.execute((TransactionCallback<Boolean>) status -> {
            _snapshotPolicyDao.lockRow(policyId, true);
            SnapshotScheduleVO schedule = _snapshotScheduleDao.findOneByVolumePolicy(volumeId, policyId);
            if (schedule == null) {
                return true;
            }
            schedule = _snapshotScheduleDao.lockRow(schedule.getId(), true);
            return schedule == null || SnapshotScheduleJobGuard.inProgress(schedule, _asyncJobDao)
                    || _snapshotScheduleDao.expunge(schedule.getId());
        });
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {

        _snapshotPollInterval = NumbersUtil.parseInt(_configDao.getValue("snapshot.poll.interval"), 300);
        final boolean snapshotsRecurringTest = Boolean.parseBoolean(_configDao.getValue("snapshot.recurring.test"));
        if (snapshotsRecurringTest) {
            // look for some test values in the configuration table so that snapshots can be taken more frequently (QA test code)
            final int minutesPerHour = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.minutes.per.hour"), 60);
            final int hoursPerDay = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.hours.per.day"), 24);
            final int daysPerWeek = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.days.per.week"), 7);
            final int daysPerMonth = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.days.per.month"), 30);
            final int weeksPerMonth = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.weeks.per.month"), 4);
            final int monthsPerYear = NumbersUtil.parseInt(_configDao.getValue("snapshot.test.months.per.year"), 12);

            _testTimerTask = new TestClock(this, minutesPerHour, hoursPerDay, daysPerWeek, daysPerMonth, weeksPerMonth, monthsPerYear);
        }
        _currentTimestamp = new Date();

        logger.info("Snapshot Scheduler is configured.");

        return true;
    }

    @Override
    @DB
    public boolean start() {
        // reschedule all policies after management restart
        final List<SnapshotPolicyVO> policyInstances = _snapshotPolicyDao.listAll();
        for (final SnapshotPolicyVO policyInstance : policyInstances) {
            if (policyInstance.getId() != Snapshot.MANUAL_POLICY_ID) {
                reconcilePolicy(policyInstance.getId(), false);
            }
        }
        if (_testTimerTask != null) {
            _testClockTimer = new Timer("TestClock");
            // Run the test clock every 60s. Because every tick is counted as 1 minute.
            // Else it becomes too confusing.
            _testClockTimer.schedule(_testTimerTask, 100 * 1000L, 60 * 1000L);
        } else {
            final TimerTask timerTask = new ManagedContextTimerTask() {
                @Override
                protected void runInContext() {
                    try {
                        final Date currentTimestamp = new Date();
                        poll(currentTimestamp);
                    } catch (final Throwable t) {
                        logger.warn("Catch throwable in snapshot scheduler ", t);
                    }
                }
            };
            _testClockTimer = new Timer("SnapshotPollTask");
            _testClockTimer.schedule(timerTask, _snapshotPollInterval * 1000L, _snapshotPollInterval * 1000L);
        }

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }
}
