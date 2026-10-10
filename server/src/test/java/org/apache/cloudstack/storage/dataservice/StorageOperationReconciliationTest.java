// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;

public class StorageOperationReconciliationTest {
    private StorageServiceOperationVO row(long instance, long revision, String state) {
        StorageServiceOperationVO value = new StorageServiceOperationVO();value.setInstanceId(instance);value.setRevision(revision);value.setState(state);return value;
    }
    @Test public void laterSuccessReconcilesFailedRevisionWithoutRewindingIt() {
        Assert.assertTrue(StorageOperationReconciliation.superseded(row(7, 4, "RECOVERY_REQUIRED"), List.of(row(7, 5, "COMPLETE"))));
        Assert.assertTrue(StorageOperationReconciliation.superseded(row(7, 4, "RECONCILED_SUPERSEDED"), List.of(row(7, 5, "COMPLETE"))));
    }
    @Test public void otherInstanceOlderOrFailedRevisionsCannotAuthorizeReconciliation() {
        StorageServiceOperationVO failed = row(7, 4, "RECOVERY_REQUIRED");
        Assert.assertFalse(StorageOperationReconciliation.superseded(failed, List.of(row(9, 8, "COMPLETE"), row(7, 3, "COMPLETE"), row(7, 5, "ROLLED_BACK"))));
    }
    @Test public void healthyOrActiveOperationCannotBeRewrittenByRecovery() {
        for (String state : new String[] {"COMPLETE", "RUNNING", "APPLYING"}) {
            Assert.assertThrows(InvalidParameterValueException.class, () -> StorageOperationReconciliation.superseded(row(7, 4, state), List.of(row(7, 5, "COMPLETE"))));
        }
    }
}
