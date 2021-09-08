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

package com.cloud.desktop.cluster.actionworkers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterManagerImpl;
import com.cloud.desktop.cluster.DesktopClusterVO;
import com.cloud.desktop.cluster.dao.DesktopClusterDao;
import com.cloud.desktop.cluster.dao.DesktopClusterVmMapDao;
import com.cloud.desktop.cluster.dao.DesktopClusterIpRangeDao;
import com.cloud.desktop.cluster.DesktopClusterVmMapVO;
import com.cloud.desktop.version.DesktopTemplateMapVO;
import com.cloud.desktop.version.dao.DesktopTemplateMapDao;
import com.cloud.desktop.version.dao.DesktopControllerVersionDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmService;
import com.cloud.vm.dao.UserVmDao;

public class DesktopClusterActionWorker {

    protected static final Logger LOGGER = Logger.getLogger(DesktopClusterActionWorker.class);

    protected StateMachine2<DesktopCluster.State, DesktopCluster.Event, DesktopCluster> _stateMachine = DesktopCluster.State.getStateMachine();

    @Inject
    protected CAManager caManager;
    @Inject
    protected ConfigurationDao configurationDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected TemplateApiService templateService;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected UserVmService userVmService;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected AccountService accountService;

    protected DesktopTemplateMapDao desktopTemplateMapDao;
    protected DesktopClusterDao desktopClusterDao;
    protected DesktopClusterVmMapDao desktopClusterVmMapDao;
    protected DesktopClusterIpRangeDao desktopClusterIpRangeDao;
    protected DesktopControllerVersionDao desktopControllerVersionDao;

    protected DesktopCluster desktopCluster;
    protected Account owner;
    protected VirtualMachineTemplate dcTemplate;
    protected VirtualMachineTemplate worksTemplate;
    protected String publicIpAddress;

    protected DesktopClusterActionWorker(final DesktopCluster desktopCluster, final DesktopClusterManagerImpl clusterManager) {
        this.desktopCluster = desktopCluster;
        this.desktopClusterDao = clusterManager.desktopClusterDao;
        this.desktopClusterVmMapDao = clusterManager.desktopClusterVmMapDao;
        this.desktopClusterIpRangeDao = clusterManager.desktopClusterIpRangeDao;
        this.desktopControllerVersionDao = clusterManager.desktopControllerVersionDao;
        this.desktopTemplateMapDao = clusterManager.desktopTemplateMapDao;
    }

    protected void init() {
        final String DC = "dc";
        final String WORKS = "works";
        this.owner = accountDao.findById(desktopCluster.getAccountId());
        List<DesktopTemplateMapVO> templateList = desktopTemplateMapDao.listByVersionId(desktopCluster.getDesktopVersionId());
        for (DesktopTemplateMapVO templateMapVO : templateList) {
            if (templateMapVO.getType().equals(DC)) {
                this.dcTemplate = templateDao.findById(templateMapVO.getTemplateId());
            }
            if (templateMapVO.getType().equals(WORKS)) {
                this.worksTemplate = templateDao.findById(templateMapVO.getTemplateId());
            }
        }
    }

