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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.concurrent.atomic.AtomicBoolean;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventInterceptor;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDaoImpl;
import com.cloud.utils.db.TransactionContextInterceptor;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmWorkJobHandler;

@RunWith(Parameterized.class)
public class AblestackSharedRawSpringContextTest {
    @Parameterized.Parameters(name = "classProxy={0}")
    public static Object[] proxyModes() {
        return new Object[]{false, true};
    }

    @Parameterized.Parameter
    public boolean classProxy;

    private final AblestackSharedRawBackupService guards = mock(AblestackSharedRawBackupService.class);
    private final ActionEventInterceptor events = mock(ActionEventInterceptor.class);
    private final TransactionContextInterceptor transactions = mock(TransactionContextInterceptor.class);

    private GenericApplicationContext context(Object target) {
        DefaultListableBeanFactory definitions = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(definitions).loadBeanDefinitions(
                "classpath:META-INF/cloudstack/core/spring-server-ablestack-shared-raw-context.xml");
        GenericApplicationContext context = new GenericApplicationContext();
        // Production already creates proxies for DAO transactions and API action events.
        new XmlBeanDefinitionReader(context).loadBeanDefinitions(
                "classpath:META-INF/cloudstack/system/spring-core-system-context-inheritable.xml");
        if (classProxy) AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(context);
        context.removeBeanDefinition("cloudStackLifeCycle");
        context.removeBeanDefinition("moduleStartup");
        ((AbstractBeanDefinition) context.getBeanDefinition("actionEventInterceptor")).setInstanceSupplier(() -> events);
        ((AbstractBeanDefinition) context.getBeanDefinition("transactionContextInterceptor")).setInstanceSupplier(() -> transactions);
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

    private Object earlyReference(GenericApplicationContext context) throws Exception {
        Object service = context.getBean("protectedService");
        CircularPeer peer = context.getBean(CircularPeer.class);
        assertTrue(AopUtils.isAopProxy(service));
        assertSame(service, peer.service);
        assertEquals(1, context.getBeansOfType(AbstractAutoProxyCreator.class).size());
        assertFalse(AopUtils.isAopProxy(((Advised) service).getTargetSource().getTarget()));
        assertFalse(AopUtils.isAopProxy(peer));
        verifyNoInteractions(guards);
        return peer.service;
    }

    @Test
    public void circularVolumeServiceUsesOneProxyAndStillRejectsProtectedAttachment() throws Exception {
        CircularVolumeService target = new CircularVolumeService();
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeApiService service = (VolumeApiService) earlyReference(context);
            assertSame(service, context.getBean(VolumeApiService.class));
            assertSame(service, context.getBean(VmWorkJobHandler.class));
            assertSame(context.getBean(CircularPeer.class), target.peer);
            when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
            assertThrows(CloudRuntimeException.class, () -> service.attachVolumeToVM(20L, 10L, 1L, false, false));
            assertFalse(target.attached);
            verify(guards).guardVolume(20L, 10L, null, null);
            verifyNoInteractions(events);
        }
    }

    @Test
    public void circularVolumeDaoUsesTheSameGuardedReference() throws Exception {
        VolumeDao target = mock(VolumeDaoImpl.class, withSettings().extraInterfaces(DependsOnPeer.class));
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeDao service = (VolumeDao) earlyReference(context);
            when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
            assertThrows(CloudRuntimeException.class, () -> service.attachVolume(10L, 20L, 1L));
            verify(target, never()).attachVolume(10L, 20L, 1L);
            verifyNoInteractions(transactions);
        }
    }

    @Test
    public void allowedApiMutationRetainsActionEventsInsideTheGuardLease() throws Throwable {
        CircularVolumeService target = new CircularVolumeService();
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeApiService service = (VolumeApiService) earlyReference(context);
            AtomicBoolean held = new AtomicBoolean();
            when(guards.guardVolume(20L, 10L, null, null)).thenAnswer(call -> {
                held.set(true);
                return (AblestackSharedRawBackupService.Lease) () -> held.set(false);
            });
            when(events.invoke(any(MethodInvocation.class))).thenAnswer(call -> {
                assertTrue(held.get());
                return ((MethodInvocation) call.getArgument(0)).proceed();
            });
            service.attachVolumeToVM(20L, 10L, 1L, false, false);
            assertTrue(target.attached);
            assertFalse(held.get());
            verify(events).invoke(any(MethodInvocation.class));
        }
    }

    @Test
    public void allowedDaoMutationRetainsTransactionAdviceInsideTheGuardLease() throws Throwable {
        VolumeDao target = mock(VolumeDaoImpl.class, withSettings().extraInterfaces(DependsOnPeer.class));
        try (GenericApplicationContext context = context(target)) {
            context.refresh();
            VolumeDao service = (VolumeDao) earlyReference(context);
            AtomicBoolean held = new AtomicBoolean();
            when(guards.guardVolume(20L, 10L, null, null)).thenAnswer(call -> {
                held.set(true);
                return (AblestackSharedRawBackupService.Lease) () -> held.set(false);
            });
            when(transactions.invoke(any(MethodInvocation.class))).thenAnswer(call -> {
                assertTrue(held.get());
                return ((MethodInvocation) call.getArgument(0)).proceed();
            });
            service.attachVolume(10L, 20L, 1L);
            verify(target).attachVolume(10L, 20L, 1L);
            assertFalse(held.get());
            verify(transactions).invoke(any(MethodInvocation.class));
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
        @ActionEvent(eventType = "TEST_VOLUME_ATTACH", eventDescription = "test attachment")
        public Volume attachVolumeToVM(Long vmId, Long volumeId, Long deviceId, Boolean allowShared, boolean restoring) {
            attached = true;
            return null;
        }
    }
}
