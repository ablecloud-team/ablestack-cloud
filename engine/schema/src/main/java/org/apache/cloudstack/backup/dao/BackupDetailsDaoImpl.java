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
package org.apache.cloudstack.backup.dao;


import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import org.apache.cloudstack.backup.ThirdPartyBackupAdmission;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.BackupDetailVO;
import org.apache.cloudstack.resourcedetail.ResourceDetailsDaoBase;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class BackupDetailsDaoImpl extends ResourceDetailsDaoBase<BackupDetailVO> implements BackupDetailsDao {

    private SearchBuilder<BackupDetailVO> backupDetailSearch;
    private SearchBuilder<BackupDetailVO> ordinaryDetailsSearch;

    private static final String BACKUP_ID = "backup_id";

    private static final String KEY = "key";

    @PostConstruct
    protected void init() {
        backupDetailSearch = createSearchBuilder();
        backupDetailSearch.and(BACKUP_ID, backupDetailSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        backupDetailSearch.and(KEY, backupDetailSearch.entity().getName(), SearchCriteria.Op.NEQ);
        backupDetailSearch.done();
        ordinaryDetailsSearch = createSearchBuilder();
        ordinaryDetailsSearch.and(BACKUP_ID, ordinaryDetailsSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        ordinaryDetailsSearch.and(KEY, ordinaryDetailsSearch.entity().getName(), SearchCriteria.Op.NOTIN);
        ordinaryDetailsSearch.and("restoreHistory", ordinaryDetailsSearch.entity().getName(), SearchCriteria.Op.NLIKE);
        ordinaryDetailsSearch.done();
    }

    @Override
    public void removeDetailsExcept(long backupId, String exception) {
        SearchCriteria<BackupDetailVO> sc = backupDetailSearch.create();
        sc.setParameters(BACKUP_ID, backupId);
        sc.setParameters(KEY, exception);
        super.expunge(sc);
    }

    @Override
    public void addDetail(long resourceId, String key, String value, boolean display) {
        super.addDetail(new BackupDetailVO(resourceId, key, value, display));
    }

    @Override
    public void saveDetails(java.util.List<BackupDetailVO> details) {
        if (details.isEmpty()) { return; }
        // Admission and restore records belong to their coordinators. A stale provider
        // BackupVO must not overwrite reservations, requests or earlier transfer results.
        Transaction.execute((TransactionCallback<Boolean>) status -> {
            SearchCriteria<BackupDetailVO> sc = ordinaryDetailsSearch.create();
            sc.setParameters(BACKUP_ID, details.get(0).getResourceId());
            sc.setParameters(KEY, ThirdPartyBackupAdmission.BACKUP_KEY, ThirdPartyBackupAdmission.RESTORE_KEY,
                    ThirdPartyBackupAdmission.INSPECTION_BACKUP_KEY, ThirdPartyBackupAdmission.INSPECTION_RESTORE_KEY,
                    ThirdPartyBackupRestore.PLAN_KEY, ThirdPartyBackupRestore.TRANSFER_KEY,
                    ThirdPartyBackupRestore.CLEANUP_STATE_KEY, ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY);
            sc.setParameters("restoreHistory", ThirdPartyBackupRestore.HISTORY_PREFIX + "%");
            expunge(sc);
            for (BackupDetailVO detail : details) {
                if (!ThirdPartyBackupAdmission.BACKUP_KEY.equals(detail.getName())
                        && !ThirdPartyBackupAdmission.RESTORE_KEY.equals(detail.getName())
                        && !ThirdPartyBackupAdmission.INSPECTION_BACKUP_KEY.equals(detail.getName())
                        && !ThirdPartyBackupAdmission.INSPECTION_RESTORE_KEY.equals(detail.getName())
                        && !ThirdPartyBackupRestore.PLAN_KEY.equals(detail.getName())
                        && !ThirdPartyBackupRestore.TRANSFER_KEY.equals(detail.getName())
                        && !ThirdPartyBackupRestore.CLEANUP_STATE_KEY.equals(detail.getName())
                        && !ThirdPartyBackupRestore.CLEANUP_DETAILS_KEY.equals(detail.getName())
                        && !detail.getName().startsWith(ThirdPartyBackupRestore.HISTORY_PREFIX)) { persist(detail); }
            }
            return true;
        });
    }
}
