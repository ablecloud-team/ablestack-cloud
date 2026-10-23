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
package org.apache.cloudstack.storage.sharedfs;

import java.lang.reflect.Field;
import java.util.Collections;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSServiceOfferingsCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SharedFSServiceOfferingResponse;
import org.apache.cloudstack.query.QueryService;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Test;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SharedFSOfferingCatalogCmdTest {
    private void field(Object target, String name, Object value) throws Exception {
        Field field = ListServiceOfferingsCmd.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void reusesVisibleQueryAndPassesItsResultUnchangedToEligibility() throws Exception {
        ListSharedFSServiceOfferingsCmd command = new ListSharedFSServiceOfferingsCmd();
        field(command, "zoneId", 13L);
        QueryService query = mock(QueryService.class);
        SharedFSService sharedFS = mock(SharedFSService.class);
        command._queryService = query;
        command.sharedFSService = sharedFS;
        ListResponse<ServiceOfferingResponse> visible = new ListResponse<>();
        visible.setResponses(Collections.emptyList(), 0);
        ListResponse<SharedFSServiceOfferingResponse> response = new ListResponse<>();
        response.setResponses(Collections.emptyList(), 0);
        when(query.searchForServiceOfferings(command)).thenReturn(visible);
        when(sharedFS.listCreationServiceOfferings(13L, visible)).thenReturn(response);
        command.execute();
        assertSame(response, command.getResponseObject());
        verify(query).searchForServiceOfferings(command);
        verify(sharedFS).listCreationServiceOfferings(13L, visible);
    }

    @Test
    public void rejectsMissingZoneAndRuntimeOfferingChangeContextBeforeQuery() throws Exception {
        ListSharedFSServiceOfferingsCmd command = new ListSharedFSServiceOfferingsCmd();
        QueryService query = mock(QueryService.class);
        command._queryService = query;
        assertThrows(InvalidParameterValueException.class, command::execute);
        field(command, "zoneId", 13L);
        field(command, "virtualMachineId", 1L);
        assertThrows(InvalidParameterValueException.class, command::execute);
        verifyNoInteractions(query);
    }

    @Test
    public void rejectsSystemOrInactiveOfferingFilters() throws Exception {
        for (String name : new String[] {"isSystem", "serviceOfferingState"}) {
            ListSharedFSServiceOfferingsCmd command = new ListSharedFSServiceOfferingsCmd();
            field(command, "zoneId", 13L);
            field(command, name, name.equals("isSystem") ? true : "Inactive");
            assertThrows(InvalidParameterValueException.class, command::execute);
        }
    }
}
