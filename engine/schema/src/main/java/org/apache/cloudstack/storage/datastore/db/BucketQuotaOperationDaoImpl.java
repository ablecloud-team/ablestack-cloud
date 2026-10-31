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
package org.apache.cloudstack.storage.datastore.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.stereotype.Component;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class BucketQuotaOperationDaoImpl extends GenericDaoBase<BucketQuotaOperationVO, Long> implements BucketQuotaOperationDao {
    private final SearchBuilder<BucketQuotaOperationVO> search;
    public BucketQuotaOperationDaoImpl() {
        search = createSearchBuilder();
        search.and("bucket", search.entity().getBucketId(), SearchCriteria.Op.EQ);
        search.and("activeBucket", search.entity().getActiveBucketId(), SearchCriteria.Op.EQ);
        search.and("state", search.entity().getState(), SearchCriteria.Op.NIN);
        search.and("store", search.entity().getObjectStoreId(), SearchCriteria.Op.EQ);
        search.done();
    }
    private SearchCriteria<BucketQuotaOperationVO> active() {
        SearchCriteria<BucketQuotaOperationVO> s = search.create(); s.setParameters("state", "Succeeded", "Failed"); return s;
    }
    @Override public BucketQuotaOperationVO findActiveByBucket(long id) {
        SearchCriteria<BucketQuotaOperationVO> s = active(); s.setParameters("activeBucket", id); return findOneBy(s);
    }
    @Override public BucketQuotaOperationVO findLatestByBucket(long id) {
        SearchCriteria<BucketQuotaOperationVO> s = search.create(); s.setParameters("bucket", id);
        List<BucketQuotaOperationVO> rows = search(s, new Filter(BucketQuotaOperationVO.class, "id", false, 0L, 1L));
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public List<BucketQuotaOperationVO> listActive() {
        SearchCriteria<BucketQuotaOperationVO> s = active();
        s.setParameters("state", "Succeeded", "Failed", "NeedsReconcile");
        return search(s, new Filter(BucketQuotaOperationVO.class, "id", true, 0L, 100L));
    }
    @Override public boolean hasActiveForStore(long id) {
        SearchCriteria<BucketQuotaOperationVO> s = active(); s.setParameters("store", id); return !listBy(s).isEmpty();
    }
    @Override public long reservedBytesForStore(long id) { return sum("reserved_bytes", "object_store_id", id); }
    @Override public long accountHoldBytes(long id) { return sum("account_hold_bytes", "account_id", id); }
    private long sum(String column, String owner, long id) {
        String sql = "SELECT COALESCE(SUM(" + column + "),0) FROM bucket_quota_operation WHERE " + owner + "=? AND state NOT IN ('Succeeded','Failed')";
        try (PreparedStatement p = TransactionLegacy.currentTxn().prepareAutoCloseStatement(sql)) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getBigDecimal(1).longValueExact(); }
        } catch (SQLException | ArithmeticException e) { throw new CloudRuntimeException("Unable to read bucket quota reservations", e); }
    }
}
