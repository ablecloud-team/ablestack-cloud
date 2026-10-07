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
package org.apache.cloudstack.api.command.user.storage.sharedfs;

import org.junit.Assert;
import org.junit.Test;
import org.apache.cloudstack.api.Parameter;
import com.cloud.exception.InvalidParameterValueException;

public class SharedFSInitialVolumeModeTest {
    private void set(CreateSharedFSCmd command,String field,Object value) throws Exception {
        java.lang.reflect.Field target=CreateSharedFSCmd.class.getDeclaredField(field);target.setAccessible(true);target.set(command,value);
    }
    @Test public void existingModeDoesNotRequireGuessedFilesystemOrOfferingAtApiBoundary() throws Exception {
        Assert.assertFalse(CreateSharedFSCmd.class.getDeclaredField("fsFormat").getAnnotation(Parameter.class).required());
        Assert.assertFalse(CreateSharedFSCmd.class.getDeclaredField("diskOfferingId").getAnnotation(Parameter.class).required());
        CreateSharedFSCmd command=new CreateSharedFSCmd();set(command,"backingVolumeMode"," existing ");set(command,"existingVolumeId",6L);
        Assert.assertTrue(command.isExistingVolume());Assert.assertNull(command.getDiskOfferingId());Assert.assertNull(command.getFsFormat());
    }
    @Test public void ambiguousAndUnknownModesAreRejected() throws Exception {
        CreateSharedFSCmd command=new CreateSharedFSCmd();set(command,"existingVolumeId",6L);
        Assert.assertThrows(InvalidParameterValueException.class,command::isExistingVolume);
        set(command,"backingVolumeMode","FORMAT");Assert.assertThrows(InvalidParameterValueException.class,command::isExistingVolume);
    }
}
