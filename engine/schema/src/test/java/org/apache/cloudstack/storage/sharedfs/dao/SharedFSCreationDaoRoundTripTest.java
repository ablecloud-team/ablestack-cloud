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
package org.apache.cloudstack.storage.sharedfs.dao;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext;
import org.apache.cloudstack.api.response.SharedFSResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.*;
import static org.junit.Assert.*;

/** Opt-in integration test. Use the dedicated loopback MariaDB described in docs;
 * persist, update and all reads use the real SharedFSDao/GenericDao implementation. */
public class SharedFSCreationDaoRoundTripTest {
    private DataSource previous;
    private Field dataSourceField;
    private TransactionLegacy transaction;
    private SharedFSDaoImpl dao;
    private MysqlDataSource database;

    @Before public void openDatabase() throws Exception {
        String url = System.getProperty("sharedfs.dao.test.url");
        Assume.assumeTrue("Dedicated local MariaDB URL is required", url != null);
        assertTrue("Only the disposable loopback test database is allowed",
                url.startsWith("jdbc:mysql://127.0.0.1:13342/sharedfs1442_dao_test?"));
        database = new MysqlDataSource();
        database.setURL(url); database.setUser("root");
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS shared_filesystem (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "uuid VARCHAR(40),name VARCHAR(255),description VARCHAR(255),domain_id BIGINT,account_id BIGINT,"
                + "data_center_id BIGINT,state VARCHAR(32),fs_provider_name VARCHAR(64),protocol VARCHAR(16),"
                + "volume_id BIGINT NULL,vm_id BIGINT NULL,fs_type VARCHAR(10),service_offering_id BIGINT,"
                + "network_mode VARCHAR(16),ip_address VARCHAR(255),cidr VARCHAR(255),gateway VARCHAR(255),"
                + "dns1 VARCHAR(255),dns2 VARCHAR(255),updated DATETIME,update_count BIGINT NOT NULL DEFAULT 0,"
                + "created DATETIME,removed DATETIME,creation_request_id VARCHAR(36),creation_context TEXT NULL,"
                + "UNIQUE KEY uk_sharedfs_creation_owner(account_id,creation_request_id)) CHARACTER SET utf8mb4");
            s.execute("DELETE FROM shared_filesystem");
        }
        dataSourceField = TransactionLegacy.class.getDeclaredField("s_ds");
        dataSourceField.setAccessible(true); previous = (DataSource)dataSourceField.get(null);
        dataSourceField.set(null, database);
        transaction = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        dao = new SharedFSDaoImpl();
    }

    @After public void closeDatabase() throws Exception {
        if (transaction != null) transaction.close();
        if (dataSourceField != null) dataSourceField.set(null, previous);
    }

    private SharedFSVO resource(String format) {
        SharedFSVO fs = new SharedFSVO("dao-" + format, "isolated regression", 1, 2, 3,
                "SHAREDFSVM", SharedFS.Protocol.NFS, SharedFS.FileSystemType.valueOf(format), 4L);
        fs.setCreationRequestId(java.util.UUID.randomUUID().toString());
        return fs;
    }
    private SharedFSCreationContext context(SharedFSVO fs, String format) {
        SharedFSCreationContext c = new SharedFSCreationContext();
        c.existing = true; c.volumeId = 10L; c.networkId = 20L;
        c.originalPath = "source-path"; c.originalPoolId = 30L; c.originalSize = 21474836480L;
        c.expectedFilesystem = "AUTO"; c.importMode = "MOUNT_EXISTING";
        c.planVm(fs.getUuid()); c.observedFilesystem = format.toLowerCase();
        c.filesystemUuid = java.util.UUID.randomUUID().toString();
        c.mountPath = "/srv/ablestack-storage/volumes/" + "보존".repeat(180);
        c.phase = "INSPECTED";
        return c;
    }
    private void roundTrip(String format) {
        SharedFSVO fs = resource(format); SharedFSCreationContext c = context(fs, format);
        String initial = c.json(); assertTrue(initial.length() > 255);
        fs.setCreationContext(initial);
        SharedFSVO saved = dao.persist(fs);
        SharedFSVO read = dao.findById(saved.getId());
        assertNotSame(fs, read); assertEquals(initial, read.getCreationContext());
        assertEquals(c.filesystemUuid, SharedFSCreationContext.read(read.getCreationContext()).filesystemUuid);
        c.phase = "READY"; c.lastJobUuid = java.util.UUID.randomUUID().toString();
        read.setCreationContext(c.json());
        assertTrue(dao.update(read.getId(), read));
        SharedFSVO updated = dao.findById(read.getId());
        assertEquals(c.json(), updated.getCreationContext());
        assertEquals("READY", SharedFSCreationContext.read(updated.getCreationContext()).phase);
        assertEquals(saved.getId(), dao.findByCreationRequest(2L, fs.getCreationRequestId()).getId());
    }
    @Test public void xfsPersistsAndUpdatesWithoutTruncation() { roundTrip("XFS"); }
    @Test public void ext4PersistsAndUpdatesWithoutTruncation() { roundTrip("EXT4"); }

    @Test public void utf8TextByteBoundaryRoundTripsExactly() {
        SharedFSVO fs = resource("EXT4"); SharedFSCreationContext c = context(fs, "EXT4");
        c.mountPath = "";
        int room = SharedFSCreationContext.MAX_JSON_BYTES - c.json().getBytes(StandardCharsets.UTF_8).length;
        c.mountPath = "한".repeat(room / 3) + "x".repeat(room % 3);
        String value = c.json(); assertEquals(65535, value.getBytes(StandardCharsets.UTF_8).length);
        fs.setCreationContext(value); SharedFSVO saved = dao.persist(fs);
        assertEquals(value, dao.findById(saved.getId()).getCreationContext());
    }

    @Test public void corruptRowDoesNotBlockOtherListResponses() throws Exception {
        SharedFSVO bad = resource("XFS"); bad.setCreationContext(context(bad, "XFS").json());
        long id = dao.persist(bad).getId();
        SharedFSVO good = resource("EXT4"); String goodContext = context(good, "EXT4").json();
        good.setCreationContext(goodContext); dao.persist(good);
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("UPDATE shared_filesystem SET creation_context=LEFT(creation_context,255) WHERE id=" + id));
        }
        assertEquals(2, dao.listAll().size());
        int corrupt = 0;
        for (SharedFSVO fs : dao.listAll()) {
            SharedFSResponse response = new SharedFSResponse(); response.setCreation(fs);
            JsonObject json = new Gson().toJsonTree(response).getAsJsonObject();
            if (fs.getId() == id) {
                assertEquals("CORRUPT", json.get("creationstage").getAsString());
                assertEquals(SharedFSCreationContext.INVALID_JOURNAL, json.get("creationerrorcode").getAsString());
                corrupt++;
            } else {
                assertEquals(goodContext, fs.getCreationContext());
                assertEquals("INSPECTED", json.get("creationstage").getAsString());
            }
        }
        assertEquals(1, corrupt);
    }
}
