// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.alert;

import com.cloud.alert.dao.AlertDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import org.apache.cloudstack.utils.mailing.SMTPMailSender;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import javax.mail.MessagingException;
import java.io.UnsupportedEncodingException;

@RunWith(MockitoJUnitRunner.class)
public class AlertManagerImplTest {

    @Spy
    @InjectMocks
    AlertManagerImpl alertManagerImplMock;

    @Mock
    AlertDao alertDaoMock;

    @Mock
    private DataCenterDao _dcDao;

    @Mock
    private HostPodDao _podDao;

    @Mock
    private ClusterDao _clusterDao;

    @Mock
    AlertVO alertVOMock;

    @Mock
    Logger loggerMock;

    @Mock
    SMTPMailSender mailSenderMock;

    private void sendMessage (){
        try {
            DataCenterVO zone = Mockito.mock(DataCenterVO.class);
            Mockito.when(zone.getId()).thenReturn(0L);
            Mockito.when(_dcDao.findById(0L)).thenReturn(zone);
            HostPodVO pod = Mockito.mock(HostPodVO.class);
            Mockito.when(pod.getId()).thenReturn(1L);
            Mockito.when(_podDao.findById(1L)).thenReturn(pod);
            ClusterVO cluster = Mockito.mock(ClusterVO.class);
            Mockito.when(cluster.getId()).thenReturn(1L);
            Mockito.when(_clusterDao.findById(1L)).thenReturn(cluster);

            alertManagerImplMock.sendAlert(AlertManager.AlertType.ALERT_TYPE_CPU, 0, 1l, 1l, "", "");
        } catch (UnsupportedEncodingException | MessagingException e) {
            Assert.fail();
        }
    }

    private void prepareZoneAndPod(long zoneId, Long podId) {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(zoneId);
        Mockito.when(_dcDao.findById(zoneId)).thenReturn(zone);
        if (podId != null) {
            HostPodVO pod = Mockito.mock(HostPodVO.class);
            Mockito.when(pod.getId()).thenReturn(podId);
            Mockito.when(_podDao.findById(podId)).thenReturn(pod);
        }
    }

    @Test
    public void sendAlertTestSendMail() {
        Mockito.doReturn(null).when(alertDaoMock).getLastAlert(Mockito.anyShort(), Mockito.anyLong(),
                Mockito.anyLong(), Mockito.anyLong());
        Mockito.doReturn(null).when(alertDaoMock).persist(Mockito.any());
        alertManagerImplMock.recipients = new String [] {""};

        sendMessage();

        Mockito.verify(alertManagerImplMock).sendMessage(Mockito.any());
    }

    @Test
    public void sendAlertTestDebugLogging() {
        Mockito.doReturn(0).when(alertVOMock).getSentCount();
        Mockito.doReturn(alertVOMock).when(alertDaoMock).getLastAlert(Mockito.anyShort(), Mockito.anyLong(),
                Mockito.anyLong(), Mockito.anyLong());

        sendMessage();

        Mockito.verify(alertManagerImplMock.logger).debug(Mockito.anyString());
        Mockito.verify(alertManagerImplMock, Mockito.never()).sendMessage(Mockito.any());
    }

    @Test
    public void sendAlertTestWarnLogging() {
        Mockito.doReturn(null).when(alertDaoMock).getLastAlert(Mockito.anyShort(), Mockito.anyLong(),
                Mockito.anyLong(), Mockito.anyLong());
        Mockito.doReturn(null).when(alertDaoMock).persist(Mockito.any());
        alertManagerImplMock.recipients = null;

        sendMessage();

        Mockito.verify(alertManagerImplMock.logger, Mockito.times(2)).warn(Mockito.anyString());
        Mockito.verify(alertManagerImplMock, Mockito.never()).sendMessage(Mockito.any());
    }

    @Test
    public void sendPersistentAlertSendsDeliveryForNewWallAlert() {
        prepareZoneAndPod(1L, 2L);
        Mockito.doReturn(null).when(alertDaoMock).getLastAlert(Mockito.anyShort(), Mockito.anyLong(),
                Mockito.anyLong(), Mockito.<Long>isNull());
        Mockito.doReturn(null).when(alertDaoMock).persist(Mockito.any());
        Mockito.doNothing().when(alertManagerImplMock).sendAlertDeliveries(
                Mockito.eq(AlertManager.AlertType.ALERT_TYPE_WALL_RULE),
                Mockito.eq(1L),
                Mockito.eq(2L),
                Mockito.<Long>isNull(),
                Mockito.eq("subject"),
                Mockito.eq("body"));

        alertManagerImplMock.sendPersistentAlert(AlertManager.AlertType.ALERT_TYPE_WALL_RULE, 1L, 2L, "subject", "body");

        Mockito.verify(alertDaoMock).persist(Mockito.any(AlertVO.class));
        Mockito.verify(alertManagerImplMock).sendAlertDeliveries(
                Mockito.eq(AlertManager.AlertType.ALERT_TYPE_WALL_RULE),
                Mockito.eq(1L),
                Mockito.eq(2L),
                Mockito.<Long>isNull(),
                Mockito.eq("subject"),
                Mockito.eq("body"));
    }

    @Test
    public void sendPersistentAlertSendsDeliveryForDuplicateWallAlert() {
        prepareZoneAndPod(1L, 2L);
        AlertVO update = new AlertVO();
        Mockito.doReturn(alertVOMock).when(alertDaoMock).getLastAlert(Mockito.anyShort(), Mockito.anyLong(),
                Mockito.anyLong(), Mockito.<Long>isNull());
        Mockito.doReturn(null).when(alertVOMock).getResolved();
        Mockito.doReturn("subject").when(alertVOMock).getSubject();
        Mockito.doReturn(3).when(alertVOMock).getSentCount();
        Mockito.doReturn(10L).when(alertVOMock).getId();
        Mockito.doReturn(update).when(alertDaoMock).createForUpdate();
        Mockito.doReturn(true).when(alertDaoMock).update(Mockito.eq(10L), Mockito.same(update));
        Mockito.doNothing().when(alertManagerImplMock).sendAlertDeliveries(
                Mockito.eq(AlertManager.AlertType.ALERT_TYPE_WALL_RULE),
                Mockito.eq(1L),
                Mockito.eq(2L),
                Mockito.<Long>isNull(),
                Mockito.eq("subject"),
                Mockito.eq("body"));

        alertManagerImplMock.sendPersistentAlert(AlertManager.AlertType.ALERT_TYPE_WALL_RULE, 1L, 2L, "subject", "body");

        Mockito.verify(alertDaoMock).update(Mockito.eq(10L), Mockito.same(update));
        Mockito.verify(alertDaoMock, Mockito.never()).persist(Mockito.any(AlertVO.class));
        Mockito.verify(alertManagerImplMock).sendAlertDeliveries(
                Mockito.eq(AlertManager.AlertType.ALERT_TYPE_WALL_RULE),
                Mockito.eq(1L),
                Mockito.eq(2L),
                Mockito.<Long>isNull(),
                Mockito.eq("subject"),
                Mockito.eq("body"));
    }
}
