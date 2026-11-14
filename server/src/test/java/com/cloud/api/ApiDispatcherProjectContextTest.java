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

import java.util.HashMap;
import java.util.Map;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloud.api.dispatch.DispatchChain;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.projects.Project;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;

public class ApiDispatcherProjectContextTest {
    private ApiDispatcher dispatcher;
    private DispatchChain chain;
    private BaseAsyncCmd command;
    private Map<String, String> params;

    @Before
    public void setup() {
        User user = mock(User.class);
        Account account = mock(Account.class);
        when(user.getId()).thenReturn(1L);
        when(account.getId()).thenReturn(1L);
        CallContext.register(user, account);
        dispatcher = new ApiDispatcher();
        dispatcher._entityMgr = mock(EntityManager.class);
        chain = mock(DispatchChain.class);
        dispatcher.standardDispatchChain = chain;
        command = mock(BaseAsyncCmd.class);
        params = new HashMap<>();
        params.put(ApiConstants.CTX_START_EVENT_ID, "1");
    }

    @After
    public void cleanup() {
        CallContext.unregisterAll();
    }

    @Test
    public void asyncEntityValidationAndExecutionSeeSelectedProject() throws Exception {
        Project project = mock(Project.class);
        params.put(ApiConstants.PROJECT_ID, "selected-project");
        when(dispatcher._entityMgr.findByUuidIncludingRemoved(Project.class, "selected-project")).thenReturn(project);
        doAnswer(call -> { assertSame(project, CallContext.current().getProject()); return null; }).when(chain).dispatch(any());
        doAnswer(call -> { assertSame(project, CallContext.current().getProject()); return null; }).when(command).execute();
        dispatcher.dispatch(command, params, true);
        verify(command).execute();
    }

    @Test
    public void absentProjectParameterDoesNotReplaceExistingNestedContext() throws Exception {
        Project existing = mock(Project.class);
        CallContext.current().setProject(existing);
        doAnswer(call -> { assertSame(existing, CallContext.current().getProject()); return null; }).when(chain).dispatch(any());
        dispatcher.dispatch(command, params, true);
        verifyNoInteractions(dispatcher._entityMgr);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void invalidExplicitProjectCannotReusePriorScopeOrExecute() throws Exception {
        CallContext.current().setProject(mock(Project.class));
        params.put(ApiConstants.PROJECT_ID, "missing-project");
        doAnswer(call -> { assertNull(CallContext.current().getProject()); throw new InvalidParameterValueException("invalid project"); }).when(chain).dispatch(any());
        try {
            dispatcher.dispatch(command, params, true);
        } finally {
            verify(command, never()).execute();
        }
    }
}
