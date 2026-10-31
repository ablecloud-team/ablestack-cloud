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

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.springframework.stereotype.Component;

@Component
public class ObjectStoreAccountCredentialDaoImpl extends GenericDaoBase<ObjectStoreAccountCredentialVO, Long> implements ObjectStoreAccountCredentialDao {
    private final SearchBuilder<ObjectStoreAccountCredentialVO> pair;
    private final SearchBuilder<ObjectStoreAccountCredentialVO> account;
    private final SearchBuilder<ObjectStoreAccountCredentialVO> store;
    public ObjectStoreAccountCredentialDaoImpl() {
        pair = createSearchBuilder();
        pair.and("account", pair.entity().getAccountId(), SearchCriteria.Op.EQ);
        pair.and("store", pair.entity().getObjectStoreId(), SearchCriteria.Op.EQ); pair.done();
        account = createSearchBuilder(); account.and("account", account.entity().getAccountId(), SearchCriteria.Op.EQ); account.done();
        store = createSearchBuilder(); store.and("store", store.entity().getObjectStoreId(), SearchCriteria.Op.EQ); store.done();
    }
    @Override public ObjectStoreAccountCredentialVO findByAccountAndStore(long accountId, long storeId) {
        SearchCriteria<ObjectStoreAccountCredentialVO> sc = pair.create();
        sc.setParameters("account", accountId); sc.setParameters("store", storeId); return findOneBy(sc);
    }
    @Override public java.util.List<ObjectStoreAccountCredentialVO> listByAccount(long id) {
        SearchCriteria<ObjectStoreAccountCredentialVO> sc = account.create(); sc.setParameters("account", id); return listBy(sc);
    }
    @Override public java.util.List<ObjectStoreAccountCredentialVO> listByStore(long id) {
        SearchCriteria<ObjectStoreAccountCredentialVO> sc = store.create(); sc.setParameters("store", id); return listBy(sc);
    }
    @Override public void removeByAccount(long accountId) {
        SearchCriteria<ObjectStoreAccountCredentialVO> sc = account.create(); sc.setParameters("account", accountId); expunge(sc);
    }
    @Override public void removeByStore(long storeId) {
        SearchCriteria<ObjectStoreAccountCredentialVO> sc = store.create(); sc.setParameters("store", storeId); expunge(sc);
    }
}
