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

package org.apache.cloudstack.backup.commvault;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

public class AblestackCommvaultClient {
    private static final Logger LOG = LogManager.getLogger(AblestackCommvaultClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int HTTP_CONNECT_TIMEOUT_MS = 10000;
    private static final int HTTP_READ_TIMEOUT_MS = 180000;
    private final URI apiURI;
    private final String apiName;
    private final String apiPassword;
    private final HttpClient httpClient;
    private String accessToken = null;
    private String cvtServerIp;
    private String cvtServerUsername;
    private String cvtServerPassword;
    private final int cvtServerPort = 22;
    // Backward compatibility only. New code should use JobStatusResult directly.
    private final ThreadLocal<String> lastJobFailureReason = new ThreadLocal<>();

    public AblestackCommvaultClient(final String url, final String username, final String password, final boolean validateCertificate, final int timeout) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {

        apiName = username;
        apiPassword = password;

        this.apiURI = new URI(url);
        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000)
                .setSocketTimeout(timeout * 1000)
                .build();

        if (!validateCertificate) {
            final SSLContext sslcontext = SSLUtils.getSSLContext();
            sslcontext.init(null, new X509TrustManager[]{new TrustAllManager()}, new SecureRandom());
            final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslcontext, NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .setSSLSocketFactory(factory)
                    .build();
        } else {
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .build();
        }

        authenticate(username, password);
        setCvtSshCredentials(this.apiURI.getHost(), username, password);
    }

    protected void setCvtSshCredentials(String hostIp, String username, String password) {
        this.cvtServerIp = hostIp;
        this.cvtServerUsername = username;
        this.cvtServerPassword = password;
    }

    private HttpJsonResponse executeJsonRequest(final String method, final String path, final JsonNode requestBody) throws IOException {
        return executeJsonRequest(method, path, requestBody, true);
    }

    private HttpJsonResponse executeJsonRequest(final String method, final String path, final JsonNode requestBody,
                                                final boolean authenticated) throws IOException {
        HttpURLConnection connection = null;
        final String requestUrl = apiURI.toString() + path;
        try {
            final URL url = new URL(requestUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);

            if (authenticated && StringUtils.isNotBlank(accessToken)) {
                connection.setRequestProperty("Authtoken", accessToken);
            }

            if (requestBody != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(OBJECT_MAPPER.writeValueAsBytes(requestBody));
                }
            }

            final int responseCode = connection.getResponseCode();
            final String responseBody = readConnectionBody(connection, responseCode);
            LOG.debug("Response received in {} request. statusCode=[{}], URL=[{}].", method, responseCode, requestUrl);
            return new HttpJsonResponse(responseCode, responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readConnectionBody(final HttpURLConnection connection, final int responseCode) throws IOException {
        final java.io.InputStream inputStream = responseCode >= 200 && responseCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (inputStream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            final StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    private static final class HttpJsonResponse {
        private final int statusCode;
        private final String body;

        private HttpJsonResponse(final int statusCode, final String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        private boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private void authenticate(final String username, final String password) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.put("username", username);
            requestBody.put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));

            final HttpJsonResponse response = executeJsonRequest("POST", "/Login", requestBody, false);
            if (response.statusCode != HttpURLConnection.HTTP_OK) {
                throw new CloudRuntimeException("Failed to authenticate Commvault API client. HTTP status=" + response.statusCode
                        + ", responseBody=" + response.body);
            }

            final JsonNode root = OBJECT_MAPPER.readTree(response.body);
            final JsonNode tokenNode = root.get("token");
            if (tokenNode != null && !tokenNode.asText().isBlank()) {
                accessToken = tokenNode.asText();
                return;
            }

            throw new CloudRuntimeException("Commvault login succeeded but token was not returned.");
        } catch (final IOException e) {
            throw new CloudRuntimeException("Failed to authenticate Commvault API service due to: " + e.getMessage(), e);
        }
    }

    private void checkAuthFailure(final HttpResponse response) {
        if (response != null && response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, "Commvault API call unauthorized. Check username/password or contact your backup administrator.");
        }
    }

    private void checkResponseOK(final HttpResponse response) {
        if (response.getStatusLine().getStatusCode() == HttpStatus.SC_NO_CONTENT) {
            LOG.debug("Requested Commvault resource does not exist");
            return;
        }
        if (!(response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ||
                response.getStatusLine().getStatusCode() == HttpStatus.SC_ACCEPTED) &&
                response.getStatusLine().getStatusCode() != HttpStatus.SC_NO_CONTENT) {
            String responseBody = getResponseBody(response);
            LOG.debug(String.format("HTTP request failed, status code is [%s], response is: [%s], response body is: [%s].",
                    response.getStatusLine().getStatusCode(), response, responseBody));
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format(
                    "Got invalid API status code returned by the Commvault server. statusCode=%s, responseBody=%s",
                    response.getStatusLine().getStatusCode(), responseBody));
        }
    }

    private String getResponseBody(final HttpResponse response) {
        if (response == null || response.getEntity() == null) {
            return "";
        }
        try {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOG.warn("Failed to read Commvault API response body", e);
            return "";
        }
    }

