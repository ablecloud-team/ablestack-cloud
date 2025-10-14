package org.apache.cloudstack.wallAlerts.service;

import com.cloud.utils.component.Manager;
import com.cloud.utils.component.PluggableService;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.WallAlertRuleResponse;
import org.apache.cloudstack.api.command.admin.wall.alerts.ListWallAlertRulesCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.UpdateWallAlertRuleThresholdCmd;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.api.command.admin.wall.alerts.ListWallAlertSilencesCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.ExpireWallAlertSilenceCmd;


public interface WallAlertsService extends Manager, PluggableService, Configurable {
    ListResponse<WallAlertRuleResponse> listWallAlertRules(ListWallAlertRulesCmd cmd);
    WallAlertRuleResponse updateWallAlertRuleThreshold(UpdateWallAlertRuleThresholdCmd cmd);
    /**
     * UID로 지정된 단일 룰의 pause/resume
     */
    boolean pauseWallAlertRule(String namespaceHint, String groupName, String ruleUid, boolean paused);
    /**
     * id="dashboardUid:panelId" 입력을 받아 pause/resume
     * (네가 기존 임계치 업데이트에서 쓰던 매핑 로직 재사용)
     */
    boolean pauseWallAlertRuleById(String id, boolean paused);
    boolean pauseWallAlertRuleByUid(String ruleUid, boolean paused);

    ListResponse<WallSilenceResponse> listWallAlertSilences(ListWallAlertSilencesCmd cmd);
    SuccessResponse expireWallAlertSilence(ExpireWallAlertSilenceCmd cmd);
}
