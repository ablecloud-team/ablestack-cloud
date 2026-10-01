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
package com.cloud.vm.process;

import com.cloud.utils.db.TransactionLegacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** All statements parameterized. No automatic deletion of unresolved reservations. */
public class VmProcessOperationStore {
    public static final class Record {
        public String operation, request, fingerprint, json, result, state;
        public long vm, account, host, generation;
    }

    private Record record(ResultSet rs) throws SQLException {
        if (!rs.next()) return null;
        Record r = new Record();
        r.operation = rs.getString("operation_id");
        r.request = rs.getString("request_id");
        r.fingerprint = rs.getString("fingerprint");
        r.json = rs.getString("request_json");
        r.result = rs.getString("result_json");
        r.state = rs.getString("state");
        r.vm = rs.getLong("vm_id");
        r.account = rs.getLong("account_id");
        r.host = rs.getLong("host_id");
        r.generation = rs.getLong("generation");
        return r;
    }

    public Record find(long account, String request) throws SQLException {
        try (Connection tx = TransactionLegacy.getStandaloneConnectionWithException();
                PreparedStatement s =
                        tx.prepareStatement(
                                "SELECT * FROM vm_process_operation WHERE account_id=? AND"
                                    + " request_id=?")) {
            s.setLong(1, account);
            s.setString(2, request);
            try (ResultSet rs = s.executeQuery()) {
                return record(rs);
            }
        }
    }

    public Record get(long vm, String operation) throws SQLException {
        try (Connection tx = TransactionLegacy.getStandaloneConnectionWithException();
                PreparedStatement s =
                        tx.prepareStatement(
                                "SELECT * FROM vm_process_operation WHERE vm_id=? AND"
                                    + " operation_id=?")) {
            s.setLong(1, vm);
            s.setString(2, operation);
            try (ResultSet rs = s.executeQuery()) {
                return record(rs);
            }
        }
    }

    public void reserve(Record r) throws SQLException {
        try (Connection tx = TransactionLegacy.getStandaloneConnectionWithException()) {
            tx.setAutoCommit(false);
            try (PreparedStatement s =
                    tx.prepareStatement(
                            "INSERT INTO"
                                + " vm_process_operation(operation_id,request_id,account_id,vm_id,active_vm_id,host_id,generation,fingerprint,request_json,result_json,state)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, r.operation);
                s.setString(2, r.request);
                s.setLong(3, r.account);
                s.setLong(4, r.vm);
                s.setLong(5, r.vm);
                s.setLong(6, r.host);
                s.setLong(7, r.generation);
                s.setString(8, r.fingerprint);
                s.setString(9, r.json);
                s.setString(10, r.result);
                s.setString(11, "UNKNOWN");
                s.executeUpdate();
                tx.commit();
            } catch (SQLException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    public void finish(Record r, String state, String result) throws SQLException {
        try (Connection tx = TransactionLegacy.getStandaloneConnectionWithException()) {
            tx.setAutoCommit(false);
            try (PreparedStatement s =
                    tx.prepareStatement(
                            "UPDATE vm_process_operation SET"
                                + " result_json=?,state=?,active_vm_id=?,updated=CURRENT_TIMESTAMP(3)"
                                + " WHERE operation_id=? AND state='UNKNOWN'")) {
                s.setString(1, result);
                s.setString(2, state);
                if ("UNKNOWN".equals(state)) s.setLong(3, r.vm);
                else s.setNull(3, Types.BIGINT);
                s.setString(4, r.operation);
                s.executeUpdate();
                tx.commit();
            } catch (SQLException e) {
                tx.rollback();
                throw e;
            }
        }
    }
}
