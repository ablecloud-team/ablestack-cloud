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

package com.cloud.ssv.actionworkers;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.cloudstack.api.command.user.ssv.CreateSSVCmd;
import org.apache.cloudstack.ca.CAManager;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVManagerImpl;
import com.cloud.ssv.SSVVO;
import com.cloud.ssv.dao.SSVDao;
import com.cloud.ssv.dao.SSVNetMapDao;
import com.cloud.ssv.dao.SSVVmMapDao;
import com.cloud.network.Network;
import com.cloud.network.IpAddress;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.server.ManagementService;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;

import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.UserVmService;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

public class SSVActionWorker {

    public static final int NFS_PORT = 8080;
    public static final int ISCSI_PORT = 8081;
    public static final int SAMBA_PORT = 8082;

    protected static final Logger LOGGER = Logger.getLogger(SSVActionWorker.class);

    protected StateMachine2<SSV.State, SSV.Event, SSV> _stateMachine = SSV.State.getStateMachine();

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
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected VolumeApiService volumeApiService;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected AccountService accountService;
    @Inject
    protected ManagementService managementService;
    @Inject
    protected NetworkService networkService;
    @Inject
    protected IPAddressDao ipAddressDao;

    protected SSVDao ssvDao;
    protected SSVVmMapDao ssvVmMapDao;
    protected SSVNetMapDao ssvNetMapDao;

    protected SSV ssv;
    protected Account owner;
    protected VirtualMachineTemplate ssvTemplate;
    protected VirtualMachineTemplate ssvSettingIso;
    protected String publicIpAddress;

    protected SSVActionWorker(final SSV ssv, final SSVManagerImpl ssvManagerImpl) {
        this.ssv = ssv;
        this.ssvDao = ssvManagerImpl.ssvDao;
        this.ssvVmMapDao = ssvManagerImpl.ssvVmMapDao;
        this.ssvNetMapDao = ssvManagerImpl.ssvNetMapDao;
    }

    protected void init() {
        this.owner = accountDao.findById(ssv.getAccountId());
        this.ssvTemplate = templateDao.findByUuid(configurationDao.getValue("cloud.shared.storage.vm.template.uuid"));
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

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long id, final SSV.Event event, final Exception e) throws CloudRuntimeException {
        logMessage(logLevel, message, e);
        if (id != null && event != null) {
            stateTransitTo(id, event);
        }
        if (e == null) {
            throw new CloudRuntimeException(message);
        }
        throw new CloudRuntimeException(message, e);
    }

    protected void logTransitStateAndThrow(final Level logLevel, final String message, final Long id, final SSV.Event event) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, id, event, null);
    }

    protected void logAndThrow(final Level logLevel, final String message) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, null);
    }

    protected void logAndThrow(final Level logLevel, final String message, final Exception ex) throws CloudRuntimeException {
        logTransitStateAndThrow(logLevel, message, null, null, ex);
    }

    protected boolean stateTransitTo(long id, SSV.Event e) {
        SSVVO ssv = ssvDao.findById(id);
        try {
            return _stateMachine.transitTo(ssv, e, null, ssvDao);
        } catch (NoTransitionException nte) {
            LOGGER.warn(String.format("Failed to transition state of the Shared Storage VM : %s in state %s on event %s",
            ssv.getName(), ssv.getState().toString(), e.toString()), nte);
            return false;
        }
    }

    // private UserVm fetchControlVmIfMissing(final UserVm controlVm) {
    //     if (controlVm != null) {
    //         return controlVm;
    //     }
    //     List<SSVVmMapVO> clusterVMs = ssvVmMapDao.listBySSVIdAndNotVmType(ssv.getId(), "desktopvm");
    //     if (CollectionUtils.isEmpty(clusterVMs)) {
    //         LOGGER.warn(String.format("Unable to retrieve VMs for Shared Storage VM : %s", ssv.getName()));
    //         return null;
    //     }
    //     List<Long> vmIds = new ArrayList<>();
    //     for (SSVVmMapVO vmMap : clusterVMs) {
    //         vmIds.add(vmMap.getVmId());
    //     }
    //     Collections.sort(vmIds);
    //     return userVmDao.findById(vmIds.get(0));
    // }

    protected IpAddress getSSVServerIp(CreateSSVCmd cmd) {
        Network network = networkDao.findById(cmd.getNetworkId());
        if (network == null) {
            LOGGER.warn(String.format("Network for Shared Storage VM : %s cannot be found", ssv.getName()));
            return null;
        }
        if (Network.GuestType.Isolated.equals(network.getGuestType())) {
            List<? extends IpAddress> addresses = networkModel.listPublicIpsAssignedToGuestNtwk(network.getId(), true);
            if (CollectionUtils.isEmpty(addresses)) {
                LOGGER.warn(String.format("No public IP addresses found for network : %s, Shared Storage VM : %s", network.getName(), ssv.getName()));
                return null;
            }
            for (IpAddress address : addresses) {
                if (address.isSourceNat()) {
                    return address;
                }
            }
            LOGGER.warn(String.format("No source NAT IP addresses found for network : %s, Shared Storage VM : %s", network.getName(), ssv.getName()));
            return null;
        }
        LOGGER.warn(String.format("Unable to retrieve server IP address for Shared Storage VM : %s", ssv.getName()));
        return null;
    }

}
