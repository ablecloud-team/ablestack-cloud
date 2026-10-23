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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

/** ABLESTACK extension wiring; no changes to CloudStack power or attach implementations. */
public class AblestackSharedRawMutationInterceptor implements BeanPostProcessor, BeanFactoryAware {
    private BeanFactory factory;
    @Override public void setBeanFactory(BeanFactory factory) { this.factory = factory; }
    @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof AgentManager) && !(bean instanceof VolumeDao) && !(bean instanceof VolumeApiService)) return bean;
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.setProxyTargetClass(!java.lang.reflect.Proxy.isProxyClass(bean.getClass()));
        proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invoke(bean, invocation));
        return proxy.getProxy();
    }
    private AblestackSharedRawBackupService service() { return factory.getBean(AblestackSharedRawBackupService.class); }
    private boolean sharedFile(VolumeObjectTO volume) {
        return volume.getDataStore() instanceof PrimaryDataStoreTO
                && ((PrimaryDataStoreTO) volume.getDataStore()).getPoolType() == Storage.StoragePoolType.SharedMountPoint;
    }
    private boolean sharedFile(Command command) {
        if (command instanceof StartCommand) return Arrays.stream(((StartCommand) command).getVirtualMachine().getDisks())
                .anyMatch(disk -> disk.getData() instanceof VolumeObjectTO && sharedFile((VolumeObjectTO) disk.getData()));
        return command instanceof AttachCommand && ((AttachCommand) command).getDisk().getData() instanceof VolumeObjectTO
                && sharedFile((VolumeObjectTO) ((AttachCommand) command).getDisk().getData());
    }
    private Command wrap(Command command) {
        if (!sharedFile(command)) return command;
        return command instanceof StartCommand ? new AblestackSharedRawDiskCommand((StartCommand) command)
                : new AblestackSharedRawDiskCommand((AttachCommand) command);
    }
    private AblestackSharedRawBackupService.Lease commandLease(Command command) {
        if (!sharedFile(command)) return () -> {};
        if (command instanceof StartCommand) {
            var vm = factory.getBean(VMInstanceDao.class).findVMByInstanceName(((StartCommand) command).getVirtualMachine().getName());
            if (vm == null) { if (sharedFile(command)) throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: start VM identity is unavailable"); return () -> {}; }
            return service().guardStart(vm.getId());
        }
        if (command instanceof AttachCommand && ((AttachCommand) command).getDisk().getData() instanceof VolumeObjectTO) {
            VolumeObjectTO disk = (VolumeObjectTO) ((AttachCommand) command).getDisk().getData();
            return service().guardVolume(null, disk.getId(), null, null);
        }
        return () -> {};
    }
    private Object invoke(Object bean, MethodInvocation invocation) throws Throwable {
        String method = invocation.getMethod().getName(); Object[] args = invocation.getArguments();
        if (bean instanceof VolumeDao && ("attachVolume".equals(method) || "detachVolume".equals(method))) {
            try (var lease = service().guardVolume("attachVolume".equals(method) ? (Long) args[1] : null, (Long) args[0], null, null)) { return invocation.proceed(); }
        }
        if (bean instanceof VolumeApiService && "attachVolumeToVM".equals(method)) {
            Long vm; Long disk;
            if (args[0] instanceof org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd) {
                var command = (org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd) args[0];
                vm = command.getVirtualMachineId(); disk = command.getId();
            } else { vm = (Long) args[0]; disk = (Long) args[1]; }
            try (var lease = service().guardVolume(vm, disk, null, null)) { return invocation.proceed(); }
        }
        if (bean instanceof VolumeApiService && "detachVolumeFromVM".equals(method)) {
            var command = (org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd) args[0];
            try (var lease = service().guardVolume(command.getVirtualMachineId(), command.getId(), null, null)) { return invocation.proceed(); }
        }
        if (bean instanceof VolumeApiService && "updateVolume".equals(method)) {
            try (var lease = service().guardVolume(null, (Long) args[0], (Long) args[3], (String) args[1])) { return invocation.proceed(); }
        }
        if (!(bean instanceof AgentManager) || !("send".equals(method) || "easySend".equals(method)) || args.length < 2) return invocation.proceed();
        List<AblestackSharedRawBackupService.Lease> leases = new ArrayList<>();
        Commands original = args[1] instanceof Commands ? (Commands) args[1] : null;
        Object originalArgument = args[1];
        Commands wrapped = original == null ? null : new Commands(original.getErrorHandling());
        try {
            if (original != null) {
                boolean changed = false;
                for (Command command : original) {
                    leases.add(commandLease(command)); Command replacement = wrap(command);
                    changed |= replacement != command; wrapped.addCommand(replacement);
                }
                if (changed) {
                    if (args.length > 2 && args[args.length - 1] instanceof com.cloud.agent.Listener)
                        throw new CloudRuntimeException("SHARED_RAW_UNKNOWN: asynchronous raw-disk power dispatch is unsupported");
                    args[1] = wrapped;
                }
            } else if (args[1] instanceof Command) {
                Command command = (Command) args[1]; leases.add(commandLease(command)); args[1] = wrap(command);
            }
            Object answer = invocation.proceed();
            if (original != null && args[1] == wrapped) original.setAnswers(wrapped.getAnswers());
            return answer;
        } finally {
            args[1] = originalArgument;
            Collections.reverse(leases); leases.forEach(AblestackSharedRawBackupService.Lease::close);
        }
    }
}
