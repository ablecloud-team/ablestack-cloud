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

package com.cloud.kubernetes.version;

import java.util.function.Supplier;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

public final class KubernetesVersionReferences {
    public static final String SOURCE = "upgrade.source.version.id";
    public static final String TARGET = "upgrade.target.version.id";
    private KubernetesVersionReferences() { }

    public static <T> T withLock(Long id, Supplier<T> action) {
        if (id == null || id < 1) {
            throw new CloudRuntimeException("A valid Kubernetes artifact version is required");
        }
        GlobalLock lock = GlobalLock.getInternLock("KubernetesVersion.Reference." + id);
        try {
            if (!lock.lock(10)) {
                throw new CloudRuntimeException("Another Kubernetes artifact reference operation is in progress; retry later");
            }
            try { return action.get(); } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    public static void requireEnabled(KubernetesSupportedVersion version) {
        if (version == null || version.getState() != KubernetesSupportedVersion.State.Enabled) {
            throw new CloudRuntimeException("Kubernetes artifact was removed or disabled before a new reference could be registered");
        }
    }

    public static void pin(KubernetesCluster cluster, long targetId, KubernetesClusterDetailsDao details) {
        KubernetesClusterDetailsVO target = details.findDetail(cluster.getId(), TARGET);
        KubernetesClusterDetailsVO source = details.findDetail(cluster.getId(), SOURCE);
        if ((target == null) != (source == null) || target != null
                && (!Long.toString(targetId).equals(target.getValue())
                || !Long.toString(cluster.getKubernetesVersionId()).equals(source.getValue()))) {
            throw new CloudRuntimeException("Partial Kubernetes upgrade requires the same source and target artifact IDs; preserve references before recovery");
        }
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) {
                details.addDetail(cluster.getId(), SOURCE, Long.toString(cluster.getKubernetesVersionId()), false);
                details.addDetail(cluster.getId(), TARGET, Long.toString(targetId), false);
            }
        });
    }

    public static void clear(long clusterId, long completedTargetId, KubernetesClusterDetailsDao details) {
        KubernetesClusterDetailsVO target = details.findDetail(clusterId, TARGET);
        if (target == null || !Long.toString(completedTargetId).equals(target.getValue())) {
            throw new CloudRuntimeException("Kubernetes upgrade artifact references changed before completion");
        }
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) {
                details.removeDetail(clusterId, SOURCE);
                details.removeDetail(clusterId, TARGET);
            }
        });
    }
}
