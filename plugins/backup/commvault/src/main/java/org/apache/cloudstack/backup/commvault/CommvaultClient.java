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
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.cloudstack.backup.BackupOffering;
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
import org.json.JSONObject;

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
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Base64;
import java.util.List;
import java.util.Set;

public class CommvaultClient {
    private static final Logger LOG = LogManager.getLogger(CommvaultClient.class);
    private final URI apiURI;
    private final String apiName;
    private final String apiPassword;
    private final HttpClient httpClient;
    private String accessToken = null;
    private String cvtServerIp;
    private String cvtServerUsername;
    private String cvtServerPassword;
    private final int cvtServerPort = 22;

    public CommvaultClient(final String url, final String username, final String password, final boolean validateCertificate, final int timeout) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {

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

    private void authenticate(final String username, final String password) {

        String tokenUrl = apiURI.toString() + "/login";
        String generatedAccessToken = null;
        try {
            URL url = new URL(tokenUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
            String jsonParams = "{\"username\":\"" + username + "\",\"password\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}";

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonParams.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    String regexPattern = "token=([^&]+)";
                    Pattern pattern = Pattern.compile("\"QSDK\\s([a-fA-F0-9]+)\"");
                    Matcher matcher = pattern.matcher(response);
                    if (matcher.find()) {
                        accessToken = "QSDK " + matcher.group(1);
                    } else {
                        throw new CloudRuntimeException("Could not fetch access token from the given code");
                    }
                }
            } else {
                throw new CloudRuntimeException("Failed to create and authenticate Commvault API client, please check the settings.");
            }
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to authenticate Commvault API service due to:" + e.getMessage());
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
            LOG.debug(String.format("HTTP request failed, status code is [%s], response is: [%s].", response.getStatusLine().getStatusCode(), response));
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Got invalid API status code returned by the Commvault server");
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
        request.setHeader(HttpHeaders.AUTHORIZATION, accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        final HttpResponse response = httpClient.execute(request);
        checkAuthFailure(response);

        LOG.debug(String.format("Response received in GET request is: [%s] for URL: [%s].", response.toString(), url));
        return response;
    }

    private HttpResponse delete(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpDelete request = new HttpDelete(url);
        request.setHeader(HttpHeaders.AUTHORIZATION, accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        final HttpResponse response = httpClient.execute(request);
        checkAuthFailure(response);

        LOG.debug(String.format("Response received in DELETE request is: [%s] for URL [%s].", response.toString(), url));
        return response;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/client
    // client에 호스트가 연결되어있는지 확인하는 API로 호스트가 없는 경우 null, 있는 경우 clientId 반환
    public String getClientId(String hostName) {
        try {
            final HttpResponse response = get("/client");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode clientProperties = root.get("clientProperties");
            if (clientProperties.isArray()) {
                for (JsonNode clientProperty : clientProperties) {
                    JsonNode clientNameNode = clientProperty.path("client").path("clientEntity").path("clientName");
                    JsonNode clientIdNode = clientProperty.path("client").path("clientEntity").path("clientId");
                    if (!clientNameNode.isMissingNode() && hostName.equals(clientNameNode.asText())) {
                        return clientIdNode.asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getClientId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/plan
    // plan 조회하는 API로 없는 경우 빈 배열, 있는 경우 plan 명, plan id 반환
    public List<BackupOffering> listPlans() {
        final List<BackupOffering> offerings = new ArrayList<>();
        try {
            final HttpResponse response = get("/plan");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode plans = root.path("plans");
            if (plans.isArray()) {
                for (JsonNode planNode : plans) {
                    JsonNode planDetails = planNode.path("plan");
                    if (!planDetails.isMissingNode()) {
                        String planId = planDetails.path("planId").asText();
                        String planName = planDetails.path("planName").asText();
                        offerings.add(new CommvaultBackupOffering(planName, planId));
                    }
                }
            }
            return offerings;
        } catch (final IOException e) {
            LOG.error("Failed to request listPlans commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return offerings;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/plan/<planId>
    // plan 상세 조회하는 API로 없는 경우 null, type이 deleteRpo인 경우 값이 있는 경우 schedule task id 반환, type이 updateRpo인 경우 plan 반환
    public String getScheduleTaskId(String type, String planId) {
        try {
            final HttpResponse response = get("/v2/plan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode planNode = root.path("plan");
            if (type.equals("deleteRpo")) {
                JsonNode scheduleTaskIdNode = planNode.path("schedule").path("task").path("taskId");
                // JsonNode scheduleLogTaskIdNode = planNode.path("database").path("scheduleLog").path("task").path("taskId");
                // JsonNode snapTaskIdNode = planNode.path("snapInfo").path("snapTask").path("task").path("taskId");
                if (!scheduleTaskIdNode.isMissingNode()) {
                    return scheduleTaskIdNode.asText();
                }
            } else {
                JsonNode plan = planNode.path("summary").path("plan");
                return plan.toString();
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getScheduleTaskId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/schedulepolicy/<taskId>
    // 스케줄 정책 조회하는 API로 없는 경우 null, 있는 경우 subtaskid 반환
    public String getSubTaskId(String taskId) {
        try {
            final HttpResponse response = get("/schedulepolicy/" + taskId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode subTaskIdNode = root.path("taskInfo").path("subTasks");
            if (!subTaskIdNode.isMissingNode()) {
                return subTaskIdNode.get(0).path("subTask").path("subTaskId").asText();
            } else {
                return null;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getSubTaskId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/schedulepolicy/<taskId>/schedule/<subTaskId>
    // 스케줄 정책 조회하여 스케줄 삭제
    public Boolean deleteSchedulePolicy(String taskId, String subTaskId) {
        try {
            final HttpResponse response = delete("/schedulepolicy/" + taskId + "/schedule/" + subTaskId);
            checkResponseOK(response);
            return true;
        } catch (final IOException e) {
            LOG.error("Failed to request deleteSchedulePolicy commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/storagepolicy
    // storagePolicy 조회하는 API로 없는 경우 null, 있는 경우 storagePolicyId 반환
    public String getStoragePolicyId(String planName) {
        try {
            final HttpResponse response = get("/storagePolicy");
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode policies = root.get("policies");
            if (policies.isArray()) {
                for (JsonNode policy : policies) {
                    JsonNode storagePolicyNameNode = policy.path("storagePolicyName");
                    JsonNode storagePolicyIdNode = policy.path("storagePolicyId");
                    if (!storagePolicyIdNode.isMissingNode() && planName.equals(storagePolicyNameNode.asText())) {
                        return storagePolicyIdNode.asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getStoragePolicyId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/storagepolicy/<storagePolicyId>
    // storagePolicy 상세 조회하여 copyId를 반환하여 updateRetentionPeriod API 호출
    public boolean getStoragePolicyDetails(String planId, String storagePolicyId, String retentionPeriod) {
        try {
            final HttpResponse response = get("/storagePolicy/" + storagePolicyId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode copy = root.get("copy");
            if (copy.isArray()) {
                for (JsonNode cop : copy) {
                    JsonNode copies = cop.path("StoragePolicyCopy");
                    if (!copies.isMissingNode()) {
                        String copyId = copies.path("copyId").asText();
                        boolean result = updateRetentionPeriod(planId, copyId, retentionPeriod);
                        if (!result) {
                            return false;
                        }
                    }
                }
                return true;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getStoragePolicyDetails commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // 정상 동작 확인
    // 1) https://10.10.255.56/commandcenter/api/plan/<planId>/storage/modify 해당 plan의 스토리지의 retention을 전부 바꿔주는 API > 테스트 시 응답 500 error
    // 2) https://10.10.255.56/commandcenter/api/v5/serverplan/<planId>/backupdestination/<copyId> 해당 plan의 스토리지의 copy id를 조회하여 개별로 바꿔주는 API
    // plan의 retention period 변경 API
    public boolean updateRetentionPeriod(String planId, String copyId, String retentionPeriod) {
        HttpURLConnection connection = null;
        String putUrl = apiURI.toString() + "/v5/serverplan/" + planId + "/backupdestination/" + copyId;
        try {
            URL url = new URL(putUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"retentionRules\":{" +
                        "\"retentionRuleType\":\"RETENTION_PERIOD\"," +
                        "\"retentionPeriodDays\":%d" +
                    "}" +
                "}",
                Integer.parseInt(retentionPeriod)
            );
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(response.toString());
                if (jObject.has("error")) {
                    JsonObject errorObject = jObject.getAsJsonObject("error");
                    if (errorObject.has("errorCode")) {
                        int errorCode = errorObject.get("errorCode").getAsInt();
                        if (errorCode == 0) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request updateRetentionPeriod commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/backupset?clientName=<hostName>
    // 호스트의 default backupset 조회하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public String getDefaultBackupSetId(String hostName) {
        try {
            final HttpResponse response = get("/backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupsetIdNode = root.path("backupsetProperties");
            if (!backupsetIdNode.isMissingNode()) {
                JsonNode backupsetId = root.path("backupsetProperties").get(0).path("backupSetEntity").path("backupsetId");
                return backupsetId.asText();
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getDefaultBackupSetId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/backupset/<backupsetId>
    // 호스트의 backupset 설정하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public boolean setBackupSet(String path, String planType, String planName, String planSubtype, String planId, String companyId, String backupSetId) {
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/backupset/" + backupSetId;
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"backupsetProperties\":{" +
                        "\"subClientList\":[" +
                            "{" +
                                "\"content\":[" +
                                    "{\"path\":\"%s\"}" +
                                "]," +
                                "\"contentOperationType\":\"OVERWRITE\"," +
                                "\"fsSubClientProp\":{" +
                                    "\"useGlobalFilters\":\"USE_CELL_LEVEL_POLICY\"" +
                                "}" +
                            "}" +
                        "]," +
                        "\"planEntity\":{" +
                            "\"planId\":%d," +
                            "\"planName\":\"%s\"," +
                            "\"planType\":%d," +
                            "\"planSubtype\":%d," +
                            "\"entityInfo\":{" +
                                "\"companyId\":%d" +
                            "}" +
                        "}," +
                        "\"useContentFromPlan\":false" +
                    "}" +
                "}",
                path, Integer.parseInt(planId), planName, Integer.parseInt(planType), Integer.parseInt(planSubtype), Integer.parseInt(companyId)
            );
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(response.toString());
                if (jObject.has("response") && jObject.get("response").isJsonArray()) {
                    JsonArray responseArray = jObject.getAsJsonArray("response");
                    if (responseArray.size() > 0) {
                        JsonObject firstResponse = responseArray.get(0).getAsJsonObject();
                        if (firstResponse.has("errorCode")) {
                            int errorCode = firstResponse.get("errorCode").getAsInt();
                            if (errorCode == 0) {
                                return true;
                            } else {
                                return false;
                            }
                        }
                    }
                }
            } else {
                return false;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request setBackupSet commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/client/<clientId>
    // client의 applicationId 조회하는 API 로 없는 경우 null, 있는 경우 applicationId 반환
    public String getApplicationId(String clientId) {
        try {
            final HttpResponse response = get("/client/" + clientId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode clientProperties = root.get("clientProperties");
            if (clientProperties != null && clientProperties.isArray()) {
                for (JsonNode clientProp : clientProperties) {
                    JsonNode client = clientProp.get("client");
                    if (!client.isMissingNode()) {
                        JsonNode idaList = client.get("idaList");
                        if (idaList != null && idaList.isArray()) {
                            for (JsonNode idaItem : idaList) {
                                JsonNode idaEntity = idaItem.get("idaEntity");
                                if (idaEntity != null && idaEntity.has("applicationId")) {
                                    return idaEntity.get("applicationId").asText();
                                }
                            }
                        }
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getApplicationId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/plan/<planId>
    // plan 상세 조회하는 API로 없는 경우 null, 있는 경우 planName 반환
    public String getPlanName(String planId) {
        try {
            final HttpResponse response = get("/plan/" + planId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode plan = root.path("plan").path("summary").path("plan");
            if (!plan.isMissingNode()) {
                JsonNode planName = plan.path("planName");
                if (!planName.isMissingNode()) {
                    return planName.asText();
                }
            }
            return null;
        } catch (final IOException e) {
            LOG.error("Failed to request plan detail commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/backupset
    // 가상머신에 백업 오퍼링 할당 시 backupset 추가 API
    public boolean createBackupSet(String vmName, String applicationId, String clientId, String planId) {
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/backupset";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"backupSetInfo\":{" +
                        "\"backupSetEntity\":{" +
                            "\"backupsetName\":\"%s\"," +
                            "\"applicationId\":%d," +
                            "\"clientId\":%d" +
                        "}," +
                        "\"subClientList\":[" +
                            "{" +
                                "\"content\":[" +
                                    "{" +
                                        "\"path\":\"/\"" +
                                    "}" +
                                "]," +
                                "\"contentOperationType\":\"OVERWRITE\"," +
                                "\"fsSubClientProp\":{" +
                                    "\"useGlobalFilters\":\"USE_CELL_LEVEL_POLICY\"" +
                                "}," +
                                "\"useLocalArchivalRules\":false" +
                            "}" +
                        "]," +
                        "\"commonBackupSet\":{" +
                            "\"isDefaultBackupSet\":false" +
                        "}," +
                        "\"planEntity\":{" +
                            "\"planId\":%d" +
                        "}," +
                        "\"useContentFromPlan\":false" +
                    "}" +
                "}",
                vmName, Integer.parseInt(applicationId), Integer.parseInt(clientId), Integer.parseInt(planId)
            );
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(response.toString());
                if (jObject.has("response") && jObject.get("response").isJsonArray()) {
                    JsonArray responseArray = jObject.getAsJsonArray("response");
                    if (responseArray.size() > 0) {
                        JsonObject firstResponse = responseArray.get(0).getAsJsonObject();
                        if (firstResponse.has("errorCode")) {
                            int errorCode = firstResponse.get("errorCode").getAsInt();
                            if (errorCode == 0) {
                                return true;
                            } else {
                                return false;
                            }
                        }
                    }
                }
            } else {
                return false;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request createBackupSet commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/backupset?clientName=<hostName>
    // 호스트의 vm backupset 조회하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public String getVmBackupSetId(String hostName, String vmName) {
        try {
            final HttpResponse response = get("/backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupSets = root.get("backupsetProperties");
            if (backupSets != null && backupSets.isArray()) {
                for (JsonNode item : backupSets) {
                    JsonNode entity = item.get("backupSetEntity");
                    if (entity != null && vmName.equals(entity.get("backupsetName").asText())) {
                        return entity.get("backupsetId").asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getVmBackupSetId commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/backupset/<backupSetId>
    // 가상머신에서 백업 오퍼링 삭제 시 관련된 백업 삭제 API
    public boolean deleteBackupSet(String backupSetId) {
        try {
            final HttpResponse response = delete("/backupset/" + backupSetId);
            checkResponseOK(response);
            return true;
        } catch (final IOException e) {
            LOG.error("Failed to request deleteBackupSet commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/subclient?clientId=<clientId>
    // subclient 조회하는 API로 없는 경우 null, 있는 경우 entity String으로 반환
    public String getSubclient(String clientId, String vmName) {
        try {
            final HttpResponse response = get("/subclient?clientId=" + clientId);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode subClient = root.get("subClientProperties");
            if (subClient.isArray()) {
                for (JsonNode item : subClient) {
                    JsonNode entity = item.path("subClientEntity");
                    JsonNode backupsetName = entity.path("backupsetName");
                    if (!entity.isMissingNode() && vmName.equals(backupsetName.asText())) {
                        return entity.toString();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getSubclient commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 정상 동작 확인
    // POST https://10.10.255.56/commandcenter/api/subclient/<backupsetId>
    // 호스트의 backupset 콘텐츠 경로를 변경하는 API로 없는 경우 null, 있는 경우 backupsetId 반환
    public boolean updateBackupSet(String path, String subclientId, String clientId, String planName, String applicationId, String backupsetId, String instanceId, String subclientName, String backupsetName) {
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/subclient/" + backupsetId;
        String[] paths = path.split(",");
        StringBuilder contentBuilder = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i].trim();
            contentBuilder.append("{\"path\":\"").append(p).append("\"}");
            if (i < paths.length - 1) {
                contentBuilder.append(",");
            }
        }
        String contentArray = "[" + contentBuilder.toString() + "]";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"subClientProperties\":{" +
                        "\"commonProperties\":{" +
                            "\"impersonateUserCredentialinfo\":{" +
                                "\"credentialId\":0" +
                            "}" +
                        "}," +
                        "\"content\":%s," +
                        "\"fsSubClientProp\":{" +
                            "\"includePolicyFilters\":false," +
                            "\"useGlobalFilters\":\"USE_CELL_LEVEL_POLICY\"," +
                            "\"backupSystemState\":false," +
                            "\"followMountPointsMode\":\"FOLLOW_MOUNT_POINTS_ON\"," +
                            "\"customSubclientContentFlags\":0," +
                            "\"customSubclientFlag\":true," +
                            "\"openvmsBackupDate\":false" +
                        "}," +
                        "\"fsContentOperationType\":\"OVERWRITE\"," +
                        "\"fsExcludeFilterOperationType\":\"DELETE\"," +
                        "\"fsIncludeFilterOperationType\":\"DELETE\"" +
                    "}," +
                    "\"association\":{" +
                        "\"entity\":[{" +
                            "\"subclientId\":%d," +
                            "\"clientId\":%d," +
                            "\"applicationId\":%d," +
                            "\"backupsetId\":%d," +
                            "\"instanceId\":%d," +
                            "\"subclientName\":\"%s\"," +
                            "\"backupsetName\":\"%s\"" +
                        "}]" +
                    "}" +
                "}",
                contentArray, Integer.parseInt(subclientId), Integer.parseInt(clientId), Integer.parseInt(applicationId), Integer.parseInt(backupsetId), Integer.parseInt(instanceId),subclientName,backupsetName
            );
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(response.toString());
                if (jObject.has("response") && jObject.get("response").isJsonArray()) {
                    JsonArray responseArray = jObject.getAsJsonArray("response");
                    if (responseArray.size() > 0) {
                        JsonObject firstResponse = responseArray.get(0).getAsJsonObject();
                        if (firstResponse.has("errorCode")) {
                            int errorCode = firstResponse.get("errorCode").getAsInt();
                            if (errorCode == 0) {
                                return true;
                            } else {
                                return false;
                            }
                        }
                    }
                }
            } else {
                return false;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request updateBackupSet commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/subclient/<subclientId>/action/backup 테스트 시 Incremental 백업으로 반환되어 사용 x 
    // https://10.10.255.56/commandcenter/api/createtask
    // 백업 실행 API
    public String createBackup(String subclientId, String storagePolicyId, String displayName, String commCellName, String clientId, String companyId, String companyName, String instanceName, String appName, String applicationId, String clientName, String backupsetId, String instanceId, String subclientGUID, String subclientName, String csGUID, String backupsetName) {
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/subclient/" + subclientId + "/action/backup";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"taskInfo\":{" +
                        "\"task\":{" +
                            "\"taskType\":\"IMMEDIATE\"" +
                        "}," +
                        "\"associations\":[{" +
                            "\"subclientId\":%d," +
                            "\"storagePolicyId\":%d," +
                            "\"displayName\":\"%s\"," +
                            "\"commCellName\":\"%s\"," +
                            "\"clientId\":%d," +
                            "\"entityInfo\":{" +
                                "\"companyId\":%d," +
                                "\"companyName\":\"%s\"" +
                            "}," +
                            "\"instanceName\":\"%s\"," +
                            "\"appName\":\"%s\"," +
                            "\"applicationId\":%d," +
                            "\"clientName\":\"%s\"," +
                            "\"backupsetId\":%d," +
                            "\"instanceId\":%d," +
                            "\"subclientGUID\":\"%s\"," +
                            "\"subclientName\":\"%s\"," +
                            "\"csGUID\":\"%s\"," +
                            "\"backupsetName\":\"%s\"," +
                            "\"_type_\":\"SUBCLIENT_ENTITY\"" +
                        "}]," +
                        "\"subTasks\":[{" +
                            "\"subTask\":{" +
                                "\"subTaskType\":\"BACKUP\"," +
                                "\"operationType\":\"BACKUP\"" +
                            "}," +
                            "\"options\":{" +
                                "\"backupOpts\":{" +
                                    "\"backupLevel\":\"FULL\"" +
                                "}" +
                            "}" +
                        "}]" +
                    "}" +
                "}",
                Integer.parseInt(subclientId), Integer.parseInt(storagePolicyId), displayName, commCellName,  Integer.parseInt(clientId),
                Integer.parseInt(companyId), companyName, instanceName, appName, Integer.parseInt(applicationId), clientName,
                Integer.parseInt(backupsetId), Integer.parseInt(instanceId), subclientGUID, subclientName, csGUID, backupsetName);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                String jsonResponse = response.toString();
                return extractJobIdsFromJsonString(jsonResponse);
            } else {
                return null;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request createBackup commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/jobDetails
    // 작업의 상세정보를 조회하는 API로 작업이 완료된 경우 최종 작업 상태를 반환
    public String getJobStatus(String jobId) {
        String jobStatus = "Running";
        HttpURLConnection connection = null;
        Set<String> runningStates = Set.of("Not Started", "Running", "Pending", "Waiting", "Queued", "Suspended");
        while (runningStates.contains(jobStatus)) {
            String postUrl = apiURI.toString() + "/jobDetails";
            try {
                URL url = new URL(postUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", accessToken);
                connection.setDoOutput(true);
                String jsonBody = String.format(
                    "{" +
                        "\"jobId\":" + jobId +
                    "}",
                    Integer.parseInt(jobId)
                );
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }
                    JSONObject jsonObject = new JSONObject(response.toString());
                    jobStatus = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("progressInfo").getString("state");
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        LOG.error("create backup get asyncjob result sleep interrupted error");
                        break;
                    }
                } else {
                    return null;
                }
            } catch (final IOException e) {
                LOG.error("Failed to request getJobDetails commvault api due to:", e);
                checkResponseTimeOut(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return jobStatus;
    }

    // 정상 동작 확인
    // https://10.10.255.56/commandcenter/api/jobDetails
    // 작업의 상세 정보 조회하는 API
    public String getJobDetails(String jobId) {
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/jobDetails";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format(
                "{" +
                    "\"jobId\":" + jobId +
                "}",
                Integer.parseInt(jobId)
            );
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                return response.toString();
            } else {
                return null;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getJobDetails commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    // https://10.10.255.56/commandcenter/api/backupset?clientName=<hostName>
    // 호스트의 vm backupset 조회하는 API로 없는 경우 null, 있는 경우 backupsetGUID 반환
    public String getVmBackupSetGuid(String hostName, String vmName) {
        try {
            LOG.info("getVmBackupSetGuid REST API 호출");
            final HttpResponse response = get("/backupset?clientName=" + hostName);
            checkResponseOK(response);
            String jsonString = EntityUtils.toString(response.getEntity(), "UTF-8");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonString);
            JsonNode backupSets = root.get("backupsetProperties");
            if (backupSets != null && backupSets.isArray()) {
                for (JsonNode item : backupSets) {
                    JsonNode entity = item.get("backupSetEntity");
                    if (entity != null && vmName.equals(entity.get("backupsetName").asText())) {
                        return entity.get("backupsetGUID").asText();
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Failed to request getVmBackupSetGuid commvault api due to:", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // https://10.10.255.56/commandcenter/api/createtask
    // 복원 실행 API
    public String restoreFullVM(String endTime, String subclientId, String displayName, String backupsetGUID, String clientId, String companyId, String companyName, String instanceName, String appName, String applicationId, String clientName, String backupsetId, String instanceId, String backupsetName, String commCellId, String path) {
        LOG.info("restoreFullVM REST API 호출");
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/createtask";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = String.format("{\"taskInfo\":{\"task\":{\"taskType\":\"IMMEDIATE\",\"initiatedFrom\":\"GUI\"},\"associations\":[{\"subclientId\":%d,\"displayName\":\"%s\",\"backupsetGUID\":\"%s\",\"clientId\":%d,"
            + "\"entityInfo\":{\"companyId\":%d,\"companyName\":\"%s\"},\"instanceName\":\"%s\",\"appName\":\"%s\",\"applicationId\":%d,\"clientName\":\"%s\",\"flags\":{},\"backupsetId\":%d,\"instanceId\":%d,\"backupsetName\":\"%s\","
            + "\"_type_\":\"SUBCLIENT_ENTITY\"}],\"subTasks\":[{\"subTask\":{\"subTaskType\":\"RESTORE\",\"operationType\":\"RESTORE\"},\"options\":{\"restoreOptions\":{\"browseOption\":{\"commCellId\":%d,\"backupset\":{\"backupsetId\":%d,\"clientId\":%d},"
            + "\"timeRange\":{\"toTime\":%s},\"browseJobCommCellId\":%d},\"destination\":{\"destClient\":{\"clientId\":%d,\"clientName\":\"%s\"},\"destAppId\":%d,\"inPlace\":true,\"destinationInstance\":{\"applicationId\":0},\"noOfStreams\":10},\"restoreACLsType\":\"ACL_DATA\",\"qrOption\":{\"destAppTypeId\":%d},\"volumeRstOption\":{\"volumeLeveRestore\":false},"
            + "\"virtualServerRstOption\":{},\"fileOption\":{\"sourceItem\":[\"%s\"],\"fsCloneOptions\":{\"cloneMountPath\":\"\"}},\"impersonation\":{\"user\":{}},\"commonOptions\":{\"overwriteFiles\":true,\"unconditionalOverwrite\":false,\"stripLevelType\":\"PRESERVE_LEVEL\",\"preserveLevel\":1,\"isFromBrowseBackup\":true}}}}]}}",
            subclientId, displayName, backupsetGUID, clientId, companyId,
            companyName, instanceName, appName, applicationId, clientName,
            backupsetId, instanceId, backupsetName, commCellId, backupsetId, clientId,
            endTime, commCellId, clientId, clientName, applicationId, applicationId, path);
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                String jsonResponse = response.toString();
                return extractJobIdsFromJsonString(jsonResponse);
            } else {
                return null;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request restoreFullVM commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    // https://10.10.255.56/commandcenter/api/v4/plan/backupdestination/joboperations
    // 백업 삭제
    public boolean deleteBackupForVM(String jobId, String commcellId, String copyId, String storagePolicyId) {
        LOG.info("deleteBackupForVM REST API 호출");
        HttpURLConnection connection = null;
        String postUrl = apiURI.toString() + "/v4/plan/backupdestination/joboperations";
        try {
            URL url = new URL(postUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", accessToken);
            connection.setDoOutput(true);
            String jsonBody = "{"
                                + "\"opType\": \"DELETE\","
                                + "\"loadDependentJobs\": \"true\","
                                + "\"jobIds\": ["
                                +         jobId
                                + "],"
                                + "\"commcellId\": " + commcellId + ","
                                + "\"copyId\": " + copyId + ","
                                + "\"storagePolicyId\": " + storagePolicyId + ","
                                + "\"loadArchiverJobs\": \"true\""
                            + "}";
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return true;
            } else {
                return false;
            }
        } catch (final IOException e) {
            LOG.error("Failed to request deleteBackupForVM commvault api due to:", e);
            checkResponseTimeOut(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return false;
    }

    public static String extractJobIdsFromJsonString(String jsonString) {
        Pattern pattern = Pattern.compile("\"jobIds\"\\s*:\\s*\\[(.*?)\\]");
        Matcher matcher = pattern.matcher(jsonString);
        if (matcher.find()) {
            String jobIdsArray = matcher.group(1);
            String jobId = jobIdsArray.replaceAll("\"", "").replaceAll("\\s", "");
            return jobId.split(",")[0];
        }
        return null;
    }

}