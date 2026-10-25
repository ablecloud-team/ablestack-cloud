// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.junit.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class AblestackSharedRawSpringContextTest {
    private final AblestackSharedRawBackupService guards = mock(AblestackSharedRawBackupService.class);

    private GenericApplicationContext context(Object target) {
        DefaultListableBeanFactory definitions = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(definitions).loadBeanDefinitions(
                "classpath:META-INF/cloudstack/core/spring-server-ablestack-shared-raw-context.xml");
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBeanDefinition("ablestackSharedRawMutationInterceptor",
                definitions.getBeanDefinition("ablestackSharedRawMutationInterceptor"));
        context.getBeanFactory().registerSingleton("guards", guards);

        RootBeanDefinition service = new RootBeanDefinition(target.getClass());
        service.setInstanceSupplier(() -> target);
        service.getPropertyValues().add("peer", new RuntimeBeanReference("peer"));
        context.registerBeanDefinition("protectedService", service);

        RootBeanDefinition peer = new RootBeanDefinition(CircularPeer.class);
        peer.getPropertyValues().add("service", new RuntimeBeanReference("protectedService"));
        context.registerBeanDefinition("peer", peer);
        return context;
    }

    private Object earlyReference(GenericApplicationContext context) {
        Object service = context.getBean("protectedService");
        CircularPeer peer = context.getBean(CircularPeer.class);
        assertTrue(AopUtils.isAopProxy(service));
        assertSame(service, peer.service);
        assertFalse(AopUtils.isAopProxy(peer));
        verifyNoInteractions(guards);
        return peer.service;
    }

    @Test
    public void circularVolumeServiceUsesOneClassProxyAndStillRejectsProtectedAttachment() {
        CircularVolumeService target = new CircularVolumeService();
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeApiService service = (VolumeApiService) earlyReference(context);
            assertSame(service, context.getBean(VolumeApiServiceImpl.class));
            assertTrue(AopUtils.isCglibProxy(service));
            assertSame(context.getBean(CircularPeer.class), target.peer);
            when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
            assertThrows(CloudRuntimeException.class, () -> service.attachVolumeToVM(20L, 10L, 1L, false, false));
            assertFalse(target.attached);
            verify(guards).guardVolume(20L, 10L, null, null);
        }
    }

    @Test
    public void circularVolumeDaoUsesTheSameGuardedReference() {
        VolumeDao target = mock(VolumeDao.class, withSettings().extraInterfaces(DependsOnPeer.class));
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeDao service = (VolumeDao) earlyReference(context);
            when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
            assertThrows(CloudRuntimeException.class, () -> service.attachVolume(10L, 20L, 1L));
            verify(target, never()).attachVolume(10L, 20L, 1L);
        }
    }

    @Test
    public void circularAgentManagerKeepsItsReferenceAndNormalCommandBehavior() throws Exception {
        AgentManager target = mock(AgentManager.class, withSettings().extraInterfaces(DependsOnPeer.class));
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            AgentManager service = (AgentManager) earlyReference(context);
            Command command = mock(Command.class);
            Answer answer = new Answer(command, true, "ok");
            when(target.send(1L, command)).thenReturn(answer);
            assertSame(answer, service.send(1L, command));
            verifyNoInteractions(guards);
        }
    }

    public interface DependsOnPeer {
        void setPeer(CircularPeer peer);
    }

    public static class CircularPeer {
        private Object service;

        public void setService(Object service) {
            this.service = service;
        }
    }

    public static class CircularVolumeService extends VolumeApiServiceImpl implements DependsOnPeer {
        private CircularPeer peer;
        private boolean attached;

        @Override
        public void setPeer(CircularPeer peer) {
            this.peer = peer;
        }

        @Override
        public Volume attachVolumeToVM(Long vmId, Long volumeId, Long deviceId, Boolean allowShared, boolean restoring) {
            attached = true;
            return null;
        }
    }
}
