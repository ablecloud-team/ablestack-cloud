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

import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.apache.cloudstack.storage.sharedfs.SharedFSDeletionPowerGuard;
import com.cloud.utils.exception.CloudRuntimeException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SharedFSDeletionPowerEntryTest {
    private List<Method> entries() {
        List<Method> result=new ArrayList<>();
        for(Method m:VirtualMachineManagerImpl.class.getDeclaredMethods()) {
            if(Arrays.asList("orchestrateStart","orchestrateReboot","orchestrateMigrate","orchestrateMigrateAway","orchestrateMigrateWithStorage","orchestrateMigrateForScale","orchestrateStorageMigration").contains(m.getName())
                    &&m.getParameterTypes()[0]==String.class) {m.setAccessible(true);result.add(m);}
        }
        assertEquals(7,result.size());return result;
    }
    private Object[] args(Method m) {
        Object[] args=new Object[m.getParameterCount()];args[0]="deletion-vm";
        for(int i=1;i<args.length;i++)if(m.getParameterTypes()[i]==long.class)args[i]=0L;
        return args;
    }
    @Test public void everyActualPowerWorkerRejectsBeforeVmEffects()throws Exception {
        VirtualMachineManagerImpl worker=new VirtualMachineManagerImpl();
        for(Method method:entries())try(MockedStatic<SharedFSDeletionPowerGuard> hooks=mockStatic(SharedFSDeletionPowerGuard.class)){
            hooks.when(()->SharedFSDeletionPowerGuard.enter("deletion-vm")).thenThrow(new CloudRuntimeException("SHAREDFS_DELETION_ACTIVE"));
            InvocationTargetException failure=assertThrows(InvocationTargetException.class,()->method.invoke(worker,args(method)));
            assertEquals("SHAREDFS_DELETION_ACTIVE",failure.getCause().getMessage());
        }
    }
    @Test public void everyActualPowerWorkerClosesReservationWhenOriginalBodyFails()throws Exception {
        VirtualMachineManagerImpl worker=new VirtualMachineManagerImpl();
        for(Method method:entries())try(MockedStatic<SharedFSDeletionPowerGuard> hooks=mockStatic(SharedFSDeletionPowerGuard.class)){
            SharedFSDeletionPowerGuard guard=mock(SharedFSDeletionPowerGuard.class);
            hooks.when(()->SharedFSDeletionPowerGuard.enter("deletion-vm")).thenReturn(guard);
            assertThrows(InvocationTargetException.class,()->method.invoke(worker,args(method)));
            verify(guard).close();
        }
    }
}
