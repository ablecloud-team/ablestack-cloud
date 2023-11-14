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

import java.util.List;
import org.springframework.stereotype.Component;

import com.cloud.vm.VbmcVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component(value = "VbmcDao")
public class VbmcDaoImpl extends GenericDaoBase<VbmcVO, Long> implements VbmcDao {

    protected final SearchBuilder<VbmcVO> VbmcSearch;
    protected final SearchBuilder<VbmcVO> VbmcAblePortSearch;

    protected VbmcDaoImpl() {
        super();
        VbmcSearch = createSearchBuilder();
        VbmcSearch.and("vmId", VbmcSearch.entity().getVmId(), SearchCriteria.Op.EQ);
        VbmcSearch.done();

        VbmcAblePortSearch = createSearchBuilder();
        VbmcAblePortSearch.and("vmId", VbmcAblePortSearch.entity().getVmId(), SearchCriteria.Op.EQ);
        VbmcAblePortSearch.done();
    }

    @Override
    public List<VbmcVO> listByVmId(long vmId) {
        SearchCriteria<VbmcVO> sc = VbmcSearch.create();
        sc.setParameters("vmId", vmId);
        return listBy(sc);
    }

    @Override
    public List<VbmcVO> findAblePort() {
        SearchCriteria<VbmcVO> sc = VbmcAblePortSearch.create();
        sc.setParameters("vmId", 0);
        return listBy(sc);
    }
}
