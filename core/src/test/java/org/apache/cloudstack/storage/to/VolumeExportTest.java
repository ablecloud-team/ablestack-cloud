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
package org.apache.cloudstack.storage.to;

import java.util.HashMap;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import com.cloud.storage.Storage;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class VolumeExportTest {
    private VolumeObjectTO source, result;
    @Before public void setup() {
        PrimaryDataStore store = mock(PrimaryDataStore.class); when(store.getUuid()).thenReturn("pool");
        source = new VolumeObjectTO(); source.setUuid("source"); source.setPath("original"); source.setSize(1048576L); source.setDataStore(new PrimaryDataStoreTO(store));
        result = new VolumeObjectTO(); result.setPath("volumes/1/file"+VolumeExport.SUFFIX); result.setSize(source.getSize()); result.setFormat(Storage.ImageFormat.QCOW2);
        result.setExportVerification(VolumeExport.receipt(source,"pool",65536));
    }
    @Test public void independentMatchingReceiptAccepted() { VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void oldAgentRejected() { result.setExportVerification(null); VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void wrongSourceRejected() { source.setPath("other"); VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void wrongFormatRejected() { result.setFormat(Storage.ImageFormat.RAW); VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void allocationEvidenceCannotBeCopied() { result.setAllocation(java.util.Map.of("allocation.evidence","copied")); VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void wrongSizeRejected() { result.setSize(1L); VolumeExport.validate(source,result); }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class) public void staleReceiptRejected() { var v=new HashMap<>(result.getExportVerification()); v.put("verifiedAt","2020-01-01T00:00:00Z"); result.setExportVerification(v); VolumeExport.validate(source,result); }
}
