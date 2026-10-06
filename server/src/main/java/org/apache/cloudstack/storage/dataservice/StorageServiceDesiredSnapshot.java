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
package org.apache.cloudstack.storage.dataservice;

import java.sql.*;
import java.util.*;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.*;

/** Snapshots only the reversible desired-state tables; never modifies VM or volume tables. */
public class StorageServiceDesiredSnapshot {
    private static final List<String> TABLES = Arrays.asList(
            "storage_service_protocol", "storage_file_share", "storage_block_target", "storage_identity_domain", "storage_access_rule");
    private static final int MAX_SNAPSHOT_BYTES = 16 * 1024 * 1024;

    private String predicate(String table) {
        if ("storage_access_rule".equals(table)) {
            return "(resource_type='FILE_SHARE' AND resource_id IN (SELECT id FROM cloud.storage_file_share WHERE instance_id=?)) OR " +
                    "(resource_type='BLOCK_TARGET' AND resource_id IN (SELECT id FROM cloud.storage_block_target WHERE instance_id=?))";
        }
        return "instance_id=?";
    }

    private void bindScope(PreparedStatement statement, String table, long instanceId) throws SQLException {
        statement.setLong(1, instanceId);
        if ("storage_access_rule".equals(table)) statement.setLong(2, instanceId);
    }

    public String capture(long instanceId) {
        final JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schemaVersion", 1); snapshot.addProperty("instanceId", instanceId);
        final JsonArray tables = new JsonArray();
        try {
            for (String table : TABLES) {
                final JsonObject entry = new JsonObject(); entry.addProperty("table", table);
                JsonArray columns = new JsonArray(); JsonArray rows = new JsonArray();
                try (PreparedStatement query = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                        "SELECT * FROM cloud." + table + " WHERE " + predicate(table) + " ORDER BY id")) {
                    bindScope(query, table, instanceId);
                    try (ResultSet result = query.executeQuery()) {
                        ResultSetMetaData metadata = result.getMetaData();
                        for (int column = 1; column <= metadata.getColumnCount(); column++) {
                            JsonObject definition = new JsonObject();
                            definition.addProperty("name", metadata.getColumnName(column));
                            definition.addProperty("type", metadata.getColumnType(column)); columns.add(definition);
                        }
                        while (result.next()) {
                            JsonObject row = new JsonObject();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                String name = metadata.getColumnName(column);
                                Object value = result.getObject(column);
                                if (value == null) row.add(name, JsonNull.INSTANCE);
                                else if (metadata.getColumnType(column) == Types.TIMESTAMP || metadata.getColumnType(column) == Types.DATE) row.addProperty(name, result.getTimestamp(column).getTime());
                                else if (value instanceof Number) row.addProperty(name, (Number) value);
                                else if (value instanceof Boolean) row.addProperty(name, (Boolean) value);
                                else row.addProperty(name, value.toString());
                            }
                            rows.add(row);
                        }
                    }
                }
                entry.add("columns", columns); entry.add("rows", rows); tables.add(entry);
            }
            snapshot.add("tables", tables);
            String json = snapshot.toString();
            if (json.length() > MAX_SNAPSHOT_BYTES) throw new CloudRuntimeException("Desired-state snapshot exceeds the configured safety bound");
            return json;
        } catch (SQLException failure) {
            throw new CloudRuntimeException("Unable to capture Storage Service desired state", failure);
        }
    }

    public void restore(long instanceId, String json) {
        final JsonObject snapshot = new JsonParser().parse(json).getAsJsonObject();
        if (snapshot.get("schemaVersion").getAsInt() != 1 || snapshot.get("instanceId").getAsLong() != instanceId) {
            throw new CloudRuntimeException("Desired-state snapshot identity does not match");
        }
        final Map<String, JsonObject> entries = new HashMap<>();
        for (JsonElement element : snapshot.getAsJsonArray("tables")) {
            JsonObject entry = element.getAsJsonObject();
            String table = entry.get("table").getAsString();
            if (!TABLES.contains(table) || entries.put(table, entry) != null) throw new CloudRuntimeException("Invalid desired-state snapshot table");
        }
        if (entries.size() != TABLES.size()) throw new CloudRuntimeException("Desired-state snapshot is incomplete");
        Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            try {
                List<String> reverse = new ArrayList<>(TABLES); Collections.reverse(reverse);
                for (String table : reverse) {
                    JsonArray rows = entries.get(table).getAsJsonArray("rows");
                    String ids = rows.size() == 0 ? "-1" : String.join(",", ids(rows));
                    try (PreparedStatement remove = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                            "DELETE FROM cloud." + table + " WHERE (" + predicate(table) + ") AND id NOT IN (" + ids + ")")) {
                        bindScope(remove, table, instanceId); remove.executeUpdate();
                    }
                }
                for (String table : TABLES) {
                    JsonObject entry = entries.get(table);
                    List<String> names = new ArrayList<>(); List<Integer> types = new ArrayList<>();
                    for (JsonElement element : entry.getAsJsonArray("columns")) {
                        JsonObject column = element.getAsJsonObject(); String name = column.get("name").getAsString();
                        if (!name.matches("[A-Za-z0-9_]+")) throw new CloudRuntimeException("Unsafe snapshot column");
                        names.add(name); types.add(column.get("type").getAsInt());
                    }
                    List<String> assignments = new ArrayList<>();
                    for (String name : names) if (!"id".equals(name)) assignments.add(name + "=VALUES(" + name + ")");
                    String sql = "INSERT INTO cloud." + table + " (" + String.join(",", names) + ") VALUES (" +
                            String.join(",", Collections.nCopies(names.size(), "?")) + ") ON DUPLICATE KEY UPDATE " + String.join(",", assignments);
                    for (JsonElement element : entry.getAsJsonArray("rows")) {
                        JsonObject row = element.getAsJsonObject();
                        try (PreparedStatement insert = TransactionLegacy.currentTxn().prepareAutoCloseStatement(sql)) {
                            for (int index = 0; index < names.size(); index++) {
                                JsonElement value = row.get(names.get(index)); int type = types.get(index);
                                if (value == null || value.isJsonNull()) insert.setNull(index + 1, type);
                                else if (type == Types.TIMESTAMP || type == Types.DATE) insert.setTimestamp(index + 1, new Timestamp(value.getAsLong()));
                                else if (value.getAsJsonPrimitive().isBoolean()) insert.setBoolean(index + 1, value.getAsBoolean());
                                else insert.setObject(index + 1, value.getAsString(), type);
                            }
                            insert.executeUpdate();
                        }
                    }
                }
                return true;
            } catch (SQLException failure) {
                throw new CloudRuntimeException("Unable to restore Storage Service desired-state snapshot", failure);
            }
        });
    }

    private List<String> ids(JsonArray rows) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : rows) {
            long id = element.getAsJsonObject().get("id").getAsLong();
            if (id <= 0) throw new CloudRuntimeException("Invalid snapshot identity");
            values.add(Long.toString(id));
        }
        return values;
    }
}
