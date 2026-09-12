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

package com.cloud.api;

import com.cloud.api.dispatch.DispatchChain;
import com.cloud.api.dispatch.DispatchTask;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.context.CallContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

public class ApiDispatcherTest {
    protected static final Long resourceId = 1L;
    protected static final ApiCommandResourceType resourceType = ApiCommandResourceType.Account;

    ApiDispatcher apiDispatcher;

    @Before
    public void injectMocks() throws SecurityException, NoSuchFieldException,
            IllegalArgumentException, IllegalAccessException {
        apiDispatcher = new ApiDispatcher();
        DispatchChain dispatchChain = Mockito.mock(DispatchChain.class);
        Mockito.doNothing().when(dispatchChain).dispatch(Mockito.any(DispatchTask.class));
        ReflectionTestUtils.setField(apiDispatcher, "standardDispatchChain", dispatchChain);
    }

    @Test
    public void testBaseCmdDispatchCallContext() {
        TesBaseCmd cmd = new TesBaseCmd();
        try {
            apiDispatcher.dispatch(cmd, new HashMap<String, String>(), false);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Assert.assertEquals(CallContext.current().getEventResourceId(), resourceId);
        Assert.assertEquals(CallContext.current().getEventResourceType(), resourceType);
    }

    @Test
    public void isoAttachAndDetachShareOneJobSlotPerVm() throws Exception {
        org.apache.cloudstack.framework.jobs.AsyncJobManager jobs = Mockito.mock(org.apache.cloudstack.framework.jobs.AsyncJobManager.class);
        ReflectionTestUtils.setField(apiDispatcher, "_asyncMgr", jobs);
        for (org.apache.cloudstack.api.BaseAsyncCmd command : new org.apache.cloudstack.api.BaseAsyncCmd[]{
                new org.apache.cloudstack.api.command.user.iso.AttachIsoCmd(),
                new org.apache.cloudstack.api.command.user.iso.DetachIsoCmd()}) {
            org.apache.cloudstack.api.BaseAsyncCmd spy = Mockito.spy(command);
            org.apache.cloudstack.framework.jobs.AsyncJob job = Mockito.mock(org.apache.cloudstack.framework.jobs.AsyncJob.class);
            spy.setJob(job);
            ReflectionTestUtils.setField(spy, "virtualMachineId", 993L);
            apiDispatcher.dispatch(spy, java.util.Map.of(org.apache.cloudstack.api.ApiConstants.CTX_START_EVENT_ID, "1"), false);
            Mockito.verify(jobs).syncAsyncJobExecution(job, org.apache.cloudstack.api.BaseAsyncCmd.vmIsoSyncObject, 993L, 1L);
            Mockito.verify(spy, Mockito.never()).execute();
        }
    }

    protected class TesBaseCmd extends BaseCmd {

        @Override
        public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException, ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {

        }

        @Override
        public String getCommandName() {
            return "testCommand";
        }

        @Override
        public long getEntityOwnerId() {
            return 1L;
        }

        @Override
        public Long getApiResourceId() {
            return resourceId;
        }

        @Override
        public ApiCommandResourceType getApiResourceType() {
            return resourceType;
        }
    }
}
