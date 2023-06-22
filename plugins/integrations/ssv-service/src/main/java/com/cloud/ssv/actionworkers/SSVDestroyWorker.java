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

import java.util.List;

import javax.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Level;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVManagerImpl;
import com.cloud.ssv.SSVNetMapVO;
import com.cloud.ssv.SSVVmMapVO;
import com.cloud.tags.dao.ResourceTagDao;
// import com.cloud.network.Network;
// import com.cloud.network.IpAddress;
// import com.cloud.network.dao.NetworkVO;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;

public class SSVDestroyWorker extends SSVModifierActionWorker {

    @Inject
    protected AccountManager accountManager;
    @Inject
    protected ResourceTagDao resourceTagDao;

    private List<SSVNetMapVO> ssvNets;
    private SSVVmMapVO ssvVm;

    public SSVDestroyWorker(final SSV ssv, final SSVManagerImpl ssvManagerImpl) {
        super(ssv, ssvManagerImpl);
    }

    private void validateSSVState() {
        if (!(ssv.getState().equals(SSV.State.Running)
                || ssv.getState().equals(SSV.State.Stopped)
                || ssv.getState().equals(SSV.State.Alert)
                || ssv.getState().equals(SSV.State.Error)
                || ssv.getState().equals(SSV.State.Destroying))) {
            String msg = String.format("Cannot perform delete operation on ssv : %s in state: %s", ssv.getName(), ssv.getState());
            LOGGER.warn(msg);
            throw new PermissionDeniedException(msg);
        }
    }

