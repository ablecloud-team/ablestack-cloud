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

package org.apache.cloudstack.wallAlerts.config;

import org.apache.cloudstack.framework.config.ConfigKey;

public final class WallConfigKeys {
    private WallConfigKeys() {}

    public static final ConfigKey<Boolean> WALL_ALERT_ENABLED =
            new ConfigKey<>("Advanced", Boolean.class, "wall.alerts.enable", "true",
                    "Enable Wall alerts integration.", false);

    public static final ConfigKey<String> WALL_BASE_URL =
            new ConfigKey<>("Advanced", String.class, "wall.base.url", "https://ccvm:8081",
                    "Base URL of Wall.", false, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Boolean> WALL_TLS_VERIFY =
            new ConfigKey<>("Advanced", Boolean.class, "wall.tls.verify", "false",
                    "Verify Wall HTTPS certificate chain and hostname. Defaults to false for private Wall endpoints "
                    + "with self-signed or address-mismatched certificates. Set true to enforce server identity verification. Applies to Wall only.",
                    true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<String> WALL_API_TOKEN =
            new ConfigKey<>("Advanced", String.class, "wall.api.token", "",
                    "Service account token for Wall.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> CONNECT_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.connect.timeout.ms", "3000",
                    "HTTP connect timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> READ_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.read.timeout.ms", "10000",
                    "HTTP read timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> WALL_ADMIN_USER =
            new ConfigKey<>("Advanced", Integer.class, "wall.admin.user", "admin",
                    "Service account for Create Wall User.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> WALL_ADMIN_PASSWORD =
            new ConfigKey<>("Secure", Integer.class, "wall.admin.password", "admin",
                    "Service account password for Create Wall User.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> BACKGROUND_POLL_INTERVAL_SECONDS =
            new ConfigKey<>("Advanced", Integer.class, "wall.alerts.background.poll.interval.seconds", "0",
                    "Interval in seconds for background Wall alert evaluation. Set to 0 or less to disable background evaluation.", false, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> ALERT_THROTTLE_SECONDS =
            new ConfigKey<>("Advanced", Integer.class, "wall.alerts.throttle.seconds", "14400",
                    "Minimum seconds between duplicate Wall alert deliveries for the same rule UID.", true, WALL_ALERT_ENABLED.key());
}
