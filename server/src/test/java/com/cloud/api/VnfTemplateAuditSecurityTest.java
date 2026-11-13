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

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VnfTemplateAuditSecurityTest {
    private String audit(String command, String response) throws Exception {
        var method = ApiServer.class.getDeclaredMethod("buildAuditTrail", StringBuilder.class, String.class, String.class);
        method.setAccessible(true);
        StringBuilder output = new StringBuilder();
        method.invoke(new ApiServer(), output, command, response);
        return output.toString();
    }
    @Test public void templateJsonResponsesDoNotLogVnfCredentials() throws Exception {
        for (String cmd : new String[]{"listTemplates", "listVnfTemplates", "registerTemplate", "registerVnfTemplate", "updateTemplate", "UPDATEVNFTEMPLATE", "queryAsyncJobResult", "copyTemplate"}) {
            String value = audit(cmd, "{\"vnfdetails\":{\"PASSWORD\":\"private-fixture\"}}");
            assertFalse(value.contains("private-fixture"));
            assertTrue(value.contains("not logged"));
        }
    }
    @Test public void templateXmlResponsesDoNotLogVnfCredentials() throws Exception {
        assertFalse(audit("updateVnfTemplate", "<vnfdetails><ssh_password>private-fixture</ssh_password></vnfdetails>").contains("private-fixture"));
    }
    @Test public void ordinaryTemplateResponsesKeepTheirAuditContext() throws Exception {
        assertTrue(audit("listTemplates", "{\"id\":\"template-fixture\"}").contains("template-fixture"));
    }
    @Test public void existingBucketResponseSuppressionIsPreserved() throws Exception {
        assertFalse(audit("getBucketObjectMetadata", "private-fixture").contains("private-fixture"));
    }
}
