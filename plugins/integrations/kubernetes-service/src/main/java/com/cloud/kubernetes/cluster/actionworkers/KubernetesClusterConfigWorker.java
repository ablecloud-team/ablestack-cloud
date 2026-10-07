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
package com.cloud.kubernetes.cluster.actionworkers;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;

/** Refreshes only the downloaded configuration; certificate renewal remains an operator procedure. */
public class KubernetesClusterConfigWorker extends KubernetesClusterActionWorker {
    private static final Pattern SERVER = Pattern.compile("(?m)^(\\s*server:[ \t]*)https://[^\\s]+[ \t]*$");
    private static final Pattern API_VERSION = Pattern.compile("(?m)^apiVersion:[ \t]*v1[ \t]*$");
    private static final Pattern KIND = Pattern.compile("(?m)^kind:[ \t]*Config[ \t]*$");

    public KubernetesClusterConfigWorker(KubernetesCluster cluster, KubernetesClusterManagerImpl manager) {
        super(cluster, manager);
    }

    public String refresh() {
        requireRefreshable(kubernetesCluster);
        final String endpoint = kubernetesCluster.getEndpoint();
        final Pair<String, Integer> address = getKubernetesClusterServerIpSshPort(null);
        if (address == null || StringUtils.isBlank(address.first())) {
            throw new CloudRuntimeException("Kubernetes configuration refresh requires an accessible control node");
        }
        final Pair<Boolean, String> response;
        try {
            response = readLatestConfig(address.first(), address.second());
        } catch (Exception e) {
            // SSH output and exception text may contain configuration credentials.
            throw new CloudRuntimeException("Kubernetes configuration refresh failed; verify control-node SSH access and retry");
        }
        if (response == null || !Boolean.TRUE.equals(response.first())) {
            throw new CloudRuntimeException("Kubernetes configuration refresh failed; cached configuration was preserved");
        }
        final String config = withClusterEndpoint(response.second(), endpoint);
        final KubernetesClusterVO current = kubernetesClusterDao.findById(kubernetesCluster.getId());
        requireRefreshable(current);
        if (current.getRemoved() != null || !Objects.equals(endpoint, current.getEndpoint())
                || !Objects.equals(kubernetesCluster.getKubernetesVersionId(), current.getKubernetesVersionId())) {
            throw new CloudRuntimeException("Kubernetes cluster changed during configuration refresh; retry after the operation completes");
        }
        kubernetesClusterDetailsDao.addDetail(kubernetesCluster.getId(), "kubeConfigData",
                Base64.encodeBase64String(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)), false);
        return config;
    }

    protected Pair<Boolean, String> readLatestConfig(String address, int port) throws Exception {
        return SshHelper.sshExecute(address, port, getControlNodeLoginUser(), getManagementServerSshPublicKeyFile(), null,
                "sudo cat /etc/kubernetes/user.conf 2>/dev/null || sudo cat /etc/kubernetes/admin.conf", 10000, 10000, 20000);
    }

    static void requireRefreshable(KubernetesCluster cluster) {
        if (cluster == null || cluster.getClusterType() != KubernetesCluster.ClusterType.CloudManaged
                || cluster.getState() != KubernetesCluster.State.Running) {
            throw new CloudRuntimeException("Configuration refresh requires a Running CloudManaged Kubernetes cluster");
        }
    }

    static String withClusterEndpoint(String config, String endpoint) {
        if (StringUtils.isBlank(config) || config.length() > 1024 * 1024
                || !API_VERSION.matcher(config).find() || !KIND.matcher(config).find()) {
            throw new CloudRuntimeException("Control-node configuration was invalid; cached configuration was preserved");
        }
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(StringUtils.isEmpty(uri.getPath()) || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CloudRuntimeException("Kubernetes cluster endpoint is invalid; cached configuration was preserved");
        }
        Matcher server = SERVER.matcher(config);
        if (!server.find()) {
            throw new CloudRuntimeException("Control-node configuration server was missing; cached configuration was preserved");
        }
        String prefix = server.group(1);
        int start = server.start(), end = server.end();
        if (server.find()) {
            throw new CloudRuntimeException("Control-node configuration was ambiguous; cached configuration was preserved");
        }
        String publicEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return config.substring(0, start) + prefix + publicEndpoint + config.substring(end);
    }
}
