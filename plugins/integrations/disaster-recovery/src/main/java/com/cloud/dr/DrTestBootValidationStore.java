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

public class DrTestBootValidationStore {
    public int update(String sql, Object... args) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(sql)) {
            for (int i = 0; i < args.length; i++) { ps.setObject(i + 1, args[i]); }
            return ps.executeUpdate();
        } catch (SQLException e) { throw new CloudRuntimeException("DR boot validation persistence failed", e); }
    }
    public void begin(DrTestSessionVO session) {
        int timeout = session.getBootTimeoutSeconds() == null ? 180 : session.getBootTimeoutSeconds();
        update("INSERT IGNORE INTO dr_test_boot_validation (session_id,run_id,vm_id,state,started_at,deadline_at,next_attempt_at) "
                + "VALUES (?,?,?,'PENDING',UTC_TIMESTAMP(),TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP()),UTC_TIMESTAMP())",
                session.getId(), session.getRunId(), session.getTargetVmId(), Math.max(1, timeout));
    }
    public List<Long> due() {
        update("UPDATE dr_test_boot_validation b JOIN dr_test_session s ON s.id=b.session_id "
                + "JOIN dr_run r ON r.id=b.run_id SET b.state='CANCELED',b.lease_until=NULL "
                + "WHERE b.state='PENDING' AND (s.removed IS NOT NULL OR s.cleanup_run_id IS NOT NULL "
                + "OR r.completed IS NOT NULL)");
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "SELECT b.session_id FROM dr_test_boot_validation b JOIN dr_test_session s ON s.id=b.session_id "
                + "JOIN dr_run r ON r.id=b.run_id WHERE b.state<>'CANCELED' AND (b.state<>'PENDING' OR (b.next_attempt_at<=UTC_TIMESTAMP() "
                + "AND (b.lease_until IS NULL OR b.lease_until<UTC_TIMESTAMP()))) "
                + "AND s.state IN ('CLOUD_VM_VALIDATING','ACTIVE','FAILED') AND s.removed IS NULL AND s.cleanup_run_id IS NULL "
                + "AND r.completed IS NULL ORDER BY b.next_attempt_at LIMIT 16"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) { ids.add(rs.getLong(1)); }
            return ids;
        } catch (SQLException e) { throw new CloudRuntimeException("DR validation scan failed", e); }
    }
    public String state(long id) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "SELECT b.state FROM dr_test_boot_validation b JOIN dr_test_session s ON s.id=b.session_id "
                + "WHERE b.session_id=? AND b.vm_id=s.target_vm_id AND b.run_id=s.run_id")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "MISSING"; }
        } catch (SQLException e) { throw new CloudRuntimeException("DR validation read failed", e); }
    }
    public boolean transition(long id, String state, String bootState, String error) {
        return update("UPDATE dr_test_session s JOIN dr_run r ON r.id=s.run_id "
                + "SET s.state=?,s.boot_validation_state=?,s.error_code=?,s.error_message=?,s.updated=UTC_TIMESTAMP() "
                + "WHERE s.id=? AND s.state='CLOUD_VM_VALIDATING' AND s.cleanup_run_id IS NULL "
                + "AND s.removed IS NULL AND r.completed IS NULL", state, bootState, error, error, id) == 1;
    }
    public int claim(long id, String token) {
        return update("UPDATE dr_test_boot_validation SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,20,UTC_TIMESTAMP()), "
                + "attempt_count=attempt_count+1 WHERE session_id=? AND state='PENDING' "
                + "AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP())", token, id);
    }
    public int remaining(long id) {
        try (PreparedStatement ps = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                "SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),deadline_at) FROM dr_test_boot_validation WHERE session_id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) { throw new CloudRuntimeException("DR validation deadline read failed", e); }
    }
    public void finish(long id, String token, String state, String evidence) {
        update("UPDATE dr_test_boot_validation b JOIN dr_test_session s ON s.id=b.session_id "
                + "JOIN dr_run r ON r.id=b.run_id SET b.state=IF(b.deadline_at<=UTC_TIMESTAMP(),'QGA_TIMEOUT',?), "
                + "b.evidence_json=?,b.validated_at=IF(?='QGA_VALIDATED' AND b.deadline_at>UTC_TIMESTAMP(),UTC_TIMESTAMP(),NULL), "
                + "b.lease_until=NULL,b.next_attempt_at=TIMESTAMPADD(SECOND,5,UTC_TIMESTAMP()) "
                + "WHERE b.session_id=? AND b.lease_token=? AND b.state='PENDING' "
                + "AND s.state='CLOUD_VM_VALIDATING' AND s.cleanup_run_id IS NULL AND s.removed IS NULL AND r.completed IS NULL",
                state, evidence, state, id, token);
    }
}