    private boolean destroySSV() {
        boolean vmDestroyed = true;
        //shared storage vm removed / ssvnetmap expunged
        if (ssvVm != null) {
            UserVmVO userVM = userVmDao.findById(ssvVm.getVmId());
            if (userVM != null && !userVM.isRemoved()) {
                try {
                    UserVm vm = userVmService.destroyVm(userVM.getId(), true);
                    if (!userVmManager.expunge(userVM)) {
                        LOGGER.warn(String.format("Unable to expunge VM %s : %s, destroying Shared Storage VM will probably fail",
                            vm.getInstanceName() , vm.getUuid()));
                    }
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format("Destroyed VM : %s as part of Shared Storage VM : %s cleanup", vm.getDisplayName(), ssv.getName()));
                    }
                } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                    LOGGER.warn(String.format("Failed to destroy VM : %s part of the Shared Storage VM : %s cleanup. Moving on with destroying remaining resources provisioned for the Shared Storage VM", userVM.getDisplayName(), ssv.getName()), e);
                    return false;
                }
            } else {
                vmDestroyed = true;
            }
        }
        return vmDestroyed;
    }

    // private boolean updateSSVEntryForGC() {
    //     SSVVO ssvVO = ssvDao.findById(ssv.getId());
    //     ssvVO.setCheckForGc(true);
    //     return ssvDao.update(ssv.getId(), ssvVO);
    // }

    // private void deleteSSVNetworkRules() throws ManagementServerException {
    //     NetworkVO network = networkDao.findById(ssv.getNetworkId());
    //     if (network == null) {
    //         return;
    //     }
    //     List<Long> removedVmIds = new ArrayList<>();
    //     if (!CollectionUtils.isEmpty(clusterVMs)) {
    //         for (SSVVmMapVO clusterVM : clusterVMs) {
    //             removedVmIds.add(clusterVM.getVmId());
    //         }
    //     }
    //     IpAddress publicIp = getSourceNatIp(network);
    //     if (publicIp == null) {
    //         throw new ManagementServerException(String.format("No source NAT IP addresses found for network : %s", network.getName()));
    //     }
    //     removeFirewallIngressRule(publicIp);
    //     removeFirewallEgressRule(network);
    //     try {
    //         removePortForwardingRules(publicIp, network, owner, removedVmIds);
    //     } catch (ResourceUnavailableException e) {
    //         // throw new ManagementServerException(String.format("Failed to Shared Storage VM port forwarding rules for network : %s", network.getName()));
    //     }
    // }

    private void validateSSVDestroyed() {
        if(ssv!=null) { // Wait for few seconds to get all VMs really expunged
            final int maxRetries = 3;
            int retryCounter = 0;
            if (ssvVm != null) {
                while (retryCounter < maxRetries) {
                    boolean allVMsRemoved = true;
                    UserVmVO userVM = userVmDao.findById(ssvVm.getVmId());
                    if (userVM != null && !userVM.isRemoved()) {
                        allVMsRemoved = false;
                        break;
                    }
                    if (allVMsRemoved) {
                        ssvVmMapDao.expunge(ssvVm.getId());
                        break;
                    }
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ie) {}
                    retryCounter++;
                }
            }
            if (!CollectionUtils.isEmpty(ssvNets)) {
                for (SSVNetMapVO ssvNet : ssvNets) {
                    ssvNetMapDao.expunge(ssvNet.getId());
                }
            }
        }
    }

    private void checkForRulesToDelete() throws ManagementServerException {
        // NetworkVO ssvNetwork = networkDao.findById(ssv.getNetworkId());
        // if (ssvNetwork != null && ssvNetwork.getGuestType() == Network.GuestType.Isolated) {
        //     // deleteSSVNetworkRules();
        // }
    }

    private boolean destroyClusterIps() {
        boolean ipDestroyed = true;
        // List<SSVIpRangeVO> ipRangeList = ssvIpRangeDao.listBySSVId(ssv.getId());
        //     for (SSVIpRangeVO iprange : ipRangeList) {
        //         boolean deletedIp = ssvIpRangeDao.remove(iprange.getId());
        //         if (!deletedIp) {
        //             logMessage(Level.WARN, String.format("Failed to delete Shared Storage VM ip range : %s", ssv.getName()), null);
        //             return false;
        //         }
        //         if (LOGGER.isInfoEnabled()) {
        //             LOGGER.info(String.format("Shared Storage VM ip range : %s is successfully deleted", ssv.getName()));
        //         }
        //     }
        return ipDestroyed;
    }

    public boolean destroy() throws CloudRuntimeException {
        init();
        validateSSVState();
        this.ssvVm = ssvVmMapDao.listVmBySSVServiceId(ssv.getId());
        this.ssvNets = ssvNetMapDao.listBySSVServiceId(ssv.getId());
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Destroying Shared Storage VM : %s", ssv.getName()));
        }
        stateTransitTo(ssv.getId(), SSV.Event.DestroyRequested);
        boolean vmDestroyed = destroySSV();
        // if there are VM's that were not expunged, we can not delete the network
        if (vmDestroyed) {
            validateSSVDestroyed();
            try {
                checkForRulesToDelete();
            } catch (ManagementServerException e) {
                String msg = String.format("Failed to remove network rules of Shared Storage VM : %s", ssv.getName());
                LOGGER.warn(msg, e);
                // updateSSVEntryForGC();
                throw new CloudRuntimeException(msg, e);
            }
        } else {
            String msg = String.format("Failed to destroy one or more VMs as part of Shared Storage VM : %s cleanup",ssv.getName());
            LOGGER.warn(msg);
            // updateSSVEntryForGC();
            throw new CloudRuntimeException(msg);
        }
        stateTransitTo(ssv.getId(), SSV.Event.OperationSucceeded);
        // Shared Storage VM IP Range remove
        // if (ssv.getAccessType().equals(Network.GuestType.Isolated)) {
        //     boolean ipDestroyed = destroyClusterIps();
        // }
        boolean deleted = ssvDao.remove(ssv.getId());
        if (!deleted) {
            logMessage(Level.WARN, String.format("Failed to delete Shared Storage VM : %s", ssv.getName()), null);
            // updateSSVEntryForGC();
            return false;
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Shared Storage VM : %s is successfully deleted", ssv.getName()));
        }
        return true;
    }
}
