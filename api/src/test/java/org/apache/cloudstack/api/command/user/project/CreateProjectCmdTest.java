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

package org.apache.cloudstack.api.command.user.project;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.projects.ProjectService;
import com.cloud.user.Account;

@RunWith(MockitoJUnitRunner.class)
public class CreateProjectCmdTest  {

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private CreateProjectCmd createProjectCmd = new CreateProjectCmd();

    @Test
    public void testGetDisplayTextWhenEmpty() {
        String netName = "net-project";
        ReflectionTestUtils.setField(createProjectCmd , "name", netName);
        Assert.assertEquals(createProjectCmd.getDisplayText(), netName);
    }

    @Test
    public void testGetEntityOwnerIdUsesExplicitAccountIdWithoutFillingInCallerScope() {
        ReflectionTestUtils.setField(createProjectCmd, "accountId", 42L);
        Account owner = Mockito.mock(Account.class);
        Mockito.when(owner.getId()).thenReturn(42L);
        Mockito.when(projectService.resolveProjectOwner(null, null, null, 42L)).thenReturn(owner);

        Assert.assertEquals(42L, createProjectCmd.getEntityOwnerId());
    }

}
