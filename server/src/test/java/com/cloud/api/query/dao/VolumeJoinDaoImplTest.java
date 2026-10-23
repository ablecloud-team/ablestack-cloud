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
package com.cloud.api.query.dao;

import com.cloud.api.query.vo.VolumeJoinVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class VolumeJoinDaoImplTest extends GenericDaoBaseWithTagInformationBaseTest<VolumeJoinVO, VolumeResponse> {

    @InjectMocks
    private VolumeJoinDaoImpl _volumeJoinDaoImpl;

    @Mock
    private VolumeDetailsDao volumeDetailsDao;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;

    private VolumeJoinVO volume = new VolumeJoinVO();
    private VolumeResponse volumeResponse = new VolumeResponse();

    @Before
    public void setup() {
        prepareSetup();
    }

    @Test
    public void testUpdateVolumeTagInfo(){
        testUpdateTagInformation(_volumeJoinDaoImpl, volume, volumeResponse);
    }

    @Test
    public void fullRootResponseKeepsAsymmetricReadWriteLimits() {
        assertAsymmetricLimits(ResponseView.Full, Volume.Type.ROOT);
    }

    @Test
    public void restrictedDataResponseKeepsAsymmetricReadWriteLimits() {
        assertAsymmetricLimits(ResponseView.Restricted, Volume.Type.DATADISK);
    }

    private void assertAsymmetricLimits(ResponseView view, Volume.Type type) {
        VolumeJoinVO source = Mockito.mock(VolumeJoinVO.class);
        when(source.getDiskOfferingId()).thenReturn(1L);
        when(source.getVolumeType()).thenReturn(type);
        when(source.getBytesReadRate()).thenReturn(5242880L);
        when(source.getBytesWriteRate()).thenReturn(4194304L);
        when(source.getIopsReadRate()).thenReturn(100L);
        when(source.getIopsWriteRate()).thenReturn(75L);
        when(source.getKmsWrappedKeyId()).thenReturn(null);
        if (view == ResponseView.Full) {
            when(source.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        }
        CallContext.register(Mockito.mock(User.class), Mockito.mock(Account.class));
        try {
            JsonObject response = new Gson().toJsonTree(_volumeJoinDaoImpl.newVolumeResponse(view, source)).getAsJsonObject();
            assertEquals(5242880L, response.get("diskBytesReadRate").getAsLong());
            assertEquals(4194304L, response.get("diskBytesWriteRate").getAsLong());
            assertEquals(100L, response.get("diskIopsReadRate").getAsLong());
            assertEquals(75L, response.get("diskIopsWriteRate").getAsLong());
        } finally {
            CallContext.unregister();
        }
    }

}
