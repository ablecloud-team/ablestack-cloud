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
package com.cloud.vm;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Provider;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageVmLifecycleSpringContextTest {
    @Test public void actualVmManagerResolvesTheSharedFsProviderLazilyAfterStartup() {
        try(GenericApplicationContext context=new GenericApplicationContext()) {
            Map<Class<?>,String> mocks=new HashMap<>();
            for(Class<?> type=UserVmManagerImpl.class;type!=null;type=type.getSuperclass()) {
                for(Field field:type.getDeclaredFields()) {
                    if ((field.isAnnotationPresent(Inject.class)||field.isAnnotationPresent(Autowired.class)) && field.getType()!=Provider.class) {
                        String name=mocks.get(field.getType());
                        if(name==null) {name="dependency_"+mocks.size();mocks.put(field.getType(),name);context.getBeanFactory().registerSingleton(name,Mockito.mock(field.getType()));}
                        if (!context.containsBean(field.getName())) context.registerAlias(name,field.getName());
                        Qualifier qualifier=field.getAnnotation(Qualifier.class);if(qualifier!=null)context.registerAlias(name,qualifier.value());
                    }
                }
            }
            AtomicInteger created=new AtomicInteger();SharedFSService service=Mockito.mock(SharedFSService.class);
            RootBeanDefinition shared=new RootBeanDefinition(SharedFSService.class,()->{created.incrementAndGet();Assert.assertNotNull(context.getBean("userVmManagerStartup"));return service;});shared.setLazyInit(true);context.registerBeanDefinition("sharedFSServiceStartup",shared);
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);context.registerBeanDefinition("userVmManagerStartup",new RootBeanDefinition(UserVmManagerImpl.class));context.refresh();
            UserVmManagerImpl manager=context.getBean("userVmManagerStartup",UserVmManagerImpl.class);Assert.assertEquals(0,created.get());
            Provider<?> provider=(Provider<?>)ReflectionTestUtils.getField(manager,"storageFsLifecycleSafety");Assert.assertNotNull(provider);Assert.assertSame(service,provider.get());Assert.assertEquals(1,created.get());
        }
    }
}
