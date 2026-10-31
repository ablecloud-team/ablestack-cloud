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
package org.apache.cloudstack.storage.datastore.db;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import java.sql.PreparedStatement;
import java.util.Date;
import org.junit.Test;
import com.cloud.utils.db.SqlGenerator;

public class ObjectStoreAccountCredentialVOTest {
    private static class Dao extends ObjectStoreAccountCredentialDaoImpl {
        void bindChecked(PreparedStatement statement, ObjectStoreAccountCredentialVO row) throws Exception {
            prepareAttribute(1, statement, new SqlGenerator(ObjectStoreAccountCredentialVO.class).findAttribute("checked_at"), row.getChecked());
        }
    }
    @Test public void verificationTimestampBindsToTheRealDaoPreparedStatement() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ObjectStoreAccountCredentialVO row = new ObjectStoreAccountCredentialVO(1, 2, "owner");
        row.setChecked(new Date());
        new Dao().bindChecked(statement, row);
        verify(statement).setString(eq(1), anyString());
    }
    @Test public void unverifiedTimestampBindsNull() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        new Dao().bindChecked(statement, new ObjectStoreAccountCredentialVO(1, 2, "owner"));
        verify(statement).setObject(1, null);
    }
}
