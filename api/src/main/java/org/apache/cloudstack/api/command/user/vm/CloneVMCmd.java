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

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import java.util.Optional;
import java.util.List;

@APICommand(name = "cloneVirtualMachine", responseObject = UserVmResponse.class, description = "clone a virtual machine",
        responseView = ResponseObject.ResponseView.Restricted, requestHasSensitiveInfo = false, responseHasSensitiveInfo = true, entityType = {VirtualMachine.class}, since="4.19.0")
public class CloneVMCmd extends BaseAsyncCreateCmd implements UserCmd {
    public static final int MAX_CLONE_COUNT = 50;
    private static final String s_name = "clonevirtualmachineresponse";
    private static final String CLONE_IDENTIFIER = "Clone";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType=UserVmResponse.class,
            required = true, description = "The ID of the virtual machine to clone")
    private Long virtualmachineid;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "name of the cloned virtual machine")
    private String name;

    //Owner information
    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "Compatibility parameter: only the source VM account is supported. Must be used with its domainId; changing ownership is not supported.")
    private String accountName;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "Compatibility parameter: only the source VM domain is supported. Required with account; changing domain is not supported.")
    private Long domainId;

    @Parameter(name = ApiConstants.START_VM, type = CommandType.BOOLEAN, description = "Start the cloned VMs after creation. Optional; defaults to false.")
    private Boolean startVm;

    @Parameter(name = ApiConstants.CLONE_TYPE, type = CommandType.STRING, description = "Clone type: full (default) or linked. Linked requires all source disks on RBD and compatible allocation policies. GFS2 uses its fixed Full copy strategy and accepts only omission or full.")
    private String type;

    @Parameter(name=ApiConstants.COUNT, type=CommandType.INTEGER, description="Number of cloned VMs, from 1 to 50 inclusive. Optional; defaults to 1. Names receive -1 through -count when count is greater than 1.")
    private Integer count;

    @Parameter(name = ApiConstants.ZONE_ID_LIST,
            type=CommandType.LIST,
            collectionType = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "Compatibility parameter: omit or specify only the source VM zone. Cross-zone cloning and multiple target zones are not supported.",
            since = "4.19.0")
    protected List<Long> zoneIds;

    public String getAccountName() {
        return accountName;
    }

    public Long getDomainId() {
        return domainId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getId() {
        return this.virtualmachineid;
    }
    @Override
    public String getEventType() {
        return EventTypes.EVENT_VM_CLONE;
    }

    public List<Long> getZoneIds() {
        return zoneIds;
    }

    public ApiCommandResourceType getInstanceType() {
        return ApiCommandResourceType.VirtualMachine;
    }

    public boolean getStartVm() {
        return startVm == null ? false : startVm;
    }

    public String getType() {
        return type == null ? "full" : type;
    }

    public Integer getCount() {
        return count == null ? 1 : count;
    }

    @Override
    public String getEventDescription() {
        return "Cloning user VM: " + this._uuidMgr.getUuid(VirtualMachine.class, getId());
    }

    @Override
    public void create() throws ResourceAllocationException {
        try {
            // Boolean.valueOf in the shared dispatcher otherwise silently turns
            // malformed values into false. Validate this API without changing others.
            if (getFullUrlParams() != null && getFullUrlParams().containsKey(ApiConstants.START_VM)) {
                String value = getFullUrlParams().get(ApiConstants.START_VM);
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new InvalidParameterValueException("startvm must be true or false");
                }
            }
            if (getFullUrlParams() != null && "".equals(getFullUrlParams().get(ApiConstants.COUNT))) {
                throw new InvalidParameterValueException("count must be an integer from 1 to " + MAX_CLONE_COUNT);
            }
            _userVmService.validateCloneCondition(this);
        }
        catch (ResourceUnavailableException e) {
            logger.warn("Exception: ", e);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, e.getMessage());
        } catch (InvalidParameterValueException e) {
            logger.warn("Exception: ", e);
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, e.getMessage());
        } catch (ServerApiException e) {
            throw new ServerApiException(e.getErrorCode(), e.getDescription());
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public boolean isPublic() {
        return false;
    }

    public String getVMName() {
        if (getName() == null) {
            return getTargetVM().getInstanceName() + "-" + CLONE_IDENTIFIER;
        }
        return getName();
    }

    public String getTemplateName() {
        return (getVMName() + "-" + _uuidMgr.generateUuid(VirtualMachineTemplate.class, null)).substring(0, 32);
    }

    @Override
    public void execute() {
        Optional<UserVm> result;
        try {
            CallContext.current().setEventDetails("Vm Id for clone: " + getEntityId());
            logger.info("Cloning actual VM id: " + getEntityId());
            result = _userVmService.cloneVirtualMachine(this);
        } catch (ResourceUnavailableException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (ConcurrentOperationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
        catch (ResourceAllocationException | InsufficientCapacityException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_ALLOCATION_ERROR, ex.getMessage());
        }
        result.ifPresentOrElse((userVm)-> {
            UserVmResponse response = _responseGenerator.createUserVmResponse(getResponseView(), "virtualmachine", result.get()).get(0);
            response.setResponseName(this.getType() + "_clone");
            setResponseObject(response);
        }, ()-> {
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, "failed to clone VM: " + getId());
        });
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        UserVm vm = this._responseGenerator.findUserVmById(getId());
        if (vm != null) {
            return vm.getAccountId();
        }
        return Account.ACCOUNT_ID_SYSTEM;
    }

    public UserVm getTargetVM() {
        return this._userVmService.getUserVm(getId());
    }
}
