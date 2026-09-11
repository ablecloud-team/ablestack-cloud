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

package org.apache.cloudstack.framework.extensions.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.cloud.network.Network;
import com.cloud.utils.Pair;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.extension.Extension;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class NetworkExtensionScriptTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    private Pair<Integer, String> execute(String status, int exitCode) throws Exception {
        File script = directory.newFile("provider.sh");
        String content = "#!/bin/sh\n[ \"$#\" -eq 3 ] || exit 99\n"
                + "printf '{\"status\":\"" + status + "\",\"file\":\"%s\",\"payload\":' \"$2\"\n"
                + "cat \"$2\"\nprintf '}\\n'\nexit " + exitCode + "\n";
        Files.writeString(script.toPath(), content, StandardCharsets.UTF_8);
        assertTrue(script.setExecutable(true));
        NetworkExtensionElement element = new NetworkExtensionElement() {
            @Override
            protected Extension resolveExtension(Network network) { return mock(Extension.class); }
            @Override
            protected File resolveScriptFile(Network network, Extension extension) { return script; }
        };
        JsonObject payload = new JsonObject();
        payload.addProperty("literal", "$(must-not-run); quote ' and Unicode 한글");
        Pair<Integer, String> result = element.executeScriptWithFilePayload(mock(Network.class), "prepare-nic", payload);
        JsonObject output = JsonParser.parseString(result.second()).getAsJsonObject();
        assertEquals(payload, output.getAsJsonObject("payload"));
        assertFalse(new File(output.get("file").getAsString()).exists());
        return result;
    }

    @Test
    public void scriptReceivesLiteralJsonAndTemporaryPayloadIsRemoved() throws Exception {
        assertEquals(Integer.valueOf(0), execute("success", 0).first());
    }

    @Test
    public void nonzeroExitIsNotReportedAsSuccessAndPayloadIsRemoved() throws Exception {
        assertEquals(Integer.valueOf(7), execute("success", 7).first());
    }

    @Test
    public void providerFailureStatusIsNotReportedAsSuccess() throws Exception {
        assertFalse(Integer.valueOf(0).equals(execute("failed", 0).first()));
    }
}
