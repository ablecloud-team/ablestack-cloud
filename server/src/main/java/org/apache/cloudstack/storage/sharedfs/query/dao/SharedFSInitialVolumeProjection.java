// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.sharedfs.query.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

/** One page-scoped metadata query; no per-row lookup and no guest command. */
public final class SharedFSInitialVolumeProjection {
    private SharedFSInitialVolumeProjection() { }
    public static final class Metadata {
        public final String mode;
        public final String state;
        private Metadata(String mode,String state) { this.mode=mode;this.state=state; }
    }
    public static Map<Long,Metadata> load(Long[] ids) {
        Map<Long,Metadata> result=new HashMap<>();
        if (ids.length==0) return result;
        String slots=String.join(",",Collections.nCopies(ids.length,"?"));
        try (PreparedStatement statement=TransactionLegacy.currentTxn().prepareAutoCloseStatement("SELECT id,COALESCE(backing_volume_mode,'NEW'),initial_import_state FROM shared_filesystem WHERE id IN ("+slots+")")) {
            for (int i=0;i<ids.length;i++) statement.setLong(i+1,ids[i]);
            try (ResultSet rows=statement.executeQuery()) {
                while (rows.next()) result.put(rows.getLong(1),new Metadata(rows.getString(2),rows.getString(3)));
            }
        } catch (SQLException failure) { throw new CloudRuntimeException("Unable to read SharedFS initial-volume metadata",failure); }
        return result;
    }
}
