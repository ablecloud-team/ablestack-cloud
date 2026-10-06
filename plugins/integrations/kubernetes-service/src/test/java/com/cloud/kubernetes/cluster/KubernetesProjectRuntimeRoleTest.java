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
import java.util.List;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import static org.junit.Assert.assertEquals;

public class KubernetesProjectRuntimeRoleTest {
    private RolePermission permission(String rule, RolePermissionEntity.Permission allow, int order, String description) {
        RolePermission permission=Mockito.mock(RolePermission.class);
        Mockito.when(permission.getRule()).thenReturn(new Rule(rule));Mockito.when(permission.getPermission()).thenReturn(allow);
        Mockito.when(permission.getSortOrder()).thenReturn((long)order);Mockito.when(permission.getDescription()).thenReturn(description);return permission;
    }
    private void check(List<RolePermission> current, boolean changes) {
        KubernetesClusterManagerImpl manager=new KubernetesClusterManagerImpl();manager.roleService=Mockito.mock(RoleService.class);
        Role role=Mockito.mock(Role.class);Mockito.when(role.getId()).thenReturn(8L);Mockito.when(manager.roleService.findAllPermissionsBy(8L)).thenReturn(current);
        Mockito.when(manager.roleService.updateRolePermission(Mockito.eq(role),Mockito.anyList())).thenReturn(true);
        Mockito.when(manager.roleService.createRolePermission(Mockito.eq(role),Mockito.any(Rule.class),Mockito.eq(RolePermissionEntity.Permission.ALLOW),Mockito.anyString()))
                .thenAnswer(i->permission(i.getArgument(1).toString(),RolePermissionEntity.Permission.ALLOW,10,i.getArgument(3)));
        manager.reconcileDefaultProjectKubernetesRole(role);
        if(changes) {
            org.mockito.ArgumentCaptor<List> captor=org.mockito.ArgumentCaptor.forClass(List.class);
            Mockito.verify(manager.roleService).updateRolePermission(Mockito.eq(role),captor.capture());
            List<RolePermission> ordered=captor.getValue();assertEquals("*",ordered.get(ordered.size()-1).getRule().toString());
            assertEquals(KubernetesRuntimeKeyProfile.commands(true).size()+1,ordered.size());
        } else {Mockito.verify(manager.roleService,Mockito.never()).createRolePermission(Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any());}
    }
    private List<RolePermission> defaultRules() {
        List<RolePermission> result=new ArrayList<>();
        for(String api:KubernetesRuntimeKeyProfile.commands(true)) {
            if(!java.util.Arrays.asList("listCapabilities","listZones","listDiskOfferings","listTags","createTags","deleteTags","listPortForwardingRules").contains(api)) {
                result.add(permission(api,RolePermissionEntity.Permission.ALLOW,result.size(),"Allow "+api));
            }
        }
        result.add(permission("*",RolePermissionEntity.Permission.DENY,result.size(),"Deny all"));return result;
    }
    @Test public void knownOldDefaultGainsMetadataApisBeforeDeny(){check(defaultRules(),true);}
    @Test public void customRuleIsPreserved(){List<RolePermission> rules=defaultRules();rules.add(0,permission("*",RolePermissionEntity.Permission.ALLOW,-1,"Operator customization"));check(rules,false);}
    @Test public void customDenyIsPreserved(){List<RolePermission> rules=defaultRules();rules.add(0,permission("listVolumes",RolePermissionEntity.Permission.DENY,-1,"Operator restriction"));check(rules,false);}
    @Test public void emptyRoleIsPreserved(){check(new ArrayList<>(),false);}
    @Test public void alreadyCurrentRoleIsIdempotent(){List<RolePermission> rules=new ArrayList<>();for(String api:KubernetesRuntimeKeyProfile.commands(true))rules.add(permission(api,RolePermissionEntity.Permission.ALLOW,rules.size(),"Allow "+api));rules.add(permission("*",RolePermissionEntity.Permission.DENY,rules.size(),"Deny all"));check(rules,false);}
}
