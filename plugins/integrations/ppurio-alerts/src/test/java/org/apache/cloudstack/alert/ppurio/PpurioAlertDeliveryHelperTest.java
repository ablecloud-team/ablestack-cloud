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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.alert.AlertManager;

public class PpurioAlertDeliveryHelperTest {
    private final PpurioAlertDeliveryHelper helper = new PpurioAlertDeliveryHelper();

    @Test
    public void parseRecipientsTrimsConfiguredNumbersAndSkipsBlanks() {
        List<String> recipients = helper.parseRecipients(" 01011112222, ,01033334444,, 01055556666 ");

        assertEquals(3, recipients.size());
        assertEquals("01011112222", recipients.get(0));
        assertEquals("01033334444", recipients.get(1));
        assertEquals("01055556666", recipients.get(2));
    }

    @Test
    public void buildResendUsesDefaultTemplateAndSafeValues() {
        Map<String, String> resend = helper.buildResend(AlertManager.AlertType.ALERT_TYPE_CPU, "subject", null);
        String message = resend.get("content");

        assertEquals("SMS", resend.get("messageType"));
        assertEquals("MOLD 경보", resend.get("subject"));
        assertTrue(resend.get("subject").getBytes(StandardCharsets.UTF_8).length <= 30);
        assertEquals("MOLD 경보: subject", message);
        assertTrue(message.getBytes(StandardCharsets.UTF_8).length <= 90);
    }

    @Test
    public void buildResendTruncatesSubjectToPpurioByteLimit() {
        PpurioAlertDeliveryHelper helper = helperWithResendSubjectTemplate("${subject}");
        StringBuilder longSubject = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            longSubject.append("가");
        }

        Map<String, String> resend = helper.buildResend(AlertManager.AlertType.ALERT_TYPE_CPU, longSubject.toString(), null);
        String subject = resend.get("subject");

