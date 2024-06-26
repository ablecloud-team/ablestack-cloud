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

package com.cloud.security.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.security.IntegrityVerificationVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class IntegrityVerificationDaoImpl extends GenericDaoBase<IntegrityVerificationVO, Long> implements IntegrityVerificationDao {

    protected SearchBuilder<IntegrityVerificationVO> IntegrityVerificationsSearchBuilder;

    protected IntegrityVerificationDaoImpl() {
        super();
        IntegrityVerificationsSearchBuilder = createSearchBuilder();
        IntegrityVerificationsSearchBuilder.and("msHostId", IntegrityVerificationsSearchBuilder.entity().getMsHostId(), SearchCriteria.Op.EQ);
        IntegrityVerificationsSearchBuilder.and("filePath", IntegrityVerificationsSearchBuilder.entity().getFilePath(), SearchCriteria.Op.EQ);
        IntegrityVerificationsSearchBuilder.done();
    }

    @Override
    public List<IntegrityVerificationVO> getIntegrityVerifications(long msHostId) {
        SearchCriteria<IntegrityVerificationVO> sc = IntegrityVerificationsSearchBuilder.create();
        sc.setParameters("msHostId", msHostId);
        return listBy(sc);
    }

    @Override
    public IntegrityVerificationVO getIntegrityVerificationResult(long msHostId, String filePath) {
        SearchCriteria<IntegrityVerificationVO> sc = IntegrityVerificationsSearchBuilder.create();
        sc.setParameters("msHostId", msHostId);
        sc.setParameters("filePath", filePath);
        List<IntegrityVerificationVO> verifications = listBy(sc);
        return verifications.isEmpty() ? null : verifications.get(0);
    }
}
