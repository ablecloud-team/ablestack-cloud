// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import com.cloud.storage.SnapshotScheduleVO;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;

final class SnapshotScheduleJobGuard {
    private SnapshotScheduleJobGuard() { }

    static boolean inProgress(SnapshotScheduleVO schedule, AsyncJobDao jobs) {
        if (schedule == null || schedule.getAsyncJobId() == null) {
            return false;
        }
        AsyncJobVO job = jobs.findByIdIncludingRemoved(schedule.getAsyncJobId());
        // A physically expired job is finished; an unknown/nonterminal status is not.
        return job != null && job.getStatus() != JobInfo.Status.SUCCEEDED && job.getStatus() != JobInfo.Status.FAILED;
    }
}