        assertTrue(subject.getBytes(StandardCharsets.UTF_8).length <= 30);
        assertEquals(10, subject.length());
    }

    @Test
    public void buildResendNormalizesFromPhoneNumber() {
        PpurioAlertDeliveryHelper helper = helperWithResendFrom("010-1111-2222");

        Map<String, String> resend = helper.buildResend(AlertManager.AlertType.ALERT_TYPE_CPU, "subject", null);

        assertEquals("01011112222", resend.get("from"));
    }

    @Test
    public void buildResendIfEnabledSkipsInvalidFromPhoneNumber() {
        PpurioAlertDeliveryHelper helper = helperWithResendFrom("123123");

        assertNull(helper.buildResendIfEnabled(AlertManager.AlertType.ALERT_TYPE_CPU, "subject", null));
    }

    @Test
    public void buildAuthorizationHeaderUsesBasicAccountAndAuthKeyToken() {
        String header = helper.buildAuthorizationHeader("account", "apiKey");

        String expectedToken = Base64.getEncoder().encodeToString("account:apiKey".getBytes(StandardCharsets.UTF_8));
        assertEquals("Basic " + expectedToken, header);
    }

    @Test
    public void getPpurioPinnedSslSocketFactoryLoadsPinnedIntermediateCertificate() throws Exception {
        Path certificateFile = createTempCertificatePath();
        PpurioAlertDeliveryHelper helper = helperWithPinnedCertificatePath(certificateFile.toString());

        assertNotNull(helper.getPpurioPinnedSslSocketFactory());
        assertTrue(Files.exists(certificateFile));
    }

    @Test
    public void ensurePinnedCertificateFileCreatesDefaultPemFile() throws Exception {
        Path certificateFile = createTempCertificatePath();
        PpurioAlertDeliveryHelper helper = helperWithPinnedCertificatePath(certificateFile.toString());

        helper.ensurePinnedCertificateFile();

        assertTrue(Files.exists(certificateFile));
        assertTrue(new String(Files.readAllBytes(certificateFile), StandardCharsets.US_ASCII).contains("BEGIN CERTIFICATE"));
        assertNotNull(helper.loadPinnedIntermediateCertificate(CertificateFactory.getInstance("X.509"), certificateFile.toString()));
    }

    @Test
    public void ensurePinnedCertificateFileRecreatesEmptyPemFile() throws Exception {
        Path certificateFile = createTempCertificatePath();
        Files.createFile(certificateFile);
        PpurioAlertDeliveryHelper helper = helperWithPinnedCertificatePath(certificateFile.toString());

        helper.ensurePinnedCertificateFile();

        assertTrue(Files.size(certificateFile) > 0);
        assertNotNull(helper.loadPinnedIntermediateCertificate(CertificateFactory.getInstance("X.509"), certificateFile.toString()));
    }

    @Test
    public void ensurePinnedCertificateFileUsesConfiguredPemWithEscapedNewlines() throws Exception {
        Path certificateFile = createTempCertificatePath();
        Files.write(certificateFile, "not a certificate".getBytes(StandardCharsets.US_ASCII));
        String configuredPem = helper.getDefaultPinnedIntermediateCertificatePem().replace("\n", "\\n");
        PpurioAlertDeliveryHelper configuredHelper = helperWithPinnedCertificatePathAndPem(certificateFile.toString(), configuredPem);

        configuredHelper.ensurePinnedCertificateFile();

        assertNotNull(configuredHelper.loadPinnedIntermediateCertificate(CertificateFactory.getInstance("X.509"), certificateFile.toString()));
    }

    @Test
    public void parseTokenReturnsTokenFromPpurioTokenResponse() throws Exception {
        String responseBody = "{\"token\":\"token-value\",\"type\":\"Bearer\",\"expired\":\"20260508150548\"}";

        assertEquals("token-value", helper.parseToken(responseBody));
    }

    @Test(expected = IOException.class)
    public void parseTokenThrowsIOExceptionForMalformedResponse() throws Exception {
        helper.parseToken("<html>");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void buildTargetsIncludeConfiguredKakaoChangeWords() {
        Map<String, String> changeWord = helper.buildChangeWord(AlertManager.AlertType.ALERT_TYPE_CPU, "subject", "body");
        List<Map<String, Object>> targets = helper.buildTargets(Arrays.asList("01033334444"), changeWord);

        assertEquals("subject", changeWord.get("var1"));
        assertEquals("ALERT.CPU", changeWord.get("var2"));
        assertFalse(changeWord.containsKey("var3"));

        assertEquals(1, targets.size());
        assertEquals("01033334444", targets.get(0).get("to"));
        assertEquals(PpurioAlertConfigKeys.TARGET_NAME.value(), targets.get(0).get("name"));
        Map<String, String> targetChangeWord = (Map<String, String>)targets.get(0).get("changeWord");
        assertEquals("subject", targetChangeWord.get("var1"));
        assertEquals("ALERT.CPU", targetChangeWord.get("var2"));
        assertFalse(targetChangeWord.containsKey("var3"));
    }

    @Test
    public void buildChangeWordTruncatesVar1ToPpurioByteLimitWithSuffix() {
        StringBuilder longSubject = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longSubject.append("가");
        }

        Map<String, String> changeWord = helper.buildChangeWord(AlertManager.AlertType.ALERT_TYPE_CPU, longSubject.toString(), "body");
        String var1 = changeWord.get("var1");

        assertTrue(var1.getBytes(StandardCharsets.UTF_8).length <= 100);
        assertTrue(var1.endsWith("..."));
        assertEquals(35, var1.length());
    }

    @Test
    public void buildChangeWordTruncatesVar1ToPpurioCharacterLimitWithSuffix() {
        StringBuilder longSubject = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longSubject.append("1");
        }

        Map<String, String> changeWord = helper.buildChangeWord(AlertManager.AlertType.ALERT_TYPE_CPU, longSubject.toString(), "body");
        String var1 = changeWord.get("var1");

        assertTrue(var1.getBytes(StandardCharsets.UTF_8).length <= 100);
        assertTrue(var1.endsWith("..."));
        assertEquals(50, var1.length());
    }

    @Test
    public void buildRequestBodyIncludesPpurioAlimtalkFieldsAndResend() {
        Map<String, String> changeWord = helper.buildChangeWord(AlertManager.AlertType.ALERT_TYPE_CPU, "subject", "body");
        List<Map<String, Object>> targets = helper.buildTargets(Arrays.asList("01033334444"), changeWord);
        Map<String, String> resend = new LinkedHashMap<>();
        resend.put("messageType", "SMS");
        resend.put("from", "01011112222");
        resend.put("subject", "sms subject");
        resend.put("content", "sms content");

        Map<String, Object> request = helper.buildRequestBody("account", "@senderProfile", "templateCode", "Y", targets, resend);

        assertEquals("account", request.get("account"));
        assertEquals("ALT", request.get("messageType"));
        assertEquals("@senderProfile", request.get("senderProfile"));
        assertEquals("templateCode", request.get("templateCode"));
        assertEquals("Y", request.get("duplicateFlag"));
        assertEquals(1, request.get("targetCount"));
        assertEquals(targets, request.get("targets"));
        assertEquals("Y", request.get("isResend"));
        assertEquals(resend, request.get("resend"));
        assertNotNull(request.get("refKey"));
        assertEquals(32, String.valueOf(request.get("refKey")).length());
    }

    private Path createTempCertificatePath() throws IOException {
        Path certificateFile = Files.createTempFile("ppurio-pinned-certificate", ".pem");
        certificateFile.toFile().deleteOnExit();
        Files.deleteIfExists(certificateFile);
        return certificateFile;
    }

    private PpurioAlertDeliveryHelper helperWithPinnedCertificatePath(final String certificatePath) {
        return helperWithPinnedCertificatePathAndPem(certificatePath, null);
    }

    private PpurioAlertDeliveryHelper helperWithResendFrom(final String resendFrom) {
        return new PpurioAlertDeliveryHelper() {
            @Override
            protected String getConfiguredResendFrom() {
                return resendFrom;
            }
        };
    }

    private PpurioAlertDeliveryHelper helperWithResendSubjectTemplate(final String resendSubjectTemplate) {
        return new PpurioAlertDeliveryHelper() {
            @Override
            protected String getConfiguredResendSubjectTemplate() {
                return resendSubjectTemplate;
            }
        };
    }

    private PpurioAlertDeliveryHelper helperWithPinnedCertificatePathAndPem(final String certificatePath, final String certificatePem) {
        return new PpurioAlertDeliveryHelper() {
            @Override
            protected String getConfiguredPinnedCertificatePath() {
                return certificatePath;
            }

            @Override
            protected String getConfiguredPinnedCertificatePem() {
                return certificatePem;
            }
        };
    }
}
