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

package com.cloud.api.query;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import org.junit.Test;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.storage.Storage.ImageFormat;

public class QueryTemplateReadinessTest {
    private TemplateJoinVO row(ImageFormat format, State state) throws Exception {
        TemplateJoinVO row = new TemplateJoinVO();
        Field formatField = TemplateJoinVO.class.getDeclaredField("format");
        Field stateField = TemplateJoinVO.class.getDeclaredField("state");
        formatField.setAccessible(true);
        stateField.setAccessible(true);
        formatField.set(row, format);
        stateField.set(row, state);
        return row;
    }

    @Test
    public void explicitReadinessIsAnExactComplementAcrossAllStorageStates() throws Exception {
        for (State state : State.values()) {
            TemplateJoinVO image = row(ImageFormat.QCOW2, state);
            if (state == State.Ready) {
                assertTrue(QueryManagerImpl.templateMatchesReadiness(image, true));
                assertFalse(QueryManagerImpl.templateMatchesReadiness(image, false));
            } else {
                assertFalse(QueryManagerImpl.templateMatchesReadiness(image, true));
                assertTrue(QueryManagerImpl.templateMatchesReadiness(image, false));
            }
        }
        assertTrue(QueryManagerImpl.templateMatchesReadiness(row(ImageFormat.QCOW2, null), false));
    }

    @Test
    public void externalAndBaremetalAreReadyWithoutDownloading() throws Exception {
        for (ImageFormat format : new ImageFormat[] {ImageFormat.EXTERNAL, ImageFormat.BAREMETAL}) {
            assertTrue(QueryManagerImpl.templateMatchesReadiness(row(format, null), true));
            assertFalse(QueryManagerImpl.templateMatchesReadiness(row(format, null), false));
        }
    }

    @Test
    public void omittedReadinessPreservesLegacyResults() throws Exception {
        for (State state : State.values()) {
            assertTrue(QueryManagerImpl.templateMatchesReadiness(row(ImageFormat.QCOW2, state), null));
        }
    }
}
