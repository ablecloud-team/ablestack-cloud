// Licensed to the ASF under one or more contributor license agreements...
package org.apache.cloudstack.api.command.admin.wall.alerts;

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

import javax.inject.Inject;
import java.util.Map;

@APICommand(
        name = CreateWallAlertSilenceCmd.APINAME,
        description = "Creates an Alertmanager silence from given labels and duration",
        responseObject = WallSilenceResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = { RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User }
)
public class CreateWallAlertSilenceCmd extends BaseCmd {
    public static final String APINAME = "createWallAlertSilence";

    @Inject
    private WallAlertsService wallAlertsService;

    /**
     * 필수: 라벨 MAP
     */
    @Parameter(name = "labels", type = CommandType.MAP, required = true,
            description = "Alert/Rule label map (name=value). UID/alertname/instance etc.")
    private Map<String, String> labels;

    /**
     * 권장: 분 단위(숫자) — 표준 키 (!! required=false 로 완화 !!)
     * - CloudStack 라우터가 'required=true' 를 강제하면 별칭으로만 보냈을 때 사전에 튕깁니다.
     * - required=false 로 두고, execute() 단계에서 유효성 검사합니다.
     */
    @Parameter(name = "durationMinutes", type = CommandType.LONG, required = false,
            description = "Silence duration in minutes from now or startsAt")
    private Long durationMinutes;

    /**
     * 별칭1: 소문자 키로만 오는 환경 대비
     */
    @Parameter(name = "durationminutes", type = CommandType.LONG, required = false,
            description = "Alias of durationMinutes for clients that lowercase params")
    private Long durationminutes;

    /**
     * 별칭2: 문자열 표기(예: 30m, 1h, 2d, 1w, 1M=30days)
     * - 같은 이름으로 LONG/STRING을 중복 선언하면 리플렉션이 꼬입니다. (기존 코드의 중복 원인 제거)
     */
    @Parameter(name = "duration", type = CommandType.STRING, required = false,
            description = "Optional duration string (e.g., 30m, 1h, 2d, 1w, 1M=30 days)")
    private String durationText;

    @Parameter(name = "comment", type = CommandType.STRING,
            description = "Optional comment for auditing")
    private String comment;

    @Parameter(name = "startsAt", type = CommandType.STRING,
            description = "Optional ISO-8601 start time (e.g., 2025-10-20T01:23:45Z). If absent, now()")
    private String startsAt;

    // ---------- Getters ----------
    public Map<String, String> getLabels() { return labels; }
    public String getComment() { return comment; }
    public String getStartsAt() { return startsAt; }
    public String getDuration() { return durationText; }

    /**
     * 최종 분 단위로 해석된 duration.
     * - 표준(durationMinutes) 우선
     * - 없으면 별칭(durationminutes)
     * - 없으면 문자열(duration) 파싱
     */
    public Long getDurationMinutes() {
        if (durationMinutes != null) return durationMinutes;
        if (durationminutes != null) return durationminutes;
        final Long parsed = parseDurationTextToMinutes(durationText);
        return parsed;
    }

    // ---------- Execution ----------
    @Override
    public void execute() {
        try {
            final Long mins = getDurationMinutes();
            if (mins == null || mins <= 0) {
                throw new IllegalArgumentException("Missing or invalid duration: supply 'durationMinutes' (minutes) "
                        + "or 'duration' like 30m/1h/1d/1w/1M.");
            }
            final WallSilenceResponse resp = wallAlertsService.createWallAlertSilence(this);
            resp.setResponseName(getCommandName());
            setResponseObject(resp);
        } catch (IllegalArgumentException iae) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, iae.getMessage());
        } catch (RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }

    // ---------- Helpers ----------
    private static Long parseDurationTextToMinutes(final String s) {
        if (s == null) return null;
        final String str = s.trim();
        if (str.isEmpty()) return null;

        // 지원: m(분), h(시간), d(일), w(주=7일), M(월=30일)
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d+)\\s*([mhdwM]?)$")
                .matcher(str);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid duration format: " + s);
        }
        final long n = Long.parseLong(m.group(1));
        final String u = m.group(2).isEmpty() ? "m" : m.group(2);
        long minutes;
        switch (u) {
            case "m": minutes = n; break;
            case "h": minutes = n * 60L; break;
            case "d": minutes = n * 60L * 24L; break;
            case "w": minutes = n * 60L * 24L * 7L; break;
            case "M": minutes = n * 60L * 24L * 30L; break;
            default: throw new IllegalArgumentException("Unsupported duration unit: " + u);
        }
        return minutes;
    }
}
