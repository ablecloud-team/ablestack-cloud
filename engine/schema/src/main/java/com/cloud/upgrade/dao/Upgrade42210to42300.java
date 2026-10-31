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
package com.cloud.upgrade.dao;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.exception.CloudRuntimeException;

public class Upgrade42210to42300 extends DbUpgradeAbstractImpl implements DbUpgrade, DbUpgradeSystemVmTemplate {

    static final String LEGACY_API_KEY_COLUMNS_QUERY = "SELECT COUNT(1) FROM information_schema.columns " +
            "WHERE table_schema = 'cloud' AND table_name = 'user' AND column_name IN ('api_key', 'secret_key')";
    static final String LEGACY_API_KEYS_QUERY = "SELECT user.id, account.domain_id, account.id, user.api_key, user.secret_key " +
            "FROM cloud.user AS user JOIN cloud.account AS account ON user.account_id = account.id " +
            "WHERE user.api_key IS NOT NULL AND user.secret_key IS NOT NULL";
    static final String ACTIVE_KEY_PAIRS_QUERY = "SELECT id FROM cloud.api_keypair " +
            "WHERE user_id = ? AND api_key = ? AND removed IS NULL ORDER BY id";
    static final String INSERT_KEY_PAIR = "INSERT INTO cloud.api_keypair " +
            "(uuid, user_id, domain_id, account_id, api_key, secret_key, created, name) " +
            "VALUES (?, ?, ?, ?, ?, ?, NOW(), 'Active key pair')";
    static final String UPDATE_KEY_PAIR_SECRET = "UPDATE cloud.api_keypair SET secret_key = ? WHERE id = ?";
    static final String REMOVE_DUPLICATE_KEY_PAIR = "UPDATE cloud.api_keypair SET removed = NOW() WHERE id = ? AND removed IS NULL";

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[]{"4.22.1.0", "4.23.0.0"};
    }

    @Override
    public String getUpgradedVersion() {
        return "4.23.0.0";
    }

    @Override
    public InputStream[] getPrepareScripts() {
        final String scriptFile = "META-INF/db/schema-42210to42300.sql";
        final InputStream script = Thread.currentThread().getContextClassLoader().getResourceAsStream(scriptFile);
        if (script == null) {
            throw new CloudRuntimeException("Unable to find " + scriptFile);
        }

        return new InputStream[] {script};
    }

    @Override
    public void performDataMigration(Connection conn) {
        EuropaSecuritySchemaUpgrade.migrate(conn);
        migrateLegacyUserApiKeyPairs(conn);
        unhideJsInterpretationEnabled(conn);
        dropUsageVmInstanceIndex(conn);
    }

    protected void migrateLegacyUserApiKeyPairs(Connection conn) {
        if (!legacyApiKeyColumnsExist(conn)) {
            logger.debug("Legacy user API key columns are absent; skipping API key pair migration.");
            return;
        }

        try (PreparedStatement legacyKeys = conn.prepareStatement(LEGACY_API_KEYS_QUERY);
             PreparedStatement activeKeyPairs = conn.prepareStatement(ACTIVE_KEY_PAIRS_QUERY);
             PreparedStatement insertKeyPair = conn.prepareStatement(INSERT_KEY_PAIR);
             PreparedStatement updateSecret = conn.prepareStatement(UPDATE_KEY_PAIR_SECRET);
             PreparedStatement removeDuplicate = conn.prepareStatement(REMOVE_DUPLICATE_KEY_PAIR);
             ResultSet legacyRows = legacyKeys.executeQuery()) {
            while (legacyRows.next()) {
                long userId = legacyRows.getLong(1);
                long domainId = legacyRows.getLong(2);
                long accountId = legacyRows.getLong(3);
                String apiKey = legacyRows.getString(4);
                String encryptedSecret = DBEncryptionUtil.encrypt(legacyRows.getString(5));

                List<Long> existingIds = findActiveKeyPairIds(activeKeyPairs, userId, apiKey);
                if (existingIds.isEmpty()) {
                    insertMigratedKeyPair(insertKeyPair, userId, domainId, accountId, apiKey, encryptedSecret);
                    continue;
                }

                updateMigratedSecret(updateSecret, existingIds.get(0), encryptedSecret);
                for (int index = 1; index < existingIds.size(); index++) {
                    removeDuplicate.setLong(1, existingIds.get(index));
                    removeDuplicate.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to migrate legacy user API key pairs with encrypted secrets.", e);
        }
    }

    protected boolean legacyApiKeyColumnsExist(Connection conn) {
        try (PreparedStatement statement = conn.prepareStatement(LEGACY_API_KEY_COLUMNS_QUERY);
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getInt(1) == 2;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to inspect legacy user API key columns.", e);
        }
    }

    protected List<Long> findActiveKeyPairIds(PreparedStatement statement, long userId, String apiKey) throws SQLException {
        statement.setLong(1, userId);
        statement.setString(2, apiKey);
        List<Long> ids = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ids.add(result.getLong(1));
            }
        }
        return ids;
    }

    protected void insertMigratedKeyPair(PreparedStatement statement, long userId, long domainId, long accountId,
                                         String apiKey, String encryptedSecret) throws SQLException {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setLong(2, userId);
        statement.setLong(3, domainId);
        statement.setLong(4, accountId);
        statement.setString(5, apiKey);
        statement.setString(6, encryptedSecret);
        statement.executeUpdate();
    }

    protected void updateMigratedSecret(PreparedStatement statement, long id, String encryptedSecret) throws SQLException {
        statement.setString(1, encryptedSecret);
        statement.setLong(2, id);
        statement.executeUpdate();
    }

    protected void unhideJsInterpretationEnabled(Connection conn) {
        String value = getJsInterpretationEnabled(conn);
        if (value != null) {
            updateJsInterpretationEnabledFields(conn, value);
        }
    }

    protected String getJsInterpretationEnabled(Connection conn) {
        String query = "SELECT value FROM cloud.configuration WHERE name = 'js.interpretation.enabled' AND category = 'Hidden';";

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
            logger.debug("Unable to retrieve value of hidden configuration 'js.interpretation.enabled'. The configuration may already be unhidden.");
            return null;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Error while retrieving value of hidden configuration 'js.interpretation.enabled'.", e);
        }
    }

    protected void updateJsInterpretationEnabledFields(Connection conn, String encryptedValue) {
        String query = "UPDATE cloud.configuration SET value = ?, category = 'System', component = 'JsInterpreter', is_dynamic = 1 WHERE name = 'js.interpretation.enabled';";

        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            String decryptedValue = DBEncryptionUtil.decrypt(encryptedValue);
            logger.info("Updating setting 'js.interpretation.enabled' to decrypted value [{}], category 'System', component 'JsInterpreter', and is_dynamic '1'.", decryptedValue);
            pstmt.setString(1, decryptedValue);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Error while unhiding configuration 'js.interpretation.enabled'.", e);
        } catch (CloudRuntimeException e) {
            logger.warn("Error while decrypting configuration 'js.interpretation.enabled'. The configuration may already be decrypted.");
        }
    }

    private void dropUsageVmInstanceIndex(Connection conn) {
        DbUpgradeUtils.dropIndexIfExists(conn, "cloud_usage", "usage_vm_instance", "vm_instance_id");
    }
}
