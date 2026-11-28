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
package com.cloud.network.as;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Comparator;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import com.cloud.utils.db.GlobalLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import javax.inject.Inject;

import com.cloud.network.NetworkModel;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.affinity.AffinityGroupVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScalePoliciesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmGroupsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmProfilesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListConditionsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.schedule.ResourceScheduleManager;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.PerformanceMonitorAnswer;
import com.cloud.agent.api.PerformanceMonitorCommand;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.agent.api.routing.GetAutoScaleMetricsAnswer;
import com.cloud.agent.api.routing.GetAutoScaleMetricsCommand;
import com.cloud.agent.api.to.LoadBalancerTO.AutoScalePolicyTO;
import com.cloud.agent.api.to.LoadBalancerTO.AutoScaleVmGroupTO;
import com.cloud.agent.api.to.LoadBalancerTO.AutoScaleVmProfileTO;
import com.cloud.agent.api.to.LoadBalancerTO.ConditionTO;
import com.cloud.agent.api.to.LoadBalancerTO.CounterTO;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.dispatch.DispatchChainFactory;
import com.cloud.api.dispatch.DispatchTask;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceInUseException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.as.AutoScaleCounter.AutoScaleCounterParam;
import com.cloud.network.as.dao.AutoScalePolicyConditionMapDao;
import com.cloud.network.as.dao.AutoScalePolicyDao;
import com.cloud.network.as.dao.AutoScaleVmGroupDao;
import com.cloud.network.as.dao.AutoScaleVmGroupPolicyMapDao;
import com.cloud.network.as.dao.AutoScaleVmGroupStatisticsDao;
import com.cloud.network.as.dao.AutoScaleVmGroupVmMapDao;
import com.cloud.network.as.dao.AutoScaleVmProfileDao;
import com.cloud.network.as.dao.ConditionDao;
import com.cloud.network.as.dao.CounterDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.router.RouterControlHelper;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.router.VirtualRouterAutoScale.AutoScaleMetrics;
import com.cloud.network.router.VirtualRouterAutoScale.AutoScaleMetricsValue;
import com.cloud.network.router.VirtualRouterAutoScale.AutoScaleValueType;
import com.cloud.network.router.VirtualRouterAutoScale.VirtualRouterAutoScaleCounter;
import com.cloud.offering.DiskOffering;
import com.cloud.network.security.SecurityGroup;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.server.ResourceTag;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VmStorageSelectionManager;
import com.cloud.vm.VmDiskInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.template.TemplateManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDao;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.VmStats;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class AutoScaleManagerImpl extends ManagerBase implements AutoScaleManager, AutoScaleService, Configurable {
    @Inject
    private AutoScaleCreationRequestManager creationRequests;

    @Override
    public org.apache.cloudstack.api.response.AutoScaleCreationRequestResponse queryAutoScaleCreationRequest(String requestId) {
        return creationRequests.query(requestId);
    }


    @Inject
    protected DispatchChainFactory dispatchChainFactory = null;
    @Inject
    EntityManager entityMgr;
    @Inject
    AccountDao accountDao;
    @Inject
    AccountManager accountMgr;
    @Inject
    ConfigurationManager configMgr;
    @Inject
    TemplateManager templateMgr;
    @Inject
    TemplateDataStoreDao autoScaleTemplateStoreDao;
    @Inject
    LoadBalancingRulesManager lbRulesMgr;
    @Inject
    NetworkDao networkDao;
    @Inject
    CounterDao counterDao;
    @Inject
    ConditionDao conditionDao;
    @Inject
    LoadBalancerDao lbDao;
    @Inject
    AutoScaleVmProfileDao autoScaleVmProfileDao;
    @Inject
    AutoScalePolicyDao autoScalePolicyDao;
    @Inject
    AutoScalePolicyConditionMapDao autoScalePolicyConditionMapDao;
    @Inject
    AutoScaleVmGroupDao autoScaleVmGroupDao;
    @Inject
    AutoScaleVmGroupPolicyMapDao autoScaleVmGroupPolicyMapDao;
    @Inject
    AutoScaleVmGroupVmMapDao autoScaleVmGroupVmMapDao;
    @Inject
    DataCenterDao dcDao = null;
    @Inject
    UserDao userDao;
    @Inject
    ConfigurationDao configDao;
    @Inject
    IPAddressDao ipAddressDao;
    @Inject
    AccountService accountService;
    @Inject
    UserVmService userVmService;
    @Inject
    LoadBalancerVMMapDao lbVmMapDao;
    @Inject
    LoadBalancingRulesService loadBalancingRulesService;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    NetworkOrchestrationService networkMgr;
    @Inject
    NetworkModel networkModel;
    @Inject
    private UserVmManager userVmMgr;
    @Inject
    private UserDataManager userDataMgr;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AutoScaleVmGroupStatisticsDao asGroupStatisticsDao;
    @Inject
    private DomainRouterDao routerDao;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    protected RouterControlHelper routerControlHelper;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private SSHKeyPairDao sshKeyPairDao;
    @Inject
    private AffinityGroupDao affinityGroupDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    GuestOSDao guestOSDao;
    @Inject
    private ResourceScheduleManager resourceScheduleManager;

    @Inject
    private VmStorageSelectionManager vmStorageSelectionManager;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    private static final String PARAM_ROOT_STORAGE_ID = "rootstorageid";
    private static final String PARAM_DATA_STORAGE_ID = "datastorageid";
    private static final long DATA_DISK_DEVICE_ID = 1L;
    private static final String PARAM_ROOT_DISK_SIZE = "rootdisksize";
    private static final String PARAM_DISK_OFFERING_ID = "diskofferingid";
    private static final String PARAM_DISK_SIZE = "disksize";
    private static final String PARAM_OVERRIDE_DISK_OFFERING_ID = "overridediskofferingid";
    private static final String PARAM_SECURITY_GROUP_IDS = "securitygroupids";
    private static final String PARAM_SSH_KEYPAIRS = "keypairs";
    private static final String PARAM_AFFINITY_GROUP_IDS = "affinitygroupids";
    private static final String PARAM_NETWORK_IDS = "networkids";

    private static final List<String> supportedDeployParams = Arrays.asList(PARAM_ROOT_DISK_SIZE, PARAM_DISK_OFFERING_ID, PARAM_DISK_SIZE, PARAM_SECURITY_GROUP_IDS,
            PARAM_OVERRIDE_DISK_OFFERING_ID, PARAM_SSH_KEYPAIRS, PARAM_AFFINITY_GROUP_IDS, PARAM_NETWORK_IDS, PARAM_ROOT_STORAGE_ID, PARAM_DATA_STORAGE_ID);

    protected static final String VM_HOSTNAME_PREFIX = "autoScaleVm-";
    protected static final int VM_HOSTNAME_RANDOM_SUFFIX_LENGTH = 6;

    // Windows OS has a limit of 15 characters for hostname
    // https://learn.microsoft.com/en-us/troubleshoot/windows-server/active-directory/naming-conventions-for-computer-domain-site-ou
    protected static final String WINDOWS_VM_HOSTNAME_PREFIX = "as-WinVm-";

    private static final Long DEFAULT_HOST_ID = -1L;

    ExecutorService groupExecutor;


    Map<Long, ScheduledExecutorService> vmGroupMonitorMaps = new ConcurrentHashMap<>();

    @Override
    public boolean start() {
        // create thread pool and blocking queue
        final int workersCount = AutoScaleStatsWorker.value();
        groupExecutor = Executors.newFixedThreadPool(workersCount);
        logger.info("AutoScale Manager created a thread pool to check autoscale vm groups. The pool size is : " + workersCount);

        logger.info("AutoScale Manager created a blocking queue to check autoscale vm groups. The queue size is : " + workersCount);


        scheduleMonitorTasks();

        return true;
    }

    @Override
    public boolean stop() {
        if (groupExecutor != null) {
            vmGroupMonitorMaps.values().forEach(ScheduledExecutorService::shutdown);
        vmGroupMonitorMaps.clear();
        groupExecutor.shutdown();
        }
        return true;
    }

    public List<AutoScaleCounter> getSupportedAutoScaleCounters(long networkid) {
        String capability = lbRulesMgr.getLBCapability(networkid, Capability.AutoScaleCounters.getName());
        if (capability == null) {
            return null;
        }
        Gson gson = new Gson();
        java.lang.reflect.Type listType = new TypeToken<List<AutoScaleCounter>>() {
        }.getType();
        List<AutoScaleCounter> result = gson.fromJson(capability, listType);
        return result;
    }

    public void validateNetworkCapability(long networkId) {
        Network network = networkDao.findById(networkId);
        if (network == null) {
            throw new CloudRuntimeException(String.format("Unable to find network with id: %s ", networkId));
        }
        NetworkOffering offering = networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (!offering.isSupportsVmAutoScaling()) {
            throw new InvalidParameterValueException("Vm AutoScaling is not supported by this network.");
        }
    }

    public void validateAutoScaleCounters(long networkid, List<Counter> counters, List<Pair<String, String>> counterParamPassed) {
        List<AutoScaleCounter> supportedCounters = getSupportedAutoScaleCounters(networkid);
        if (supportedCounters == null) {
            throw new InvalidParameterValueException("AutoScale is not supported in the network");
        }
        for (Counter counter : counters) {
            String counterName = counter.getSource().name();
            boolean isCounterSupported = false;
            for (AutoScaleCounter autoScaleCounter : supportedCounters) {
                if (autoScaleCounter.getName().equals(counterName)) {
                    isCounterSupported = true;
                    List<AutoScaleCounterParam> counterParams = autoScaleCounter.getParamList();
                    for (AutoScaleCounterParam autoScaleCounterParam : counterParams) {
                        boolean isRequiredParameter = autoScaleCounterParam.getRequired();
                        if (isRequiredParameter) {
                            boolean isRequiredParamPresent = false;
                            for (Pair<String, String> pair : counterParamPassed) {
                                if (pair.first().equals(autoScaleCounterParam.getParamName()))
                                    isRequiredParamPresent = true;

                            }
                            if (!isRequiredParamPresent) {
                                throw new InvalidParameterValueException("Parameter " + autoScaleCounterParam.getParamName() + " has to be set in AutoScaleVmProfile's " +
                                    ApiConstants.COUNTERPARAM_LIST);
                            }
                        }
                    }
                    break;
                }
            }
            if (!isCounterSupported) {
                throw new InvalidParameterValueException("AutoScale counter with source='" + counter.getSource().name() + "' is not supported " + "in the network");
            }
        }
    }

    private <VO extends ControlledEntity> VO getEntityInDatabase(Account caller, String paramName, Long id, GenericDao<VO, Long> dao) {

        VO vo = dao.findById(id);

        if (vo == null) {
            throw new InvalidParameterValueException("Unable to find " + paramName);
        }

        accountMgr.checkAccess(caller, null, false, (ControlledEntity)vo);

        return vo;
    }

    private boolean isAutoScaleScaleUpPolicy(AutoScalePolicy policyVO) {
        return policyVO.getAction().equals(AutoScalePolicy.Action.SCALEUP);
    }

    private List<AutoScalePolicyVO> getAutoScalePolicies(List<Long> policyIds, List<Counter> counters, int interval, boolean scaleUpPolicies) {
        SearchBuilder<AutoScalePolicyVO> policySearch = autoScalePolicyDao.createSearchBuilder();
        policySearch.and("ids", policySearch.entity().getId(), Op.IN);
        policySearch.done();
        SearchCriteria<AutoScalePolicyVO> sc = policySearch.create();

        sc.setParameters("ids", policyIds.toArray(new Object[0]));
        List<AutoScalePolicyVO> policies = autoScalePolicyDao.search(sc, null);

        for (AutoScalePolicyVO policy : policies) {
            int duration = policy.getDuration();
            if (duration < interval) {
                throw new InvalidParameterValueException("duration : " + duration + " specified in a policy cannot be less than vm group's interval : " + interval);
            }

            if (scaleUpPolicies) {
                if (!isAutoScaleScaleUpPolicy(policy)) {
                    throw new InvalidParameterValueException("Only scaleup policies can be specified in scaleuppolicyids");
                }
            } else {
                if (isAutoScaleScaleUpPolicy(policy)) {
                    throw new InvalidParameterValueException("Only scaledown policies can be specified in scaledownpolicyids");
                }
            }
            List<AutoScalePolicyConditionMapVO> policyConditionMapVOs = autoScalePolicyConditionMapDao.listByAll(policy.getId(), null);
            for (AutoScalePolicyConditionMapVO policyConditionMapVO : policyConditionMapVOs) {
                long conditionid = policyConditionMapVO.getConditionId();
                Condition condition = conditionDao.findById(conditionid);
                Counter counter = counterDao.findById(condition.getCounterId());
                counters.add(counter);
            }
        }
        return policies;
    }

    @DB
    protected AutoScaleVmProfileVO checkValidityAndPersist(AutoScaleVmProfileVO vmProfile, boolean isNew) {
        long templateId = vmProfile.getTemplateId();
        Long autoscaleUserId = vmProfile.getAutoScaleUserId();
        int expungeVmGracePeriod = vmProfile.getExpungeVmGracePeriod();

        Long serviceOfferingId = vmProfile.getServiceOfferingId();
        if (serviceOfferingId != null) {
            ServiceOffering serviceOffering = entityMgr.findByIdIncludingRemoved(ServiceOffering.class, serviceOfferingId);
            if (serviceOffering == null) {
                throw new InvalidParameterValueException("Unable to find service offering by id");
            }
            if (serviceOffering.isDynamic()) {
                throw new InvalidParameterValueException("Unable to use dynamic service offering in AutoScale vm profile");
            }
        }

        VirtualMachineTemplate template = entityMgr.findById(VirtualMachineTemplate.class, templateId);
        // Make sure a valid template ID was specified
        if (template == null) {
            throw new InvalidParameterValueException("Unable to use the given template.");
        }

        if (expungeVmGracePeriod < 0) {
            throw new InvalidParameterValueException("Expunge Vm Grace Period cannot be less than 0.");
        }

        if (!isNew) {
            List<AutoScaleVmGroupVO> vmGroups = autoScaleVmGroupDao.listByProfile(vmProfile.getId());
            for (AutoScaleVmGroupVO vmGroup : vmGroups) {
                Network.Provider provider = getLoadBalancerServiceProvider(vmGroup.getLoadBalancerId());
                if (Network.Provider.Netscaler.equals(provider)) {
                    rejectNetScalerStorage(vmProfile);
                    checkAutoScaleUser(autoscaleUserId, vmProfile.getAccountId());
                }
            }
        }

        Integer autoScaleStatsInterval = AutoScaleStatsInterval.value();
        if (autoScaleStatsInterval <= 0) {
            throw new InvalidParameterValueException(String.format("Global setting %s has to be set to larger than 0", AutoScaleStatsInterval.key()));
        }

        List<Pair<String, String>> otherDeployParams = vmProfile.getOtherDeployParamsList();
        if (CollectionUtils.isNotEmpty(otherDeployParams)) {
            Set<String> names = new HashSet<>();
            for (Pair<String, String> kvPair : otherDeployParams) {
                if (!names.add(kvPair.first())) {
                    throw new InvalidParameterValueException("Duplicate AutoScale deploy parameter: " + kvPair.first());
                }
                String key = kvPair.first();
                if (!supportedDeployParams.contains(key)) {
                    throw new InvalidParameterValueException(String.format("Unsupported otherdeployparams key: %s. Supported values are %s", key, supportedDeployParams));
                }
            }
        }
        Account owner = accountMgr.getAccount(vmProfile.getAccountId());
        DataCenter zone = entityMgr.findById(DataCenter.class, vmProfile.getZoneId());
        ServiceOffering offering = entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (owner == null || zone == null || offering == null) {
            throw new InvalidParameterValueException("AutoScale profile owner, zone and service offering must exist");
        }
        validateNewAutoScaleProfileSources(owner, zone, offering, template);
        validateAutoScaleDeployParams(vmProfile, owner, zone, offering);
        Map<String, String> deployParams = getDeployParams(vmProfile.getOtherDeployParams());
        if (hasSelectedStorage(deployParams)) {
            if (isNew) {
                checkStorageSelectionPermission();
            }
            Map<Long, Long> storage = resolveSelectedStorage(deployParams, zone.getId());
            List<VmDiskInfo> dataDisks = selectedDataDisk(deployParams, storage);
            Long rootOfferingId = getVmOverrideDiskOfferingId(deployParams);
            Long rootSize = deployParams.containsKey(PARAM_ROOT_DISK_SIZE) ? Long.valueOf(deployParams.get(PARAM_ROOT_DISK_SIZE)) : null;
            vmStorageSelectionManager.validateAutoScaleStorage(storage, zone, owner, offering, template,
                    rootOfferingId != null ? rootOfferingId : Long.valueOf(offering.getDiskOfferingId()), rootSize, dataDisks);
        }
        vmProfile = autoScaleVmProfileDao.persist(vmProfile);

        return vmProfile;
    }

    protected void validateAutoScaleDeployParams(AutoScaleVmProfileVO profile, Account owner, DataCenter zone, ServiceOffering offering) {
        Map<String, String> params = getDeployParams(profile.getOtherDeployParams());
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (StringUtils.isBlank(value) || value.contains("&") || value.contains("=")) {
                throw new InvalidParameterValueException("Invalid AutoScale deploy parameter value for " + key);
            }
            if (PARAM_ROOT_DISK_SIZE.equals(key) || PARAM_DISK_SIZE.equals(key)) {
                try {
                    if (!value.matches("[0-9]+") || Long.parseLong(value) <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new InvalidParameterValueException(key + " must be a positive integer in GiB");
                }
            } else if (PARAM_DISK_OFFERING_ID.equals(key) || PARAM_OVERRIDE_DISK_OFFERING_ID.equals(key)) {
                DiskOffering disk = diskOfferingDao.findByUuid(value);
                if (disk == null || disk.getState() == DiskOffering.State.Inactive) {
                    throw new InvalidParameterValueException("AutoScale deploy disk offering must exist and be active");
                }
                accountMgr.checkAccess(owner, disk, zone);
                if (PARAM_OVERRIDE_DISK_OFFERING_ID.equals(key) && offering.getDiskOfferingStrictness()) {
                    throw new InvalidParameterValueException("Cannot override the disk offering of a strict service offering");
                }
            } else if (!PARAM_ROOT_STORAGE_ID.equals(key) && !PARAM_DATA_STORAGE_ID.equals(key)) {
                Set<String> unique = new HashSet<>();
                for (String item : value.split(",", -1)) {
                    if (StringUtils.isBlank(item) || !item.equals(item.trim()) || !unique.add(item)) {
                        throw new InvalidParameterValueException("AutoScale deploy parameter list must contain distinct non-empty values: " + key);
                    }
                    if (PARAM_NETWORK_IDS.equals(key)) {
                        Network network = networkDao.findByUuid(item);
                        if (network == null || network.getDataCenterId() != zone.getId()) {
                            throw new InvalidParameterValueException("AutoScale deploy network must exist in the profile zone");
                        }
                        accountMgr.checkAccess(owner, AccessType.UseEntry, false, network);
                    } else if (PARAM_SSH_KEYPAIRS.equals(key)) {
                        if (sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), item) == null) {
                            throw new InvalidParameterValueException("AutoScale SSH keypair must belong to the profile owner");
                        }
                    } else if (PARAM_AFFINITY_GROUP_IDS.equals(key)) {
                        AffinityGroupVO affinity = affinityGroupDao.findByUuid(item);
                        if (affinity == null) {
                            throw new InvalidParameterValueException("AutoScale affinity group must exist");
                        }
                        accountMgr.checkAccess(owner, AccessType.UseEntry, false, affinity);
                    } else if (PARAM_SECURITY_GROUP_IDS.equals(key)) {
                        SecurityGroup securityGroup = entityMgr.findByUuid(SecurityGroup.class, item);
                        if (securityGroup == null) {
                            throw new InvalidParameterValueException("AutoScale security group must exist");
                        }
                        accountMgr.checkAccess(owner, AccessType.UseEntry, false, securityGroup);
                    }
                }
            }
        }
        if (params.containsKey(PARAM_DISK_SIZE) && !params.containsKey(PARAM_DISK_OFFERING_ID)) {
            throw new InvalidParameterValueException("AutoScale disksize requires a data disk offering");
        }
        if (params.containsKey(PARAM_DISK_OFFERING_ID)) {
            DiskOffering dataDisk = diskOfferingDao.findByUuid(params.get(PARAM_DISK_OFFERING_ID));
            if (dataDisk.isCustomized() && !params.containsKey(PARAM_DISK_SIZE)) {
                throw new InvalidParameterValueException("AutoScale custom data disk offering requires disksize");
            }
            if (!dataDisk.isCustomized() && params.containsKey(PARAM_DISK_SIZE)) {
                throw new InvalidParameterValueException("AutoScale disksize is only supported for a custom data disk offering");
            }
        }
    }

    private boolean hasSelectedStorage(Map<String, String> params) {
        return params.containsKey(PARAM_ROOT_STORAGE_ID) || params.containsKey(PARAM_DATA_STORAGE_ID);
    }

    private void checkStorageSelectionPermission() {
        if (!accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
            throw new PermissionDeniedException("Direct AutoScale primary storage selection requires administrator permission");
        }
    }

    private Map<Long, Long> resolveSelectedStorage(Map<String, String> params, long zoneId) {
        Map<Long, Long> storage = new HashMap<>();
        addSelectedStorage(storage, 0L, params.get(PARAM_ROOT_STORAGE_ID), zoneId);
        addSelectedStorage(storage, DATA_DISK_DEVICE_ID, params.get(PARAM_DATA_STORAGE_ID), zoneId);
        return storage;
    }

    private void addSelectedStorage(Map<Long, Long> storage, long deviceId, String uuid, long zoneId) {
        if (uuid == null) {
            return;
        }
        StoragePoolVO pool = primaryDataStoreDao.findByUuid(uuid);
        if (pool == null || pool.getDataCenterId() != zoneId || pool.getStatus() != StoragePoolStatus.Up) {
            throw new InvalidParameterValueException("Selected primary storage is unavailable or outside the AutoScale profile zone");
        }
        storage.put(deviceId, pool.getId());
    }

    private List<VmDiskInfo> selectedDataDisk(Map<String, String> params, Map<Long, Long> storage) {
        if (!storage.containsKey(DATA_DISK_DEVICE_ID)) {
            return null;
        }
        String offeringUuid = params.get(PARAM_DISK_OFFERING_ID);
        DiskOffering disk = offeringUuid == null ? null : diskOfferingDao.findByUuid(offeringUuid);
        if (disk == null || disk.getState() == DiskOffering.State.Inactive) {
            throw new InvalidParameterValueException("AutoScale data storage requires an active data disk offering");
        }
        return Collections.singletonList(new VmDiskInfo(disk, getVmDataDiskSize(params), null, null, DATA_DISK_DEVICE_ID));
    }

    private void rejectNetScalerStorage(AutoScaleVmProfileVO profile) {
        if (hasSelectedStorage(getDeployParams(profile.getOtherDeployParams()))) {
            throw new InvalidParameterValueException("AutoScale primary storage selection is not supported by NetScaler");
        }
    }

    @Override
    public void checkAutoScaleUser(Long autoscaleUserId, long accountId) {
        if (autoscaleUserId == null) {
            throw new InvalidParameterValueException("autoscaleuserid is required but not passed");
        }
        User user = userDao.findById(autoscaleUserId);
        if (user == null) {
            throw new InvalidParameterValueException("Unable to find autoscale user id: " + autoscaleUserId);
        }

        if (user.getAccountId() != accountId) {
            throw new InvalidParameterValueException("AutoScale User id does not belong to the same account");
        }

        ApiKeyPairVO latestKeypair = ApiDBUtils.searchForLatestUserKeyPair(user.getId());

        if (latestKeypair == null) {
            throw new InvalidParameterValueException(String.format("No API keypair for user [%s]. Please generate it.", user.getUsername()));
        }

        ApiServiceConfiguration.validateEndpointUrl();
    }
    protected void validateNewAutoScaleProfileSources(Account owner, DataCenter zone,
            ServiceOffering offering, VirtualMachineTemplate template) {
        if (offering.isDynamic() || offering.getRemoved() != null || offering.getState() == ServiceOffering.State.Inactive) {
            throw new InvalidParameterValueException("AutoScale requires an active fixed service offering");
        }
        if (template.getState() != VirtualMachineTemplate.State.Active || template.getFormat() == ImageFormat.ISO
                || template.getTemplateType() == TemplateType.SYSTEM || template.getTemplateType() == TemplateType.ROUTING
                || template.getTemplateType() == TemplateType.DATADISK || template.getTemplateType() == TemplateType.ISODISK) {
            throw new InvalidParameterValueException("AutoScale requires a user VM template, not an ISO or system VM template");
        }
        accountMgr.checkAccess(owner, offering, zone);
        accountMgr.checkAccess(owner, AccessType.UseEntry, false, template);
        boolean directDownload = template instanceof VMTemplateVO && ((VMTemplateVO)template).isDirectDownload();
        if (!directDownload && template.getTemplateType() != TemplateType.PERHOST
                && autoScaleTemplateStoreDao.findByTemplateZoneReady(template.getId(), zone.getId()) == null) {
            throw new InvalidParameterValueException("AutoScale template is not ready in the selected zone");
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMPROFILE_CREATE, eventDescription = "creating autoscale vm profile", create = true)
    public AutoScaleVmProfile createAutoScaleVmProfile(CreateAutoScaleVmProfileCmd cmd) {
        if (cmd.getInjectedJobId() == null) {
            return createAutoScaleVmProfileInternal(cmd);
        }
        return creationRequests.create(cmd, AutoScaleVmProfileVO.class, () -> createAutoScaleVmProfileInternal(cmd));
    }

    protected AutoScaleVmProfile createAutoScaleVmProfileInternal(CreateAutoScaleVmProfileCmd cmd) {

        Account caller = CallContext.current().getCallingAccount();
        Account owner = accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        accountMgr.checkAccess(caller, null, true, owner);

        long zoneId = cmd.getZoneId();
        long serviceOfferingId = cmd.getServiceOfferingId();
        Long autoscaleUserId = cmd.getAutoscaleUserId();

        DataCenter zone = entityMgr.findById(DataCenter.class, zoneId);

        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id");
        }

        ServiceOffering serviceOffering = entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering by id");
        }

        VirtualMachineTemplate template = entityMgr.findById(VirtualMachineTemplate.class, cmd.getTemplateId());
        if (template == null) {
            throw new InvalidParameterValueException("Unable to find template by id " + cmd.getTemplateId());
        }
        if (HypervisorType.External.equals(template.getHypervisorType())) {
            logger.error("Cannot create AutoScale Vm Profile with {} as it is an {} hypervisor template", template, HypervisorType.External);
            throw new InvalidParameterValueException(String.format("Unable to create AutoScale Vm Profile with template: %s", template.getName()));
        }

        validateNewAutoScaleProfileSources(owner, zone, serviceOffering, template);

        // validations
        HashMap<String, String> deployParams = cmd.getDeployParamMap();
        /*
         * Just for making sure the values are right in other deploy params.
         * For ex. if projectId is given as a string instead of an long value, this
         * will be throwing an error.
         */
        // The generic VM dispatch ACL checks the personal caller, even when a
        // template is shared exclusively with the profile's project account.
        // Project creation uses the owner-scoped source and deploy-parameter
        // validation in checkValidityAndPersist; it must not widen launch access.
        if (owner.getType() != Account.Type.PROJECT) {
            dispatchChainFactory.getStandardDispatchChain().dispatch(new DispatchTask(ComponentContext.inject(DeployVMCmd.class), deployParams));
        }

        AutoScaleVmProfileVO profileVO =
            new AutoScaleVmProfileVO(cmd.getZoneId(), owner.getDomainId(), owner.getAccountId(), cmd.getServiceOfferingId(), cmd.getTemplateId(), cmd.getOtherDeployParams(),
                cmd.getCounterParamList(), cmd.getUserData(), cmd.getExpungeVmGracePeriod(), autoscaleUserId);

        if (cmd.getDisplay() != null) {
            profileVO.setDisplay(cmd.getDisplay());
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserDataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserDataDetails())) {
            userDataDetails = cmd.getUserDataDetails().toString();
        }
        userData = userVmMgr.finalizeUserData(userData, userDataId, template);
        userData = userDataMgr.validateUserData(userData, cmd.getHttpMethod());
        if (userData != null) {
            profileVO.setUserData(userData);
        }
        if (userDataId != null) {
            profileVO.setUserDataId(userDataId);
        }
        if (userDataDetails != null) {
            profileVO.setUserDataDetails(userDataDetails);
        }

        profileVO = checkValidityAndPersist(profileVO, true);
        logger.info("Successfully create AutoScale Vm Profile: {}", profileVO);

        return profileVO;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMPROFILE_UPDATE, eventDescription = "updating autoscale vm profile")
    public AutoScaleVmProfile updateAutoScaleVmProfile(UpdateAutoScaleVmProfileCmd cmd) {
        List<Long> groupIds = autoScaleVmGroupDao.listByAll(null, cmd.getId()).stream().map(AutoScaleVmGroupVO::getId).collect(Collectors.toList());
        return withAssociatedGroupControls(groupIds, () -> updateAutoScaleVmProfileInternal(cmd));
    }

    protected AutoScaleVmProfile updateAutoScaleVmProfileInternal(UpdateAutoScaleVmProfileCmd cmd) {
        Long profileId = cmd.getId();
        Long serviceOfferingId = cmd.getServiceOfferingId();
        Long templateId = cmd.getTemplateId();
        Long autoscaleUserId = cmd.getAutoscaleUserId();
        Map<String, HashMap<String, String>> otherDeployParams = cmd.getOtherDeployParams();
        Map counterParamList = cmd.getCounterParamList();
        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserDataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserDataDetails())) {
            userDataDetails = cmd.getUserDataDetails().toString();
        }
        boolean userdataUpdate = userData != null || userDataId != null || MapUtils.isNotEmpty(cmd.getUserDataDetails());

        Integer expungeVmGracePeriod = cmd.getExpungeVmGracePeriod();

        AutoScaleVmProfileVO vmProfile = getEntityInDatabase(CallContext.current().getCallingAccount(), "Auto Scale Vm Profile", profileId, autoScaleVmProfileDao);

        boolean physicalParameterUpdate = (serviceOfferingId != null || templateId != null || autoscaleUserId != null || counterParamList != null
                || otherDeployParams != null || cmd.isClearOtherDeployParams() || cmd.isClearCounterParams() || expungeVmGracePeriod != null || userdataUpdate);

        if (serviceOfferingId != null) {
            vmProfile.setServiceOfferingId(serviceOfferingId);
        }

        if (templateId != null) {
            vmProfile.setTemplateId(templateId);
        }

        if (autoscaleUserId != null) {
            vmProfile.setAutoscaleUserId(autoscaleUserId);
        }

        if ((cmd.isClearOtherDeployParams() && MapUtils.isNotEmpty(otherDeployParams))
                || (cmd.isClearCounterParams() && MapUtils.isNotEmpty(counterParamList))) {
            throw new InvalidParameterValueException("Cannot clear and replace the same profile parameters in one request");
        }
        if (cmd.isClearOtherDeployParams()) {
            vmProfile.setOtherDeployParams(null);
        } else if (otherDeployParams != null) {
            vmProfile.setOtherDeployParamsForUpdate(otherDeployParams);
            if (hasSelectedStorage(getDeployParams(vmProfile.getOtherDeployParams()))) {
                checkStorageSelectionPermission();
            }
        }
        if (cmd.isClearCounterParams()) {
            vmProfile.setCounterParams(null);
        } else if (counterParamList != null) {
            vmProfile.setCounterParamsForUpdate(counterParamList);
        }

        if (expungeVmGracePeriod != null) {
            vmProfile.setExpungeVmGracePeriod(expungeVmGracePeriod);
        }

        if (cmd.getCustomId() != null) {
            vmProfile.setUuid(cmd.getCustomId());
        }

        if (cmd.getDisplay() != null) {
            vmProfile.setDisplay(cmd.getDisplay());
        }

        if (userdataUpdate) {
            if (userData == null && userDataId == null) {
                userDataId = vmProfile.getUserDataId();
                userData = userDataId == null ? vmProfile.getUserData() : null;
            }
            if (templateId == null) {
                templateId = vmProfile.getTemplateId();
            }
            VirtualMachineTemplate template = entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId);
            userData = userVmMgr.finalizeUserData(userData, userDataId, template);
            userData = userDataMgr.validateUserData(userData, cmd.getHttpMethod());
            vmProfile.setUserDataId(userDataId);
            vmProfile.setUserData(userData);
            vmProfile.setUserDataDetails(userDataDetails);
        }

        List<AutoScaleVmGroupVO> vmGroupList = autoScaleVmGroupDao.listByAll(null, profileId);
        for (AutoScaleVmGroupVO vmGroupVO : vmGroupList) {
            if (physicalParameterUpdate && !vmGroupVO.getState().equals(AutoScaleVmGroup.State.DISABLED)) {
                throw new InvalidParameterValueException("The AutoScale Vm Profile can be updated only if the Vm Group it is associated with is disabled in state");
            }
        }

        vmProfile = checkValidityAndPersist(vmProfile, false);
        logger.info("Updated Auto Scale Vm Profile: {}", vmProfile);

        return vmProfile;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMPROFILE_DELETE, eventDescription = "deleting autoscale vm profile")
    public boolean deleteAutoScaleVmProfile(long id) {
        /* Check if entity is in database */
        AutoScaleVmProfileVO vmProfile = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Vm Profile", id, autoScaleVmProfileDao);
        if (autoScaleVmGroupDao.isProfileInUse(id)) {
            throw new InvalidParameterValueException("Cannot delete AutoScale Vm Profile when it is in use by one more vm groups");
        }

        boolean success = autoScaleVmProfileDao.remove(id);
        if (success) {
            logger.info("Successfully deleted AutoScale Vm Profile: {}", vmProfile);
        }
        return success;
    }

    @Override
    public List<? extends AutoScaleVmProfile> listAutoScaleVmProfiles(ListAutoScaleVmProfilesCmd cmd) {
        Long id = cmd.getId();
        Long templateId = cmd.getTemplateId();
        String otherDeployParams = cmd.getOtherDeployParams();
        Long serviceOffId = cmd.getServiceOfferingId();
        Long zoneId = cmd.getZoneId();
        Boolean display = cmd.getDisplay();

        SearchWrapper<AutoScaleVmProfileVO> searchWrapper = new SearchWrapper<>(autoScaleVmProfileDao, AutoScaleVmProfileVO.class, cmd, cmd.getId());
        SearchBuilder<AutoScaleVmProfileVO> sb = searchWrapper.getSearchBuilder();

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("templateId", sb.entity().getTemplateId(), SearchCriteria.Op.EQ);
        sb.and("serviceOfferingId", sb.entity().getServiceOfferingId(), SearchCriteria.Op.EQ);
        sb.and("otherDeployParams", sb.entity().getOtherDeployParams(), SearchCriteria.Op.LIKE);
        sb.and("zoneId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);
        SearchCriteria<AutoScaleVmProfileVO> sc = searchWrapper.buildSearchCriteria();

        if (id != null) {
            sc.setParameters("id", id);
        }
        if (templateId != null) {
            sc.setParameters("templateId", templateId);
        }
        if (otherDeployParams != null) {
            sc.addAnd("otherDeployParams", SearchCriteria.Op.LIKE, "%" + otherDeployParams + "%");
        }

        if (serviceOffId != null) {
            sc.setParameters("serviceOfferingId", serviceOffId);
        }

        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }

        if (display != null) {
            sc.setParameters("display", display);
        }

        return searchWrapper.search();
    }

    @DB
    protected AutoScalePolicyVO checkValidityAndPersist(final AutoScalePolicyVO autoScalePolicyVOFinal, final List<Long> conditionIds) {
        final int duration = autoScalePolicyVOFinal.getDuration();
        final int quietTime = autoScalePolicyVOFinal.getQuietTime();

        if (duration <= 0) {
            throw new InvalidParameterValueException("duration is an invalid value: " + duration);
        }

        if (quietTime < 0) {
            throw new InvalidParameterValueException("quietTime is an invalid value: " + quietTime);
        }

        return Transaction.execute(new TransactionCallback<AutoScalePolicyVO>() {
            @Override
            public AutoScalePolicyVO doInTransaction(TransactionStatus status) {
                AutoScalePolicyVO autoScalePolicyVO = autoScalePolicyDao.persist(autoScalePolicyVOFinal);

                if (conditionIds != null) {
                    if (CollectionUtils.isEmpty(conditionIds)) {
                        throw new InvalidParameterValueException("AutoScale policy must have at least one condition");
                    }
                    SearchBuilder<ConditionVO> conditionsSearch = conditionDao.createSearchBuilder();
                    conditionsSearch.and("ids", conditionsSearch.entity().getId(), Op.IN);
                    conditionsSearch.done();
                    SearchCriteria<ConditionVO> sc = conditionsSearch.create();

                    sc.setParameters("ids", conditionIds.toArray(new Object[0]));
                    List<ConditionVO> conditions = conditionDao.search(sc, null);

                    ControlledEntity[] sameOwnerEntities = conditions.toArray(new ControlledEntity[conditions.size() + 1]);
                    sameOwnerEntities[sameOwnerEntities.length - 1] = autoScalePolicyVO;
                    accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, sameOwnerEntities);

                    if (conditionIds.size() != conditions.size()) {
                        // TODO report the condition id which could not be found
                        throw new InvalidParameterValueException("Unable to find the condition specified");
                    }

                    ArrayList<Long> counterIds = new ArrayList<Long>();
                    for (ConditionVO condition : conditions) {
                        if (counterIds.contains(condition.getCounterId())) {
                            throw new InvalidParameterValueException(
                                "at least two conditions in the conditionids have the same counter. It is not right to apply two different conditions for the same counter");
                        }
                        counterIds.add(condition.getCounterId());
                    }

                    /* For update case remove the existing mappings and create fresh ones */
                    autoScalePolicyConditionMapDao.removeByAutoScalePolicyId(autoScalePolicyVO.getId());

                    for (Long conditionId : conditionIds) {
                        AutoScalePolicyConditionMapVO policyConditionMapVO = new AutoScalePolicyConditionMapVO(autoScalePolicyVO.getId(), conditionId);
                        autoScalePolicyConditionMapDao.persist(policyConditionMapVO);
                    }
                }

                return autoScalePolicyVO;
            }
        });
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEPOLICY_CREATE, eventDescription = "creating autoscale policy", create = true)
    public AutoScalePolicy createAutoScalePolicy(CreateAutoScalePolicyCmd cmd) {
        if (cmd.getInjectedJobId() == null) {
            return createAutoScalePolicyInternal(cmd);
        }
        return creationRequests.create(cmd, AutoScalePolicyVO.class, () -> createAutoScalePolicyInternal(cmd));
    }

    protected AutoScalePolicy createAutoScalePolicyInternal(CreateAutoScalePolicyCmd cmd) {

        int duration = cmd.getDuration();
        Integer quietTime = cmd.getQuietTime();
        String action = cmd.getAction();

        if (quietTime == null) {
            quietTime = NetUtils.DEFAULT_AUTOSCALE_POLICY_QUIET_TIME;
        }

        AutoScalePolicy.Action scaleAction = null;
        try {
            scaleAction = AutoScalePolicy.Action.fromValue(action);
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("action is invalid. Supported actions are: " + Arrays.toString(AutoScalePolicy.Action.values()));
        }

        AutoScalePolicyVO policyVO = new AutoScalePolicyVO(cmd.getName(), cmd.getDomainId(), cmd.getAccountId(), duration, quietTime, null, scaleAction);

        policyVO = checkValidityAndPersist(policyVO, cmd.getConditionIds());
        logger.info("Successfully created AutoScale Policy: {}", policyVO);
        return policyVO;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEPOLICY_DELETE, eventDescription = "deleting autoscale policy")
    public boolean deleteAutoScalePolicy(final long id) {
        /* Check if entity is in database */
        AutoScalePolicyVO policy = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Policy", id, autoScalePolicyDao);

        if (autoScaleVmGroupPolicyMapDao.isAutoScalePolicyInUse(id)) {
            throw new InvalidParameterValueException("Cannot delete AutoScale Policy when it is in use by one or more AutoScale Vm Groups");
        }

        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                boolean success = true;
                success = autoScalePolicyDao.remove(id);
                if (!success) {
                    logger.warn("Failed to remove AutoScale Policy db object");
                    return false;
                }
                success = autoScalePolicyConditionMapDao.removeByAutoScalePolicyId(id);
                if (!success) {
                    logger.warn("Failed to remove AutoScale Policy Condition mappings");
                    return false;
                }
                logger.info("Successfully deleted autoscale policy: {}", policy);

                return success;
            }
        });
    }

    private class SearchWrapper<VO extends ControlledEntity> {
        GenericDao<VO, Long> dao;
        SearchBuilder<VO> searchBuilder;
        SearchCriteria<VO> searchCriteria;
        Long domainId;
        boolean isRecursive;
        List<Long> permittedAccounts = new ArrayList<Long>();
        ListProjectResourcesCriteria listProjectResourcesCriteria;
        Filter searchFilter;

        public SearchWrapper(GenericDao<VO, Long> dao, Class<VO> entityClass, BaseListProjectAndAccountResourcesCmd cmd, Long id)
        {
            this.dao = dao;
            this.searchBuilder = dao.createSearchBuilder();
            domainId = cmd.getDomainId();
            String accountName = cmd.getAccountName();
            isRecursive = cmd.isRecursive();
            boolean listAll = cmd.listAll();
            long startIndex = cmd.getStartIndex();
            long pageSizeVal = cmd.getPageSizeVal();
            Account caller = CallContext.current().getCallingAccount();

            Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean,
                    ListProjectResourcesCriteria>(domainId, isRecursive, null);
            accountMgr.buildACLSearchParameters(caller, id, accountName, cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject,
                    listAll, false);
            domainId = domainIdRecursiveListProject.first();
            isRecursive = domainIdRecursiveListProject.second();
            listProjectResourcesCriteria = domainIdRecursiveListProject.third();

            accountMgr.buildACLSearchBuilder(searchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
            searchFilter = new Filter(entityClass, "id", false, startIndex, pageSizeVal);
        }

        public SearchBuilder<VO> getSearchBuilder() {
            return searchBuilder;
        }

        public SearchCriteria<VO> buildSearchCriteria() {
            searchCriteria = searchBuilder.create();
            accountMgr.buildACLSearchCriteria(searchCriteria, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
            return searchCriteria;
        }

        public List<VO> search() {
            return dao.search(searchCriteria, searchFilter);
        }
    }

    @Override
    public List<? extends AutoScalePolicy> listAutoScalePolicies(ListAutoScalePoliciesCmd cmd) {
        SearchWrapper<AutoScalePolicyVO> searchWrapper = new SearchWrapper<>(autoScalePolicyDao, AutoScalePolicyVO.class, cmd, cmd.getId());
        SearchBuilder<AutoScalePolicyVO> sb = searchWrapper.getSearchBuilder();
        Long id = cmd.getId();
        String name = cmd.getName();
        Long conditionId = cmd.getConditionId();
        String action = cmd.getAction();
        Long vmGroupId = cmd.getVmGroupId();

        AutoScalePolicy.Action scaleAction = null;
        if (action != null) {
            try {
                scaleAction = AutoScalePolicy.Action.fromValue(action);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("action is invalid. Supported actions are: " + Arrays.toString(AutoScalePolicy.Action.values()));
            }
        }

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("action", sb.entity().getAction(), SearchCriteria.Op.EQ);

        if (conditionId != null) {
            SearchBuilder<AutoScalePolicyConditionMapVO> asPolicyConditionSearch = autoScalePolicyConditionMapDao.createSearchBuilder();
            asPolicyConditionSearch.and("conditionId", asPolicyConditionSearch.entity().getConditionId(), SearchCriteria.Op.EQ);
            sb.join("asPolicyConditionSearch", asPolicyConditionSearch, sb.entity().getId(), asPolicyConditionSearch.entity().getPolicyId(), JoinBuilder.JoinType.INNER);
        }

        if (vmGroupId != null) {
            SearchBuilder<AutoScaleVmGroupPolicyMapVO> asVmGroupPolicySearch = autoScaleVmGroupPolicyMapDao.createSearchBuilder();
            asVmGroupPolicySearch.and("vmGroupId", asVmGroupPolicySearch.entity().getVmGroupId(), SearchCriteria.Op.EQ);
            sb.join("asVmGroupPolicySearch", asVmGroupPolicySearch, sb.entity().getId(), asVmGroupPolicySearch.entity().getPolicyId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<AutoScalePolicyVO> sc = searchWrapper.buildSearchCriteria();

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (scaleAction != null) {
            sc.setParameters("action", scaleAction);
        }

        if (conditionId != null) {
            sc.setJoinParameters("asPolicyConditionSearch", "conditionId", conditionId);
        }

        if (vmGroupId != null) {
            sc.setJoinParameters("asVmGroupPolicySearch", "vmGroupId", vmGroupId);
        }

        return searchWrapper.search();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEPOLICY_UPDATE, eventDescription = "updating autoscale policy")
    public AutoScalePolicy updateAutoScalePolicy(UpdateAutoScalePolicyCmd cmd) {
        List<Long> groupIds = autoScaleVmGroupPolicyMapDao.listByPolicyId(cmd.getId()).stream().map(AutoScaleVmGroupPolicyMapVO::getVmGroupId).collect(Collectors.toList());
        return withAssociatedGroupControls(groupIds, () -> updateAutoScalePolicyInternal(cmd));
    }

    protected AutoScalePolicy updateAutoScalePolicyInternal(UpdateAutoScalePolicyCmd cmd) {
        Long policyId = cmd.getId();
        String name = cmd.getName();
        Integer duration = cmd.getDuration();
        Integer quietTime = cmd.getQuietTime();
        List<Long> conditionIds = cmd.getConditionIds();
        AutoScalePolicyVO policy = getEntityInDatabase(CallContext.current().getCallingAccount(), "Auto Scale Policy", policyId, autoScalePolicyDao);

        if (name != null) {
            policy.setName(name);
        }

        if (duration != null) {
            policy.setDuration(duration);
        }

        if (quietTime != null) {
            policy.setQuietTime(quietTime);
        }

        List<AutoScaleVmGroupPolicyMapVO> vmGroupPolicyList = autoScaleVmGroupPolicyMapDao.listByPolicyId(policyId);
        for (AutoScaleVmGroupPolicyMapVO vmGroupPolicy : vmGroupPolicyList) {
            AutoScaleVmGroupVO vmGroupVO = autoScaleVmGroupDao.findById(vmGroupPolicy.getVmGroupId());
            if (vmGroupVO == null) {
                logger.warn("Stale database entry! There is an entry in VmGroupPolicyMap but the vmGroup is missing:" + vmGroupPolicy.getVmGroupId());

                continue;

            }
            if (!vmGroupVO.getState().equals(AutoScaleVmGroup.State.DISABLED)) {
                throw new InvalidParameterValueException("The AutoScale Policy can be updated only if the Vm Group it is associated with is disabled in state");
            }
            if (policy.getDuration() < vmGroupVO.getInterval()) {
                throw new InvalidParameterValueException("duration is less than the associated AutoScaleVmGroup's interval");
            }
        }

        policy = checkValidityAndPersist(policy, conditionIds);
        logger.info("Successfully updated Auto Scale Policy: {}", policy);

        if (CollectionUtils.isNotEmpty(conditionIds)) {
            markStatisticsAsInactive(null, policyId);
        }

        return policy;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_CREATE, eventDescription = "creating autoscale vm group", create = true)
    public AutoScaleVmGroup createAutoScaleVmGroup(CreateAutoScaleVmGroupCmd cmd) {
        if (cmd.getInjectedJobId() == null) {
            return createAutoScaleVmGroupInternal(cmd);
        }
        return creationRequests.create(cmd, AutoScaleVmGroupVO.class, () -> createAutoScaleVmGroupInternal(cmd));
    }

    protected AutoScaleVmGroup createAutoScaleVmGroupInternal(CreateAutoScaleVmGroupCmd cmd) {
        String name = cmd.getName();
        int minMembers = cmd.getMinMembers();
        int maxMembers = cmd.getMaxMembers();
        Integer interval = cmd.getInterval();
        Boolean forDisplay = cmd.getDisplay();

        if (interval == null) {
            interval = NetUtils.DEFAULT_AUTOSCALE_POLICY_INTERVAL_TIME;
        }

        LoadBalancerVO loadBalancer = getEntityInDatabase(CallContext.current().getCallingAccount(), ApiConstants.LBID, cmd.getLbRuleId(), lbDao);

        Long zoneId = ipAddressDao.findById(loadBalancer.getSourceIpAddressId()).getDataCenterId();

        if (autoScaleVmGroupDao.isAutoScaleLoadBalancer(loadBalancer.getId())) {
            throw new InvalidParameterValueException("an AutoScaleVmGroup is already attached to the lb rule, the existing vm group has to be first deleted");
        }

        if (lbVmMapDao.isVmAttachedToLoadBalancer(loadBalancer.getId())) {
            throw new InvalidParameterValueException(
                "there are Vms already bound to the specified LoadBalancing Rule. User bound Vms and AutoScaled Vm Group cannot co-exist on a Load Balancing Rule");
        }

        AutoScaleVmGroupVO vmGroupVO = new AutoScaleVmGroupVO(cmd.getLbRuleId(), zoneId, loadBalancer.getDomainId(), loadBalancer.getAccountId(), name, minMembers, maxMembers,
            loadBalancer.getDefaultPortStart(), interval, null, cmd.getProfileId(), AutoScaleVmGroup.State.NEW);

        if (forDisplay != null) {
            vmGroupVO.setDisplay(forDisplay);
        }

        AutoScaleVmProfileVO profile = getEntityInDatabase(CallContext.current().getCallingAccount(), ApiConstants.VMPROFILE_ID,
                cmd.getProfileId(), autoScaleVmProfileDao);
        if (profile.getZoneId() != zoneId) {
            throw new InvalidParameterValueException("AutoScale VM profile and load balancer must belong to the same zone");
        }
        vmGroupVO = checkValidityAndPersist(vmGroupVO, cmd.getScaleUpPolicyIds(), cmd.getScaleDownPolicyIds());
        logger.info("Successfully created Autoscale Vm Group: {}", vmGroupVO);

        createInactiveDummyRecord(vmGroupVO.getId());
        scheduleMonitorTask(vmGroupVO.getId());

        return vmGroupVO;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_CREATE, eventDescription = "creating autoscale vm group", async = true)
    public boolean configureAutoScaleVmGroup(CreateAutoScaleVmGroupCmd cmd) throws ResourceUnavailableException {
        return configureAutoScaleVmGroup(cmd.getEntityId(), AutoScaleVmGroup.State.NEW);
    }

    public boolean isLoadBalancerBasedAutoScaleVmGroup(AutoScaleVmGroup vmGroup) {
        return vmGroup.getLoadBalancerId() != null;
    }

    protected boolean configureAutoScaleVmGroup(long vmGroupid, AutoScaleVmGroup.State currentState) throws ResourceUnavailableException {
        AutoScaleVmGroup vmGroup = autoScaleVmGroupDao.findById(vmGroupid);

        if (isLoadBalancerBasedAutoScaleVmGroup(vmGroup)) {
            try {
                return lbRulesMgr.configureLbAutoScaleVmGroup(vmGroupid, currentState);
            } catch (ResourceUnavailableException re) {
                throw re;
            } catch (Exception e) {
                logger.warn("Exception during configureLbAutoScaleVmGroup in lb rules manager", e);
                return false;
            }
        }

        // This should never happen, because today loadbalancerruleid is manadatory for AutoScaleVmGroup.
        throw new InvalidParameterValueException("Only LoadBalancer based AutoScale is supported");
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_DELETE, eventDescription = "deleting autoscale vm group", async = true)
    public boolean deleteAutoScaleVmGroup(final long id, final Boolean cleanup) {
        return withGroupControl(id, true, () -> deleteAutoScaleVmGroupInternal(id, cleanup));
    }

    protected boolean deleteAutoScaleVmGroupInternal(final long id, final Boolean cleanup) {
        AutoScaleVmGroupVO autoScaleVmGroupVO = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Vm Group", id, autoScaleVmGroupDao);

        requireNoOperation(autoScaleVmGroupVO);
        if (autoScaleVmGroupVO.getState().equals(AutoScaleVmGroup.State.NEW) && autoScaleVmGroupVmMapDao.countByGroup(id) == 0) {
            // A failed creation has no members. Remove its dependent records too so
            // owned policies/conditions can be compensated and no monitor remains.
            boolean removed = Transaction.execute((TransactionCallback<Boolean>) status -> {
                if (!autoScaleVmGroupDao.remove(id)) {
                    return false;
                }
                autoScaleVmGroupPolicyMapDao.removeByGroupId(id);
                autoScaleVmGroupVmMapDao.removeByGroup(id);
                asGroupStatisticsDao.removeByGroupId(id);
                resourceScheduleManager.removeSchedulesForResource(ApiCommandResourceType.AutoScaleVmGroup, id);
                return true;
            });
            if (removed) {
                cancelMonitorTask(id);
            }
            return removed;
        }

        if (!autoScaleVmGroupVO.getState().equals(AutoScaleVmGroup.State.DISABLED) && !Boolean.TRUE.equals(cleanup)) {
            throw new InvalidParameterValueException(String.format("Cannot delete autoscale vm group : %s because it is in %s state. Please disable it or pass cleanup=true flag which will destroy all VMs.", autoScaleVmGroupVO, autoScaleVmGroupVO.getState()));
        }

        Integer currentVM = autoScaleVmGroupVmMapDao.countByGroup(id);
        if (currentVM > 0 && !Boolean.TRUE.equals(cleanup)) {
            throw new InvalidParameterValueException(String.format("Cannot delete autoscale vm group : %s because there are %d VMs. Please remove the VMs or pass cleanup=true flag which will destroy all VMs.", autoScaleVmGroupVO, currentVM));
        }

        // A failed cleanup must never resume policy evaluation or forget its members.
        AutoScaleVmGroup.State previousState = autoScaleVmGroupVO.getState();
        if (Boolean.TRUE.equals(cleanup) && currentVM > 0) {
            String token = UUID.randomUUID().toString();
            if (!autoScaleVmGroupDao.beginCleanupOperation(id, token)) { return false; }
            scheduleMonitorTask(id);
            AutoScaleVmGroupVO current = autoScaleVmGroupDao.findById(id);
            while (!resumeCleanupOperation(current)) {
                current = autoScaleVmGroupDao.findById(id);
                if (current == null || !"DRAINING".equals(current.getOperationPhase()) || current.getOperationDeadline() == null) {
                    return false;
                }
                long remaining = current.getOperationDeadline().getTime() - currentTimeMillis();
                if (remaining <= 0) { continue; }
                try {
                    Thread.sleep(Math.min(1000L, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        AutoScaleVmGroupVO current = autoScaleVmGroupDao.findById(id);
        current.setState(AutoScaleVmGroup.State.REVOKE);
        autoScaleVmGroupDao.persist(current);
        try {
            if (!configureAutoScaleVmGroup(id, previousState)) {
                autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.REVOKE, AutoScaleVmGroup.State.DISABLED);
                return false;
            }
        } catch (Exception e) {
            autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.REVOKE, AutoScaleVmGroup.State.DISABLED);
            logger.warn("AutoScale group {} LB removal failed; the disabled group is retained for retry", id, e);
            return false;
        }
        boolean removed;
        try {
            removed = Transaction.execute((TransactionCallback<Boolean>) status -> {
            if (!autoScaleVmGroupDao.remove(id) || !autoScaleVmGroupPolicyMapDao.removeByGroupId(id) ||
                    !autoScaleVmGroupVmMapDao.removeByGroup(id) || !asGroupStatisticsDao.removeByGroupId(id)) {
                throw new CloudRuntimeException("Failed to remove AutoScale group records; retry the retained group");
            }
            resourceScheduleManager.removeSchedulesForResource(ApiCommandResourceType.AutoScaleVmGroup, id);
            annotationDao.removeByEntityType(AnnotationService.EntityType.AUTOSCALE_VM_GROUP.name(), autoScaleVmGroupVO.getUuid());
            return true;
            });
        } catch (RuntimeException e) {
            autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.REVOKE, AutoScaleVmGroup.State.DISABLED);
            throw e;
        }
        if (removed) { cancelMonitorTask(id); }
        return removed;
    }

    @Override
    public List<? extends AutoScaleVmGroup> listAutoScaleVmGroups(ListAutoScaleVmGroupsCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        Long policyId = cmd.getPolicyId();
        Long loadBalancerId = cmd.getLoadBalancerId();
        Long profileId = cmd.getProfileId();
        Long zoneId = cmd.getZoneId();
        Boolean forDisplay = cmd.getDisplay();
        String keyword = cmd.getKeyword();

        SearchWrapper<AutoScaleVmGroupVO> searchWrapper = new SearchWrapper<>(autoScaleVmGroupDao, AutoScaleVmGroupVO.class, cmd, cmd.getId());
        SearchBuilder<AutoScaleVmGroupVO> sb = searchWrapper.getSearchBuilder();

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("loadBalancerId", sb.entity().getLoadBalancerId(), SearchCriteria.Op.EQ);
        sb.and("profileId", sb.entity().getProfileId(), SearchCriteria.Op.EQ);
        sb.and("zoneId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);

        if (policyId != null) {
            SearchBuilder<AutoScaleVmGroupPolicyMapVO> asVmGroupPolicySearch = autoScaleVmGroupPolicyMapDao.createSearchBuilder();
            asVmGroupPolicySearch.and("policyId", asVmGroupPolicySearch.entity().getPolicyId(), SearchCriteria.Op.EQ);
            sb.join("asVmGroupPolicySearch", asVmGroupPolicySearch, sb.entity().getId(), asVmGroupPolicySearch.entity().getVmGroupId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<AutoScaleVmGroupVO> sc = searchWrapper.buildSearchCriteria();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (name != null) {
            sc.setParameters("name", name);
        }
        if (loadBalancerId != null) {
            sc.setParameters("loadBalancerId", loadBalancerId);
        }
        if (profileId != null) {
            sc.setParameters("profileId", profileId);
        }
        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }
        if (policyId != null) {
            sc.setJoinParameters("asVmGroupPolicySearch", "policyId", policyId);
        }
        if (forDisplay != null) {
            sc.setParameters("display", forDisplay);
        }
        if (StringUtils.isNotBlank(keyword)) {
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        return searchWrapper.search();
    }

    @Override
    public void validateMinMaxMembers(int minMembers, int maxMembers) {
        if (minMembers <= 0) {
            throw new InvalidParameterValueException(ApiConstants.MIN_MEMBERS + " is an invalid value: " + minMembers);
        }

        if (maxMembers <= 0) {
            throw new InvalidParameterValueException(ApiConstants.MAX_MEMBERS + " is an invalid value: " + maxMembers);
        }

        if (minMembers > maxMembers) {
            throw new InvalidParameterValueException(ApiConstants.MIN_MEMBERS + " (" + minMembers +
                    ") cannot be greater than " + ApiConstants.MAX_MEMBERS + " (" + maxMembers + ")");
        }
    }

    @DB
    protected AutoScaleVmGroupVO checkValidityAndPersist(final AutoScaleVmGroupVO vmGroup, final List<Long> passedScaleUpPolicyIds,
        final List<Long> passedScaleDownPolicyIds) {

        checkAutoScaleVmGroupName(vmGroup.getName());

        int minMembers = vmGroup.getMinMembers();
        int maxMembers = vmGroup.getMaxMembers();
        int interval = vmGroup.getInterval();
        List<Counter> counters = new ArrayList<Counter>();
        List<AutoScalePolicyVO> policies = new ArrayList<AutoScalePolicyVO>();
        final List<Long> policyIds = new ArrayList<Long>();
        List<Long> currentScaleUpPolicyIds = new ArrayList<Long>();
        List<Long> currentScaleDownPolicyIds = new ArrayList<Long>();
        if (vmGroup.getCreated() != null) {
            ApiDBUtils.getAutoScaleVmGroupPolicyIds(vmGroup.getId(), currentScaleUpPolicyIds, currentScaleDownPolicyIds);
        }

        validateMinMaxMembers(minMembers, maxMembers);

        if (interval <= 0) {
            throw new InvalidParameterValueException("interval is an invalid value: " + interval);
        }

        if (passedScaleUpPolicyIds != null) {
            if (CollectionUtils.isEmpty(passedScaleUpPolicyIds)) {
                throw new InvalidParameterValueException("AutoScale VM group must have at least one ScaleUp policy");
            }
            policies.addAll(getAutoScalePolicies(passedScaleUpPolicyIds, counters, interval, true));
            policyIds.addAll(passedScaleUpPolicyIds);
        } else {
            // Run the interval check for existing policies
            getAutoScalePolicies(currentScaleUpPolicyIds, counters, interval, true);
            policyIds.addAll(currentScaleUpPolicyIds);
        }

        if (passedScaleDownPolicyIds != null) {
            if (CollectionUtils.isEmpty(passedScaleDownPolicyIds)) {
                throw new InvalidParameterValueException("AutoScale VM group must have at least one ScaleDown policy");
            }
            policies.addAll(getAutoScalePolicies(passedScaleDownPolicyIds, counters, interval, false));
            policyIds.addAll(passedScaleDownPolicyIds);
        } else {
            // Run the interval check for existing policies
            getAutoScalePolicies(currentScaleDownPolicyIds, counters, interval, false);
            policyIds.addAll(currentScaleDownPolicyIds);
        }
        AutoScaleVmProfileVO profileVO =
            getEntityInDatabase(CallContext.current().getCallingAccount(), ApiConstants.VMPROFILE_ID, vmGroup.getProfileId(), autoScaleVmProfileDao);

        LoadBalancerVO loadBalancer = getEntityInDatabase(CallContext.current().getCallingAccount(), ApiConstants.LBID, vmGroup.getLoadBalancerId(), lbDao);
        validateNetworkCapability(loadBalancer.getNetworkId());
        validateAutoScaleCounters(loadBalancer.getNetworkId(), counters, profileVO.getCounterParams());

        Network.Provider provider = getLoadBalancerServiceProvider(vmGroup.getLoadBalancerId());
        if (Network.Provider.Netscaler.equals(provider)) {
            rejectNetScalerStorage(profileVO);
            checkAutoScaleUser(profileVO.getAutoScaleUserId(), vmGroup.getAccountId());
        }

        ControlledEntity[] sameOwnerEntities = policies.toArray(new ControlledEntity[policies.size() + 2]);
        sameOwnerEntities[sameOwnerEntities.length - 2] = loadBalancer;
        sameOwnerEntities[sameOwnerEntities.length - 1] = profileVO;
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, sameOwnerEntities);

        return Transaction.execute(new TransactionCallback<AutoScaleVmGroupVO>() {
            @Override
            public AutoScaleVmGroupVO doInTransaction(TransactionStatus status) {
                if (vmGroup.getId() == 0) {
                    LoadBalancerVO lockedLb = lbDao.lockRow(vmGroup.getLoadBalancerId(), true);
                    if (lockedLb == null || autoScaleVmGroupDao.isAutoScaleLoadBalancer(vmGroup.getLoadBalancerId()) ||
                            lbVmMapDao.isVmAttachedToLoadBalancer(vmGroup.getLoadBalancerId())) {
                        throw new InvalidParameterValueException("The load balancer already has a VM group or manually assigned VMs");
                    }
                }
                AutoScaleVmGroupVO vmGroupNew = autoScaleVmGroupDao.persist(vmGroup);

                if (passedScaleUpPolicyIds != null || passedScaleDownPolicyIds != null) {
                    autoScaleVmGroupPolicyMapDao.removeByGroupId(vmGroupNew.getId());

                    for (Long policyId : policyIds) {
                        autoScaleVmGroupPolicyMapDao.persist(new AutoScaleVmGroupPolicyMapVO(vmGroupNew.getId(), policyId));
                    }
                }

                return vmGroupNew;
            }
        });

    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_UPDATE, eventDescription = "updating autoscale vm group", async = true)
    public AutoScaleVmGroup updateAutoScaleVmGroup(UpdateAutoScaleVmGroupCmd cmd) {
        return withGroupControl(cmd.getId(), true, () -> Transaction.execute((TransactionCallback<AutoScaleVmGroup>) status -> {
            autoScaleVmGroupDao.lockRow(cmd.getId(), true);
            return updateAutoScaleVmGroupInternal(cmd);
        }));
    }

    protected AutoScaleVmGroup updateAutoScaleVmGroupInternal(UpdateAutoScaleVmGroupCmd cmd) {
        Long vmGroupId = cmd.getId();
        String name = cmd.getName();
        Integer minMembers = cmd.getMinMembers();
        Integer maxMembers = cmd.getMaxMembers();
        Integer interval = cmd.getInterval();
        Boolean forDisplay = cmd.getDisplay();

        List<Long> scaleUpPolicyIds = cmd.getScaleUpPolicyIds();
        List<Long> scaleDownPolicyIds = cmd.getScaleDownPolicyIds();

        AutoScaleVmGroupVO vmGroupVO = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Vm Group", vmGroupId, autoScaleVmGroupDao);
        requireNoOperation(vmGroupVO);
        int currentInterval = vmGroupVO.getInterval();

        boolean physicalParametersUpdate = ((interval != null && interval != currentInterval) || CollectionUtils.isNotEmpty(scaleUpPolicyIds) || CollectionUtils.isNotEmpty(scaleDownPolicyIds));

        if (physicalParametersUpdate && !vmGroupVO.getState().equals(AutoScaleVmGroup.State.DISABLED)) {
            throw new InvalidParameterValueException("An AutoScale Vm Group can be updated with Interval/Policies only when it is in disabled state");
        }

        if (StringUtils.isNotBlank(name)) {
            vmGroupVO.setName(name);
        }

        if (minMembers != null) {
            vmGroupVO.setMinMembers(minMembers);
        }

        if (maxMembers != null) {
            vmGroupVO.setMaxMembers(maxMembers);
        }

        if (interval != null) {
            vmGroupVO.setInterval(interval);
        }

        if (cmd.getCustomId() != null) {
            vmGroupVO.setUuid(cmd.getCustomId());
        }

        if (forDisplay != null) {
            vmGroupVO.setDisplay(forDisplay);
        }

        vmGroupVO = checkValidityAndPersist(vmGroupVO, scaleUpPolicyIds, scaleDownPolicyIds);
        if (vmGroupVO != null) {
            logger.debug("Updated Auto Scale VmGroup: {}", vmGroupVO);

            if ((interval != null && interval != currentInterval) || CollectionUtils.isNotEmpty(scaleUpPolicyIds) || CollectionUtils.isNotEmpty(scaleDownPolicyIds)) {
                markStatisticsAsInactive(vmGroupId, null);
            }

            return vmGroupVO;
        } else
            return null;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_ENABLE, eventDescription = "enabling autoscale vm group", async = true)
    public AutoScaleVmGroup enableAutoScaleVmGroup(Long id) {
        return withGroupControl(id, true, () -> enableAutoScaleVmGroupInternal(id));
    }

    protected AutoScaleVmGroup enableAutoScaleVmGroupInternal(Long id) {
        AutoScaleVmGroupVO vmGroup = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Vm Group", id, autoScaleVmGroupDao);
        requireNoOperation(vmGroup);
        if (vmGroup.getState() == AutoScaleVmGroup.State.ENABLED) {
            return vmGroup;
        }
        if (!autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.DISABLED, AutoScaleVmGroup.State.ENABLED)) {
            throw new InvalidParameterValueException("Only a disabled AutoScale VM group without an active operation can be enabled");
        }
        boolean success = false;
        try {
            success = configureAutoScaleVmGroup(id, AutoScaleVmGroup.State.DISABLED);
        } catch (ResourceUnavailableException e) {
            logger.warn("Failed to configure enabled AutoScale VM group {}", id, e);
        }
        if (!success) {
            autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.ENABLED, AutoScaleVmGroup.State.DISABLED);
            return null;
        }
        scheduleMonitorTask(id);
        createInactiveDummyRecord(id);
        return autoScaleVmGroupDao.findById(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_AUTOSCALEVMGROUP_DISABLE, eventDescription = "disabling autoscale vm group", async = true)
    @DB
    public AutoScaleVmGroup disableAutoScaleVmGroup(Long id) {
        return withGroupControl(id, false, () -> disableAutoScaleVmGroupInternal(id));
    }

    protected AutoScaleVmGroup disableAutoScaleVmGroupInternal(Long id) {
        AutoScaleVmGroupVO initial = getEntityInDatabase(CallContext.current().getCallingAccount(), "AutoScale Vm Group", id, autoScaleVmGroupDao);
        if (initial.getState() == AutoScaleVmGroup.State.DISABLED) {
            if (initial.isOperationInProgress()) { scheduleMonitorTask(id); }
            return initial;
        }
        AutoScaleVmGroupVO vmGroup = Transaction.execute((TransactionCallback<AutoScaleVmGroupVO>) status -> {
            AutoScaleVmGroupVO current = autoScaleVmGroupDao.lockRow(id, true);
            if (current.getState() != AutoScaleVmGroup.State.DISABLED &&
                    !autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.ENABLED, AutoScaleVmGroup.State.DISABLED) &&
                    !autoScaleVmGroupDao.updateState(id, AutoScaleVmGroup.State.SCALING, AutoScaleVmGroup.State.DISABLED)) {
                throw new InvalidParameterValueException("Only an enabled or scaling AutoScale VM group can be disabled");
            }
            return autoScaleVmGroupDao.findById(id);
        });
        // Stop intent is committed before VR configuration; a configuration failure must never restart scaling.
        boolean success = false;
        try {
            success = configureAutoScaleVmGroup(id, AutoScaleVmGroup.State.ENABLED);
        } catch (ResourceUnavailableException e) {
            logger.warn("AutoScale VM group {} remains disabled after configuration failure", id, e);
        }
        createInactiveDummyRecord(id);
        if (vmGroup.isOperationInProgress()) {
            scheduleMonitorTask(id);
        } else {
            cancelMonitorTask(id);
        }
        return success ? autoScaleVmGroupDao.findById(id) : null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_COUNTER_CREATE, eventDescription = "Counter", create = true)
    @DB
    public Counter createCounter(CreateCounterCmd cmd) {
        String source = cmd.getSource().toUpperCase();
        String name = cmd.getName();
        String value = cmd.getValue();
        Counter.Source src;
        // Validate Source
        try {
            src = Counter.Source.valueOf(source);
        } catch (Exception ex) {
            throw new InvalidParameterValueException("The Source " + source + " does not exist; Unable to create Counter");
        }

        // Validate Provider
        Network.Provider provider = networkModel.resolveProvider(cmd.getProvider());
        if (provider == null) {
            throw new InvalidParameterValueException("The Provider " + cmd.getProvider() + " does not exist; Unable to create Counter");
        }

        CounterVO counter = null;

        CounterVO existingCounter = counterDao.findByNameProviderValue(name, value, provider.getName());
        if (existingCounter != null) {
            throw new InvalidParameterValueException(String.format("Counter with name %s and value %s already exists. ", name,value));
        }
        logger.debug("Adding Counter " + name);
        counter = counterDao.persist(new CounterVO(src, name, value, provider));

        CallContext.current().setEventDetails(" ID: " + counter.getUuid() + " Name: " + name);
        return counter;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_COUNTER_CREATE, eventDescription = "Creating a counter", async = true)
    public Counter getCounter(long counterId) {
        return counterDao.findById(counterId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONDITION_CREATE, eventDescription = "Condition", create = true)
    public Condition createCondition(CreateConditionCmd cmd) {
        if (cmd.getInjectedJobId() == null) {
            return createConditionInternal(cmd);
        }
        return creationRequests.create(cmd, ConditionVO.class, () -> createConditionInternal(cmd));
    }

    protected Condition createConditionInternal(CreateConditionCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Account owner = accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        accountMgr.checkAccess(caller, null, true, owner);

        String opr = cmd.getRelationalOperator().toUpperCase();
        long cid = cmd.getCounterId();
        long threshold = cmd.getThreshold();
        Condition.Operator op;
        // Validate Relational Operator
        try {
            op = Condition.Operator.valueOf(opr);
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("The Operator " + opr + " does not exist; Unable to create Condition.");
        }
        if (threshold < 0) {
            throw new InvalidParameterValueException("The threshold " + threshold + " must be equal to or greater than 0.");
        }

        CounterVO counter = counterDao.findById(cid);

        if (counter == null) {
            throw new InvalidParameterValueException("Unable to find counter");
        }
        ConditionVO condition = null;

        condition = conditionDao.persist(new ConditionVO(cid, threshold, owner.getAccountId(), owner.getDomainId(), op));
        logger.info("Successfully created condition: {}", condition);

        CallContext.current().setEventDetails(" ID: " + condition.getUuid());
        return condition;
    }

    @Override
    public List<? extends Counter> listCounters(ListCountersCmd cmd) {
        String name = cmd.getName();
        Long id = cmd.getId();
        String source = cmd.getSource();
        if (source != null) {
            source = source.toUpperCase();
        }
        String providerStr = cmd.getProvider();
        if (providerStr != null) {
            Network.Provider provider = networkModel.resolveProvider(providerStr);
            if (provider == null) {
                throw new InvalidParameterValueException("The Provider " + providerStr + " does not exist; Unable to list Counter");
            }
            providerStr = provider.getName();
        }

        Filter searchFilter = new Filter(CounterVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        return counterDao.listCounters(id, name, source, providerStr, cmd.getKeyword(), searchFilter);
    }

    @Override
    public List<? extends Condition> listConditions(ListConditionsCmd cmd) {
        Long id = cmd.getId();
        Long counterId = cmd.getCounterId();
        Long policyId = cmd.getPolicyId();
        SearchWrapper<ConditionVO> searchWrapper = new SearchWrapper<>(conditionDao, ConditionVO.class, cmd, cmd.getId());
        SearchBuilder<ConditionVO> sb = searchWrapper.getSearchBuilder();
        if (policyId != null) {
            SearchBuilder<AutoScalePolicyConditionMapVO> asPolicyConditionSearch = autoScalePolicyConditionMapDao.createSearchBuilder();
            asPolicyConditionSearch.and("policyId", asPolicyConditionSearch.entity().getPolicyId(), SearchCriteria.Op.EQ);
            sb.join("asPolicyConditionSearch", asPolicyConditionSearch, sb.entity().getId(), asPolicyConditionSearch.entity().getConditionId(),
                JoinBuilder.JoinType.INNER);
        }

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("counterId", sb.entity().getCounterId(), SearchCriteria.Op.EQ);

        // now set the SC criteria...
        SearchCriteria<ConditionVO> sc = searchWrapper.buildSearchCriteria();

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (counterId != null) {
            sc.setParameters("counterId", counterId);
        }

        if (policyId != null) {
            sc.setJoinParameters("asPolicyConditionSearch", "policyId", policyId);
        }

        return searchWrapper.search();
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_COUNTER_DELETE, eventDescription = "counter")
    public boolean deleteCounter(long counterId) throws ResourceInUseException {
        // Verify Counter id
        CounterVO counter = counterDao.findById(counterId);
        if (counter == null) {
            throw new InvalidParameterValueException("Unable to find Counter");
        }

        // Verify if it is used in any Condition

        ConditionVO condition = conditionDao.findByCounterId(counterId);
        if (condition != null) {
            logger.info("Cannot delete counter {} as it is being used in a condition.", counter);
            throw new ResourceInUseException("Counter is in use.");
        }

        boolean success = counterDao.remove(counterId);
        if (success) {
            logger.info("Successfully deleted counter: {}", counter);
        }

        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONDITION_DELETE, eventDescription = "delete a condition")
    public boolean deleteCondition(long conditionId) throws ResourceInUseException {
        /* Check if entity is in database */
        ConditionVO condition = getEntityInDatabase(CallContext.current().getCallingAccount(), "Condition", conditionId, conditionDao);
        if (condition == null) {
            throw new InvalidParameterValueException("Unable to find Condition");
        }

        // Verify if condition is used in any autoscale policy
        if (autoScalePolicyConditionMapDao.isConditionInUse(conditionId)) {
            logger.info("Cannot delete condition {} as it is being used in a condition.", condition);
            throw new ResourceInUseException("Cannot delete Condition when it is in use by one or more AutoScale Policies.");
        }
        boolean success = conditionDao.remove(conditionId);
        if (success) {
            logger.info("Successfully deleted condition {}", condition);
        }
        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONDITION_UPDATE, eventDescription = "update a condition")
    public Condition updateCondition(UpdateConditionCmd cmd) throws ResourceInUseException {
        Long conditionId = cmd.getId();
        /* Check if entity is in database */
        ConditionVO condition = getEntityInDatabase(CallContext.current().getCallingAccount(), "Condition", conditionId, conditionDao);

        String operator = cmd.getRelationalOperator().toUpperCase();
        Long threshold = cmd.getThreshold();

        Condition.Operator op;
        // Validate Relational Operator
        try {
            op = Condition.Operator.valueOf(operator);
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("The Operator " + operator + " does not exist; Unable to update Condition.");
        }
        if (threshold < 0) {
            throw new InvalidParameterValueException("The threshold " + threshold + " must be equal to or greater than 0.");
        }

        // Verify if condition is used in any autoscale vmgroup
        GenericSearchBuilder<AutoScalePolicyConditionMapVO, Long> conditionSearch = autoScalePolicyConditionMapDao.createSearchBuilder(Long.class);
        conditionSearch.selectFields(conditionSearch.entity().getPolicyId());
        conditionSearch.and("conditionId", conditionSearch.entity().getConditionId(), Op.EQ);
        SearchCriteria<Long> sc = conditionSearch.create();
        sc.setParameters("conditionId", conditionId);
        List<Long> policyIds = autoScalePolicyConditionMapDao.customSearch(sc, null);

        if (CollectionUtils.isNotEmpty(policyIds)) {
            SearchBuilder<AutoScaleVmGroupPolicyMapVO> policySearch = autoScaleVmGroupPolicyMapDao.createSearchBuilder();
            policySearch.and("policyId", policySearch.entity().getPolicyId(), Op.IN);
            SearchBuilder<AutoScaleVmGroupVO> vmGroupSearch = autoScaleVmGroupDao.createSearchBuilder();
            vmGroupSearch.and("stateNEQ", vmGroupSearch.entity().getState(), Op.NEQ);
            vmGroupSearch.join("policySearch", policySearch, vmGroupSearch.entity().getId(), policySearch.entity().getVmGroupId(), JoinBuilder.JoinType.INNER);
            vmGroupSearch.done();

            SearchCriteria<AutoScaleVmGroupVO> sc2 = vmGroupSearch.create();
            sc2.setParameters("stateNEQ", AutoScaleVmGroup.State.DISABLED);
            sc2.setJoinParameters("policySearch", "policyId", policyIds.toArray((new Object[policyIds.size()])));
            List<AutoScaleVmGroupVO> groups = autoScaleVmGroupDao.search(sc2, null);
            if (CollectionUtils.isNotEmpty(groups)) {
                String msg = String.format("Cannot update condition %s as it is being used in %d vm groups NOT in Disabled state.", condition, groups.size());
                logger.info(msg);
                throw new ResourceInUseException(msg);
            }
        }

        List<Long> groupIds = new ArrayList<>();
        for (Long policyId : policyIds) {
            autoScaleVmGroupPolicyMapDao.listByPolicyId(policyId).forEach(map -> groupIds.add(map.getVmGroupId()));
        }
        return withAssociatedGroupControls(groupIds, () -> {
            for (Long groupId : groupIds) {
                AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(groupId);
                if (group != null && group.getState() != AutoScaleVmGroup.State.DISABLED) {
                    throw new InvalidParameterValueException("Conditions can only be updated while associated AutoScale VM groups are disabled");
                }
            }
            condition.setRelationalOperator(op);
            condition.setThreshold(threshold);
            boolean success = conditionDao.update(conditionId, condition);
            if (success) {
                logger.info("Successfully updated condition {}", condition);

                for (Long policyId : policyIds) {
                    markStatisticsAsInactive(null, policyId);
                }
            }
            return condition;
        });
    }

    @Override
    public boolean deleteAutoScaleVmGroupsByAccount(Account account) {
        boolean success = true;
        List<AutoScaleVmGroupVO> groups = autoScaleVmGroupDao.listByAccount(account.getId());
        for (AutoScaleVmGroupVO group : groups) {
            logger.debug("Deleting AutoScale Vm Group {} for account: {}", group, account);
            try {
                if (!deleteAutoScaleVmGroup(group.getId(), true)) {
                    success = false;
                    continue;
                }
                logger.debug("AutoScale Vm Group {} has been successfully deleted for account: {}", group, account);
            } catch (Exception e) {
                logger.warn("Failed to delete AutoScale Vm Group {} for account: {} due to: ", group, account, e);
                success = false;
            }
        }
        return success;
    }

    @Override
    public void cleanUpAutoScaleResources(Account account) {
        // Do not remove dependencies of a group whose VM/LB cleanup is still incomplete.
        if (!autoScaleVmGroupDao.listByAccount(account.getId()).isEmpty()) {
            throw new CloudRuntimeException("AutoScale groups remain; retry account cleanup after VM/LB reconciliation completes");
        }
        // cleans Autoscale VmProfiles, AutoScale Policies and Conditions belonging to an account
        int count = 0;
        count = autoScaleVmProfileDao.removeByAccountId(account.getId());
        if (count > 0) {
            logger.debug("Deleted {} AutoScale Vm Profile for account: {}", count, account);
        }
        count = autoScalePolicyDao.removeByAccountId(account.getId());
        if (count > 0) {
            logger.debug("Deleted {} AutoScale Policies for account: {}", count, account);
        }
        count = conditionDao.removeByAccountId(account.getId());
        if (count > 0) {
            logger.debug("Deleted {} Conditions for account: {}", count, account);
        }
    }

    private boolean checkConditionUp(AutoScaleVmGroupVO asGroup, Integer numVm) {
        // check maximum
        Integer currentVM = autoScaleVmGroupVmMapDao.countByGroup(asGroup.getId());
        Integer maxVm = asGroup.getMaxMembers();
        if (currentVM + numVm > maxVm) {
            logger.warn("number of VM will greater than the maximum in this group if scaling up, so do nothing more");
            return false;
        }
        int erroredInstanceCount = autoScaleVmGroupVmMapDao.getErroredInstanceCount(asGroup.getId());
        if (erroredInstanceCount > AutoScaleManager.AutoScaleErroredInstanceThreshold.value()) {
            logger.warn("Number of Errored Instances are greater than the threshold in this group for scaling up, so do nothing more");
            return false;
        }
        return true;
    }

    private boolean checkConditionDown(AutoScaleVmGroupVO asGroup) {
        Integer currentVM = countServingMembers(asGroup);
        Integer minVm = asGroup.getMinMembers();
        if (currentVM - 1 < minVm) {
            logger.warn("number of VM will less than the minimum in this group if scaling down, so do nothing more");
            return false;
        }
        return true;
    }

    protected int countServingMembers(AutoScaleVmGroupVO group) {
        Set<Long> registered = lbVmMapDao.listByLoadBalancerId(group.getLoadBalancerId()).stream()
                .filter(mapping -> !mapping.isRevoke()).map(LoadBalancerVMMapVO::getInstanceId).collect(Collectors.toSet());
        return (int) autoScaleVmGroupVmMapDao.listByGroup(group.getId()).stream().filter(member -> {
            UserVmVO vm = userVmDao.findById(member.getInstanceId());
            return vm != null && vm.getState() == VirtualMachine.State.Running && registered.contains(vm.getId());
        }).count();
    }

    protected Map<String, String> getDeployParams (String otherDeployParams) {
        Map<String, String> deployParams = new HashMap<>();
        if (StringUtils.isNotBlank(otherDeployParams)) {
            for (String param : otherDeployParams.split("&")) {
                if (param.split("=").length >= 2) {
                    deployParams.put(param.split("=")[0], param.split("=")[1]);
                }
            }
        }
        return deployParams;
    }

    protected UserVm createNewVM(AutoScaleVmGroupVO asGroup) {
        AutoScaleVmProfileVO profileVo = autoScaleVmProfileDao.findById(asGroup.getProfileId());
        long templateId = profileVo.getTemplateId();
        long serviceOfferingId = profileVo.getServiceOfferingId();
        if (templateId == -1) {
            return null;
        }
        // create new VM into DB
        try {
            //Verify that all objects exist before passing them to the service
            Account owner = accountService.getActiveAccountById(profileVo.getAccountId());

            DataCenter zone = entityMgr.findById(DataCenter.class, profileVo.getZoneId());
            if (zone == null) {
                throw new InvalidParameterValueException("Unable to find zone by id=" + profileVo.getZoneId());
            }

            ServiceOffering serviceOffering = entityMgr.findById(ServiceOffering.class, serviceOfferingId);
            if (serviceOffering == null) {
                throw new InvalidParameterValueException("Unable to find service offering: " + serviceOfferingId);
            }

            DiskOffering diskOffering = entityMgr.findById(DiskOffering.class, serviceOffering.getDiskOfferingId());
            if (diskOffering == null) {
                throw new InvalidParameterValueException("Unable to find disk offering: " + serviceOffering.getDiskOfferingId());
            }

            VirtualMachineTemplate template = entityMgr.findById(VirtualMachineTemplate.class, templateId);
            // Make sure a valid template ID was specified
            if (template == null) {
                throw new InvalidParameterValueException("Unable to use template " + templateId);
            }

            if (!zone.isLocalStorageEnabled()) {
                if (diskOffering.isUseLocalStorage()) {
                    throw new InvalidParameterValueException(String.format("Zone is not configured to use local storage but disk offering %s associated to the service offering %s uses it", diskOffering, serviceOffering));
                }
            }

            String userData = profileVo.getUserData();
            Long userDataId = profileVo.getUserDataId();
            String userDataDetails = profileVo.getUserDataDetails();

            IpAddresses addrs = new IpAddresses(null, null);
            HypervisorType hypervisorType = template.getHypervisorType();
            final Network network = getNetwork(asGroup.getLoadBalancerId());
            final Map<String, String> customParameters = new HashMap<>();
            final Map<String, String> deployParams = getDeployParams(profileVo.getOtherDeployParams());

            final List<Long> networkIds = getVmNetworkIds(deployParams, network.getId());
            Long overrideDiskOfferingId = getVmOverrideDiskOfferingId(deployParams);
            Long diskOfferingId = getVmDiskOfferingId(deployParams);
            Long dataDiskSize = getVmDataDiskSize(deployParams);
            List<String> sshKeyPairs = getVmSshKeyPairs(deployParams, owner);
            List<Long> affinityGroupIdList = getVmAffinityGroupId(deployParams);
            List<Long> securityGroupIds = new ArrayList<>();
            if (deployParams.containsKey(PARAM_SECURITY_GROUP_IDS)) {
                for (String uuid : deployParams.get(PARAM_SECURITY_GROUP_IDS).split(",")) {
                    SecurityGroup securityGroup = entityMgr.findByUuid(SecurityGroup.class, uuid);
                    if (securityGroup == null) {
                        throw new InvalidParameterValueException("AutoScale security group no longer exists");
                    }
                    securityGroupIds.add(securityGroup.getId());
                }
            }
            updateVmDetails(deployParams, customParameters);

            Pair<String, String> vmHostAndDisplayName = getNextVmHostAndDisplayName(asGroup, template);
            String vmHostName = vmHostAndDisplayName.first();
            String vmDisplayName = vmHostAndDisplayName.second();
            asGroup.setNextVmSeq(asGroup.getNextVmSeq() + 1);
            autoScaleVmGroupDao.updateNextVmSeq(asGroup.getId(), asGroup.getNextVmSeq());

            Map<Long, Long> storage = hasSelectedStorage(deployParams)
                    ? resolveSelectedStorage(deployParams, zone.getId()) : Collections.emptyMap();
            List<VmDiskInfo> dataDisks = selectedDataDisk(deployParams, storage);
            if (dataDisks != null) {
                diskOfferingId = null;
                dataDiskSize = null;
            }
            CallContext context = CallContext.current();
            Object previousSelection = context.getContextParameter(VmStorageSelectionManager.Selection.class);
            try {
                if (!storage.isEmpty()) {
                    context.putContextParameter(VmStorageSelectionManager.Selection.class, new VmStorageSelectionManager.Selection(storage));
                }
                if (zone.getNetworkType() == NetworkType.Basic) {
                    return userVmService.createBasicSecurityGroupVirtualMachine(zone, serviceOffering, template, securityGroupIds, owner, vmHostName,
                            vmDisplayName, diskOfferingId, dataDiskSize, dataDisks, null,
                            hypervisorType, HTTPMethod.POST, userData, userDataId, userDataDetails, sshKeyPairs,
                            null, null, true, null, affinityGroupIdList, customParameters, null, null, null,
                            null, true, overrideDiskOfferingId, null, null, null);
                } else {
                    if (networkModel.checkSecurityGroupSupportForNetwork(owner, zone, networkIds,
                            Collections.emptyList())) {
                        return userVmService.createAdvancedSecurityGroupVirtualMachine(zone, serviceOffering, template, networkIds, securityGroupIds,
                                owner, vmHostName, vmDisplayName, diskOfferingId, dataDiskSize, dataDisks, null,
                                hypervisorType, HTTPMethod.POST, userData, userDataId, userDataDetails, sshKeyPairs,
                                null, null, true, null, affinityGroupIdList, customParameters, null, null, null,
                                null, true, overrideDiskOfferingId, null, null, null, null);
                    } else {
                        if (!securityGroupIds.isEmpty()) {
                            throw new InvalidParameterValueException("AutoScale security groups require a security-group-enabled network");
                        }
                        return userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner, vmHostName, vmDisplayName,
                                diskOfferingId, dataDiskSize, dataDisks, null,
                                hypervisorType, HTTPMethod.POST, userData, userDataId, userDataDetails, sshKeyPairs,
                                null, addrs, true, null, affinityGroupIdList, customParameters, null, null, null,
                                null, true, null, overrideDiskOfferingId, null, null, null);
                    }
                }
            } finally {
                if (!storage.isEmpty()) {
                    context.removeContextParameter(VmStorageSelectionManager.Selection.class);
                    if (previousSelection != null) {
                        context.putContextParameter(VmStorageSelectionManager.Selection.class, previousSelection);
                    }
                }
            }
        } catch (InsufficientCapacityException ex) {
            logger.info(ex);
            logger.trace(ex.getMessage(), ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, ex.getMessage());
        } catch (ResourceUnavailableException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, ex.getMessage());
        } catch (ConcurrentOperationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        } catch (ResourceAllocationException ex) {
            logger.warn("Exception: ", ex);
            throw new ServerApiException(ApiErrorCode.RESOURCE_ALLOCATION_ERROR, ex.getMessage());
        }
    }

    protected List<Long> getVmNetworkIds(Map<String, String> deployParams, long defaultNetworkId) {
        final List<Long> networkIds = new ArrayList<>();
        networkIds.add(defaultNetworkId);
        if (deployParams.get(PARAM_NETWORK_IDS) != null) { // networkids, append to LB networkid
            String[] networkids = deployParams.get(PARAM_NETWORK_IDS).split(",");
            for (String networkid : networkids) {
                Network otherNetwork = networkDao.findByUuid(networkid);
                if (otherNetwork != null && otherNetwork.getId() != defaultNetworkId) {
                    networkIds.add(otherNetwork.getId());
                }
            }
        }
        return networkIds;
    }

    protected Long getVmOverrideDiskOfferingId(Map<String, String> deployParams) {
        Long overrideDiskOfferingId = null;     // override ROOT disk offering
        if (deployParams.get(PARAM_OVERRIDE_DISK_OFFERING_ID) != null) {
            String overrideDiskOfferingUuid = deployParams.get(PARAM_OVERRIDE_DISK_OFFERING_ID);
            DiskOffering overrideDiskOfferingInParam = diskOfferingDao.findByUuid(overrideDiskOfferingUuid);
            if (overrideDiskOfferingInParam != null) {
                overrideDiskOfferingId = overrideDiskOfferingInParam.getId();
            } else {
                logger.warn("Cannot find disk offering by overridediskofferingid from otherdeployparams in AutoScale Vm profile");
            }
        }
        return overrideDiskOfferingId;
    }

    protected final Long getVmDiskOfferingId(Map<String, String> deployParams) {
        Long diskOfferingId = null;     // DATA disk offering ID
        if (deployParams.get(PARAM_DISK_OFFERING_ID) != null) {
            String diskOfferingUuid = deployParams.get(PARAM_DISK_OFFERING_ID);
            DiskOffering diskOfferingInParam = diskOfferingDao.findByUuid(diskOfferingUuid);
            if (diskOfferingInParam != null) {
                diskOfferingId = diskOfferingInParam.getId();
            } else {
                logger.warn("Cannot find disk offering by diskofferingid from otherdeployparams in AutoScale Vm profile");
            }
        }
        return diskOfferingId;
    }

    protected Long getVmDataDiskSize(Map<String, String> deployParams) {
        Long dataDiskSize = null;       // DATA disk size
        if (deployParams.get(PARAM_DISK_SIZE) != null) {
            String dataDiskSizeInParam = deployParams.get(PARAM_DISK_SIZE);
            try {
                dataDiskSize = Long.parseLong(dataDiskSizeInParam);
            } catch (NumberFormatException ex) {
                logger.warn("Cannot parse size from otherdeployparams in AutoScale Vm profile");
            }
        }
        return dataDiskSize;
    }

    protected List<String> getVmSshKeyPairs(Map<String, String> deployParams, Account owner) {
        List<String> sshKeyPairs = new ArrayList<>();
        if (deployParams.get(PARAM_SSH_KEYPAIRS) != null) {   // SSH keypairs
            String[] keypairs = deployParams.get(PARAM_SSH_KEYPAIRS).split(",");
            for (String keypair : keypairs) {
                SSHKeyPairVO s = sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), keypair);
                if (s != null) {
                    sshKeyPairs.add(s.getName());
                } else {
                    logger.warn("Cannot find SSH keypair by name in sshkeypairs from otherdeployparams in AutoScale Instance profile");
                }
            }
        }
        return sshKeyPairs;
    }

    protected List<Long> getVmAffinityGroupId(Map<String, String> deployParams) {
        List<Long> affinityGroupIdList = new ArrayList<>();
        if (deployParams.get(PARAM_AFFINITY_GROUP_IDS) != null) {   // Affinity groups
            String[] affinityGroupIds = deployParams.get(PARAM_AFFINITY_GROUP_IDS).split(",");
            for (String affinityGroupId : affinityGroupIds) {
                AffinityGroupVO affintyGroup = affinityGroupDao.findByUuid(affinityGroupId);
                if (affintyGroup != null) {
                    affinityGroupIdList.add(affintyGroup.getId());
                } else {
                    logger.warn("Cannot find affinity group by affinitygroupids from otherdeployparams in AutoScale Vm profile");
                }
            }
        }
        return affinityGroupIdList;
    }

    public void updateVmDetails(Map<String, String> deployParams, Map<String, String> customParameters) {
        if (deployParams.get(PARAM_ROOT_DISK_SIZE) != null) {     // ROOT disk size
            String value = deployParams.get(PARAM_ROOT_DISK_SIZE);
            try {
                Long rootDiskSize = Long.parseLong(value);
                customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(rootDiskSize));
            } catch (NumberFormatException ex) {
                logger.warn("Cannot parse rootdisksize from otherdeployparams in AutoScale Vm profile");
            }
        }
    }

    protected boolean isWindowsOs(VirtualMachineTemplate template) {
        GuestOSVO guestOSVO = guestOSDao.findById(template.getGuestOSId());
        if (guestOSVO == null) {
            return false;
        }
        String osName = StringUtils.firstNonBlank(guestOSVO.getName(), guestOSVO.getDisplayName());
        if (StringUtils.isBlank(osName)) {
            return false;
        }
        return osName.toLowerCase().contains("windows");
    }

    protected Pair<String, String> getNextVmHostAndDisplayName(AutoScaleVmGroupVO asGroup, VirtualMachineTemplate template) {
        boolean isWindows = isWindowsOs(template);
        String winVmHostNameSuffix = RandomStringUtils.random(VM_HOSTNAME_RANDOM_SUFFIX_LENGTH, 0, 0, true, false, (char[])null, new SecureRandom()).toLowerCase();
        String vmHostNameSuffix = "-" + asGroup.getNextVmSeq() + "-" + winVmHostNameSuffix;
        // Truncate vm group name because max length of vm name is 63
        int subStringLength = Math.min(asGroup.getName().length(), 63 - VM_HOSTNAME_PREFIX.length() - vmHostNameSuffix.length());
        String name = VM_HOSTNAME_PREFIX + asGroup.getName().substring(0, subStringLength) + vmHostNameSuffix;
        if (!isWindows) {
            return new Pair<>(name, name);
        }
        return new Pair<>(WINDOWS_VM_HOSTNAME_PREFIX + winVmHostNameSuffix, name);
    }

    @Override
    public void checkAutoScaleVmGroupName(String groupName) {
        String errorMessage = "";
        if (groupName == null || groupName.length() > 255 || groupName.length() < 1) {
            errorMessage = "AutoScale Vm Group name must be between 1 and 255 characters long";
        } else if (!groupName.toLowerCase().matches("[a-z0-9-]*")) {
            errorMessage = "AutoScale Vm Group name may contain only the ASCII letters 'a' through 'z' (in a case-insensitive manner), " +
                    "the digits '0' through '9' and the hyphen ('-')";
        }
        if (StringUtils.isNotBlank(errorMessage)) {
            logger.warn(errorMessage);
            throw new InvalidParameterValueException("Invalid AutoScale VM group name. It can contain the ASCII letters 'a' through 'z', " +
                    "'A' through 'Z', the digits '0' through '9' and the hyphen ('-'), must be between 1 and 255 characters long.");
        }
    }

    protected UserVmVO startNewVM(long vmId) {
        try {
            CallContext.current().setEventDetails("Instance ID: " + vmId);
            return userVmMgr.startVirtualMachine(vmId, null, new HashMap<>(), null, false).first();
        } catch (final ResourceUnavailableException ex) {
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
            logger.info(ex);
            logger.info(message.toString(), ex);
            throw new ServerApiException(ApiErrorCode.INSUFFICIENT_CAPACITY_ERROR, message.toString());
        }
    }

    protected boolean assignLBruleToNewVm(long vmId, AutoScaleVmGroupVO asGroup) {
        List<Long> lstVmId = new ArrayList<Long>();
        long lbId = asGroup.getLoadBalancerId();

        List<LoadBalancerVMMapVO> lbVmMapVos = lbVmMapDao.listByLoadBalancerId(lbId);
        if (CollectionUtils.isNotEmpty(lbVmMapVos)) {
            for (LoadBalancerVMMapVO LbVmMapVo : lbVmMapVos) {
                long instanceId = LbVmMapVo.getInstanceId();
                if (instanceId == vmId && !LbVmMapVo.isRevoke()) {
                    try {
                        // DB membership alone does not prove that the previous VR apply succeeded.
                        return loadBalancingRulesService.applyLoadBalancerConfig(lbId);
                    } catch (ResourceUnavailableException | RuntimeException e) {
                        return false;
                    }
                }
            }
        }
        lstVmId.add(new Long(vmId));
        try {
            return loadBalancingRulesService.assignToLoadBalancer(lbId, lstVmId, new HashMap<>(), null, true);
        } catch (RuntimeException ex) {
            logger.warn("Caught exception: ", ex);
            return false;
        }
    }

    protected GlobalLock getGroupWorkLock(long id) {
        return GlobalLock.getInternLock("autoscale-work-" + id);
    }

    protected GlobalLock getGroupControlLock(long id) {
        return GlobalLock.getInternLock("autoscale-control-" + id);
    }

    protected <T> T withGroupControl(long id, boolean needsWorkLock, java.util.function.Supplier<T> action) {
        GlobalLock control = getGroupControlLock(id);
        GlobalLock work = needsWorkLock ? getGroupWorkLock(id) : null;
        boolean controlHeld = false;
        boolean workHeld = false;
        try {
            controlHeld = control.lock(10);
            workHeld = work == null || work.lock(1);
            if (!controlHeld || !workHeld) {
                throw new InvalidParameterValueException("AutoScale VM group has an operation in progress. Wait for it to finish and retry");
            }
            return action.get();
        } finally {
            if (work != null) {
                if (workHeld) { work.unlock(); }
                work.releaseRef();
            }
            if (controlHeld) { control.unlock(); }
            control.releaseRef();
        }
    }

    protected <T> T withAssociatedGroupControls(List<Long> groupIds, java.util.function.Supplier<T> action) {
        List<Long> orderedIds = groupIds.stream().distinct().sorted().collect(Collectors.toList());
        return withAssociatedGroupControls(orderedIds, 0, action);
    }

    private <T> T withAssociatedGroupControls(List<Long> groupIds, int index, java.util.function.Supplier<T> action) {
        if (index == groupIds.size()) {
            return action.get();
        }
        long id = groupIds.get(index);
        return withGroupControl(id, true, () -> {
            AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(id);
            if (group != null) {
                requireNoOperation(group);
            }
            return withAssociatedGroupControls(groupIds, index + 1, action);
        });
    }

    protected void requireNoOperation(AutoScaleVmGroupVO group) {
        if (group.isOperationInProgress()) {
            throw new InvalidParameterValueException("AutoScale VM group has an operation in progress. Wait for it to finish and retry");
        }
    }

    @Override
    public void doScaleUp(long groupId, Integer numVm) {
        if (numVm == null || numVm <= 0) { return; }
        GlobalLock lock = getGroupWorkLock(groupId);
        boolean held = false;
        try {
            held = lock.lock(1);
            if (!held) { return; }
            for (int i = 0; i < numVm; i++) {
                AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(groupId);
                if (group == null || group.getState() != AutoScaleVmGroup.State.ENABLED || group.isOperationInProgress() ||
                        !checkConditionUp(group, 1)) { return; }
                String token = UUID.randomUUID().toString();
                if (!autoScaleVmGroupDao.beginOperation(groupId, token, "SCALEUP")) { return; }
                ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, group.getAccountId(), EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEUP,
                        "Scaling up AutoScale VM group " + groupId, groupId, ApiCommandResourceType.AutoScaleVmGroup.toString(), true, 0);
                UserVm vm;
                try {
                    vm = Transaction.execute((TransactionCallback<UserVm>) status -> {
                    AutoScaleVmGroupVO current = autoScaleVmGroupDao.lockRow(groupId, true);
                    if (current == null || current.getState() != AutoScaleVmGroup.State.SCALING || !token.equals(current.getOperationUuid())) {
                        return null;
                    }
                    // Allocation, membership and operation target commit together, before any host start.
                    UserVm allocated = createNewVM(current);
                    if (allocated != null) {
                        autoScaleVmGroupVmMapDao.persist(new AutoScaleVmGroupVmMapVO(groupId, allocated.getId()));
                        if (!autoScaleVmGroupDao.checkpointOperation(groupId, token, "STARTING", allocated.getId(), null)) {
                            throw new CloudRuntimeException("Lost AutoScale operation ownership during allocation");
                        }
                    }
                    return allocated;
                    });
                } catch (RuntimeException e) {
                    recordLifecycleFailure(group, token, "ALLOCATING", null);
                    autoScaleVmGroupDao.finishOperation(groupId, token);
                    logger.warn("AutoScale group {} allocation failed before membership committed", groupId, e);
                    return;
                }
                if (vm == null) {
                    recordLifecycleFailure(group, token, "ALLOCATING", null);
                    autoScaleVmGroupDao.finishOperation(groupId, token);
                    return;
                }
                createInactiveDummyRecord(groupId);
                if (!resumeOperation(autoScaleVmGroupDao.findById(groupId))) { return; }
            }
        } finally {
            if (held) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    @Override
    public void doScaleDown(long groupId) {
        GlobalLock lock = getGroupWorkLock(groupId);
        boolean held = false;
        try {
            held = lock.lock(1);
            if (!held) { return; }
            AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(groupId);
            if (group == null || group.getState() != AutoScaleVmGroup.State.ENABLED || group.isOperationInProgress() ||
                    !checkConditionDown(group)) { return; }
            List<AutoScaleVmGroupVmMapVO> members = autoScaleVmGroupVmMapDao.listByGroup(groupId);
            Long vmId = null;
            for (AutoScaleVmGroupVmMapVO member : members) {
                UserVmVO vm = userVmDao.findById(member.getInstanceId());
                if (vm != null && vm.getState() == VirtualMachine.State.Running) {
                    vmId = vm.getId();
                }
            }
            if (vmId == null) { return; }
            String token = UUID.randomUUID().toString();
            final Long target = vmId;
            boolean accepted = Transaction.execute((TransactionCallback<Boolean>) status -> {
                AutoScaleVmGroupVO current = autoScaleVmGroupDao.lockRow(groupId, true);
                if (current == null || !autoScaleVmGroupDao.beginOperation(groupId, token, "SCALEDOWN")) { return false; }
                return autoScaleVmGroupDao.checkpointOperation(groupId, token, "DRAINING", target, null);
            });
            if (!accepted) { return; }
            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, group.getAccountId(), EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEDOWN,
                    "Scaling down AutoScale VM group " + groupId, groupId, ApiCommandResourceType.AutoScaleVmGroup.toString(), true, 0);
            createInactiveDummyRecord(groupId);
            resumeOperation(autoScaleVmGroupDao.findById(groupId));
        } finally {
            if (held) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    protected void recoverGroupOperation(long groupId) {
        GlobalLock lock = getGroupWorkLock(groupId);
        boolean held = false;
        try {
            held = lock.lock(1);
            if (!held) { return; }
            AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(groupId);
            if (group == null || !group.isOperationInProgress()) { return; }
            if (group.getOperationUuid() != null) {
                resumeOperation(group);
                return;
            }
            // Pre-upgrade SCALING has no durable target. Reconcile existing members before enabling evaluation.
            for (AutoScaleVmGroupVmMapVO member : autoScaleVmGroupVmMapDao.listByGroup(groupId)) {
                UserVmVO vm = userVmDao.findById(member.getInstanceId());
                if (vm != null && isTransientVm(vm)) { return; }
                if (vm != null && vm.getState() == VirtualMachine.State.Running && !assignLBruleToNewVm(vm.getId(), group)) { return; }
            }
            autoScaleVmGroupDao.updateState(groupId, AutoScaleVmGroup.State.SCALING, AutoScaleVmGroup.State.ENABLED);
        } catch (Exception e) {
            logger.warn("AutoScale VM group {} recovery will retry the recorded target", groupId, e);
        } finally {
            if (held) { lock.unlock(); }
            lock.releaseRef();
        }
    }

    protected boolean isTransientVm(UserVmVO vm) {
        return vm.getState() == VirtualMachine.State.Starting || vm.getState() == VirtualMachine.State.Stopping ||
                vm.getState() == VirtualMachine.State.Migrating;
    }

    protected boolean resumeOperation(AutoScaleVmGroupVO group) {
        String token = group.getOperationUuid();
        if (token == null) { return false; }
        if ("CLEANUP".equals(group.getOperationAction())) {
            return resumeCleanupOperation(group);
        }
        if ("RECOVERY".equals(group.getOperationAction())) {
            for (AutoScaleVmGroupVmMapVO member : autoScaleVmGroupVmMapDao.listByGroup(group.getId())) {
                UserVmVO existing = userVmDao.findById(member.getInstanceId());
                if (existing == null || existing.getState() == VirtualMachine.State.Destroyed || existing.getState() == VirtualMachine.State.Expunging) {
                    if (!destroyVm(member.getInstanceId())) { return false; }
                    autoScaleVmGroupVmMapDao.remove(group.getId(), member.getInstanceId());
                    continue;
                }
                if (isTransientVm(existing)) { return false; }
                if (existing.getState() == VirtualMachine.State.Stopped) { startNewVM(existing.getId()); }
                if (!assignLBruleToNewVm(existing.getId(), group)) { return false; }
            }
            return autoScaleVmGroupDao.finishOperation(group.getId(), token);
        }
        Long vmId = group.getOperationVmId();
        if (vmId == null) {
            // No VM was atomically allocated/selected before the interruption.
            return autoScaleVmGroupDao.finishOperation(group.getId(), token);
        }
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm != null && isTransientVm(vm)) { return false; }
        boolean remove = "SCALEDOWN".equals(group.getOperationAction()) ||
                (group.getOperationPhase() != null && group.getOperationPhase().startsWith("DESTROYING"));
        if (!remove && vm != null && vm.getState() != VirtualMachine.State.Destroyed && vm.getState() != VirtualMachine.State.Expunging) {
            if (vm.getState() == VirtualMachine.State.Stopped) {
                if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "STARTING", vmId, null)) { return false; }
                try {
                    startNewVM(vmId);
                    // The start service may return its pre-start VO. Only persisted state confirms readiness.
                    UserVmVO current = userVmDao.findById(vmId);
                    if (current == null || current.getState() != VirtualMachine.State.Running) {
                        if (current != null && isTransientVm(current)) { return false; }
                        recordLifecycleFailure(group, token, "STARTING", vmId);
                        return false;
                    }
                } catch (RuntimeException e) {
                    // Do not forget a failed allocation before its cleanup is confirmed.
                    recordLifecycleFailure(group, token, "STARTING", vmId);
                    autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "DESTROYING", vmId, null);
                    logger.warn("AutoScale start failed for recorded VM {}", vmId, e);
                    return cleanupOperationVm(group, token, vmId);
                }
            } else if (vm.getState() != VirtualMachine.State.Running) {
                return false;
            }
            if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "ASSIGNING", vmId, null)) { return false; }
            if (!assignLBruleToNewVm(vmId, group)) {
                recordLifecycleFailure(group, token, "ASSIGNING", vmId);
                return false;
            }
            updateOperationQuietTime(group, AutoScalePolicy.Action.SCALEUP);
            createInactiveDummyRecord(group.getId());
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, group.getAccountId(), EventVO.LEVEL_INFO,
                    EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEUP, "Started and assigned LB rule for VM " + vmId,
                    group.getId(), ApiCommandResourceType.AutoScaleVmGroup.toString(), 0);
            return autoScaleVmGroupDao.finishOperation(group.getId(), token);
        }
        if ("SCALEDOWN".equals(group.getOperationAction()) &&
                (group.getOperationPhase() == null || !group.getOperationPhase().startsWith("DESTROYING"))) {
            Date deadline = group.getOperationDeadline();
            if (deadline == null) {
                if (!excludeLifecycleMembers(group, token, Collections.singletonList(vmId))) { return false; }
                AutoScaleVmProfileVO profile = autoScaleVmProfileDao.findById(group.getProfileId());
                deadline = new Date(currentTimeMillis() + Math.max(0, profile.getExpungeVmGracePeriod()) * 1000L);
                if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "DRAINING", vmId, deadline)) { return false; }
            }
            if (deadline.getTime() > currentTimeMillis()) { return false; }
        }
        return cleanupOperationVm(group, token, vmId);
    }

    protected boolean cleanupOperationVm(AutoScaleVmGroupVO group, String token, Long vmId) {
        if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "DESTROYING", vmId, group.getOperationDeadline())) { return false; }
        if (!destroyVm(vmId)) {
            recordLifecycleFailure(group, token, "DESTROYING", vmId);
            return false;
        }
        autoScaleVmGroupVmMapDao.remove(group.getId(), vmId);
        if ("SCALEDOWN".equals(group.getOperationAction())) {
            updateOperationQuietTime(group, AutoScalePolicy.Action.SCALEDOWN);
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, group.getAccountId(), EventVO.LEVEL_INFO,
                    EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEDOWN, "Destroyed recorded VM " + vmId,
                    group.getId(), ApiCommandResourceType.AutoScaleVmGroup.toString(), 0);
        }
        createInactiveDummyRecord(group.getId());
        return autoScaleVmGroupDao.finishOperation(group.getId(), token);
    }

    protected boolean excludeLifecycleMembers(AutoScaleVmGroupVO group, String token, List<Long> vmIds) {
        try {
            if (loadBalancingRulesService.removeFromLoadBalancer(group.getLoadBalancerId(), vmIds, new HashMap<>(), true)) { return true; }
        } catch (RuntimeException e) {
            logger.warn("AutoScale group {} LB exclusion will retry its recorded members", group.getId(), e);
        }
        recordLifecycleFailure(group, token, "EXCLUDING", group.getOperationVmId());
        return false;
    }

    protected void recordLifecycleFailure(AutoScaleVmGroupVO group, String token, String phase, Long vmId) {
        autoScaleVmGroupDao.checkpointOperation(group.getId(), token, phase + "_RETRY", vmId, group.getOperationDeadline());
        if (!(phase + "_RETRY").equals(group.getOperationPhase())) {
            String event = "SCALEDOWN".equals(group.getOperationAction()) ? EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEDOWN :
                    "CLEANUP".equals(group.getOperationAction()) ? EventTypes.EVENT_AUTOSCALEVMGROUP_DELETE : EventTypes.EVENT_AUTOSCALEVMGROUP_SCALEUP;
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, group.getAccountId(), EventVO.LEVEL_ERROR, event,
                    "AutoScale lifecycle failed at " + phase + "; recorded members are retained for reconciliation",
                    group.getId(), ApiCommandResourceType.AutoScaleVmGroup.toString(), 0);
        }
    }

    protected boolean resumeCleanupOperation(AutoScaleVmGroupVO group) {
        String token = group.getOperationUuid();
        List<AutoScaleVmGroupVmMapVO> members = autoScaleVmGroupVmMapDao.listByGroup(group.getId());
        Date deadline = group.getOperationDeadline();
        if (!members.isEmpty() && deadline == null) {
            List<Long> ids = members.stream().map(AutoScaleVmGroupVmMapVO::getInstanceId).collect(Collectors.toList());
            if (!excludeLifecycleMembers(group, token, ids)) { return false; }
            AutoScaleVmProfileVO profile = autoScaleVmProfileDao.findById(group.getProfileId());
            deadline = new Date(currentTimeMillis() + Math.max(0, profile.getExpungeVmGracePeriod()) * 1000L);
            if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "DRAINING", null, deadline)) { return false; }
        }
        if (deadline != null && deadline.getTime() > currentTimeMillis()) { return false; }
        group.setOperationDeadline(deadline);
        for (AutoScaleVmGroupVmMapVO member : members) {
            long vmId = member.getInstanceId();
            if (!autoScaleVmGroupDao.checkpointOperation(group.getId(), token, "DESTROYING", vmId, deadline)) { return false; }
            if (!destroyVm(vmId)) {
                recordLifecycleFailure(group, token, "DESTROYING", vmId);
                return false;
            }
            autoScaleVmGroupVmMapDao.remove(group.getId(), vmId);
        }
        return autoScaleVmGroupDao.finishOperation(group.getId(), token);
    }

    protected void updateOperationQuietTime(AutoScaleVmGroupVO group, AutoScalePolicy.Action action) {
        Date completed = new Date(currentTimeMillis());
        autoScaleVmGroupDao.updateLastScaled(group.getId(), completed);
        for (AutoScaleVmGroupPolicyMapVO mapping : autoScaleVmGroupPolicyMapDao.listByVmGroupId(group.getId())) {
            AutoScalePolicyVO policy = autoScalePolicyDao.findById(mapping.getPolicyId());
            if (policy != null) {
                policy.setLastQuietTime(completed);
                autoScalePolicyDao.persist(policy);
            }
        }
    }

    @Override
    public String getConfigComponentName() {
        return AutoScaleManager.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                AutoScaleStatsInterval,
                AutoScaleStatsCleanupDelay,
                AutoScaleStatsWorker,
                AutoScaleErroredInstanceThreshold
        };
    }

    @Override
    public void checkAllAutoScaleVmGroups() {
        // list all AS VMGroups
        List<AutoScaleVmGroupVO> asGroups = autoScaleVmGroupDao.listAll();
        CompletionService<Pair<Long, Boolean>> completionService = new ExecutorCompletionService<>(groupExecutor);
        for (AutoScaleVmGroupVO asGroup : asGroups) {
            completionService.submit(new CheckAutoScaleVmGroupAsync(asGroup));
        }
        for (int i = 0; i < asGroups.size(); i++) {
            try {
                Future<Pair<Long, Boolean>> future = completionService.take();
                Pair<Long, Boolean> result = future.get();
                logger.debug("Checked AutoScale vm group " + result.first() + " with result: " + result.second());
            } catch (ExecutionException ex) {
                logger.warn("Failed to get result of checking AutoScale vm group due to Exception: " , ex);
            } catch (InterruptedException ex) {
                logger.warn("Failed to get result of checking AutoScale vm group due to Exception: " , ex);
                Thread.currentThread().interrupt();
            }
        }
    }

    protected class CheckAutoScaleVmGroupAsync implements Callable<Pair<Long, Boolean>> {
        AutoScaleVmGroupVO asGroup;

        public CheckAutoScaleVmGroupAsync(AutoScaleVmGroupVO asGroup) {
            this.asGroup = asGroup;
        }

        @Override
        public Pair<Long, Boolean> call() {
            try {
                logger.debug("Checking AutoScale vm group " + asGroup);
                checkAutoScaleVmGroup(asGroup);
            } catch (Exception ex) {
                logger.warn("Failed to check AutoScale vm group " + asGroup + " due to Exception: " , ex);
                return new Pair<>(asGroup.getId(), false);
            }
            return new Pair<>(asGroup.getId(), true);
        }
    }

    @Override
    public void checkAutoScaleVmGroup(AutoScaleVmGroupVO asGroup) {
        asGroup = autoScaleVmGroupDao.findById(asGroup.getId());
        if (asGroup == null) {
            return;
        }
        if (asGroup.isOperationInProgress()) {
            recoverGroupOperation(asGroup.getId());
            return;
        }
        // check fresh group state
        if (asGroup.getState().equals(AutoScaleVmGroup.State.ENABLED)) {
            Network.Provider provider = getLoadBalancerServiceProvider(asGroup.getLoadBalancerId());
            if (Network.Provider.Netscaler.equals(provider)) {
                checkNetScalerAsGroup(asGroup);
            } else if (Network.Provider.VirtualRouter.equals(provider) || Network.Provider.VPCVirtualRouter.equals(provider) ||
                       Network.Provider.Netris.equals(provider)) {
                checkAutoscalingGroup(asGroup);
            }
        }
    }

    protected boolean isNative(AutoScaleVmGroupTO groupTO) {
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                CounterTO counterTO = conditionTO.getCounter();
                if (Counter.NativeSources.contains(counterTO.getSource())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean hasSourceVirtualRouter(AutoScaleVmGroupTO groupTO) {
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                CounterTO counterTO = conditionTO.getCounter();
                if (Counter.Source.VIRTUALROUTER.equals(counterTO.getSource())) {
                    return true;
                }
            }
        }
        return false;
    }

    protected Map<Long, List<Long>> getHostAndVmIdsMap(AutoScaleVmGroupTO groupTO) {
        Map<Long, List<Long>> hostAndVmIdsMap = new HashMap<>();

        List<AutoScaleVmGroupVmMapVO> asGroupVmVOs = autoScaleVmGroupVmMapDao.listByGroup(groupTO.getId());
        for (AutoScaleVmGroupVmMapVO asGroupVmVO : asGroupVmVOs) {
            updateHostAndVmIdsMap(hostAndVmIdsMap, asGroupVmVO);
        }
        return hostAndVmIdsMap;
    }

    protected void updateHostAndVmIdsMap(Map<Long, List<Long>> hostAndVmIdsMap, AutoScaleVmGroupVmMapVO asGroupVmVO) {
        Long vmId = asGroupVmVO.getInstanceId();
        UserVmVO vm = userVmDao.findById(vmId);
        Long vmHostId = DEFAULT_HOST_ID;
        if (VirtualMachine.State.Running.equals(vm.getState())) {
            vmHostId = vm.getHostId();
        } else if (VirtualMachine.State.Migrating.equals(vm.getState())) {
            vmHostId = vm.getLastHostId();
        }
        List<Long> vmIds = hostAndVmIdsMap.get(vmHostId);
        if (vmIds == null) {
            vmIds = new ArrayList<>();
        }
        vmIds.add(vmId);
        hostAndVmIdsMap.put(vmHostId, vmIds);
    }

    protected Map<Long, List<CounterTO>> getPolicyCounters(AutoScaleVmGroupTO groupTO) {
        Map<Long, List<CounterTO>> counters = new HashMap<>();
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            List<CounterTO> counterTOs = new ArrayList<>();
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                CounterTO counterTO = conditionTO.getCounter();
                counterTOs.add(counterTO);
            }
            counters.put(policyTO.getId(), counterTOs);
        }
        return counters;
    }

    protected AutoScalePolicy.Action getAutoscaleAction(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap, AutoScaleVmGroupTO groupTO) {
        logger.debug("[AutoScale] Getting autoscale action for group [id={}, uuid={}]", groupTO.getId(), groupTO.getUuid());

        Network.Provider provider = getLoadBalancerServiceProvider(groupTO.getLoadBalancerId());

        for (AutoScalePolicyTO policyTO : groupTO.getPolicies().stream().sorted(Comparator.comparing(p -> p.getAction() != AutoScalePolicy.Action.SCALEUP)).collect(Collectors.toList())) {
            if (isQuitTimePassForPolicy(policyTO)) {
                AutoScalePolicy.Action determinedAction = checkConditionsForPolicy(countersMap, countersNumberMap, policyTO, provider);
                if (determinedAction != null) {
                    return determinedAction;
                }
            }
        }
        return null;
    }

    protected boolean isQuitTimePassForPolicy(AutoScalePolicyTO policyTO) {
        int quietTime = policyTO.getQuietTime();
        Date quietTimeDate = policyTO.getLastQuietTime();
        long lastQuiettime = 0L;
        if (quietTimeDate != null) {
            lastQuiettime = policyTO.getLastQuietTime().getTime();
        }
        long currentTime = (new Date()).getTime();
        return (currentTime - lastQuiettime) >= quietTime * 1000L;
    }

    protected AutoScalePolicy.Action checkConditionsForPolicy(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap, AutoScalePolicyTO policyTO, Network.Provider provider) {
        boolean bValid = CollectionUtils.isNotEmpty(policyTO.getConditions());
        for (ConditionTO conditionTO : policyTO.getConditions()) {
            CounterTO counter = conditionTO.getCounter();
            long thresholdValue = conditionTO.getThreshold();
            Double thresholdPercent = (double)thresholdValue;

            String key = generateKeyFromPolicyAndConditionAndCounter(policyTO.getId(), conditionTO.getId(), counter.getId());
            if (Network.Provider.Netscaler.equals(provider)) {
                key = generateKeyFromPolicyAndConditionAndCounter(0L, conditionTO.getId(), counter.getId());
            }
            Double sum = countersMap.get(key);
            Integer number = countersNumberMap.get(key);
            logger.debug("Checking policy [id: {}, uuid: {}], condition [id: {}, uuid: {}], counter = \"{}\", sum = {}, number = {}",
                    policyTO.getId(), policyTO.getUuid(), conditionTO.getId(), conditionTO.getUuid(), counter.getName(), sum, number);
            if (number == null || number <= 0 || sum == null || !Double.isFinite(sum)) {
                bValid = false;
                logger.debug("Skipping policy [id: {}, uuid: {}], condition [id: {}, uuid: {}], counter = \"{}\" because the number is {}",
                        policyTO.getId(), policyTO.getUuid(), conditionTO.getId(), conditionTO.getUuid(), counter.getName(), number);
                break;
            }
            Double avg = sum / number;
            Condition.Operator op = conditionTO.getRelationalOperator();
            boolean bConditionCheck = ((op == com.cloud.network.as.Condition.Operator.EQ) && (thresholdPercent.equals(avg)))
                    || ((op == com.cloud.network.as.Condition.Operator.GE) && (avg.doubleValue() >= thresholdPercent.doubleValue()))
                    || ((op == com.cloud.network.as.Condition.Operator.GT) && (avg.doubleValue() > thresholdPercent.doubleValue()))
                    || ((op == com.cloud.network.as.Condition.Operator.LE) && (avg.doubleValue() <= thresholdPercent.doubleValue()))
                    || ((op == com.cloud.network.as.Condition.Operator.LT) && (avg.doubleValue() < thresholdPercent.doubleValue()));

            logger.debug("Check result on policy [id: {}, uuid: {}], condition [id: {}, uuid: {}], counter = {} is : {}" +
                            " (actual result = {}, operator = {}, threshold = {})",
                    policyTO.getId(), policyTO.getUuid(), conditionTO.getId(), conditionTO.getUuid(), counter.getSource(), bConditionCheck, avg, op, thresholdPercent);

            if (!bConditionCheck) {
                bValid = false;
                break;
            }
        }
        AutoScalePolicy.Action action = bValid ? policyTO.getAction() : null;
        logger.debug("Check result on policy [id: {}, uuid: {}] is {}", policyTO.getId(), policyTO.getUuid(), action);

        return action;
    }

    private List<Pair<String, Integer>> getPairofCounternameAndDuration(AutoScaleVmGroupTO groupTO) {
        List<Pair<String, Integer>> result = new ArrayList<>();

        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            //get duration
            Integer duration = policyTO.getDuration();
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                CounterTO counter = conditionTO.getCounter();
                StringBuilder buff = new StringBuilder();
                buff.append(counter.getName());
                buff.append(",");
                buff.append(conditionTO.getId());
                // add to result
                Pair<String, Integer> pair = new Pair<>(buff.toString(), duration);
                result.add(pair);
            }
        }
        return result;
    }

    protected Network getNetwork(Long loadBalancerId) {
        final LoadBalancerVO loadBalancer = lbDao.findById(loadBalancerId);
        if (loadBalancer == null) {
            throw new CloudRuntimeException(String.format("Unable to find load balancer with id: %s ", loadBalancerId));
        }
        Network network = networkDao.findById(loadBalancer.getNetworkId());
        if (network == null) {
            throw new CloudRuntimeException(String.format("Unable to find network with id: %s for load balancer: %s", loadBalancer.getNetworkId(), loadBalancer));
        }
        return network;
    }

    protected Pair<String, Integer> getPublicIpAndPort(Long loadBalancerId) {
        final LoadBalancerVO loadBalancer = lbDao.findById(loadBalancerId);
        if (loadBalancer == null) {
            throw new CloudRuntimeException(String.format("Unable to find load balancer with id: %s ", loadBalancerId));
        }
        IPAddressVO ipAddress = ipAddressDao.findById(loadBalancer.getSourceIpAddressId());
        if (ipAddress == null) {
            throw new CloudRuntimeException(String.format("Unable to find IP Address with id: %s for load balancer: %s", loadBalancer.getSourceIpAddressId(), loadBalancer));
        }
        return new Pair<>(ipAddress.getAddress().addr(), loadBalancer.getSourcePortStart());
    }

    protected Network.Provider getLoadBalancerServiceProvider(Long loadBalancerId) {
        final LoadBalancerVO loadBalancer = lbDao.findById(loadBalancerId);
        if (loadBalancer == null) {
            throw new CloudRuntimeException(String.format("Unable to find load balancer with id: %s ", loadBalancerId));
        }
        return lbRulesMgr.getLoadBalancerServiceProvider(loadBalancer);
    }

    protected boolean checkAsGroupMaxAndMinMembers(AutoScaleVmGroupVO asGroup) {
        // check minimum vm of group
        Integer currentVM = autoScaleVmGroupVmMapDao.countAvailableVmsByGroup(asGroup.getId());
        if (currentVM < asGroup.getMinMembers()) {
            logger.debug(String.format("There are currently %s available VMs which is less than the minimum member of " +
                    "the AS group (%s), scaling up %d VMs", currentVM, asGroup.getMinMembers(), asGroup.getMinMembers() - currentVM));
            doScaleUp(asGroup.getId(), asGroup.getMinMembers() - currentVM);
            return false;
        }

        // check maximum vm of group
        if (currentVM > asGroup.getMaxMembers()) {
            logger.debug(String.format("There are currently %s available VMs which is more than the maximum member of " +
                    "the AS group (%s), scaling down %d VMs", currentVM, asGroup.getMaxMembers(), currentVM - asGroup.getMaxMembers()));
            for (int i = 0; i <  currentVM - asGroup.getMaxMembers(); i++) {
                doScaleDown(asGroup.getId());
            }
            return false;
        }
        return true;
    }

    protected void checkNetScalerAsGroup(AutoScaleVmGroupVO asGroup) {
        AutoScaleVmGroupTO groupTO = lbRulesMgr.toAutoScaleVmGroupTO(asGroup);

        if (!isNative(groupTO)) {
            return;
        }

        if (!checkAsGroupMaxAndMinMembers(asGroup)) {
            return;
        }

        //check interval
        long now = currentTimeMillis();
        if (asGroup.getLastInterval() != null && (now - asGroup.getLastInterval().getTime()) < TimeUnit.SECONDS.toMillis(asGroup.getInterval())) {
            return;
        }

        // update last_interval
        asGroup.setLastInterval(new Date(now));
        if (!autoScaleVmGroupDao.updateLastInterval(asGroup.getId(), asGroup.getLastInterval())) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("[Netscaler AutoScale] Collecting RRDs data...");
        }
        Map<String, String> params = new HashMap<>();
        List<AutoScaleVmGroupVmMapVO> asGroupVmVOs = autoScaleVmGroupVmMapDao.listByGroup(asGroup.getId());
        params.put("total_vm", String.valueOf(asGroupVmVOs.size()));
        for (int i = 0; i < asGroupVmVOs.size(); i++) {
            long vmId = asGroupVmVOs.get(i).getInstanceId();
            VMInstanceVO vmVO = vmInstanceDao.findById(vmId);
            //xe vm-list | grep vmname -B 1 | head -n 1 | awk -F':' '{print $2}'
            params.put("vmname" + (i + 1), vmVO.getInstanceName());
            params.put("vmid" + (i + 1), String.valueOf(vmVO.getId()));

        }
        // get random hostid because all vms are in a cluster
        long vmId = asGroupVmVOs.get(0).getInstanceId();
        VMInstanceVO vmVO = vmInstanceDao.findById(vmId);
        Long receiveHost = vmVO.getHostId();

        setPerformanceMonitorCommandParams(groupTO, params);

        PerformanceMonitorCommand perfMon = new PerformanceMonitorCommand(params, 20);

        try {
            PerformanceMonitorAnswer answer = (PerformanceMonitorAnswer) agentMgr.send(receiveHost, perfMon);
            if (answer == null || !answer.getResult()) {
                logger.debug("Failed to send data to node !");
            } else {
                String result = answer.getDetails();
                logger.debug("[AutoScale] RRDs collection answer: " + result);
                HashMap<String, Double> countersMap = new HashMap<>();
                HashMap<String, Integer> countersNumberMap = new HashMap<>();

                processPerformanceMonitorAnswer(countersMap, countersNumberMap, groupTO, params, result);

                AutoScalePolicy.Action scaleAction = getAutoscaleAction(countersMap, countersNumberMap, groupTO);
                if (scaleAction != null) {
                    logger.debug("[AutoScale] Doing scale action: {} for group {}", scaleAction, asGroup);
                    if (AutoScalePolicy.Action.SCALEUP.equals(scaleAction)) {
                        doScaleUp(asGroup.getId(), 1);
                    } else {
                        doScaleDown(asGroup.getId());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Cannot sent PerformanceMonitorCommand to host " + receiveHost + " or process the answer due to Exception: ", e);
        }
    }

    protected void setPerformanceMonitorCommandParams(AutoScaleVmGroupTO groupTO, Map<String, String> params) {
        // setup parameters phase: duration and counter
        // list pair [counter, duration]
        List<Pair<String, Integer>> lstPair = getPairofCounternameAndDuration(groupTO);
        int totalCounter = 0;
        String[] lstCounter = new String[lstPair.size()];
        for (int i = 0; i < lstPair.size(); i++) {
            Pair<String, Integer> pair = lstPair.get(i);
            String strCounterNames = pair.first();
            Integer duration = pair.second();

            lstCounter[i] = strCounterNames.split(",")[0];  // counter name
            totalCounter++;
            params.put("duration" + totalCounter, duration.toString());
            params.put("counter" + totalCounter, lstCounter[i]);
            params.put("con" + totalCounter, strCounterNames.split(",")[1]);    // condition id
        }
        params.put("totalCounter", String.valueOf(totalCounter));
    }

    protected void processPerformanceMonitorAnswer(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap,
                                                 AutoScaleVmGroupTO groupTO, Map<String, String> params, String details) {
        // extract data
        String[] counterElements = details.split(",");
        if ((counterElements != null) && (counterElements.length > 0)) {
            for (String string : counterElements) {
                try {
                    String[] counterVals = string.split(":");
                    String[] counterVm = counterVals[0].split("\\.");

                    Long counterId = Long.parseLong(counterVm[1]);

                    ConditionTO condition = new ConditionTO(Long.parseLong(params.get("con" + counterVm[0])), null, 0L, null, null);

                    // For NetScaler, the policyId is not returned in PerformanceMonitorAnswer
                    AutoScalePolicyTO policy = new AutoScalePolicyTO(0L, null, 0, 0, null, null, null, false);

                    Double coVal = Double.parseDouble(counterVals[1]);

                    updateCountersMapWithInstantData(countersMap, countersNumberMap, groupTO, counterId, condition, policy, coVal, AutoScaleValueType.INSTANT_VM);

                } catch (Exception e) {
                    logger.error("Cannot process PerformanceMonitorAnswer due to Exception: ", e);
                }
            }
        }
    }

    protected void updateCountersMapWithInstantData(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap, AutoScaleVmGroupTO groupTO,
                                                    Long counterId, ConditionTO condition, AutoScalePolicyTO policy, Double coVal, AutoScaleValueType valueType) {
        // Summary of all counter by counterId key
        String key = generateKeyFromPolicyAndConditionAndCounter(policy.getId(), condition.getId(), counterId);

        CounterVO counter = counterDao.findById(counterId);
        if (counter == null) {
            return;
        }
        if (Counter.Source.MEMORY.equals(counter.getSource())) {
            // calculate memory in percent
            AutoScaleVmProfileTO profile = groupTO.getProfile();
            ServiceOfferingVO serviceOff = serviceOfferingDao.findByUuidIncludingRemoved(profile.getServiceOfferingId());
            int maxRAM = serviceOff.getRamSize();

            // get current RAM percent
            coVal = coVal / maxRAM * 100;
        } else if (Counter.Source.CPU.equals(counter.getSource())) {
            // cpu
            coVal = coVal * 100;
        }

        if (AutoScaleValueType.INSTANT_VM_GROUP.equals(valueType)) {
            Integer currentVM = autoScaleVmGroupVmMapDao.countAvailableVmsByGroup(groupTO.getId());
            if (currentVM == 0) {
                logger.debug("Skipping updating countersMap for group [id={}, uuid={}] " +
                        "and policy [id={}, uuid={}] and counter [id={}, uuid={}] due to no VMs",
                        groupTO.getId(), groupTO.getUuid(), policy.getId(), policy.getUuid(), counter.getId(), counter.getUuid());
                return;
            }
            coVal = coVal / currentVM;
        }

        updateCountersMapWithProcessedData(countersMap, countersNumberMap, key, coVal);
    }

    protected void updateCountersMapWithProcessedData(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap, String key, Double coVal) {
        /* initialize if data is not set */
        countersMap.computeIfAbsent(key, k -> Double.valueOf(0));
        countersNumberMap.computeIfAbsent(key, k -> 0);

        // update data entry
        countersMap.put(key, countersMap.get(key) + coVal);
        countersNumberMap.put(key, countersNumberMap.get(key) + 1);
    }

    protected void monitorAutoscalingGroup(AutoScaleVmGroupVO asGroup) {
        if (!checkAsGroupMaxAndMinMembers(asGroup)) {
            return;
        }

        //check interval
        long now = currentTimeMillis();
        if (asGroup.getLastInterval() != null && (now - asGroup.getLastInterval().getTime()) < TimeUnit.SECONDS.toMillis(asGroup.getInterval())) {
            return;
        }

        // update last_interval
        asGroup.setLastInterval(new Date(now));
        if (!autoScaleVmGroupDao.updateLastInterval(asGroup.getId(), asGroup.getLastInterval())) {
            return;
        }

        logger.debug("[AutoScale] Collecting performance data ...");

        AutoScaleVmGroupTO groupTO = lbRulesMgr.toAutoScaleVmGroupTO(asGroup);

        if (isNative(groupTO)) {
            logger.debug("[AutoScale] Collecting performance data from hosts ...");
            getVmStatsFromHosts(groupTO);
        }

        if (hasSourceVirtualRouter(groupTO)) {
            logger.debug("[AutoScale] Collecting performance data from virtual router ...");
            getNetworkStatsFromVirtualRouter(groupTO);
        }
    }

    protected long currentTimeMillis() { return System.currentTimeMillis(); }

    protected void checkAutoscalingGroup(AutoScaleVmGroupVO asGroup) {
        AutoScaleVmGroupTO groupTO = lbRulesMgr.toAutoScaleVmGroupTO(asGroup);
        AutoScalePolicy.Action scaleAction;
        try {
            scaleAction = evaluateAutoScalePolicies(groupTO);
        } finally {
            cleanupAsVmGroupStatistics(groupTO);
        }
        if (scaleAction == AutoScalePolicy.Action.SCALEUP) { doScaleUp(asGroup.getId(), 1); }
        else if (scaleAction == AutoScalePolicy.Action.SCALEDOWN) { doScaleDown(asGroup.getId()); }
    }

    protected AutoScalePolicy.Action evaluateAutoScalePolicies(AutoScaleVmGroupTO group) {
        long now = currentTimeMillis();
        AutoScaleVmGroupVO current = autoScaleVmGroupDao.findById(group.getId());
        Set<Long> members = getHostAndVmIdsMap(group).values().stream().flatMap(List::stream).collect(Collectors.toSet());
        int memberCount = members.size();
        List<AutoScalePolicyTO> policies = group.getPolicies();
        long quietMs = policies.stream().mapToLong(p -> TimeUnit.SECONDS.toMillis(Math.max(0, p.getQuietTime()))).max().orElse(0);
        long cooldownUntil = current == null || current.getLastScaled() == null ? 0 : current.getLastScaled().getTime() + quietMs;
        JsonObject evaluation = new JsonObject();
        evaluation.addProperty("evaluatedat", now);
        evaluation.addProperty("cooldownuntil", cooldownUntil);
        JsonArray policyResults = new JsonArray();
        evaluation.add("policies", policyResults);
        boolean up = false;
        boolean down = false;
        boolean upBlocked = false;
        boolean anyBlocked = false;
        Long latestCollection = null;
        for (AutoScalePolicyTO policy : policies) {
            JsonObject result = new JsonObject();
            result.addProperty("id", policy.getUuid());
            result.addProperty("action", policy.getAction() == null ? null : policy.getAction().name());
            result.addProperty("duration", policy.getDuration());
            JsonArray conditions = new JsonArray();
            result.add("conditions", conditions);
            String reason = null;
            boolean matched = CollectionUtils.isNotEmpty(policy.getConditions());
            long start = now - TimeUnit.SECONDS.toMillis(policy.getDuration());
            Date queryStart = new Date(start - TimeUnit.SECONDS.toMillis(group.getInterval()) * 2);
            List<AutoScaleVmGroupStatisticsVO> dummy = asGroupStatisticsDao.listDummyRecordsByVmGroup(group.getId(), queryStart);
            List<AutoScaleVmGroupStatisticsVO> inactive = asGroupStatisticsDao.listInactiveByVmGroupAndPolicy(group.getId(), policy.getId(), queryStart);
            for (ConditionTO condition : policy.getConditions()) {
                CounterTO counter = condition.getCounter();
                boolean nativeCounter = Counter.NativeSources.contains(counter.getSource());
                Set<Long> resources = members;
                if (!nativeCounter) {
                    Network network = getNetwork(group.getLoadBalancerId());
                    resources = routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER).stream()
                            .filter(this::isActiveMetricsRouter).map(DomainRouterVO::getId).collect(Collectors.toSet());
                }
                double multiplier = 1;
                if (counter.getSource() == Counter.Source.CPU) { multiplier = 100; }
                if (counter.getSource() == Counter.Source.MEMORY) {
                    ServiceOfferingVO offering = serviceOfferingDao.findByUuidIncludingRemoved(group.getProfile().getServiceOfferingId());
                    multiplier = offering == null || offering.getRamSize() <= 0 ? 0 : 100d / offering.getRamSize();
                }
                List<AutoScaleVmGroupStatisticsVO> records = asGroupStatisticsDao.listByVmGroupAndPolicyAndCounter(group.getId(), policy.getId(), counter.getId(), queryStart);
                AutoScaleMetricWindow.Result window = AutoScaleMetricWindow.evaluate(records, resources, policy.getDuration(), group.getInterval(), now,
                        multiplier, !nativeCounter, memberCount);
                long observedStart = (window.getCollectedAt() == null ? now : window.getCollectedAt()) - TimeUnit.SECONDS.toMillis(policy.getDuration());
                if (dummy.stream().anyMatch(r -> r.getCreated() == null || r.getCreated().getTime() > observedStart)) { reason = "MEMBERS_CHANGED"; }
                if (inactive.stream().anyMatch(r -> r.getCreated() == null || r.getCreated().getTime() >= observedStart)) { reason = "COLLECTION_UNAVAILABLE"; }
                if (!window.isReady()) { reason = reason == null ? window.getReason() : reason; }
                boolean holds = window.isReady() && !window.getPoints().isEmpty() && window.getPoints().stream()
                        .allMatch(value -> AutoScaleMetricWindow.compare(value, condition.getRelationalOperator(), condition.getThreshold()));
                matched &= holds;
                JsonObject metric = new JsonObject();
                metric.addProperty("id", condition.getUuid());
                metric.addProperty("countername", counter.getName());
                metric.addProperty("source", nativeCounter ? counter.getSource().name() : "VIRTUALROUTER");
                metric.addProperty("unit", nativeCounter ? "%" : counter.getValue().contains("mbps") ? "Mbps/VM" : "connections/VM");
                metric.addProperty("operator", condition.getRelationalOperator() == null ? null : condition.getRelationalOperator().name());
                metric.addProperty("threshold", condition.getThreshold());
                metric.addProperty("value", window.getValue());
                metric.addProperty("samples", window.getSamples());
                metric.addProperty("resources", resources.size());
                metric.addProperty("collectedat", window.getCollectedAt());
                metric.addProperty("reason", window.getReason());
                metric.addProperty("matched", holds);
                conditions.add(metric);
                if (window.getCollectedAt() != null) { latestCollection = latestCollection == null ? window.getCollectedAt() : Math.max(latestCollection, window.getCollectedAt()); }
            }
            if (CollectionUtils.isEmpty(policy.getConditions())) { reason = "INVALID_CONFIGURATION"; }
            boolean blocked = reason != null;
            result.addProperty("status", blocked ? "BLOCKED" : matched ? "MATCHED" : "NOT_MATCHED");
            result.addProperty("reason", reason);
            policyResults.add(result);
            anyBlocked |= blocked;
            if (policy.getAction() == AutoScalePolicy.Action.SCALEUP) { up |= matched && !blocked; upBlocked |= blocked; }
            else if (policy.getAction() == AutoScalePolicy.Action.SCALEDOWN) { down |= matched && !blocked; }
        }
        AutoScalePolicy.Action action = up ? AutoScalePolicy.Action.SCALEUP : down && !upBlocked ? AutoScalePolicy.Action.SCALEDOWN : null;
        String reason = upBlocked && down && !up ? "SCALEUP_UNCERTAIN" : null;
        if (now < cooldownUntil) { action = null; reason = "COOLDOWN"; }
        if (action == AutoScalePolicy.Action.SCALEUP && memberCount >= group.getMaxMembers()) { action = null; reason = "MAX_MEMBERS"; }
        if (action == AutoScalePolicy.Action.SCALEDOWN && memberCount <= group.getMinMembers()) { action = null; reason = "MIN_MEMBERS"; }
        evaluation.addProperty("collectedat", latestCollection);
        evaluation.addProperty("action", action == null ? null : action.name());
        evaluation.addProperty("status", reason != null ? reason : action != null ? action.name() : anyBlocked ? "WAITING" : "NO_ACTION");
        evaluation.addProperty("reason", reason);
        autoScaleVmGroupDao.updateEvaluation(group.getId(), evaluation.toString(), new Date(now));
        return action;
    }

    protected boolean isActiveMetricsRouter(DomainRouterVO router) {
        return router.getState() == VirtualMachine.State.Running && (!router.getIsRedundantRouter() ||
                router.getRedundantState() == VirtualRouter.RedundantState.PRIMARY);
    }

    protected void getVmStatsFromHosts(AutoScaleVmGroupTO groupTO) {
        // group vms by host id
        Map<Long, List<Long>> hostAndVmIdsMap = getHostAndVmIdsMap(groupTO);

        Map<Long, List<CounterTO>> policyCountersMap = getPolicyCounters(groupTO);

        // get vm stats from each host and update database
        for (Map.Entry<Long, List<Long>> hostAndVmIds : hostAndVmIdsMap.entrySet()) {
            Long hostId = hostAndVmIds.getKey();
            List<Long> vmIds = hostAndVmIds.getValue();

            if (!DEFAULT_HOST_ID.equals(hostId)) {
                Map<Long, ? extends VmStats> vmStatsById = getVmStatsByIdFromHost(hostId, vmIds);
                processVmStatsByIdFromHost(groupTO, vmIds, vmStatsById, policyCountersMap);
            } else {
                processVmStatsByIdFromHost(groupTO, vmIds, null, policyCountersMap);
            }
        }
    }

    protected Map<Long, ? extends VmStats> getVmStatsByIdFromHost(Long hostId, List<Long> vmIds) {
        Map<Long, ? extends VmStats> vmStatsById = new HashMap<>();
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.debug("Failed to get VM stats from non-existing host : " + hostId);
            return vmStatsById;
        }
        try {
            vmStatsById = virtualMachineManager.getVirtualMachineStatistics(host, vmIds);
            if (MapUtils.isEmpty(vmStatsById)) {
                logger.warn("Got empty result for virtual machine statistics from host: " + host);
            }
        } catch (Exception e) {
            logger.debug("Failed to get VM stats from host : {}", host);
        }
        return vmStatsById;
    }

    protected void processVmStatsByIdFromHost(AutoScaleVmGroupTO groupTO, List<Long> vmIds, Map<Long, ? extends VmStats> vmStatsById, Map<Long, List<CounterTO>> policyCountersMap) {
        Date timestamp = new Date();
        for (Long vmId : vmIds) {
            VmStatsEntry vmStats = vmStatsById == null ? null : (VmStatsEntry)vmStatsById.get(vmId);
            for (Map.Entry<Long, List<CounterTO>> policyCounters : policyCountersMap.entrySet()) {
                Long policyId = policyCounters.getKey();
                List<CounterTO> counters = policyCounters.getValue();
                for (CounterTO counter : counters) {
                    if (!Counter.NativeSources.contains(counter.getSource())) {
                        continue;
                    }
                    Double counterValue = null;
                    if (vmStats != null) {
                        if (Counter.Source.CPU.equals(counter.getSource())) {
                            if (Double.isFinite(vmStats.getCPUUtilization()) && vmStats.getCPUUtilization() >= 0 && vmStats.getCPUUtilization() <= 100) {
                                counterValue = vmStats.getCPUUtilization() / 100;
                            }
                        } else if (Counter.Source.MEMORY.equals(counter.getSource())) {
                            if (Double.isFinite(vmStats.getMemoryKBs()) && vmStats.getMemoryKBs() > 0 && Double.isFinite(vmStats.getIntFreeMemoryKBs()) && vmStats.getIntFreeMemoryKBs() >= 0 && vmStats.getIntFreeMemoryKBs() <= vmStats.getMemoryKBs()) {
                                counterValue = (vmStats.getMemoryKBs() - vmStats.getIntFreeMemoryKBs()) / 1024;
                            } else {
                                // In some scenarios, the free memory is greater than VM memory
                                // see https://github.com/apache/cloudstack/issues/4566
                                logger.warn(String.format("Getting virtual machine statistics return invalid free memory KBs for VM %d: %f", vmId, vmStats.getIntFreeMemoryKBs()));
                            }
                        }
                    }
                    if (counterValue == null) {
                        asGroupStatisticsDao.persist(new AutoScaleVmGroupStatisticsVO(groupTO.getId(), policyId, counter.getId(), vmId, ResourceTag.ResourceObjectType.UserVm,
                                AutoScaleValueType.INSTANT_VM, timestamp));
                    } else {
                        asGroupStatisticsDao.persist(new AutoScaleVmGroupStatisticsVO(groupTO.getId(), policyId, counter.getId(), vmId, ResourceTag.ResourceObjectType.UserVm,
                                counterValue, AutoScaleValueType.INSTANT_VM, timestamp));
                    }
                }
            }
        }
    }

    protected void getNetworkStatsFromVirtualRouter(AutoScaleVmGroupTO groupTO) {
        Network network = getNetwork(groupTO.getLoadBalancerId());
        Pair<String, Integer> publicIpAddr = getPublicIpAndPort(groupTO.getLoadBalancerId());
        List<DomainRouterVO> routers = routerDao.listByNetworkAndRole(network.getId(), VirtualRouter.Role.VIRTUAL_ROUTER).stream()
                .filter(this::isActiveMetricsRouter).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(routers)) {
            processGetAutoScaleMetricsAnswer(groupTO, new ArrayList<>(), null);
            return;
        }
        List<AutoScaleMetrics> metrics = setGetAutoScaleMetricsCommandMetrics(groupTO);
        for (DomainRouterVO router : routers) {
            if (isActiveMetricsRouter(router)) {
                final GetAutoScaleMetricsCommand command = new GetAutoScaleMetricsCommand(router.getPrivateIpAddress(), network.getVpcId() != null, publicIpAddr.first(), publicIpAddr.second(), metrics);
                command.setWait(30);
                GetAutoScaleMetricsAnswer answer = (GetAutoScaleMetricsAnswer) agentMgr.easySend(router.getHostId(), command);
                if (answer == null || !answer.getResult()) {
                    logger.error("Failed to get autoscale metrics from virtual router {}", router);
                    processGetAutoScaleMetricsAnswer(groupTO, new ArrayList<>(), router.getId());
                } else {
                    processGetAutoScaleMetricsAnswer(groupTO, answer.getValues(), router.getId());
                }
            } else {
                processGetAutoScaleMetricsAnswer(groupTO, new ArrayList<>(), router.getId());
            }
        }
    }
    protected List<AutoScaleMetrics> setGetAutoScaleMetricsCommandMetrics(AutoScaleVmGroupTO groupTO) {
        List<AutoScaleMetrics> metrics = new ArrayList<>();
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                CounterTO counter = conditionTO.getCounter();

                String provider = counter.getProvider();
                if (! Network.Provider.VirtualRouter.getName().equals(provider) && ! Network.Provider.VPCVirtualRouter.getName().equals(provider)) {
                    continue;
                }
                VirtualRouterAutoScaleCounter vrCounter = VirtualRouterAutoScaleCounter.fromValue(counter.getValue());
                if (vrCounter == null) {
                    continue;
                }
                metrics.add(new AutoScaleMetrics(vrCounter, policyTO.getId(), conditionTO.getId(), counter.getId(), policyTO.getDuration()));
            }
        }
        return metrics;
    }

    protected void processGetAutoScaleMetricsAnswer(AutoScaleVmGroupTO groupTO, List<AutoScaleMetricsValue> values, Long routerId) {
        Date timestamp = new Date();
        Map<Long, List<CounterTO>> policyCountersMap = getPolicyCounters(groupTO);
        for (Map.Entry<Long, List<CounterTO>> policyCounters : policyCountersMap.entrySet()) {
            Long policyId = policyCounters.getKey();
            List<CounterTO> counters = policyCounters.getValue();
            for (CounterTO counter : counters) {
                if (!Counter.Source.VIRTUALROUTER.equals(counter.getSource())) {
                    continue;
                }
                boolean found = false;
                for (AutoScaleMetricsValue value : values) {
                    AutoScaleMetrics metrics = value.getMetrics();
                    if (policyId.equals(metrics.getPolicyId()) && counter.getId().equals(metrics.getCounterId()) && value.getValue() != null && Double.isFinite(value.getValue()) && value.getValue() >= 0) {
                        asGroupStatisticsDao.persist(new AutoScaleVmGroupStatisticsVO(groupTO.getId(), policyId, counter.getId(), routerId,
                                ResourceTag.ResourceObjectType.DomainRouter, value.getValue(), value.getType(), timestamp));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    asGroupStatisticsDao.persist(new AutoScaleVmGroupStatisticsVO(groupTO.getId(), policyId, counter.getId(), routerId,
                            ResourceTag.ResourceObjectType.DomainRouter, AutoScaleValueType.INSTANT_VM, timestamp));
                }
            }
        }
    }

    protected boolean updateCountersMap(AutoScaleVmGroupTO groupTO, Map<String, Double> countersMap, Map<String, Integer> countersNumberMap) {
        logger.debug("Updating countersMap for as group [id={}, uuid={}]", groupTO.getId(), groupTO.getUuid());
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            Date afterDate = new Date(System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(policyTO.getDuration()));
            List<AutoScaleVmGroupStatisticsVO> dummyStats = asGroupStatisticsDao.listDummyRecordsByVmGroup(groupTO.getId(), afterDate);
            if (CollectionUtils.isNotEmpty(dummyStats)) {
                logger.error("Failed to update counters map as there are {} dummy statistics in as group {}", dummyStats.size(), groupTO.getId());
                return false;
            }
            List<AutoScaleVmGroupStatisticsVO> inactiveStats = asGroupStatisticsDao.listInactiveByVmGroupAndPolicy(groupTO.getId(), policyTO.getId(), afterDate);
            if (CollectionUtils.isNotEmpty(inactiveStats)) {
                logger.error("Failed to update counters map as there are {} Inactive " +
                        "statistics in as group [id={}, uuid={}] and policy [id={}, uuid={}]",
                        inactiveStats.size(), groupTO.getId(), groupTO.getUuid(), policyTO.getId(), policyTO.getUuid());
                continue;
            }
            for (ConditionTO conditionTO : policyTO.getConditions()) {
                updateCountersMapPerCondition(groupTO, policyTO, conditionTO, afterDate, countersMap, countersNumberMap);
            }
        }
        logger.debug("DONE Updating countersMap for as group  [id={}, uuid={}]", groupTO.getId(), groupTO.getUuid());
        return true;
    }

    private void updateCountersMapPerCondition(AutoScaleVmGroupTO groupTO, AutoScalePolicyTO policyTO, ConditionTO conditionTO, Date afterDate,
                                                  Map<String, Double> countersMap, Map<String, Integer> countersNumberMap) {
        Long conditionId = conditionTO.getId();
        CounterTO counter = conditionTO.getCounter();
        List<AutoScaleVmGroupStatisticsVO> stats = asGroupStatisticsDao.listByVmGroupAndPolicyAndCounter(groupTO.getId(), policyTO.getId(), counter.getId(), afterDate);
        if (CollectionUtils.isEmpty(stats)) {
            logger.debug("Skipping updating countersMap for group {} and policy {} and " +
                    "counter {} due to no stats", groupTO.getId(), groupTO.getUuid(), policyTO.getId());
            return;
        }
        logger.debug("Updating countersMap with {} stats for group {} and policy {} and " +
                "counter {}", stats.size(), groupTO.getId(), groupTO.getUuid(), policyTO.getId());
        Map<String, List<AutoScaleVmGroupStatisticsVO>> aggregatedRecords = new HashMap<>();
        List<String> incorrectRecords = new ArrayList<>();
        for (AutoScaleVmGroupStatisticsVO stat : stats) {
            if (Arrays.asList(AutoScaleValueType.INSTANT_VM, AutoScaleValueType.INSTANT_VM_GROUP).contains(stat.getValueType())) {
                updateCountersMapWithInstantData(countersMap, countersNumberMap, groupTO, counter.getId(), conditionTO, policyTO, stat.getRawValue(), stat.getValueType());
            } else if (Arrays.asList(AutoScaleValueType.AGGREGATED_VM, AutoScaleValueType.AGGREGATED_VM_GROUP).contains(stat.getValueType())) {
                String key = stat.getCounterId() + "-" + stat.getResourceId();
                if (incorrectRecords.contains(key)) {
                    continue;
                }
                if (!aggregatedRecords.containsKey(key)) {
                    List<AutoScaleVmGroupStatisticsVO> aggregatedRecordList = new ArrayList<>();
                    aggregatedRecordList.add(stat);
                    aggregatedRecords.put(key, aggregatedRecordList);
                    continue;
                }
                List<AutoScaleVmGroupStatisticsVO> aggregatedRecordList = aggregatedRecords.get(key);
                AutoScaleVmGroupStatisticsVO lastRecord = aggregatedRecordList.get(aggregatedRecordList.size() - 1);
                if (stat.getCreated().after(lastRecord.getCreated())) {
                    if (stat.getRawValue() >= lastRecord.getRawValue()) {
                        aggregatedRecordList.add(stat);
                    } else {
                        logger.info("The new raw value is less than the previous raw value, which means the data is incorrect. The key is " + key);
                        aggregatedRecords.remove(key);
                        incorrectRecords.add(key);
                    }
                }
            }
        }

        updateCountersMapByAggregatedRecords(countersMap, countersNumberMap, aggregatedRecords, conditionTO, policyTO, groupTO);
    }

    public void updateCountersMapByAggregatedRecords(Map<String, Double> countersMap, Map<String, Integer> countersNumberMap,
                                                     Map<String, List<AutoScaleVmGroupStatisticsVO>> aggregatedRecords,
                                                     ConditionTO condition, AutoScalePolicyTO policy, AutoScaleVmGroupTO group) {
        if (MapUtils.isNotEmpty(aggregatedRecords)) {
            logger.debug("Processing aggregated data");
            for (Map.Entry<String, List<AutoScaleVmGroupStatisticsVO>> aggregatedRecord : aggregatedRecords.entrySet()) {
                String recordKey = aggregatedRecord.getKey();
                Long counterId = Long.valueOf(recordKey.split("-")[0]);
                List<AutoScaleVmGroupStatisticsVO> records = aggregatedRecord.getValue();
                if (records.size() <= 1) {
                    logger.info(String.format("Ignoring aggregated records, condition [id=%d, uuid=%s], counterId = %s",
                            condition.getId(), condition.getUuid(), counterId));
                    continue;
                }
                AutoScaleVmGroupStatisticsVO firstRecord = records.get(0);
                AutoScaleVmGroupStatisticsVO lastRecord = records.get(records.size() - 1);
                Double coVal = (lastRecord.getRawValue() - firstRecord.getRawValue()) * 1000 / (lastRecord.getCreated().getTime() - firstRecord.getCreated().getTime());
                if (AutoScaleValueType.AGGREGATED_VM_GROUP.equals(firstRecord.getValueType())) {
                    Integer currentVM = autoScaleVmGroupVmMapDao.countAvailableVmsByGroup(group.getId());
                    if (currentVM == 0) {
                        logger.debug("Skipping updating countersMap for group [id={}," +
                                " uuid={}] and policy [id={}, uuid={}] and counter {} due to no VMs",
                                group.getId(), group.getUuid(), policy.getId(), policy.getUuid(), counterId);
                        return;
                    }
                    coVal = coVal / currentVM;
                }
                String key = generateKeyFromPolicyAndConditionAndCounter(policy.getId(), condition.getId(), counterId);
                updateCountersMapWithProcessedData(countersMap, countersNumberMap, key, coVal);
            }
        }
    }

    private String generateKeyFromPolicyAndConditionAndCounter(Long policyId, Long conditionId, Long counterId) {
        return policyId + "-" + conditionId + "-" + counterId;
    }

    protected void cleanupAsVmGroupStatistics(AutoScaleVmGroupTO groupTO) {
        Integer cleanupDelay = AutoScaleStatsCleanupDelay.value();
        long maxDelaySecs = cleanupDelay;
        for (AutoScalePolicyTO policyTO : groupTO.getPolicies()) {
            Integer duration = policyTO.getDuration();
            long delaySecs = Math.max(cleanupDelay, (long) duration + 2L * groupTO.getInterval());
            Date beforeDate = new Date(currentTimeMillis() - TimeUnit.SECONDS.toMillis(delaySecs));
            logger.debug("Removing stats for policy [id={}, uuid={}] in as group [id={}, uuid={}], before {}",
                    policyTO.getId(), policyTO.getUuid(), groupTO.getId(), groupTO.getUuid(), beforeDate);
            asGroupStatisticsDao.removeByGroupAndPolicy(groupTO.getId(), policyTO.getId(), beforeDate);
            if (delaySecs > maxDelaySecs) {
                maxDelaySecs = delaySecs;
            }
        }
        Date beforeDate = new Date(currentTimeMillis() - TimeUnit.SECONDS.toMillis(maxDelaySecs));
        logger.debug(String.format("Removing stats for other policies in as group [id=%d, uuid=%s], before %s", groupTO.getId(), groupTO.getUuid(), beforeDate));
        asGroupStatisticsDao.removeByGroupId(groupTO.getId(), beforeDate);
    }

    protected void scheduleMonitorTasks() {
        for (AutoScaleVmGroupVO group : autoScaleVmGroupDao.listAll()) {
            if (group.getState() == AutoScaleVmGroup.State.ENABLED || group.isOperationInProgress()) {
                scheduleMonitorTask(group.getId());
            }
        }
    }

    protected void scheduleMonitorTask(Long groupId) {
        vmGroupMonitorMaps.computeIfAbsent(groupId, id -> {
            AutoScaleVmGroupVO vmGroup = autoScaleVmGroupDao.findById(id);
            if (vmGroup == null) {
                return null;
            }
            ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("VmGroup-Monitor-" + id));
            executor.scheduleWithFixedDelay(new MonitorTask(id), vmGroup.getInterval(), vmGroup.getInterval(), TimeUnit.SECONDS);
            return executor;
        });
    }

    protected void cancelMonitorTask(Long groupId) {
        vmGroupMonitorMaps.computeIfPresent(groupId, (id, executor) -> {
            AutoScaleVmGroupVO current = autoScaleVmGroupDao.findById(id);
            if (current != null && (current.getState() == AutoScaleVmGroup.State.ENABLED || current.isOperationInProgress())) {
                return executor;
            }
            executor.shutdown();
            return null;
        });
    }

    protected class MonitorTask extends ManagedContextRunnable {

        Long groupId;

        public MonitorTask(Long groupId) {
            this.groupId = groupId;
        }

        @Override
        protected synchronized void runInContext() {
            try {
                AutoScaleVmGroupVO asGroup = autoScaleVmGroupDao.findById(groupId);
                if (asGroup == null) {
                    logger.error("Can not find the groupid " + groupId + " for monitoring");
                    return;
                }
                logger.debug("Start monitoring on AutoScale VmGroup " + asGroup);
                if (asGroup.isOperationInProgress()) {
                    recoverGroupOperation(groupId);
                    return;
                }
                if (asGroup.getState() == AutoScaleVmGroup.State.DISABLED) {
                    cancelMonitorTask(groupId);
                    return;
                }
                // check group state
                if (asGroup.getState().equals(AutoScaleVmGroup.State.ENABLED)) {
                    Network.Provider provider = getLoadBalancerServiceProvider(asGroup.getLoadBalancerId());
                    if (Network.Provider.Netscaler.equals(provider)) {
                        logger.debug("Skipping the monitoring on AutoScale VmGroup with Netscaler provider: " + asGroup);
                    } else if (Network.Provider.VirtualRouter.equals(provider) || Network.Provider.VPCVirtualRouter.equals(provider) ||
                               Network.Provider.Netris.equals(provider)) {
                        monitorAutoscalingGroup(asGroup);
                    }
                }
            } catch (final Exception e) {
                logger.warn("Caught the following exception on monitoring AutoScale Vm Group", e);
            }
        }
    }

    @Override
    public void checkIfVmActionAllowed(Long vmId) {
        List<AutoScaleVmGroupVmMapVO> asGroupVmVOs = autoScaleVmGroupVmMapDao.listByVm(vmId);
        if (CollectionUtils.isNotEmpty(asGroupVmVOs)) {
            for (AutoScaleVmGroupVmMapVO asGroupVmVO : asGroupVmVOs) {
                AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(asGroupVmVO.getVmGroupId());
                if (group != null && (group.isOperationInProgress() || !AutoScaleVmGroup.State.DISABLED.equals(group.getState()))) {
                    throw new InvalidParameterValueException("Cannot perform actions on VM in an AutoScale VM group, Please disable the VM group and retry.");
                }
            }
        }
    }

    @Override
    public void removeVmFromVmGroup(Long vmId) {
        for (AutoScaleVmGroupVmMapVO member : autoScaleVmGroupVmMapDao.listByVm(vmId)) {
            AutoScaleVmGroupVO group = autoScaleVmGroupDao.findById(member.getVmGroupId());
            // Autoscale owns removal only after expunge has confirmed success.
            if (group == null || !group.isOperationInProgress()) {
                autoScaleVmGroupVmMapDao.remove(member.getVmGroupId(), vmId);
            }
        }
    }

    protected boolean destroyVm(Long vmId) {
        UserVmVO vm = userVmDao.findById(vmId);
        try {
            if (vm != null) {
                if (vm.isDeleteProtection()) {
                    logger.warn("AutoScale VM {} has delete protection; cleanup will retain and retry it", vmId);
                    return false;
                }
                if (vm.getState() != VirtualMachine.State.Destroyed && vm.getState() != VirtualMachine.State.Expunging) {
                    UserVm destroyed = userVmMgr.destroyVm(vmId, true);
                    if (destroyed == null) { return false; }
                    UserVmVO current = userVmDao.findById(vmId);
                    if (current == null) { return true; }
                    vm = current;
                    if (vm.getState() != VirtualMachine.State.Destroyed && vm.getState() != VirtualMachine.State.Expunging) { return false; }
                }
                return userVmMgr.expunge(vm);
            }
            return true;
        } catch (Exception ex) {
            logger.error("Cannot destroy vm {} with id: {} due to Exception: ", vm, vmId, ex);
            return false;
        }
    }

    private void markStatisticsAsInactive(Long groupId, Long policyId) {
        asGroupStatisticsDao.updateStateByGroup(groupId, policyId, AutoScaleVmGroupStatisticsVO.State.INACTIVE);
    }

    private void createInactiveDummyRecord(Long groupId) {
        asGroupStatisticsDao.createInactiveDummyRecord(groupId);
    }
}
