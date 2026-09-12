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
package org.apache.cloudstack.api.command.admin.dr;

import com.google.gson.JsonObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Assert;
import org.junit.Test;

public class StartDrTestFailoverCmdTest {
    @Test
    public void independentTestRejectsProductionAliasAndUnknownModes() throws Exception {
        for (String mode : new String[] {"PRODUCTION", "PRODUCTION_NETWORK", "UNKNOWN"}) {
            StartDrTestFailoverCmd cmd = new StartDrTestFailoverCmd();
            FieldUtils.writeField(cmd, "sourceIndependent", Boolean.TRUE, true);
            FieldUtils.writeField(cmd, "networkMode", mode, true);
            FieldUtils.writeField(cmd, "networkId", 1L, true);
            try {
                cmd.addRequestProperties(new JsonObject());
                Assert.fail("Unsafe mode accepted: " + mode);
            } catch (ServerApiException expected) {
                Assert.assertTrue(expected.getMessage().contains("isolated network"));
            }
        }
    }
}
