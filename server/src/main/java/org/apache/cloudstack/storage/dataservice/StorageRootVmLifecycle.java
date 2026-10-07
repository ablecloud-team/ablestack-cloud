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

import java.util.Collections;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Same-VM lifecycle operations; QGA capability readiness is separate from protocol verification. */
public final class StorageRootVmLifecycle {
    private final VirtualMachineManager lifecycle;
    private final UserVmDao vms;
    private final StorageServiceGuestCommandDispatcher guest;
    public StorageRootVmLifecycle(VirtualMachineManager lifecycle,UserVmDao vms,StorageServiceGuestCommandDispatcher guest) {
        this.lifecycle=lifecycle;this.vms=vms;this.guest=guest;
    }
    private UserVmVO vm(long id) {
        UserVmVO vm=vms.findById(id);
        if (vm==null || !UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) throw new CloudRuntimeException("ROOT lifecycle requires a SharedFS SystemVM");
        return vm;
    }
    public void stop(long vmId) {
        UserVmVO vm=vm(vmId);
        if (vm.getState()==VirtualMachine.State.Stopped) return;
        if (vm.getState()!=VirtualMachine.State.Running) throw new CloudRuntimeException("SystemVM is transitional; ROOT stop requires reconciliation");
        try { lifecycle.stop(vm.getUuid()); }
        catch (com.cloud.exception.ResourceUnavailableException unavailable) {throw new CloudRuntimeException("Unable to stop SharedFS SystemVM gracefully",unavailable);}
        if (vm(vmId).getState()!=VirtualMachine.State.Stopped) throw new CloudRuntimeException("ROOT cannot be swapped before a confirmed SystemVM stop");
    }
    public void startAndAwaitCapabilities(StorageServiceInstanceVO instance,String operationUuid) {
        UserVmVO vm=vm(instance.getVmId());
        if (vm.getState()==VirtualMachine.State.Stopped) lifecycle.start(vm.getUuid(),Collections.emptyMap());
        else if (vm.getState()!=VirtualMachine.State.Running) throw new CloudRuntimeException("SystemVM is transitional; ROOT boot requires reconciliation");
        JsonObject request=new JsonObject();request.addProperty("instanceUuid",instance.getUuid());request.addProperty("operationUuid",operationUuid);
        long deadline=System.currentTimeMillis()+300000;
        while (System.currentTimeMillis()<deadline) {
            try {
                if (vm(instance.getVmId()).getState()==VirtualMachine.State.Running) {
                    StorageServiceGuestCommandResult result=guest.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                            "identity capsule capabilities",request.toString(),15,Collections.emptySet()));
                    JsonObject capability=result.isSuccess()?new JsonParser().parse(result.getResultJson()).getAsJsonObject():new JsonObject();
                    if (capability.has("success") && capability.get("success").getAsBoolean()
                            && capability.has("localIdentity") && capability.get("localIdentity").getAsBoolean()
                            && capability.has("protectedStdinTransport") && capability.get("protectedStdinTransport").getAsBoolean()) return;
                }
            } catch (RuntimeException booting) { /* QGA and bootstrap can become ready after the VM Running transition. */ }
            try { Thread.sleep(2000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new CloudRuntimeException("SystemVM ROOT readiness interrupted",interrupted);
            }
        }
        throw new CloudRuntimeException("Target ROOT did not reach protected QGA identity readiness");
    }
}
