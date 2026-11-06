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
package org.apache.cloudstack.storage.dataservice.dao;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.upgrade.dao.EuropaStorageConfigurationUpgrade;
import org.apache.cloudstack.storage.dataservice.StorageServiceConfigurationOperationVO;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Real GenericDao/MariaDB round trips; only the dedicated loopback database is allowed. */
public class StorageConfigurationDaoRoundTripTest {
    private Field field;
    private DataSource previous;
    private TransactionLegacy transaction;
    private StorageServiceConfigurationOperationDaoImpl dao;
    @Before public void open() throws Exception {
        String url = System.getProperty("configuration.dao.test.url");
        Assume.assumeTrue("Dedicated local database required", url != null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds = new MysqlDataSource(); ds.setURL(url); ds.setUser("root");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS storage_service_instance(id BIGINT UNSIGNED NOT NULL PRIMARY KEY)");
            EuropaStorageConfigurationUpgrade.migrate(c); EuropaStorageConfigurationUpgrade.migrate(c);
            s.execute("DELETE FROM storage_service_configuration_operation");
        }
        field = TransactionLegacy.class.getDeclaredField("s_ds"); field.setAccessible(true);
        previous = (DataSource)field.get(null); field.set(null, ds);
        transaction = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        dao = new StorageServiceConfigurationOperationDaoImpl();
    }
    @After public void close() throws Exception {
        if (transaction != null) transaction.close();
        if (field != null) field.set(null, previous);
    }
    private StorageServiceConfigurationOperationVO value() {
        StorageServiceConfigurationOperationVO v = new StorageServiceConfigurationOperationVO();
        v.setInstanceId(1); v.setAccountId(2); v.setDomainId(1);
        v.setRequestId(UUID.randomUUID().toString()); v.setRequestHash("a".repeat(64));
        v.setProtocol("NFS"); v.setBaseRevision(0); v.setTargetRevision(1);
        v.setState("RUNNING"); v.setPhase("APPLYING");
        return v;
    }
    @Test public void largeUtf8SnapshotsAndFirstFailureSurviveActualDaoSaveUpdateReload() {
        StorageServiceConfigurationOperationVO v = value();
        String large = "{\"한글\":\"" + "보존😀".repeat(50000) + "\"}";
        v.setBeforeSnapshot(large); v.setCandidateSnapshot(large);
        v = dao.persist(v);
        StorageServiceConfigurationOperationVO read = dao.findById(v.getId());
        assertEquals(large, read.getBeforeSnapshot());
        assertEquals(large, dao.findByRequest(1, v.getRequestId()).getCandidateSnapshot());
        StorageServiceConfigurationOperationVO update = dao.createForUpdate(v.getId());
        update.setState("RECOVERY_REQUIRED"); update.setPhase("OBSERVING");
        update.setOriginalFailureJson("{\"code\":\"FIRST\"}");
        update.setRollbackResultJson("{\"state\":\"FAILED\",\"code\":\"SECOND\"}");
        assertTrue(dao.update(v.getId(), update));
        read = dao.findById(v.getId());
        assertEquals(large, read.getCandidateSnapshot());
        assertTrue(read.getOriginalFailureJson().contains("FIRST"));
        assertTrue(read.getRollbackResultJson().contains("SECOND"));
    }
    @Test public void uniqueRequestAndCorruptSnapshotAreIsolatedOnRead() {
        StorageServiceConfigurationOperationVO good = dao.persist(value());
        StorageServiceConfigurationOperationVO corrupt = value(); corrupt.setCandidateSnapshot("{truncated");
        corrupt = dao.persist(corrupt);
        assertEquals(2, dao.listByInstance(1).size());
        assertEquals("{truncated", dao.findById(corrupt.getId()).getCandidateSnapshot());
        StorageServiceConfigurationOperationVO duplicate = value(); duplicate.setRequestId(good.getRequestId());
        try { dao.persist(duplicate); fail("duplicate request must be rejected by MariaDB"); }
        catch (RuntimeException expected) { }
        assertEquals(good.getUuid(), dao.findByRequest(1, good.getRequestId()).getUuid());
        assertEquals(2, dao.listByInstance(1).size());
    }
    @Test public void nfsIdentityPolicyAndOtherListenerFieldsSurviveRealDaoRoundTrip() {
        StorageServiceProtocolDaoImpl protocols = new StorageServiceProtocolDaoImpl();
        org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO row =
                new org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO(1,
                    org.apache.cloudstack.storage.dataservice.StorageServiceInstance.Protocol.NFS, true, "10.0.0.1", 2049);
        String candidate = "{\"protocolMode\":\"V4_ONLY\",\"listenerGroups\":[{\"port\":2050}],\"nfs4IdentityMappingMode\":\"NUMERIC\",\"nfs4IdentityPolicyRevision\":13}";
        row.setConfigJson(candidate);
        row = protocols.persist(row);
        org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO saved = protocols.findById(row.getId());
        assertEquals(candidate, saved.getConfigJson());
        assertEquals(Integer.valueOf(2049), saved.getPort());
        org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO update = protocols.createForUpdate(row.getId());
        String before = candidate.replace("NUMERIC", "NAME_DOMAIN").replace(":13}", ":7}");
        update.setConfigJson(before); assertTrue(protocols.update(row.getId(), update));
        saved = protocols.findById(row.getId());
        assertEquals(before, saved.getConfigJson()); assertEquals(row.getUuid(), saved.getUuid());
        assertEquals("10.0.0.1", saved.getListenIp()); assertTrue(saved.isEnabled());
    }

}
