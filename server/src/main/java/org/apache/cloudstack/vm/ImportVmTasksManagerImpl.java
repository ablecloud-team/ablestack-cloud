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
package org.apache.cloudstack.vm;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.AblestackN2KCleanupCommand;
import com.cloud.agent.api.AblestackV2KCleanupCommand;
import com.cloud.agent.api.AblestackN2KStatusAnswer;
import com.cloud.agent.api.AblestackN2KStatusCommand;
import com.cloud.agent.api.AblestackV2KUndefineDomainCommand;
import com.cloud.agent.api.AblestackV2KStatusAnswer;
import com.cloud.agent.api.AblestackV2KStatusCommand;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.serializer.GsonHelper;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.crypt.EncryptionSecretKeyChecker;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ImportVMTaskCredentialVO;
import com.cloud.vm.ImportVMTaskEventVO;
import com.cloud.vm.ImportVMTaskVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.ImportVMTaskCredentialDao;
import com.cloud.vm.dao.ImportVMTaskDao;
import com.cloud.vm.dao.ImportVMTaskEventDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.google.gson.reflect.TypeToken;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.vm.ExecuteImportVMTaskActionCmd;
import org.apache.cloudstack.api.command.admin.vm.ListImportVMTaskEventsCmd;
import org.apache.cloudstack.api.command.admin.vm.ListImportVMTasksCmd;
import org.apache.cloudstack.api.response.ImportVMTaskEventResponse;
import org.apache.cloudstack.api.response.ImportVMTaskResponse;
import org.apache.cloudstack.api.response.ListResponse;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.cloudstack.vm.ImportVmTask.Step.CloningInstance;
import static org.apache.cloudstack.vm.ImportVmTask.Step.Completed;
import static org.apache.cloudstack.vm.ImportVmTask.Step.ConvertingInstance;
import static org.apache.cloudstack.vm.ImportVmTask.Step.Importing;
import static org.apache.cloudstack.vm.ImportVmTask.Step.Prepare;

public class ImportVmTasksManagerImpl implements ImportVmTasksManager {

    protected Logger logger = LogManager.getLogger(ImportVmTasksManagerImpl.class);
    private static final String V2K_STATUS_PREFIX = "[V2K] ";
    private static final String V2K_STEP_NONE = "None";
    private static final String CREDENTIAL_ENCRYPTION_VERSION = "db";
    private static final String CREDENTIAL_PAYLOAD_ENDPOINT = "endpoint";
    private static final String CREDENTIAL_PAYLOAD_USERNAME = "username";
    private static final String CREDENTIAL_PAYLOAD_PASSWORD = "password";
    private static final String EVENT_TYPE_CREATED = "created";
    private static final String EVENT_TYPE_STEP = "step";
    private static final String EVENT_TYPE_STATUS = "status";
    private static final String EVENT_TYPE_ERROR = "error";
    private static final String EVENT_TYPE_ACTION = "action";
    private static final String EVENT_TYPE_CREDENTIAL = "credential";
    private static final String MASKED_VALUE = "******";
    private static final Pattern V2K_PHASE_PATTERN = Pattern.compile("(?i)phase:\\s*([^|\\n]+)");
    private static final Pattern V2K_STATE_PATTERN = Pattern.compile("(?i)state:\\s*([^|\\n]+)");
    private static final List<String> SENSITIVE_EVENT_KEYS = Arrays.asList(
            "password", "secretkey", "apikey", "token", "sessionkey",
            "accesskey", "signature", "authorization", "credential", "secret");
    private static final Type CREDENTIAL_PAYLOAD_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    @Inject
    private ImportVMTaskDao importVMTaskDao;
    @Inject
    private ImportVMTaskCredentialDao importVMTaskCredentialDao;
    @Inject
    private ImportVMTaskEventDao importVMTaskEventDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private AccountService accountService;
    @Inject
    private HostDao hostDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private AgentManager agentManager;
    public ImportVmTasksManagerImpl() {
    }

    @Override
    public ListResponse<ImportVMTaskResponse> listImportVMTasks(ListImportVMTasksCmd cmd) {
        Long zoneId = cmd.getZoneId();
        Long accountId = cmd.getAccountId();
        String vcenter = cmd.getVcenter();
        Long convertHostId = cmd.getConvertHostId();
        Long startIndex = cmd.getStartIndex();
        Long pageSizeVal = cmd.getPageSizeVal();

        ImportVmTask.TaskState state = getStateFromFilter(cmd.getTasksFilter());
        Pair<List<ImportVMTaskVO>, Integer> result = importVMTaskDao.listImportVMTasks(zoneId, accountId, vcenter, convertHostId, state,
                cmd.getMigrationTool(), cmd.getSourceProvider(), cmd.getTargetProvider(), cmd.getTargetProfile(), cmd.getCurrentPhase(),
                cmd.getMigrationState(), startIndex, pageSizeVal);
        List<ImportVMTaskVO> tasks = result.first();

        List<ImportVMTaskResponse> responses = new ArrayList<>();
        for (ImportVMTaskVO task : tasks) {
            responses.add(createImportVMTaskResponse(task));
        }
        ListResponse<ImportVMTaskResponse> listResponses = new ListResponse<>();
        listResponses.setResponses(responses, result.second());
        return listResponses;
    }

    @Override
    public ListResponse<ImportVMTaskEventResponse> listImportVMTaskEvents(ListImportVMTaskEventsCmd cmd) {
        ImportVMTaskVO task = getImportVMTaskOrThrow(cmd.getImportVmTaskId());
        Pair<List<ImportVMTaskEventVO>, Integer> result = importVMTaskEventDao.listAndCountByTaskId(task.getId(), cmd.getStartIndex(), cmd.getPageSizeVal());

        List<ImportVMTaskEventResponse> responses = new ArrayList<>();
        for (ImportVMTaskEventVO event : result.first()) {
            responses.add(createImportVMTaskEventResponse(task, event));
        }
        ListResponse<ImportVMTaskEventResponse> listResponses = new ListResponse<>();
        listResponses.setResponses(responses, result.second());
        return listResponses;
    }

