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
import java.sql.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
public class EuropaSharedFSCreationUpgradeTest {
    private List<String> run(Set<String> existing) throws Exception {
        Connection c=mock(Connection.class);Statement ddl=mock(Statement.class);List<String> statements=new ArrayList<>();
        when(c.createStatement()).thenReturn(ddl);when(ddl.execute(anyString())).thenAnswer(x->{statements.add(x.getArgument(0));return true;});
        when(c.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);PreparedStatement p=mock(PreparedStatement.class);final String[] name={null};
            doAnswer(x->{name[0]=x.getArgument(1);return null;}).when(p).setString(anyInt(),anyString());
            when(p.executeQuery()).thenAnswer(x->{
                ResultSet r=mock(ResultSet.class);when(r.next()).thenReturn(true,false);
                when(r.getInt(1)).thenReturn(existing.contains(sql.contains("statistics")?"index":name[0])?1:0);return r;});
            return p;
        });
        EuropaSharedFSCreationUpgrade.migrate(c);return statements;
    }
    @Test public void freshMigrationAddsOnlyTwoColumnsAndOneIndex() throws Exception {
        List<String> x=run(Set.of());assertEquals(3,x.size());assertTrue(x.get(0).contains("creation_request_id"));assertTrue(x.get(1).contains("creation_context"));assertTrue(x.get(2).contains("UNIQUE KEY"));
    }
    @Test public void completedMigrationIsReadOnly() throws Exception { assertTrue(run(Set.of("creation_request_id","creation_context","index")).isEmpty()); }
    @Test public void interruptedMigrationResumesMissingDdl() throws Exception { List<String>x=run(Set.of("creation_request_id"));assertEquals(2,x.size());assertFalse(x.stream().anyMatch(s->s.contains("ADD COLUMN creation_request_id"))); }
}