    protected String readResourceFile(String resource) throws IOException {
        return IOUtils.toString(Objects.requireNonNull(Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)), StringUtils.getPreferredCharset());
    }

    protected void logMessage(final Level logLevel, final String message, final Exception e) {
        if (logLevel == Level.INFO) {
            if (LOGGER.isInfoEnabled()) {
                if (e != null) {
                    LOGGER.info(message, e);
                } else {
                    LOGGER.info(message);
                }
            }
        } else if (logLevel == Level.DEBUG) {
            if (LOGGER.isDebugEnabled()) {
                if (e != null) {
                    LOGGER.debug(message, e);
                } else {
                    LOGGER.debug(message);
                }
            }
        } else if (logLevel == Level.WARN) {
            if (e != null) {
                LOGGER.warn(message, e);
            } else {
                LOGGER.warn(message);
            }
        } else {
            if (e != null) {
                LOGGER.error(message, e);
            } else {
                LOGGER.error(message);
            }
        }
    }

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long desktopClusterId, final DesktopCluster.Event event, final Exception e) throws CloudRuntimeException {
        logMessage(logLevel, message, e);
        if (desktopClusterId != null && event != null) {
            stateTransitTo(desktopClusterId, event);
        }
        if (e == null) {
            throw new CloudRuntimeException(message);
        }
        throw new CloudRuntimeException(message, e);
    }

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long desktopClusterId, final DesktopCluster.Event event) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, desktopClusterId, event, null);
    }

    protected void logAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, null);
    }

    protected void logAndThrow(final Level logLevel, final String message, final Exception ex) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, ex);
    }

    protected DesktopClusterVmMapVO addDesktopClusterVm(final long desktopClusterId, final long vmId, final String type) {
        return Transaction.execute(new TransactionCallback<DesktopClusterVmMapVO>() {
            @Override
            public DesktopClusterVmMapVO doInTransaction(TransactionStatus status) {
                DesktopClusterVmMapVO newClusterVmMap = new DesktopClusterVmMapVO(desktopClusterId, vmId, type);
                desktopClusterVmMapDao.persist(newClusterVmMap);
                return newClusterVmMap;
            }
        });
    }

    protected List<DesktopClusterVmMapVO> getDesktopAllVMMaps() {
        List<DesktopClusterVmMapVO> clusterVMs = desktopClusterVmMapDao.listByDesktopClusterId(desktopCluster.getId());
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            clusterVMs.sort((t1, t2) -> (int)((t1.getId() - t2.getId())/Math.abs(t1.getId() - t2.getId())));
        }
        return clusterVMs;
    }

    protected List<UserVm> getDesktopAllVMs() {
        List<UserVm> vmList = new ArrayList<>();
        List<DesktopClusterVmMapVO> clusterVMs = getDesktopAllVMMaps();
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (DesktopClusterVmMapVO vmMap : clusterVMs) {
                vmList.add(userVmDao.findById(vmMap.getVmId()));
            }
        }
        return vmList;
    }

    protected List<DesktopClusterVmMapVO> getControlVMMaps() {
        List<DesktopClusterVmMapVO> clusterVMs = desktopClusterVmMapDao.listByDesktopClusterIdAndNotVmType(desktopCluster.getId(), "desktopvm");
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            clusterVMs.sort((t1, t2) -> (int)((t1.getId() - t2.getId())/Math.abs(t1.getId() - t2.getId())));
        }
        return clusterVMs;
    }

    protected List<UserVm> getControlVMs() {
        List<UserVm> vmList = new ArrayList<>();
        List<DesktopClusterVmMapVO> clusterVMs = getControlVMMaps();
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (DesktopClusterVmMapVO vmMap : clusterVMs) {
                vmList.add(userVmDao.findById(vmMap.getVmId()));
            }
        }
        return vmList;
    }

    protected boolean stateTransitTo(long desktopClusterId, DesktopCluster.Event e) {
        DesktopClusterVO desktopCluster = desktopClusterDao.findById(desktopClusterId);
        try {
            return _stateMachine.transitTo(desktopCluster, e, null, desktopClusterDao);
        } catch (NoTransitionException nte) {
            LOGGER.warn(String.format("Failed to transition state of the desktop cluster : %s in state %s on event %s",
            desktopCluster.getName(), desktopCluster.getState().toString(), e.toString()), nte);
            return false;
        }
    }

    private UserVm fetchControlVmIfMissing(final UserVm controlVm) {
        if (controlVm != null) {
            return controlVm;
        }
        List<DesktopClusterVmMapVO> clusterVMs = desktopClusterVmMapDao.listByDesktopClusterIdAndNotVmType(desktopCluster.getId(), "desktopvm");
        if (CollectionUtils.isEmpty(clusterVMs)) {
            LOGGER.warn(String.format("Unable to retrieve VMs for desktop cluster : %s", desktopCluster.getName()));
            return null;
        }
        List<Long> vmIds = new ArrayList<>();
        for (DesktopClusterVmMapVO vmMap : clusterVMs) {
            vmIds.add(vmMap.getVmId());
        }
        Collections.sort(vmIds);
        return userVmDao.findById(vmIds.get(0));
    }
}
