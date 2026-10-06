// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
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
package org.apache.cloudstack.backup.netbackup;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class AblestackNetBackupClient {
    private static final Logger LOG = LogManager.getLogger(AblestackNetBackupClient.class);

    private static final String NETBACKUP_RECOVER_PATH = "/recovery/workloads/physical/scenarios/granular-files-folders/recover";
    private static final String NETBACKUP_EXPIRE_IMAGES_PATH = "/catalog/expire-images";
    private static final String NETBACKUP_CATALOG_IMAGES_PATH = "/catalog/images";
    private static final String NETBACKUP_JOBS_PATH = "/admin/jobs/";
    private static final String NETBACKUP_PART_CONTENT_TYPE = "multipart/vnd.netbackup+form-data;version=12.0";
    private static final String NETBACKUP_RECOVER_CONTENT_TYPE = "multipart/vnd.netbackup+form-data;version=12.0";
    private static final String NETBACKUP_EXPIRE_PART_CONTENT_TYPE = "application/vnd.netbackup+json;version=12.0";
    private static final String NETBACKUP_JSON_V12_CONTENT_TYPE = "application/vnd.netbackup+json;version=12.0";
    private static final String NETBACKUP_POLICY_TYPE_STANDARD = "STANDARD";
    private static final String DETAIL_BACKUP_ID = "netbackup.backup.id";
    private static final int NETBACKUP_JOB_POLL_INTERVAL_MS = 5000;
    private static final int NETBACKUP_JOB_RETRY_COUNT = 5;
    private static final int NETBACKUP_JOB_404_RETRY_INTERVAL_MS = 5000;
    private static final int NETBACKUP_JOB_RETRY_INTERVAL_MS = 5000;

    private final URI apiUri;
    private final String apiKey;
    private final HttpClient httpClient;
    private final int recoveryJobTimeoutSeconds;

    public static final class ExpireImageResult {
        private final boolean completed;
        private final String mpaTicket;

        public ExpireImageResult(final boolean completed, final String mpaTicket) {
            this.completed = completed;
            this.mpaTicket = mpaTicket;
        }

        public boolean isCompleted() {
            return completed;
        }

        public String getMpaTicket() {
            return mpaTicket;
        }
    }

    public AblestackNetBackupClient(final String url, final String apiKey, final int timeout)
            throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        this(url, apiKey, timeout, timeout);
    }

    public AblestackNetBackupClient(final String url, final String apiKey, final int requestTimeout, final int recoveryJobTimeout)
            throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        this.apiUri = new URI(url);
        this.apiKey = apiKey;
        this.recoveryJobTimeoutSeconds = recoveryJobTimeout;

        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(requestTimeout * 1000)
                .setConnectionRequestTimeout(requestTimeout * 1000)
                .setSocketTimeout(requestTimeout * 1000)
                .build();

        final SSLContext sslcontext = SSLUtils.getSSLContext();
        sslcontext.init(null, new X509TrustManager[]{new TrustAllManager()}, new SecureRandom());
        final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslcontext, NoopHostnameVerifier.INSTANCE);
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(config)
                .setSSLSocketFactory(factory)
                .build();
    }

    public boolean volumeSourceReady(final String templateName) {
        final JSONObject response = artifactGet(NETBACKUP_JOBS_PATH, "policyName eq '" + filterValue(templateName) + "'");
        if (response == null) {
            return false;
        }
        for (Object value : response.getJSONArray("data")) {
            final JSONObject job = new JSONObject().put("data", value);
            final String state = normalizeJobState(extractJobState(job));
            if (!Set.of("DONE", "COMPLETED", "FAILED", "CANCELLED", "ABORTED").contains(StringUtils.defaultString(state))) {
                return false;
            }
        }
        return true;
    }

    /** Clone a Standard policy so concurrent VM jobs never modify the operator's policy selections. */
    public ThirdPartyBackupManifest.Artifact backupVolumeArtifact(final String templateName, final String hostName,
            final String hostIp, final ThirdPartyBackupManifest.Artifact artifact, final boolean metadata) {
        final String name = "ABLESTACK-" + artifact.backupUuid + "-" + (metadata ? "metadata" :
                UUID.nameUUIDFromBytes(artifact.path.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (StringUtils.isBlank(artifact.jobId)) {
            final JSONObject template = artifactGet("/config/policies/" + uriSegment(templateName), null);
            if (template == null) {
                throw new CloudRuntimeException("NetBackup staging template policy is unavailable: " + templateName);
            }
            final JSONObject policy = new JSONObject(template.getJSONObject("data").getJSONObject("attributes").getJSONObject("policy").toString());
            if (!"Standard".equalsIgnoreCase(policy.optString("policyType"))) {
                throw new CloudRuntimeException("Volume staging requires a NetBackup Standard policy");
            }
            final JSONArray clients = new JSONArray();
            for (Object value : policy.getJSONArray("clients")) {
                final JSONObject client = (JSONObject) value;
                if (hostName.equalsIgnoreCase(client.optString("hostName")) || hostIp.equals(client.optString("hostName"))) {
                    clients.put(client);
                }
            }
            if (clients.length() != 1) {
                throw new CloudRuntimeException("Staging Host must match exactly one client in the NetBackup template policy");
            }
            JSONObject schedule = null;
            for (Object value : policy.getJSONArray("schedules")) {
                if ("Full Backup".equalsIgnoreCase(((JSONObject) value).optString("backupType"))) {
                    schedule = (JSONObject) value;
                    break;
                }
            }
            if (schedule == null) {
                throw new CloudRuntimeException("NetBackup staging template needs a Full Backup schedule");
            }
            schedule.put("startWindow", new JSONArray());
            schedule.remove("includeDates");
            schedule.put("syntheticBackup", false);
            schedule.put("snapshotOnly", false);
            policy.put("policyName", name);
            policy.put("clients", clients);
            policy.put("schedules", new JSONArray().put(schedule));
            policy.put("backupSelections", new JSONObject().put("selections", new JSONArray().put(artifact.path)));
            policy.getJSONObject("policyAttributes").put("active", true).put("jobLimit", 1);
            artifact.sourceHost = clients.getJSONObject(0).getString("hostName");
            final JSONObject request = new JSONObject().put("data", new JSONObject().put("type", "policy").put("id", name)
                    .put("attributes", new JSONObject().put("policy", policy)));
            artifactPost("/config/policies", request, Set.of(201, 204));
            artifactPost("/admin/manual-backup", new JSONObject().put("data", new JSONObject().put("type", "backupRequest")
                    .put("attributes", new JSONObject().put("policyName", name)
                            .put("scheduleName", schedule.getString("scheduleName")).put("clientName", clients.getJSONObject(0).getString("hostName")))), Set.of(202));
            // The manual-backup response can precede publication of the numeric Job ID. This accepted
            // policy reference is only used to discover that job, never to submit another request.
            artifact.jobId = "policy:" + name;
            return artifact;
        }
        if (artifact.jobId.startsWith("policy:")) {
            if (!artifact.jobId.equals("policy:" + name)) {
                throw new CloudRuntimeException("NetBackup accepted policy does not match the artifact");
            }
            final JSONObject response = artifactGet(NETBACKUP_JOBS_PATH, "policyName eq '" + name + "'");
            if (response == null || response.getJSONArray("data").length() == 0) {
                return artifact;
            }
            final JSONArray jobs = response.getJSONArray("data");
            if (jobs.length() != 1) {
                throw new CloudRuntimeException("NetBackup artifact must resolve to one backup job; multiple streams are unsupported");
            }
            artifact.jobId = jobs.getJSONObject(0).getString("id");
            return artifact;
        }
        final JSONObject job = artifactGet(NETBACKUP_JOBS_PATH + uriSegment(artifact.jobId), null);
        if (job == null) {
            return artifact;
        }
        if (!name.equals(job.getJSONObject("data").getJSONObject("attributes").optString("policyName"))) {
            throw new CloudRuntimeException("NetBackup job belongs to another artifact policy");
        }
        final String state = normalizeJobState(extractJobState(job));
        final Integer status = extractJobStatusCode(job);
        if (isJobFailure(state, status)) {
            throw new CloudRuntimeException("NetBackup artifact job failed: " + artifact.jobId);
        }
        if (isJobSuccess(state, status) && Integer.valueOf(0).equals(status)) {
            final JSONObject catalog = artifactGet(NETBACKUP_CATALOG_IMAGES_PATH, "policyName eq '" + name + "'");
            if (catalog == null || catalog.getJSONArray("data").length() == 0) {
                return artifact;
            }
            final JSONArray images = catalog.getJSONArray("data");
            if (images.length() != 1) {
                throw new CloudRuntimeException("NetBackup artifact catalog reference is ambiguous");
            }
            artifact.externalId = images.getJSONObject(0).getString("id");
            artifact.backupTime = images.getJSONObject(0).getJSONObject("attributes").optString("backupTime", null);
            artifact.completed = true;
        }
        return artifact;
    }

    private static String filterValue(final String value) {
        if (StringUtils.isBlank(value) || value.contains("'") || value.contains("\n") || value.contains("\r")) {
            throw new CloudRuntimeException("Invalid NetBackup artifact policy name");
        }
        return value;
    }

    private static String uriSegment(final String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JSONObject artifactGet(final String path, final String filter) {
        final HttpGet request = new HttpGet(resolvePath(path + (filter == null ? "" : "?page%5Blimit%5D=1000&filter=" + uriSegment(filter))));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        applyAuthenticationHeaders(request);
        try {
            final HttpResponse response = httpClient.execute(request);
            final int status = response.getStatusLine().getStatusCode();
            final String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), "UTF-8");
            if (status == 404 || status == 429 || status >= 500) {
                return null;
            }
            if (status != 200) {
                throw new CloudRuntimeException("NetBackup artifact query failed with status " + status);
            }
            final JSONObject result = new JSONObject(body);
            if (filter != null && result.getJSONArray("data").length() >= 1000) {
                throw new CloudRuntimeException("NetBackup artifact query exceeds its page limit");
            }
            return result;
        } catch (IOException e) {
            LOG.warn("NetBackup artifact status is temporarily unavailable");
            return null;
        }
    }

    private void artifactPost(final String path, final JSONObject body, final Set<Integer> acceptedStatuses) {
        final HttpPost request = new HttpPost(resolvePath(path));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        request.setHeader(HttpHeaders.CONTENT_TYPE, NETBACKUP_JSON_V12_CONTENT_TYPE);
        applyAuthenticationHeaders(request);
        request.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));
        try {
            final HttpResponse response = httpClient.execute(request);
            EntityUtils.consume(response.getEntity());
            if (!acceptedStatuses.contains(response.getStatusLine().getStatusCode())) {
                throw new CloudRuntimeException("NetBackup artifact submission failed with status " + response.getStatusLine().getStatusCode());
            }
        } catch (IOException e) {
            throw new CloudRuntimeException("NetBackup artifact submission outcome is unknown", e);
        }
    }

    public String restoreBackupChain(final String recoveryClient, final String destinationClient, final List<Backup> restoreChain) {
        if (restoreChain == null || restoreChain.isEmpty()) {
            throw new CloudRuntimeException("NetBackup restore chain backups cannot be empty");
        }
        if (StringUtils.isBlank(recoveryClient) || StringUtils.isBlank(destinationClient)) {
            throw new CloudRuntimeException("NetBackup recovery client and destination client cannot be empty");
        }

        final Backup fullBackup = restoreChain.get(0);
        final Backup targetBackup = restoreChain.get(restoreChain.size() - 1);
        LOG.info("NetBackup restore API request. recoveryClient=[{}], destinationClient=[{}], chain=[{}], fullBackup=[{}], targetBackup=[{}]",
                recoveryClient, destinationClient,
                restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()),
                fullBackup.getExternalId(), targetBackup.getExternalId());
        final String boundary = "----AbleStackNetBackup" + UUID.randomUUID().toString().replace("-", "");
        final String body = buildMultipartBody(boundary, recoveryClient, destinationClient, fullBackup, targetBackup, restoreChain);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "Preparing NetBackup restore request. apiUriHost=[{}], recoveryClient=[{}], destinationClient=[{}], "
                            + "fullBackupExternalId=[{}], targetBackupExternalId=[{}], restoreChain=[{}], boundary=[{}], body=[{}]",
                    apiUri.getHost(),
                    recoveryClient,
                    destinationClient,
                    fullBackup.getExternalId(),
                    targetBackup.getExternalId(),
                    restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()),
                    boundary,
                    body);
        }

        final HttpPost request = new HttpPost(resolvePath(NETBACKUP_RECOVER_PATH));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        request.setHeader(HttpHeaders.CONTENT_TYPE, NETBACKUP_RECOVER_CONTENT_TYPE + "; boundary=" + boundary);
        applyAuthenticationHeaders(request);
        request.setEntity(new StringEntity(body, ContentType.create("multipart/vnd.netbackup+form-data", "UTF-8")));

        try {
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
            if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_ACCEPTED && statusCode != HttpStatus.SC_CREATED) {
                LOG.error("NetBackup restore request failed. statusCode={}, response={}", statusCode, responseBody);
                throw new CloudRuntimeException(String.format(
                        "NetBackup restore REST API failed with status [%s]: %s", statusCode, responseBody));
            }

            final String recoveryJobId = extractRecoveryJobId(responseBody, response);
            if (StringUtils.isBlank(recoveryJobId)) {
                throw new CloudRuntimeException("NetBackup restore REST API did not return a recovery job ID");
            }

            LOG.info("NetBackup restore API accepted. recoveryJobId=[{}], destinationClient=[{}], targetBackup=[{}]",
                    recoveryJobId, destinationClient, targetBackup.getExternalId());
            waitForRecoveryJob(recoveryJobId);
            LOG.info("NetBackup restore job [{}] file list after completion: {}", recoveryJobId, getRestoreJobFileList(recoveryJobId));
            LOG.info("NetBackup restore request completed for destination client [{}] using target backup path [{}].",
                    destinationClient, targetBackup.getExternalId());
            return recoveryJobId;
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to request NetBackup restore API: " + e.getMessage(), e);
        }
    }

    public String getRecoveryJobState(final String recoveryJobId) {
        if (StringUtils.isBlank(recoveryJobId)) {
            return null;
        }
        final JSONObject response = getRecoveryJob(recoveryJobId);
        return normalizeJobState(extractJobState(response));
    }

    public String getPrimaryServerName() { return apiUri.getHost(); }

    public org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result pollVolumeRestore(String jobId) {
        JSONObject response = getRecoveryJob(jobId);
        String state = normalizeJobState(extractJobState(response));
        Integer status = extractJobStatusCode(response);
        if (isJobFailure(state, status)) {
            throw new CloudRuntimeException("NetBackup volume restore failed: " + jobId + " (" + state + ", " + status + ")");
        }
        return new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result(jobId, isJobSuccess(state, status));
    }

    public ExpireImageResult expireBackupImage(final String backupId, final int copyNumber) {
        if (StringUtils.isBlank(backupId)) {
            throw new CloudRuntimeException("NetBackup backup ID cannot be empty when expiring images");
        }

        final String boundary = "----AbleStackNetBackup" + UUID.randomUUID().toString().replace("-", "");
        final JSONArray selections = new JSONArray()
                .put(new JSONObject()
                        .put("backupId", backupId)
                        .put("copyNumber", copyNumber));
        final StringBuilder builder = new StringBuilder();
        appendFormPart(builder, boundary, "selectionsFile", selections.toString(), NETBACKUP_EXPIRE_PART_CONTENT_TYPE);
        builder.append("--").append(boundary).append("--").append("\r\n");

        final HttpPost request = new HttpPost(resolvePath(NETBACKUP_EXPIRE_IMAGES_PATH));
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        request.setHeader(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        request.setHeader("X-NetBackup-Log-All-Files", "false");
        applyAuthenticationHeaders(request);
        request.setEntity(new StringEntity(builder.toString(), ContentType.create("multipart/form-data", "UTF-8")));

        try {
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
            if (statusCode == HttpStatus.SC_NO_CONTENT) {
                LOG.info("NetBackup expire image request completed for backup ID [{}], copy number [{}].", backupId, copyNumber);
                return new ExpireImageResult(true, null);
            }
            if (statusCode == HttpStatus.SC_ACCEPTED) {
                final String ticketHeader = response.getFirstHeader("X-NetBackup-MPA-Ticket") != null
                        ? response.getFirstHeader("X-NetBackup-MPA-Ticket").getValue()
                        : null;
                LOG.info("NetBackup expire image request for backup ID [{}], copy number [{}] is awaiting MPA approval. Ticket: [{}].",
                        backupId, copyNumber, ticketHeader);
                return new ExpireImageResult(false, ticketHeader);
            }
            if (statusCode != HttpStatus.SC_OK) {
                LOG.error("NetBackup expire image request failed. statusCode={}, response={}", statusCode, responseBody);
                throw new CloudRuntimeException(String.format(
                        "NetBackup expire image REST API failed with status [%s]: %s", statusCode, responseBody));
            }
            LOG.info("NetBackup expire image request completed for backup ID [{}], copy number [{}].", backupId, copyNumber);
            return new ExpireImageResult(true, null);
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to request NetBackup expire image API: " + e.getMessage(), e);
        }
    }

    public boolean backupImageExists(final String backupId) {
        if (StringUtils.isBlank(backupId)) {
            return false;
        }

        final HttpGet request = new HttpGet(resolvePath(String.format("%s/%s",
                NETBACKUP_CATALOG_IMAGES_PATH, backupId)));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        applyAuthenticationHeaders(request);

        try {
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
            if (statusCode != HttpStatus.SC_OK) {
                throw new CloudRuntimeException(String.format(
                        "Failed to query NetBackup catalog image [%s]. status [%s], response [%s]",
                        backupId, statusCode, responseBody));
            }
            if (StringUtils.isBlank(responseBody)) {
                return false;
            }
            final JSONObject responseJson = new JSONObject(responseBody);
            final JSONObject image = responseJson.optJSONObject("data");
            if (image != null) {
                return StringUtils.equals(backupId, image.optString("id", null));
            }
            return false;
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to query NetBackup catalog image API: " + e.getMessage(), e);
        }
    }

    private String buildMultipartBody(final String boundary, final String recoveryClient, final String destinationClient,
            final Backup fullBackup, final Backup targetBackup, final List<Backup> restoreChain) {
        final JSONObject recoveryPoint = new JSONObject();
        recoveryPoint.put("client", recoveryClient);
        recoveryPoint.put("policyType", NETBACKUP_POLICY_TYPE_STANDARD);
        recoveryPoint.put("startDate", resolveCatalogBackupTime(fullBackup));
        recoveryPoint.put("endDate", resolveCatalogBackupTime(targetBackup));

        final JSONObject recoveryOptions = new JSONObject();
        recoveryOptions.put("server", apiUri.getHost());
        recoveryOptions.put("destinationClient", destinationClient);
        recoveryOptions.put("overwriteExistingFiles", false);
        recoveryOptions.put("restrictMountPoints", false);
        recoveryOptions.put("renameHardLinks", false);
        recoveryOptions.put("renameSoftLinks", false);
        recoveryOptions.put("accessControlAttributes", false);
        recoveryOptions.put("jobPriorityOverride", 90000);

        final JSONObject requestPayload = new JSONObject();
        requestPayload.put("data", new JSONObject()
                .put("type", "physicalFilesFoldersRecoveryRequest")
                .put("attributes", new JSONObject()
                        .put("recoveryPoint", recoveryPoint)
                        .put("recoveryOptions", recoveryOptions)));

        final JSONArray selections = buildRestoreSelections(restoreChain);

        if (LOG.isDebugEnabled()) {
            LOG.debug("NetBackup restore recoveryPoint=[{}]", recoveryPoint);
            LOG.debug("NetBackup restore recoveryOptions=[{}]", recoveryOptions);
            LOG.debug("NetBackup restore selectionsFile=[{}]", selections);
            LOG.debug("NetBackup restore requestPayload=[{}]", requestPayload);
        }

        final StringBuilder builder = new StringBuilder();
        appendFormPart(builder, boundary, "recoveryRequest", requestPayload.toString(), NETBACKUP_PART_CONTENT_TYPE);
        appendFormPart(builder, boundary, "selectionsFile", selections.toString(), NETBACKUP_PART_CONTENT_TYPE);
        builder.append("--").append(boundary).append("--").append("\r\n");
        return builder.toString();
    }

    private JSONArray buildRestoreSelections(final List<Backup> restoreChain) {
        final JSONArray selections = new JSONArray();
        final Set<String> selectedPaths = new LinkedHashSet<>();
        for (final Backup restoreBackup : restoreChain) {
            if (StringUtils.isNotBlank(restoreBackup.getExternalId())) {
                addRestoreSelection(selections, selectedPaths,
                        ensureTrailingSlash(restoreBackup.getExternalId()), resolveCatalogBackupTime(restoreBackup));
            }
        }
        return selections;
    }

    private void addRestoreSelection(final JSONArray selections, final Set<String> selectedPaths, final String path, final String backupTime) {
        if (StringUtils.isBlank(path)) {
            return;
        }
        final String selectionKey = String.format("%s|%s", path, backupTime);
        if (!selectedPaths.add(selectionKey)) {
            return;
        }
        selections.put(new JSONObject()
                .put("path", path)
                .put("backupTime", backupTime));
    }

    private void appendFormPart(final StringBuilder builder, final String boundary, final String name, final String content,
            final String partContentType) {
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"").append(name).append("\"; filename=\"blob\"").append("\r\n");
        builder.append("Content-Type: ").append(partContentType).append("\r\n");
        builder.append("\r\n");
        builder.append(content).append("\r\n");
    }

    private String resolveCatalogBackupTime(final Backup backup) {
        if (backup == null) {
            throw new CloudRuntimeException("NetBackup restore backup metadata is missing");
        }

        final String backupId = backup.getDetail(DETAIL_BACKUP_ID);
        if (StringUtils.isBlank(backupId)) {
            throw new CloudRuntimeException(String.format(
                    "NetBackup backup ID is missing for backup [%s].",
                    backup.getExternalId()));
        }

        final String catalogBackupTime = getCatalogBackupTime(backupId);
        if (StringUtils.isBlank(catalogBackupTime)) {
            throw new CloudRuntimeException(String.format(
                    "NetBackup catalog backupTime was not found for backup ID [%s] (external ID [%s]).",
                    backupId, backup.getExternalId()));
        }
        return catalogBackupTime;
    }

    public String getCatalogBackupTime(final String backupId) {
        final HttpGet request = new HttpGet(resolvePath(String.format("%s/%s", NETBACKUP_CATALOG_IMAGES_PATH, backupId)));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        applyAuthenticationHeaders(request);

        try {
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
            if (statusCode == HttpStatus.SC_NOT_FOUND) {
                return null;
            }
            if (statusCode != HttpStatus.SC_OK) {
                throw new CloudRuntimeException(String.format(
                        "Failed to query NetBackup catalog image [%s]. status [%s], response [%s]",
                        backupId, statusCode, responseBody));
            }
            if (StringUtils.isBlank(responseBody)) {
                return null;
            }

            final JSONObject responseJson = new JSONObject(responseBody);
            final JSONObject image = responseJson.optJSONObject("data");
            if (image == null) {
                return null;
            }
            final JSONObject attributes = image.optJSONObject("attributes");
            if (attributes == null) {
                return null;
            }
            final String backupTime = attributes.optString("backupTime", null);
            if (StringUtils.isBlank(backupTime)) {
                return null;
            }
            return backupTime;
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to query NetBackup catalog image API: " + e.getMessage(), e);
        }
    }

    private String ensureTrailingSlash(final String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        return path.endsWith("/") ? path : path + "/";
    }

    private String extractRecoveryJobId(final String responseBody, final HttpResponse response) {
        if (StringUtils.isBlank(responseBody)) {
            final String recoveryJobIdFromLocation = extractRecoveryJobIdFromLocation(response);
            return StringUtils.isNotBlank(recoveryJobIdFromLocation) ? recoveryJobIdFromLocation : null;
        }
        final JSONObject responseJson = new JSONObject(responseBody);
        final String recoveryJobId = extractString(responseJson, "relationships.recoveryJob.data.id");
        if (StringUtils.isNotBlank(recoveryJobId)) {
            return recoveryJobId;
        }
        return extractRecoveryJobIdFromLocation(response);
    }

    private String extractRecoveryJobIdFromLocation(final HttpResponse response) {
        if (response == null || response.getFirstHeader("Location") == null) {
            return null;
        }
        final String location = response.getFirstHeader("Location").getValue();
        if (StringUtils.isBlank(location)) {
            return null;
        }
        final String normalized = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
        final int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == normalized.length() - 1) {
            return normalized;
        }
        return normalized.substring(lastSlash + 1);
    }

    private void waitForRecoveryJob(final String recoveryJobId) {
        final long deadline = resolveRecoveryJobDeadline();
        while (!isRecoveryJobDeadlineExceeded(deadline)) {
            final JSONObject response = getRecoveryJob(recoveryJobId);
            final String jobState = normalizeJobState(extractJobState(response));
            final Integer jobStatusCode = extractJobStatusCode(response);
            if (isJobSuccess(jobState, jobStatusCode)) {
                final String restoreBackupIds = extractString(response, "data.attributes.restoreBackupIDs");
                if (StringUtils.isNotBlank(restoreBackupIds)) {
                    LOG.info("NetBackup recovery job [{}] completed successfully with restored backup IDs [{}].",
                            recoveryJobId, restoreBackupIds.replaceAll("\\s+", ", ").trim());
                }
                return;
            }
            if (isJobFailure(jobState, jobStatusCode)) {
                throw new CloudRuntimeException(String.format(
                        "NetBackup recovery job [%s] failed with state [%s] and status code [%s]: %s",
                        recoveryJobId, jobState, jobStatusCode, response));
            }
            sleepBeforePolling(recoveryJobId);
        }
        throw new CloudRuntimeException(String.format(
                "Timed out after [%s] seconds while waiting for NetBackup recovery job [%s].",
                formatRecoveryJobTimeout(), recoveryJobId));
    }

    private long resolveRecoveryJobDeadline() {
        if (recoveryJobTimeoutSeconds <= 0) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() + recoveryJobTimeoutSeconds * 1000L;
    }

    private boolean isRecoveryJobDeadlineExceeded(final long deadline) {
        return System.currentTimeMillis() >= deadline;
    }

    private String formatRecoveryJobTimeout() {
        return recoveryJobTimeoutSeconds <= 0 ? "unlimited" : String.valueOf(recoveryJobTimeoutSeconds);
    }

    private JSONObject getRecoveryJob(final String recoveryJobId) {
        for (int attempt = 1; attempt <= NETBACKUP_JOB_RETRY_COUNT; attempt++) {
            try {
                final HttpGet request = new HttpGet(resolvePath(NETBACKUP_JOBS_PATH + recoveryJobId));
                request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
                applyAuthenticationHeaders(request);
                final HttpResponse response = httpClient.execute(request);
                final int statusCode = response.getStatusLine().getStatusCode();
                final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
                if (statusCode == HttpStatus.SC_OK) {
                    return StringUtils.isBlank(responseBody) ? new JSONObject() : new JSONObject(responseBody);
                }

                if (shouldRetryRecoveryJob(statusCode) && attempt < NETBACKUP_JOB_RETRY_COUNT) {
                    LOG.warn("NetBackup recovery job [{}] query returned status [{}] on attempt [{}/{}]; retrying after [{}] ms. response=[{}]",
                            recoveryJobId, statusCode, attempt, NETBACKUP_JOB_RETRY_COUNT, NETBACKUP_JOB_RETRY_INTERVAL_MS, responseBody);
                    sleepBeforeRecoveryJobRetry(recoveryJobId);
                    continue;
                }

                throw new CloudRuntimeException(String.format(
                        "Failed to query NetBackup recovery job [%s]. status [%s], response [%s]",
                        recoveryJobId, statusCode, responseBody));
            } catch (IOException e) {
                throw new CloudRuntimeException("Failed to query NetBackup recovery job: " + e.getMessage(), e);
            }
        }

        throw new CloudRuntimeException(String.format(
                "Failed to query NetBackup recovery job [%s] after [%s] retries.",
                recoveryJobId, NETBACKUP_JOB_RETRY_COUNT));
    }

    private Set<String> getRestoreJobFileList(final String jobId) {
        final HttpGet request = new HttpGet(resolvePath(NETBACKUP_JOBS_PATH + jobId + "/file-lists"));
        request.setHeader(HttpHeaders.ACCEPT, NETBACKUP_JSON_V12_CONTENT_TYPE);
        applyAuthenticationHeaders(request);

        try {
            final HttpResponse response = httpClient.execute(request);
            final int statusCode = response.getStatusLine().getStatusCode();
            final String responseBody = response.getEntity() != null ? EntityUtils.toString(response.getEntity(), "UTF-8") : "";
            if (statusCode != HttpStatus.SC_OK) {
                LOG.debug("NetBackup restore job [{}] file-lists query returned status [{}]. response=[{}]",
                        jobId, statusCode, responseBody);
                return null;
            }
            if (StringUtils.isBlank(responseBody)) {
                return new LinkedHashSet<>();
            }

            final JSONObject responseJson = new JSONObject(responseBody);
            final JSONArray fileList = extractFileListArray(responseJson);
            if (fileList == null || fileList.isEmpty()) {
                return new LinkedHashSet<>();
            }

            final Set<String> normalized = new LinkedHashSet<>();
            for (int i = 0; i < fileList.length(); i++) {
                final String path = fileList.optString(i, null);
                if (StringUtils.isNotBlank(path)) {
                    normalized.add(ensureTrailingSlash(path));
                }
            }
            return normalized;
        } catch (IOException e) {
            throw new CloudRuntimeException("Failed to query NetBackup restore job file list: " + e.getMessage(), e);
        }
    }

    private JSONArray extractFileListArray(final JSONObject responseJson) {
        if (responseJson == null) {
            return null;
        }
        final JSONObject data = responseJson.optJSONObject("data");
        if (data == null) {
            return null;
        }
        final JSONObject attributes = data.optJSONObject("attributes");
        if (attributes == null) {
            return null;
        }
        return attributes.optJSONArray("fileList");
    }

    private void applyAuthenticationHeaders(final HttpRequestBase request) {
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        request.setHeader("X-API-Key", apiKey);
    }

    private String extractJobState(final JSONObject response) {
        final String[] candidates = {
                "data.attributes.state",
                "data.attributes.jobStatus",
                "data.attributes.jobState",
                "state",
                "jobStatus",
                "jobState",
                "data.attributes.status",
                "status"
        };
        for (final String candidate : candidates) {
            final String value = extractString(response, candidate);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private Integer extractJobStatusCode(final JSONObject response) {
        final String statusValue = extractString(response, "data.attributes.status");
        if (StringUtils.isBlank(statusValue)) {
            return null;
        }
        try {
            return Integer.valueOf(statusValue);
        } catch (NumberFormatException e) {
            LOG.debug("Unable to parse NetBackup recovery job status code [{}].", statusValue, e);
            return null;
        }
    }

    private String extractString(final JSONObject jsonObject, final String dottedPath) {
        Object current = jsonObject;
        for (final String token : dottedPath.split("\\.")) {
            if (!(current instanceof JSONObject)) {
                return null;
            }
            final JSONObject currentObject = (JSONObject) current;
            if (!currentObject.has(token) || currentObject.isNull(token)) {
                return null;
            }
            current = currentObject.get(token);
        }
        return current == null ? null : String.valueOf(current);
    }

    private String normalizeJobState(final String jobState) {
        if (StringUtils.isBlank(jobState)) {
            return null;
        }
        return jobState.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private boolean isJobSuccess(final String jobState, final Integer jobStatusCode) {
        if (jobStatusCode != null && jobStatusCode == 0 && "DONE".equals(jobState)) {
            return true;
        }
        return ("COMPLETED".equals(jobState)
                || "SUCCEEDED".equals(jobState)
                || "SUCCESS".equals(jobState)
                || "DONE".equals(jobState))
                && (jobStatusCode == null || jobStatusCode == 0);
    }

    private boolean isJobFailure(final String jobState, final Integer jobStatusCode) {
        if ("DONE".equals(jobState) && jobStatusCode != null && jobStatusCode != 0) {
            return true;
        }
        return "FAILED".equals(jobState)
                || "FAILURE".equals(jobState)
                || "ERROR".equals(jobState)
                || "CANCELLED".equals(jobState)
                || "ABORTED".equals(jobState);
    }

    private void sleepBeforePolling(final String recoveryJobId) {
        try {
            Thread.sleep(NETBACKUP_JOB_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException(String.format(
                    "Interrupted while polling NetBackup recovery job [%s].", recoveryJobId), e);
        }
    }

    private void sleepBeforeRecoveryJobRetry(final String recoveryJobId) {
        try {
            Thread.sleep(NETBACKUP_JOB_404_RETRY_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudRuntimeException(String.format(
                    "Interrupted while retrying NetBackup recovery job [%s].", recoveryJobId), e);
        }
    }

    private boolean shouldRetryRecoveryJob(final int statusCode) {
        return statusCode == HttpStatus.SC_NOT_FOUND
                || statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE;
    }

    private URI resolvePath(final String path) {
        final String base = StringUtils.removeEnd(apiUri.toString(), "/");
        final String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return URI.create(base + normalizedPath);
    }
}
