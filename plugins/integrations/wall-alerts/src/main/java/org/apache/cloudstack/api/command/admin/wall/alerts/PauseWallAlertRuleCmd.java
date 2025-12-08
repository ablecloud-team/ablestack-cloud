// src/org/apache/cloudstack/api/command/admin/wall/alerts/PauseWallAlertRuleCmd.java
package org.apache.cloudstack.api.command.admin.wall.alerts;

import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

import javax.inject.Inject;

@APICommand(name = PauseWallAlertRuleCmd.APINAME,
        description = "Pause/Resume evaluation of a Wall alert rule",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        responseView = ResponseObject.ResponseView.Full)
public class PauseWallAlertRuleCmd extends BaseCmd {

    public static final String APINAME = "pauseWallAlertRule";

    // (호환) dashUid:panelId
    @Parameter(name = ApiConstants.ID, type = CommandType.STRING, required = false,
            description = "ID in the form 'dashboardUid:panelId' (e.g., dec041amqw4cge:77)")
    private String id;

    // ★ 신규: UID 전용(프런트 사용)
    @Parameter(name = "uid", type = CommandType.STRING, required = false,
            description = "Grafana rule UID to pause/resume (preferred).")
    private String uid;

    // (선택 힌트: 옛 경로)
    @Parameter(name = "namespace", type = CommandType.STRING, required = false,
            description = "Folder / namespace hint (name or uid)")
    private String namespace;

    @Parameter(name = "group", type = CommandType.STRING, required = false,
            description = "Ruler group name")
    private String group;

    // (호환) 과거 클라이언트가 쓰던 이름
    @Parameter(name = "ruleUid", type = CommandType.STRING, required = false,
            description = "[Deprecated] Use 'uid' instead.")
    private String ruleUid;

    @Parameter(name = "paused", type = CommandType.BOOLEAN, required = true,
            description = "true to pause, false to resume")
    private Boolean paused;

    public String getId() { return id; }
    public String getNamespace() { return namespace; }
    public String getGroup() { return group; }
    public String getRuleUid() { return ruleUid; }
    public String getUid() { return uid; } // ★ 추가
    public Boolean getPaused() { return paused; }

    @Inject
    private WallAlertsService wallAlertsService;

    @Override
    public void execute() {
        if (paused == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "'paused' is required");
        }

        // ★ uid 우선, ruleUid는 호환용 폴백
        final String effectiveUid = (uid != null && !uid.isBlank()) ? uid
                : (ruleUid != null && !ruleUid.isBlank() ? ruleUid : null);

        final boolean ok;
        if (effectiveUid != null) {
            ok = wallAlertsService.pauseWallAlertRuleByUid(effectiveUid, paused);
        } else if (id != null && !id.isBlank()) {
            // 레거시 호환: id=dashUid:panelId
            ok = wallAlertsService.pauseWallAlertRuleById(id, paused);
        } else {
            // 완전 레거시 루트(ns+group+ruleUid) — 유지하되 메시지 갱신
            if (namespace == null || namespace.isBlank()
                    || group == null || group.isBlank()
                    || ruleUid == null || ruleUid.isBlank()) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR,
                        "Provide 'uid' (preferred), or 'id', or full triple 'namespace','group','ruleUid'.");
            }
            ok = wallAlertsService.pauseWallAlertRule(namespace, group, ruleUid, paused);
        }

        if (!ok) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to change pause state");
        }
        setResponseObject(new SuccessResponse(getCommandName()));
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
