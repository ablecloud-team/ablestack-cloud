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
package org.apache.cloudstack.api.command.user.snapshot;

import com.cloud.storage.snapshot.SnapshotApiService;
import com.cloud.storage.snapshot.SnapshotPolicy;
import com.cloud.utils.Pair;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SnapshotPolicyResponse;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

public class ListSnapshotPoliciesCmdTest {
    private ListSnapshotPoliciesCmd cmd;
    private SnapshotApiService snapshotService;
    private ResponseGenerator responseGenerator;

    @Test
    public void displayIsNullableAndPersonalScopeIsExplicit() throws Exception {
        Assert.assertNull(cmd.getDisplay());
        Assert.assertTrue(cmd.isDisplay());
        Assert.assertFalse(cmd.isIgnoreProject());
        java.lang.reflect.Field display = ListSnapshotPoliciesCmd.class.getDeclaredField("display");
        display.setAccessible(true); display.set(cmd, false);
        Assert.assertEquals(Boolean.FALSE, cmd.getDisplay());
        Assert.assertFalse(cmd.isDisplay());
        java.lang.reflect.Field ignore = ListSnapshotPoliciesCmd.class.getDeclaredField("ignoreProject");
        ignore.setAccessible(true); ignore.set(cmd, true);
        Assert.assertTrue(cmd.isIgnoreProject());
        Assert.assertEquals("ignoreproject", ignore.getAnnotation(org.apache.cloudstack.api.Parameter.class).name());
    }

    @Test
    public void sortingParametersAreExposedWithoutInventingDefaults() throws Exception {
        Assert.assertEquals("sortkey", ListSnapshotPoliciesCmd.class.getDeclaredField("sortKey").getAnnotation(org.apache.cloudstack.api.Parameter.class).name());
        Assert.assertEquals("sortorder", ListSnapshotPoliciesCmd.class.getDeclaredField("sortOrder").getAnnotation(org.apache.cloudstack.api.Parameter.class).name());
        Assert.assertNull(cmd.getSortKey());
        Assert.assertNull(cmd.getSortOrder());
        java.lang.reflect.Field key = ListSnapshotPoliciesCmd.class.getDeclaredField("sortKey");
        key.setAccessible(true); key.set(cmd, "maxsnaps");
        java.lang.reflect.Field order = ListSnapshotPoliciesCmd.class.getDeclaredField("sortOrder");
        order.setAccessible(true); order.set(cmd, "asc");
        Assert.assertEquals("maxsnaps", cmd.getSortKey());
        Assert.assertEquals("asc", cmd.getSortOrder());
    }

    @Before
    public void setUp() {
        cmd = new ListSnapshotPoliciesCmd();
        snapshotService = Mockito.mock(SnapshotApiService.class);
        responseGenerator = Mockito.mock(ResponseGenerator.class);

        cmd._snapshotService = snapshotService;
        cmd._responseGenerator = responseGenerator;
    }

    @Test
    public void testExecuteWithPolicies() {
        SnapshotPolicy policy = Mockito.mock(SnapshotPolicy.class);
        SnapshotPolicyResponse policyResponse = Mockito.mock(SnapshotPolicyResponse.class);
        List<SnapshotPolicy> policies = new ArrayList<>();
        policies.add(policy);

        Mockito.when(snapshotService.listSnapshotPolicies(cmd))
                .thenReturn(new Pair<>(policies, 1));
        Mockito.when(responseGenerator.createSnapshotPolicyResponse(policy))
                .thenReturn(policyResponse);

        cmd.execute();

        ListResponse<?> response = (ListResponse<?>) cmd.getResponseObject();
        Assert.assertNotNull(response);
        Assert.assertEquals(1, response.getResponses().size());
        Assert.assertEquals(policyResponse, response.getResponses().get(0));
    }

    @Test
    public void testExecuteWithNoPolicies() {
        Mockito.when(snapshotService.listSnapshotPolicies(cmd))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        cmd.execute();

        ListResponse<?> response = (ListResponse<?>) cmd.getResponseObject();
        Assert.assertNotNull(response);
        Assert.assertTrue(response.getResponses().isEmpty());
    }

    @Test
    public void operationalFactsAreOptInAndOnlyForAuthorizedListResults() throws Exception {
        SnapshotPolicy policy = Mockito.mock(SnapshotPolicy.class);
        Mockito.when(policy.getId()).thenReturn(7L);
        SnapshotPolicyResponse policyResponse = new SnapshotPolicyResponse();
        Mockito.when(snapshotService.listSnapshotPolicies(cmd)).thenReturn(new Pair<>(java.util.Collections.singletonList(policy), 1));
        Mockito.when(responseGenerator.createSnapshotPolicyResponse(policy)).thenReturn(policyResponse);
        cmd.execute();
        Mockito.verify(snapshotService, Mockito.never()).getSnapshotPolicyContext(Mockito.anyLong());
        Assert.assertNull(policyResponse.getPolicyContext());
        java.lang.reflect.Field field = ListSnapshotPoliciesCmd.class.getDeclaredField("includePolicyContext");
        field.setAccessible(true); field.set(cmd, true);
        java.util.Map<String, Object> context = java.util.Collections.singletonMap("version", 1);
        Mockito.when(snapshotService.getSnapshotPolicyContext(7L)).thenReturn(context);
        cmd.execute(); Assert.assertEquals(context, policyResponse.getPolicyContext());
        Mockito.when(snapshotService.listSnapshotPolicies(cmd)).thenReturn(new Pair<>(new ArrayList<>(), 0));
        cmd.execute(); Mockito.verify(snapshotService, Mockito.times(1)).getSnapshotPolicyContext(7L);
    }
}
