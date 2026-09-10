// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0.
package org.apache.cloudstack.api.command.admin.dr;

import java.util.Collections;

import org.apache.cloudstack.api.ServerApiException;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dr.DrActionAvailability;
import com.cloud.dr.DrConstants;
import com.cloud.dr.DrPlanService;

public class StartDrFailoverCmdTest {

    @Test
    public void disasterFailoverMayProceedWhenOnlyNormalCutoverIsNotReady() {
        StartDrFailoverCmd command = command(true,
                unavailable(DrConstants.ACTION_REASON_CUTOVER_NOT_READY));

        command.validateActionAllowed();
        Mockito.verify(command.drPlanService, Mockito.never()).getActionAvailability(Mockito.anyLong());
    }

    @Test(expected = ServerApiException.class)
    public void plannedFailoverRemainsBlockedWhenNormalCutoverIsNotReady() {
        StartDrFailoverCmd command = command(false,
                unavailable(DrConstants.ACTION_REASON_CUTOVER_NOT_READY));

        command.validateActionAllowed();
    }

    @Test(expected = ServerApiException.class)
    public void disasterFailoverDoesNotBypassOtherActionBlockers() {
        StartDrFailoverCmd command = command(true,
                unavailable("DR_ACTION_TARGET_NOT_READY"));

        command.validateActionAllowed();
    }

    @Test(expected = ServerApiException.class)
    public void disasterFailoverDoesNotBypassControlReadiness() {
        StartDrFailoverCmd command = command(true,
                unavailable(DrConstants.ACTION_REASON_CUTOVER_NOT_READY), false);

        command.validateActionAllowed();
    }

    private StartDrFailoverCmd command(boolean disaster, DrActionAvailability availability) {
        return command(disaster, availability, true);
    }

    private StartDrFailoverCmd command(boolean disaster, DrActionAvailability availability,
            boolean disasterFailoverEligible) {
        StartDrFailoverCmd command = new StartDrFailoverCmd();
        DrPlanService service = Mockito.mock(DrPlanService.class);
        Mockito.when(service.getActionAvailability(41L))
                .thenReturn(Collections.singletonMap("failover", availability));
        Mockito.when(service.getActionEligibility(41L))
                .thenReturn(Collections.singletonMap("disasterFailover", disasterFailoverEligible));
        Mockito.when(service.getDatabaseActionEvaluation(41L)).thenReturn(new com.cloud.dr.DrPlanActionEvaluation(
                Collections.singletonMap("disasterFailover", disasterFailoverEligible),
                Collections.singletonMap("failover", availability)));
        ReflectionTestUtils.setField(command, "planId", 41L);
        ReflectionTestUtils.setField(command, "disaster", disaster);
        ReflectionTestUtils.setField(command, "drPlanService", service);
        return command;
    }

    private DrActionAvailability unavailable(String reasonCode) {
        return new DrActionAvailability(true, false, reasonCode, Collections.emptyMap());
    }
}
