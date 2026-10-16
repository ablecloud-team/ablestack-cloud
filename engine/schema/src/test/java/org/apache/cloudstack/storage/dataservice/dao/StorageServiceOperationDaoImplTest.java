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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class StorageServiceOperationDaoImplTest {
    private static class Query extends StorageServiceOperationDaoImpl {
        String sql;final PreparedStatement statement=Mockito.mock(PreparedStatement.class);
        final ResultSet result=Mockito.mock(ResultSet.class);
        Query()throws Exception {
            Mockito.when(statement.executeQuery()).thenReturn(result);
            Mockito.when(result.getBytes(Mockito.anyInt())).thenThrow(new AssertionError("No history body bytes"));
            Mockito.when(result.getBytes(Mockito.anyString())).thenThrow(new AssertionError("No history body bytes"));
            for(String body:java.util.List.of("previous_snapshot_json","snapshot_json","result_json"))
                Mockito.when(result.getString(body)).thenThrow(new AssertionError("No history body strings"));
        }
        @Override PreparedStatement historyStatement(String query) { sql=query;return statement; }
    }
    @Test public void admissionReadsOneScalarRowAndNoSnapshotColumns()throws Exception {
        Query q=new Query();Mockito.when(q.result.next()).thenReturn(true,false);
        Mockito.when(q.result.getLong(1)).thenReturn(150L);Mockito.when(q.result.getLong(2)).thenReturn(0L);Mockito.when(q.result.getLong(3)).thenReturn(149L);
        var state=q.readAdmission(72);Assert.assertEquals(150,state.getCommittedRevision());Assert.assertFalse(state.blocked());
        Assert.assertTrue(q.sql.contains("state='COMPLETE'"));Assert.assertTrue(q.sql.contains("state='RUNNING'"));Assert.assertTrue(q.sql.contains("state='RECOVERY_REQUIRED'"));
        Assert.assertFalse(q.sql.contains("COMPLETE_NO_CONFIG_CHANGE"));Assert.assertFalse(q.sql.contains("snapshot"));Assert.assertFalse(q.sql.contains("result_json"));
        Mockito.verify(q.statement).setLong(1,72L);Mockito.verify(q.statement).executeQuery();
    }
    @Test public void sqlFailureCannotBecomeIdle()throws Exception {
        Query q=new Query();Mockito.when(q.statement.executeQuery()).thenThrow(new SQLException("synthetic"));
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->q.readAdmission(71));
    }
    @Test public void missingOrAmbiguousScalarRowIsRejected()throws Exception {
        Query none=new Query();Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->none.readAdmission(71));
        Query two=new Query();Mockito.when(two.result.next()).thenReturn(true,true);
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->two.readAdmission(71));
    }
    @Test public void metadataUsesOnlyNamedPublicColumnsAndPreservesDates()throws Exception {
        Query q=new Query();Mockito.when(q.result.next()).thenReturn(true,false);
        Mockito.when(q.result.getLong("id")).thenReturn(9L);Mockito.when(q.result.getLong("instance_id")).thenReturn(71L);
        Mockito.when(q.result.getString("uuid")).thenReturn("operation");Mockito.when(q.result.getString("state")).thenReturn("COMPLETE");
        Mockito.when(q.result.getString("created")).thenReturn("1970-01-01 00:00:01");
        var rows=q.listSummariesByInstance(71);Assert.assertEquals(1,rows.size());Assert.assertEquals(9,rows.get(0).getId());
        Date date=rows.get(0).getCreated();date.setTime(0);Assert.assertEquals(1000,rows.get(0).getCreated().getTime());
        Assert.assertFalse(q.sql.contains("snapshot"));Assert.assertFalse(q.sql.contains("result_json"));Assert.assertTrue(q.sql.endsWith("ORDER BY created DESC,id DESC"));
        Mockito.verify(q.statement).setLong(1,71L);
    }
    @Test public void staleScanIsOnlyTwentyMetadataRowsWithSameCutoffAndOrder()throws Exception {
        Query q=new Query();Assert.assertTrue(q.listStaleRunningSummaries(new Date(1000)).isEmpty());
        Assert.assertTrue(q.sql.contains("state='RUNNING' AND heartbeat<=?"));Assert.assertTrue(q.sql.endsWith("ORDER BY heartbeat ASC,id ASC LIMIT 20"));
        Assert.assertFalse(q.sql.contains("snapshot"));Assert.assertFalse(q.sql.contains("result_json"));Mockito.verify(q.statement).setString(Mockito.eq(1),Mockito.anyString());
    }
    @Test public void newDaoQueriesUseTheExistingTransactionInterceptor()throws Exception {
        var builder=new com.cloud.utils.db.TransactionContextBuilder();
        for(String name:java.util.List.of("readAdmission","listSummariesByInstance"))
            Assert.assertTrue(builder.needToIntercept(StorageServiceOperationDaoImpl.class.getMethod(name,long.class)));
        Assert.assertTrue(builder.needToIntercept(StorageServiceOperationDaoImpl.class.getMethod("listStaleRunningSummaries",Date.class)));
    }

}
