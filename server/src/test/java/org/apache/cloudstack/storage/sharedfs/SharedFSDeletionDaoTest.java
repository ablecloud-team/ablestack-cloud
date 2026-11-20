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
package org.apache.cloudstack.storage.sharedfs;
import com.cloud.utils.db.TransactionLegacy;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDeletionOperationDaoImpl;
import com.cloud.upgrade.dao.EuropaSharedFSDeletionUpgrade;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.lang.reflect.Field;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
public class SharedFSDeletionDaoTest {
    private Field source;private DataSource previous;private TransactionLegacy transaction;private SharedFSDeletionOperationDaoImpl dao;
    @Before public void open()throws Exception{
        String url=System.getProperty("configuration.dao.test.url");Assume.assumeTrue(url!=null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds=new MysqlDataSource();ds.setURL(url);ds.setUser("root");
        try(Connection c=ds.getConnection()){EuropaSharedFSDeletionUpgrade.migrate(c);EuropaSharedFSDeletionUpgrade.migrate(c);c.createStatement().execute("DELETE FROM sharedfs_deletion_operation");}
        source=TransactionLegacy.class.getDeclaredField("s_ds");source.setAccessible(true);previous=(DataSource)source.get(null);source.set(null,ds);
        transaction=TransactionLegacy.open(TransactionLegacy.CLOUD_DB);dao=new SharedFSDeletionOperationDaoImpl();
    }
    @After public void close()throws Exception{if(transaction!=null)transaction.close();if(source!=null)source.set(null,previous);}
    @Test public void actualCoordinatorDaoFailureReloadResumeAndCompletedReplay(){
        SharedFSDeletionCoordinatorTest fixture=new SharedFSDeletionCoordinatorTest();fixture.open();
        JsonObject plan=JsonParser.parseString("{\"vmuuid\":\"vm-a\",\"volumes\":[{\"id\":\"1\",\"uuid\":\"data-a\",\"type\":\"DATADISK\",\"accountid\":\"2\",\"zoneid\":\"1\",\"size\":\"1024\",\"poolid\":\"1\",\"delete\":false},{\"id\":\"2\",\"uuid\":\"data-b\",\"type\":\"DATADISK\",\"accountid\":\"2\",\"zoneid\":\"1\",\"size\":\"2048\",\"poolid\":\"1\",\"delete\":false}]}").getAsJsonObject();
        String metadata="권한보존 ".repeat(12000);plan.addProperty("boundedMetadata",metadata);
        SharedFSDeletionOperationVO op=new SharedFSDeletionOperationVO();op.setSharedFsId(1);op.setSharedFsUuid("fs-a");op.setAccountId(2);op.setDomainId(1);op.setRequestId("request-a");op.setRequestHash("a".repeat(64));op.setPlanFingerprint("b".repeat(64));op.setPlanJson(plan.toString());op.setState("PLANNED");op.setPhase("PLAN_VALIDATED");op=dao.persist(op);
        SharedFSDeletionCoordinatorTest.Fake port=new SharedFSDeletionCoordinatorTest.Fake(plan);port.fail="detach:data-b";
        SharedFSDeletionCoordinator coordinator=new SharedFSDeletionCoordinator(port,x->dao.update(x.getId(),x));coordinator.execute(op,true);
        SharedFSDeletionOperationVO reloaded=dao.findByRequest(1,"request-a");assertEquals("RECOVERY_REQUIRED",reloaded.getState());assertEquals(metadata,JsonParser.parseString(reloaded.getPlanJson()).getAsJsonObject().get("boundedMetadata").getAsString());
        assertTrue(reloaded.getResultJson().contains("DETACHED_PRESERVED"));String original=reloaded.getOriginalFailureJson();
        port.fail=null;port.calls.clear();coordinator.execute(reloaded,true);reloaded=dao.findByUuid(op.getUuid());assertEquals("COMPLETED",reloaded.getState());assertEquals(original,reloaded.getOriginalFailureJson());assertFalse(port.calls.contains("detach:data-a"));assertFalse(port.calls.stream().anyMatch(x->x.startsWith("delete:")));
        port.calls.clear();coordinator.execute(reloaded,true);assertTrue(port.calls.isEmpty());assertEquals(1,dao.listBySharedFs(1).size());
    }
}
