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
package com.cloud.kubernetes.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.Rule;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.user.RegisterUserKeysCmd;

import com.cloud.utils.exception.CloudRuntimeException;

/** Cluster-scoped controller credentials. Never widen or reuse another cluster's key. */
public final class KubernetesRuntimeKeyProfile {
    public static final String KEY_DETAIL = "runtime.apikey.id";
    private static final List<String> BASE = Arrays.asList(
            "listCapabilities", "listZones", "listVirtualMachines", "listNetworks", "listPublicIpAddresses",
            "associateIpAddress", "disassociateIpAddress", "listLoadBalancerRules", "createLoadBalancerRule",
            "updateLoadBalancerRule", "deleteLoadBalancerRule", "assignToLoadBalancerRule", "removeFromLoadBalancerRule",
            "listLoadBalancerRuleInstances", "listFirewallRules", "createFirewallRule", "updateFirewallRule",
            "deleteFirewallRule", "listPortForwardingRules", "listNetworkACLs", "listNetworkACLLists", "createNetworkACL", "deleteNetworkACL",
            "listTags", "createTags", "deleteTags", "listKubernetesClusters", "scaleKubernetesCluster", "queryAsyncJobResult");
    private static final List<String> CSI = Arrays.asList("listDiskOfferings", "listVolumes", "createVolume", "deleteVolume",
            "attachVolume", "detachVolume", "resizeVolume", "listSnapshots", "createSnapshot", "deleteSnapshot");

    private KubernetesRuntimeKeyProfile() { }

    public static String name(String clusterUuid, boolean csi) {
        if (!UUID.fromString(clusterUuid).toString().equals(clusterUuid)) {
            throw new CloudRuntimeException("Invalid Kubernetes cluster UUID for controller credentials");
        }
        return "mold-cks-" + clusterUuid + (csi ? "-csi-v1" : "-base-v1");
    }

    public static List<String> commands(boolean csi) {
        List<String> commands = new ArrayList<>(BASE);
        if (csi) {
            commands.addAll(CSI);
        }
        return commands;
    }

    public static RegisterUserKeysCmd request(long userId, String clusterUuid, boolean csi) {
        final String keyName = name(clusterUuid, csi);
        final List<Map<String, Object>> rules = new ArrayList<>();
        for (String command : commands(csi)) {
            rules.add(rule(command, RolePermissionEntity.Permission.ALLOW));
        }
        rules.add(rule("*", RolePermissionEntity.Permission.DENY));
        return new RegisterUserKeysCmd() {
            @Override public Long getUserId() { return userId; }
            @Override public String getName() { return keyName; }
            @Override public String getDescription() { return "Kubernetes controller profile " + keyName; }
            @Override public List<Map<String, Object>> getRules() { return rules; }
        };
    }

    private static Map<String, Object> rule(String command, RolePermissionEntity.Permission permission) {
        Map<String, Object> result = new HashMap<>();
        result.put(ApiConstants.RULE, new Rule(command));
        result.put(ApiConstants.PERMISSION, permission);
        result.put(ApiConstants.DESCRIPTION, "Kubernetes controller " + command);
        return result;
    }

    public static void validateIdentity(ApiKeyPair key, long userId, long accountId, long domainId, String clusterUuid, boolean csi) {
        if (key == null || key.getUserId() == null || key.getUserId() != userId || key.getAccountId() != accountId
                || key.getDomainId() != domainId || !name(clusterUuid, csi).equals(key.getName())) {
            throw new CloudRuntimeException("Kubernetes controller key owner or profile does not match the cluster");
        }
    }

    public static void validateForUse(ApiKeyPair key, long userId, long accountId, long domainId,
            String clusterUuid, boolean csi, List<? extends RolePermissionEntity> permissions) {
        validateIdentity(key, userId, accountId, domainId, clusterUuid, csi);
        Date now = new Date();
        if (key.getRemoved() != null || key.hasEndDatePassed() || key.getStartDate() != null && key.getStartDate().after(now)
                || key.getApiKey() == null || key.getApiKey().isEmpty() || key.getSecretKey() == null || key.getSecretKey().isEmpty()) {
            throw new CloudRuntimeException("Kubernetes controller key is unavailable; replace the cluster-scoped credential before retry");
        }
        validatePermissions(csi, permissions);
    }

    public static void validatePermissions(boolean csi, List<? extends RolePermissionEntity> permissions) {
        Set<String> expected = new HashSet<>(commands(csi));
        // Accept only the exact previous profile when the newly required VPC read command is absent.
        // Existing credentials are never broadened in place; new creation/explicit rotation gets the new profile.
        if (permissions != null && permissions.stream().noneMatch(permission ->
                "listNetworkACLLists".equals(permission.getRule().getRuleString()))) {
            expected.remove("listNetworkACLLists");
        }
        if (permissions == null || permissions.size() != expected.size() + 1) {
            throw new CloudRuntimeException("Kubernetes controller key permissions do not match its feature profile");
        }
        Set<String> seen = new HashSet<>();
        long lastAllow = Long.MIN_VALUE;
        Long denyOrder = null;
        for (RolePermissionEntity permission : permissions) {
            if (!(permission instanceof ApiKeyPairPermissionVO)) {
                throw new CloudRuntimeException("Cannot verify Kubernetes controller permission order");
            }
            long order = ((ApiKeyPairPermissionVO) permission).getSortOrder();
            String rule = permission.getRule().getRuleString();
            if ("*".equals(rule) && permission.getPermission() == RolePermissionEntity.Permission.DENY && denyOrder == null) {
                denyOrder = order;
            } else if (expected.contains(rule) && permission.getPermission() == RolePermissionEntity.Permission.ALLOW && seen.add(rule)) {
                lastAllow = Math.max(lastAllow, order);
            } else {
                throw new CloudRuntimeException("Kubernetes controller key has an unexpected permission");
            }
        }
        if (!seen.equals(expected) || denyOrder == null || denyOrder <= lastAllow) {
            throw new CloudRuntimeException("Kubernetes controller key requires exact API allows followed by deny-all");
        }
    }
}
