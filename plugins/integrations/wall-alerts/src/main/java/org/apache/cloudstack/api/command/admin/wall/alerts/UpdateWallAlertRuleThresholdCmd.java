package org.apache.cloudstack.api.command.admin.wall.alerts;

import javax.inject.Inject;

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.WallAlertRuleResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

/**
 * 월(Wall, Grafana) 경고 룰 임계치 업데이트 커맨드입니다.
 * - 리스트 경로에는 영향이 없습니다.
 * - 에러 코드 타입만 CloudStack 표준(ApiErrorCode)로 맞췄습니다.
 */
@APICommand(
        name = UpdateWallAlertRuleThresholdCmd.APINAME,
        description = "Updates a Wall(Grafana) alert rule threshold",
        responseObject = WallAlertRuleResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = { RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin }
)
public class UpdateWallAlertRuleThresholdCmd extends BaseCmd {
    public static final String APINAME = "updateWallAlertRuleThreshold";

    @Inject
    private WallAlertsService wallAlertsService;

    @Parameter(name = "id", type = CommandType.STRING, required = false,
            description = "Rule key in 'group:title' format")
    private String id;

    @Parameter(name = ApiConstants.UID, type = CommandType.STRING, required = true,
            description = "권장 방식: Grafana alert rule UID")
    private String uid;

    @Parameter(name = ApiConstants.OPERATOR, type = CommandType.STRING, required = true,
            description = "Threshold operator (gt, lt, between, outside)")
    private String operator;

    @Parameter(name = "threshold", type = CommandType.DOUBLE, required = true,
            description = "New threshold value (single-threshold operators)")
    private Double threshold;

    @Parameter(name = "threshold2", type = CommandType.DOUBLE, required = false,
            description = "Upper threshold value (only for between/outside operators)")
    private Double threshold2;

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            if ((id == null || id.isBlank()) && (uid == null || uid.isBlank())) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "id 또는 uid 중 하나는 필수입니다.");
            }
            final WallAlertRuleResponse resp = wallAlertsService.updateWallAlertRuleThreshold(this);
            setResponseObject(resp);
            resp.setResponseName(getCommandName());
        } catch (RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    public String getId() { return id; }
    public String getUid() { return uid; }
    public String getOperator() {
        return operator;
    }
    public Double getThreshold() { return threshold; }
    public Double getThreshold2() {
        return threshold2;
    }
}
