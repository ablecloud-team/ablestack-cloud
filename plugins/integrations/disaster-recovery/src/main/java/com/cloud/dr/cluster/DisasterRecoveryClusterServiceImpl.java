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
package com.cloud.dr.cluster;

import java.util.EnumSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.HashMap;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.File;

import javax.inject.Inject;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDetailsDao;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterVmMapDao;
import com.cloud.event.ActionEvent;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.UserAccount;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.script.Script;
import com.cloud.utils.server.ServerProperties;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.admin.dr.GetDisasterRecoveryClusterListCmd;
import org.apache.cloudstack.api.command.admin.dr.UpdateDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ScvmIpAddressResponse;
import org.apache.cloudstack.api.command.admin.dr.ConnectivityTestsDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.CreateDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.CreateDisasterRecoveryClusterVmCmd;
import org.apache.cloudstack.api.command.admin.dr.DeleteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DisableDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.EnableDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.PromoteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.dr.DemoteDisasterRecoveryClusterCmd;
import org.apache.cloudstack.api.command.admin.glue.ListScvmIpAddressCmd;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterListResponse;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class DisasterRecoveryClusterServiceImpl extends ManagerBase implements DisasterRecoveryClusterService {

    @Inject
    private ManagementServerHostDao msHostDao;
    @Inject
    private DisasterRecoveryClusterDao disasterRecoveryClusterDao;
    @Inject
    private DisasterRecoveryClusterVmMapDao disasterRecoveryClusterVmMapDao;
    @Inject
    protected UserVmJoinDao userVmJoinDao;
    @Inject
    private DisasterRecoveryClusterDetailsDao disasterRecoveryClusterDetailsDao;
    @Inject
    protected AccountService accountService;
    protected static Logger LOGGER = LogManager.getLogger(DisasterRecoveryClusterServiceImpl.class);

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_TEST_CONNECT, eventDescription = "disaster recovery cluster connection testing", resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean connectivityTestsDisasterRecovery(final ConnectivityTestsDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        String url = cmd.getDrClusterUrl();
        String apiKey = cmd.getDrClusterApiKey();
        String secretKey = cmd.getDrClusterSecretKey();
        String moldUrl = url + "/client/api/";
        String moldCommand = "listScvmIpAddress";
        String moldMethod = "GET";
        // Secondary Cluster - moldListScvmIpAddressAPI 호출
        String response = DisasterRecoveryClusterUtil.moldListScvmIpAddressAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
        if (response != null) {
            // Secondary Cluster - glueStatusAPI 호출
            String[] array = response.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/glue";
                String glueMethod = "GET";
                String glueStatus = DisasterRecoveryClusterUtil.glueStatusAPI(glueUrl, glueCommand, glueMethod);
                if (glueStatus != null) {
                    // ******** glue 상태에 따라 오픈 여부 설정 필요
                    if (glueStatus.contains("HEALTH_OK") || glueStatus.contains("HEALTH_WARN") ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public ListResponse<ScvmIpAddressResponse> listScvmIpAddressResponse(ListScvmIpAddressCmd cmd) {
        List<ScvmIpAddressResponse> responses = new ArrayList<>();
        ScvmIpAddressResponse response = new ScvmIpAddressResponse();
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
        }
        response.setObjectName("scvmipaddress");
        response.setIpAddress(ipList);
        responses.add(response);
        ListResponse<ScvmIpAddressResponse> listResponse = new ListResponse<>();
        listResponse.setResponses(responses);
        return listResponse;
    }

    @Override
    public ListResponse<GetDisasterRecoveryClusterListResponse> listDisasterRecoveryClusterResponse(GetDisasterRecoveryClusterListCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        String drClusterType = cmd.getDrClusterType();
        List<GetDisasterRecoveryClusterListResponse> responsesList = new ArrayList<>();
        Filter searchFilter = new Filter(DisasterRecoveryClusterVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<DisasterRecoveryClusterVO> sb = this.disasterRecoveryClusterDao.createSearchBuilder();

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("drClusterType", sb.entity().getDrClusterType(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        SearchCriteria<DisasterRecoveryClusterVO> sc = sb.create();
        String keyword = cmd.getKeyword();
        if (keyword != null){
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (name != null) {
            sc.setParameters("name", name);
        }
        if (drClusterType != null) {
            sc.setParameters("drClusterType", drClusterType);
        }
        if(keyword != null){
            sc.addOr("id", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("keyword", "%" + keyword + "%");
        }
        List <DisasterRecoveryClusterVO> results = disasterRecoveryClusterDao.search(sc, searchFilter);
        for (DisasterRecoveryClusterVO result : results) {
            GetDisasterRecoveryClusterListResponse disasterRecoveryClusterResponse = setDisasterRecoveryClusterListResultResponse(result.getId());
            responsesList.add(disasterRecoveryClusterResponse);
        }
        ListResponse<GetDisasterRecoveryClusterListResponse> response = new ListResponse<>();
        response.setResponses(responsesList);
        return response;
    }

    public GetDisasterRecoveryClusterListResponse setDisasterRecoveryClusterListResultResponse(long clusterId) {
        DisasterRecoveryClusterVO drcluster = disasterRecoveryClusterDao.findById(clusterId);
        GetDisasterRecoveryClusterListResponse response = new GetDisasterRecoveryClusterListResponse();
        response.setObjectName("disasterrecoverycluster");
        response.setId(drcluster.getUuid());
        response.setName(drcluster.getName());
        response.setDescription(drcluster.getDescription());
        response.setDrClusterUrl(drcluster.getDrClusterUrl());
        response.setDrClusterType(drcluster.getDrClusterType());
        response.setDrClusterStatus(drcluster.getDrClusterStatus());
        response.setDrClusterGlueIpAddress(drcluster.getDrClusterGlueIpAddress());
        response.setCreated(drcluster.getCreated());
        // Primary Cluster - scvm ip 조회
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
            // Primary Cluster - glueMirrorStatusAPI 호출
            String[] array = ipList.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror";
                String glueMethod = "GET";
                String daemonHealth = DisasterRecoveryClusterUtil.glueMirrorStatusAPI(glueUrl, glueCommand, glueMethod);
                if (daemonHealth != null) {
                    // glueMirrorStatusAPI 성공
                    if (daemonHealth.contains("OK")) {
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Enabled.toString());
                        break;
                    } else if (daemonHealth.contains("WARNING")){
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Warning.toString());
                        break;
                    } else if (daemonHealth.contains("DISABLED")){
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Disabled.toString());
                        break;
                    } else {
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                        break;
                    }
                } else {
                    // glueMirrorStatusAPI 실패
                    drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Unknown.toString());
                }
            }
        } else {
            // Primary Cluster - scvm ip 조회 실패
            drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Unknown.toString());
        }
        disasterRecoveryClusterDao.update(drcluster.getId(), drcluster);
        response.setMirroringAgentStatus(drcluster.getMirroringAgentStatus());
        List<UserVmResponse> disasterRecoveryClusterVmResponses = new ArrayList<UserVmResponse>();
        List<DisasterRecoveryClusterVmMapVO> drClusterVmList = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(drcluster.getId());
        ResponseObject.ResponseView respView = ResponseObject.ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseObject.ResponseView.Full;
        }
        String responseName = "drclustervmlist";
        if (drClusterVmList != null && !drClusterVmList.isEmpty()) {
            for (DisasterRecoveryClusterVmMapVO vmMapVO : drClusterVmList) {
                UserVmJoinVO userVM = userVmJoinDao.findById(vmMapVO.getVmId());
                if (userVM != null) {
                    UserVmResponse cvmResponse = ApiDBUtils.newUserVmResponse(respView, responseName, userVM, EnumSet.of(ApiConstants.VMDetails.nics), caller);
                    disasterRecoveryClusterVmResponses.add(cvmResponse);
                }
            }
        }
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(clusterId);
        String secApiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secSecretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        if (details != null && !details.isEmpty()) {
            response.setDetails(details);
        }
        response.setDisasterRecoveryClusterVms(disasterRecoveryClusterVmResponses);
        String moldUrl = drcluster.getDrClusterUrl() + "/client/api/";
        String moldMethod = "GET";
        // Secondary Cluster - listServiceOfferings 호출
        String moldCommandListServiceOfferings = "listServiceOfferings";
        List<ServiceOfferingResponse> secDrClusterServiceOfferingListResponse = DisasterRecoveryClusterUtil.getSecDrClusterInfoList(moldUrl, moldCommandListServiceOfferings, moldMethod, secApiKey, secSecretKey);
        response.setSecDisasterRecoveryClusterServiceOfferingList(secDrClusterServiceOfferingListResponse);
        // Secondary Cluster - listNetworks 호출
        String moldCommandListNetworks = "listNetworks";
        List<NetworkResponse> secDrClusterNetworksListResponse = DisasterRecoveryClusterUtil.getSecDrClusterInfoList(moldUrl, moldCommandListNetworks, moldMethod, secApiKey, secSecretKey);
        response.setSecDisasterRecoveryClusterNetworkList(secDrClusterNetworksListResponse);
        return response;
    }

    public GetDisasterRecoveryClusterListResponse setDisasterRecoveryClusterResponse(long clusterId) {
        DisasterRecoveryClusterVO drcluster = disasterRecoveryClusterDao.findById(clusterId);
        GetDisasterRecoveryClusterListResponse response = new GetDisasterRecoveryClusterListResponse();
        response.setObjectName("disasterrecoverycluster");
        response.setId(drcluster.getUuid());
        response.setName(drcluster.getName());
        response.setDescription(drcluster.getDescription());
        response.setDrClusterUrl(drcluster.getDrClusterUrl());
        response.setDrClusterType(drcluster.getDrClusterType());
        response.setDrClusterStatus(drcluster.getDrClusterStatus());
        response.setDrClusterGlueIpAddress(drcluster.getDrClusterGlueIpAddress());
        response.setCreated(drcluster.getCreated());
        // Primary Cluster - scvm ip 조회
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
            // Primary Cluster - glueMirrorStatusAPI 호출
            String[] array = ipList.split(",");
            String daemonHealth = null;
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror";
                String glueMethod = "GET";
                daemonHealth = DisasterRecoveryClusterUtil.glueMirrorStatusAPI(glueUrl, glueCommand, glueMethod);
                // glueMirrorStatusAPI 성공
                if (daemonHealth != null) {
                    if (daemonHealth.contains("OK")) {
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Enabled.toString());
                        break;
                    } else if (daemonHealth.contains("WARNING")){
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Warning.toString());
                        break;
                    } else if (daemonHealth.contains("DISABLED")){
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Disabled.toString());
                        break;
                    } else {
                        drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                        break;
                    }
                }
            }
            if (daemonHealth == null) {
                // glueMirrorStatusAPI 실패
                drcluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Unknown.toString());
            }
        }
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drcluster.getId());
        if (details != null && !details.isEmpty()) {
            response.setDetails(details);
        }
        disasterRecoveryClusterDao.update(drcluster.getId(), drcluster);
        return response;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_UPDATE, eventDescription = "updating dr cluster", resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public GetDisasterRecoveryClusterListResponse updateDisasterRecoveryCluster(UpdateDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery plugin is disabled");
        }
        DisasterRecoveryClusterVO drcluster = null;
        Long drClusterId = cmd.getId();
        String drClusterName = cmd.getName();
        if (drClusterId == null) {
            // Primary Cluster 에서 request MoldAPI로 요청한 경우
            drcluster = disasterRecoveryClusterDao.findByName(drClusterName);
        } else {
            drcluster = disasterRecoveryClusterDao.findById(drClusterId);
            if (drcluster == null) {
                throw new InvalidParameterValueException("Invalid Disaster Recovery id specified");
            }
        }
        drcluster = disasterRecoveryClusterDao.createForUpdate(drcluster.getId());
        if (cmd.getDescription() != null) {
            String drClusterDescription = cmd.getDescription();
            drcluster.setDescription(drClusterDescription);
        }
        if (cmd.getDrClusterUrl() != null) {
            String drClusterUrl = cmd.getDrClusterUrl();
            drcluster.setDrClusterUrl(drClusterUrl);
        }
        if (cmd.getDrClusterGlueIpAddress() != null) {
            String drClusterGlueIpAddress = cmd.getDrClusterGlueIpAddress();
            drcluster.setDrClusterGlueIpAddress(drClusterGlueIpAddress);
        }
        if (cmd.getDetails() != null) {
            Map<String,String> details = cmd.getDetails();
            drcluster.setDetails(details);
            disasterRecoveryClusterDetailsDao.persist(drcluster.getId(), details);
            List<DisasterRecoveryClusterVmMapVO> drClusterVmList = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(drcluster.getId());
            if (drClusterVmList != null && !drClusterVmList.isEmpty()) {
                String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
                ipList = ipList.replaceAll(",$", "");
                String[] array = ipList.split(",");
                for (DisasterRecoveryClusterVmMapVO vmMapVO : drClusterVmList) {
                    UserVmJoinVO userVM = userVmJoinDao.findById(vmMapVO.getVmId());
                    if (userVM != null && (ipList != null || !ipList.isEmpty())) {
                        for (int i=0; i < array.length; i++) {
                            // Secondary Cluster - glueImageMirrorSetupUpdateAPI 호출
                            String glueIp = array[i];
                            String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                            String glueCommand = "/mirror/image/{mirrorPool}/{imageName}";
                            String glueMethod = "PATCH";
                            Map<String, String> glueParams = new HashMap<>();
                            glueParams.put("mirrorPool", "rbd");
                            glueParams.put("imageName", userVM.getVolumeUuid());
                            glueParams.put("interval", details.get("mirrorscheduleinterval"));
                            glueParams.put("startTime", details.get("mirrorschedulestarttime"));
                            boolean result = DisasterRecoveryClusterUtil.glueImageMirrorSetupUpdateAPI(glueUrl, glueCommand, glueMethod, glueParams);
                            if (result) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (cmd.getDrClusterStatus() != null) {
            String drClusterStatus = cmd.getDrClusterStatus();
            drcluster.setDrClusterStatus(drClusterStatus);
        }
        if (cmd.getMirroringAgentStatus() != null) {
            String mirroringAgentStatus = cmd.getMirroringAgentStatus();
            drcluster.setMirroringAgentStatus(mirroringAgentStatus);
        }
        if (!disasterRecoveryClusterDao.update(drcluster.getId(), drcluster)) {
            throw new CloudRuntimeException(String.format("Failed to update Disaster Recovery ID: %s", drcluster.getUuid()));
        }
        drcluster = disasterRecoveryClusterDao.findById(drcluster.getId());
        return setDisasterRecoveryClusterResponse(drcluster.getId());
    }

    @Override
    public DisasterRecoveryCluster createDisasterRecoveryCluster(CreateDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        validateDisasterRecoveryClusterCreateParameters(cmd);
        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
        DisasterRecoveryClusterVO cluster = Transaction.execute(new TransactionCallback<DisasterRecoveryClusterVO>() {
            @Override
            public DisasterRecoveryClusterVO doInTransaction(TransactionStatus status) {
                DisasterRecoveryClusterVO newCluster = new DisasterRecoveryClusterVO(msHost.getId(), cmd.getName(), cmd.getDescription(),
                        cmd.getDrClusterGlueIpAddress(), cmd.getDrClusterUrl(), cmd.getDrClusterType(), DisasterRecoveryCluster.DrClusterStatus.Created.toString(), DisasterRecoveryCluster.MirroringAgentStatus.Created.toString());
                disasterRecoveryClusterDao.persist(newCluster);
                Map<String, String> drDetails = disasterRecoveryClusterDetailsDao.findDetails(newCluster.getId());
                if (cmd.getDrClusterApiKey() != null) {
                    drDetails.put(ApiConstants.DR_CLUSTER_API_KEY, cmd.getDrClusterApiKey());
                }
                if (cmd.getDrClusterSecretKey() != null) {
                    drDetails.put(ApiConstants.DR_CLUSTER_SECRET_KEY, cmd.getDrClusterSecretKey());
                }
                if (cmd.getDrClusterPrivateKey() != null) {
                    drDetails.put(ApiConstants.DR_CLUSTER_PRIVATE_KEY, cmd.getDrClusterPrivateKey());
                }
                if (cmd.getDrClusterType().equalsIgnoreCase("secondary")) {
                    drDetails.put("mirrorscheduleinterval", "24h"); // interval h,m,d format
                    drDetails.put("mirrorschedulestarttime", ""); // start-time ISO 8601 time format
                }
                disasterRecoveryClusterDetailsDao.persist(newCluster.getId(), drDetails);
                return newCluster;
            }
        });
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("Disaster recovery cluster name: %s and ID: %s has been created", cluster.getName(), cluster.getUuid()));
        }
        return cluster;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_CREATE, eventDescription = "creating disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean setupDisasterRecoveryCluster(long clusterId) throws CloudRuntimeException {
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(clusterId);
        String drName = drCluster.getName();
        String drDescription = drCluster.getDescription();
        // Secondary Cluster 정보
        String secUrl = drCluster.getDrClusterUrl();
        String secClusterType = drCluster.getDrClusterType();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String secApiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secSecretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String secPrivateKey = details.get(ApiConstants.DR_CLUSTER_PRIVATE_KEY);
        String secGlueIpAddress = drCluster.getDrClusterGlueIpAddress();
        try {
            FileOutputStream fos = new FileOutputStream("glue.key");
            fos.write(secPrivateKey.getBytes());
            fos.close();
        } catch (IOException e) {
            throw new CloudRuntimeException("Converting the secondary cluster's private key to a file failed.", e);
        }
        File permKey = new File("glue.key");
        // Primary Cluster 정보
        String[] properties = getServerProperties();
        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
        String priUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0];
        // String priUrl = "http://10.10.254.39:5050"; // VM 테스트로 임시 변수 설정
        String priClusterType = "primary";
        UserAccount user = accountService.getActiveUserAccount("admin", 1L);
        String priApiKey = user.getApiKey();
        String priSecretKey = user.getSecretKey();
        // Secondary Cluster - moldCreateDisasterRecoveryClusterAPI 호출
        Map<String, String> secParams = new HashMap<>();
        secParams.put("name", drName);
        secParams.put("description", drDescription);
        secParams.put("drclustertype", priClusterType);
        secParams.put("drclusterurl", priUrl);
        secParams.put("drclusterapikey", priApiKey);
        secParams.put("drclustersecretkey", priSecretKey);
        String secCommand = "createDisasterRecoveryCluster";
        String secMethod = "POST";
        String secResponse = DisasterRecoveryClusterUtil.moldCreateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, secParams);
        if (secResponse == null || secResponse.isEmpty()) {
            // Secondary Cluster - moldCreateDisasterRecoveryClusterAPI 실패
            // Primary Cluster DB 업데이트
            drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Error.toString());
            drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
            disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
            return false;
        } else {
            // Secondary Cluster - moldCreateDisasterRecoveryClusterAPI 성공
            // Primary Cluster - scvm ip 조회
            String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
            if (ipList != null || !ipList.isEmpty()) {
                ipList = ipList.replaceAll(",$", "");
                // Primary Cluster - glueMirrorSetupAPI 호출
                String[] array = ipList.split(",");
                for (int i=0; i < array.length; i++) {
                    String glueIp = array[i];
                    String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                    String glueCommand = "/mirror";
                    String glueMethod = "POST";
                    Map<String, String> glueParams = new HashMap<>();
                    glueParams.put("localClusterName", "local");
                    glueParams.put("remoteClusterName", "remote");
                    glueParams.put("mirrorPool", "rbd");
                    glueParams.put("host", secGlueIpAddress);
                    boolean result = DisasterRecoveryClusterUtil.glueMirrorSetupAPI(glueUrl, glueCommand, glueMethod, glueParams, permKey);
                    // glueMirrorSetupAPI 성공
                    if (result) {
                        // Primary Cluster DB 업데이트
                        drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Enabled.toString());
                        drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Enabled.toString());
                        disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                        // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                        secCommand = "updateDisasterRecoveryCluster";
                        secMethod = "GET";
                        Map<String, String> sucParams = new HashMap<>();
                        sucParams.put("name", drName);
                        sucParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Enabled.toString());
                        sucParams.put("mirroringagentstatus", DisasterRecoveryCluster.DrClusterStatus.Enabled.toString());
                        DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
                        return true;
                    }
                }
                // glueMirrorSetupAPI 실패
                // Primary Cluster DB 업데이트
                drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                secCommand = "updateDisasterRecoveryCluster";
                secMethod = "GET";
                Map<String, String> sucParams = new HashMap<>();
                sucParams.put("name", drName);
                sucParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                sucParams.put("mirroringagentstatus", DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
            } else {
                // Primary Cluster - scvm ip 조회 실패
                // Primary Cluster DB 업데이트
                drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                secCommand = "updateDisasterRecoveryCluster";
                secMethod = "GET";
                Map<String, String> errParams = new HashMap<>();
                errParams.put("name", drName);
                errParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                errParams.put("mirroringagentstatus", DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, errParams);
            }
        }
        return false;
    }

    @Override
    public GetDisasterRecoveryClusterListResponse createDisasterRecoveryClusterResponse(long clusterId) {
        DisasterRecoveryClusterVO drcluster = disasterRecoveryClusterDao.findById(clusterId);
        GetDisasterRecoveryClusterListResponse response = new GetDisasterRecoveryClusterListResponse();
        response.setObjectName("disasterrecoverycluster");
        response.setId(drcluster.getUuid());
        response.setName(drcluster.getName());
        response.setDescription(drcluster.getDescription());
        response.setDrClusterUrl(drcluster.getDrClusterUrl());
        response.setDrClusterType(drcluster.getDrClusterType());
        response.setDrClusterStatus(drcluster.getDrClusterStatus());
        response.setMirroringAgentStatus(drcluster.getMirroringAgentStatus());
        response.setDrClusterGlueIpAddress(drcluster.getDrClusterGlueIpAddress());
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drcluster.getId());
        if (details != null && !details.isEmpty()) {
            response.setDetails(details);
        }
        response.setCreated(drcluster.getCreated());
        return response;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_DELETE, eventDescription = "deleting disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean deleteDisasterRecoveryCluster(DeleteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(cmd.getId());
        if (drCluster == null) {
            throw new InvalidParameterValueException("Invalid disaster recovery cluster id specified");
        }
        // Secondary Cluster에서 요청한 경우
        if (drCluster.getDrClusterType().equalsIgnoreCase("primary")) {
            disasterRecoveryClusterDetailsDao.deleteDetails(drCluster.getId());
            return disasterRecoveryClusterDao.remove(drCluster.getId());
        } else {
            // Primary Cluster에서 요청한 경우
            String drName = drCluster.getName();
            String secUrl = drCluster.getDrClusterUrl();
            String secClusterType = drCluster.getDrClusterType();
            Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
            String secApiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
            String secSecretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
            String secPrivateKey = details.get(ApiConstants.DR_CLUSTER_PRIVATE_KEY);
            String secGlueIpAddress = drCluster.getDrClusterGlueIpAddress();
            try {
                FileOutputStream fos = new FileOutputStream("glue.key");
                fos.write(secPrivateKey.getBytes());
                fos.close();
            } catch (IOException e) {
                throw new CloudRuntimeException("Converting the secondary cluster's private key to a file failed.", e);
            }
            File permKey = new File("glue.key");
            // Primary Cluster 정보
            String[] properties = getServerProperties();
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            String priUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0];
            // String priUrl = "http://10.10.254.39:5050"; // VM 테스트로 임시 변수 설정
            String priClusterType = "primary";
            UserAccount user = accountService.getActiveUserAccount("admin", 1L);
            String priApiKey = user.getApiKey();
            String priSecretKey = user.getSecretKey();
            // Primary Cluster - scvm ip 조회
            String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
            if (ipList != null || !ipList.isEmpty()) {
                ipList = ipList.replaceAll(",$", "");
                // Primary Cluster - glueMirrorDeleteAPI 호출
                String[] array = ipList.split(",");
                for (int i=0; i < array.length; i++) {
                    String glueIp = array[i];
                    String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                    String glueCommand = "/mirror";
                    String glueMethod = "DELETE";
                    Map<String, String> glueParams = new HashMap<>();
                    glueParams.put("mirrorPool", "rbd");
                    glueParams.put("host", secGlueIpAddress);
                    boolean result = DisasterRecoveryClusterUtil.glueMirrorDeleteAPI(glueUrl, glueCommand, glueMethod, glueParams, permKey);
                    // glueMirrorDeleteAPI 성공
                    if (result) {
                        // Primary Cluster - DB 업데이트
                        disasterRecoveryClusterDetailsDao.deleteDetails(drCluster.getId());
                        disasterRecoveryClusterDao.remove(drCluster.getId());
                        // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 호출
                        String secCommand = "getDisasterRecoveryClusterList";
                        String secMethod = "GET";
                        Map<String, String> sucParams = new HashMap<>();
                        List<GetDisasterRecoveryClusterListResponse> drListResponse = DisasterRecoveryClusterUtil.moldGetDisasterRecoveryClusterListAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey);
                        if (drListResponse != null || !drListResponse.isEmpty()) {
                            // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 성공
                            for (GetDisasterRecoveryClusterListResponse dr : drListResponse) {
                                if (dr.getName().equalsIgnoreCase(drCluster.getName())) {
                                    String primaryDrId = dr.getId();
                                    // Secondary Cluster - moldDeleteDisasterRecoveryClusterAPI 호출
                                    secCommand = "deleteDisasterRecoveryCluster";
                                    secMethod = "GET";
                                    sucParams = new HashMap<>();
                                    sucParams.put("id", primaryDrId);
                                    String response = DisasterRecoveryClusterUtil.moldDeleteDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
                                    if (response != null) {
                                        // Secondary Cluster - moldDeleteDisasterRecoveryClusterAPI 성공
                                        return true;
                                    } else {
                                        // Secondary Cluster - moldDeleteDisasterRecoveryClusterAPI 실패
                                        return false;
                                    }
                                }
                            }
                        } else {
                            // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 실패
                            return false;
                        }
                    }
                }
                // ******** glue-api 에서 삭제 도중 에러난 경우 가비지 어떻게 처리할 것인지
                // glueMirrorDeleteAPI 실패
                // // Primary Cluster - DB 업데이트
                // drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                // drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Error.toString());
                // disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                // // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                // String secCommand = "updateDisasterRecoveryCluster";
                // String secMethod = "GET";
                // Map<String, String> sucParams = new HashMap<>();
                // sucParams.put("name", drName);
                // sucParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                // sucParams.put("mirroringagentstatus", DisasterRecoveryCluster.DrClusterStatus.Error.toString());
                // DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
            } else {
                // Primary Cluster - scvm ip 조회 실패
                throw new CloudRuntimeException("primary cluster scvm list lookup fails.");
            }
            return false;
        }
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_ENABLE, eventDescription = "enabling disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean enableDisasterRecoveryCluster(EnableDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(cmd.getId());
        if (drCluster == null) {
            throw new InvalidParameterValueException("Invalid disaster recovery cluster id specified");
        }
        String drName = drCluster.getName();
        String secUrl = drCluster.getDrClusterUrl();
        String secClusterType = drCluster.getDrClusterType();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String secApiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secSecretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String secPrivateKey = details.get(ApiConstants.DR_CLUSTER_PRIVATE_KEY);
        String secGlueIpAddress = drCluster.getDrClusterGlueIpAddress();
        try {
            FileOutputStream fos = new FileOutputStream("glue.key");
            fos.write(secPrivateKey.getBytes());
            fos.close();
        } catch (IOException e) {
            throw new CloudRuntimeException("Converting the secondary cluster's private key to a file failed.", e);
        }
        File permKey = new File("glue.key");
        // Primary Cluster 정보
        String[] properties = getServerProperties();
        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
        String priUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0];
        // String priUrl = "http://10.10.254.39:5050"; // VM 테스트로 임시 변수 설정
        String priClusterType = "primary";
        UserAccount user = accountService.getActiveUserAccount("admin", 1L);
        String priApiKey = user.getApiKey();
        String priSecretKey = user.getSecretKey();
        // Primary Cluster - scvm ip 조회
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
            // Primary Cluster - glueMirrorEnableAPI 호출
            String[] array = ipList.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror/{mirrorPool}";
                String glueMethod = "POST";
                Map<String, String> glueParams = new HashMap<>();
                glueParams.put("localClusterName", "local");
                glueParams.put("remoteClusterName", "remote");
                glueParams.put("mirrorPool", "rbd");
                glueParams.put("host", secGlueIpAddress);
                boolean result = DisasterRecoveryClusterUtil.glueMirrorEnableAPI(glueUrl, glueCommand, glueMethod, glueParams, permKey);
                // glueMirrorEnableAPI 성공
                if (result) {
                    // Primary Cluster - DB 업데이트
                    drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Enabled.toString());
                    drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Enabled.toString());
                    disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                    // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 호출
                    String secCommand = "getDisasterRecoveryClusterList";
                    String secMethod = "GET";
                    Map<String, String> sucParams = new HashMap<>();
                    List<GetDisasterRecoveryClusterListResponse> drListResponse = DisasterRecoveryClusterUtil.moldGetDisasterRecoveryClusterListAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey);
                    if (drListResponse != null || !drListResponse.isEmpty()) {
                        // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 성공
                        for (GetDisasterRecoveryClusterListResponse dr : drListResponse) {
                            if (dr.getName().equalsIgnoreCase(drCluster.getName())) {
                                String primaryDrId = dr.getId();
                                // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                                secCommand = "updateDisasterRecoveryCluster";
                                secMethod = "GET";
                                sucParams.put("name", drName);
                                sucParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Enabled.toString());
                                sucParams.put("mirroringagentstatus", DisasterRecoveryCluster.MirroringAgentStatus.Enabled.toString());
                                DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
                                return true;
                            }
                        }
                    } else {
                        // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 실패
                        return false;
                    }
                }
            }
        } else {
            // Primary Cluster - scvm ip 조회 실패
            throw new CloudRuntimeException("primary cluster scvm list lookup fails.");
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_DISABLE, eventDescription = "disabling disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean disableDisasterRecoveryCluster(DisableDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(cmd.getId());
        if (drCluster == null) {
            throw new InvalidParameterValueException("Invalid disaster recovery cluster id specified");
        }
        String drName = drCluster.getName();
        String secUrl = drCluster.getDrClusterUrl();
        String secClusterType = drCluster.getDrClusterType();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String secApiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secSecretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String secPrivateKey = details.get(ApiConstants.DR_CLUSTER_PRIVATE_KEY);
        String secGlueIpAddress = drCluster.getDrClusterGlueIpAddress();
        try {
            FileOutputStream fos = new FileOutputStream("glue.key");
            fos.write(secPrivateKey.getBytes());
            fos.close();
        } catch (IOException e) {
            throw new CloudRuntimeException("Converting the secondary cluster's private key to a file failed.", e);
        }
        File permKey = new File("glue.key");
        // Primary Cluster - scvm ip 조회
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
            // Primary Cluster - glueMirrorDisableAPI 호출
            String[] array = ipList.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror/{mirrorPool}";
                String glueMethod = "DELETE";
                Map<String, String> glueParams = new HashMap<>();
                glueParams.put("mirrorPool", "rbd");
                glueParams.put("host", secGlueIpAddress);
                boolean result = DisasterRecoveryClusterUtil.glueMirrorDisableAPI(glueUrl, glueCommand, glueMethod, glueParams, permKey);
                // glueMirrorDisableAPI 성공
                if (result) {
                    // Primary Cluster - DB 업데이트
                    drCluster.setDrClusterStatus(DisasterRecoveryCluster.DrClusterStatus.Disabled.toString());
                    drCluster.setMirroringAgentStatus(DisasterRecoveryCluster.MirroringAgentStatus.Disabled.toString());
                    disasterRecoveryClusterDao.update(drCluster.getId(), drCluster);
                    // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 호출
                    String secCommand = "getDisasterRecoveryClusterList";
                    String secMethod = "GET";
                    Map<String, String> sucParams = new HashMap<>();
                    List<GetDisasterRecoveryClusterListResponse> drListResponse = DisasterRecoveryClusterUtil.moldGetDisasterRecoveryClusterListAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey);
                    if (drListResponse != null || !drListResponse.isEmpty()) {
                        // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 성공
                        for (GetDisasterRecoveryClusterListResponse dr : drListResponse) {
                            if (dr.getName().equalsIgnoreCase(drCluster.getName())) {
                                String primaryDrId = dr.getId();
                                // Secondary Cluster - moldUpdateDisasterRecoveryClusterAPI 호출
                                secCommand = "updateDisasterRecoveryCluster";
                                secMethod = "GET";
                                sucParams.put("name", drName);
                                sucParams.put("drclusterstatus", DisasterRecoveryCluster.DrClusterStatus.Disabled.toString());
                                sucParams.put("mirroringagentstatus", DisasterRecoveryCluster.MirroringAgentStatus.Disabled.toString());
                                DisasterRecoveryClusterUtil.moldUpdateDisasterRecoveryClusterAPI(secUrl + "/client/api/", secCommand, secMethod, secApiKey, secSecretKey, sucParams);
                                return true;
                            }
                        }
                    } else {
                        // Secondary Cluster - moldGetDisasterRecoveryClusterListAPI 실패
                        return false;
                    }
                }
            }
        } else {
            // Primary Cluster - scvm ip 조회 실패
            throw new CloudRuntimeException("primary cluster scvm list lookup fails.");
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_PROMOTE, eventDescription = "promoting disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean promoteDisasterRecoveryCluster(PromoteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(cmd.getId());
        if (drCluster == null) {
            throw new InvalidParameterValueException("Invalid disaster recovery cluster id specified");
        }
        validateDisasterRecoveryClusterMirrorParameters(drCluster);

        String url = drCluster.getDrClusterUrl();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String apiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String moldUrl = url + "/client/api/";
        String moldCommand = "listScvmIpAddress";
        String moldMethod = "GET";
        // Primary Cluster - moldListScvmIpAddressAPI 호출
        String response = DisasterRecoveryClusterUtil.moldListScvmIpAddressAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
        if (response != null) {
            // Primary Cluster - glueImageMirrorAPI 호출
            String[] array = response.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror/image";
                String glueMethod = "GET";
                String mirrorList = DisasterRecoveryClusterUtil.glueImageMirrorAPI(glueUrl, glueCommand, glueMethod);
                if (mirrorList != null) {
                    JsonArray drArray = (JsonArray) new JsonParser().parse(mirrorList).getAsJsonObject().get("Local");
                    if (drArray.size() != 0) {
                        for (JsonElement dr : drArray) {
                            JsonElement imageName = dr.getAsJsonObject().get("image") == null ? null : dr.getAsJsonObject().get("image");
                            if (imageName != null) {
                                // Primary Cluster - glueImageMirrorPromoteAPI 호출
                                glueCommand = "/mirror/image/promote/rbd/"+imageName.getAsString();
                                glueMethod = "POST";
                                boolean result = DisasterRecoveryClusterUtil.glueImageMirrorPromoteAPI(glueUrl, glueCommand, glueMethod);
                                // glueImageMirrorPromoteAPI 성공
                                if (!result) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    } else {
                        throw new CloudRuntimeException("There are no images being mirrored.");
                    }
                }
            }
        } else {
            throw new CloudRuntimeException("secondary cluster scvm list lookup fails.");
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_DEMOTE, eventDescription = "demoting disaster recovery cluster", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean demoteDisasterRecoveryCluster(DemoteDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findById(cmd.getId());
        if (drCluster == null) {
            throw new InvalidParameterValueException("Invalid disaster recovery cluster id specified");
        }
        validateDisasterRecoveryClusterMirrorParameters(drCluster);
        String url = drCluster.getDrClusterUrl();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String apiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String moldUrl = url + "/client/api/";
        String moldCommand = "listScvmIpAddress";
        String moldMethod = "GET";
        // Primary Cluster - moldListScvmIpAddressAPI 호출
        String response = DisasterRecoveryClusterUtil.moldListScvmIpAddressAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
        if (response != null) {
            // Primary Cluster - glueImageMirrorAPI 호출
            String[] array = response.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror/image";
                String glueMethod = "GET";
                String mirrorList = DisasterRecoveryClusterUtil.glueImageMirrorAPI(glueUrl, glueCommand, glueMethod);
                if (mirrorList != null) {
                    JsonArray drArray = (JsonArray) new JsonParser().parse(mirrorList).getAsJsonObject().get("Local");
                    if (drArray.size() != 0) {
                        for (JsonElement dr : drArray) {
                            JsonElement imageName = dr.getAsJsonObject().get("image") == null ? null : dr.getAsJsonObject().get("image");
                            if (imageName != null) {
                                // Primary Cluster - glueImageMirrorDemoteAPI 호출
                                glueCommand = "/mirror/image/demote/rbd/"+imageName.getAsString();
                                glueMethod = "DELETE";
                                boolean result = DisasterRecoveryClusterUtil.glueImageMirrorDemoteAPI(glueUrl, glueCommand, glueMethod);
                                // glueImageMirrorDemoteAPI 성공
                                if (!result) {
                                    return false;
                                }
                            }
                        }
                        return true;
                    } else {
                        throw new CloudRuntimeException("There are no images being mirrored.");
                    }
                }
            }
        } else {
            throw new CloudRuntimeException("secondary cluster scvm list lookup fails.");
        }
        return false;
    }

    @Override
    @ActionEvent(eventType = DisasterRecoveryClusterEventTypes.EVENT_DR_VM_CREATE, eventDescription = "creating disaster recovery virtual machine", async = true, resourceId = 5, resourceType = "DisasterRecoveryCluster")
    public boolean setupDisasterRecoveryClusterVm(CreateDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException {
        if (!DisasterRecoveryServiceEnabled.value()) {
            throw new CloudRuntimeException("Disaster Recovery Service plugin is disabled");
        }
        validateDisasterRecoveryClusterVmCreateParameters(cmd);
        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findByName(cmd.getDrClusterName());
        UserVmJoinVO userVM = userVmJoinDao.findById(cmd.getVmId());
        String volumeUuid = userVM.getVolumeUuid();
        String url = drCluster.getDrClusterUrl();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String apiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String interval = details.get("mirrorscheduleinterval");
        String startTime = details.get("mirrorschedulestarttime");
        String offeringId = "";
        String networkId = "";
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm1-mngt|scvm2-mngt|scvm3-mngt' | awk '{print $1}' | tr '\n' ','");
        if (ipList != null || !ipList.isEmpty()) {
            ipList = ipList.replaceAll(",$", "");
            // Secondary Cluster - glueImageMirrorSetupAPI 호출
            String[] array = ipList.split(",");
            for (int i=0; i < array.length; i++) {
                String glueIp = array[i];
                String glueUrl = "https://" + glueIp + ":8080/api/v1"; // glue-api 프로토콜과 포트 확정 시 변경 예정
                String glueCommand = "/mirror/image/rbd/" + volumeUuid;
                String glueMethod = "POST";
                Map<String, String> glueParams = new HashMap<>();
                glueParams.put("mirrorPool", "rbd");
                glueParams.put("imageName", volumeUuid);
                glueParams.put("interval", interval);
                glueParams.put("startTime", startTime);
                LOGGER.info(glueParams);
                boolean result = DisasterRecoveryClusterUtil.glueImageMirrorSetupAPI(glueUrl, glueCommand, glueMethod, glueParams);
                // glueImageMirrorSetupAPI 성공
                if (result) {
                    DisasterRecoveryClusterVmMapVO newClusterVmMapVO = new DisasterRecoveryClusterVmMapVO(drCluster.getId(), cmd.getVmId());
                    disasterRecoveryClusterVmMapDao.persist(newClusterVmMapVO);
                    String moldUrl = url + "/client/api/";
                    String moldMethod = "GET";
                    String moldCommand = "listServiceOfferings";
                    // Secondary Cluster - listServiceOfferings 호출
                    List<ServiceOfferingResponse> secDrClusterServiceOfferingListResponse = DisasterRecoveryClusterUtil.getSecDrClusterInfoList(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
                    for (ServiceOfferingResponse serviceOff : secDrClusterServiceOfferingListResponse) {
                        if (serviceOff.getName() == cmd.getServiceOfferingName()) {
                            offeringId = serviceOff.getId();
                        }
                    }
                    // Secondary Cluster - listNetworks 호출
                    moldCommand = "listNetworks";
                    List<NetworkResponse> secDrClusterNetworksListResponse = DisasterRecoveryClusterUtil.getSecDrClusterInfoList(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
                    for (NetworkResponse net : secDrClusterNetworksListResponse) {
                        if (net.getName() == cmd.getNetworkName()) {
                            networkId = net.getId();
                        }
                    }
                    // Secondary Cluster - listStoragePoolsMetrics 호출
                    moldCommand = "listStoragePoolsMetrics";
                    String psInfo = DisasterRecoveryClusterUtil.moldListStoragePoolsMetricsAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
                    String zoneId = new JsonParser().parse(psInfo).getAsJsonObject().get("zoneid").getAsString();
                    String poolId = new JsonParser().parse(psInfo).getAsJsonObject().get("id").getAsString();
                    // Secondary Cluster - listStoragePoolObjects 호출
                    moldCommand = "listStoragePoolObjects";
                    Map<String, String> poolParams = new HashMap<>();
                    poolParams.put("id", poolId);
                    LOGGER.info(poolParams);
                    String volumeInfo = DisasterRecoveryClusterUtil.moldListStoragePoolObjectsAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, poolParams, volumeUuid);
                    String size = new JsonParser().parse(volumeInfo).getAsJsonObject().get("size").getAsString();
                    String name = new JsonParser().parse(volumeInfo).getAsJsonObject().get("name").getAsString();
                    LOGGER.info(volumeInfo);
                    // Secondary Cluster - listDiskOfferings 호출
                    moldCommand = "listDiskOfferings";
                    String diskOfferingId = DisasterRecoveryClusterUtil.moldListDiskOfferingsAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
                    // Secondary Cluster - createVolume 호출 (비동기)
                    moldMethod = "POST";
                    Map<String, String> volParams = new HashMap<>();
                    volParams.put("diskofferingid", diskOfferingId);
                    volParams.put("size", size);
                    volParams.put("name", name);
                    volParams.put("zoneid", zoneId);
                    LOGGER.info(volParams);
                    String volumeId = DisasterRecoveryClusterUtil.moldCreateVolumeAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, volParams);
                    moldCommand = "deployVirtualMachineForVolume";
                    Map<String, String> vmParams = new HashMap<>();
                    vmParams.put("volumeid", ""); // volumeId
                    vmParams.put("zoneid", zoneId); // psInfo의 zoneid
                    vmParams.put("serviceofferingid", offeringId);
                    vmParams.put("rootdisksize", size); // volumeInfo의 size
                    vmParams.put("name", "");
                    vmParams.put("displayname", "");
                    vmParams.put("account", "admin");
                    vmParams.put("domainid", "");
                    vmParams.put("iptonetworklist[0].networkid", networkId);
                    // vmParams.put("details[0].cpuNumber", "");
                    // vmParams.put("details[0].memory", "");
                    vmParams.put("startvm", "false");
                    vmParams.put("hypervisor", "KVM");
                    vmParams.put("keypairs", "");
                    vmParams.put("boottype", "BIOS");
                    vmParams.put("affinitygroupids", "");
                    vmParams.put("bootmode", "LEGACY");
                    vmParams.put("tpmversion", "NONE");
                    vmParams.put("dynamicscalingenabled", "true");
                    vmParams.put("iothreadsenabled", "true");
                    vmParams.put("iodriverpolicy", "io_uring");
                    LOGGER.info(vmParams);
                    // Secondary Cluster - deployVirtualMachineForVolume 호출 (비동기)
                    String response = DisasterRecoveryClusterUtil.moldDeployVirtualMachineForVolumeAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, vmParams);
                    return true;
                }
            }
        } else {
            throw new CloudRuntimeException("secondary cluster scvm list lookup fails.");
        }
        return false;
    }

    private void validateDisasterRecoveryClusterVmCreateParameters(final CreateDisasterRecoveryClusterVmCmd cmd) throws CloudRuntimeException {
        final Long vmId = cmd.getVmId();
        final String drClusterName = cmd.getDrClusterName();
        final String networkName = cmd.getNetworkName();
        final String serOfferingName = cmd.getServiceOfferingName();

        if (vmId == null) {
            throw new InvalidParameterValueException("Invalid id for the virtual machine id:" + vmId);
        }
        if (drClusterName == null) {
            throw new InvalidParameterValueException("Invalid name for the disaster recovery cluster name:" + drClusterName);
        }
        if (networkName == null) {
            throw new InvalidParameterValueException("Invalid name for the disaster recovery cluster vm network name:" + networkName);
        }
        if (serOfferingName == null) {
            throw new InvalidParameterValueException("Invalid name for the disaster recovery cluster vm service offering name:" + serOfferingName);
        }

        DisasterRecoveryClusterVO drCluster = disasterRecoveryClusterDao.findByName(drClusterName);

        List<DisasterRecoveryClusterVmMapVO> drVm = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterVmId(drCluster.getId(), vmId);
        if (!drVm.isEmpty()) {
            throw new InvalidParameterValueException("A disaster recovery cluster with the same virtual machine id exists:" + vmId);
        }

        String url = drCluster.getDrClusterUrl();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String apiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        String moldCommand = "listDiskOfferings";
        String moldUrl = url + "/client/api/";
        String moldMethod = "GET";
        String diskOffering = DisasterRecoveryClusterUtil.moldListDiskOfferingsAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey);
        if (diskOffering == "" || diskOffering.isEmpty()) {
            throw new CloudRuntimeException("A mirroring virtual machine cannot be added because a disk offering with a custom disk size does not exist.");
        }
    }

    private void validateDisasterRecoveryClusterCreateParameters(final CreateDisasterRecoveryClusterCmd cmd) throws CloudRuntimeException {
        final String name = cmd.getName();
        final String type = cmd.getDrClusterType();
        final String url = cmd.getDrClusterUrl();
        final String apiKey = cmd.getDrClusterApiKey();
        final String secretKey = cmd.getDrClusterSecretKey();
        final String privateKey = cmd.getDrClusterPrivateKey();
        final String glueIp = cmd.getDrClusterGlueIpAddress();

        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Invalid name for the disaster recovery cluster name:" + name);
        }
        if (type.equalsIgnoreCase("secondary") && (privateKey == null || privateKey.isEmpty())) {
            throw new InvalidParameterValueException("Invalid private key for the disaster recovery cluster private key:" + privateKey);
        }
        if (type.equalsIgnoreCase("secondary") && (glueIp == null || glueIp.isEmpty())) {
            throw new InvalidParameterValueException("Invalid glue ip for the disaster recovery cluster glue ip:" + glueIp);
        }
        if (url == null || url.isEmpty()) {
            throw new InvalidParameterValueException("Invalid url for the disaster recovery cluster url:" + url);
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new InvalidParameterValueException("Invalid api key for the disaster recovery cluster api key:" + apiKey);
        }
        if (secretKey == null || secretKey.isEmpty()) {
            throw new InvalidParameterValueException("Invalid secret key for the disaster recovery cluster secret key:" + secretKey);
        }

        DisasterRecoveryClusterVO drcluster = disasterRecoveryClusterDao.findByName(name);
        if (drcluster != null) {
            throw new InvalidParameterValueException("A disaster recovery cluster with the same name exists:" + name);
        }
    }

    private void validateDisasterRecoveryClusterMirrorParameters(final DisasterRecoveryClusterVO drCluster) throws CloudRuntimeException {
        // Secondary Cluster DR VM 상태 조회
        List<DisasterRecoveryClusterVmMapVO> drClusterVmList = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(drCluster.getId());
        ResponseObject.ResponseView respView = ResponseObject.ResponseView.Restricted;
        Account caller = CallContext.current().getCallingAccount();
        if (accountService.isRootAdmin(caller.getId())) {
            respView = ResponseObject.ResponseView.Full;
        }
        String responseName = "drclustervmlist";
        if (drClusterVmList != null && !drClusterVmList.isEmpty()) {
            for (DisasterRecoveryClusterVmMapVO vmMapVO : drClusterVmList) {
                UserVmJoinVO userVM = userVmJoinDao.findById(vmMapVO.getVmId());
                if (userVM != null) {
                    if (userVM.getState() != VirtualMachine.State.Stopped) {
                        throw new InvalidParameterValueException("Forced promote and demote functions cannot be executed because there is a running disaster recovery secondary cluster virtual machine : " + userVM.getName());
                    }
                }
            }
        }
        String url = drCluster.getDrClusterUrl();
        Map<String, String> details = disasterRecoveryClusterDetailsDao.findDetails(drCluster.getId());
        String apiKey = details.get(ApiConstants.DR_CLUSTER_API_KEY);
        String secretKey = details.get(ApiConstants.DR_CLUSTER_SECRET_KEY);
        // Primary Cluster DR VM 상태 조회
        String command = "getDisasterRecoveryClusterList";
        String method = "GET";
        Map<String, String> params = new HashMap<>();
        List<GetDisasterRecoveryClusterListResponse> drListResponse = DisasterRecoveryClusterUtil.moldGetDisasterRecoveryClusterListAPI(url + "/client/api/", command, method, apiKey, secretKey);
        if (drListResponse != null || !drListResponse.isEmpty()) {
            // Primary Cluster - moldGetDisasterRecoveryClusterListAPI 성공
            for (GetDisasterRecoveryClusterListResponse dr : drListResponse) {
                if (dr.getName().equalsIgnoreCase(drCluster.getName())) {
                    if (dr.getDisasterRecoveryClusterVms() != null) {
                        List<UserVmResponse> vmListResponse = dr.getDisasterRecoveryClusterVms();
                        for (UserVmResponse vm : vmListResponse) {
                            if (!vm.getState().equalsIgnoreCase("Stopped")) {
                                throw new InvalidParameterValueException("Forced promote and demote functions cannot be executed because there is a running disaster recovery primary cluster virtual machine : " + vm.getName());
                            }
                        }
                    }
                }
            }
        } else {
            // Primary Cluster - moldGetDisasterRecoveryClusterListAPI - 실패
            throw new InvalidParameterValueException("Forced promote and demote functions cannot be executed because failed to query primary cluster DR information.");
        }
    }

    private String[] getServerProperties() {
        String[] serverInfo = null;
        final String HTTP_PORT = "http.port";
        final String HTTPS_ENABLE = "https.enable";
        final String HTTPS_PORT = "https.port";
        final File confFile = PropertiesUtil.findConfigFile("server.properties");
        try {
            InputStream is = new FileInputStream(confFile);
            String port = null;
            String protocol = null;
            final Properties properties = ServerProperties.getServerProperties(is);
            if (properties.getProperty(HTTPS_ENABLE).equals("true")){
                port = properties.getProperty(HTTPS_PORT);
                protocol = "https";
            } else {
                port = properties.getProperty(HTTP_PORT);
                protocol = "http";
            }
            serverInfo = new String[]{port, protocol};
        } catch (final IOException e) {
            LOGGER.debug("Failed to read configuration from server.properties file", e);
        }
        return serverInfo;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!DisasterRecoveryServiceEnabled.value()) {
            return cmdList;
        }
        cmdList.add(ListScvmIpAddressCmd.class);
        cmdList.add(ConnectivityTestsDisasterRecoveryClusterCmd.class);
        cmdList.add(GetDisasterRecoveryClusterListCmd.class);
        cmdList.add(UpdateDisasterRecoveryClusterCmd.class);
        cmdList.add(CreateDisasterRecoveryClusterCmd.class);
        cmdList.add(DeleteDisasterRecoveryClusterCmd.class);
        cmdList.add(EnableDisasterRecoveryClusterCmd.class);
        cmdList.add(DisableDisasterRecoveryClusterCmd.class);
        cmdList.add(PromoteDisasterRecoveryClusterCmd.class);
        cmdList.add(DemoteDisasterRecoveryClusterCmd.class);
        cmdList.add(CreateDisasterRecoveryClusterVmCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return DisasterRecoveryClusterService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                DisasterRecoveryServiceEnabled
        };
    }
}
