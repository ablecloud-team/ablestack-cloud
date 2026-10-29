// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.storage.SnapshotScheduleVO;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;

/** Read facts only. Never expose job arguments, results, paths or credentials. */
final class SnapshotPolicyContext {
    private SnapshotPolicyContext() { }

    static boolean belongs(AsyncJobVO job, SnapshotPolicy policy) {
        if (job == null || job.getAccountId() != policy.getAccountId() || !"Snapshot".equals(job.getInstanceType()) ||
                !org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd.class.getName().equals(job.getCmd())) { return false; }
        try {
            JsonObject args = new JsonParser().parse(job.getCmdInfo()).getAsJsonObject();
            return Long.toString(policy.getId()).equals(args.get("policyid").getAsString()) &&
                Long.toString(policy.getVolumeId()).equals(args.get("volumeid").getAsString());
        } catch (RuntimeException e) { return false; }
    }

    static Map<String, Object> read(SnapshotPolicy policy, VolumeVO volume, SnapshotScheduleVO schedule,
            AsyncJobVO current, List<AsyncJobVO> jobs, SnapshotDao snapshots, int pollSeconds, Date observed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("observedat", observed);
        result.put("volumeid", volume.getUuid());
        result.put("schedulingenabled", policy.isActive() && policy.isDisplay());
        result.put("pollintervalseconds", Math.max(1, pollSeconds));
        result.put("historysource", "RETAINED_SCHEDULER_JOBS");
        result.put("historylimit", 50);
        // A schedule with an assigned job refers to the accepted occurrence, not the next future run.
        boolean assigned = schedule != null && schedule.getAsyncJobId() != null;
        result.put("schedulestate", !policy.isActive() || !policy.isDisplay() ? "PAUSED" : schedule == null ? "UNKNOWN" : assigned ? "ASSIGNED" : "SCHEDULED");
        if (schedule != null && !assigned && policy.isActive() && policy.isDisplay()) {
            result.put("nextscheduled", schedule.getScheduledTimestamp());
        }
        AsyncJobVO latest = belongs(current, policy) ? current : null;
        for (AsyncJobVO job : jobs) {
            if (belongs(job, policy) && (latest == null || job.getId() > latest.getId())) { latest = job; }
        }
        result.put("currentjobknown", assigned && belongs(current, policy));
        if (assigned && belongs(current, policy)) { result.put("currentjobid", current.getUuid()); }
        if (latest != null) {
            Map<String, Object> execution = new LinkedHashMap<>();
            execution.put("id", latest.getUuid());
            execution.put("status", latest.getStatus() == JobInfo.Status.SUCCEEDED ? "SUCCEEDED" : latest.getStatus() == JobInfo.Status.FAILED ? "FAILED" : latest.getStatus() == JobInfo.Status.IN_PROGRESS ? "IN_PROGRESS" : "UNKNOWN");
            execution.put("created", latest.getCreated());
            if (latest.getLastUpdated() != null) { execution.put("updated", latest.getLastUpdated()); }
            if (latest.getStatus() == JobInfo.Status.FAILED) { execution.put("errorcode", latest.getResultCode()); }
            SnapshotVO snapshot = latest.getInstanceId() == null ? null : snapshots.findByIdIncludingRemoved(latest.getInstanceId());
            boolean available = snapshot != null && snapshot.getRemoved() == null && snapshot.getAccountId() == policy.getAccountId() && snapshot.getVolumeId() == policy.getVolumeId();
            execution.put("snapshotavailable", available);
            if (available) { execution.put("snapshotid", snapshot.getUuid()); execution.put("snapshotname", snapshot.getName()); }
            result.put("latestjob", execution);
        }
        return result;
    }
}
