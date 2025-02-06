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

import javax.inject.Inject;

import java.util.List;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterVmMapDao;
import com.cloud.utils.script.Script;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;

@Component
public class DisasterRecoveryHelperImpl extends AdapterBase implements DisasterRecoveryHelper, Configurable {
    private static final Logger logger = LogManager.getLogger(DisasterRecoveryHelperImpl.class);

    @Inject
    private DisasterRecoveryClusterDao disasterRecoveryClusterDao;
    @Inject
    private DisasterRecoveryClusterVmMapDao disasterRecoveryClusterVmMapDao;

    public void checkVmCanBeDestroyed(long vmId) {
        List<DisasterRecoveryClusterVO> drCluster = disasterRecoveryClusterDao.listAll();
        for (DisasterRecoveryClusterVO dr : drCluster) {
            List<DisasterRecoveryClusterVmMapVO> vmMap = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(dr.getId());
            if (!CollectionUtils.isEmpty(vmMap)) {
                for (DisasterRecoveryClusterVmMapVO map : vmMap) {
                    if (map.getVmId() == vmId) {
                        throw new CloudRuntimeException("If a mirroring virtual machine exists, the virtual machine cannot be destroyed or expunged.");
                    }
                }
            }
        }
        return;
    }

    public void checkVmCanBeStarted(long vmId) {
        String ipList = Script.runSimpleBashScript("cat /etc/hosts | grep -E 'scvm.*-mngt' | awk '{print $1}' | tr '\n' ','");
        List<DisasterRecoveryClusterVO> drCluster = disasterRecoveryClusterDao.listAll();
        // Secondary 클러스터의 강제 디모트 기능이 정상적으로 완료되기 전 까지 VM 시작 예외처리
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        PreparedStatement pstmt = null;
        try {
            pstmt = txn.prepareAutoCloseStatement("select * from `cloud`.`event` where type = 'DR.DEMOTE' and date_format(created, '%y-%m-%d') = CURRENT_DATE()");
            int numRows = 0;
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                numRows = rs.getInt(1);
            }
            if (numRows > 0) {
                pstmt = txn.prepareAutoCloseStatement("select * from `cloud`.`event` where type = 'DR.DEMOTE' and state = 'Completed' and description = 'Successfully completed demoting disaster recovery cluster' and date_format(created, '%y-%m-%d') = CURRENT_DATE()");
                numRows = 0;
                rs = pstmt.executeQuery();
                if (rs.next()) {
                    numRows = rs.getInt(1);
                }
                for (DisasterRecoveryClusterVO drc : drCluster) {
                    if (drc.getDrClusterType().equalsIgnoreCase("primary")) {
                        List<DisasterRecoveryClusterVmMapVO> vmMap = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(drc.getId());
                        if (!CollectionUtils.isEmpty(vmMap)) {
                            for (DisasterRecoveryClusterVmMapVO map : vmMap) {
                                if (map.getVmId() == vmId) {
                                    if (numRows > 0) {
                                        if (ipList != null || !ipList.isEmpty()) {
                                            ipList = ipList.replaceAll(",$", "");
                                            String[] array = ipList.split(",");
                                            String imageName = map.getMirroredVmVolumePath();
                                            int cnt = 0;
                                            for (int i=0; i < array.length; i++) {
                                                String glueIp = array[i];
                                                ///////////////////// glue-api 프로토콜과 포트 확정 시 변경 예정
                                                String glueUrl = "https://" + glueIp + ":8080/api/v1";
                                                String glueCommand = "/mirror/image/status/rbd/" +imageName;
                                                String glueMethod = "GET";
                                                String mirrorImageStatus = DisasterRecoveryClusterUtil.glueImageMirrorStatusAPI(glueUrl, glueCommand, glueMethod);
                                                if (mirrorImageStatus != null) {
                                                    JsonObject statObject = (JsonObject) new JsonParser().parse(mirrorImageStatus).getAsJsonObject();
                                                    JsonArray drArray = (JsonArray) new JsonParser().parse(mirrorImageStatus).getAsJsonObject().get("peer_sites");
                                                    if (statObject.has("description") && drArray.size() != 0 && drArray != null) {
                                                        JsonElement peerState = null;
                                                        JsonElement peerDescription = null;
                                                        for (JsonElement dr : drArray) {
                                                            if (dr.getAsJsonObject().get("state") != null) {
                                                                peerState = dr.getAsJsonObject().get("state");
                                                            }
                                                            if (dr.getAsJsonObject().get("description") != null) {
                                                                peerDescription = dr.getAsJsonObject().get("description");
                                                            }
                                                        }
                                                        if (peerState != null && peerDescription != null) {
                                                            if (statObject.get("description").getAsString().equals("local image is primary")) {
                                                                if (peerState.getAsString().contains("replaying") && !peerDescription.getAsString().contains("idle")) {
                                                                    throw new CloudRuntimeException("The virtual machine cannot be started because the image syncing process for the mirroring virtual machine has not completed.");
                                                                }
                                                            }
                                                        }
                                                    }
                                                    break;
                                                } else {
                                                    cnt += 1;
                                                }
                                            }
                                            if (cnt > 2) {
                                                throw new CloudRuntimeException("The virtual machine cannot be started because the image syncing process for the mirroring virtual machine has not completed.");
                                            }
                                        }
                                    } else {
                                        throw new CloudRuntimeException("The virtual machine cannot be started because the forced demote is not completed.");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("The virtual machine cannot be started because the forced demote is not completed. ", e);
        }
        // 정상적인 Ready 상태가 아닌 경우 VM 시작 예외처리
        for (DisasterRecoveryClusterVO drc : drCluster) {
            List<DisasterRecoveryClusterVmMapVO> vmMap = disasterRecoveryClusterVmMapDao.listByDisasterRecoveryClusterId(drc.getId());
            if (!CollectionUtils.isEmpty(vmMap)) {
                for (DisasterRecoveryClusterVmMapVO map : vmMap) {
                    if (map.getVmId() == vmId) {
                        if (ipList != null || !ipList.isEmpty()) {
                            ipList = ipList.replaceAll(",$", "");
                            String[] array = ipList.split(",");
                            String imageName = map.getMirroredVmVolumePath();
                            int cnt = 0;
                            for (int i=0; i < array.length; i++) {
                                String glueIp = array[i];
                                ///////////////////// glue-api 프로토콜과 포트 확정 시 변경 예정
                                String glueUrl = "https://" + glueIp + ":8080/api/v1";
                                String glueCommand = "/mirror/image/status/rbd/" +imageName;
                                String glueMethod = "GET";
                                String mirrorImageStatus = DisasterRecoveryClusterUtil.glueImageMirrorStatusAPI(glueUrl, glueCommand, glueMethod);
                                if (mirrorImageStatus != null) {
                                    JsonObject statObject = (JsonObject) new JsonParser().parse(mirrorImageStatus).getAsJsonObject();
                                    if (statObject.has("description")) {
                                        if (!statObject.get("description").getAsString().equals("local image is primary") && !statObject.get("description").getAsString().equals("orphan (force promoting)")) {
                                            throw new CloudRuntimeException("The virtual machine cannot be started because the mirroring image not ready status.");
                                        }
                                    }
                                    break;
                                } else {
                                    cnt += 1;
                                }
                            }
                            if (cnt > 2) {
                                throw new CloudRuntimeException("The virtual machine cannot be started because the mirroring image status Glue-API failed.");
                            }
                        }
                    }
                }
            }
        }
        return;
    }

    @Override
    public String getConfigComponentName() {
        return DisasterRecoveryHelper.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{};
    }
}