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

package com.cloud.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.api.command.admin.GetSecurityCheckCmd;
import org.apache.cloudstack.api.command.admin.RunSecurityCheckCmd;
import org.apache.cloudstack.api.command.admin.DeleteSecurityCheckResultCmd;
import org.apache.cloudstack.api.response.GetSecurityCheckResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.alert.AlertManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.security.dao.SecurityCheckDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

import java.util.stream.Collectors;

public class SecurityCheckServiceImpl extends ManagerBase implements PluggableService, SecurityCheckService, Configurable {

    protected static Logger LOGGER = LogManager.getLogger(SecurityCheckServiceImpl.class);

    private static final ConfigKey<Integer> SecurityCheckInterval = new ConfigKey<>("Advanced", Integer.class,
            "security.check.interval", "4",
            "The interval security check background tasks in hour", false);
    private static String runMode = "";

    @Inject
    private SecurityCheckDao securityCheckDao;
    @Inject
    private ManagementServerHostDao msHostDao;
    @Inject
    private AlertManager alertManager;
    ScheduledExecutorService executor;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("SecurityChecker"));
        return true;
    }

    @Override
    public boolean start() {
        runMode = "first";
        if(SecurityCheckInterval.value() != 0) {
            executor.scheduleAtFixedRate(new SecurityCheckTask(), 0, SecurityCheckInterval.value(), TimeUnit.HOURS);
        }
        return true;
    }

    @Override
    public boolean stop() {
        runMode = "";
        return true;
    }

    protected class SecurityCheckTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                securityCheck();
            } catch (Exception e) {
                LOGGER.error("Exception in security check schedule : "+ e);
            }
        }

        private void securityCheck() {
            String type = "";
            if (runMode == "first") {
                type = "Execution";
                ActionEventUtils.onStartedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventTypes.EVENT_SECURITY_CHECK,
                    "제품 실행 시 관리서버에 대한 주기적인 보안점검을 실시합니다.", new Long(0), null, true, 0);
            } else {
                type = "Routine";
                ActionEventUtils.onStartedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventTypes.EVENT_SECURITY_CHECK,
                    "제품 운영 시 관리서버에 대한 주기적인 보안점검을 실시합니다.", new Long(0), null, true, 0);
            }
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            String path = Script.findScript("scripts/security/", "securitycheck.sh");
            if (path == null) {
                updateSecurityCheckResult(msHost.getId(), false, "", type);
                LOGGER.error("Failed to execute security check schedule for management server:  Unable to find the securitycheck script");
            }
            ProcessBuilder processBuilder = new ProcessBuilder("sh", path);
            Process process = null;
            List<Boolean> checkResults = new ArrayList<>();
            List<String> checkFailedList = new ArrayList<>();
            boolean checkFinalResult;
            try {
                process = processBuilder.start();
                BufferedReader bfr = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = bfr.readLine()) != null) {
                    String[] temp = line.split(",");
                    String checkName = temp[0];
                    String checkResult = temp[1];
                    if ("false".equals(checkResult)) {
                        checkResults.add(false);
                        checkFailedList.add(checkName);
                    } else {
                        checkResults.add(true);
                    }
                }
                checkFinalResult = checkConditions(checkResults);
                String checkFailedListToString = checkFailedList.stream().collect(Collectors.joining(", "));
                updateSecurityCheckResult(msHost.getId(), checkFinalResult, checkFailedListToString, type);
                runMode = "";
            } catch (IOException e) {
                updateSecurityCheckResult(msHost.getId(), false, "", type);
                runMode = "";
                LOGGER.error("Failed to execute security check schedule for management server: "+e);
            }
        }
    }

    @Override
    public List<GetSecurityCheckResponse> listSecurityChecks(GetSecurityCheckCmd cmd) {
        long mshostId = cmd.getMsHostId();
        List<SecurityCheck> result = new ArrayList<>(securityCheckDao.getSecurityChecks(mshostId));
        List<GetSecurityCheckResponse> responses = new ArrayList<>(result.size());
        for (SecurityCheck scResult : result) {
            GetSecurityCheckResponse securityCheckResponse = new GetSecurityCheckResponse();
            securityCheckResponse.setObjectName("securitychecks");
            securityCheckResponse.setId(scResult.getId());
            securityCheckResponse.setCheckResult(scResult.getCheckResult());
            securityCheckResponse.setCheckDate(scResult.getCheckDate());
            securityCheckResponse.setCheckFailedList(scResult.getCheckFailedList());
            securityCheckResponse.setType(scResult.getType());
            securityCheckResponse.setService(scResult.getService());
            responses.add(securityCheckResponse);
        }
        return responses;
    }

    public static boolean checkConditions(List<Boolean> conditions) {
        for (boolean condition : conditions) {
            if (!condition) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean runSecurityCheckCommand(final RunSecurityCheckCmd cmd) {
        ActionEventUtils.onStartedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventTypes.EVENT_SECURITY_CHECK,
                    "제품 운영 시 관리 서버에 대한 수동 보안 점검을 실행합니다.", new Long(0), null, true, 0);
        Long mshostId = cmd.getMsHostId();
        ManagementServerHost mshost = msHostDao.findById(mshostId);
        String type = "Manual";
        String path = Script.findScript("scripts/security/", "securitycheck.sh");
        if (path == null) {
            updateSecurityCheckResult(mshost.getId(), false, "", type);
            throw new CloudRuntimeException(String.format("Failed to execute security check command for management server: Unable to find the securitycheck script"));
        }
        ProcessBuilder processBuilder = new ProcessBuilder("sh", path);
        Process process = null;
        List<Boolean> checkResults = new ArrayList<>();
        List<String> checkFailedList = new ArrayList<>();
        boolean checkFinalResult;
        try {
            process = processBuilder.start();
            StringBuffer output = new StringBuffer();
            BufferedReader bfr = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = bfr.readLine()) != null) {
                String[] temp = line.split(",");
                String checkName = temp[0];
                String checkResult = temp[1];
                if ("false".equals(checkResult)) {
                    checkResults.add(false);
                    checkFailedList.add(checkName);
                } else {
                    checkResults.add(true);
                }
                output.append(line).append('\n');
            }
            checkFinalResult = checkConditions(checkResults);
            String checkFailedListToString = checkFailedList.stream().collect(Collectors.joining(", "));
            updateSecurityCheckResult(mshost.getId(), checkFinalResult, checkFailedListToString, type);
            if (output.toString().contains("false")) {
                return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            updateSecurityCheckResult(mshost.getId(), false, "", type);
            throw new CloudRuntimeException("Failed to execute security check command for management server: "+mshost.getId() +e);
        }
    }

    @Override
    @ActionEvent(eventType = SecurityCheckEventTypes.EVENT_SECURITY_CHECK_DELETE, eventDescription = "Deleting Security check result")
    public boolean deleteSecurityCheckResult(final DeleteSecurityCheckResultCmd cmd) {
        final Long resultId = cmd.getId();
        SecurityCheck result = securityCheckDao.findById(resultId);
        if (result == null) {
            throw new InvalidParameterValueException("Invalid security check result id specified");
        }
        return securityCheckDao.remove(result.getId());
    }

    private void updateSecurityCheckResult(long msHostId, boolean checkFinalResult, String checkFailedList, String type) {
        if (checkFinalResult) {
            if ("Execution".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 실행 시 관리 서버에서 보안 검사를 성공적으로 완료했습니다.", new Long(0), null, 0);
            } else if ("Routine".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 운영 시 관리서버에서 보안점검 일정을 성공적으로 완료하였습니다.", new Long(0), null, 0);
            } else if ("Manual".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 운영 시 관리 서버에 대한 보안 점검을 성공적으로 완료했습니다.", new Long(0), null, 0);
            }
        } else {
            if ("Execution".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 실행시 관리서버에서 보안검사를 실행하지 못했습니다.", new Long(0), null, 0);
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 실행시 관리서버에서 보안검사를 실행하지 못했습니다.", "");
            } else if ("Routine".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 운영시 관리서버에서 보안점검 스케줄을 실행하지 못했습니다.", new Long(0), null, 0);
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 운영시 관리서버에서 보안점검 스케줄을 실행하지 못했습니다.", "");
            } else if ("Manual".equals(type)) {
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                    EventTypes.EVENT_SECURITY_CHECK, "제품 운영시 관리서버에 대한 보안검사를 실행하지 못했습니다.", new Long(0), null, 0);
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 운영시 관리서버에 대한 보안검사를 실행하지 못했습니다.", "");
            }
        }
        String service = "Mold";
        SecurityCheckVO connectivityVO = new SecurityCheckVO(msHostId, checkFinalResult, checkFailedList, type, service);
        connectivityVO.setMsHostId(msHostId);
        connectivityVO.setCheckResult(checkFinalResult);
        connectivityVO.setCheckFailedList(checkFailedList);
        connectivityVO.setCheckDate(new Date());
        connectivityVO.setType(type);
        connectivityVO.setService(service);
        securityCheckDao.persist(connectivityVO);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(RunSecurityCheckCmd.class);
        cmdList.add(GetSecurityCheckCmd.class);
        cmdList.add(DeleteSecurityCheckResultCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return SecurityCheckServiceImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                SecurityCheckInterval
        };
    }
}