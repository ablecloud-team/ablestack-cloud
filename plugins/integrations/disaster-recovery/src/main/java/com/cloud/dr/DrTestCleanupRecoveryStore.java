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
package com.cloud.dr;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

/** Durable protection intent, independent of the removable test artifacts. */
public class DrTestCleanupRecoveryStore {
    public static class Intent {
        public long testRunId;
        public long planId;
        public Long cleanupRunId;
        public String desiredState;
        public String state;
    }
    private int update(String sql, Object... args) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(sql)) {
            for (int i = 0; i < args.length; i++) { ps.setObject(i + 1, args[i]); }
            return ps.executeUpdate();
        } catch (SQLException e) { throw new CloudRuntimeException("DR test cleanup intent persistence failed", e); }
    }
    public void capture(long planId, long testRunId, String desired) {
        update("INSERT IGNORE INTO dr_test_cleanup_recovery (test_run_id,plan_id,desired_state,state,next_attempt_at) "
                + "VALUES (?,?,?,'HELD',UTC_TIMESTAMP())", testRunId, planId, desired);
    }
    public void arm(long planId, long testRunId, long cleanupRunId) {
        // Legacy sessions have no trustworthy pre-test RUNNING intent. Never invent one.
        capture(planId, testRunId, "PAUSED");
        update("UPDATE dr_test_cleanup_recovery SET cleanup_run_id=?,state='PENDING',next_attempt_at=UTC_TIMESTAMP() "
                + "WHERE test_run_id=? AND state='HELD'", cleanupRunId, testRunId);
    }
    private List<Intent> query(String suffix, Object... args) {
        List<Intent> rows = new ArrayList<>();
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "SELECT test_run_id,plan_id,cleanup_run_id,desired_state,state FROM dr_test_cleanup_recovery " + suffix)) {
            for (int i = 0; i < args.length; i++) { ps.setObject(i + 1, args[i]); }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Intent row = new Intent(); row.testRunId=rs.getLong(1); row.planId=rs.getLong(2);
                    long cleanup=rs.getLong(3); row.cleanupRunId=rs.wasNull() ? null : cleanup;
                    row.desiredState=rs.getString(4); row.state=rs.getString(5); rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) { throw new CloudRuntimeException("DR test cleanup intent read failed", e); }
    }
    public Intent find(long testRunId) {
        List<Intent> rows=query("WHERE test_run_id=?", testRunId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public boolean pending(long planId) { return !query("WHERE plan_id=? AND state='PENDING' LIMIT 1", planId).isEmpty(); }
    public List<Intent> due() {
        return query("WHERE state='PENDING' AND next_attempt_at<=UTC_TIMESTAMP() "
                + "AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP()) ORDER BY next_attempt_at LIMIT 16");
    }
    public boolean claim(long id, String token) {
        return update("UPDATE dr_test_cleanup_recovery SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,120,UTC_TIMESTAMP()), "
                + "attempt_count=attempt_count+1 WHERE test_run_id=? AND state='PENDING' "
                + "AND next_attempt_at<=UTC_TIMESTAMP() AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP())", token,id)==1;
    }
    public void finish(long id, String token, String state, String error) {
        update("UPDATE dr_test_cleanup_recovery SET state=?,last_error=?,lease_until=NULL, "
                + "next_attempt_at=TIMESTAMPADD(SECOND,10,UTC_TIMESTAMP()),updated_at=UTC_TIMESTAMP() "
                + "WHERE test_run_id=? AND lease_token=? AND state='PENDING'",state,error,id,token);
    }
}
