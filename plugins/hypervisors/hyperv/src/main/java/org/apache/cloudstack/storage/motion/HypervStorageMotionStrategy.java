/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.storage.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataMotionStrategy;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.StrategyPriority;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrateWithStorageAnswer;
import com.cloud.agent.api.MigrateWithStorageCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.api.to.VolumeTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class HypervStorageMotionStrategy implements DataMotionStrategy {
    protected Logger logger = LogManager.getLogger(getClass());
    @Inject AgentManager agentMgr;
    @Inject VolumeDao volDao;
    @Inject VolumeDataFactory volFactory;
    @Inject PrimaryDataStoreDao storagePoolDao;
    @Inject VMInstanceDao instanceDao;

    @Override
    public StrategyPriority canHandle(DataObject srcData, DataObject destData) {
        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public StrategyPriority canHandle(Map<VolumeInfo, DataStore> volumeMap, Host srcHost, Host destHost) {
        if (srcHost.getHypervisorType() == HypervisorType.Hyperv &&
                destHost.getHypervisorType() == HypervisorType.Hyperv) {
            return StrategyPriority.HYPERVISOR;
        }

        return StrategyPriority.CANT_HANDLE;
    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyAsync(Map<VolumeInfo, DataStore> volumeMap, VirtualMachineTO vmTo, Host srcHost, Host destHost,
            AsyncCompletionCallback<CopyCommandResult> callback) {
        Answer answer = null;
        String errMsg = null;
        try {
            VMInstanceVO instance = instanceDao.findById(vmTo.getId());
            if (instance != null) {
                answer = migrateVmWithVolumes(instance, vmTo, srcHost, destHost, volumeMap);
            } else {
                throw new CloudRuntimeException("Unsupported operation requested for moving data.");
            }
        } catch (Exception e) {
            logger.error("copy failed", e);
            errMsg = e.toString();
        }

        CopyCommandResult result = new CopyCommandResult(null, answer);
        result.setResult(errMsg);
        callback.complete(result);
    }

    private Answer migrateVmWithVolumes(VMInstanceVO vm, VirtualMachineTO to, Host srcHost,
            Host destHost, Map<VolumeInfo, DataStore> volumeToPool) throws AgentUnavailableException {

        // Initiate migration of a virtual machine with it's volumes.
        try {
            List<Pair<VolumeTO, StorageFilerTO>> volumeToFilerto = new ArrayList<Pair<VolumeTO, StorageFilerTO>>();
            for (Map.Entry<VolumeInfo, DataStore> entry : volumeToPool.entrySet()) {
                VolumeInfo volume = entry.getKey();
                VolumeTO volumeTo = new VolumeTO(volume, storagePoolDao.findById(volume.getPoolId()));
                StorageFilerTO filerTo = new StorageFilerTO((StoragePool)entry.getValue());
                volumeToFilerto.add(new Pair<VolumeTO, StorageFilerTO>(volumeTo, filerTo));
            }

            MigrateWithStorageCommand command = new MigrateWithStorageCommand(to, volumeToFilerto, destHost.getPrivateIpAddress());
            MigrateWithStorageAnswer answer = (MigrateWithStorageAnswer) agentMgr.send(srcHost.getId(), command);
            if (answer == null) {
                logger.error("Migration with storage of vm " + vm + " failed.");
                throw new CloudRuntimeException("Error while migrating the vm " + vm + " to host " + destHost);
            } else if (!answer.getResult()) {
                logger.error("Migration with storage of vm " + vm+ " failed. Details: " + answer.getDetails());
                throw new CloudRuntimeException("Error while migrating the vm " + vm + " to host " + destHost +
                        ". " + answer.getDetails());
            } else {
                // Update the volume details after migration.
                updateVolumePathsAfterMigration(volumeToPool, answer.getVolumeTos());
            }

            return answer;
        } catch (OperationTimedoutException e) {
            logger.error("Error while migrating vm " + vm + " to host " + destHost, e);
            throw new AgentUnavailableException("Operation timed out on storage motion for " + vm, destHost.getId());
        }
    }

    private void updateVolumePathsAfterMigration(Map<VolumeInfo, DataStore> volumeToPool, List<VolumeObjectTO> volumeTos) {
        for (Map.Entry<VolumeInfo, DataStore> entry : volumeToPool.entrySet()) {
            boolean updated = false;
            VolumeInfo volume = entry.getKey();
            StoragePool pool = (StoragePool)entry.getValue();
            for (VolumeObjectTO volumeTo : volumeTos) {
                if (volume.getId() == volumeTo.getId()) {
                    VolumeVO volumeVO = volDao.findById(volume.getId());
                    Long oldPoolId = volumeVO.getPoolId();
                    volumeVO.setPath(volumeTo.getPath());
                    volumeVO.setPodId(pool.getPodId());
                    volumeVO.setPoolId(pool.getId());
                    volumeVO.setLastPoolId(oldPoolId);
                    // For SMB, pool credentials are also stored in the uri query string.  We trim the query string
                    // part  here to make sure the credentials do not get stored in the db unencrypted.
                    String folder = pool.getPath();
                    if (pool.getPoolType() == StoragePoolType.SMB && folder != null && folder.contains("?")) {
                        folder = folder.substring(0, folder.indexOf("?"));
                    }
                    volumeVO.setFolder(folder);

                    volDao.update(volume.getId(), volumeVO);
                    updated = true;
                    break;
                }
            }

            if (!updated) {
                logger.error("Volume path wasn't updated for volume " + volume + " after it was migrated.");
            }
        }
    }

    @Override
    public void cloneAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {
        return;
    }
}
