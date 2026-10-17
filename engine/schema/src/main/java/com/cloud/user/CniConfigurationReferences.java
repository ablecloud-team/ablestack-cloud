// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.user;

import java.util.function.Supplier;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

/** Serializes reference publication and deletion across management servers. */
public final class CniConfigurationReferences {
    private CniConfigurationReferences() { }

    public static <T> T withLock(Long id, Supplier<T> action) {
        if (id == null) { return action.get(); }
        if (id < 1) { throw new CloudRuntimeException("A valid CNI configuration is required"); }
        GlobalLock lock = GlobalLock.getInternLock("CniConfiguration.Reference." + id);
        try {
            if (!lock.lock(10)) {
                throw new CloudRuntimeException("Another CNI reference operation is in progress; retry later");
            }
            try { return action.get(); } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }
}
