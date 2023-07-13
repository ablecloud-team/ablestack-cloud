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

import com.cloud.security.SecurityCheckResultVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class SecurityCheckResultDaoImpl extends GenericDaoBase<SecurityCheckResultVO, Long> implements SecurityCheckResultDao {

    protected SearchBuilder<SecurityCheckResultVO> SecurityChecksSearchBuilder;

    protected SecurityCheckResultDaoImpl() {
        super();
        SecurityChecksSearchBuilder = createSearchBuilder();
        SecurityChecksSearchBuilder.and("mshostId", SecurityChecksSearchBuilder.entity().getMshostId(), SearchCriteria.Op.EQ);
        SecurityChecksSearchBuilder.done();
    }

    @Override
    public List<SecurityCheckResultVO> getSecurityChecks(long mshostId) {
        SearchCriteria<SecurityCheckResultVO> sc = SecurityChecksSearchBuilder.create();
        sc.setParameters("mshostId", mshostId);
        return listBy(sc);
    }
}
