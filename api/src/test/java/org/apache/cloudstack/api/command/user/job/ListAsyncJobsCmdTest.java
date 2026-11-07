// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command.user.job;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Test;

public class ListAsyncJobsCmdTest {
    private ListAsyncJobsCmd cmd(String resourceId, String type) throws Exception {
        ListAsyncJobsCmd cmd = new ListAsyncJobsCmd();
        for (String name : new String[]{"includeCompleted", "resourceId", "resourceType"}) {
            Field f = ListAsyncJobsCmd.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(cmd, name.equals("includeCompleted") ? Boolean.TRUE : name.equals("resourceId") ? resourceId : type);
        }
        return cmd;
    }

    @Test
    public void defaultsToPendingJobs() {
        assertFalse(new ListAsyncJobsCmd().getIncludeCompleted());
    }

    @Test
    public void completedJobsRequireAnExplicitResource() throws Exception {
        assertTrue(cmd("e0e13498-872d-47dc-b165-c847bf371dc1", "Network").getIncludeCompleted());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsMissingResourceId() throws Exception {
        cmd(null, "Network").getIncludeCompleted();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsMissingResourceType() throws Exception {
        cmd("e0e13498-872d-47dc-b165-c847bf371dc1", " ").getIncludeCompleted();
    }
}
