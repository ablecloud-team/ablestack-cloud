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
package com.cloud.agent.transport;
import java.util.Collections;
import com.cloud.agent.api.StorageServiceHostCommand;
import com.cloud.agent.api.StorageServiceHostAnswer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.*;
public class StorageServiceSensitiveLoggingTest {
    @Test public void payloadIsHiddenFromLogsButPreservedOnTheTransport() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture-vm","smb share apply",
                "{\"password\":\"fixture-only-secret\"}",30,Collections.singleton("password"));
        Logger logger=mock(Logger.class);when(logger.isEnabled(any(Level.class))).thenReturn(true);
        Gson logging=new GsonBuilder().setExclusionStrategies(new LoggingExclusionStrategy(logger)).create();
        String log=logging.toJson(command);
        Assert.assertFalse(log.contains("fixture-only-secret"));Assert.assertTrue(log.contains("smb share apply"));
        String wire=new Gson().toJson(command);
        Assert.assertEquals(command.getPayload(),new Gson().fromJson(wire,StorageServiceHostCommand.class).getPayload());
    }
    @Test public void guestAnswerContentIsNotLoggedEvenAtTrace() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("fixture-vm","iscsi target apply","{}",30);
        StorageServiceHostAnswer answer=new StorageServiceHostAnswer(command,false,"fixture-only-secret","fixture-only-secret");
        Logger logger=mock(Logger.class);when(logger.isEnabled(any(Level.class))).thenReturn(true);
        Gson logging=new GsonBuilder().setExclusionStrategies(new LoggingExclusionStrategy(logger)).create();
        Assert.assertFalse(logging.toJson(answer).contains("fixture-only-secret"));
        Assert.assertEquals("fixture-only-secret",new Gson().fromJson(new Gson().toJson(answer),StorageServiceHostAnswer.class).getResultJson());
    }
}
