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

package org.apache.cloudstack.storage.dataservice;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class StorageServiceDesiredSnapshotValidationTest {
    private JsonObject snapshot() {
        JsonObject value=new JsonObject();value.addProperty("schemaVersion",1);value.addProperty("instanceId",7);
        JsonArray tables=new JsonArray();value.add("tables",tables);
        String[] names={"storage_service_protocol","storage_file_share","storage_block_target","storage_identity_domain","storage_access_rule"};
        for(String name:names) {
            JsonObject table=new JsonObject();table.addProperty("table",name);
            JsonArray columns=new JsonArray();
            for(String key:("storage_access_rule".equals(name) ? new String[]{"id","uuid","resource_type","resource_id"} : new String[]{"id","uuid","instance_id"})) {
                JsonObject column=new JsonObject();column.addProperty("name",key);column.addProperty("type",java.sql.Types.VARCHAR);columns.add(column);
            }
            table.add("columns",columns);table.add("rows",new JsonArray());tables.add(table);
        }
        JsonObject share=new JsonObject();share.addProperty("id",11);share.addProperty("uuid","00000000-0000-0000-0000-000000000011");share.addProperty("instance_id",7);
        table(value,1).getAsJsonArray("rows").add(share);
        return value;
    }
    private JsonObject table(JsonObject value,int index){return value.getAsJsonArray("tables").get(index).getAsJsonObject();}
    private JsonObject share(JsonObject value){return table(value,1).getAsJsonArray("rows").get(0).getAsJsonObject();}
    private void reject(JsonObject value){Assert.assertThrows(CloudRuntimeException.class,()->StorageServiceDesiredSnapshot.validateSnapshot(7,value.toString()));}
    private JsonObject extendedSnapshot() {
        final JsonObject value = snapshot();value.addProperty("schemaVersion", 2);
        final JsonObject policy = new JsonObject();policy.addProperty("table", "storage_posix_directory_policy");
        final JsonArray columns = new JsonArray();
        for (String key : new String[] {"id", "uuid", "instance_id"}) {
            final JsonObject column = new JsonObject();column.addProperty("name", key);column.addProperty("type", java.sql.Types.VARCHAR);columns.add(column);
        }
        policy.add("columns", columns);policy.add("rows", new JsonArray());value.getAsJsonArray("tables").add(policy);
        final JsonObject reference = new JsonObject();reference.addProperty("name", "posix_policy_id");reference.addProperty("type", java.sql.Types.BIGINT);
        table(value, 1).getAsJsonArray("columns").add(reference);share(value).add("posix_policy_id", com.google.gson.JsonNull.INSTANCE);
        return value;
    }
    @Test public void acceptsExtendedSnapshotAndRejectsForeignPolicyReference() {
        JsonObject value = extendedSnapshot();Assert.assertEquals(6, StorageServiceDesiredSnapshot.validateSnapshot(7, value.toString()).size());
        share(value).addProperty("posix_policy_id", 99);reject(value);
    }
    @Test public void nativeDirectoryMetadataDoesNotExpandTheAllowedSqlTableSet() {
        JsonObject value = extendedSnapshot();value.add("nativePosixDirectory", new JsonObject());
        Assert.assertEquals(6, StorageServiceDesiredSnapshot.validateSnapshot(7, value.toString()).size());
        table(value, 5).addProperty("table", "volumes");reject(value);
    }
    @Test public void acceptsCompleteScopedSnapshot() {Assert.assertEquals(5,StorageServiceDesiredSnapshot.validateSnapshot(7,snapshot().toString()).size());}
    @Test public void rejectsCrossInstanceRowBeforeAnyDatabaseMutation() {JsonObject value=snapshot();share(value).addProperty("instance_id",8);reject(value);}
    @Test public void rejectsForeignAclReferenceBeforeAnyDatabaseMutation() {
        JsonObject value=snapshot();JsonObject acl=new JsonObject();acl.addProperty("id",12);acl.addProperty("uuid","00000000-0000-0000-0000-000000000012");acl.addProperty("resource_type","FILE_SHARE");acl.addProperty("resource_id",999);
        table(value,4).getAsJsonArray("rows").add(acl);reject(value);
    }
    @Test public void rejectsDuplicateResourceIdentity() {JsonObject value=snapshot();table(value,1).getAsJsonArray("rows").add(new JsonParser().parse(share(value).toString()));reject(value);}
    @Test public void rejectsMissingAndUnknownRowColumns() {JsonObject value=snapshot();share(value).addProperty("unexpected",true);reject(value);}
    @Test public void rejectsNestedValues() {JsonObject value=snapshot();share(value).add("uuid",new JsonObject());reject(value);}
    @Test public void rejectsIncompleteOrRepeatedTables() {JsonObject value=snapshot();value.getAsJsonArray("tables").remove(4);reject(value);}
    @Test public void rejectsSnapshotForAnotherInstance() {JsonObject value=snapshot();value.addProperty("instanceId",8);reject(value);}
}
