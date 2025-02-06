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

package com.cloud.dr.cluster.dao;

import com.cloud.dr.cluster.DisasterRecoveryClusterVO;
import com.cloud.utils.db.GenericDao;

import java.util.List;

public interface DisasterRecoveryClusterDao extends GenericDao<DisasterRecoveryClusterVO, Long> {
    /**
     * @param id
     * @return Returns all the security checks in the database for the given management server id
     */
    List<DisasterRecoveryClusterVO> getDisasterRecoveryClusterList(long id);

     /**
     * @param msHostId
     * @param checkName
     * @return returns the check result for the msHostId, the check name.
     */
    DisasterRecoveryClusterVO getDisasterRecoveryResult(long msHostId, String checkName);

    DisasterRecoveryClusterVO findByName(String name);
}
