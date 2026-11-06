// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** Durable, provider-scoped deletion of ABLESTACK recovery points. */
public interface BackupDeletionService {
    String REQUEST_KEY = "ablestack.backup.delete.request";
    String OWNER_KEY = "ablestack.backup.delete.owner";
    String CLEANED_KEY = "ablestack.backup.delete.cleaned";

    static boolean supports(String provider) {
        return Set.of("ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam").contains(
                StringUtils.defaultString(provider));
    }

    static boolean isPending(Backup backup) {
        return StringUtils.isNotBlank(backup.getDetail(OWNER_KEY)) || StringUtils.isNotBlank(backup.getDetail(REQUEST_KEY));
    }

    class Entry {
        public long id;
        public String uuid;
        public String name;
        public String type;
    }

    class Plan {
        public long rootId;
        public String rootUuid;
        public String provider;
        public boolean forced;
        public String fingerprint;
        public String state;
        public String reason;
        public List<Entry> backups = new ArrayList<>();
        public List<Entry> dependents = new ArrayList<>();
    }

    @FunctionalInterface
    interface Cleanup {
        /** Called under the VM lifecycle lease. Delete only this point's owned artifacts. */
        boolean delete(Backup backup);
    }

    /** Read-only, authorized by BackupManager. No remote delete or catalog query. */
    Plan plan(Backup backup, boolean forced);

    /** True means the deletion request was accepted, including deferred ordinary deletion. */
    boolean delete(Backup backup, boolean forced, String expectedPlan, Cleanup cleanup);

    /** Resume persisted operator intent; returns true when ordinary reconciliation must skip this point. */
    boolean resume(Backup backup, Cleanup cleanup);
}
