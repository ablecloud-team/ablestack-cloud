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
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageOperationControlSpringContextTest {
    @Test public void shippedSchemaResourceResolvesTheNewManagerDaoInjectionAtContextStartup() {
        try(GenericApplicationContext context=new GenericApplicationContext()) {
            XmlBeanDefinitionReader reader=new XmlBeanDefinitionReader(context);
            reader.loadBeanDefinitions(new ClassPathResource("META-INF/cloudstack/core/spring-engine-schema-core-daos-context.xml"));
            String daoName="StorageServiceOperationControlDaoImpl";
            Assert.assertTrue("Operation control DAO must be registered in the shipped schema context",context.containsBeanDefinition(daoName));
            for(String name:context.getBeanDefinitionNames()) {
                if (!name.equals(daoName)) context.removeBeanDefinition(name);
                else context.getBeanDefinition(name).setLazyInit(true);
            }
            Set<Class<?>> dependencies=new HashSet<>();
            for(Class<?> type=StorageServiceManagerImpl.class;type!=null;type=type.getSuperclass()) {
                for(Field field:type.getDeclaredFields()) {
                    if(field.isAnnotationPresent(Inject.class) && field.getType()!=StorageServiceOperationControlDao.class && dependencies.add(field.getType())) {
                        context.getBeanFactory().registerSingleton("dependency_"+dependencies.size(),Mockito.mock(field.getType()));
                    }
                }
            }
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBeanDefinition("storageServiceManagerStartup",new RootBeanDefinition(StorageServiceManagerImpl.class));
            context.refresh();
            StorageServiceManagerImpl manager=context.getBean("storageServiceManagerStartup",StorageServiceManagerImpl.class);
            Object injected=ReflectionTestUtils.getField(manager,"storageOperationControlDao");
            Assert.assertNotNull(injected);Assert.assertSame(context.getBean(daoName),injected);Assert.assertTrue(injected instanceof StorageServiceOperationControlDao);
        }
    }
}
