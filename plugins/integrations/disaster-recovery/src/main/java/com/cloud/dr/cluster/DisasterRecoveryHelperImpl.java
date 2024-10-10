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

import com.cloud.dr.cluster.dao.DisasterRecoveryClusterDao;
import com.cloud.dr.cluster.dao.DisasterRecoveryClusterVmMapDao;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

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

    @Override
    public String getConfigComponentName() {
        return DisasterRecoveryHelper.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{};
    }
}