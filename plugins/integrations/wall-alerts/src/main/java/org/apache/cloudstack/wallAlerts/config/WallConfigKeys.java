package org.apache.cloudstack.wallAlerts.config;

import org.apache.cloudstack.framework.config.ConfigKey;

public final class WallConfigKeys {
    private WallConfigKeys() {}

    public static final ConfigKey<Boolean> WALL_ALERT_ENABLED =
            new ConfigKey<>("Advanced", Boolean.class, "wall.alerts.enable", "true",
                    "Enable Wall alerts integration.", false);

    public static final ConfigKey<String> WALL_BASE_URL =
            new ConfigKey<>("Advanced", String.class, "wall.base.url", "https://ccvm:19400",
                    "Base URL of Wall.", false, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<String> WALL_API_TOKEN =
            new ConfigKey<>("Advanced", String.class, "wall.api.token", "",
                    "Service account token for Wall.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> CONNECT_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.connect.timeout.ms", "3000",
                    "HTTP connect timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> READ_TIMEOUT_MS =
            new ConfigKey<>("Advanced", Integer.class, "wall.read.timeout.ms", "10000",
                    "HTTP read timeout in milliseconds.", true, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> BACKGROUND_POLL_INTERVAL_SECONDS =
            new ConfigKey<>("Advanced", Integer.class, "wall.alerts.background.poll.interval.seconds", "0",
                    "Interval in seconds for background Wall alert evaluation. Set to 0 or less to disable background evaluation.", false, WALL_ALERT_ENABLED.key());

    public static final ConfigKey<Integer> ALERT_THROTTLE_SECONDS =
            new ConfigKey<>("Advanced", Integer.class, "wall.alerts.throttle.seconds", "14400",
                    "Minimum seconds between duplicate Wall alert deliveries for the same rule UID.", true, WALL_ALERT_ENABLED.key());
}
