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
import com.cloud.exception.InvalidParameterValueException;

/** Failed-operation reconciliation preserves later successful desired-state revisions. */
public final class StorageOperationReconciliation {
    private StorageOperationReconciliation() { }
    public static boolean superseded(final StorageServiceOperationVO operation, final List<StorageServiceOperationVO> history) {
        if (operation == null || !("RECOVERY_REQUIRED".equals(operation.getState()) || "BLOCKED".equals(operation.getState())
                || "ROLLED_BACK".equals(operation.getState()) || "RECONCILED_SUPERSEDED".equals(operation.getState()))) {
            throw new InvalidParameterValueException("Operation does not require reconciliation");
        }
        return history.stream().anyMatch(row -> row.getInstanceId() == operation.getInstanceId() && "COMPLETE".equals(row.getState())
                && row.getRevision() >= operation.getRevision());
    }
}