    private void checkResponseTimeOut(final Exception e) {
        if (e instanceof ConnectTimeoutException || e instanceof SocketTimeoutException) {
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, "Commvault API operation timed out, please try again.");
        }
    }

    private HttpResponse get(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpGet request = new HttpGet(url);
        request.setHeader("Authtoken", accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        final HttpResponse response = httpClient.execute(request);
        checkAuthFailure(response);

        LOG.debug("Response received in GET request. statusCode=[{}], URL=[{}].", response.getStatusLine().getStatusCode(), url);
        return response;
    }

    private HttpResponse delete(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpDelete request = new HttpDelete(url);
        request.setHeader("Authtoken", accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        final HttpResponse response = httpClient.execute(request);
        checkAuthFailure(response);

        LOG.debug("Response received in DELETE request. statusCode=[{}], URL=[{}].", response.getStatusLine().getStatusCode(), url);
        return response;
    }

    // GET https://<commserveIp>/commandcenter/api/Client
    // client에 호스트가 연결되어있는지 확인하는 API로 호스트가 없는 경우 null, 있는 경우 clientId 반환
    public String getClientId(String hostName) {
        if (StringUtils.isBlank(hostName)) {
            return null;
        }

        try {
            final HttpResponse response = get("/Client");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode clientProperties = root.path("clientProperties");
            if (!clientProperties.isArray()) {
                return null;
            }

            for (JsonNode clientProperty : clientProperties) {
                JsonNode clientEntity = clientProperty.path("client").path("clientEntity");
                String clientName = clientEntity.path("clientName").asText(null);
                String clientId = clientEntity.path("clientId").asText(null);

                if (StringUtils.equalsIgnoreCase(hostName, clientName)
                        && StringUtils.isNotBlank(clientId)
                        && isClientEntityAvailable(clientId)) {
                    return clientId;
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getClientId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // Retire된 Client가 /Client 목록에는 남아 있어도 /Client/<clientId> 상세 조회에서는
    // errorCode=4로 반환될 수 있으므로 실제로 유효한 Client entity인지 확인한다.
    private boolean isClientEntityAvailable(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            return false;
        }

        try {
            final HttpResponse response = get("/Client/" + clientId);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK || response.getEntity() == null) {
                return false;
            }

            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);

            if (root.path("errorCode").asInt(0) != 0) {
                return false;
            }

            JsonNode clientProperties = root.path("clientProperties");
            return clientProperties.isArray() && !clientProperties.isEmpty();
        } catch (final IOException e) {
            LOG.error("Failed to validate Commvault client entity [{}]: ", clientId, e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Plan
    // plan 조회하는 API로 없는 경우 빈 배열, 있는 경우 plan 명, plan id 반환
    public List<BackupOffering> listPlans() {
        final List<BackupOffering> offerings = new ArrayList<>();
        try {
            final HttpResponse response = get("/Plan");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode plans = root.path("plans");
            if (plans.isArray()) {
                for (JsonNode planNode : plans) {
                    JsonNode planDetails = planNode.path("plan");
                    if (!planDetails.isMissingNode()) {
                        String planId = planDetails.path("planId").asText();
                        String planName = planDetails.path("planName").asText();
                        offerings.add(new AblestackCommvaultBackupOffering(planName, planId));
                    }
                }
            }
            return offerings;
        } catch (final IOException e) {
            LOG.error("Failed to request listPlans commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return offerings;
    }

    // GET https://<commserveIp>/commandcenter/api/v2/Plan/<planId>
    // plan 상세 조회하는 API로 없는 경우 null, type이 deleteRpo인 경우 값이 있는 경우 schedule task id 반환, type이 updateRpo인 경우 plan 반환
    public String getScheduleTaskId(String type, String planId) {
        try {
            final HttpResponse response = get("/v2/Plan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode planNode = root.path("plan");
            if (type.equals("deleteRpo")) {
                JsonNode scheduleTaskIdNode = planNode.path("schedule").path("task").path("taskId");
                if (!scheduleTaskIdNode.isMissingNode()) {
                    return scheduleTaskIdNode.asText();
                }
            } else {
                JsonNode plan = planNode.path("summary").path("plan");
                return plan.toString();
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getScheduleTaskId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIp>/commandcenter/api/schedulepolicy/<taskId>
    // 스케줄 정책 조회하는 API로 없는 경우 null, 있는 경우 subtaskid 반환
    public String getSubTaskId(String taskId) {
        try {
            final HttpResponse response = get("/schedulepolicy/" + taskId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode subTaskIdNode = root.path("taskInfo").path("subTasks");
            if (!subTaskIdNode.isArray() || subTaskIdNode.isEmpty()) {
                return null;
            }
            return subTaskIdNode.get(0).path("subTask").path("subTaskId").asText(null);
        } catch (final IOException e) {
            LOG.error("Failed to request getSubTaskId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // DELETE https://<commserveIp>/commandcenter/api/schedulepolicy/<taskId>/schedule/<subTaskId>
    // 스케줄 정책 조회하여 스케줄 삭제
    public Boolean deleteSchedulePolicy(String taskId, String subTaskId) {
        try {
            final HttpResponse response = delete("/schedulepolicy/" + taskId + "/schedule/" + subTaskId);
            checkResponseOK(response);
            return true;
        } catch (final IOException e) {
            LOG.error("Failed to request deleteSchedulePolicy commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/V2/StoragePolicy
    // storagePolicy 조회하는 API로 없는 경우 null, 있는 경우 storagePolicyId 반환
    public String getStoragePolicyId(String planName) {
        try {
            final HttpResponse response = get("/V2/StoragePolicy");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode policies = root.path("policies");
            if (policies.isArray()) {
                for (JsonNode policy : policies) {
                    JsonNode storagePolicy = policy.path("storagePolicy");
                    JsonNode storagePolicyName = storagePolicy.path("storagePolicyName");
                    JsonNode storagePolicyId = storagePolicy.path("storagePolicyId");
                    if (!storagePolicyId.isMissingNode() && planName.equals(storagePolicyName.asText())) {
                        return storagePolicyId.asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getStoragePolicyId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIp>/commandcenter/api/V4/ServerPlan/<planId>
    // plan에 연결된 Primary Backup Destination ID를 조회하는 API로 없는 경우 null, 있는 경우 Primary Backup Destination ID 목록 반환
    public List<String> getPrimaryBackupDestinationIds(String planId) {
        try {
            final HttpResponse response = get("/V4/ServerPlan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode primaryIds = root.path("backupDestinationIds");
            if (!primaryIds.isArray() || primaryIds.isEmpty()) {
                return null;
            }
            final List<String> ids = new ArrayList<>();
            for (JsonNode idNode : primaryIds) {
                if (idNode == null || idNode.isNull()) {
                    return null;
                }
                String id = idNode.asText();
                if (id == null || id.isEmpty()) {
                    return null;
                }
                ids.add(id);
            }
            if (ids.isEmpty()) {
                return null;
            }
            return ids;
        } catch (final IOException e) {
            LOG.error("Failed to request primary backup destination from Commvault API: ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIp>/commandcenter/api/V4/ServerPlan/<planId>
    // plan에 연결된 Primary Backup Destination ID를 조회하여 retention period 변경
    public boolean updatePrimaryBackupDestinationRetention(String planId, String retentionPeriod) {
        List<String> backupDestinationIds = getPrimaryBackupDestinationIds(planId);
        if (backupDestinationIds == null || backupDestinationIds.isEmpty()) {
            return false;
        }
        for (String backupDestinationId : backupDestinationIds) {
            boolean result = updateRetentionPeriod(planId,backupDestinationId,retentionPeriod);
            if (!result) {
                return false;
            }
        }
        return true;
    }

    // 기존 호출부 호환성을 위해 유지한다.
    // 11.44에서는 StoragePolicy copyId와 ServerPlan BackupDestination ID가 서로 다른 식별자이므로
    // storagePolicy copyId를 V5 BackupDestination API에 전달하지 않고 검증된 Plan 기반 경로를 사용한다.
    @Deprecated
    public boolean getStoragePolicyDetails(String planId, String storagePolicyId, String retentionPeriod) {
        return updatePrimaryBackupDestinationRetention(planId, retentionPeriod);
    }

    // GET https://<commserveIp>/commandcenter/api/V4/ServerPlan/<planId>
    // Primary Backup Destination의 retention period를 조회하는 API로 없는 경우 null, 있는 경우 retentionPeriodDays 반환
    public String getPrimaryBackupDestinationRetention(String planId) {
        try {
            final HttpResponse response = get("/V4/ServerPlan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode primaryIds = root.path("backupDestinationIds");
            JsonNode destinations = root.path("backupDestinations");

            if (!primaryIds.isArray() || primaryIds.isEmpty() || !destinations.isArray()) {
                return null;
            }

            String primaryId = primaryIds.get(0).asText();
            for (JsonNode destination : destinations) {
                String destinationId = destination.path("planBackupDestination").path("id").asText();
                if (primaryId.equals(destinationId)) {
                    JsonNode retentionPeriodDays = destination.path("retentionPeriodDays");
                    if (!retentionPeriodDays.isMissingNode() && !retentionPeriodDays.isNull()) {
                        return retentionPeriodDays.asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getPrimaryBackupDestinationRetention commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // PUT https://<commserveIp>/commandcenter/api/V5/ServerPlan/<planId>/BackupDestination/<backupDestinationId>
    // Primary Backup Destination의 retention period를 변경하는 API
    public boolean updateRetentionPeriod(String planId, String backupDestinationId, String retentionPeriod) {
        final String path = "/V5/ServerPlan/" + planId + "/BackupDestination/" + backupDestinationId;
        try {
            final ObjectNode retentionRules = OBJECT_MAPPER.createObjectNode();
            retentionRules.put("enableDataAging", true);
            retentionRules.put("overrideRetentionSettings", true);
            retentionRules.put("retentionRuleType", "RETENTION_PERIOD");
            retentionRules.put("retentionPeriodDays", Integer.parseInt(retentionPeriod));
            retentionRules.put("useExtendedRetentionRules", false);

            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.set("retentionRules", retentionRules);

            final HttpJsonResponse response = executeJsonRequest("PUT", path, requestBody);
            if (!response.isSuccessful()) {
                LOG.warn("Failed to update Commvault retention period. statusCode=[{}], responseBody=[{}]",
                        response.statusCode, response.body);
                return false;
            }
            return true;
        } catch (final IOException e) {
            LOG.error("Failed to request updateRetentionPeriod commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/backupset?clientName=<hostName>
    // 호스트의 default backupset 조회하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public String getDefaultBackupSetId(String hostName) {
        try {
            final HttpResponse response = get("/Backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupsetProperties = root.path("backupsetProperties");
            if (!backupsetProperties.isArray() || backupsetProperties.isEmpty()) {
                return null;
            }
            for (JsonNode backupsetProperty : backupsetProperties) {
                JsonNode backupSetEntity = backupsetProperty.path("backupSetEntity");
                String backupsetName = backupSetEntity.path("backupsetName").asText();
                String applicationId = backupSetEntity.path("applicationId").asText();
                if ("defaultBackupSet".equalsIgnoreCase(backupsetName) && "29".equals(applicationId)) {
                    String backupsetId = backupSetEntity.path("backupsetId").asText();
                    if (StringUtils.isNotBlank(backupsetId)) {
                        return backupsetId;
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getDefaultBackupSetId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/Backupset/<backupsetId>
    // 호스트의 BackupSet에 백업 경로와 Plan을 설정하는 API로 성공한 경우 true, 실패한 경우 false 반환
    public boolean setBackupSet(String path, String planType, String planName, String planSubtype, String planId, String companyId, String backupSetId) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode backupsetProperties = requestBody.putObject("backupsetProperties");

            final ObjectNode subClient = backupsetProperties.putArray("subClientList").addObject();
            subClient.putArray("content").addObject().put("path", path);
            subClient.put("contentOperationType", "OVERWRITE");
            final ObjectNode fsSubClientProp = subClient.putObject("fsSubClientProp");
            fsSubClientProp.put("useGlobalFilters", "USE_CELL_LEVEL_POLICY");
            fsSubClientProp.put("oneTouchSubclient", false);

            final ObjectNode planEntity = backupsetProperties.putObject("planEntity");
            planEntity.put("planId", Integer.parseInt(planId));
            planEntity.put("planName", planName);
            planEntity.put("planType", Integer.parseInt(planType));
            planEntity.put("planSubtype", Integer.parseInt(planSubtype));
            planEntity.putObject("entityInfo").put("companyId", Integer.parseInt(companyId));
            backupsetProperties.put("useContentFromPlan", false);

            final HttpJsonResponse response = executeJsonRequest("POST", "/Backupset/" + backupSetId, requestBody);
            if (!response.isSuccessful()) {
                return false;
            }

            final JsonNode responseArray = OBJECT_MAPPER.readTree(response.body).path("response");
            return responseArray.isArray() && !responseArray.isEmpty()
                    && responseArray.get(0).path("errorCode").asInt(-1) == 0;
        } catch (final IOException e) {
            LOG.error("Failed to request setBackupSet commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Client/<clientId>
    // client의 File System applicationId를 조회하는 API로 없는 경우 null, 있는 경우 applicationId 반환
    public String getApplicationId(String clientId) {
        try {
            final HttpResponse response = get("/Client/" + clientId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode clientProperties = root.path("clientProperties");
            if (!clientProperties.isArray() || clientProperties.isEmpty()) {
                return null;
            }
            for (JsonNode clientProp : clientProperties) {
                JsonNode idaList = clientProp.path("client").path("idaList");
                if (!idaList.isArray() || idaList.isEmpty()) {
                    continue;
                }
                for (JsonNode idaItem : idaList) {
                    JsonNode idaEntity = idaItem.path("idaEntity");
                    String applicationId = idaEntity.path("applicationId").asText();
                    if ("29".equals(applicationId)) {
                        return applicationId;
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getApplicationId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIp>/commandcenter/api/Client/<clientId>
    // client의 설치 및 준비상태를 조회하는 API로 정상인 경우 true, 정상 상태가 아닌 경우 readiness check 수행
    public boolean getClientProps(String clientId) {
        try {
            final HttpResponse response = get("/Client/" + clientId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode clientProperties = root.path("clientProperties");
            if (!clientProperties.isArray() || clientProperties.isEmpty()) {
                return false;
            }
            for (JsonNode clientProp : clientProperties) {
                JsonNode clientReadiness = clientProp.path("clientReadiness");
                if (clientReadiness.isMissingNode() || clientReadiness.isNull()) {
                    continue;
                }
                String readinessStatus = normalizeReadinessText(clientReadiness.path("readinessStatus").asText(null));
                if ("Ready.".equalsIgnoreCase(readinessStatus)) {
                    return true;
                }
                return getClientCheckReadiness(clientId);
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getClientProps commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Client/<clientId>/CheckReadiness?network=true&resourceCapacity=true&includeDisabledClients=false&NeedXmlResp=true&ApplicationReadinessOption=1
    // client 준비상태를 체크하는 API로 모든 readiness 상태가 정상인 경우 true 반환
    public boolean getClientCheckReadiness(String clientId) {
        try {
            final HttpResponse response = get("/Client/" + clientId + "/CheckReadiness?network=true&resourceCapacity=true&includeDisabledClients=false&NeedXmlResp=true&ApplicationReadinessOption=1");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode summary = root.path("summary");
            if (!summary.isArray() || summary.isEmpty()) {
                return false;
            }
            for (JsonNode entity : summary) {
                JsonNode statusNode = entity.path("status");
                JsonNode entityStatusNode = entity.path("entityStatus");
                if (statusNode.isMissingNode() || statusNode.isNull() || entityStatusNode.isMissingNode() || entityStatusNode.isNull()) {
                    return false;
                }
                String status = normalizeReadinessText(statusNode.asText(null));
                if (entityStatusNode.asInt(-1) != 0 || !"Ready.".equalsIgnoreCase(status)) {
                    return false;
                }
            }
            return true;
        } catch (final IOException e) {
            LOG.error("Failed to request getClientCheckReadiness commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Client/<clientId>/CheckReadiness?network=true&resourceCapacity=true&includeDisabledClients=false&NeedXmlResp=true&ApplicationReadinessOption=1
    // client 준비상태의 상세 정보를 조회하는 API로 Client, MediaAgent 등의 role별 status와 reason 반환
    public String getClientCheckReadinessDetails(String clientId) {
        try {
            final HttpResponse response = get("/Client/" + clientId + "/CheckReadiness?network=true&resourceCapacity=true&includeDisabledClients=false&NeedXmlResp=true&ApplicationReadinessOption=1");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            return extractClientReadinessDetails(root);
        } catch (final IOException e) {
            LOG.error("Failed to request getClientCheckReadinessDetails commvault api due to : ", e);
            checkResponseTimeOut(e);
            return "Unable to read Commvault client readiness details: " + e.getMessage();
        }
    }

    private String extractClientReadinessDetails(JsonNode root) {
        JsonNode summary = root.path("summary");
        if (!summary.isArray() || summary.isEmpty()) {
            return "status=[unknown], reason=[unknown]";
        }
        List<String> details = new ArrayList<>();
        for (JsonNode entity : summary) {
            String role = entity.path("entity").path("entityName").asText("unknown");
            String status = normalizeReadinessText(entity.path("status").asText("unknown"));
            String reason = normalizeReadinessText(entity.path("reason").asText(""));
            if (StringUtils.isBlank(reason)) {
                reason = getReadinessReason(root);
            }
            if ("Ready.".equalsIgnoreCase(reason)) {
                reason = "none";
            }
            details.add(String.format("role=[%s], status=[%s], reason=[%s]",
                    StringUtils.defaultIfBlank(role, "unknown"),
                    StringUtils.defaultIfBlank(status, "unknown"),
                    StringUtils.defaultIfBlank(reason, "unknown")));
        }
        return String.join("; ", details);
    }

    private String getReadinessReason(JsonNode root) {
        JsonNode detail = root.path("detail");
        if (!detail.isArray() || detail.isEmpty()) {
            return "unknown";
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode item : detail) {
            String status = normalizeReadinessText(item.path("ReadinessStatus").asText(null));
            String subclient = item.path("Subclient").path("entityName").asText(null);
            if (StringUtils.isBlank(status)) {
                continue;
            }
            if (!StringUtils.equalsIgnoreCase(status, "Ready.")) {
                reasons.add(String.format("subclient=[%s], readinessStatus=[%s]",
                        StringUtils.defaultIfBlank(subclient, "unknown"),
                        status));
            }
        }
        if (!reasons.isEmpty()) {
            return String.join("; ", reasons);
        }
        return "Ready.";
    }

    private String normalizeReadinessText(String value) {
        return StringUtils.trimToEmpty(value).replaceAll("\\s+", " ");
    }

    // GET https://<commserveIp>/commandcenter/api/V4/ServerPlan/<planId>
    // plan 상세 조회하는 API로 없는 경우 null, 있는 경우 planName 반환
    public String getPlanName(String planId) {
        try {
            final HttpResponse response = get("/V4/ServerPlan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode planName = root.path("plan").path("name");
            if (planName.isMissingNode() || planName.isNull() || planName.asText().isEmpty()) {
                return null;
            }
            return planName.asText();
        } catch (final IOException e) {
            LOG.error("Failed to request plan detail commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/Backupset
    // 가상머신에 백업 오퍼링 할당 시 backupset 생성하는 API
    public boolean createBackupSet(String vmName, String applicationId, String clientId, String planId) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode backupSetInfo = requestBody.putObject("backupSetInfo");

            final ObjectNode backupSetEntity = backupSetInfo.putObject("backupSetEntity");
            backupSetEntity.put("backupsetName", vmName);
            backupSetEntity.put("applicationId", Integer.parseInt(applicationId));
            backupSetEntity.put("clientId", Integer.parseInt(clientId));

            final ObjectNode subClient = backupSetInfo.putArray("subClientList").addObject();
            subClient.putArray("content").addObject().put("path", "/");
            subClient.put("contentOperationType", "OVERWRITE");
            final ObjectNode fsSubClientProp = subClient.putObject("fsSubClientProp");
            fsSubClientProp.put("useGlobalFilters", "USE_CELL_LEVEL_POLICY");
            fsSubClientProp.put("oneTouchSubclient", false);
            subClient.put("useLocalArchivalRules", false);

            backupSetInfo.putObject("commonBackupSet").put("isDefaultBackupSet", false);
            backupSetInfo.putObject("planEntity").put("planId", Integer.parseInt(planId));
            backupSetInfo.put("useContentFromPlan", false);

            final HttpJsonResponse response = executeJsonRequest("POST", "/Backupset", requestBody);
            if (!response.isSuccessful()) {
                return false;
            }

            final JsonNode responseArray = OBJECT_MAPPER.readTree(response.body).path("response");
            return responseArray.isArray() && !responseArray.isEmpty()
                    && responseArray.get(0).path("errorCode").asInt(-1) == 0;
        } catch (final IOException e) {
            LOG.error("Failed to request createBackupSet commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Backupset?clientName=<hostName>
    // 호스트의 VM File System BackupSet을 조회하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public String getVmBackupSetId(String hostName, String vmName) {
        try {
            final HttpResponse response = get("/Backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupSets = root.path("backupsetProperties");
            if (!backupSets.isArray() || backupSets.isEmpty()) {
                return null;
            }
            for (JsonNode item : backupSets) {
                JsonNode entity = item.path("backupSetEntity");
                String backupsetName = entity.path("backupsetName").asText();
                String applicationId = entity.path("applicationId").asText();
                if (vmName.equals(backupsetName) && "29".equals(applicationId)) {
                    String backupsetId = entity.path("backupsetId").asText();
                    if (StringUtils.isNotBlank(backupsetId)) {
                        return backupsetId;
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getVmBackupSetId commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // DELETE https://<commserveIp>/commandcenter/api/Backupset/<backupSetId>
    // 가상머신에서 백업 오퍼링 삭제 시 관련된 BackupSet 삭제 API -> 삭제 승인 정책
    // ABLESTACK이 관리하는 Commvault Client는 Delete Backup Set MPA 승인 대상 Client Group에서 제외해야 자동 BackupSet 삭제가 정상 동작
    public boolean deleteBackupSet(String backupSetId) {
        try {
            final HttpResponse response = delete("/Backupset/" + backupSetId);
            checkResponseOK(response);
            return true;
        } catch (final ServerApiException e) {
            LOG.error("Failed to delete Commvault backupSet [{}]: {}", backupSetId, e.getMessage(), e);
            throw e;
        } catch (final IOException e) {
            LOG.error("Failed to request deleteBackupSet commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Subclient?clientId=<clientId>
    // VM BackupSet에 연결된 File System Subclient를 조회하는 API로 없는 경우 null, 있는 경우 subClientEntity 반환
    public String getSubclient(String clientId, String vmName) {
        try {
            final HttpResponse response = get("/Subclient?clientId=" + clientId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode subClients = root.path("subClientProperties");
            if (!subClients.isArray() || subClients.isEmpty()) {
                return null;
            }
            for (JsonNode item : subClients) {
                JsonNode entity = item.path("subClientEntity");
                String backupsetName = entity.path("backupsetName").asText();
                String applicationId = entity.path("applicationId").asText();
                if (vmName.equals(backupsetName) && "29".equals(applicationId)) {
                    return entity.toString();
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getSubclient commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/Subclient/<subclientId>
    // 호스트의 backupset 콘텐츠 경로를 변경하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public boolean updateBackupSet(String path, String subclientId, String clientId, String applicationId, String backupsetId, String instanceId, String subclientName, String backupsetName) {
        final ArrayNode content = buildPathsJson(path);
        if (content.isEmpty()) {
            return false;
        }

        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode subClientProperties = requestBody.putObject("subClientProperties");
            subClientProperties.putObject("commonProperties")
                    .putObject("impersonateUserCredentialinfo")
                    .put("credentialId", 0);
            subClientProperties.set("content", content);

            final ObjectNode fsSubClientProp = subClientProperties.putObject("fsSubClientProp");
            fsSubClientProp.put("includePolicyFilters", false);
            fsSubClientProp.put("useGlobalFilters", "USE_CELL_LEVEL_POLICY");
            fsSubClientProp.put("backupSystemState", false);
            fsSubClientProp.put("oneTouchSubclient", false);
            fsSubClientProp.put("followMountPointsMode", "FOLLOW_MOUNT_POINTS_ON");
            fsSubClientProp.put("customSubclientContentFlags", 0);
            fsSubClientProp.put("customSubclientFlag", true);
            fsSubClientProp.put("openvmsBackupDate", false);
            subClientProperties.put("fsContentOperationType", "OVERWRITE");
            subClientProperties.put("fsExcludeFilterOperationType", "DELETE");
            subClientProperties.put("fsIncludeFilterOperationType", "DELETE");

            final ObjectNode entity = requestBody.putObject("association").putArray("entity").addObject();
            entity.put("subclientId", Integer.parseInt(subclientId));
            entity.put("clientId", Integer.parseInt(clientId));
            entity.put("applicationId", Integer.parseInt(applicationId));
            entity.put("backupsetId", Integer.parseInt(backupsetId));
            entity.put("instanceId", Integer.parseInt(instanceId));
            entity.put("subclientName", subclientName);
            entity.put("backupsetName", backupsetName);

            final HttpJsonResponse response = executeJsonRequest("POST", "/Subclient/" + subclientId, requestBody);
            if (!response.isSuccessful()) {
                return false;
            }

            final JsonNode responseArray = OBJECT_MAPPER.readTree(response.body).path("response");
            return responseArray.isArray() && !responseArray.isEmpty()
                    && responseArray.get(0).path("errorCode").asInt(-1) == 0;
        } catch (final IOException e) {
            LOG.error("Failed to request updateBackupSet commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // POST https://<commserveIp>/commandcenter/api/subclient/<subclientId>/action/backup 테스트 시 Incremental 백업으로 반환되어 사용 x
    // POST https://<commserveIp>/commandcenter/api/CreateTask
    // 백업 실행 API
    public String createBackup(String subclientId, String storagePolicyId, String displayName, String commCellName, String clientId, String companyId, String companyName, String instanceName,
            String appName, String applicationId, String clientName, String backupsetId, String instanceId, String subclientGUID, String subclientName, String csGUID,
            String backupsetName, String backupType) {
        final boolean incrementalBackup = "INCREMENTAL".equalsIgnoreCase(backupType);
        final String backupLevel = incrementalBackup ? "INCREMENTAL" : "FULL";

        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode taskInfo = requestBody.putObject("taskInfo");
            taskInfo.putObject("task").put("taskType", "IMMEDIATE");

            final ObjectNode association = taskInfo.putArray("associations").addObject();
            association.put("subclientId", Integer.parseInt(subclientId));
            association.put("storagePolicyId", Integer.parseInt(storagePolicyId));
            association.put("displayName", displayName);
            association.put("commCellName", commCellName);
            association.put("clientId", Integer.parseInt(clientId));
            final ObjectNode entityInfo = association.putObject("entityInfo");
            entityInfo.put("companyId", Integer.parseInt(companyId));
            entityInfo.put("companyName", companyName);
            association.put("instanceName", instanceName);
            association.put("appName", appName);
            association.put("applicationId", Integer.parseInt(applicationId));
            association.put("clientName", clientName);
            association.put("backupsetId", Integer.parseInt(backupsetId));
            association.put("instanceId", Integer.parseInt(instanceId));
            association.put("subclientGUID", subclientGUID);
            association.put("subclientName", subclientName);
            association.put("csGUID", csGUID);
            association.put("backupsetName", backupsetName);
            association.put("_type_", "SUBCLIENT_ENTITY");

            final ObjectNode subTask = taskInfo.putArray("subTasks").addObject();
            final ObjectNode subTaskInfo = subTask.putObject("subTask");
            subTaskInfo.put("subTaskType", "BACKUP");
            subTaskInfo.put("operationType", "BACKUP");
            final ObjectNode options = subTask.putObject("options");
            final ObjectNode backupOpts = options.putObject("backupOpts");
            backupOpts.put("backupLevel", backupLevel);
            backupOpts.put("runIncrementalBackup", incrementalBackup);
            backupOpts.put("forceFullBackup", !incrementalBackup);
            final ObjectNode commonOpts = options.putObject("commonOpts");
            commonOpts.put("overrideStoragePolicySettings", true);
            commonOpts.put("notifyUserOnJobCompletion", true);

            final HttpJsonResponse response = executeJsonRequest("POST", "/CreateTask", requestBody);
            return response.isSuccessful() ? extractJobIdsFromJsonString(response.body) : null;
        } catch (final IOException e) {
            LOG.error("Failed to request createBackup commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/JobDetails
    // 작업 상태를 주기적으로 조회하여 최종 작업 상태를 반환
    public String getJobStatus(String jobId) {
        return getJobStatus(jobId, 0);
    }

    public String getJobStatus(String jobId, long timeoutMillis) {
        final JobStatusResult result = getJobStatusResult(jobId, timeoutMillis);
        if (StringUtils.isBlank(result.getFailureReason())) {
            lastJobFailureReason.remove();
        } else {
            lastJobFailureReason.set(result.getFailureReason());
        }
        return result.getStatus();
    }

    public JobStatusResult getJobStatusResult(String jobId) {
        return getJobStatusResult(jobId, 0);
    }

    public JobStatusResult getJobStatusResult(String jobId, long timeoutMillis) {
        String jobStatus = "Running";
        String failureReason = null;
        final Set<String> terminalStates = Set.of(
                "Completed",
                "Completed w/ one or more errors",
                "Completed w/ one or more warnings",
                "Committed",
                "Failed",
                "Failed to Start",
                "Killed"
        );
        final Set<String> failureStates = Set.of(
                "Failed",
                "Failed to Start",
                "Killed",
                "Completed w/ one or more errors"
        );
        final long deadline = timeoutMillis > 0 ? System.currentTimeMillis() + timeoutMillis : Long.MAX_VALUE;

        while (!terminalStates.contains(jobStatus)) {
            if (System.currentTimeMillis() >= deadline) {
                LOG.warn("Timed out waiting for Commvault job [{}] to complete. lastStatus=[{}], timeoutMillis=[{}]",
                        jobId, jobStatus, timeoutMillis);
                return new JobStatusResult("TimedOut", "Timed out waiting for Commvault job to complete.");
            }

            try {
                final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
                requestBody.put("jobId", Integer.parseInt(jobId));
                final HttpJsonResponse response = executeJsonRequest("POST", "/JobDetails", requestBody);
                if (response.statusCode != HttpURLConnection.HTTP_OK) {
                    LOG.warn("Failed to query Commvault job [{}]. statusCode=[{}], responseBody=[{}]",
                            jobId, response.statusCode, response.body);
                    return new JobStatusResult(null, null);
                }

                final JsonNode progressInfo = OBJECT_MAPPER.readTree(response.body)
                        .path("job").path("jobDetail").path("progressInfo");
                jobStatus = progressInfo.path("state").asText(null);
                if (StringUtils.isBlank(jobStatus)) {
                    LOG.warn("Commvault JobDetails response did not contain job.jobDetail.progressInfo.state. jobId=[{}]", jobId);
                    return new JobStatusResult(null, null);
                }

                if (failureStates.contains(jobStatus)) {
                    failureReason = progressInfo.path("reasonForJobDelay").asText("");
                    if (StringUtils.isBlank(failureReason)) {
                        failureReason = progressInfo.path("pendingReason").asText("");
                    }
                    LOG.error("Commvault job [{}] failed. status=[{}], reason=[{}]", jobId, jobStatus, failureReason);
                }

                if (terminalStates.contains(jobStatus)) {
                    break;
                }

                try {
                    Thread.sleep(30000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("getJobStatus sleep interrupted", e);
                    return new JobStatusResult("Interrupted", "Thread interrupted while waiting for Commvault job.");
                }
            } catch (final IOException e) {
                LOG.error("Failed to request getJobStatus commvault api due to : ", e);
                checkResponseTimeOut(e);
                return new JobStatusResult(null, null);
            }
        }

        return new JobStatusResult(jobStatus, failureReason);
    }

    public static final class JobStatusResult {
        private final String status;
        private final String failureReason;

        public JobStatusResult(final String status, final String failureReason) {
            this.status = status;
            this.failureReason = failureReason;
        }

        public String getStatus() {
            return status;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    @Deprecated
    public String getLastJobFailureReason() {
        return lastJobFailureReason.get();
    }

    // POST https://<commserveIp>/commandcenter/api/JobDetails
    // 작업의 상세 정보 조회하는 API
    public String getJobDetails(String jobId) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.put("jobId", Integer.parseInt(jobId));
            final HttpJsonResponse response = executeJsonRequest("POST", "/JobDetails", requestBody);
            return response.statusCode == HttpURLConnection.HTTP_OK ? response.body : null;
        } catch (final IOException e) {
            LOG.error("Failed to request getJobDetails commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/DoBrowse
    // commvault의 브라우저단에서 백업 목록에서 조회되지 않도록 삭제하는 API
    public boolean deleteBackup(String subclientId, String applicationId, String instanceId, String clientId, String clientName, String backupsetId, String path) {
        if (StringUtils.isBlank(path)) {
            LOG.warn("Refusing Commvault DoEndUserErase request because backup path is blank.");
            return false;
        }

        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            requestBody.put("opType", "DoEndUserErase");

            final ObjectNode entity = requestBody.putObject("entity");
            entity.put("subclientId", Integer.parseInt(subclientId));
            entity.put("applicationId", Integer.parseInt(applicationId));
            entity.put("instanceId", Integer.parseInt(instanceId));
            entity.put("clientId", Integer.parseInt(clientId));
            entity.put("clientName", clientName);
            entity.put("backupsetId", Integer.parseInt(backupsetId));

            requestBody.putObject("advOptions").put("copyPrecedence", 0);
            final ObjectNode query = requestBody.putArray("queries").addObject();
            query.put("type", "DATA");
            query.put("queryId", "dataQuery");
            requestBody.set("paths", buildPathsJson(path));

            final HttpJsonResponse response = executeJsonRequest("POST", "/DoBrowse", requestBody);
            if (!response.isSuccessful()) {
                return false;
            }

            final JsonNode root = OBJECT_MAPPER.readTree(response.body);
            if (root.path("errorCode").asInt(0) != 0) {
                return false;
            }

            final JsonNode browseResponses = root.path("browseResponses");
            if (browseResponses.isArray()) {
                for (final JsonNode browseResponse : browseResponses) {
                    final int respType = browseResponse.path("respType").asInt(-1);
                    if (respType == 1) {
                        return false;
                    }
                    if (respType == 5) {
                        return true;
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request deleteBackup commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIp>/commandcenter/api/Backupset?clientName=<hostName>
    // 호스트의 VM File System BackupSet을 조회하여 없는 경우 null, 있는 경우 backupsetGUID 반환
    public String getVmBackupSetGuid(String hostName, String backupsetName) {
        try {
            final HttpResponse response = get("/Backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupSets = root.path("backupsetProperties");
            if (!backupSets.isArray() || backupSets.isEmpty()) {
                return null;
            }
            for (JsonNode item : backupSets) {
                JsonNode entity = item.path("backupSetEntity");
                String currentBackupsetName = entity.path("backupsetName").asText();
                String applicationId = entity.path("applicationId").asText();
                if (backupsetName.equals(currentBackupsetName) && "29".equals(applicationId)) {
                    String backupsetGuid = entity.path("backupsetGUID").asText();
                    if (StringUtils.isNotBlank(backupsetGuid)) {
                        return backupsetGuid;
                    }
                    return null;
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getVmBackupSetGuid commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/CreateTask
    // 복원 실행 API
    public String restoreFullVM(String subclientId, String displayName, String backupsetGUID, String clientId, String companyId, String companyName, String instanceName, String appName, String applicationId, String clientName, String backupsetId, String instanceId, String backupsetName, String commCellId, String endTime, String path) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode taskInfo = requestBody.putObject("taskInfo");
            final ObjectNode task = taskInfo.putObject("task");
            task.put("taskType", "IMMEDIATE");
            task.put("initiatedFrom", "GUI");

            final ObjectNode association = taskInfo.putArray("associations").addObject();
            association.put("subclientId", Integer.parseInt(subclientId));
            association.put("displayName", displayName);
            association.put("backupsetGUID", backupsetGUID);
            association.put("clientId", Integer.parseInt(clientId));
            final ObjectNode entityInfo = association.putObject("entityInfo");
            entityInfo.put("companyId", Integer.parseInt(companyId));
            entityInfo.put("companyName", companyName);
            association.put("instanceName", instanceName);
            association.put("appName", appName);
            association.put("applicationId", Integer.parseInt(applicationId));
            association.put("clientName", clientName);
            association.putObject("flags");
            association.put("backupsetId", Integer.parseInt(backupsetId));
            association.put("instanceId", Integer.parseInt(instanceId));
            association.put("backupsetName", backupsetName);
            association.put("_type_", "SUBCLIENT_ENTITY");

            final ObjectNode subTask = taskInfo.putArray("subTasks").addObject();
            final ObjectNode subTaskInfo = subTask.putObject("subTask");
            subTaskInfo.put("subTaskType", "RESTORE");
            subTaskInfo.put("operationType", "RESTORE");

            final ObjectNode restoreOptions = subTask.putObject("options").putObject("restoreOptions");
            final ObjectNode browseOption = restoreOptions.putObject("browseOption");
            browseOption.put("commCellId", Integer.parseInt(commCellId));
            final ObjectNode browseBackupset = browseOption.putObject("backupset");
            browseBackupset.put("backupsetId", Integer.parseInt(backupsetId));
            browseBackupset.put("clientId", Integer.parseInt(clientId));
            browseOption.putObject("timeRange").put("toTime", Long.parseLong(endTime));
            browseOption.put("browseJobCommCellId", Integer.parseInt(commCellId));

            final ObjectNode destination = restoreOptions.putObject("destination");
            final ObjectNode destClient = destination.putObject("destClient");
            destClient.put("clientId", Integer.parseInt(clientId));
            destClient.put("clientName", clientName);
            destination.put("destAppId", Integer.parseInt(applicationId));
            destination.put("inPlace", true);
            destination.putObject("destinationInstance").put("applicationId", 0);
            destination.put("noOfStreams", 10);

            restoreOptions.put("restoreACLsType", "ACL_DATA");
            restoreOptions.putObject("qrOption").put("destAppTypeId", Integer.parseInt(applicationId));
            restoreOptions.putObject("volumeRstOption").put("volumeLeveRestore", false);
            restoreOptions.putObject("virtualServerRstOption");

            final ObjectNode fileOption = restoreOptions.putObject("fileOption");
            fileOption.set("sourceItem", convertPathToJsonArray(path));
            fileOption.putObject("fsCloneOptions").put("cloneMountPath", "");
            restoreOptions.putObject("impersonation").putObject("user");

            final ObjectNode commonOptions = restoreOptions.putObject("commonOptions");
            commonOptions.put("overwriteFiles", true);
            commonOptions.put("unconditionalOverwrite", true);
            commonOptions.put("stripLevelType", "PRESERVE_LEVEL");
            commonOptions.put("preserveLevel", 0);
            commonOptions.put("isFromBrowseBackup", true);

            final HttpJsonResponse response = executeJsonRequest("POST", "/CreateTask", requestBody);
            return response.isSuccessful() ? extractJobIdsFromJsonString(response.body) : null;
        } catch (final IOException e) {
            LOG.error("Failed to request restoreFullVM commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    public String restoreFullVM(String subclientId, String displayName, String backupsetGUID, String clientId, String companyId, String companyName, String instanceName,
                                String appName, String applicationId, String clientName, String backupsetId, String instanceId, String backupsetName,
                                String commCellId, String endTime, List<String> paths) {
        return restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName,
                applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, String.join(",", paths));
    }

    // GET https://<commserveIp>/commandcenter/api/commcell/properties
    // 에이전트 설치 및 CommCell 식별에 필요한 CommCell 정보를 조회하는 API로 없는 경우 null, 있는 경우 commCellEntity 반환
    public String getCommcell() {
        try {
            final HttpResponse response = get("/commcell/properties");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode commCell = root.path("commCellInfo").path("commCellEntity");
            if (commCell.isMissingNode() || commCell.isNull()) {
                LOG.warn("Commvault commcell response did not contain commCellInfo.commCellEntity.");
                return null;
            }
            return commCell.toString();
        } catch (final IOException e) {
            LOG.error("Failed to request getCommcell commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIp>/commandcenter/api/commserv
    // CommServe 버전 정보를 조회하는 API로 없는 경우 null, 있는 경우 버전 문자열 반환
    public String getCvtVersion() {
        try {
            final HttpResponse response = get("/commserv");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(),  StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode csVersionInfo = root.path("csVersionInfo");
            if (!csVersionInfo.isMissingNode() && !csVersionInfo.isNull() && !csVersionInfo.asText().isBlank()) {
                return csVersionInfo.asText();
            }
            LOG.warn("Commvault version response did not contain csVersionInfo.");
        } catch (final IOException e) {
            LOG.error("Failed to request getCvtVersion commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // POST https://<commserveIp>/commandcenter/api/CreateTask
    // commvault 에이전트 설치 API
    public String installAgent(String clientName, String commCellId, String commServeHostName, String userName, String password) {
        try {
            final ObjectNode requestBody = OBJECT_MAPPER.createObjectNode();
            final ObjectNode taskInfo = requestBody.putObject("taskInfo");

            final ObjectNode task = taskInfo.putObject("task");
            task.putObject("taskFlags").put("disabled", false);
            task.put("taskType", "IMMEDIATE");
            task.put("initiatedFrom", "GUI");
            taskInfo.putArray("associations").addObject().put("commCellId", Integer.parseInt(commCellId));

            final ObjectNode subTask = taskInfo.putArray("subTasks").addObject();
            final ObjectNode subTaskInfo = subTask.putObject("subTask");
            subTaskInfo.put("subTaskType", "ADMIN");
            subTaskInfo.put("operationType", "INSTALL_CLIENT");

            final ObjectNode adminOpts = subTask.putObject("options").putObject("adminOpts");
            adminOpts.putObject("updateOption").put("rebootClient", false);
            final ObjectNode clientInstallOption = adminOpts.putObject("clientInstallOption");
            final ObjectNode clientEntity = clientInstallOption.putArray("clientDetails")
                    .addObject().putObject("clientEntity");
            clientEntity.put("clientName", clientName);
            clientEntity.put("commCellId", Integer.parseInt(commCellId));
            clientInstallOption.put("installOSType", "UNIX");
            clientInstallOption.put("discoveryType", "MANUAL");

            final ObjectNode installerOption = clientInstallOption.putObject("installerOption");
            installerOption.put("RemoteClient", false);
            installerOption.put("requestType", "PRE_DECLARE_CLIENT");
            final ObjectNode user = installerOption.putObject("User");
            user.put("userId", 1);
            user.put("userName", "admin");
            installerOption.put("Operationtype", "INSTALL_CLIENT");
            installerOption.put("CommServeHostName", commServeHostName);

            final ObjectNode composition = installerOption.putArray("clientComposition").addObject();
            composition.put("overrideSoftwareCache", false);
            final ObjectNode client = composition.putObject("clientInfo").putObject("client");
            client.put("cvdPort", 0);
            client.put("evmgrcPort", 0);

            final ObjectNode components = composition.putObject("components");
            final ObjectNode componentInfo = components.putArray("componentInfo").addObject();
            componentInfo.put("osType", "Unix");
            componentInfo.put("ComponentId", 1101);
            components.putObject("commonInfo").put("globalFilters", "UseCellLevelPolicy");
            components.putObject("fileSystem").put("configureForLaptopBackups", false);
            composition.put("packageDeliveryOption", "CopyPackage");

            final ObjectNode installFlags = installerOption.putObject("installFlags");
            installFlags.put("install32Base", false);
            installFlags.put("disableOSFirewall", false);
            installFlags.put("addToFirewallExclusion", true);
            installFlags.put("forceReboot", false);
            installFlags.put("killBrowserProcesses", true);
            installFlags.put("ignoreJobsRunning", false);
            installFlags.put("stopOracleServices", false);
            installFlags.put("skipClientsOfCS", false);
            installFlags.put("restoreOnlyAgents", false);
            installFlags.put("overrideClientInfo", true);
            final ObjectNode firewallInstall = installFlags.putObject("firewallInstall");
            firewallInstall.put("enableFirewallConfig", false);
            firewallInstall.put("firewallConnectionType", 0);
            firewallInstall.put("portNumber", 0);

            final ObjectNode clientAuthForJob = clientInstallOption.putObject("clientAuthForJob");
            clientAuthForJob.put("userName", userName);
            clientAuthForJob.put("password", Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            clientInstallOption.put("reuseADCredentials", false);

            final HttpJsonResponse response = executeJsonRequest("POST", "/CreateTask", requestBody);
            return response.isSuccessful() ? extractJobIdsFromJsonString(response.body) : null;
        } catch (final IOException e) {
            LOG.error("Failed to request installAgent commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // GET https://<commserveIP>/commandcenter/api/Job?jobCategory=Active
    // 실행중인 Job 조회 API로, vm의 백업 작업이 실행중인 경우 true 반환
    public boolean getActiveJob(String vmName) {
        if (StringUtils.isBlank(vmName)) {
            return false;
        }

        try {
            final HttpResponse response = get("/Job?jobCategory=Active");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray()) {
                return false;
            }

            for (JsonNode item : jobs) {
                JsonNode jobSummary = item.path("jobSummary");
                if (jobSummary.isMissingNode()) {
                    continue;
                }

                // 11.44 Active Job 응답은 backupSetName을 사용한다.
                // 구 버전/응답 차이에 대비해 backupsetName도 fallback으로 확인한다.
                String backupSetName = jobSummary.path("backupSetName").asText(null);
                if (StringUtils.isBlank(backupSetName)) {
                    backupSetName = jobSummary.path("backupsetName").asText(null);
                }

                if (StringUtils.equals(vmName, backupSetName)) {
                    return true;
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getActiveJob commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // GET https://<commserveIP>/commandcenter/api/Job?jobCategory=Active
    // 실행중인 Job 조회 API로, 호스트의 에이전트 설치 작업이 실행중인 경우 true 반환
    public boolean getInstallActiveJob(String hostName) {
        if (StringUtils.isBlank(hostName)) {
            return false;
        }
        try {
            final HttpResponse response = get("/Job?jobCategory=Active");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            ObjectMapper mapper = OBJECT_MAPPER;
            JsonNode root = mapper.readTree(jsonString);
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray()) {
                return false;
            }
            for (JsonNode item : jobs) {
                JsonNode jobSummary = item.path("jobSummary");
                if (jobSummary.isMissingNode()) {
                    continue;
                }
                if (isInstallClientJobForHost(jobSummary, hostName)) {
                    return true;
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getInstallActiveJob commvault api due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    private boolean isInstallClientJobForHost(JsonNode jobSummary, String hostName) {
        if (!StringUtils.equalsIgnoreCase("Install Client", jobSummary.path("jobType").asText(null))) {
            return false;
        }
        return StringUtils.equalsIgnoreCase(hostName, jobSummary.path("destClientName").asText(null)) ||
                StringUtils.equalsIgnoreCase(hostName, jobSummary.path("destinationClient").path("clientName").asText(null)) ||
                StringUtils.equalsIgnoreCase(hostName,jobSummary.path("subclient").path("clientName").asText(null));
    }

    public static String extractJobIdsFromJsonString(String jsonString) {
        try {
            JsonNode jobIds = OBJECT_MAPPER.readTree(jsonString).path("jobIds");
            if (jobIds.isArray() && !jobIds.isEmpty()) {
                return jobIds.get(0).asText(null);
            }
        } catch (IOException e) {
            LOG.error("Failed to parse Commvault jobIds response.", e);
        }
        return null;
    }

    private ArrayNode buildPathsJson(String pathsString) {
        final ArrayNode paths = OBJECT_MAPPER.createArrayNode();
        if (StringUtils.isBlank(pathsString)) {
            return paths;
        }

        for (String rawPath : pathsString.split(",")) {
            final String path = StringUtils.trimToEmpty(rawPath);
            if (StringUtils.isBlank(path)) {
                continue;
            }
            paths.addObject().put("path", path);
        }
        return paths;
    }

    private ArrayNode convertPathToJsonArray(String path) {
        final ArrayNode paths = OBJECT_MAPPER.createArrayNode();
        if (StringUtils.isBlank(path)) {
            return paths;
        }

        for (String rawPath : path.split(",")) {
            final String trimmedPath = StringUtils.trimToEmpty(rawPath);
            if (StringUtils.isNotBlank(trimmedPath)) {
                paths.add(trimmedPath);
            }
        }
        return paths;
    }
}
