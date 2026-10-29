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
package org.apache.cloudstack.wallAlerts.service;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Map;

import com.cloud.user.Account;
import com.cloud.user.User;
import org.apache.cloudstack.api.command.admin.wall.alerts.CreateWallAlertSilenceCmd;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.wallAlerts.client.WallApiClient;
import org.apache.cloudstack.wallAlerts.client.WallApiClient.SilenceCreateRequest;
import org.apache.cloudstack.wallAlerts.model.SilenceDto;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WallSilenceEndTimeTest {
    private WallAlertsServiceImpl service;
    private WallApiClient client;
    private CreateWallAlertSilenceCmd command;

    @Before
    public void setUp() throws Exception {
        final Account account = mock(Account.class);
        when(account.getAccountName()).thenReturn("test-account");
        CallContext.register(mock(User.class), account);
        service = new WallAlertsServiceImpl();
        client = mock(WallApiClient.class);
        setField(service, "wallApiClient", client);
        command = new CreateWallAlertSilenceCmd();
        setField(command, "labels", Map.of("__alert_rule_uid__", "test-rule"));
        when(client.createSilence(any())).thenReturn(new SilenceDto());
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void explicitEndIsPassedToWallWithoutRoundingOrDuration() throws Exception {
        final OffsetDateTime end = OffsetDateTime.now().plusDays(3).withOffsetSameInstant(java.time.ZoneOffset.ofHours(9));
        setField(command, "endsAt", end.toString());
        service.createWallAlertSilence(command);
        final SilenceCreateRequest request = capturedRequest();
        assertEquals(end, request.getEndsAt());
        assertTrue(request.getStartsAt().isBefore(end));
        assertEquals("__alert_rule_uid__", request.getMatchers().get(0).getName());
        assertEquals("test-rule", request.getMatchers().get(0).getValue());
    }

    @Test
    public void explicitEndOverridesDuration() throws Exception {
        final OffsetDateTime end = OffsetDateTime.now().plusDays(2);
        setField(command, "endsAt", end.toString());
        setField(command, "durationMinutes", 30L);
        service.createWallAlertSilence(command);
        assertEquals(end, capturedRequest().getEndsAt());
    }

    @Test
    public void legacyMinuteDurationStillWorks() throws Exception {
        final OffsetDateTime start = OffsetDateTime.now().plusMinutes(5);
        setField(command, "startsAt", start.toString());
        setField(command, "durationMinutes", 30L);
        service.createWallAlertSilence(command);
        assertEquals(start.plusMinutes(30), capturedRequest().getEndsAt());
    }

    @Test
    public void legacyStringDurationStillWorks() throws Exception {
        final OffsetDateTime start = OffsetDateTime.now().plusMinutes(5);
        setField(command, "startsAt", start.toString());
        setField(command, "durationText", "2h");
        service.createWallAlertSilence(command);
        assertEquals(start.plusHours(2), capturedRequest().getEndsAt());
    }

    @Test
    public void invalidPastAndNonIncreasingEndTimesNeverReachWall() throws Exception {
        for (String end : new String[] {"not-a-date", "2050-01-01T12:00:00", OffsetDateTime.now().minusSeconds(1).toString()}) {
            setField(command, "endsAt", end);
            assertRejected();
        }
        final OffsetDateTime end = OffsetDateTime.now().plusHours(1);
        setField(command, "endsAt", end.toString());
        setField(command, "startsAt", end.toString());
        assertRejected();
        setField(command, "startsAt", end.plusSeconds(1).toString());
        assertRejected();
        verify(client, never()).createSilence(any());
    }

    @Test
    public void missingOrInvalidLegacyDurationNeverReachesWall() throws Exception {
        assertRejected();
        setField(command, "durationMinutes", 0L);
        assertRejected();
        setField(command, "durationMinutes", -1L);
        assertRejected();
        verify(client, never()).createSilence(any());
    }

    @Test
    public void apiCommandAcceptsExplicitEndWithoutDuration() throws Exception {
        final WallAlertsService mockService = mock(WallAlertsService.class);
        setField(command, "wallAlertsService", mockService);
        setField(command, "endsAt", OffsetDateTime.now().plusHours(1).toString());
        when(mockService.createWallAlertSilence(command)).thenReturn(new WallSilenceResponse());
        command.execute();
        verify(mockService).createWallAlertSilence(command);
    }

    @Test
    public void endTimeParameterBindsFromNormalizedHttpRequestKey() throws Exception {
        // API HTTP keys are lowercased before ParamProcessWorker looks up annotation.name().
        final String end = OffsetDateTime.now().plusHours(1).toString();
        final Map<String, String> normalizedRequest = Map.of("endsat", end);
        final Field field = CreateWallAlertSilenceCmd.class.getDeclaredField("endsAt");
        final Parameter parameter = field.getAnnotation(Parameter.class);
        assertEquals("endsat", parameter.name());
        field.setAccessible(true);
        field.set(command, normalizedRequest.get(parameter.name()));
        assertEquals(end, command.getEndsAt());
        service.createWallAlertSilence(command);
        assertEquals(OffsetDateTime.parse(end), capturedRequest().getEndsAt());
    }

    private void assertRejected() {
        try {
            service.createWallAlertSilence(command);
            fail("Invalid silence timing must be rejected before calling Wall");
        } catch (IllegalArgumentException expected) {
            // Expected parameter validation failure.
        }
    }

    private SilenceCreateRequest capturedRequest() {
        final ArgumentCaptor<SilenceCreateRequest> capture = ArgumentCaptor.forClass(SilenceCreateRequest.class);
        verify(client).createSilence(capture.capture());
        return capture.getValue();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
