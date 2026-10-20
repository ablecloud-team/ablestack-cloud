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
package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

/** Real Manager producer -> packaged Native CLI, always inside the private bwrap runner. */
public class StorageProtocolEndpointNativeContractTest {
    @Test public void sameAddressAndNewAliasRequestsPreserveSourceBeforeNfsAndRestoreExactCanonicalMetadata() throws Exception {
        Path repo=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if(!Files.isRegularFile(repo.resolve("systemvm/test/run-preparation-health-wire-isolated.py")))repo=repo.getParent();
        Assert.assertTrue(Files.isRegularFile(repo.resolve("systemvm/test/run-preparation-health-wire-isolated.py")));
        for(boolean alias:new boolean[]{false,true}) {
            StorageProtocolEndpointPreparationTest.Manager m=new StorageProtocolEndpointPreparationTest.Manager();
            if(alias)m.addAlias();m.enable(alias?"10.10.13.229":"10.10.13.228");m.recovery();m.restorePreparedProtocolEndpoint(m.instance,m.operation);
            JsonObject input=new JsonObject();input.add("requests",m.requests);
            Path error=Files.createTempFile("nfs-manager-endpoint-", ".stderr");
            try {
                Process process=new ProcessBuilder("python3",repo.resolve("systemvm/test/run-preparation-health-wire-isolated.py").toString(),
                        "--suite","nfs-manager-endpoint").directory(repo.toFile()).redirectError(error.toFile()).start();
                try(java.io.OutputStream out=process.getOutputStream()){out.write(input.toString().getBytes(StandardCharsets.UTF_8));}
                boolean done=process.waitFor(90,TimeUnit.SECONDS);
                if(!done)process.destroyForcibly();
                Assert.assertTrue("Private Native contract runner timed out",done);
                Assert.assertEquals(Files.readString(error),0,process.exitValue());
                String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                JsonObject result=JsonParser.parseString(output).getAsJsonObject();
                Assert.assertTrue(output,result.get("success").getAsBoolean());
                Assert.assertTrue(output,result.get("nfsRuntimeNotExercised").getAsBoolean());
            } finally {Files.deleteIfExists(error);}
        }
    }
}