    @Override
    public ImportVMTaskResponse executeImportVMTaskAction(ExecuteImportVMTaskActionCmd cmd) {
        ImportVMTaskVO task = getImportVMTaskOrThrow(cmd.getImportVmTaskId());
        ImportVmTask.Action action;
        try {
            action = ImportVmTask.Action.fromValue(cmd.getAction());
        } catch (IllegalArgumentException e) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, e.getMessage());
        }

        if (action == ImportVmTask.Action.Refresh) {
            appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null, "Refresh import VM task status", actionPayload(action));
            return createImportVMTaskResponse(importVMTaskDao.findById(task.getId()), true);
        }
        if (action == ImportVmTask.Action.ClearCredentials) {
            if (task.getState() == ImportVmTask.TaskState.Running && !isPhase1CompletedWaiting(task)) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Cannot clear import VM task credentials while the task is running");
            }
            boolean removed = removeImportVMTaskSourceCredentials(task);
            appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null, removed ? "Cleared import VM task credentials" : "No import VM task credentials to clear",
                    actionPayload(action));
            return createImportVMTaskResponse(importVMTaskDao.findById(task.getId()));
        }
        if (action == ImportVmTask.Action.Cancel) {
            return cancelImportVMTask(task, cmd);
        }
        if (action == ImportVmTask.Action.Delete) {
            return deleteImportVMTask(task, cmd);
        }
        throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                String.format("Import VM task action [%s] is not implemented by the generic action executor yet", action.getValue()));
    }

    private ImportVMTaskResponse cancelImportVMTask(ImportVMTaskVO task, ExecuteImportVMTaskActionCmd cmd) {
        if (task.getState() != ImportVmTask.TaskState.Running && !cmd.isForced()) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Import VM task %s is not running", task.getUuid()));
        }
        task.setState(ImportVmTask.TaskState.Cancelling);
        task.setMigrationState("cancelling");
        task.setUpdated(DateUtil.now());
        importVMTaskDao.update(task.getId(), task);
        appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null, "Cancelling import VM task", actionPayload(ImportVmTask.Action.Cancel));

        cleanupImportVMTaskRuntime(task, false, true);
        if (cmd.isRemoveCredentials()) {
            removeImportVMTaskSourceCredentials(task);
        }

        ImportVMTaskVO latestTask = importVMTaskDao.findById(task.getId());
        latestTask.setState(ImportVmTask.TaskState.Cancelled);
        latestTask.setMigrationState("cancelled");
        latestTask.setDescription(StringUtils.defaultIfBlank(latestTask.getDescription(), "Import VM task cancelled by operator"));
        latestTask.setUpdated(DateUtil.now());
        importVMTaskDao.update(latestTask.getId(), latestTask);
        appendImportVMTaskEvent(latestTask, EVENT_TYPE_ACTION, null, "Cancelled import VM task", actionPayload(ImportVmTask.Action.Cancel));
        return createImportVMTaskResponse(importVMTaskDao.findById(latestTask.getId()));
    }

    private ImportVMTaskResponse deleteImportVMTask(ImportVMTaskVO task, ExecuteImportVMTaskActionCmd cmd) {
        if (task.getState() == ImportVmTask.TaskState.Running && !isPhase1CompletedWaiting(task) && !cmd.isForced()) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Cannot delete a running import VM task. Cancel it first or use force=true.");
        }
        if (cmd.isCleanup()) {
            cleanupImportVMTaskRuntime(task, true, true);
        }
        if (cmd.isRemoveCredentials()) {
            removeImportVMTaskSourceCredentials(task);
        }
        appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null, "Deleted import VM task", actionPayload(ImportVmTask.Action.Delete));
        ImportVMTaskResponse response = createImportVMTaskResponse(task);
        importVMTaskDao.remove(task.getId());
        return response;
    }

    private void cleanupImportVMTaskRuntime(ImportVMTaskVO task, boolean removeWorkdir, boolean keepSourcePoints) {
        if (task == null || task.getConvertHostId() <= 0) {
            return;
        }
        HostVO convertHost = hostDao.findById(task.getConvertHostId());
        if (convertHost == null || convertHost.getStatus() != Status.Up) {
            logger.warn("Unable to cleanup import VM task {} because conversion host is not available", task.getUuid());
            return;
        }
        Command cleanupCommand = null;
        if (isN2KTask(task)) {
            cleanupCommand = new AblestackN2KCleanupCommand(task.getWorkdir(), keepSourcePoints, removeWorkdir);
            cleanupCommand.setWait(300);
        } else if (isV2KTask(task)) {
            cleanupCommand = new AblestackV2KCleanupCommand(task.getWorkdir(), task.getSourceVMName(), keepSourcePoints, removeWorkdir, true);
            cleanupCommand.setWait(300);
        }
        if (cleanupCommand == null) {
            return;
        }
        try {
            Answer answer = agentManager.send(convertHost.getId(), cleanupCommand);
            if (answer == null || !answer.getResult()) {
                logger.warn("Unable to cleanup import VM task {} on host {}: {}",
                        task.getUuid(), convertHost.getName(), answer != null ? answer.getDetails() : "no answer");
                appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null,
                        String.format("Runtime cleanup warning: %s", answer != null ? answer.getDetails() : "no answer"), null);
                return;
            }
            appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null,
                    String.format("Runtime cleanup completed: %s", answer.getDetails()), null);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Could not cleanup import VM task {} on host {} due to: {}",
                    task.getUuid(), convertHost.getName(), e.getMessage());
            appendImportVMTaskEvent(task, EVENT_TYPE_ACTION, null,
                    String.format("Runtime cleanup warning: %s", e.getMessage()), null);
        }
    }

    private ImportVMTaskVO getImportVMTaskOrThrow(String taskUuid) {
        if (StringUtils.isBlank(taskUuid)) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Import VM task ID is required");
        }
        ImportVMTaskVO task = importVMTaskDao.findByUuid(taskUuid);
        if (task == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Unable to find import VM task with ID %s", taskUuid));
        }
        return task;
    }

    private ImportVmTask.TaskState getStateFromFilter(String tasksFilter) {
        if (StringUtils.isBlank(tasksFilter) || tasksFilter.equalsIgnoreCase("all")) {
            return null;
        }
        try {
            return ImportVmTask.TaskState.getValue(tasksFilter);
        } catch (IllegalArgumentException e) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Invalid value for task state: %s", tasksFilter));
        }
    }

    @Override
    public ImportVmTask createImportVMTaskRecord(DataCenter zone, Account owner, long userId, String displayName, String vcenter, String datacenterName, String sourceVMName, Host convertHost, Host importHost) {
        logger.debug("Creating import VM task entry for VM: {} for account {} on zone {} " +
                        "from the vCenter: {} / datacenter: {} / source VM: {}",
                sourceVMName, owner.getAccountName(), zone.getName(), displayName, vcenter, datacenterName);
        ImportVMTaskVO importVMTaskVO = new ImportVMTaskVO(zone.getId(), owner.getAccountId(), userId, displayName,
                vcenter, datacenterName, sourceVMName, convertHost.getId(), importHost.getId());
        importVMTaskVO.setState(ImportVmTask.TaskState.Running);
        importVMTaskVO.setCurrentPhase(ImportVmTask.MigrationPhase.Prepare.getValue());
        importVMTaskVO.setMigrationState(ImportVmTask.MigrationState.Running.getValue());
        importVMTaskVO.setMigrationStep(Prepare.name());
        ImportVMTaskVO persistedTask = importVMTaskDao.persist(importVMTaskVO);
        appendImportVMTaskEvent(persistedTask, EVENT_TYPE_CREATED, null, "Created import VM task", null);
        return persistedTask;
    }

    private String getStepDescription(ImportVMTaskVO importVMTaskVO, Host convertHost, Host importHost,
                                      ImportVMTaskVO.Step step, Date updatedDate) {
        String sourceVMName = importVMTaskVO.getSourceVMName();
        String vcenter = importVMTaskVO.getVcenter();
        String datacenter = importVMTaskVO.getDatacenter();

        StringBuilder stringBuilder = new StringBuilder();
        if (Completed == step) {
            stringBuilder.append("Completed at ").append(DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), updatedDate));
        } else {
            if (CloningInstance == step) {
                stringBuilder.append(String.format("Cloning source instance: %s on vCenter: %s / datacenter: %s", sourceVMName, vcenter, datacenter));
            } else if (ConvertingInstance == step) {
                stringBuilder.append(String.format("Converting the cloned VMware instance to a KVM instance on the host: %s", convertHost.getName()));
            } else if (Importing == step) {
                stringBuilder.append(String.format("Importing the converted KVM instance on the host: %s", importHost.getName()));
            } else if (Prepare == step) {
                stringBuilder.append("Preparing to convert Vmware instance");
            }
        }
        return stringBuilder.toString();
    }

    @Override
    public void updateImportVMTaskStep(ImportVmTask importVMTask, DataCenter zone, Account owner, Host convertHost,
                                       Host importHost, Long vmId, ImportVmTask.Step step) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        logger.debug("Updating import VM task entry for VM: {} for account {} on zone {} " +
                        "from the vCenter: {} / datacenter: {} / source VM: {} to step: {}",
                importVMTaskVO.getSourceVMName(), owner.getAccountName(), zone.getName(), importVMTaskVO.getDisplayName(),
                importVMTaskVO.getVcenter(), importVMTaskVO.getDatacenter(), step);
        Date updatedDate = DateUtil.now();
        String description = getStepDescription(importVMTaskVO, convertHost, importHost, step, updatedDate);
        importVMTaskVO.setStep(step);
        importVMTaskVO.setDescription(description);
        importVMTaskVO.setCurrentPhase((Completed == step ? ImportVmTask.MigrationPhase.Completed : ImportVmTask.MigrationPhase.Prepare).getValue());
        importVMTaskVO.setMigrationState((Completed == step ? ImportVmTask.MigrationState.Completed : ImportVmTask.MigrationState.Running).getValue());
        importVMTaskVO.setMigrationStep(step != null ? step.name() : null);
        importVMTaskVO.setUpdated(updatedDate);
        if (Completed == step) {
            Duration duration = Duration.between(importVMTaskVO.getCreated().toInstant(), updatedDate.toInstant());
            importVMTaskVO.setDuration(duration.toMillis());
            importVMTaskVO.setVmId(vmId);
            importVMTaskVO.setState(ImportVmTask.TaskState.Completed);
        }
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_STEP, null, description, null);
    }

    @Override
    public void updateImportVMTaskV2KStep(ImportVmTask importVMTask, ImportVmTask.V2KStep step) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        Date updatedDate = DateUtil.now();
        importVMTaskVO.setV2kStep(step != null ? step.name() : V2K_STEP_NONE);
        applyV2KStepStatus(importVMTaskVO, step);
        importVMTaskVO.setUpdated(updatedDate);
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_STEP, null, String.format("Updated ablestack-v2k step to %s", importVMTaskVO.getV2kStep()), null);
    }

    @Override
    public void updateImportVMTaskRuntimeStatus(ImportVmTask importVMTask, ImportVmTaskStatus status,
                                                String rawStatusJson, String description) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        if (importVMTaskVO == null || status == null) {
            return;
        }
        boolean changed = applyNormalizedStatus(importVMTaskVO, status);
        if (StringUtils.isNotBlank(rawStatusJson) && !StringUtils.equals(importVMTaskVO.getStatusJson(), rawStatusJson)) {
            importVMTaskVO.setStatusJson(rawStatusJson);
            changed = true;
        }
        if (StringUtils.isNotBlank(description) && !StringUtils.equals(importVMTaskVO.getDescription(), description)) {
            importVMTaskVO.setDescription(description);
            changed = true;
        }
        if (!changed) {
            return;
        }
        importVMTaskVO.setUpdated(DateUtil.now());
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_STATUS, status, StringUtils.defaultIfBlank(description, "Updated import VM task status"), null);
    }

    @Override
    public void updateImportVMTaskV2KContext(ImportVmTask importVMTask, Long clusterId, Long serviceOfferingId,
                                             Long targetStoragePoolId, String sourceClusterName, String sourceHostName,
                                             Long vcenterId, String vcenterUsername, String vcenterPassword,
                                             Map<String, String> serviceOfferingDetails,
                                             Map<String, Map<String, String>> nicSelectionMap,
                                             String targetProfile, String targetFormat, String targetStorageType,
                                             String targetVMName, String workdir, String targetContextJson) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        importVMTaskVO.setClusterId(clusterId);
        importVMTaskVO.setServiceOfferingId(serviceOfferingId);
        importVMTaskVO.setV2kTargetStoragePoolId(targetStoragePoolId);
        importVMTaskVO.setSourceClusterName(sourceClusterName);
        importVMTaskVO.setSourceHostName(sourceHostName);
        importVMTaskVO.setVcenterId(vcenterId);
        importVMTaskVO.setVcenterUsername(vcenterUsername);
        importVMTaskVO.setVcenterPassword(null);
        importVMTaskVO.setMigrationTool(ImportVmTask.MigrationTool.AblestackV2K.getValue());
        importVMTaskVO.setSourceProvider(ImportVmTask.SourceProvider.VMware.getValue());
        importVMTaskVO.setTargetProvider(ImportVmTask.TargetProvider.Cloud.getValue());
        importVMTaskVO.setTargetStoragePoolId(targetStoragePoolId);
        importVMTaskVO.setTargetProfile(targetProfile);
        importVMTaskVO.setTargetFormat(targetFormat);
        importVMTaskVO.setTargetStorageType(targetStorageType);
        importVMTaskVO.setTargetVMName(targetVMName);
        importVMTaskVO.setWorkdir(workdir);
        importVMTaskVO.setSourceEndpoint(importVMTaskVO.getVcenter());
        importVMTaskVO.setSourceRef(importVMTaskVO.getSourceVMName());
        importVMTaskVO.setTargetContextJson(targetContextJson);
        importVMTaskVO.setServiceOfferingDetails(serializeMap(serviceOfferingDetails));
        importVMTaskVO.setNicNetworkMap(serializeMap(nicSelectionMap));
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
    }

    @Override
    public void updateImportVMTaskN2KContext(ImportVmTask importVMTask, Long clusterId, Long serviceOfferingId,
                                             Long targetStoragePoolId, String prismEndpoint, String sourceApi,
                                             String sourceInventoryJson, Map<String, String> serviceOfferingDetails,
                                             Map<String, Map<String, String>> nicSelectionMap,
                                             String targetProfile, String targetFormat, String targetStorageType,
                                             String targetVMName, String workdir, String splitMode,
                                             String sourceContextJson, String targetContextJson) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        importVMTaskVO.setClusterId(clusterId);
        importVMTaskVO.setServiceOfferingId(serviceOfferingId);
        importVMTaskVO.setV2kTargetStoragePoolId(targetStoragePoolId);
        importVMTaskVO.setMigrationTool(ImportVmTask.MigrationTool.AblestackN2K.getValue());
        importVMTaskVO.setSourceProvider(ImportVmTask.SourceProvider.Nutanix.getValue());
        importVMTaskVO.setTargetProvider(ImportVmTask.TargetProvider.Cloud.getValue());
        importVMTaskVO.setTargetStoragePoolId(targetStoragePoolId);
        importVMTaskVO.setTargetProfile(targetProfile);
        importVMTaskVO.setTargetFormat(targetFormat);
        importVMTaskVO.setTargetStorageType(targetStorageType);
        importVMTaskVO.setTargetVMName(targetVMName);
        importVMTaskVO.setWorkdir(workdir);
        importVMTaskVO.setSplitMode(splitMode);
        importVMTaskVO.setSourceEndpoint(prismEndpoint);
        importVMTaskVO.setSourceRef(importVMTaskVO.getSourceVMName());
        importVMTaskVO.setSourceInventoryJson(sourceInventoryJson);
        importVMTaskVO.setSourceContextJson(sourceContextJson);
        importVMTaskVO.setTargetContextJson(targetContextJson);
        importVMTaskVO.setServiceOfferingDetails(serializeMap(serviceOfferingDetails));
        importVMTaskVO.setNicNetworkMap(serializeMap(nicSelectionMap));
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
    }

    @Override
    public ImportVmTaskSourceCredential storeImportVMTaskSourceCredential(ImportVmTask importVMTask, String provider, String credentialType,
                                                                          String endpoint, String username, String password) {
        if (importVMTask == null) {
            throw new CloudRuntimeException("Import VM task is required to store source credentials");
        }
        if (StringUtils.isAnyBlank(provider, credentialType, username, password)) {
            throw new CloudRuntimeException("Provider, credential type, username, and password are required to store source credentials");
        }
        if (!EncryptionSecretKeyChecker.useEncryption()) {
            throw new CloudRuntimeException("Management server DB encryption must be enabled before storing import VM task credentials");
        }

        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        Map<String, String> payload = new HashMap<>();
        payload.put(CREDENTIAL_PAYLOAD_ENDPOINT, endpoint);
        payload.put(CREDENTIAL_PAYLOAD_USERNAME, username);
        payload.put(CREDENTIAL_PAYLOAD_PASSWORD, password);
        String encryptedPayload = DBEncryptionUtil.encrypt(GsonHelper.getGson().toJson(payload));

        ImportVMTaskCredentialVO credential = new ImportVMTaskCredentialVO(importVMTaskVO.getId(), provider, credentialType, username,
                encryptedPayload, CREDENTIAL_ENCRYPTION_VERSION, null);
        ImportVMTaskCredentialVO persistedCredential = importVMTaskCredentialDao.persist(credential);

        importVMTaskVO.setSourceCredentialId(persistedCredential.getId());
        importVMTaskVO.setUpdated(DateUtil.now());
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        Map<String, String> eventPayload = new HashMap<>();
        eventPayload.put("provider", provider);
        eventPayload.put("credentialtype", credentialType);
        eventPayload.put("usernamehint", username);
        appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_CREDENTIAL, null, "Stored encrypted import VM task source credential", eventPayload);
        return new ImportVmTaskSourceCredential(provider, credentialType, endpoint, username, password);
    }

    @Override
    public ImportVmTaskSourceCredential getImportVMTaskSourceCredential(ImportVmTask importVMTask) {
        ImportVMTaskCredentialVO credential = getStoredCredential(importVMTask);
        if (credential == null) {
            return null;
        }
        String decryptedPayload = DBEncryptionUtil.decrypt(credential.getEncryptedPayload());
        Map<String, String> payload = GsonHelper.getGson().fromJson(decryptedPayload, CREDENTIAL_PAYLOAD_TYPE);
        if (payload == null) {
            throw new CloudRuntimeException(String.format("Stored import VM task credential %s has an empty payload", credential.getUuid()));
        }
        return new ImportVmTaskSourceCredential(credential.getProvider(), credential.getCredentialType(),
                payload.get(CREDENTIAL_PAYLOAD_ENDPOINT), payload.get(CREDENTIAL_PAYLOAD_USERNAME), payload.get(CREDENTIAL_PAYLOAD_PASSWORD));
    }

    @Override
    public boolean removeImportVMTaskSourceCredentials(ImportVmTask importVMTask) {
        if (importVMTask == null) {
            return false;
        }
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        boolean removed = false;
        List<ImportVMTaskCredentialVO> credentials = importVMTaskCredentialDao.listByTaskId(importVMTaskVO.getId());
        for (ImportVMTaskCredentialVO credential : credentials) {
            removed |= importVMTaskCredentialDao.remove(credential.getId());
        }
        if (importVMTaskVO.getSourceCredentialId() != null) {
            importVMTaskVO.setSourceCredentialId(null);
            importVMTaskVO.setUpdated(DateUtil.now());
            importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        }
        if (removed) {
            appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_CREDENTIAL, null, "Removed import VM task source credentials", null);
        }
        return removed;
    }

    private ImportVMTaskCredentialVO getStoredCredential(ImportVmTask importVMTask) {
        if (importVMTask == null) {
            return null;
        }
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        if (importVMTaskVO.getSourceCredentialId() != null) {
            ImportVMTaskCredentialVO credential = importVMTaskCredentialDao.findById(importVMTaskVO.getSourceCredentialId());
            if (credential != null && credential.getRemoved() == null) {
                return credential;
            }
        }
        return importVMTaskCredentialDao.findLatestByTaskId(importVMTaskVO.getId());
    }

    private String serializeMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return GsonHelper.getGson().toJson(map);
    }

    @Override
    public void updateImportVMTaskErrorState(ImportVmTask importVMTask, ImportVmTask.TaskState state, String errorMsg) {
        ImportVMTaskVO importVMTaskVO = (ImportVMTaskVO) importVMTask;
        Date updatedDate = DateUtil.now();
        importVMTaskVO.setUpdated(updatedDate);
        importVMTaskVO.setState(state);
        importVMTaskVO.setDescription(errorMsg);
        importVMTaskVO.setMigrationState(ImportVmTask.MigrationState.Failed.getValue());
        importVMTaskDao.update(importVMTaskVO.getId(), importVMTaskVO);
        appendImportVMTaskEvent(importVMTaskVO, EVENT_TYPE_ERROR, null, errorMsg, null);
    }

    private ImportVMTaskResponse createImportVMTaskResponse(ImportVMTaskVO task) {
        return createImportVMTaskResponse(task, false);
    }

    private ImportVMTaskResponse createImportVMTaskResponse(ImportVMTaskVO task, boolean reconcileN2K) {
        ImportVMTaskResponse response = new ImportVMTaskResponse();
        response.setId(task.getUuid());
        DataCenterVO zone = dataCenterDao.findById(task.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        Account account = accountService.getAccount(task.getAccountId());
        if (account != null) {
            response.setAccountId(account.getUuid());
            response.setAccountName(account.getAccountName());
        }
        Cluster cluster = task.getClusterId() != null ? clusterDao.findById(task.getClusterId()) : null;
        if (cluster != null) {
            response.setClusterId(cluster.getUuid());
        }
        ServiceOfferingVO serviceOffering = task.getServiceOfferingId() != null ? serviceOfferingDao.findById(task.getServiceOfferingId()) : null;
        if (serviceOffering != null) {
            response.setServiceOfferingId(serviceOffering.getUuid());
        }
        response.setVcenter(task.getVcenter());
        response.setDatacenterName(task.getDatacenter());
        response.setSourceVMName(task.getSourceVMName());
        response.setMigrationTool(StringUtils.defaultIfBlank(task.getMigrationTool(), ImportVmTask.MigrationTool.Legacy.getValue()));
        response.setSourceProvider(task.getSourceProvider());
        response.setTargetProvider(task.getTargetProvider());
        response.setTargetProfile(task.getTargetProfile());
        response.setTargetVMName(StringUtils.defaultIfBlank(task.getTargetVMName(), task.getDisplayName()));
        response.setCurrentPhase(task.getCurrentPhase());
        response.setPhase(task.getCurrentPhase());
        response.setMigrationState(task.getMigrationState());
        response.setMigrationStep(task.getMigrationStep());
        response.setWorkdir(task.getWorkdir());
        response.setDisplayName(task.getDisplayName());
        String resolvedV2kStep = resolveV2KStep(task);
        response.setV2kStep(StringUtils.defaultIfBlank(resolvedV2kStep, V2K_STEP_NONE));
        response.setStep(StringUtils.isNotBlank(resolvedV2kStep) ? getStoredV2KStepDisplayField(resolvedV2kStep) : getStepDisplayField(task.getStep()));
        response.setDisplayStep(resolveDisplayStep(task));
        applyStoredSyncProgress(response, task);
        if (StringUtils.isNotBlank(response.getDisplayStep())) {
            response.setStep(response.getDisplayStep());
        }
        response.setDescription(task.getDescription());
        response.setState(task.getState().name());
        setAblestackV2KStatus(response, task);
        setAblestackN2KStatus(response, task, reconcileN2K);
        response.setCredentialState(getCredentialState(task));
        response.setAvailableActions(getAvailableActions(task));

        Date updated = task.getUpdated();
        Date currentDate = new Date();

        if (updated != null) {
            if (ImportVmTask.TaskState.Running == task.getState()) {
                Duration stepDuration = Duration.between(updated.toInstant(), currentDate.toInstant());
                response.setStepDuration(getDurationDisplay(stepDuration.toMillis()));
            } else {
                Duration totalDuration = Duration.between(task.getCreated().toInstant(), updated.toInstant());
                response.setDuration(getDurationDisplay(totalDuration.toMillis()));
            }
        }

        HostVO host = hostDao.findById(task.getConvertHostId());
        if (host != null) {
            response.setConvertInstanceHostId(host.getUuid());
            response.setConvertInstanceHostName(host.getName());
        }
        if (task.getVmId() != null) {
            UserVmVO userVm = userVmDao.findById(task.getVmId());
            if (userVm != null) {
                // Migrated VM could have been removed from CloudStack after the migration
                response.setVirtualMachineId(userVm.getUuid());
            }
        }
        response.setCreated(task.getCreated());
        response.setLastUpdated(task.getUpdated());
        response.setObjectName("importvmtask");
        return response;
    }

    private void setAblestackV2KStatus(ImportVMTaskResponse response, ImportVMTaskVO task) {
        if (task == null || task.getState() != ImportVmTask.TaskState.Running || !isV2KTask(task)) {
            return;
        }
        HostVO host = hostDao.findById(task.getConvertHostId());
        if (host == null || host.getStatus() != Status.Up) {
            return;
        }

        AblestackV2KStatusCommand statusCommand = new AblestackV2KStatusCommand(task.getSourceVMName());
        statusCommand.setWait(30);
        try {
            Answer answer = agentManager.send(host.getId(), statusCommand);
            if (!(answer instanceof AblestackV2KStatusAnswer) || !answer.getResult()) {
                logger.debug("Unable to retrieve ablestack-v2k status for source VM {} on host {}: {}",
                        task.getSourceVMName(), host.getName(), answer != null ? answer.getDetails() : "no answer");
                return;
            }
            AblestackV2KStatusAnswer status = (AblestackV2KStatusAnswer) answer;
            ImportVmTaskStatus normalizedStatus = normalizeV2KStatus(status);
            response.setPhase(normalizedStatus.getCurrentPhase());
            response.setCurrentPhase(normalizedStatus.getCurrentPhase());
            response.setMigrationState(normalizedStatus.getMigrationState());
            response.setMigrationStep(normalizedStatus.getMigrationStep());
            response.setSyncPhysical(normalizedStatus.getSyncPhysical());
            applySyncProgressFields(response, normalizedStatus);
            response.setStep(StringUtils.defaultIfBlank(response.getDisplayStep(), response.getStep()));
            response.setWorkdir(normalizedStatus.getWorkdir());
            updateTaskDescriptionWithV2KStatus(response, task, status, normalizedStatus);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.debug("Error while retrieving ablestack-v2k status for source VM {} on host {}: {}",
                    task.getSourceVMName(), host.getName(), e.getMessage());
        }
    }

    private void setAblestackN2KStatus(ImportVMTaskResponse response, ImportVMTaskVO task, boolean reconcile) {
        if (task == null || !isN2KTask(task) || StringUtils.isBlank(task.getWorkdir())) {
            return;
        }
        boolean running = task.getState() == ImportVmTask.TaskState.Running;
        if (!running && (!reconcile || task.getState() != ImportVmTask.TaskState.Failed)) {
            return;
        }
        HostVO host = hostDao.findById(task.getConvertHostId());
        if (host == null || host.getStatus() != Status.Up) {
            return;
        }

        AblestackN2KStatusCommand statusCommand = new AblestackN2KStatusCommand(task.getSourceVMName(), task.getWorkdir());
        statusCommand.setWait(30);
        try {
            Answer answer = agentManager.send(host.getId(), statusCommand);
            if (!(answer instanceof AblestackN2KStatusAnswer) || !answer.getResult()) {
                logger.debug("Unable to retrieve ablestack-n2k status for source VM {} on host {}: {}",
                        task.getSourceVMName(), host.getName(), answer != null ? answer.getDetails() : "no answer");
                return;
            }
            AblestackN2KStatusAnswer status = (AblestackN2KStatusAnswer) answer;
            if (reconcile && reconcileCompletedN2KCloudTask(task, status)) {
                response.setState(task.getState().name());
                response.setV2kStep(task.getV2kStep());
                response.setDescription(task.getDescription());
            } else if (!running) {
                return;
            }
            ImportVmTaskStatus normalizedStatus = normalizeN2KStatusForTask(task, status);
            response.setPhase(normalizedStatus.getCurrentPhase());
            response.setCurrentPhase(normalizedStatus.getCurrentPhase());
            response.setMigrationState(normalizedStatus.getMigrationState());
            response.setMigrationStep(normalizedStatus.getMigrationStep());
            response.setSyncPhysical(normalizedStatus.getSyncPhysical());
            applySyncProgressFields(response, normalizedStatus);
            response.setStep(StringUtils.defaultIfBlank(response.getDisplayStep(), response.getStep()));
            response.setWorkdir(normalizedStatus.getWorkdir());
            updateTaskDescriptionWithN2KStatus(response, task, status, normalizedStatus);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.debug("Error while retrieving ablestack-n2k status for source VM {} on host {}: {}",
                    task.getSourceVMName(), host.getName(), e.getMessage());
        }
    }

    private boolean reconcileCompletedN2KCloudTask(ImportVMTaskVO task, AblestackN2KStatusAnswer status) {
        String cloudVmId = status.getCloudVmId();
        if (task.getVmId() != null || StringUtils.isBlank(cloudVmId)
                || !"ablestack-cloud".equals(status.getTargetProvider())
                || !"phase2".equals(status.getPhase()) || !"completed".equals(status.getMigrationState())) {
            return false;
        }
        UserVmVO importedVm = userVmDao.findByUuid(cloudVmId);
        if (importedVm == null || importedVm.getAccountId() != task.getAccountId()
                || importedVm.getDataCenterId() != task.getZoneId()
                || (StringUtils.isNotBlank(task.getTargetVMName())
                && !StringUtils.equalsAny(task.getTargetVMName(), importedVm.getName(), importedVm.getDisplayName()))) {
            return false;
        }
        Date updated = DateUtil.now();
        task.setVmId(importedVm.getId());
        task.setState(ImportVmTask.TaskState.Completed);
        task.setV2kStep(ImportVmTask.V2KStep.Completed.name());
        task.setDescription("Managed instance import succeeded (vmId=" + cloudVmId + ")");
        task.setUpdated(updated);
        if (task.getCreated() != null) {
            task.setDuration(Duration.between(task.getCreated().toInstant(), updated.toInstant()).toMillis());
        }
        return true;
    }

    private void updateTaskDescriptionWithN2KStatus(ImportVMTaskResponse response, ImportVMTaskVO task, AblestackN2KStatusAnswer status,
                                                    ImportVmTaskStatus normalizedStatus) {
        boolean changed = false;
        boolean statusChanged = applyNormalizedStatus(task, normalizedStatus);
        String statusJson = StringUtils.trimToNull(status.getStatusJson());
        if (statusJson != null && !StringUtils.equals(task.getStatusJson(), statusJson)) {
            task.setStatusJson(statusJson);
            changed = true;
        }
        String statusMessage = buildN2KStatusMessage(normalizedStatus);
        if (!StringUtils.equals(task.getDescription(), statusMessage)) {
            task.setDescription(statusMessage);
            response.setDescription(statusMessage);
            changed = true;
        }

        if (!changed && !statusChanged) {
            return;
        }
        importVMTaskDao.update(task.getId(), task);
        appendImportVMTaskEvent(task, EVENT_TYPE_STATUS, normalizedStatus, statusMessage, null);
    }

    private void updateTaskDescriptionWithV2KStatus(ImportVMTaskResponse response, ImportVMTaskVO task, AblestackV2KStatusAnswer status,
                                                    ImportVmTaskStatus normalizedStatus) {
        Date now = DateUtil.now();
        boolean changed = false;
        boolean statusChanged = applyNormalizedStatus(task, normalizedStatus);
        ImportVmTask.TaskState previousTaskState = task.getState();
        ImportVmTask.V2KStep v2kStep = getProgressiveV2KStep(task != null ? task.getV2kStep() : null, getV2KStepFromStatus(status));
        String v2kStepName = v2kStep.name();
        String v2kStatusMessage = buildV2KStatusMessage(status);
        String descriptionWithoutV2K = stripV2KStatusLine(task.getDescription());
        String mergedDescription = mergeV2KStatusAndDescription(v2kStatusMessage, descriptionWithoutV2K);

        response.setDescription(mergedDescription);
        if (!StringUtils.equals(task.getDescription(), mergedDescription)) {
            task.setDescription(mergedDescription);
            changed = true;
        }

        response.setV2kStep(v2kStepName);
        if (!StringUtils.equals(task.getV2kStep(), v2kStepName)) {
            task.setV2kStep(v2kStepName);
            changed = true;
        }

        ImportVmTask.TaskState taskState = getTaskStateFromV2KStatus(task, status, v2kStep);
        if (taskState != task.getState()) {
            task.setState(taskState);
            changed = true;
        }

        if (task.getCreated() != null) {
            Long durationMs = Duration.between(task.getCreated().toInstant(), now.toInstant()).toMillis();
            if (!durationMs.equals(task.getDuration())) {
                task.setDuration(durationMs);
                changed = true;
            }
        }

        response.setState(task.getState().name());
        response.setStep(StringUtils.defaultIfBlank(normalizedStatus.getDisplayStep(), getV2KStepDisplayField(v2kStep)));

        if (!changed && !statusChanged) {
            return;
        }

        if (previousTaskState != ImportVmTask.TaskState.Failed && taskState == ImportVmTask.TaskState.Failed) {
            cleanupAblestackV2KDomain(task);
        }

        // Keep updated as step-start timestamp while running; update it only on terminal states.
        if (taskState == ImportVmTask.TaskState.Completed || taskState == ImportVmTask.TaskState.Failed) {
            task.setUpdated(now);
        }
        importVMTaskDao.update(task.getId(), task);
        if (statusChanged) {
            appendImportVMTaskEvent(task, EVENT_TYPE_STATUS, normalizedStatus, buildV2KStatusMessage(status), null);
        }
    }

    private ImportVmTaskStatus normalizeV2KStatus(AblestackV2KStatusAnswer status) {
        return new ImportVmTaskStatus(StringUtils.trimToNull(status.getPhase()), StringUtils.trimToNull(status.getMigrationState()),
                StringUtils.trimToNull(status.getMigrationStep()), StringUtils.trimToNull(status.getWorkdir()),
                StringUtils.trimToNull(status.getSyncPhysical()), normalizeDisplayStep(status.getDisplayStep(), status.getMigrationStep()),
                StringUtils.trimToNull(status.getSyncProgressLabel()), status.getSyncDoneBytes(), status.getSyncTotalBytes(), status.getSyncPercent(),
                status.getSyncCumulativeDoneBytes(), status.getSyncCumulativeKnownBytes(), status.getSyncCumulativePercent());
    }

    private ImportVmTaskStatus normalizeN2KStatus(AblestackN2KStatusAnswer status) {
        return new ImportVmTaskStatus(StringUtils.trimToNull(status.getPhase()), StringUtils.trimToNull(status.getMigrationState()),
                StringUtils.trimToNull(status.getMigrationStep()), StringUtils.trimToNull(status.getWorkdir()),
                StringUtils.trimToNull(status.getSyncPhysical()), normalizeDisplayStep(status.getDisplayStep(), status.getMigrationStep()),
                StringUtils.trimToNull(status.getSyncProgressLabel()), status.getSyncDoneBytes(), status.getSyncTotalBytes(), status.getSyncPercent(),
                status.getSyncCumulativeDoneBytes(), status.getSyncCumulativeKnownBytes(), status.getSyncCumulativePercent());
    }

    private ImportVmTaskStatus normalizeN2KStatusForTask(ImportVMTaskVO task, AblestackN2KStatusAnswer status) {
        ImportVmTaskStatus normalizedStatus = normalizeN2KStatus(status);
        if (task == null || isFailedV2KMigrationState(normalizedStatus.getMigrationState())) {
            return normalizedStatus;
        }

        String workdir = StringUtils.defaultIfBlank(normalizedStatus.getWorkdir(), task.getWorkdir());
        String v2kStep = normalizeV2KStep(task.getV2kStep());
        if (task.getState() == ImportVmTask.TaskState.Completed || ImportVmTask.V2KStep.Completed.name().equals(v2kStep)) {
            return new ImportVmTaskStatus(ImportVmTask.MigrationPhase.Completed.getValue(),
                    ImportVmTask.MigrationState.Completed.getValue(), ImportVmTask.V2KStep.Completed.name(), workdir, "100%",
                    "Migration Completed", normalizedStatus.getSyncProgressLabel(), normalizedStatus.getSyncDoneBytes(), normalizedStatus.getSyncTotalBytes(),
                    normalizedStatus.getSyncPercent(), normalizedStatus.getSyncCumulativeDoneBytes(), normalizedStatus.getSyncCumulativeKnownBytes(),
                    normalizedStatus.getSyncCumulativePercent());
        }
        if (ImportVmTask.V2KStep.Phase2_In_Progress.name().equals(v2kStep)) {
            return new ImportVmTaskStatus(ImportVmTask.MigrationPhase.Phase2.getValue(),
                    ImportVmTask.MigrationState.Running.getValue(), ImportVmTask.V2KStep.Phase2_In_Progress.name(),
                    workdir, normalizedStatus.getSyncPhysical(), normalizedStatus.getDisplayStep(), normalizedStatus.getSyncProgressLabel(),
                    normalizedStatus.getSyncDoneBytes(), normalizedStatus.getSyncTotalBytes(), normalizedStatus.getSyncPercent(),
                    normalizedStatus.getSyncCumulativeDoneBytes(), normalizedStatus.getSyncCumulativeKnownBytes(), normalizedStatus.getSyncCumulativePercent());
        }
        if (ImportVmTask.V2KStep.Phase2_Completed.name().equals(v2kStep)) {
            return new ImportVmTaskStatus(ImportVmTask.MigrationPhase.Phase2.getValue(),
                    ImportVmTask.MigrationState.Completed.getValue(), ImportVmTask.V2KStep.Phase2_Completed.name(),
                    workdir, StringUtils.defaultIfBlank(normalizedStatus.getSyncPhysical(), "100%"), normalizedStatus.getDisplayStep(),
                    normalizedStatus.getSyncProgressLabel(), normalizedStatus.getSyncDoneBytes(), normalizedStatus.getSyncTotalBytes(),
                    normalizedStatus.getSyncPercent(), normalizedStatus.getSyncCumulativeDoneBytes(), normalizedStatus.getSyncCumulativeKnownBytes(),
                    normalizedStatus.getSyncCumulativePercent());
        }
        return normalizedStatus;
    }

    private boolean applyNormalizedStatus(ImportVMTaskVO task, ImportVmTaskStatus status) {
        if (task == null || status == null) {
            return false;
        }

        boolean changed = false;
        if (!StringUtils.equals(task.getCurrentPhase(), status.getCurrentPhase())) {
            task.setCurrentPhase(status.getCurrentPhase());
            changed = true;
        }
        if (!StringUtils.equals(task.getMigrationState(), status.getMigrationState())) {
            task.setMigrationState(status.getMigrationState());
            changed = true;
        }
        if (!StringUtils.equals(task.getMigrationStep(), status.getMigrationStep())) {
            task.setMigrationStep(status.getMigrationStep());
            changed = true;
        }
        if (!StringUtils.equals(task.getWorkdir(), status.getWorkdir())) {
            task.setWorkdir(status.getWorkdir());
            changed = true;
        }

        String statusJson = GsonHelper.getGson().toJson(statusPayload(status));
        if (!StringUtils.equals(task.getStatusJson(), statusJson)) {
            task.setStatusJson(statusJson);
            changed = true;
        }
        return changed;
    }

    private void applyV2KStepStatus(ImportVMTaskVO task, ImportVmTask.V2KStep step) {
        if (task == null || step == null) {
            return;
        }
        switch (step) {
            case Phase1_In_Progress:
                task.setCurrentPhase(ImportVmTask.MigrationPhase.Phase1.getValue());
                task.setMigrationState(ImportVmTask.MigrationState.Running.getValue());
                break;
            case Phase1_Completed:
                task.setCurrentPhase(ImportVmTask.MigrationPhase.Phase1.getValue());
                task.setMigrationState(ImportVmTask.MigrationState.Completed.getValue());
                break;
            case Phase2_In_Progress:
                task.setCurrentPhase(ImportVmTask.MigrationPhase.Phase2.getValue());
                task.setMigrationState(ImportVmTask.MigrationState.Running.getValue());
                break;
            case Phase2_Completed:
                task.setCurrentPhase(ImportVmTask.MigrationPhase.Phase2.getValue());
                task.setMigrationState(ImportVmTask.MigrationState.Completed.getValue());
                break;
            case Completed:
                task.setCurrentPhase(ImportVmTask.MigrationPhase.Completed.getValue());
                task.setMigrationState(ImportVmTask.MigrationState.Completed.getValue());
                break;
            default:
                break;
        }
        task.setMigrationStep(step.name());
    }

    private Map<String, String> statusPayload(ImportVmTaskStatus status) {
        Map<String, String> payload = new HashMap<>();
        if (status == null) {
            return payload;
        }
        payload.put("currentphase", status.getCurrentPhase());
        payload.put("migrationstate", status.getMigrationState());
        payload.put("migrationstep", status.getMigrationStep());
        payload.put("workdir", status.getWorkdir());
        payload.put("syncphysical", status.getSyncPhysical());
        payload.put("displaystep", status.getDisplayStep());
        payload.put("syncprogresslabel", status.getSyncProgressLabel());
        payload.put("syncdonebytes", status.getSyncDoneBytes() != null ? String.valueOf(status.getSyncDoneBytes()) : null);
        payload.put("synctotalbytes", status.getSyncTotalBytes() != null ? String.valueOf(status.getSyncTotalBytes()) : null);
        payload.put("syncpercent", status.getSyncPercent() != null ? String.valueOf(status.getSyncPercent()) : null);
        payload.put("synccumulativedonebytes", status.getSyncCumulativeDoneBytes() != null ? String.valueOf(status.getSyncCumulativeDoneBytes()) : null);
        payload.put("synccumulativeknownbytes", status.getSyncCumulativeKnownBytes() != null ? String.valueOf(status.getSyncCumulativeKnownBytes()) : null);
        payload.put("synccumulativepercent", status.getSyncCumulativePercent() != null ? String.valueOf(status.getSyncCumulativePercent()) : null);
        return payload;
    }

    private void applySyncProgressFields(ImportVMTaskResponse response, ImportVmTaskStatus status) {
        if (response == null || status == null) {
            return;
        }
        response.setDisplayStep(StringUtils.defaultIfBlank(status.getDisplayStep(), normalizeDisplayStep(status.getMigrationStep(), status.getMigrationStep())));
        response.setSyncProgressLabel(status.getSyncProgressLabel());
        response.setSyncDoneBytes(status.getSyncDoneBytes());
        response.setSyncTotalBytes(status.getSyncTotalBytes());
        response.setSyncPercent(status.getSyncPercent());
        response.setSyncCumulativeDoneBytes(status.getSyncCumulativeDoneBytes());
        response.setSyncCumulativeKnownBytes(status.getSyncCumulativeKnownBytes());
        response.setSyncCumulativePercent(status.getSyncCumulativePercent());
        if (response.getSyncDoneBytes() == null || response.getSyncTotalBytes() == null || response.getSyncPercent() == null) {
            applySyncPhysicalFallback(response, status.getSyncPhysical());
        }
    }

    private void applyStoredSyncProgress(ImportVMTaskResponse response, ImportVMTaskVO task) {
        if (response == null || task == null || StringUtils.isBlank(task.getStatusJson())) {
            applySyncPhysicalFallback(response, task != null ? task.getDescription() : null);
            return;
        }
        Map<String, String> payload;
        try {
            payload = GsonHelper.getGson().fromJson(task.getStatusJson(), CREDENTIAL_PAYLOAD_TYPE);
        } catch (RuntimeException e) {
            applySyncPhysicalFallback(response, task.getDescription());
            return;
        }
        if (payload == null) {
            return;
        }
        response.setDisplayStep(StringUtils.defaultIfBlank(payload.get("displaystep"), response.getDisplayStep()));
        response.setSyncProgressLabel(payload.get("syncprogresslabel"));
        response.setSyncDoneBytes(parseLong(payload.get("syncdonebytes")));
        response.setSyncTotalBytes(parseLong(payload.get("synctotalbytes")));
        response.setSyncPercent(parseInteger(payload.get("syncpercent")));
        response.setSyncCumulativeDoneBytes(parseLong(payload.get("synccumulativedonebytes")));
        response.setSyncCumulativeKnownBytes(parseLong(payload.get("synccumulativeknownbytes")));
        response.setSyncCumulativePercent(parseInteger(payload.get("synccumulativepercent")));
        if (response.getSyncDoneBytes() == null || response.getSyncTotalBytes() == null || response.getSyncPercent() == null) {
            applySyncPhysicalFallback(response, StringUtils.defaultIfBlank(payload.get("syncphysical"), task.getDescription()));
        }
    }

    private void applySyncPhysicalFallback(ImportVMTaskResponse response, String syncPhysical) {
        if (response == null || StringUtils.isBlank(syncPhysical)) {
            return;
        }
        Matcher matcher = Pattern.compile("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*G\\s*/\\s*([0-9]+(?:\\.[0-9]+)?)\\s*G\\s*\\((\\d+)%\\)").matcher(syncPhysical);
        if (!matcher.find()) {
            return;
        }
        long doneBytes = gibToBytes(matcher.group(1));
        long totalBytes = gibToBytes(matcher.group(2));
        Integer percent = parseInteger(matcher.group(3));
        response.setSyncDoneBytes(doneBytes);
        response.setSyncTotalBytes(totalBytes);
        response.setSyncPercent(percent);
        response.setSyncCumulativeDoneBytes(doneBytes);
        response.setSyncCumulativeKnownBytes(totalBytes);
        response.setSyncCumulativePercent(percent);
    }

    private long gibToBytes(String value) {
        try {
            return Math.round(Double.parseDouble(value) * 1024 * 1024 * 1024);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private Long parseLong(String value) {
        try {
            return StringUtils.isBlank(value) ? null : Long.parseLong(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        Long parsed = parseLong(value);
        return parsed != null ? parsed.intValue() : null;
    }

    private String resolveDisplayStep(ImportVMTaskVO task) {
        if (task == null) {
            return "-";
        }
        if (task.getState() == ImportVmTask.TaskState.Completed || StringUtils.equals(task.getV2kStep(), ImportVmTask.V2KStep.Completed.name())) {
            return "Migration Completed";
        }
        return normalizeDisplayStep(task.getMigrationStep(), task.getV2kStep());
    }

    private String normalizeDisplayStep(String primaryStep, String fallbackStep) {
        String step = StringUtils.defaultIfBlank(primaryStep, fallbackStep);
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(step));
        if (StringUtils.isBlank(normalized) || "-".equals(normalized) || "unknown".equals(normalized)) {
            return "-";
        }
        if (normalized.contains("completed") || normalized.contains("success") || normalized.equals("done")) {
            return "Migration Completed";
        }
        if (normalized.contains("final") && normalized.contains("sync")) {
            return "Final Sync";
        }
        if (normalized.contains("final") && (normalized.contains("snap") || normalized.contains("recovery-point"))) {
            return "Final Snap";
        }
        if (normalized.contains("shutdown")) {
            return "Guest Shutdown";
        }
        if (normalized.contains("incr") && normalized.contains("sync")) {
            return "Incr Sync";
        }
        if (normalized.contains("incr") && (normalized.contains("snap") || normalized.contains("recovery-point"))) {
            return "Incr Snap";
        }
        if (normalized.contains("base") && normalized.contains("sync")) {
            return "Base Sync";
        }
        if (normalized.contains("base") && (normalized.contains("snap") || normalized.contains("recovery-point"))) {
            return "Base Snap";
        }
        if (normalized.contains("initramfs") || normalized.contains("winpe") || normalized.contains("bootstrap")) {
            return "Initramfs/WinPE";
        }
        if (normalized.contains("init") || normalized.contains("preflight") || normalized.contains("inventory")
                || normalized.contains("prepare")) {
            return "Init";
        }
        if (normalized.contains("cutover") || normalized.contains("define-target") || normalized.contains("cleanup")) {
            return "Migration Completed";
        }
        return step;
    }

    private void cleanupAblestackV2KDomain(ImportVMTaskVO task) {
        if (task == null || task.getConvertHostId() <= 0 || StringUtils.isBlank(task.getSourceVMName())) {
            return;
        }
        HostVO convertHost = hostDao.findById(task.getConvertHostId());
        if (convertHost == null) {
            logger.warn("Unable to cleanup ablestack-v2k domain {} because conversion host id {} does not exist",
                    task.getSourceVMName(), task.getConvertHostId());
            return;
        }
        AblestackV2KUndefineDomainCommand cleanupCommand = new AblestackV2KUndefineDomainCommand(task.getSourceVMName(), true);
        cleanupCommand.setWait(60);
        try {
            Answer answer = agentManager.send(convertHost.getId(), cleanupCommand);
            if (answer == null || !answer.getResult()) {
                logger.warn("Unable to cleanup ablestack-v2k domain {} on host {} after failed status detection: {}",
                        task.getSourceVMName(), convertHost.getName(), answer != null ? answer.getDetails() : "no answer");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Could not cleanup ablestack-v2k domain {} on host {} after failed status detection due to: {}",
                    task.getSourceVMName(), convertHost.getName(), e.getMessage());
        }
    }

    private ImportVmTask.V2KStep getProgressiveV2KStep(String persistedV2kStep, ImportVmTask.V2KStep statusV2kStep) {
        String normalizedPersistedStep = normalizeV2KStep(persistedV2kStep);
        if (StringUtils.isBlank(normalizedPersistedStep)) {
            return statusV2kStep;
        }
        try {
            ImportVmTask.V2KStep persistedStep = ImportVmTask.V2KStep.valueOf(normalizedPersistedStep);
            return persistedStep.ordinal() > statusV2kStep.ordinal() ? persistedStep : statusV2kStep;
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid persisted v2k step [{}] found for import VM task while resolving current step.", persistedV2kStep);
            return statusV2kStep;
        }
    }

    private ImportVmTask.TaskState getTaskStateFromV2KStatus(ImportVMTaskVO task, AblestackV2KStatusAnswer status, ImportVmTask.V2KStep v2kStep) {
        if (isFailedV2KMigrationState(status.getMigrationState())) {
            return ImportVmTask.TaskState.Failed;
        }
        // For V2K workflow, task is completed only after unmanaged->managed import has finished.
        if (v2kStep == ImportVmTask.V2KStep.Completed && task != null && task.getVmId() != null) {
            return ImportVmTask.TaskState.Completed;
        }
        return ImportVmTask.TaskState.Running;
    }

    private boolean isFailedV2KMigrationState(String migrationState) {
        String state = StringUtils.lowerCase(StringUtils.trimToEmpty(migrationState));
        return state.contains("fail") || state.contains("error") || state.contains("abort") || state.contains("cancel");
    }

    private boolean isCompletedV2KMigrationState(String migrationState) {
        String state = StringUtils.lowerCase(StringUtils.trimToEmpty(migrationState));
        return state.contains("complete") || state.contains("success") || "done".equals(state) || "finished".equals(state);
    }

    private ImportVmTask.V2KStep getV2KStepFromStatus(AblestackV2KStatusAnswer status) {
        String phase = StringUtils.lowerCase(StringUtils.trimToEmpty(status.getPhase()));
        boolean completed = isCompletedV2KMigrationState(status.getMigrationState());

        if (phase.contains("phase2")) {
            return completed ? ImportVmTask.V2KStep.Phase2_Completed : ImportVmTask.V2KStep.Phase2_In_Progress;
        }
        if (phase.contains("phase1")) {
            return completed ? ImportVmTask.V2KStep.Phase1_Completed : ImportVmTask.V2KStep.Phase1_In_Progress;
        }
        return completed ? ImportVmTask.V2KStep.Completed : ImportVmTask.V2KStep.Phase1_In_Progress;
    }

    private String resolveV2KStep(ImportVMTaskVO task) {
        String normalizedPersistedStep = normalizeV2KStep(task != null ? task.getV2kStep() : null);
        if (StringUtils.isNotBlank(normalizedPersistedStep)) {
            return normalizedPersistedStep;
        }
        ImportVmTask.V2KStep inferredStep = inferV2KStepFromDescription(task != null ? task.getDescription() : null);
        return inferredStep != null ? inferredStep.name() : null;
    }

    private String normalizeV2KStep(String v2kStep) {
        String normalized = StringUtils.trimToNull(v2kStep);
        if (normalized == null || V2K_STEP_NONE.equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private ImportVmTask.V2KStep inferV2KStepFromDescription(String description) {
        String normalizedDescription = StringUtils.defaultString(description);
        if (!StringUtils.containsIgnoreCase(normalizedDescription, V2K_STATUS_PREFIX)
                && !StringUtils.containsIgnoreCase(normalizedDescription, "phase:")) {
            return null;
        }

        String phase = extractV2KField(normalizedDescription, V2K_PHASE_PATTERN);
        String migrationState = extractV2KField(normalizedDescription, V2K_STATE_PATTERN);
        boolean completed = isCompletedV2KMigrationState(migrationState);
        String phaseValue = StringUtils.lowerCase(StringUtils.trimToEmpty(phase));

        if (phaseValue.contains("phase2")) {
            return completed ? ImportVmTask.V2KStep.Phase2_Completed : ImportVmTask.V2KStep.Phase2_In_Progress;
        }
        if (phaseValue.contains("phase1")) {
            return completed ? ImportVmTask.V2KStep.Phase1_Completed : ImportVmTask.V2KStep.Phase1_In_Progress;
        }
        if (StringUtils.isNotBlank(migrationState)) {
            return completed ? ImportVmTask.V2KStep.Completed : ImportVmTask.V2KStep.Phase1_In_Progress;
        }
        return null;
    }

    private String extractV2KField(String description, Pattern pattern) {
        Matcher matcher = pattern.matcher(StringUtils.defaultString(description));
        if (!matcher.find()) {
            return null;
        }
        return StringUtils.trimToNull(matcher.group(1));
    }

    private String mergeV2KStatusAndDescription(String v2kStatusMessage, String descriptionWithoutV2K) {
        String statusLine = StringUtils.trimToNull(v2kStatusMessage);
        String message = StringUtils.trimToNull(descriptionWithoutV2K);
        if (statusLine == null) {
            return StringUtils.defaultString(message);
        }
        if (message == null) {
            return statusLine;
        }
        return String.format("%s%n%s", statusLine, message);
    }

    private String stripV2KStatusLine(String description) {
        String normalized = StringUtils.defaultString(description);
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        String[] lines = normalized.split("\\R");
        List<String> messageLines = new ArrayList<>();
        for (String line : lines) {
            if (StringUtils.startsWith(StringUtils.trimToEmpty(line), V2K_STATUS_PREFIX)) {
                continue;
            }
            messageLines.add(line);
        }
        return StringUtils.trimToEmpty(String.join("\n", messageLines));
    }

    private String buildV2KStatusMessage(AblestackV2KStatusAnswer status) {
        return String.format("%sPHASE: %s | STATE: %s | STEP: %s | SYNC(Physical): %s | WORKDIR: %s",
                V2K_STATUS_PREFIX,
                StringUtils.defaultString(status.getPhase(), "-"),
                StringUtils.defaultString(status.getMigrationState(), "-"),
                StringUtils.defaultString(status.getMigrationStep(), "-"),
                StringUtils.defaultString(status.getSyncPhysical(), "-"),
                StringUtils.defaultString(status.getWorkdir(), "-"));
    }

    private String buildN2KStatusMessage(ImportVmTaskStatus status) {
        return String.format("[N2K] PHASE: %s | STATE: %s | STEP: %s | SYNC(Physical): %s | WORKDIR: %s",
                StringUtils.defaultString(status.getCurrentPhase(), "-"),
                StringUtils.defaultString(status.getMigrationState(), "-"),
                StringUtils.defaultString(status.getMigrationStep(), "-"),
                StringUtils.defaultString(status.getSyncPhysical(), "-"),
                StringUtils.defaultString(status.getWorkdir(), "-"));
    }

    protected String getStepDisplayField(ImportVMTaskVO.Step step) {
        if (step == null) {
            return "-";
        }
        int totalSteps = ImportVMTaskVO.Step.values().length;
        return String.format("[%s/%s] %s", step.ordinal() + 1, totalSteps, step.name());
    }

    protected String getStoredV2KStepDisplayField(String storedV2kStep) {
        if (StringUtils.isBlank(storedV2kStep) || V2K_STEP_NONE.equalsIgnoreCase(storedV2kStep)) {
            return "-";
        }
        try {
            return getV2KStepDisplayField(ImportVmTask.V2KStep.valueOf(storedV2kStep));
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid persisted v2k step [{}] found for import VM task.", storedV2kStep);
            return storedV2kStep;
        }
    }

    protected String getV2KStepDisplayField(ImportVmTask.V2KStep step) {
        if (step == null) {
            return "-";
        }
        int totalSteps = ImportVmTask.V2KStep.values().length;
        return String.format("[%s/%s] %s", step.ordinal() + 1, totalSteps, step.name());
    }

    protected boolean isV2KTask(ImportVMTaskVO task) {
        return task != null && StringUtils.equals(task.getMigrationTool(), ImportVmTask.MigrationTool.AblestackV2K.getValue());
    }

    protected boolean isN2KTask(ImportVMTaskVO task) {
        return task != null && StringUtils.equals(task.getMigrationTool(), ImportVmTask.MigrationTool.AblestackN2K.getValue());
    }

    private String getCredentialState(ImportVMTaskVO task) {
        if (task == null) {
            return ImportVmTask.CredentialState.Missing.getValue();
        }
        if (task.getVcenterId() != null) {
            return ImportVmTask.CredentialState.Managed.getValue();
        }
        if (getStoredCredential(task) != null) {
            return ImportVmTask.CredentialState.Stored.getValue();
        }
        if (StringUtils.isNotBlank(task.getVcenterPassword())) {
            return ImportVmTask.CredentialState.Legacy.getValue();
        }
        return ImportVmTask.CredentialState.Missing.getValue();
    }

    private List<String> getAvailableActions(ImportVMTaskVO task) {
        List<String> actions = new ArrayList<>();
        actions.add(ImportVmTask.Action.Refresh.getValue());
        if (task == null) {
            return actions;
        }
        boolean ablestackTask = isAblestackImportTask(task);
        boolean phase1Completed = isPhase1CompletedWaiting(task);
        boolean activeRunning = task.getState() == ImportVmTask.TaskState.Running && !phase1Completed;
        boolean editableInactive = task.getState() != ImportVmTask.TaskState.Running || phase1Completed;

        if (getStoredCredential(task) != null && editableInactive) {
            actions.add(ImportVmTask.Action.ClearCredentials.getValue());
        }
        if (ablestackTask && phase1Completed) {
            actions.add(ImportVmTask.Action.Phase2.getValue());
        }
        if (ablestackTask && (activeRunning || phase1Completed)) {
            actions.add(ImportVmTask.Action.Cancel.getValue());
        }
        if (task.getState() == ImportVmTask.TaskState.Running && StringUtils.equals(task.getV2kStep(), ImportVmTask.V2KStep.Phase2_Completed.name())) {
            actions.add(ImportVmTask.Action.Finalize.getValue());
        }
        if (ablestackTask && canResumeImportVMTask(task)) {
            actions.add(ImportVmTask.Action.Resume.getValue());
        }
        if (ablestackTask && canRetryImportVMTaskFromStart(task)) {
            actions.add(ImportVmTask.Action.RetryFromStart.getValue());
        }
        if (task.getState() == ImportVmTask.TaskState.Failed || task.getState() == ImportVmTask.TaskState.Cancelled ||
                task.getState() == ImportVmTask.TaskState.Completed || phase1Completed) {
            actions.add(ImportVmTask.Action.Delete.getValue());
        }
        return actions;
    }

    private boolean isAblestackImportTask(ImportVMTaskVO task) {
        return isV2KTask(task) || isN2KTask(task);
    }

    private boolean isPhase1CompletedWaiting(ImportVMTaskVO task) {
        if (task == null) {
            return false;
        }
        if (StringUtils.equals(task.getV2kStep(), ImportVmTask.V2KStep.Phase1_Completed.name())) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(task.getCurrentPhase(), ImportVmTask.MigrationPhase.Phase1.getValue()) &&
                StringUtils.equalsIgnoreCase(task.getMigrationState(), ImportVmTask.MigrationState.Completed.getValue())) {
            return true;
        }
        return StringUtils.containsIgnoreCase(task.getMigrationStep(), "Phase1_Completed");
    }

    private boolean canResumeImportVMTask(ImportVMTaskVO task) {
        return task != null && task.getConvertHostId() > 0 && StringUtils.isNotBlank(task.getWorkdir()) &&
                (task.getState() == ImportVmTask.TaskState.Failed || task.getState() == ImportVmTask.TaskState.Cancelled);
    }

    private boolean canRetryImportVMTaskFromStart(ImportVMTaskVO task) {
        return task != null && task.getConvertHostId() > 0 &&
                (task.getState() == ImportVmTask.TaskState.Failed || task.getState() == ImportVmTask.TaskState.Cancelled ||
                        isPhase1CompletedWaiting(task));
    }

    private ImportVMTaskEventResponse createImportVMTaskEventResponse(ImportVMTaskVO task, ImportVMTaskEventVO event) {
        ImportVMTaskEventResponse response = new ImportVMTaskEventResponse();
        response.setId(event.getUuid());
        response.setImportVmTaskId(task.getUuid());
        response.setEventType(event.getEventType());
        response.setPhase(event.getPhase());
        response.setState(event.getState());
        response.setStep(event.getStep());
        response.setMessage(event.getMessage());
        response.setPayload(event.getPayloadJson());
        response.setCreated(event.getCreated());
        response.setObjectName("importvmtaskevent");
        return response;
    }

    private ImportVMTaskEventVO appendImportVMTaskEvent(ImportVMTaskVO task, String eventType, ImportVmTaskStatus status, String message,
                                                       Map<String, String> payload) {
        if (task == null) {
            return null;
        }
        String payloadJson = null;
        Map<String, String> sanitizedPayload = sanitizeEventPayload(payload);
        if (!sanitizedPayload.isEmpty()) {
            payloadJson = GsonHelper.getGson().toJson(sanitizedPayload);
        }
        ImportVMTaskEventVO event = new ImportVMTaskEventVO(task.getId(), eventType,
                status != null ? status.getCurrentPhase() : task.getCurrentPhase(),
                status != null ? status.getMigrationState() : task.getMigrationState(),
                status != null ? status.getMigrationStep() : task.getMigrationStep(),
                message, payloadJson);
        return importVMTaskEventDao.persist(event);
    }

    private Map<String, String> sanitizeEventPayload(Map<String, String> payload) {
        Map<String, String> sanitizedPayload = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return sanitizedPayload;
        }
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            sanitizedPayload.put(entry.getKey(), isSensitiveEventPayloadKey(entry.getKey()) ? MASKED_VALUE : entry.getValue());
        }
        return sanitizedPayload;
    }

    private boolean isSensitiveEventPayloadKey(String key) {
        String lowerKey = StringUtils.lowerCase(StringUtils.defaultString(key));
        for (String sensitiveKey : SENSITIVE_EVENT_KEYS) {
            if (lowerKey.contains(sensitiveKey)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> actionPayload(ImportVmTask.Action action) {
        Map<String, String> payload = new HashMap<>();
        payload.put("action", action.getValue());
        return payload;
    }

    protected static String getDurationDisplay(Long durationMs) {
        if (durationMs == null) {
            return null;
        }
        long hours = durationMs / (1000 * 60 * 60);
        long minutes = (durationMs / (1000 * 60)) % 60;
        long seconds = (durationMs / 1000) % 60;

        StringBuilder result = new StringBuilder();
        if (hours > 0) {
            result.append(String.format("%s hs ", hours));
        }
        if (minutes > 0) {
            result.append(String.format("%s min ", minutes));
        }
        if (seconds > 0) {
            result.append(String.format("%s secs", seconds));
        }
        return result.toString();
    }
}
