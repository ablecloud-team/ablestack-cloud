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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.cloud.alert.AlertDeliveryHelper;
import com.cloud.alert.AlertManager;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ComponentContext;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PpurioAlertDeliveryHelper extends AdapterBase implements AlertDeliveryHelper, Configurable, ApplicationContextAware {
    private static final Logger logger = LogManager.getLogger(PpurioAlertDeliveryHelper.class);
    private static final Gson gson = new Gson();
    private static final String TOKEN_PATH = "/v1/token";
    private static final String KAKAO_PATH = "/v1/kakao";
    private static final String ALIMTALK_MESSAGE_TYPE = "ALT";
    private static final String RESEND_ENABLED_VALUE = "Y";
    private static final String RESEND_DISABLED_VALUE = "N";
    private static final String PPURIO_PINNED_INTERMEDIATE_ALIAS = "ppurio-sectigo-rsa-dv-ca";
    private static final String TRUNCATION_SUFFIX = "...";
    private static final String DEFAULT_PINNED_INTERMEDIATE_CERTIFICATE_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIGEzCCA/ugAwIBAgIQfVtRJrR2uhHbdBYLvFMNpzANBgkqhkiG9w0BAQwFADCB\n"
            + "iDELMAkGA1UEBhMCVVMxEzARBgNVBAgTCk5ldyBKZXJzZXkxFDASBgNVBAcTC0pl\n"
            + "cnNleSBDaXR5MR4wHAYDVQQKExVUaGUgVVNFUlRSVVNUIE5ldHdvcmsxLjAsBgNV\n"
            + "BAMTJVVTRVJUcnVzdCBSU0EgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkwHhcNMTgx\n"
            + "MTAyMDAwMDAwWhcNMzAxMjMxMjM1OTU5WjCBjzELMAkGA1UEBhMCR0IxGzAZBgNV\n"
            + "BAgTEkdyZWF0ZXIgTWFuY2hlc3RlcjEQMA4GA1UEBxMHU2FsZm9yZDEYMBYGA1UE\n"
            + "ChMPU2VjdGlnbyBMaW1pdGVkMTcwNQYDVQQDEy5TZWN0aWdvIFJTQSBEb21haW4g\n"
            + "VmFsaWRhdGlvbiBTZWN1cmUgU2VydmVyIENBMIIBIjANBgkqhkiG9w0BAQEFAAOC\n"
            + "AQ8AMIIBCgKCAQEA1nMz1tc8INAA0hdFuNY+B6I/x0HuMjDJsGz99J/LEpgPLT+N\n"
            + "TQEMgg8Xf2Iu6bhIefsWg06t1zIlk7cHv7lQP6lMw0Aq6Tn/2YHKHxYyQdqAJrkj\n"
            + "eocgHuP/IJo8lURvh3UGkEC0MpMWCRAIIz7S3YcPb11RFGoKacVPAXJpz9OTTG0E\n"
            + "oKMbgn6xmrntxZ7FN3ifmgg0+1YuWMQJDgZkW7w33PGfKGioVrCSo1yfu4iYCBsk\n"
            + "Haswha6vsC6eep3BwEIc4gLw6uBK0u+QDrTBQBbwb4VCSmT3pDCg/r8uoydajotY\n"
            + "uK3DGReEY+1vVv2Dy2A0xHS+5p3b4eTlygxfFQIDAQABo4IBbjCCAWowHwYDVR0j\n"
            + "BBgwFoAUU3m/WqorSs9UgOHYm8Cd8rIDZsswHQYDVR0OBBYEFI2MXsRUrYrhd+mb\n"
            + "+ZsF4bgBjWHhMA4GA1UdDwEB/wQEAwIBhjASBgNVHRMBAf8ECDAGAQH/AgEAMB0G\n"
            + "A1UdJQQWMBQGCCsGAQUFBwMBBggrBgEFBQcDAjAbBgNVHSAEFDASMAYGBFUdIAAw\n"
            + "CAYGZ4EMAQIBMFAGA1UdHwRJMEcwRaBDoEGGP2h0dHA6Ly9jcmwudXNlcnRydXN0\n"
            + "LmNvbS9VU0VSVHJ1c3RSU0FDZXJ0aWZpY2F0aW9uQXV0aG9yaXR5LmNybDB2Bggr\n"
            + "BgEFBQcBAQRqMGgwPwYIKwYBBQUHMAKGM2h0dHA6Ly9jcnQudXNlcnRydXN0LmNv\n"
            + "bS9VU0VSVHJ1c3RSU0FBZGRUcnVzdENBLmNydDAlBggrBgEFBQcwAYYZaHR0cDov\n"
            + "L29jc3AudXNlcnRydXN0LmNvbTANBgkqhkiG9w0BAQwFAAOCAgEAMr9hvQ5Iw0/H\n"
            + "ukdN+Jx4GQHcEx2Ab/zDcLRSmjEzmldS+zGea6TvVKqJjUAXaPgREHzSyrHxVYbH\n"
            + "7rM2kYb2OVG/Rr8PoLq0935JxCo2F57kaDl6r5ROVm+yezu/Coa9zcV3HAO4OLGi\n"
            + "H19+24rcRki2aArPsrW04jTkZ6k4Zgle0rj8nSg6F0AnwnJOKf0hPHzPE/uWLMUx\n"
            + "RP0T7dWbqWlod3zu4f+k+TY4CFM5ooQ0nBnzvg6s1SQ36yOoeNDT5++SR2RiOSLv\n"
            + "xvcRviKFxmZEJCaOEDKNyJOuB56DPi/Z+fVGjmO+wea03KbNIaiGCpXZLoUmGv38\n"
            + "sbZXQm2V0TP2ORQGgkE49Y9Y3IBbpNV9lXj9p5v//cWoaasm56ekBYdbqbe4oyAL\n"
            + "l6lFhd2zi+WJN44pDfwGF/Y4QA5C5BIG+3vzxhFoYt/jmPQT2BVPi7Fp2RBgvGQq\n"
            + "6jG35LWjOhSbJuMLe/0CjraZwTiXWTb2qHSihrZe68Zk6s+go/lunrotEbaGmAhY\n"
            + "LcmsJWTyXnW0OMGuf1pGg+pRyrbxmRE1a6Vqe8YAsOf4vmSyrcjC8azjUeqkk+B5\n"
            + "yOGBQMkKW+ESPMFgKuOXwIlCypTPRpgSabuY0MLTDXJLR27lk8QyKGOHQ+SwMj4K\n"
            + "00u/I5sUKUErmgQfky3xxzlIPK1aEn8=\n"
            + "-----END CERTIFICATE-----\n";
    private static final int PPURIO_CHANGE_WORD_MAX_BYTES = 100;
    private static final int PPURIO_CHANGE_WORD_MAX_CHARS = 50;
    private static final int PPURIO_RESEND_SUBJECT_MAX_BYTES = 30;
    private static final int PPURIO_RESEND_CONTENT_MAX_BYTES = 90;
    private static final int REF_KEY_LENGTH = 32;
    private static final char[] REF_KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Object sslSocketFactoryLock = new Object();

    private static volatile SSLSocketFactory ppurioPinnedSslSocketFactory;
    private static volatile String ppurioPinnedCertificateSource;

    private ApplicationContext applicationContext;

    @Override
    public boolean start() {
        if (PpurioAlertConfigKeys.ALERT_KAKAO_ENABLED.value()) {
            try {
                ensurePinnedCertificateFile();
            } catch (IOException e) {
                logger.warn("Failed to prepare Ppurio pinned intermediate certificate file", e);
            }
        }
        if (applicationContext == null) {
            logger.warn("Unable to register Ppurio AlertDeliveryHelper delegate context because application context is null");
            return true;
        }
        ComponentContext.addDelegateContext(AlertDeliveryHelper.class, applicationContext);
        logger.info("Registered Ppurio AlertDeliveryHelper delegate context");
        return true;
    }

    @Override
    public boolean stop() {
        ComponentContext.removeDelegateContext(AlertDeliveryHelper.class);
        return true;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void sendAlert(AlertManager.AlertType alertType, long dataCenterId, Long podId, Long clusterId, String subject, String body) {
        // logger.debug(String.format("Received Ppurio Kakao alert delivery request [alertType=%s, dataCenterId=%s, podId=%s, clusterId=%s, subject=%s].", alertType, dataCenterId, podId, clusterId, subject));
        if (!PpurioAlertConfigKeys.ALERT_KAKAO_ENABLED.value()) {
            //logger.info(String.format("Skipped Ppurio Kakao alert delivery because kakao.ppurio.enabled is false [alertType=%s, dataCenterId=%s, podId=%s, clusterId=%s, subject=%s].", alertType, dataCenterId, podId, clusterId, subject));
            return;
        }

        List<String> recipients = parseRecipients(PpurioAlertConfigKeys.RECIPIENTS.value());
        if (recipients.isEmpty()) {
            logger.warn("Ppurio Alert integration is enabled but no recipients are configured in kakao.ppurio.recipients");
            return;
        }

        String baseUrl = PpurioAlertConfigKeys.BASE_URL.value();
        String account = PpurioAlertConfigKeys.ACCOUNT.value();
        String apiKey = PpurioAlertConfigKeys.API_KEY.value();
        String senderProfile = PpurioAlertConfigKeys.SENDER_PROFILE.value();
        String templateCode = PpurioAlertConfigKeys.TEMPLATE_CODE.value();
        String duplicateFlag = PpurioAlertConfigKeys.DUPLICATE_FLAG.value();

        if (StringUtils.isAnyBlank(baseUrl, account, apiKey, senderProfile, templateCode)) {
            logger.warn("Ppurio Alert integration is enabled but one or more required settings are blank");
            return;
        }

        List<Map<String, Object>> targets = buildTargets(recipients, buildChangeWord(alertType, subject, body));
        Map<String, String> resend = buildResendIfEnabled(alertType, subject, body);
        try {
            logPreparedMessage(baseUrl, account, senderProfile, templateCode, recipients.size());
            sendMessage(baseUrl, account, apiKey, senderProfile, templateCode, duplicateFlag, targets, resend);
        } catch (IOException e) {
            logger.warn(String.format("Failed to send Ppurio Kakao alert for subject [%s]", subject), e);
        }
    }

    protected void sendMessage(String baseUrl, String account, String apiKey, String senderProfile, String templateCode,
            String duplicateFlag, List<Map<String, Object>> targets, Map<String, String> resend) throws IOException {
        String token = getToken(baseUrl, account, apiKey);
        Map<String, Object> requestBody = buildRequestBody(account, senderProfile, templateCode, duplicateFlag, targets, resend);
        String responseBody = postJson(buildEndpoint(baseUrl, KAKAO_PATH), "Bearer " + token, requestBody);
        logger.info(String.format("Ppurio Kakao alert request accepted [refKey=%s, response=%s]",
                requestBody.get("refKey"), responseBody));
    }

    protected String getToken(String baseUrl, String account, String apiKey) throws IOException {
        String responseBody = postJson(buildEndpoint(baseUrl, TOKEN_PATH), buildAuthorizationHeader(account, apiKey), null);
        String token = parseToken(responseBody);
        if (StringUtils.isBlank(token)) {
            throw new IOException(String.format("Ppurio token response does not contain token: [%s]", responseBody));
        }
        return token;
    }

    protected String parseToken(String responseBody) throws IOException {
        try {
            JsonElement jsonElement = new JsonParser().parse(responseBody);
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                throw new IOException(String.format("Ppurio token response is not a JSON object: [%s]", responseBody));
            }
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            JsonElement tokenElement = jsonObject.get("token");
            if (tokenElement == null || tokenElement.isJsonNull()) {
                return null;
            }
            return tokenElement.getAsString();
        } catch (RuntimeException e) {
            throw new IOException(String.format("Failed to parse Ppurio token response: [%s]", responseBody), e);
        }
    }

    protected String postJson(String endpointUrl, String authorizationHeader, Object requestBody) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpointUrl);
            connection = (HttpURLConnection) url.openConnection();
            configureHttpsConnection(connection);
            connection.setConnectTimeout(PpurioAlertConfigKeys.CONNECT_TIMEOUT_MS.value());
            connection.setReadTimeout(PpurioAlertConfigKeys.READ_TIMEOUT_MS.value());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", authorizationHeader);

            byte[] payloadBytes = requestBody == null ? new byte[0] : gson.toJson(requestBody).getBytes(StandardCharsets.UTF_8);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(payloadBytes);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                String responseBody = readResponse(connection);
                throw new IOException(String.format("Unexpected Ppurio response code [%s], response [%s]", responseCode, responseBody));
            }
            return readResponse(connection);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    protected void configureHttpsConnection(HttpURLConnection connection) throws IOException {
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection)connection).setSSLSocketFactory(getPpurioPinnedSslSocketFactory());
        }
    }

    protected SSLSocketFactory getPpurioPinnedSslSocketFactory() throws IOException {
        String certificatePath = getEffectivePinnedCertificatePath(getConfiguredPinnedCertificatePath());
        ensurePinnedCertificateFile(certificatePath);
        String certificateSource = getPinnedCertificateSource(certificatePath);
        if (ppurioPinnedSslSocketFactory == null || !StringUtils.equals(certificateSource, ppurioPinnedCertificateSource)) {
            synchronized (sslSocketFactoryLock) {
                if (ppurioPinnedSslSocketFactory == null || !StringUtils.equals(certificateSource, ppurioPinnedCertificateSource)) {
                    SSLSocketFactory sslSocketFactory = buildPpurioPinnedSslSocketFactory();
                    ppurioPinnedSslSocketFactory = sslSocketFactory;
                    ppurioPinnedCertificateSource = certificateSource;
                }
            }
        }
        return ppurioPinnedSslSocketFactory;
    }

    protected SSLSocketFactory buildPpurioPinnedSslSocketFactory() throws IOException {
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate pinnedIntermediate = loadPinnedIntermediateCertificate(certificateFactory);
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry(PPURIO_PINNED_INTERMEDIATE_ALIAS, pinnedIntermediate);

            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            return sslContext.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to initialize Ppurio pinned TLS trust store", e);
        }
    }

    protected Certificate loadPinnedIntermediateCertificate(CertificateFactory certificateFactory) throws IOException, GeneralSecurityException {
        return loadPinnedIntermediateCertificate(certificateFactory, getEffectivePinnedCertificatePath(getConfiguredPinnedCertificatePath()));
    }

    protected Certificate loadPinnedIntermediateCertificate(CertificateFactory certificateFactory, String certificatePath) throws IOException, GeneralSecurityException {
        String effectiveCertificatePath = getEffectivePinnedCertificatePath(certificatePath);
        ensurePinnedCertificateFile(effectiveCertificatePath);
        try (InputStream inputStream = openPinnedIntermediateCertificateInputStream(effectiveCertificatePath)) {
            return certificateFactory.generateCertificate(inputStream);
        }
    }

    protected InputStream openPinnedIntermediateCertificateInputStream(String certificatePath) throws IOException {
        return Files.newInputStream(Paths.get(getEffectivePinnedCertificatePath(certificatePath)));
    }

    protected String getPinnedCertificateSource(String certificatePath) throws IOException {
        String effectiveCertificatePath = getEffectivePinnedCertificatePath(certificatePath);
        return effectiveCertificatePath + "#" + Files.getLastModifiedTime(Paths.get(effectiveCertificatePath)).toMillis();
    }

    protected void ensurePinnedCertificateFile() throws IOException {
        ensurePinnedCertificateFile(getEffectivePinnedCertificatePath(getConfiguredPinnedCertificatePath()));
    }

    protected void ensurePinnedCertificateFile(String certificatePath) throws IOException {
        String effectiveCertificatePath = getEffectivePinnedCertificatePath(certificatePath);
        Path path = Paths.get(effectiveCertificatePath);
        String configuredCertificatePem = getConfiguredPinnedCertificatePem();
        boolean hasConfiguredCertificatePem = StringUtils.isNotBlank(configuredCertificatePem);
        if (Files.exists(path) && isPinnedCertificateFileUsable(path) && !hasConfiguredCertificatePem) {
            return;
        }

        String certificatePem = getPinnedCertificatePemForFile();
        if (hasConfiguredCertificatePem && Files.exists(path) &&
                StringUtils.equals(normalizePinnedCertificatePem(new String(Files.readAllBytes(path), StandardCharsets.US_ASCII)), certificatePem)) {
            return;
        }
        validatePinnedCertificatePem(certificatePem);

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, certificatePem.getBytes(StandardCharsets.US_ASCII));
        logger.info(String.format("Created or updated Ppurio pinned intermediate certificate file [%s]", effectiveCertificatePath));
    }

    protected boolean isPinnedCertificateFileUsable(Path path) throws IOException {
        if (Files.size(path) == 0) {
            logger.warn(String.format("Ppurio pinned intermediate certificate file [%s] is empty and will be recreated", path));
            return false;
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
            return true;
        } catch (GeneralSecurityException e) {
            logger.warn(String.format("Ppurio pinned intermediate certificate file [%s] is not a valid X.509 certificate and will be recreated", path), e);
            return false;
        }
    }

    protected String getEffectivePinnedCertificatePath(String certificatePath) {
        if (StringUtils.isNotBlank(certificatePath)) {
            return StringUtils.trim(certificatePath);
        }
        return PpurioAlertConfigKeys.DEFAULT_PINNED_CERTIFICATE_PATH;
    }

    protected String getConfiguredPinnedCertificatePath() {
        return PpurioAlertConfigKeys.PINNED_CERTIFICATE_PATH.value();
    }

    protected String getConfiguredPinnedCertificatePem() {
        return PpurioAlertConfigKeys.PINNED_CERTIFICATE_PEM.value();
    }

    protected String getPinnedCertificatePemForFile() {
        String configuredCertificatePem = getConfiguredPinnedCertificatePem();
        if (StringUtils.isNotBlank(configuredCertificatePem)) {
            return normalizePinnedCertificatePem(configuredCertificatePem);
        }
        return getDefaultPinnedIntermediateCertificatePem();
    }

    protected String normalizePinnedCertificatePem(String certificatePem) {
        return StringUtils.replace(StringUtils.trim(certificatePem), "\\n", "\n") + "\n";
    }

    protected void validatePinnedCertificatePem(String certificatePem) throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII))) {
            CertificateFactory.getInstance("X.509").generateCertificate(inputStream);
        } catch (GeneralSecurityException e) {
            throw new IOException("Configured Ppurio pinned intermediate certificate PEM is not a valid X.509 certificate", e);
        }
    }

    protected String getDefaultPinnedIntermediateCertificatePem() {
        return DEFAULT_PINNED_INTERMEDIATE_CERTIFICATE_PEM;
    }

    protected Map<String, Object> buildRequestBody(String account, String senderProfile, String templateCode,
            String duplicateFlag, List<Map<String, Object>> targets, Map<String, String> resend) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("account", account);
        root.put("messageType", ALIMTALK_MESSAGE_TYPE);
        root.put("senderProfile", senderProfile);
        root.put("templateCode", templateCode);
        root.put("duplicateFlag", StringUtils.defaultIfBlank(duplicateFlag, "Y"));
        root.put("targetCount", targets.size());
        root.put("targets", targets);
        root.put("isResend", resend == null ? RESEND_DISABLED_VALUE : RESEND_ENABLED_VALUE);
        if (resend != null) {
            root.put("resend", resend);
        }
        root.put("refKey", makeRefKey());
        return root;
    }

    protected List<Map<String, Object>> buildTargets(List<String> recipients, Map<String, String> changeWord) {
        List<Map<String, Object>> targets = new ArrayList<>();
        String targetName = PpurioAlertConfigKeys.TARGET_NAME.value();
        for (String recipient : recipients) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("to", recipient);
            if (StringUtils.isNotBlank(targetName)) {
                target.put("name", targetName);
            }
            target.put("changeWord", new LinkedHashMap<>(changeWord));
            targets.add(target);
        }
        return targets;
    }

    protected Map<String, String> buildChangeWord(AlertManager.AlertType alertType, String subject, String body) {
        Map<String, String> changeWord = new LinkedHashMap<>();
        changeWord.put("var1", renderChangeWordValue(PpurioAlertConfigKeys.CHANGE_WORD_VAR1.value(), alertType, subject, true));
        changeWord.put("var2", renderChangeWordValue(PpurioAlertConfigKeys.CHANGE_WORD_VAR2.value(), alertType, subject));
        return changeWord;
    }

    protected String renderChangeWordValue(String template, AlertManager.AlertType alertType, String subject) {
        return truncateUtf8Bytes(renderTemplate(template, alertType, subject), PPURIO_CHANGE_WORD_MAX_BYTES);
    }

    protected String renderChangeWordValue(String template, AlertManager.AlertType alertType, String subject, boolean appendTruncationSuffix) {
        String value = renderTemplate(template, alertType, subject);
        if (appendTruncationSuffix) {
            return truncateUtf8BytesAndCharsWithSuffix(value, PPURIO_CHANGE_WORD_MAX_BYTES,
                    PPURIO_CHANGE_WORD_MAX_CHARS, TRUNCATION_SUFFIX);
        }
        return truncateUtf8Bytes(value, PPURIO_CHANGE_WORD_MAX_BYTES);
    }

    protected Map<String, String> buildResend(AlertManager.AlertType alertType, String subject, String body) {
        Map<String, String> resend = new LinkedHashMap<>();
        resend.put("messageType", PpurioAlertConfigKeys.RESEND_MESSAGE_TYPE.value());
        resend.put("from", normalizePhoneNumber(getConfiguredResendFrom()));
        resend.put("subject", renderResendSubject(alertType, subject));
        resend.put("content", renderResendContent(alertType, subject));
        return resend;
    }

    protected Map<String, String> buildResendIfEnabled(AlertManager.AlertType alertType, String subject, String body) {
        if (!PpurioAlertConfigKeys.RESEND_ENABLED.value()) {
            return null;
        }

        String resendFrom = normalizePhoneNumber(getConfiguredResendFrom());
        if (!isValidPhoneNumber(resendFrom)) {
            logger.warn("Ppurio Alert integration resend is enabled but kakao.ppurio.resend.from is not a valid phone number; sending Kakao alert without resend fallback");
            return null;
        }
        return buildResend(alertType, subject, body);
    }

    protected String normalizePhoneNumber(String phoneNumber) {
        return StringUtils.defaultString(phoneNumber).replaceAll("[^0-9]", "");
    }

    protected boolean isValidPhoneNumber(String phoneNumber) {
        return StringUtils.isNotBlank(phoneNumber) && phoneNumber.matches("\\d{8,11}");
    }

    protected String getConfiguredResendFrom() {
        return PpurioAlertConfigKeys.RESEND_FROM.value();
    }

    protected String getConfiguredResendSubjectTemplate() {
        return PpurioAlertConfigKeys.RESEND_SUBJECT_TEMPLATE.value();
    }

    protected String renderResendSubject(AlertManager.AlertType alertType, String subject) {
        return truncateUtf8Bytes(renderTemplate(getConfiguredResendSubjectTemplate(), alertType, subject), PPURIO_RESEND_SUBJECT_MAX_BYTES);
    }

    protected String renderResendContent(AlertManager.AlertType alertType, String subject) {
        return truncateUtf8Bytes(renderTemplate(PpurioAlertConfigKeys.RESEND_CONTENT_TEMPLATE.value(), alertType, subject), PPURIO_RESEND_CONTENT_MAX_BYTES);
    }

    protected String renderTemplate(String template, AlertManager.AlertType alertType, String subject) {
        return StringUtils.defaultString(template)
                .replace("${alertType}", safeValue(alertType == null ? null : alertType.getName()))
                .replace("${subject}", safeValue(subject));
    }

    protected String truncateUtf8Bytes(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }

        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            builder.append(character);
            bytes += characterBytes;
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    protected String truncateUtf8BytesWithSuffix(String value, int maxBytes, String suffix) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }

        String safeSuffix = StringUtils.defaultString(suffix);
        int suffixBytes = safeSuffix.getBytes(StandardCharsets.UTF_8).length;
        if (suffixBytes >= maxBytes) {
            return truncateUtf8Bytes(safeSuffix, maxBytes);
        }

        return truncateUtf8Bytes(value, maxBytes - suffixBytes) + safeSuffix;
    }

    protected String truncateUtf8BytesAndCharsWithSuffix(String value, int maxBytes, int maxChars,
            String suffix) {
        if (value == null || (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes &&
                getCodePointCount(value) <= maxChars)) {
            return value;
        }

        String safeSuffix = StringUtils.defaultString(suffix);
        int suffixBytes = safeSuffix.getBytes(StandardCharsets.UTF_8).length;
        int suffixChars = getCodePointCount(safeSuffix);
        if (suffixBytes >= maxBytes || suffixChars >= maxChars) {
            return truncateUtf8Bytes(safeSuffix, maxBytes);
        }

        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        int chars = 0;
        int maxValueBytes = maxBytes - suffixBytes;
        int maxValueChars = maxChars - suffixChars;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxValueBytes || chars + 1 > maxValueChars) {
                break;
            }
            builder.append(character);
            bytes += characterBytes;
            chars++;
            i += Character.charCount(codePoint);
        }
        return builder.toString() + safeSuffix;
    }

    protected int getCodePointCount(String value) {
        String safeValue = StringUtils.defaultString(value);
        return safeValue.codePointCount(0, safeValue.length());
    }

    protected String buildAuthorizationHeader(String account, String apiKey) {
        String token = account + ":" + apiKey;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    protected List<String> parseRecipients(String recipientsConfig) {
        List<String> recipients = new ArrayList<>();
        if (StringUtils.isBlank(recipientsConfig)) {
            return recipients;
        }
        for (String recipient : recipientsConfig.split(",")) {
            String trimmed = StringUtils.trim(recipient);
            if (StringUtils.isNotBlank(trimmed)) {
                recipients.add(trimmed);
            }
        }
        return recipients;
    }

    protected String readResponse(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream inputStream = stream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    protected String safeValue(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    protected String buildEndpoint(String baseUrl, String path) {
        return StringUtils.removeEnd(StringUtils.trim(baseUrl), "/") + path;
    }

    protected String makeRefKey() {
        StringBuilder builder = new StringBuilder(REF_KEY_LENGTH);
        for (int i = 0; i < REF_KEY_LENGTH; i++) {
            builder.append(REF_KEY_CHARS[secureRandom.nextInt(REF_KEY_CHARS.length)]);
        }
        return builder.toString();
    }

    protected void logPreparedMessage(String baseUrl, String account, String senderProfile, String templateCode, int targetCount) {
        logger.info(String.format(
                "Prepared Ppurio Kakao alert request [baseUrl=%s, account=%s, senderProfile=%s, templateCode=%s, targetCount=%s]",
                baseUrl,
                maskValue(account),
                maskValue(senderProfile),
                templateCode,
                targetCount));
    }

    protected String maskValue(String value) {
        if (StringUtils.isBlank(value)) {
            return "-";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    @Override
    public String getConfigComponentName() {
        return AlertDeliveryHelper.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                PpurioAlertConfigKeys.ALERT_KAKAO_ENABLED,
                PpurioAlertConfigKeys.BASE_URL,
                PpurioAlertConfigKeys.PINNED_CERTIFICATE_PATH,
                PpurioAlertConfigKeys.PINNED_CERTIFICATE_PEM,
                PpurioAlertConfigKeys.ACCOUNT,
                PpurioAlertConfigKeys.API_KEY,
                PpurioAlertConfigKeys.SENDER_PROFILE,
                PpurioAlertConfigKeys.TEMPLATE_CODE,
                PpurioAlertConfigKeys.TARGET_NAME,
                PpurioAlertConfigKeys.DUPLICATE_FLAG,
                PpurioAlertConfigKeys.RECIPIENTS,
                PpurioAlertConfigKeys.CHANGE_WORD_VAR1,
                PpurioAlertConfigKeys.CHANGE_WORD_VAR2,
                PpurioAlertConfigKeys.RESEND_ENABLED,
                PpurioAlertConfigKeys.RESEND_MESSAGE_TYPE,
                PpurioAlertConfigKeys.RESEND_FROM,
                PpurioAlertConfigKeys.RESEND_SUBJECT_TEMPLATE,
                PpurioAlertConfigKeys.RESEND_CONTENT_TEMPLATE,
                PpurioAlertConfigKeys.CONNECT_TIMEOUT_MS,
                PpurioAlertConfigKeys.READ_TIMEOUT_MS
        };
    }
}
