/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.storage;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import com.cloud.agent.api.Answer;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.junit.Test;

public class AttachResponseTest {
    @Test public void genericFailurePreservesAgentRootCause() {
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                () -> VolumeApiServiceImpl.requireAttachAnswer(new Answer(null, false, "NoClassDefFoundError: MissingAgentDependency")));
        assertTrue(failure.getMessage().contains("MissingAgentDependency"));
        assertFalse(failure.getMessage().contains("cannot be cast"));
    }
    @Test public void nullOrUntypedSuccessNeverCommitsAttachment() {
        assertThrows(CloudRuntimeException.class, () -> VolumeApiServiceImpl.requireAttachAnswer(null));
        assertThrows(CloudRuntimeException.class, () -> VolumeApiServiceImpl.requireAttachAnswer(new Answer(null, true, null)));
        assertThrows(CloudRuntimeException.class, () -> VolumeApiServiceImpl.requireAttachAnswer(new AttachAnswer((com.cloud.agent.api.to.DiskTO)null)));
    }
    @Test public void typedFailureIsReturnedUnchanged() {
        AttachAnswer failure = new AttachAnswer("Storage denied access");
        assertSame(failure, VolumeApiServiceImpl.requireAttachAnswer(failure));
    }
}
