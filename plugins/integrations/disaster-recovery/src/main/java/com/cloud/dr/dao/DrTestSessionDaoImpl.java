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

package com.cloud.dr.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import com.cloud.dr.DrTestSessionVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

@DB
public class DrTestSessionDaoImpl extends GenericDaoBase<DrTestSessionVO, Long> implements DrTestSessionDao {
    private final SearchBuilder<DrTestSessionVO> activeByRun;
    private final SearchBuilder<DrTestSessionVO> activeByPlan;
    private final SearchBuilder<DrTestSessionVO> byRun;
    private final SearchBuilder<DrTestSessionVO> activeByTargetVm;

    public DrTestSessionDaoImpl() {
        activeByRun = createSearchBuilder();
        activeByRun.and("runId", activeByRun.entity().getRunId(), SearchCriteria.Op.EQ);
        activeByRun.and("removed", activeByRun.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByRun.done();
        activeByPlan = createSearchBuilder();
        activeByPlan.and("planId", activeByPlan.entity().getPlanId(), SearchCriteria.Op.EQ);
        activeByPlan.and("removed", activeByPlan.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByPlan.done();
        byRun = createSearchBuilder();
        byRun.and("runId", byRun.entity().getRunId(), SearchCriteria.Op.EQ);
        byRun.done();
        activeByTargetVm = createSearchBuilder();
        activeByTargetVm.and("targetVmId", activeByTargetVm.entity().getTargetVmId(), SearchCriteria.Op.EQ);
        activeByTargetVm.and("removed", activeByTargetVm.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByTargetVm.done();
    }

    @Override public DrTestSessionVO findActiveByRunId(long runId) {
        SearchCriteria<DrTestSessionVO> sc = activeByRun.create();
        sc.setParameters("runId", runId);
        return findOneBy(sc);
    }

    @Override public DrTestSessionVO findActiveByPlanId(long planId) {
        SearchCriteria<DrTestSessionVO> sc = activeByPlan.create();
        sc.setParameters("planId", planId);
        return findOneBy(sc);
    }

    @Override public DrTestSessionVO findByRunIdIncludingRemoved(long runId) {
        SearchCriteria<DrTestSessionVO> sc = byRun.create();
        sc.setParameters("runId", runId);
        return findOneIncludingRemovedBy(sc);
    }

    @Override public List<DrTestSessionVO> listActiveByTargetVmId(long targetVmId) {
        SearchCriteria<DrTestSessionVO> sc = activeByTargetVm.create();
        sc.setParameters("targetVmId", targetVmId);
        return listBy(sc);
    }

    @Override
    public boolean restoreSoftClosedForMaterialization(DrTestSessionVO session) {
        if (session == null || session.getId() == 0 || session.getRunId() == 0) {
            return false;
        }
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        try {
            txn.start();
            // Generic UpdateBuilder turns null DaoGenerated fields into generated values.
            PreparedStatement pstmt = txn.prepareAutoCloseStatement(
                    "UPDATE dr_test_session SET state=?, cleanup_required=?, error_code=NULL, "
                            + "error_message=NULL, updated=UTC_TIMESTAMP(), removed=NULL "
                            + "WHERE id=? AND run_id=? AND removed IS NOT NULL "
                            + "AND target_vm_id IS NULL AND (artifact_manifest IS NULL OR artifact_manifest='')");
            pstmt.setString(1, session.getState());
            pstmt.setBoolean(2, session.isCleanupRequired());
            pstmt.setLong(3, session.getId());
            pstmt.setLong(4, session.getRunId());
            int updated = pstmt.executeUpdate();
            txn.commit();
            return updated == 1 || findActiveByRunId(session.getRunId()) != null;
        } catch (SQLException e) {
            txn.rollback();
            throw new CloudRuntimeException("Unable to restore the DR test session for materialization", e);
        }
    }
}
