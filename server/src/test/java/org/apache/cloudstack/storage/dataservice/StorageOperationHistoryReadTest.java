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

import java.util.Date;
import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageOperationHistoryReadTest {
    private StorageServiceOperationDao operations() {
        StorageServiceOperationDao dao=Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(dao.listByInstance(Mockito.anyLong())).thenThrow(new AssertionError("History LOB loading is forbidden"));
        Mockito.when(dao.listStaleRunning(Mockito.any())).thenThrow(new AssertionError("Stale LOB loading is forbidden"));
        return dao;
    }
    private String execute(StorageServiceOperationDao dao, Long expected) {
        var user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(1L);
        CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));
        try {
            var snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
            Mockito.when(snapshots.capture(7L)).thenReturn("previous","current");
            Mockito.when(dao.persist(Mockito.any())).thenAnswer(call->call.getArgument(0));
            Mockito.when(dao.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
            var engine=new DesiredStateChange(dao,snapshots,id->new DesiredStateChange.WriterLock() {
                public boolean lock(int seconds) { Assert.assertEquals(120,seconds);return true; }
                public void unlock() { }
                public void releaseRef() { }
            });
            return engine.execute(7L,"nfs","unique",expected,String.class,()->"done",Mockito.mock(DesiredStateChange.Runtime.class));
        } finally {CallContext.unregister();}
    }
    @Test public void unrelatedHistoricalBodiesAreNotLoadedBeforeNewChange() {
        StorageServiceOperationDao dao=operations();Mockito.when(dao.readAdmission(7L)).thenReturn(new StorageServiceOperationDao.Admission(60,0,59L));
        Assert.assertEquals("done",execute(dao,60L));Mockito.verify(dao,Mockito.never()).listByInstance(Mockito.anyLong());
        Mockito.verify(dao).readAdmission(7L);
    }
    @Test public void runningAnyRevisionAndNewRecoveryStillBlockWithoutHistoryBodies() {
        for(StorageServiceOperationDao.Admission state:List.of(new StorageServiceOperationDao.Admission(60,1,1L),new StorageServiceOperationDao.Admission(60,0,61L))) {
            StorageServiceOperationDao dao=operations();Mockito.when(dao.readAdmission(7L)).thenReturn(state);
            Assert.assertThrows(CloudRuntimeException.class,()->execute(dao,60L));Mockito.verify(dao,Mockito.never()).persist(Mockito.any());
        }
    }
    @Test public void sameRevisionRecoveryIsSupersededButExpectedRevisionIsStillExact() {
        StorageServiceOperationDao dao=operations();Mockito.when(dao.readAdmission(7L)).thenReturn(new StorageServiceOperationDao.Admission(60,0,60L));
        Assert.assertEquals("done",execute(dao,60L));
        StorageServiceOperationDao changed=operations();Mockito.when(changed.readAdmission(7L)).thenReturn(new StorageServiceOperationDao.Admission(61,0,null));
        Assert.assertThrows(CloudRuntimeException.class,()->execute(changed,60L));Mockito.verify(changed,Mockito.never()).persist(Mockito.any());
    }
    @Test public void statusApiReturnsAllMetadataWithoutAnyHistoryBodies() {
        var instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn("instance");
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl() {
            @Override protected StorageServiceInstanceVO requireInstance(Long id) { Assert.assertEquals(Long.valueOf(7),id);return instance; }
        };
        StorageServiceOperationDao dao=operations();ReflectionTestUtils.setField(manager,"storageOperationDao",dao);
        java.util.ArrayList<StorageServiceOperationSummary> rows=new java.util.ArrayList<>();
        for(int i=0;i<1000;i++)rows.add(new StorageServiceOperationSummary(i,"operation-"+i,7,"request","nfs","COMPLETE","COMPLETE",i,100,1,new Date(1000-i),new Date(2000),new Date(3000),"closed"));
        Mockito.when(dao.listSummariesByInstance(7L)).thenReturn(rows);
        var cmd=Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceOperationsCmd.class);Mockito.when(cmd.getInstanceId()).thenReturn(7L);
        var response=manager.listStorageServiceOperations(cmd);
        Assert.assertEquals(Integer.valueOf(1000),response.getCount());Assert.assertEquals(1000,response.getResponses().size());
        Assert.assertEquals("operation-0",ReflectionTestUtils.getField(response.getResponses().get(0),"id"));
        Mockito.verify(dao,Mockito.never()).listByInstance(Mockito.anyLong());Mockito.verify(dao,Mockito.never()).findById(Mockito.anyLong());
    }
    private StorageServiceOperationVO authority(long id,long revision) {
        var row=new StorageServiceOperationVO();ReflectionTestUtils.setField(row,"id",id);
        row.setInstanceId(7);row.setRevision(revision);row.setState("COMPLETE");row.setPhase("COMPLETE");row.setAction("nfs");
        row.setPreviousSnapshotJson("{\"original\":true}");row.setSnapshotJson("{\"target\":true}");row.setResultJson("{\"_response\":{}}");
        return row;
    }
    @Test public void selectedRecoveryLoadsOnlyItsFullAuthorityAndKeepsAllBodies()throws Exception {
        StorageServiceOperationDao dao=operations();StorageServiceOperationVO latest=authority(5,100);
        Mockito.when(dao.listSummariesByInstance(7L)).thenReturn(List.of(StorageServiceOperationSummary.from(authority(4,99)),StorageServiceOperationSummary.from(latest)));
        Mockito.when(dao.findById(5L)).thenReturn(latest);
        var manager=new StorageServiceManagerImpl();ReflectionTestUtils.setField(manager,"storageOperationDao",dao);
        var method=StorageServiceManagerImpl.class.getDeclaredMethod("latestCompleteHistory",long.class);method.setAccessible(true);
        List<?> rows=(List<?>)method.invoke(manager,7L);Assert.assertSame(latest,rows.get(0));
        Assert.assertNotNull(((StorageServiceOperationVO)rows.get(0)).getPreviousSnapshotJson());
        Assert.assertNotNull(((StorageServiceOperationVO)rows.get(0)).getSnapshotJson());
        Assert.assertNotNull(((StorageServiceOperationVO)rows.get(0)).getResultJson());
        Mockito.verify(dao).findById(5L);Mockito.verify(dao,Mockito.never()).findById(4L);
    }
    @Test public void selectedAuthorityScopeStatePhaseRevisionOwnerAndUuidDriftAreRejected() {
        for(String field:List.of("id","instanceId","state","phase","revision","createdBy","uuid","requestKey","action")) {
            StorageServiceOperationVO row=authority(5,100);StorageServiceOperationSummary summary=StorageServiceOperationSummary.from(row);
            Object value=field.equals("id")||field.equals("instanceId")||field.equals("revision")||field.equals("createdBy")?999L:"changed";
            ReflectionTestUtils.setField(row,field,value);StorageServiceOperationDao dao=operations();Mockito.when(dao.findById(5L)).thenReturn(row);
            Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceOperationSummary.requireFull(dao,summary));
        }
        StorageServiceOperationDao missing=operations();
        Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceOperationSummary.requireFull(missing,StorageServiceOperationSummary.from(authority(5,100))));
    }
    @Test public void summaryNeverReadsOrExposesSnapshotAndResponseBodies()throws Exception {
        StorageServiceOperationVO row=Mockito.mock(StorageServiceOperationVO.class);
        Mockito.when(row.getPreviousSnapshotJson()).thenThrow(new AssertionError("private SOURCE body"));
        Mockito.when(row.getSnapshotJson()).thenThrow(new AssertionError("private TARGET body"));
        Mockito.when(row.getResultJson()).thenThrow(new AssertionError("private response body"));
        StorageServiceOperationSummary.from(row);
        for(String method:List.of("getPreviousSnapshotJson","getSnapshotJson","getResultJson"))
            Assert.assertThrows(NoSuchMethodException.class,()->StorageServiceOperationSummary.class.getMethod(method));
        Mockito.verify(row,Mockito.never()).getPreviousSnapshotJson();Mockito.verify(row,Mockito.never()).getSnapshotJson();Mockito.verify(row,Mockito.never()).getResultJson();
    }
    @Test public void missingAndForeignMetadataCannotBeTreatedAsEmptyHistory() {
        Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceOperationSummary.requireScope(null,7));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceOperationSummary.requireScope(List.of(StorageServiceOperationSummary.from(authority(5,100))),8));
    }
    @Test public void staleScannerRechecksFullRowUnderOriginalWriterLock() {
        Object previousDepot=ReflectionTestUtils.getField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot");
        var depot=Mockito.mock(org.apache.cloudstack.framework.config.impl.ConfigDepotImpl.class);
        Mockito.when(depot.getConfigStringValue(Mockito.eq(StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.key()),Mockito.any(),Mockito.isNull())).thenReturn("true");
        ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",depot);
        StorageServiceOperationVO stale=authority(5,100);stale.setState("RUNNING");stale.setHeartbeat(new Date(1));
        StorageServiceOperationDao dao=operations();Mockito.when(dao.listStaleRunningSummaries(Mockito.any())).thenReturn(List.of(StorageServiceOperationSummary.from(stale)));
        Mockito.when(dao.findById(5L)).thenReturn(stale);
        var instances=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
        var instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instances.findById(7L)).thenReturn(instance);
        java.util.ArrayList<StorageServiceOperationVO> recovered=new java.util.ArrayList<>();
        var manager=new StorageServiceManagerImpl() {
            @Override protected void recoverInterruptedStorageWriter(StorageServiceInstanceVO current,StorageServiceOperationVO operation) { recovered.add(operation); }
        };
        ReflectionTestUtils.setField(manager,"storageOperationDao",dao);ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        var lock=Mockito.mock(com.cloud.utils.db.GlobalLock.class);Mockito.when(lock.lock(1)).thenReturn(true);
        try(var locks=Mockito.mockStatic(com.cloud.utils.db.GlobalLock.class)) {
            locks.when(()->com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);
            manager.recoverStaleStorageWriters();Assert.assertEquals(List.of(stale),recovered);
            recovered.clear();stale.setRevision(101);
            manager.recoverStaleStorageWriters();Assert.assertTrue(recovered.isEmpty());
            Mockito.verify(dao,Mockito.never()).listStaleRunning(Mockito.any());
            Mockito.verify(lock,Mockito.times(2)).unlock();Mockito.verify(lock,Mockito.times(2)).releaseRef();
        } finally {ReflectionTestUtils.setField(org.apache.cloudstack.framework.config.ConfigKey.class,"s_depot",previousDepot);}
    }

    private void completedReadOnlyFeatures(boolean retainedLease) {
        var instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        var manager=new StorageServiceManagerImpl() {
            @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
            @Override protected com.google.gson.JsonObject instanceControlPolicy(StorageServiceInstanceVO current) {
                var policy=new com.google.gson.JsonObject();policy.addProperty("enabled",false);return policy;
            }
        };
        StorageServiceOperationDao dao=operations();
        java.util.ArrayList<StorageServiceOperationSummary> summaries=new java.util.ArrayList<>();
        for(int i=1;i<=1000;i++) {
            StorageServiceOperationVO row=authority(i,i);row.setState("COMPLETE_NO_CONFIG_CHANGE");row.setAction("CONFIGURATION_BACKUP_READ_ONLY");
            summaries.add(StorageServiceOperationSummary.from(row));
        }
        Mockito.when(dao.listSummariesByInstance(7L)).thenReturn(summaries);
        Mockito.when(dao.findById(Mockito.anyLong())).thenThrow(new AssertionError("Completed BACKUP body must not be reloaded"));
        ReflectionTestUtils.setField(manager,"storageOperationDao",dao);
        var controls=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class);
        ReflectionTestUtils.setField(manager,"storageOperationControlDao",controls);
        if(retainedLease) {
            var control=new StorageServiceOperationControlVO();control.setInstanceId(7);control.setOperationId(5);
            control.setLeaseJson("{\"reservationAcquired\":true}");Mockito.when(controls.findByOperation(5L)).thenReturn(control);
        }
        Assert.assertEquals(retainedLease?java.util.Set.of("LOGICAL_RESOURCE_RESERVATION"):java.util.Set.of(),manager.requiredManagedOperationFeatures(7));
        Mockito.verify(dao,Mockito.never()).findById(Mockito.anyLong());
    }
    @Test public void completedReadOnlyBackupHistoryUsesOnlyMetadata() { completedReadOnlyFeatures(false); }
    @Test public void completedReadOnlyBackupRetainedLeaseStillRequiresReservationFeature() { completedReadOnlyFeatures(true); }

}
