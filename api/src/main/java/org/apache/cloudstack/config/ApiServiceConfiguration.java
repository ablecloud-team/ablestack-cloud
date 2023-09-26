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
package org.apache.cloudstack.config;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;

public class ApiServiceConfiguration implements Configurable {public static final ConfigKey<String> ManagementServerAddresses = new ConfigKey<>(String.class, "host", "Advanced", "localhost", "The ip address of management server. This can also accept comma separated addresses.", true, ConfigKey.Scope.Global, null, null, null, null, null, ConfigKey.Kind.CSV, null);
    public static final ConfigKey<String> ApiServletPath = new ConfigKey<String>("Advanced", String.class, "endpoint.url", "http://localhost:8080/client/api",
            "API end point. Can be used by CS components/services deployed remotely, for sending CS API requests", true);
    public static final ConfigKey<Long> DefaultUIPageSize = new ConfigKey<Long>("Advanced", Long.class, "default.ui.page.size", "20",
            "The default pagesize to be used by UI and other clients when making list* API calls", true, ConfigKey.Scope.Global);
    public static final ConfigKey<Boolean> ApiSourceCidrChecksEnabled = new ConfigKey<>("Advanced", Boolean.class, "api.source.cidr.checks.enabled",
            "true", "Are the source checks on API calls enabled (true) or not (false)? See api.allowed.source.ip", true, ConfigKey.Scope.Global);
    public static final ConfigKey<String> MonitoringWallPortalProtocol = new ConfigKey<String>("Advanced", String.class, "monitoring.wall.portal.protocol",
            "http", "Monitoring Service Wall Portal Protocol.(ex: http or https)", true);
    public static final ConfigKey<String> MonitoringWallPortalDomain = new ConfigKey<String>("Advanced", String.class, "monitoring.wall.portal.domain",
            "", "Monitoring Service Wall Portal Domain.(ex: id or domain)", true);
    public static final ConfigKey<String> ApiAllowedSourceIp = new ConfigKey<>(String.class, "api.allowed.source.ip", "Advanced",
            "0.0.0.0", "Comma separated list of IPv4/IPv6 CIDRs from which API calls can be performed. Can be set on Global and Account levels.", true, ConfigKey.Scope.Account, null, null, null, null, null, ConfigKey.Kind.CSV, null);
    public static final ConfigKey<String> ApiAllowedSourceCidr = new ConfigKey<String>("Advanced", String.class, "api.allowed.source.cidr",
            "0", "A cidr setting that allows you to make api calls.", true, ConfigKey.Scope.Account);
    public static final ConfigKey<String> MonitoringWallPortalPort = new ConfigKey<String>("Advanced", String.class, "monitoring.wall.portal.port",
            "3000", "Monitoring Service Wall Portal Port.(ex:3000)", true);
    public static final ConfigKey<String> MonitoringWallPortalVmUri = new ConfigKey<String>("Advanced", String.class, "monitoring.wall.portal.vm.uri",
            "/d/ldwEyoKnz/gasangmeosin-sangse-hyeonhwang", "Monitoring Service Wall Portal Uri.(ex:/d/ldwEyoKnz/gasangmeosin-sangse-hyeonhwang)", true);
    public static final ConfigKey<Boolean> EventDeleteEnabled = new ConfigKey<>("Advanced", Boolean.class, "event.delete.enabled",
            "false", "true if Event Delete Button is enabled, false otherwise)", false);

    @Override
    public String getConfigComponentName() {
        return ApiServiceConfiguration.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ManagementServerAddresses, ApiServletPath, DefaultUIPageSize, ApiSourceCidrChecksEnabled, ApiAllowedSourceIp, ApiAllowedSourceCidr, MonitoringWallPortalProtocol, MonitoringWallPortalDomain, MonitoringWallPortalPort, MonitoringWallPortalVmUri, EventDeleteEnabled};
    }

}
