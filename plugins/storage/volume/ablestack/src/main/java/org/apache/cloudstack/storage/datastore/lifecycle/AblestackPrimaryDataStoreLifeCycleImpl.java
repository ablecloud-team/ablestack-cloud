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
package org.apache.cloudstack.storage.datastore.lifecycle;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.StorageConflictException;
import com.cloud.host.dao.HostDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolWorkDao;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolAutomation;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.UriUtils;
import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreParameters;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.volume.datastore.PrimaryDataStoreHelper;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AblestackPrimaryDataStoreLifeCycleImpl extends BasePrimaryDataStoreLifeCycleImpl implements PrimaryDataStoreLifeCycle {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected ResourceManager _resourceMgr;
    @Inject
    PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    AgentManager agentMgr;
    @Inject
    StorageManager storageMgr;
    @Inject
    protected StoragePoolHostDao _storagePoolHostDao;
    @Inject
    protected StoragePoolWorkDao _storagePoolWorkDao;
    @Inject
    PrimaryDataStoreHelper dataStoreHelper;
    @Inject
    StoragePoolAutomation storagePoolAutmation;
    @Inject
    protected HostDao _hostDao;

    @SuppressWarnings("unchecked")
    @Override
    public DataStore initialize(Map<String, Object> dsInfos) {
        Long clusterId = (Long) dsInfos.get("clusterId");
        Long podId = (Long) dsInfos.get("podId");
        Long zoneId = (Long) dsInfos.get("zoneId");
        String url = (String) dsInfos.get("url");
        String providerName = (String) dsInfos.get("providerName");
        HypervisorType hypervisorType = (HypervisorType) dsInfos.get("hypervisorType");
        if (clusterId != null && podId == null) {
            throw new InvalidParameterValueException("Cluster id requires pod id");
        }

        PrimaryDataStoreParameters parameters = new PrimaryDataStoreParameters();

        UriUtils.UriInfo uriInfo = UriUtils.getUriInfo(url);

        String scheme = uriInfo.getScheme();
        String storageHost = uriInfo.getStorageHost();
        String storagePath = uriInfo.getStoragePath();
        try {
            if (scheme == null) {
                throw new InvalidParameterValueException("scheme is null " + url + ", add rbd:// (or gluefs://) as a prefix");
            } else if (scheme.equalsIgnoreCase("rbd")) {
                if (storagePath == null) {
                    throw new InvalidParameterValueException("host or path is null, should be rbd://hostname/pool");
                }
            } else if (scheme.equalsIgnoreCase("gluefs")) {
                if (storagePath == null) {
                    throw new InvalidParameterValueException("host or path is null, should be gluefs://hostname/pool");
                }
            }
        } catch (Exception e) {
            throw new InvalidParameterValueException(url + " is not a valid uri");
        }

        String tags = (String) dsInfos.get("tags");
        Map<String, String> details = (Map<String, String>) dsInfos.get("details");

        parameters.setTags(tags);
        parameters.setDetails(details);

        String hostPath = null;
        try {
            hostPath = URLDecoder.decode(storagePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logger.error("[ignored] we are on a platform not supporting \"UTF-8\"!?!", e);
        }
        if (hostPath == null) { // if decoding fails, use getPath() anyway
            hostPath = storagePath;
        }
        String userInfo = uriInfo.getUserInfo();
        int port = uriInfo.getPort();
        if (logger.isDebugEnabled()) {
            logger.debug("createPool Params @ scheme - " + scheme + " storageHost - " + storageHost + " hostPath - " + hostPath + " port - " + port);
        }
        if (scheme.equalsIgnoreCase("rbd")) {
            if (port == -1) {
                port = 0;
            }
            parameters.setType(StoragePoolType.RBD);
            parameters.setHost(storageHost);
            parameters.setPort(port);
            parameters.setPath(hostPath.replaceFirst("/", ""));
            parameters.setUserInfo(userInfo);
            parameters.setKrbdPath((String) dsInfos.get("krbdPath"));
        } else if (scheme.equalsIgnoreCase("gluefs")) {
            if (port == -1) {
                port = 0;
            }
            parameters.setType(StoragePoolType.SharedMountPoint);
            parameters.setHost(storageHost);
            parameters.setPort(0);
            parameters.setPath(hostPath);
            parameters.setUserInfo(userInfo);
        }

        String uuid = null;
        if (scheme.equalsIgnoreCase("gluefs")) {
            uuid = UUID.randomUUID().toString();
        } else {
            uuid = UUID.nameUUIDFromBytes((storageHost + hostPath).getBytes()).toString();
        }

        List<StoragePoolVO> spHandles = primaryDataStoreDao.findIfDuplicatePoolsExistByUUID(uuid);
        if ((spHandles != null) && (spHandles.size() > 0)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Another active pool with the same uuid already exists");
            }
            throw new CloudRuntimeException("Another active pool with the same uuid already exists");
        }
        parameters.setUuid(uuid);
        parameters.setZoneId(zoneId);
        parameters.setPodId(podId);
        parameters.setName((String) dsInfos.get("name"));
        parameters.setClusterId(clusterId);
        parameters.setProviderName(providerName);
        parameters.setHypervisorType(hypervisorType);

        return dataStoreHelper.createPrimaryDataStore(parameters);
    }

    protected boolean createStoragePool(long hostId, StoragePool pool) {
        logger.debug("creating pool " + pool.getName() + " on  host " + hostId);

        if (pool.getPoolType() != StoragePoolType.RBD) {
            logger.warn(" Doesn't support storage pool type " + pool.getPoolType());
            return false;
        }
        CreateStoragePoolCommand cmd = new CreateStoragePoolCommand(true, pool);
        final Answer answer = agentMgr.easySend(hostId, cmd);
        if (answer != null && answer.getResult()) {
            return true;
        } else {
            primaryDataStoreDao.expunge(pool.getId());
            String msg = answer != null
                    ? "Can not create storage pool through host " + hostId + " due to " + answer.getDetails()
                    : "Can not create storage pool through host " + hostId + " due to CreateStoragePoolCommand returns null";
            logger.warn(msg);
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public boolean attachCluster(DataStore store, ClusterScope scope) {
        PrimaryDataStoreInfo primarystore = (PrimaryDataStoreInfo)store;
        // Check if there is host up in this cluster
        List<HostVO> allHosts =
                _resourceMgr.listAllUpHosts(Host.Type.Routing, primarystore.getClusterId(), primarystore.getPodId(), primarystore.getDataCenterId());
        if (allHosts.isEmpty()) {
            primaryDataStoreDao.expunge(primarystore.getId());
            throw new CloudRuntimeException("No host up to associate a storage pool with in cluster " + primarystore.getClusterId());
        }

        boolean success = false;
        for (HostVO h : allHosts) {
            success = createStoragePool(h.getId(), primarystore);
            if (success) {
                break;
            }
        }

        logger.debug("In createPool Adding the pool to each of the hosts");
        List<HostVO> poolHosts = new ArrayList<HostVO>();
        for (HostVO h : allHosts) {
            try {
                storageMgr.connectHostToSharedPool(h.getId(), primarystore.getId());
                poolHosts.add(h);
            } catch (StorageConflictException se) {
                primaryDataStoreDao.expunge(primarystore.getId());
                throw new CloudRuntimeException("Storage has already been added as local storage");
            } catch (Exception e) {
                logger.warn("Unable to establish a connection between " + h + " and " + primarystore, e);
            }
        }

        if (poolHosts.isEmpty()) {
            logger.warn("No host can access storage pool " + primarystore + " on cluster " + primarystore.getClusterId());
            primaryDataStoreDao.expunge(primarystore.getId());
            throw new CloudRuntimeException("Failed to access storage pool");
        }

        dataStoreHelper.attachCluster(store);
        return true;
    }

    @Override
    public boolean attachZone(DataStore dataStore, ZoneScope scope, HypervisorType hypervisorType) {
        List<HostVO> hosts = _resourceMgr.listAllUpAndEnabledHostsInOneZoneByHypervisor(hypervisorType, scope.getScopeId());
        logger.debug("In createPool. Attaching the pool to each of the hosts.");
        List<HostVO> poolHosts = new ArrayList<HostVO>();
        for (HostVO host : hosts) {
            try {
                storageMgr.connectHostToSharedPool(host.getId(), dataStore.getId());
                poolHosts.add(host);
            } catch (StorageConflictException se) {
                    primaryDataStoreDao.expunge(dataStore.getId());
                    throw new CloudRuntimeException("Storage has already been added as local storage to host: " + host.getName());
            } catch (Exception e) {
                logger.warn("Unable to establish a connection between " + host + " and " + dataStore, e);
            }
        }
        if (poolHosts.isEmpty()) {
            logger.warn("No host can access storage pool " + dataStore + " in this zone.");
            primaryDataStoreDao.expunge(dataStore.getId());
            throw new CloudRuntimeException("Failed to create storage pool as it is not accessible to hosts.");
        }
        dataStoreHelper.attachZone(dataStore, hypervisorType);
        return true;
    }

    @Override
    public boolean maintain(DataStore dataStore) {
        storagePoolAutmation.maintain(dataStore);
        dataStoreHelper.maintain(dataStore);
        return true;
    }

    @Override
    public boolean cancelMaintain(DataStore store) {
        dataStoreHelper.cancelMaintain(store);
        storagePoolAutmation.cancelMaintain(store);
        return true;
    }

    @DB
    @Override
    public boolean deleteDataStore(DataStore store) {
        List<StoragePoolHostVO> hostPoolRecords = _storagePoolHostDao.listByPoolId(store.getId());
        StoragePool pool = (StoragePool)store;
        boolean deleteFlag = false;
        // find the hypervisor where the storage is attached to.
        HypervisorType hType = null;
        if (hostPoolRecords.size() > 0) {
            hType = getHypervisorType(hostPoolRecords.get(0).getHostId());
        }

        // Remove the SR associated with the Xenserver
        for (StoragePoolHostVO host : hostPoolRecords) {
            DeleteStoragePoolCommand deleteCmd = new DeleteStoragePoolCommand(pool);
            final Answer answer = agentMgr.easySend(host.getHostId(), deleteCmd);

            if (answer != null && answer.getResult()) {
                deleteFlag = true;
                // if host is KVM hypervisor then send deleteStoragepoolcmd to all the kvm hosts.
                if (HypervisorType.KVM != hType) {
                    break;
                }
            } else {
                if (answer != null) {
                    logger.debug("Failed to delete storage pool: " + answer.getResult());
                }
            }
        }

        if (!hostPoolRecords.isEmpty() && !deleteFlag) {
            throw new CloudRuntimeException("Failed to delete storage pool on host");
        }

        return dataStoreHelper.deletePrimaryDataStore(store);
    }

    private HypervisorType getHypervisorType(long hostId) {
        HostVO host = _hostDao.findById(hostId);
        if (host != null)
            return host.getHypervisorType();
        return HypervisorType.None;
    }

    @Override
    public boolean attachHost(DataStore store, HostScope scope, StoragePoolInfo existingInfo) {
        dataStoreHelper.attachHost(store, scope, existingInfo);
        return true;
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle#
     * migrateToObjectStore(org.apache.cloudstack.engine.subsystem.api.storage.
     * DataStore)
     */
    @Override
    public boolean migrateToObjectStore(DataStore store) {
        return false;
    }

    @Override
    public void updateStoragePool(StoragePool storagePool, Map<String, String> details) {
    }

    public void enableStoragePool(DataStore dataStore) {
        dataStoreHelper.enable(dataStore);
    }

    @Override
    public void disableStoragePool(DataStore dataStore) {
        dataStoreHelper.disable(dataStore);
    }
}
