// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.usage;

import java.util.Date;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import com.cloud.alert.AlertVO;
import com.cloud.alert.dao.AlertDao;
import com.cloud.utils.component.ManagerBase;
import org.apache.cloudstack.utils.mailing.MailAddress;
import org.apache.cloudstack.utils.mailing.SMTPMailProperties;
import org.apache.cloudstack.utils.mailing.SMTPMailSender;
import org.apache.commons.lang3.ArrayUtils;

@Component
public class UsageAlertManagerImpl extends ManagerBase implements AlertManager {
    protected Logger logger = LogManager.getLogger(UsageAlertManagerImpl.class.getName());

    private String senderAddress;
    protected SMTPMailSender mailSender;
    protected String[] recipients;

    @Inject
    protected AlertDao _alertDao;
    @Inject
    private ConfigurationDao _configDao;

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        Map<String, String> configs = _configDao.getConfiguration("management-server", params);

        senderAddress = configs.get("alert.email.sender");
        String emailAddressList = configs.get("alert.email.addresses");
        recipients = null;
        if (emailAddressList != null) {
            recipients = emailAddressList.split(",");
        }

        String namespace = "alert.smtp";
        mailSender = new SMTPMailSender(configs, namespace);
        return true;
    }

    @Override
    public void clearAlert(AlertType alertType, long dataCenterId, long podId) {
        try {
            clearAlert(alertType.getType(), dataCenterId, podId);
        } catch (Exception ex) {
            logger.error("Problem clearing email alert", ex);
        }
    }

    /**
     * 기존 공용 경로: DB 저장 후, 수신자 설정이 있을 때만 메일 전송
     */
    @Override
    public void sendAlert(AlertType alertType, long dataCenterId, Long podId, String subject, String content) {
        // 최근 발송 이력 확인(일부 타입은 최신 1건만 유지)
        AlertVO alert = null;
        if ((alertType != AlertManager.AlertType.ALERT_TYPE_HOST)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_USERVM)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_SSVM)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_MANAGEMENT_NODE)) {
            alert = _alertDao.getLastAlert(alertType.getType(), dataCenterId, podId);
        }

        if (alert == null) {
            AlertVO newAlert = new AlertVO();
            newAlert.setType(alertType.getType());
            newAlert.setSubject(subject);
            newAlert.setContent(content);
            newAlert.setPodId(podId);
            newAlert.setDataCenterId(dataCenterId);
            newAlert.setSentCount(1);
            newAlert.setLastSent(new Date());
            newAlert.setName(alertType.getName());
            _alertDao.persist(newAlert);
        } else {
            logger.debug(String.format("Have already sent [%s] emails for alert type [%s] -- skipping send email.", alert.getSentCount(), alertType));
            return;
        }

        // 메일은 설정돼 있을 때만 발송(없어도 DB는 이미 기록됨)
        if (ArrayUtils.isEmpty(recipients)) {
            logger.warn(String.format("No recipients set in global setting 'alert.email.addresses', "
                    + "skipping sending alert with subject [%s] and content [%s].", subject, content));
            return;
        }

        SMTPMailProperties mailProps = new SMTPMailProperties();
        mailProps.setSender(new MailAddress(senderAddress));
        mailProps.setSubject(subject);
        mailProps.setContent(content);
        mailProps.setContentType("text/plain");

        Set<MailAddress> addresses = new HashSet<>();
        for (String recipient : recipients) {
            addresses.add(new MailAddress(recipient));
        }
        mailProps.setRecipients(addresses);

        try {
            mailSender.sendMail(mailProps);
        } catch (Throwable t) {
            logger.warn("Failed to send alert email (DB was persisted): " + t.getMessage(), t);
        }
    }

    /**
     * AlertDao 헬퍼: short 시그니처(클러스터 없음)
     */
    public void clearAlert(short alertType, long dataCenterId, Long podId) {
        if (alertType != -1) {
            AlertVO alert = _alertDao.getLastAlert(alertType, dataCenterId, podId);
            if (alert != null) {
                AlertVO updatedAlert = _alertDao.createForUpdate();
                updatedAlert.setResolved(new Date());
                _alertDao.update(alert.getId(), updatedAlert);
            }
        }
    }

    @Override
    public void recalculateCapacity() {
        // not used in usage alerts
    }

    @Override
    public boolean generateAlert(AlertType alertType, long dataCenterId, Long podId, String msg) {
        try {
            sendAlert(alertType, dataCenterId, podId, msg, msg);
            return true;
        } catch (Exception ex) {
            logger.warn("Failed to generate an alert of type=" + alertType + "; msg=" + msg, ex);
            return false;
        }
    }

    // =========================================================================
    //  새 전용 메서드: WALL 등에서 mailSender/수신자 유무와 무관하게 DB 기록을 보장
    //    - 기존 공용 메서드는 건드리지 않음
    //    - 필요 시 클러스터 ID 버전도 제공
    // =========================================================================

    /**
     * 전용(클러스터 없이): 내부적으로 공용 경로를 그대로 사용
     * - UsageAlertManagerImpl의 sendAlert는 메일 전송과 무관하게 DB를 먼저 기록하므로
     *   전용 메서드에서도 동일 보장.
     */
    @Override
    public void sendPersistentAlert(AlertType alertType, long dataCenterId, Long podId, String subject, String content) {
        sendAlert(alertType, dataCenterId, podId, subject, content);
    }

    /**
     * 전용(클러스터 포함): DAO를 통해 DB 기록 보장 후, 메일은 설정 시에만 발송
     */
    @Override
    public void sendPersistentAlert(AlertType alertType, long dataCenterId, Long podId, Long clusterId, String subject, String content) {
        // 최근 발송 이력 확인(클러스터 단위)
        AlertVO alert = null;
        if ((alertType != AlertManager.AlertType.ALERT_TYPE_HOST)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_USERVM)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_CONSOLE_PROXY)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_SSVM)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_STORAGE_MISC)
                && (alertType != AlertManager.AlertType.ALERT_TYPE_MANAGEMENT_NODE)) {
            // AlertDao의 클러스터 포함 오버로드 사용
            alert = _alertDao.getLastAlert(alertType.getType(), dataCenterId, podId, clusterId);
        }

        if (alert == null) {
            AlertVO newAlert = new AlertVO();
            newAlert.setType(alertType.getType());
            newAlert.setSubject(subject);
            newAlert.setContent(content);
            newAlert.setClusterId(clusterId);
            newAlert.setPodId(podId);
            newAlert.setDataCenterId(dataCenterId);
            newAlert.setSentCount(1);
            newAlert.setLastSent(new Date());
            newAlert.setName(alertType.getName());
            _alertDao.persist(newAlert);
        } else {
            logger.debug(String.format("Have already sent [%s] emails for alert type [%s] (cluster=%s) -- skipping send email.",
                    alert.getSentCount(), alertType, String.valueOf(clusterId)));
            return;
        }

        // 메일은 옵션
        if (ArrayUtils.isEmpty(recipients)) {
            logger.warn(String.format("No recipients set in global setting 'alert.email.addresses', "
                    + "skipping sending alert with subject [%s] and content [%s].", subject, content));
            return;
        }

        SMTPMailProperties mailProps = new SMTPMailProperties();
        mailProps.setSender(new MailAddress(senderAddress));
        mailProps.setSubject(subject);
        mailProps.setContent(content);
        mailProps.setContentType("text/plain");

        Set<MailAddress> addresses = new HashSet<>();
        for (String recipient : recipients) {
            addresses.add(new MailAddress(recipient));
        }
        mailProps.setRecipients(addresses);

        try {
            mailSender.sendMail(mailProps);
        } catch (Throwable t) {
            logger.warn("Failed to send alert email (DB was persisted): " + t.getMessage(), t);
        }
    }
}
