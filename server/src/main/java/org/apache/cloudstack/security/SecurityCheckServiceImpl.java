package org.apache.cloudstack.security;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.command.admin.security.GetSecurityCheckResultCmd;
import org.apache.cloudstack.api.command.admin.security.RunSecurityCheckCmd;
import org.apache.cloudstack.api.response.SecurityCheckResultResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.log4j.Logger;

import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.security.dao.SecurityCheckResultDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;

public class SecurityCheckServiceImpl extends ManagerBase implements PluggableService, SecurityCheckService, Configurable {
    private static final Logger LOGGER = Logger.getLogger(SecurityCheckServiceImpl.class);

    private static final ConfigKey<Integer> SecurityCheckInterval = new ConfigKey<>("Advanced", Integer.class,
            "security.check.interval", "0",
            "The interval security check background tasks in seconds", false);

    @Inject
    private SecurityCheckResultDao securityCheckResultDao;
    @Inject
    private ManagementServerHostDao msHostDao;
    @Inject
    private ResponseGenerator responseGenerator;

    @Override
    public List<SecurityCheckResultResponse> listSecurityChecks(GetSecurityCheckResultCmd cmd) {
        long mshostId = cmd.getMshostId();
        List<SecurityCheckResult> result = new ArrayList<>(securityCheckResultDao.getSecurityChecks(mshostId));
        LOGGER.info("=============================");
        LOGGER.info(SecurityCheckInterval.value());
        return responseGenerator.createSecurityCheckResponse(msHostDao.findById(mshostId), result);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SYSTEM_VM_DIAGNOSTICS, eventDescription = "running diagnostics on system vm", async = true)
    public Pair<Boolean, String> runSecurityCheckCommand(final RunSecurityCheckCmd cmd) {
        final Long mshostId = cmd.getMshostId();
        return null;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(RunSecurityCheckCmd.class);
        cmdList.add(GetSecurityCheckResultCmd.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return SecurityCheckServiceImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                SecurityCheckInterval
        };
    }
}
