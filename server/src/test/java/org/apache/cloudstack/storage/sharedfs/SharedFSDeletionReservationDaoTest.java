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
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.sql.*;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.upgrade.dao.EuropaSharedFSDeletionUpgrade;
import com.google.gson.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.command.user.storage.sharedfs.DestroySharedFSCmd;
import org.apache.cloudstack.storage.sharedfs.dao.*;
import org.apache.cloudstack.storage.dataservice.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
public class SharedFSDeletionReservationDaoTest {
    SharedFSDeletionImpactTest fixture;SharedFSServiceImpl service;SharedFSDeletionOperationDaoImpl operations;StorageServiceInstanceDaoImpl instances;StorageServiceInstanceVO instance;
    Field source;DataSource previous;TransactionLegacy transaction;
    @Before public void open()throws Exception {
        String url=System.getProperty("configuration.dao.test.url");Assume.assumeTrue(url!=null);assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        fixture=new SharedFSDeletionImpactTest();fixture.open();when(fixture.fs.getState()).thenReturn(SharedFS.State.Ready);
        MysqlDataSource ds=new MysqlDataSource();ds.setURL(url);ds.setUser("root");
        try(Connection c=ds.getConnection()){EuropaSharedFSDeletionUpgrade.migrate(c);Statement s=c.createStatement();s.execute("DELETE FROM sharedfs_deletion_operation");s.execute("DELETE FROM storage_service_instance WHERE name='deletion-reservation-test'");}
        source=TransactionLegacy.class.getDeclaredField("s_ds");source.setAccessible(true);previous=(DataSource)source.get(null);source.set(null,ds);transaction=TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        instances=new StorageServiceInstanceDaoImpl();operations=new SharedFSDeletionOperationDaoImpl();
        instance=new StorageServiceInstanceVO("deletion-reservation-test",null,1,1,13,1L,"SHAREDFS_COMPATIBILITY");instance.setVmId(7L);instance=instances.persist(instance);
        ReflectionTestUtils.setField(fixture.service,"storageServiceInstanceDao",instances);
        ReflectionTestUtils.setField(fixture.service,"deletionDao",operations);
        ReflectionTestUtils.setField(fixture.service,"deletionUpgradeDao",mock(StorageServiceRuntimeUpgradeDao.class));
        ReflectionTestUtils.setField(fixture.service,"deletionIdentityDao",mock(StorageIdentityDomainDao.class));
        SharedFSDao fsDao=(SharedFSDao)ReflectionTestUtils.getField(fixture.service,"sharedFSDao");when(fsDao.findById(9L)).thenReturn(fixture.fs);when(fsDao.lockRow(9L,true)).thenReturn(fixture.fs);
        service=spy(fixture.service);doAnswer(x->SharedFSDeletionCoordinator.response(x.getArgument(0))).when(service).executeDeletion(any(),anyBoolean());
    }
    @After public void close()throws Exception {if(transaction!=null)transaction.close();if(source!=null)source.set(null,previous);if(fixture!=null)fixture.close();}
    DestroySharedFSCmd command(String id){
        DestroySharedFSCmd c=new DestroySharedFSCmd();ReflectionTestUtils.setField(c,"id",9L);ReflectionTestUtils.setField(c,"forced",true);ReflectionTestUtils.setField(c,"expunge",true);ReflectionTestUtils.setField(c,"requestId",id);return c;
    }
    @Test public void defaultPreserveAtomicallyStoresPlanAndLifecycleReservation(){
        JsonObject r=service.deleteSharedFS(command("request-one"),true);SharedFSDeletionOperationVO stored=operations.findByUuid(r.get("operationid").getAsString());
        assertEquals("PRESERVE",JsonParser.parseString(stored.getPlanJson()).getAsJsonObject().get("policy").getAsString());
        assertEquals(stored.getUuid(),instances.findById(instance.getId()).getActiveLifecycleOperationUuid());
        for(JsonElement e:JsonParser.parseString(stored.getPlanJson()).getAsJsonObject().getAsJsonArray("volumes"))assertFalse(e.getAsJsonObject().get("delete").getAsBoolean());
        verify(fixture.volumes,never()).remove(anyLong());
    }
    @Test public void duplicateRequestDoesNotCreateAnotherPlan(){
        DestroySharedFSCmd c=command("same");JsonObject a=service.deleteSharedFS(c,true),b=service.deleteSharedFS(c,true);
        assertEquals(a.get("operationid"),b.get("operationid"));assertEquals(1,operations.listBySharedFs(9).size());
    }
    @Test public void changedApprovedFingerprintRejectsBeforeJournalAndVmAction(){
        String fp=service.getDeletionImpact("fs").get("fingerprint").getAsString();when(fixture.byId.get(2L).getSize()).thenReturn(2049L);
        DestroySharedFSCmd c=command("stale");ReflectionTestUtils.setField(c,"planFingerprint",fp);ReflectionTestUtils.setField(c,"confirmResource","preserve");
        try{service.deleteSharedFS(c,true);fail("stale approval rejected");}catch(com.cloud.utils.exception.CloudRuntimeException e){assertTrue(e.getMessage().contains("DELETION_PLAN_STALE"));}
        assertTrue(operations.listBySharedFs(9).isEmpty());assertNull(instances.findById(instance.getId()).getActiveLifecycleOperationUuid());
    }
    @Test public void existingConfigurationReservationBlocksDeletion(){
        instance.setActiveConfigurationOperationId(44L);instances.update(instance.getId(),instance);
        try{service.deleteSharedFS(command("busy"),true);fail("configuration recovery blocks lifecycle");}catch(com.cloud.utils.exception.CloudRuntimeException e){assertTrue(e.getMessage().contains("CONFIGURATION_RECOVERY_REQUIRED"));}
        assertTrue(operations.listBySharedFs(9).isEmpty());
    }
    @Test public void rootCannotEnterSelectedDataDeleteSet(){
        DestroySharedFSCmd c=command("root");ReflectionTestUtils.setField(c,"volumePolicy","DELETE_SELECTED");ReflectionTestUtils.setField(c,"deleteVolumeIds",java.util.List.of(1L));ReflectionTestUtils.setField(c,"confirmDataLoss",true);ReflectionTestUtils.setField(c,"planFingerprint",service.getDeletionImpact("fs").get("fingerprint").getAsString());ReflectionTestUtils.setField(c,"confirmResource","preserve");
        try{service.deleteSharedFS(c,true);fail("ROOT must not be selected DATA");}catch(com.cloud.exception.InvalidParameterValueException expected){}
        assertTrue(operations.listBySharedFs(9).isEmpty());
    }
    @Test public void interruptedRecoverableDestroyResumesWithItsOriginalNonExpungeIntent(){
        DestroySharedFSCmd cmd=command("soft-pending");ReflectionTestUtils.setField(cmd,"expunge",false);JsonObject result=service.deleteSharedFS(cmd,false);
        SharedFSDeletionOperationVO op=operations.findByUuid(result.get("operationid").getAsString());op.setState("RECOVERY_REQUIRED");operations.update(op.getId(),op);
        service.resumeDeletion(op.getUuid(),op.getRequestId());verify(service,times(2)).executeDeletion(any(),eq(false));
    }

    @Test public void activeInitialPreparationBlocksBeforeAnyDeletionPlan(){
        when(fixture.fs.getCreationContext()).thenReturn("{\"phase\":\"FORMATTING\"}");
        try{service.deleteSharedFS(command("preparation"),true);fail("active formatter must block deletion");}catch(com.cloud.utils.exception.CloudRuntimeException expected){assertTrue(expected.getMessage().contains("VOLUME_PREPARATION_ACTIVE"));}
        assertTrue(operations.listBySharedFs(9).isEmpty());
    }

}
