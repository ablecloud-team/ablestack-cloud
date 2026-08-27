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
package org.apache.cloudstack.alert.ppurio;

import org.apache.cloudstack.framework.config.ConfigKey;

public class PpurioAlertConfigKeys {
    public static final String DEFAULT_PINNED_CERTIFICATE_PATH = "/var/cloudstack/management/ppurio-sectigo-rsa-domain-validation-secure-server-ca.pem";

    public static final ConfigKey<Boolean> ALERT_KAKAO_ENABLED = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, Boolean.class,
            "kakao.ppurio.enabled", "false",
            "Enable Kakao AlimTalk delivery for Alerts through the Ppurio integration module.", true);

    public static final ConfigKey<String> BASE_URL = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.baseUrl", "https://message.ppurio.com",
            "Ppurio message API base URL.", true);

    public static final ConfigKey<String> PINNED_CERTIFICATE_PATH = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.pinnedCertificatePath", DEFAULT_PINNED_CERTIFICATE_PATH,
            "Filesystem path to the Ppurio pinned intermediate certificate PEM. The default file is created when Ppurio alert delivery is enabled.", true);

    public static final ConfigKey<String> PINNED_CERTIFICATE_PEM = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.pinnedCertificatePem", "",
            "Optional PEM content used to create the Ppurio pinned intermediate certificate file. Leave blank to use the built-in default certificate. Escaped \\n line breaks are supported.", true);

    public static final ConfigKey<String> ACCOUNT = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.account", "",
            "Ppurio account identifier used to issue API tokens.", true);

    public static final ConfigKey<String> API_KEY = new ConfigKey<>("Secure", String.class,
            "kakao.ppurio.apiKey", "",
            "Ppurio API key used to issue API tokens.", true);

    public static final ConfigKey<String> SENDER_PROFILE = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.senderProfile", "",
            "Ppurio Kakao sender profile.", true);

    public static final ConfigKey<String> TEMPLATE_CODE = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.templateCode", "",
            "Ppurio Kakao AlimTalk template code.", true);

    public static final ConfigKey<String> TARGET_NAME = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.targetName", "Ablestack Alert",
            "Default target name sent to Ppurio for Alert Kakao AlimTalk recipients.", true);

    public static final ConfigKey<String> DUPLICATE_FLAG = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.duplicateFlag", "Y",
            "Ppurio duplicateFlag value for Kakao AlimTalk requests.", true);

    public static final ConfigKey<String> RECIPIENTS = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.recipients", "",
            "Comma-separated recipient phone numbers for Alert Kakao AlimTalk delivery.", true);

    public static final ConfigKey<String> CHANGE_WORD_VAR1 = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.changeWord.var1", "${subject}",
            "Template for Ppurio Kakao AlimTalk changeWord var1, used as the alert title. Supported placeholders: ${alertType}, ${subject}", true);

    public static final ConfigKey<String> CHANGE_WORD_VAR2 = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.changeWord.var2", "${alertType}",
            "Template for Ppurio Kakao AlimTalk changeWord var2, used as the alert type. Supported placeholders: ${alertType}, ${subject}", true);

    public static final ConfigKey<Boolean> RESEND_ENABLED = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, Boolean.class,
            "kakao.ppurio.resend.enabled", "false",
            "Enable Ppurio fallback resend settings for failed Kakao AlimTalk delivery.", true);

    public static final ConfigKey<String> RESEND_MESSAGE_TYPE = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.resend.messageType", "SMS",
            "Ppurio fallback resend message type.", true);

    public static final ConfigKey<String> RESEND_FROM = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.resend.from", "",
            "Sender phone number used for Ppurio fallback resend.", true);

    public static final ConfigKey<String> RESEND_SUBJECT_TEMPLATE = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.resend.subjectTemplate", "MOLD 경보",
            "Subject template for Ppurio fallback resend. Supported placeholders: ${alertType}, ${subject}. Rendered subject is limited to 30 bytes.", true);

    public static final ConfigKey<String> RESEND_CONTENT_TEMPLATE = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, String.class,
            "kakao.ppurio.resend.contentTemplate",
            "MOLD 경보: ${subject}",
            "Content template for Ppurio fallback resend. Supported placeholders: ${alertType}, ${subject}. Rendered content is limited to 90 bytes.", true);

    public static final ConfigKey<Integer> CONNECT_TIMEOUT_MS = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, Integer.class,
            "kakao.ppurio.connectTimeoutMs", "5000",
            "Connection timeout in milliseconds for the Ppurio API.", true);

    public static final ConfigKey<Integer> READ_TIMEOUT_MS = new ConfigKey<>(ConfigKey.CATEGORY_ALERT, Integer.class,
            "kakao.ppurio.readTimeoutMs", "10000",
            "Read timeout in milliseconds for the Ppurio API.", true);

    private PpurioAlertConfigKeys() {
    }
}
