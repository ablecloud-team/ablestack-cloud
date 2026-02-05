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
package org.apache.cloudstack.api.command.user.vm;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SnapshotResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.BooleanUtils;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Dhcp;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.StringUtils;
import com.cloud.vm.VirtualMachine;

@APICommand(name = "deployVirtualMachine", description = "Creates and automatically starts an Instance based on a service offering, disk offering, and Template.", responseObject = UserVmResponse.class, responseView = ResponseView.Restricted, entityType = {VirtualMachine.class},
        requestHasSensitiveInfo = false)
public class DeployVMCmd extends BaseDeployVMCmd {

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @ACL
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class, required = true, description = "The ID of the Service offering for the Instance")
    private Long serviceOfferingId;

    @ACL
    @Parameter(name = ApiConstants.TEMPLATE_ID, type = CommandType.UUID, entityType = TemplateResponse.class, description = "The ID of the Template for the Instance")
    private Long templateId;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, since = "4.21")
    private Long volumeId;

    @Parameter(name = ApiConstants.SNAPSHOT_ID, type = CommandType.UUID, entityType = SnapshotResponse.class, since = "4.21")
    private Long snapshotId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public String getAccountName() {
        if (accountName == null) {
            return CallContext.current().getCallingAccount().getAccountName();
        }
        return accountName;
    }

    public Long getDiskOfferingId() {
        return diskOfferingId;
    }

    public String getDeploymentPlanner() {
        return deploymentPlanner;
    }

    public String getDisplayName() {
        if (StringUtils.isEmpty(displayName)) {
            displayName = name;
        }
        return displayName;
    }

    public Long getDomainId() {
        if (domainId == null) {
            return CallContext.current().getCallingAccount().getDomainId();
        }
        return domainId;
    }

    public ApiConstants.BootType getBootType() {
        if (StringUtils.isNotBlank(bootType)) {
            try {
                String type = bootType.trim().toUpperCase();
                return ApiConstants.BootType.valueOf(type);
            } catch (IllegalArgumentException e) {
                String errMesg = "Invalid bootType " + bootType + "Specified for Instance " + getName()
                        + " Valid values are: " + Arrays.toString(ApiConstants.BootType.values());
                logger.warn(errMesg);
                throw new InvalidParameterValueException(errMesg);
            }
        }
        return null;
    }

    public ApiConstants.BootMode getBootMode() {
        if (StringUtils.isNotBlank(bootMode)) {
            try {
                String mode = bootMode.trim().toUpperCase();
                return ApiConstants.BootMode.valueOf(mode);
            } catch (IllegalArgumentException e) {
                String msg = String.format("Invalid %s: %s specified for Instance: %s. Valid values are: %s",
                        ApiConstants.BOOT_MODE, bootMode, getName(), Arrays.toString(ApiConstants.BootMode.values()));
                logger.error(msg);
                throw new InvalidParameterValueException(msg);
            }
        }
        if (ApiConstants.BootType.UEFI.equals(getBootType())) {
            String msg = String.format("%s must be specified for the Instance with boot type: %s. Valid values are: %s",
                    ApiConstants.BOOT_MODE, getBootType(), Arrays.toString(ApiConstants.BootMode.values()));
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }
        return null;
    }

    public Map<String, String> getVmProperties() {
        Map<String, String> map = new HashMap<>();
        if (MapUtils.isNotEmpty(vAppProperties)) {
            Collection parameterCollection = vAppProperties.values();
            Iterator iterator = parameterCollection.iterator();
            while (iterator.hasNext()) {
                HashMap<String, String> entry = (HashMap<String, String>)iterator.next();
                map.put(entry.get("key"), entry.get("value"));
            }
        }
        return map;
    }

    public Map<Integer, Long> getVmNetworkMap() {
        Map<Integer, Long> map = new HashMap<>();
        if (MapUtils.isNotEmpty(vAppNetworks)) {
            Collection parameterCollection = vAppNetworks.values();
            Iterator iterator = parameterCollection.iterator();
            while (iterator.hasNext()) {
                HashMap<String, String> entry = (HashMap<String, String>) iterator.next();
                Integer nic;
                try {
                    nic = Integer.valueOf(entry.get(VmDetailConstants.NIC));
                } catch (NumberFormatException nfe) {
                    nic = null;
                }
                String networkUuid = entry.get(VmDetailConstants.NETWORK);
                if (logger.isTraceEnabled()) {
                    logger.trace(String.format("nic, '%s', goes on net, '%s'", nic, networkUuid));
                }
                if (nic == null || StringUtils.isEmpty(networkUuid) || _entityMgr.findByUuid(Network.class, networkUuid) == null) {
                    throw new InvalidParameterValueException(String.format("Network ID: %s for NIC ID: %s is invalid", networkUuid, nic));
                }
                map.put(nic, _entityMgr.findByUuid(Network.class, networkUuid).getId());
            }
        }
        return map;
    }

    public String getGroup() {
        return group;
    }

    public HypervisorType getHypervisor() {
        return HypervisorType.getType(hypervisor);
    }

    public Boolean isDisplayVm() {
        return displayVm;
    }

    @Override
    public boolean isDisplay() {
        if(displayVm == null)
            return true;
        else
            return displayVm;
    }

    public List<String> getSecurityGroupNameList() {
        return securityGroupNameList;
    }

    public List<Long> getSecurityGroupIdList() {
        return securityGroupIdList;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    @Override
    public Map<String, String> getDetails() {
        Map<String, String> details = super.getDetails();
        if (volumeId != null) {
            details.put("volumeId", String.valueOf(volumeId));
        }
        return details;
    }

    public boolean isVolumeOrSnapshotProvided() {
        return volumeId != null || snapshotId != null;
    }

    @Override
    public void execute() {
        UserVm result;

        CallContext.current().setEventDetails("Instance ID: " + getEntityUuid());
        if (getStartVm()) {
            try {
                result = _userVmService.startVirtualMachine(this);
            } catch (ResourceUnavailableException ex) {
                logger.warn("Exception: ", ex);
                throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
            } catch (ResourceAllocationException ex) {
                logger.warn("Exception: ", ex);
                throw new ServerApiException(ApiErrorCode.RESOURCE_ALLOCATION_ERROR, ex.getMessage());
            } catch (ConcurrentOperationException ex) {
                logger.warn("Exception: ", ex);
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
            } catch (InsufficientCapacityException ex) {
                StringBuilder message = new StringBuilder(ex.getMessage());
                if (ex instanceof InsufficientServerCapacityException) {
                    if (((InsufficientServerCapacityException)ex).isAffinityApplied()) {
                        message.append(", Please check the affinity groups provided, there may not be sufficient capacity to follow them");
                    }
                }
                logger.info("{}: {}", message.toString(), ex.getLocalizedMessage());
                logger.debug(message.toString(), ex);
                throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, message.toString());
            }
        } else {
            logger.info("Instance {} already created, load UserVm from DB", getEntityUuid());
            result = _userVmService.finalizeCreateVirtualMachine(getEntityId());
        }

        if (result != null) {
            UserVmResponse response = _responseGenerator.createUserVmResponse(getResponseView(), "virtualmachine", result).get(0);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to deploy Instance UUID:"+getEntityUuid());
        }
    }

    @Override
    public void create() throws ResourceAllocationException {
        if (Stream.of(templateId, snapshotId, volumeId).filter(Objects::nonNull).count() != 1) {
            throw new CloudRuntimeException("Please provide only one of the following parameters - template ID, volume ID or snapshot ID");
        }

        try {
            UserVm vm = _userVmService.createVirtualMachine(this);

            if (vm != null) {
                setEntityId(vm.getId());
                setEntityUuid(vm.getUuid());
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to deploy Instance");
            }
        } catch (InsufficientCapacityException ex) {
            logger.info(ex);
            logger.trace(ex.getMessage(), ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        } catch (ResourceUnavailableException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        }  catch (ConcurrentOperationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (ResourceAllocationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_ALLOCATION_ERROR, ex.getMessage());
        }
    }
}
