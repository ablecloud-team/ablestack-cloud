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
package com.cloud.storage;

import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.api.command.admin.storage.GetVolumeAllocationCapabilitiesCmd;
import org.apache.cloudstack.api.response.VolumeAllocationCapabilityResponse;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import com.cloud.agent.AgentManager;
import com.cloud.api.response.ApiResponseSerializer;
import com.google.gson.JsonParser;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.utils.component.PluggableService;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class VolumeAllocationRegistrationTest {
    @Test public void capabilityJsonHasAStableObjectNameAndFalseSupportFlag() {
        VolumeAllocationCapabilityResponse response = new VolumeAllocationCapabilityResponse();
        response.setResponseName("getvolumeallocationcapabilitiesresponse");
        response.setScope("pool", "host", "raw", "create");
        response.setReason("AGENT_CAPABILITY_NOT_ADVERTISED");
        var json = JsonParser.parseString(ApiResponseSerializer.toSerializedString(response, "json"))
                .getAsJsonObject().getAsJsonObject("getvolumeallocationcapabilitiesresponse");
        assertFalse(json.has("null"));
        assertFalse(json.getAsJsonObject("volumeallocationcapability").get("supported").getAsBoolean());
    }

    @Test public void productionXmlRegistersAnInjectedApiProvider() {
        DefaultListableBeanFactory definitions = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(definitions).loadBeanDefinitions(
                "classpath:META-INF/cloudstack/core/spring-server-core-managers-context.xml");
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBeanDefinition("volumeAllocationManager", definitions.getBeanDefinition("volumeAllocationManager"));
            HostDao hosts = mock(HostDao.class);
            context.getBeanFactory().registerSingleton("hostDao", hosts);
            context.getBeanFactory().registerSingleton("poolDao", mock(PrimaryDataStoreDao.class));
            context.getBeanFactory().registerSingleton("poolHostDao", mock(StoragePoolHostDao.class));
            context.getBeanFactory().registerSingleton("agentManager", mock(AgentManager.class));
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.refresh();
            PluggableService provider = context.getBeansOfType(PluggableService.class).get("volumeAllocationManager");
            assertTrue(provider.getCommands().contains(GetVolumeAllocationCapabilitiesCmd.class));
            assertSame(hosts, ReflectionTestUtils.getField(provider, "hostDao"));
        }
    }
}
