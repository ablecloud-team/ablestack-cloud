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

import com.cloud.alert.AlertManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.security.dao.IntegrityVerificationDao;
import com.cloud.security.dao.IntegrityVerificationFinalResultDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.admin.GetIntegrityVerificationCmd;
import org.apache.cloudstack.api.command.admin.GetIntegrityVerificationFinalResultCmd;
import org.apache.cloudstack.api.command.admin.RunIntegrityVerificationCmd;
import org.apache.cloudstack.api.command.admin.DeleteIntegrityVerificationFinalResultCmd;
import org.apache.cloudstack.api.response.GetIntegrityVerificationFinalResultListResponse;
import org.apache.cloudstack.api.response.GetIntegrityVerificationResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.Date;

public class IntegrityVerificationServiceImpl extends ManagerBase implements PluggableService, IntegrityVerificationService, Configurable {

    private static final Logger LOGGER = LogManager.getLogger(IntegrityVerificationServiceImpl.class);

    private static final ConfigKey<Integer> IntegrityVerificationInterval = new ConfigKey<>("Advanced", Integer.class,
            "integrity.verification.interval", "4",
            "The interval integrity verification background tasks in hour", false);
    private static String runMode = "";

    @Inject
    private IntegrityVerificationDao integrityVerificationDao;
    @Inject
    private IntegrityVerificationFinalResultDao integrityVerificationFinalResultDao;
    @Inject
    private ManagementServerHostDao msHostDao;
    @Inject
    private AlertManager alertManager;
    ScheduledExecutorService executor;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        executor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("IntegrityVerifier"));
        return true;
    }

    @Override
    public boolean start() {
        runMode = "first";
        if(IntegrityVerificationInterval.value() != 0) {
            executor.scheduleAtFixedRate(new IntegrityVerificationTask(), 0, IntegrityVerificationInterval.value(), TimeUnit.HOURS);
        }
        return true;
    }

    public boolean stop() {
        runMode = "";
        return true;
    }

    protected class IntegrityVerificationTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                integrityVerification();
            } catch (Exception e) {
                LOGGER.error("Exception in Integrity Verification : "+ e);
            }
        }

        private void integrityVerification() {
            ActionEventUtils.onStartedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventTypes.EVENT_INTEGRITY_VERIFICATION,
                    "제품 실행 시 관리서버에서 주기적인 무결성 검증을 실행합니다.", new Long(0), null, true, 0);
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            List<String> verificationFailedList = new ArrayList<>();
            List<Boolean> verificationResults = new ArrayList<>();
            boolean verificationResult;
            boolean verificationFinalResult;
            String comparisonHashValue;
            String uuid;
            String type = "";
            if (runMode == "first") {
                type = "Execution";
                // mold 시작 또는 재시작 시 initial 업데이트
                List<IntegrityVerification> exeResult = new ArrayList<>(integrityVerificationDao.getIntegrityVerifications(msHost.getId()));
                for (IntegrityVerification exe : exeResult) {
                    String exeFilePath = exe.getFilePath();
                    File exeFile = new File(exeFilePath);
                    try {
                        String exeComparisonHashValue = calculateHash(exeFile, "SHA-512");
                        boolean exeVerificationResult = true;
                        String exeVerificationMessage = "The integrity of the file has been verified.";
                        updateIntegrityVerification(msHost.getId(), exeFilePath, exeComparisonHashValue, exeVerificationResult, exeVerificationMessage);
                    } catch (NoSuchAlgorithmException | IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                type = "Routine";
            }
            List<IntegrityVerification> result = new ArrayList<>(integrityVerificationDao.getIntegrityVerifications(msHost.getId()));
            for (IntegrityVerification ivResult : result) {
                String filePath = ivResult.getFilePath();
                String initialHashValue = ivResult.getInitialHashValue();
                String verificationMessage;
                File file = new File(filePath);
                try {
                    comparisonHashValue = calculateHash(file, "SHA-512");
                    if (initialHashValue.equals(comparisonHashValue)) {
                        verificationResults.add(true);
                        verificationResult = true;
                        verificationMessage = "The integrity of the file has been verified.";
                    } else {
                        verificationResults.add(false);
                        verificationResult = false;
                        verificationMessage = "The integrity of the file could not be verified. at last verification.";
                        verificationFailedList.add(filePath);
                    }
                    updateIntegrityVerificationResult(msHost.getId(), filePath, comparisonHashValue, verificationResult, verificationMessage);
                } catch (NoSuchAlgorithmException | IOException e) {
                    throw new RuntimeException(e);
                }
            }
            uuid = UUID.randomUUID().toString();
            verificationFinalResult = checkConditions(verificationResults);
            String verificationFailedListToString = verificationFailedList.stream().collect(Collectors.joining(", "));
            verificationFailedListToString = verificationFailedListToString.replaceFirst(", $", "");
            updateIntegrityVerificationFinalResult(msHost.getId(), uuid, verificationFinalResult, verificationFailedListToString, type);
            runMode = "";
        }

        private String calculateHash(File file, String algorithm) throws NoSuchAlgorithmException, IOException {
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            File tempFile = null;
            if (!(file.exists())) {
                tempFile = createTempFileWithRandomContent();
                file = tempFile;
            }
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; // Adjust the buffer size as needed
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            } catch (FileNotFoundException e) {
                throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: Unable to find the file"+ e));
            } catch (IOException e) {
                throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: "+msHost.getId()+ e));
            }

            byte[] hashBytes = digest.digest();

            // Convert the byte array to a hexadecimal string
            StringBuilder hexStringBuilder = new StringBuilder();
            for (byte hashByte : hashBytes) {
                hexStringBuilder.append(String.format("%02x", hashByte));
            }
            if (tempFile != null) {
                tempFile.delete();
            }
            return hexStringBuilder.toString();
        }
    }

    @Override
    public List<GetIntegrityVerificationResponse> listIntegrityVerifications(GetIntegrityVerificationCmd cmd) {
        long mshostId = cmd.getMsHostId();
        List<IntegrityVerification> result = new ArrayList<>(integrityVerificationDao.getIntegrityVerifications(mshostId));
        List<GetIntegrityVerificationResponse> responses = new ArrayList<>(result.size());
        for (IntegrityVerification ivResult : result) {
            GetIntegrityVerificationResponse integrityVerificationResponse = new GetIntegrityVerificationResponse();
            integrityVerificationResponse.setObjectName("integrity_verification");
            integrityVerificationResponse.setFilePath(ivResult.getFilePath());
            integrityVerificationResponse.setVerificationResult(ivResult.getVerificationResult());
            integrityVerificationResponse.setVerificationDate(ivResult.getVerificationDate());
            integrityVerificationResponse.setVerificationDetails(ivResult.getParsedVerificationDetails());
            responses.add(integrityVerificationResponse);
        }
        return responses;
    }

    @Override
    public ListResponse<GetIntegrityVerificationFinalResultListResponse> listIntegrityVerificationFinalResults(final GetIntegrityVerificationFinalResultCmd cmd) {
        final Long id = cmd.getId();
        Filter searchFilter = new Filter(IntegrityVerificationFinalResultVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<IntegrityVerificationFinalResultVO> sb = integrityVerificationFinalResultDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        SearchCriteria<IntegrityVerificationFinalResultVO> sc = sb.create();
        String keyword = cmd.getKeyword();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if(keyword != null){
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <IntegrityVerificationFinalResultVO> versions = integrityVerificationFinalResultDao.search(sc, searchFilter);
        return createAutomationControllerVersionListResponse(versions);
    }

    private ListResponse<GetIntegrityVerificationFinalResultListResponse> createAutomationControllerVersionListResponse(List<IntegrityVerificationFinalResultVO> versions) {
        List<GetIntegrityVerificationFinalResultListResponse> responseList = new ArrayList<>();
        for (IntegrityVerificationFinalResultVO version : versions) {
            responseList.add(createIntegrityVerificationFinalResultResponse(version));
        }
        ListResponse<GetIntegrityVerificationFinalResultListResponse> response = new ListResponse<>();
        response.setResponses(responseList);
        return response;
    }

    private GetIntegrityVerificationFinalResultListResponse createIntegrityVerificationFinalResultResponse(final IntegrityVerificationFinalResult integrityVerificationFinalResult) {
        GetIntegrityVerificationFinalResultListResponse response = new GetIntegrityVerificationFinalResultListResponse();
        response.setObjectName("integrityverificationsfinalresults");
        response.setId(integrityVerificationFinalResult.getId());
        response.setUuid(integrityVerificationFinalResult.getUuid());
        response.setVerificationFinalResult(integrityVerificationFinalResult.getVerificationFinalResult());
        response.setVerificationDate(integrityVerificationFinalResult.getVerificationDate());
        response.setVerificationFailedList(integrityVerificationFinalResult.getVerificationFailedList());
        response.setType(integrityVerificationFinalResult.getType());
        return response;
    }

    // Generate a temporary file with random content
    private File createTempFileWithRandomContent() throws IOException {
        File tempFile = File.createTempFile("randomFile", ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            writer.write("Random content: " + Math.random());
            // Add a new line if needed
            writer.newLine();
        }
        return tempFile;
    }

//    private String calculateHash(File file, String algorithm) throws NoSuchAlgorithmException, IOException {
//        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
//        MessageDigest md = MessageDigest.getInstance(algorithm);
//        File tempFile = null;
//        if (!(file.exists())) {
//            tempFile = createTempFileWithRandomContent();
//            file = tempFile;
//        }
//        try (DigestInputStream dis = new DigestInputStream(new FileInputStream(file), md)) {
//            // Read the file to update the digest
//            while (dis.read() != -1) ;
//        } catch (FileNotFoundException e) {
//            throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: Unable to find the file"+ e));
//        } catch (IOException e) {
//            throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: "+msHost.getId()+ e));
//        }
//        byte[] hashBytes = md.digest();
//        StringBuilder hexString = new StringBuilder();
//        for (byte hashByte : hashBytes) {
//            String hex = Integer.toHexString(0xFF & hashByte);
//            if (hex.length() == 1) {
//                hexString.append('0');
//            }
//            hexString.append(hex);
//        }
//        if (tempFile != null) {
//            tempFile.delete();
//        }
//        return hexString.toString();
//    }

    private String calculateHash(File file, String algorithm) throws IOException, NoSuchAlgorithmException {
        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        File tempFile = null;
        if (!(file.exists())) {
            tempFile = createTempFileWithRandomContent();
            file = tempFile;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; // Adjust the buffer size as needed
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        } catch (FileNotFoundException e) {
            throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: Unable to find the file"+ e));
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Failed to execute integrity verification command for management server: "+msHost.getId()+ e));
        }

        byte[] hashBytes = digest.digest();

        // Convert the byte array to a hexadecimal string
        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte hashByte : hashBytes) {
            hexStringBuilder.append(String.format("%02x", hashByte));
        }
        if (tempFile != null) {
            tempFile.delete();
        }
        return hexStringBuilder.toString();
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
    public boolean runIntegrityVerificationCommand(final RunIntegrityVerificationCmd cmd) {
        ActionEventUtils.onStartedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventTypes.EVENT_INTEGRITY_VERIFICATION,
                    "제품 운영 시 관리서버에서 수동 무결성 검증을 실행합니다.", new Long(0), null, true, 0);
        Long mshostId = cmd.getMsHostId();
        List<Boolean> verificationResults = new ArrayList<>();
        List<String> verificationFailedList = new ArrayList<>();
        boolean verificationResult;
        boolean verificationFinalResult;
        String comparisonHashValue;
        String uuid;
        String type = "Manual";
        ManagementServerHost msHost = msHostDao.findById(mshostId);
        List<IntegrityVerification> result = new ArrayList<>(integrityVerificationDao.getIntegrityVerifications(mshostId));
        for (IntegrityVerification ivResult : result) {
            String filePath = ivResult.getFilePath();
            String initialHashValue = ivResult.getInitialHashValue();
            String verificationMessage;
            File file = new File(filePath);
            try {
                comparisonHashValue = calculateHash(file, "SHA-512");
                if (initialHashValue.equals(comparisonHashValue)) {
                    verificationResults.add(true);
                    verificationResult = true;
                    verificationMessage = "The integrity of the file has been verified.";
                } else {
                    verificationResults.add(false);
                    verificationResult = false;
                    verificationMessage = "The integrity of the file could not be verified. at last verification.";
                    verificationFailedList.add(filePath);
                }
                updateIntegrityVerificationResult(msHost.getId(), filePath, comparisonHashValue, verificationResult, verificationMessage);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        uuid = UUID.randomUUID().toString();
        verificationFinalResult = checkConditions(verificationResults);
        String verificationFailedListToString = verificationFailedList.stream().collect(Collectors.joining(", "));
        verificationFailedListToString = verificationFailedListToString.replaceFirst(", $", "");
        updateIntegrityVerificationFinalResult(msHost.getId(), uuid, verificationFinalResult, verificationFailedListToString, type);
        return verificationFinalResult;
    }

    private void updateIntegrityVerification(long msHostId, String filePath, String comparisonHashValue, boolean verificationResult, String verificationMessage) {
        boolean newIntegrityVerificationEntry = false;
        IntegrityVerificationVO connectivityVO = integrityVerificationDao.getIntegrityVerificationResult(msHostId, filePath);
        if (connectivityVO == null) {
            connectivityVO = new IntegrityVerificationVO(msHostId, filePath);
            newIntegrityVerificationEntry = true;
        }
        connectivityVO.setVerificationResult(verificationResult);
        connectivityVO.setComparisonHashValue(comparisonHashValue);
        connectivityVO.setInitialHashValue(comparisonHashValue);
        connectivityVO.setVerificationDate(new Date());
        if (StringUtils.isNotEmpty(verificationMessage)) {
            connectivityVO.setVerificationDetails(verificationMessage.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
        }
        if (newIntegrityVerificationEntry) {
            integrityVerificationDao.persist(connectivityVO);
        } else {
            integrityVerificationDao.update(connectivityVO.getId(), connectivityVO);
        }
    }

    private void updateIntegrityVerificationResult(final long msHostId, String filePath, String comparisonHashValue, boolean verificationResult, String verificationMessage) {
        boolean newIntegrityVerificationEntry = false;
        IntegrityVerificationVO connectivityVO = integrityVerificationDao.getIntegrityVerificationResult(msHostId, filePath);
        if (connectivityVO == null) {
            connectivityVO = new IntegrityVerificationVO(msHostId, filePath);
            newIntegrityVerificationEntry = true;
        }
        connectivityVO.setVerificationResult(verificationResult);
        connectivityVO.setComparisonHashValue(comparisonHashValue);
        connectivityVO.setVerificationDate(new Date());
        if (StringUtils.isNotEmpty(verificationMessage)) {
            connectivityVO.setVerificationDetails(verificationMessage.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
        }
        if (newIntegrityVerificationEntry) {
            integrityVerificationDao.persist(connectivityVO);
        } else {
            integrityVerificationDao.update(connectivityVO.getId(), connectivityVO);
        }
    }

    @Override
    public boolean deleteIntegrityVerificationFinalResults(final DeleteIntegrityVerificationFinalResultCmd cmd) {
        final Long resultId = cmd.getId();
        IntegrityVerificationFinalResult result = integrityVerificationFinalResultDao.findById(resultId);
        if (result == null) {
            throw new InvalidParameterValueException("잘못된 무결성 검증 최종 결과 ID가 지정되었습니다.");
        }
        return integrityVerificationFinalResultDao.remove(result.getId());
    }

    private void updateIntegrityVerificationFinalResult(final long msHostId, String uuid, boolean verificationFinalResult, String verificationFailedListToString, String type) {
        if (verificationFinalResult == false) {
            if(type.equals("Execution")){
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 실행시 관리서버에서 무결성 검증을 실행하지 못했습니다.", "");
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 실행시 관리서버에서 무결성 검증을 실행하지 못했습니다.", new Long(0), null, 0);
            }else if(type.equals("Routine")){
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 운영 중 관리서버에서 무결성 검증 일정을 실행하지 못했습니다.", "");
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 운영 중 관리서버에서 무결성 검증 일정을 실행하지 못했습니다.", new Long(0), null, 0);
            }else if(type.equals("Manual")){
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), "제품 운영 중 관리서버에서 무결성 검증을 실행하지 못했습니다.", "");
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_ERROR,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 운영 중 관리서버에서 무결성 검증을 실행하지 못했습니다.", new Long(0), null, 0);
            }
        }else {
            if(type.equals("Execution")){
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 실행 시 관리 서버에서 무결성 검증을 성공적으로 완료했습니다.", new Long(0), null, 0);
            }else if(type.equals("Routine")){
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 작동 시 관리 서버에서 무결성 검증 일정을 성공적으로 완료 수행", new Long(0), null, 0);
            }else if(type.equals("Manual")){
                ActionEventUtils.onCompletedActionEvent(CallContext.current().getCallingUserId(), CallContext.current().getCallingAccountId(), EventVO.LEVEL_INFO,
                        EventTypes.EVENT_INTEGRITY_VERIFICATION, "제품 작동 시 관리 서버에서 무결성 검증을 성공적으로 완료했습니다.", new Long(0), null, 0);
            }
        }
        IntegrityVerificationFinalResultVO connectivityVO = new IntegrityVerificationFinalResultVO(msHostId, verificationFinalResult, verificationFailedListToString, type);
        connectivityVO.setUuid(uuid);
        connectivityVO.setVerificationFinalResult(verificationFinalResult);
        connectivityVO.setVerificationFailedList(verificationFailedListToString);
        connectivityVO.setVerificationDate(new Date());
        integrityVerificationFinalResultDao.persist(connectivityVO);
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(RunIntegrityVerificationCmd.class);
        cmdList.add(GetIntegrityVerificationCmd.class);
        cmdList.add(GetIntegrityVerificationFinalResultCmd.class);
        cmdList.add(DeleteIntegrityVerificationFinalResultCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return IntegrityVerificationServiceImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                IntegrityVerificationInterval
        };
    }
}
