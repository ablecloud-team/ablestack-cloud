package org.apache.cloudstack.wallAlerts.config;

import org.apache.cloudstack.framework.config.ConfigKey;

public final class WallConfigKeys {
    private WallConfigKeys() {}

    public static final ConfigKey<Boolean> WALL_ALERT_ENABLED =
            new ConfigKey<>("Advanced", Boolean.class, "wall.alerts.enable", "true",
                    "Enable Wall alerts integration.", true);

    public static final ConfigKey<String> WALL_BASE_URL =
            new ConfigKey<>("Advanced", String.class, "wall.base.url", "http://localhost:3000",
                    "Base URL of Wall.", false, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<String> WALL_API_TOKEN =
            new ConfigKey<>("Secure", String.class, "wall.api.token", "",
                    "Service account token for Wall.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> CONNECT_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.connect.timeout.ms", "3000",
                    "HTTP connect timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> READ_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.read.timeout.ms", "10000",
                    "HTTP read timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());
}
