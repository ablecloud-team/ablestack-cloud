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
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.security.dao.IntegrityVerificationDao;
import com.cloud.security.dao.IntegrityVerificationFinalResultDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
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
import org.apache.log4j.Logger;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.DigestInputStream;
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

    private static final Logger LOGGER = Logger.getLogger(IntegrityVerificationServiceImpl.class);

    private static final ConfigKey<Integer> IntegrityVerificationInterval = new ConfigKey<>("Advanced", Integer.class,
            "integrity.verification.interval", "0",
            "The interval integrity verification background tasks in seconds", false);

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
        if(IntegrityVerificationInterval.value() != 0) {
            executor.scheduleAtFixedRate(new IntegrityVerificationTask(), 0, IntegrityVerificationInterval.value(), TimeUnit.SECONDS);
        }
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
                    "running periodic integrity verification on management server", new Long(0), null, true, 0);
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            List<String> verificationFailedList = new ArrayList<>();
            List<Boolean> verificationResults = new ArrayList<>();
            boolean verificationResult;
            boolean verificationFinalResult;
            String comparisonHashValue;
            String uuid;
            String type = "Routine";
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
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }
            uuid = UUID.randomUUID().toString();
            verificationFinalResult = checkConditions(verificationResults);
            String verificationFailedListToString = verificationFailedList.stream().collect(Collectors.joining(", "));
            verificationFailedListToString = verificationFailedListToString.replaceFirst(", $", "");
            updateIntegrityVerificationFinalResult(msHost.getId(), uuid, verificationFinalResult, verificationFailedListToString, type);
        }

        private String calculateHash(File file, String algorithm) throws NoSuchAlgorithmException {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (DigestInputStream dis = new DigestInputStream(new FileInputStream(file), md)) {
                // Read the file to update the digest
                while (dis.read() != -1) ;
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            byte[] hashBytes = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte hashByte : hashBytes) {
                String hex = Integer.toHexString(0xFF & hashByte);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
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

    private String calculateHash(File file, String algorithm) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (DigestInputStream dis = new DigestInputStream(new FileInputStream(file), md)) {
            // Read the file to update the digest
            while (dis.read() != -1) ;
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] hashBytes = md.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte hashByte : hashBytes) {
            String hex = Integer.toHexString(0xFF & hashByte);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
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
    @ActionEvent(eventType = EventTypes.EVENT_INTEGRITY_VERIFICATION, eventDescription = "running manual integrity verification on management server", async = true)
    public boolean runIntegrityVerificationCommand(final RunIntegrityVerificationCmd cmd) throws NoSuchAlgorithmException {
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
            }
        }
        uuid = UUID.randomUUID().toString();
        verificationFinalResult = checkConditions(verificationResults);
        String verificationFailedListToString = verificationFailedList.stream().collect(Collectors.joining(", "));
        verificationFailedListToString = verificationFailedListToString.replaceFirst(", $", "");
        updateIntegrityVerificationFinalResult(msHost.getId(), uuid, verificationFinalResult, verificationFailedListToString, type);
        return verificationFinalResult;
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
            throw new InvalidParameterValueException("Invalid integrity verification final result id specified");
        }
        return integrityVerificationFinalResultDao.remove(result.getId());
    }

    private void updateIntegrityVerificationFinalResult(final long msHostId, String uuid, boolean verificationFinalResult, String verificationFailedListToString, String type) {
        if (verificationFinalResult == false) {
            alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_MANAGMENT_NODE, 0, new Long(0), " integrity verification failed.(uuid:"+uuid+") could not be verified. at last verification.", "");
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
