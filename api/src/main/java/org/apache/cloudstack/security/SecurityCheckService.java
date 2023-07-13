package org.apache.cloudstack.security;

import java.util.List;

import com.cloud.utils.Pair;

import org.apache.cloudstack.api.command.admin.security.GetSecurityCheckResultCmd;
import org.apache.cloudstack.api.command.admin.security.RunSecurityCheckCmd;
import org.apache.cloudstack.api.response.SecurityCheckResultResponse;

public interface SecurityCheckService {

    List<SecurityCheckResultResponse> listSecurityChecks(GetSecurityCheckResultCmd cmd);

    Pair<Boolean, String> runSecurityCheckCommand(RunSecurityCheckCmd runSecurityCheckCmd);
}
