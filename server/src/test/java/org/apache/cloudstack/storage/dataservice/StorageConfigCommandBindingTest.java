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

import org.junit.Assert;
import org.junit.Test;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageConfigCommandBindingTest {
    private JsonObject object(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    @Test public void mappingUsesCurrentApiParametersAndForcesExistingVolumeImport() {
        JsonObject resource = object("{'name':'project','filesystem':'xfs','config':{'relativeSharePath':'exports/project','listenerGroupPorts':[2049,2050],'readOnly':true}}");
        JsonObject parameters = StorageConfigCommandBinding.parameters(resource, "file-shares");parameters.addProperty("instanceid", 7);parameters.addProperty("volumeid", 45);
        CreateStorageNfsExportCmd cmd = (CreateStorageNfsExportCmd) StorageConfigCommandBinding.bind(CreateStorageNfsExportCmd.class, parameters);
        Assert.assertEquals(Long.valueOf(7), cmd.getInstanceId());Assert.assertEquals(Long.valueOf(45), cmd.getVolumeId());
        Assert.assertEquals("MOUNT_EXISTING", cmd.getImportMode());Assert.assertEquals("2049,2050", cmd.getListenerPorts());
        Assert.assertEquals("exports/project", cmd.getRelativePath());Assert.assertEquals(Boolean.TRUE, cmd.getReadOnly());Assert.assertEquals(Boolean.FALSE, cmd.getCreateDirectory());
    }
    @Test public void arbitraryFieldsCannotBeInjectedIntoCommandObjects() {
        Assert.assertThrows(InvalidParameterValueException.class, () -> StorageConfigCommandBinding.bind(CreateStorageNfsExportCmd.class, object("{'storageService':'not-a-service'}")));
    }
    @Test public void stringBooleansAndFractionalOrOverflowIntegersAreRejected() {
        for (String input : new String[] {"{'readonly':'false'}", "{'anonuid':1.5}", "{'anonuid':2147483648}"}) {
            Assert.assertThrows(InvalidParameterValueException.class, () -> StorageConfigCommandBinding.bind(UpdateStorageNfsExportCmd.class, object(input)));
        }
    }
    @Test public void longUuidMappingIdsDoNotOverflowIntegerSlots() {
        JsonObject parameters = object("{'id':5000000000,'readonly':false}");
        UpdateStorageNfsExportCmd cmd = (UpdateStorageNfsExportCmd) StorageConfigCommandBinding.bind(UpdateStorageNfsExportCmd.class, parameters);
        Assert.assertEquals(Long.valueOf(5000000000L), cmd.getId());Assert.assertEquals(Boolean.FALSE, cmd.getReadOnly());
    }
    @Test public void commonPosixPolicyRemainsTheOnlyNfsDirectoryOwnerDuringRestore() {
        JsonObject resource=new JsonObject();resource.addProperty("protocol","NFS");resource.addProperty("posixPolicyUuid","policy");
        JsonObject config=new JsonObject();config.addProperty("ownerUid",1001001);config.addProperty("ownerGid",1001001);config.addProperty("mode","2775");resource.add("config",config);
        JsonObject parameters=StorageConfigCommandBinding.parameters(resource,"file-shares");
        Assert.assertFalse(parameters.has("owneruid"));Assert.assertFalse(parameters.has("ownergid"));Assert.assertFalse(parameters.has("mode"));
        resource.remove("posixPolicyUuid");parameters=StorageConfigCommandBinding.parameters(resource,"file-shares");Assert.assertTrue(parameters.has("owneruid"));
    }

}
