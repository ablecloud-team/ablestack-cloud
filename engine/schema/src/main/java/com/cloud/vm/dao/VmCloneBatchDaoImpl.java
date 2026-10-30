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
package com.cloud.vm.dao;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.vm.VmCloneBatchVO;

public class VmCloneBatchDaoImpl extends GenericDaoBase<VmCloneBatchVO, Long>
        implements VmCloneBatchDao {
    @Override public java.util.List<VmCloneBatchVO> listCleanupRequired() {
        com.cloud.utils.db.SearchCriteria<VmCloneBatchVO> criteria = createSearchCriteria();
        criteria.addAnd("state", com.cloud.utils.db.SearchCriteria.Op.IN, "FAILED", "PARTIAL", "INTERRUPTED");
        criteria.addAnd("data", com.cloud.utils.db.SearchCriteria.Op.LIKE, "%\"cleanupRequired\":true%");
        java.util.Map<Long, VmCloneBatchVO> result = new java.util.LinkedHashMap<>();
        for (VmCloneBatchVO row : listBy(criteria)) result.put(row.getId(), row);
        criteria = createSearchCriteria();
        criteria.addAnd("state", com.cloud.utils.db.SearchCriteria.Op.IN, "FAILED", "PARTIAL", "INTERRUPTED");
        criteria.addAnd("data", com.cloud.utils.db.SearchCriteria.Op.LIKE, "%\"snapshotCleanupError\":%");
        for (VmCloneBatchVO row : listBy(criteria)) result.put(row.getId(), row);
        return new java.util.ArrayList<>(result.values());
    }
}
