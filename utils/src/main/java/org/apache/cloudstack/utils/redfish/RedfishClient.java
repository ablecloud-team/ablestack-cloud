//
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
//
package org.apache.cloudstack.utils.redfish;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.google.gson.JsonElement;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HTTP;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.utils.net.NetUtils;
import com.google.common.net.InternetDomainName;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Provides support to a set of REST requests that can be sent to a Redfish Server. </br>
 * RedfishClient allows to gather the server Power State, and execute Reset
 * actions such as 'On', 'ForceOff', 'GracefulShutdown', 'GracefulRestart' etc.
 */
public class RedfishClient {

    protected Logger logger = LogManager.getLogger(getClass());

    private String username;
    private String password;
    private boolean useHttps;
    private boolean ignoreSsl;
    private int redfishRequestMaxRetries;

    private final static String SYSTEMS_URL_PATH = "redfish/v1/Systems/";
    private final static String MANAGERS_URL_PATH = "redfish/v1/Managers/";
    private final static String CHASSIS_URL_PATH = "redfish/v1/Chassis/";
    private final static String FIRMWARE_URL_PATH = "redfish/v1/UpdateService/FirmwareInventory/";
    private final static String COMPUTER_SYSTEM_RESET_URL_PATH = "/Actions/ComputerSystem.Reset";
    private final static String REDFISH_RESET_TYPE = "ResetType";
    private final static String POWER_STATE = "PowerState";
    private final static String APPLICATION_JSON = "application/json";
    private final static String ACCEPT = "accept";
    private final static String ODATA_ID = "@odata.id";
    private final static String MEMBERS = "Members";
    private final static String LINKS = "Links";
    private final static String EXPECTED_HTTP_STATUS = "2XX";
    private final static int WAIT_FOR_REQUEST_RETRY = 2;

    // --- 싱글톤 자원 추가 ---
    private static final int THREAD_POOL_SIZE = 50;
    private static final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    // OkHttpClient (SSL 옵션 별로 2개)
    private static final OkHttpClient safeClient = new OkHttpClient();
    private static final OkHttpClient unsafeClient = createUnsafeClient();

    /**
     * Redfish Command type: </br>
     * <b>ComputerSystemReset:</b> execute Redfish reset commands ({@link RedfishResetCmd}). </br>
     * <b>GetSystemId:</b> get the system ID. </br>
     * <b>GetPowerState:</b> used for get the system power state. </br>
     */
    public enum
    RedfishCmdType {
        ComputerSystemReset, GetSystemId, GetPowerState, GetManagerId, getChassisId
    }

    /**
     * Redfish System Power State: </br>
     * <b>Off:</b> The state is powered Off. </br>
     * <b>On:</b> The state is powered On. </br>
     * <b>PoweringOff:</b> A temporary state between On and Off. </br>
     * <b>PoweringOn:</b> A temporary state between Off and On.
     */
    public enum RedfishPowerState {
        On, Off, PoweringOn, PoweringOff
    }

    /**
     * <ul>
     * <li><b>ForceOff:</b> Turn the unit off immediately (non-graceful shutdown).
     * <li><b>ForceOn:</b> Turn the unit on immediately.
     * <li><b>ForceRestart:</b> Perform an immediate (non-graceful) shutdown,
     * followed by a restart.
     * <li><b>GracefulRestart:</b> Perform a graceful shutdown followed by a restart
     * of the system.
     * <li><b>GracefulShutdown:</b> Perform a graceful shutdown and power off.
     * <li><b>Nmi:</b> Generate a Diagnostic Interrupt (usually an NMI on x86
     * systems) to cease normal operations, perform diagnostic actions and typically
     * halt the system.
     * <li><b>On:</b> Turn the unit on.
     * <li><b>PowerCycle:</b> Perform a power cycle of the unit.
     * <li><b>PushPowerButton:</b> Simulate the pressing of the physical power
     * button on this unit.
     * </ul>
     */
    public enum RedfishResetCmd {
        ForceOff, ForceOn, ForceRestart, GracefulRestart, GracefulShutdown, Nmi, On, PowerCycle, PushPowerButton
    }

    public RedfishClient(String username, String password, boolean useHttps, boolean ignoreSsl, int redfishRequestRetries) {
        this.username = username;
        this.password = password;
        this.useHttps = useHttps;
        this.ignoreSsl = ignoreSsl;
        this.redfishRequestMaxRetries = redfishRequestRetries;
    }

    protected String buildRequestUrl(String hostAddress, RedfishCmdType cmd, String resourceId) {
        String urlHostAddress = validateAddressAndPrepareForUrl(hostAddress);
        String requestPath = getRequestPathForCommand(cmd, resourceId);

        if (useHttps) {
            return String.format("https://%s/%s", urlHostAddress, requestPath);
        } else {
            return String.format("http://%s/%s", urlHostAddress, requestPath);
        }
    }

    /**
     * Executes a GET request for the given URL address.
     */
    protected HttpResponse executeGetRequest(String url) {
        URIBuilder builder = null;
        HttpGet httpReq = null;
        try {
            builder = new URIBuilder(url);
            httpReq = new HttpGet(builder.build());
        } catch (URISyntaxException e) {
            throw new RedfishException(String.format("Failed to create URI for GET request [URL: %s] due to exception.", url), e);
        }

        prepareHttpRequestBasicAuth(httpReq);
        return executeHttpRequest(url, httpReq);
    }

    /**
     * Executes a POST request for the given URL address and Json object.
     */
    private HttpResponse executePostRequest(String url, JsonObject jsonToSend) {
        HttpPost httpReq = null;
        try {
            URIBuilder builder = new URIBuilder(url);
            httpReq = new HttpPost(builder.build());
            httpReq.addHeader(HTTP.CONTENT_TYPE, APPLICATION_JSON);
            httpReq.setEntity(new StringEntity(jsonToSend.toString()));
        } catch (URISyntaxException | UnsupportedEncodingException e) {
            throw new RedfishException(String.format("Failed to create URI for POST request [URL: %s] due to exception.", url), e);
        }

        prepareHttpRequestBasicAuth(httpReq);
        return executeHttpRequest(url, httpReq);
    }

    /**
     * Prepare http request to accept JSON and basic authentication
     */
    private void prepareHttpRequestBasicAuth(HttpRequestBase httpReq) {
        httpReq.addHeader(ACCEPT, APPLICATION_JSON);
        String encoding = basicAuth(username, password);
        httpReq.addHeader("Authorization", encoding);
    }

    /**
     * Encodes 'username:password' into 64-base encoded String
     */
    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
    }

    /**
     * Executes Http request according to URL and HttpRequestBase (e.g. HttpGet, HttpPost)
     */
    private HttpResponse executeHttpRequest(String url, HttpRequestBase httpReq) {
        HttpClient client = null;
        if (ignoreSsl) {
            try {
                client = ignoreSSLCertValidator();
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RedfishException(String.format("Failed to handle SSL Cert validator on POST request [URL: %s] due to exception.", url), e);
            }
        } else {
            client = HttpClientBuilder.create().build();
        }

        try {
            return client.execute(httpReq);
        } catch (IOException e) {
            if (redfishRequestMaxRetries == 0) {
                throw new RedfishException(String.format("Failed to execute HTTP %s request [URL: %s] due to exception %s.", httpReq.getMethod(), url, e), e);
            }
            return retryHttpRequest(url, httpReq, client);
        }
    }

    protected HttpResponse retryHttpRequest(String url, HttpRequestBase httpReq, HttpClient client) {
        logger.warn(String.format("Failed to execute HTTP %s request [URL: %s]. Executing the request again.", httpReq.getMethod(), url));
        HttpResponse response = null;
        for (int attempt = 1; attempt < redfishRequestMaxRetries + 1; attempt++) {
            try {
                TimeUnit.SECONDS.sleep(WAIT_FOR_REQUEST_RETRY);
                logger.debug(String.format("HTTP %s request retry attempt %d/%d [URL: %s].", httpReq.getMethod(), attempt, redfishRequestMaxRetries, url));
                response = client.execute(httpReq);
                break;
            } catch (IOException | InterruptedException e) {
                if (attempt == redfishRequestMaxRetries) {
                    throw new RedfishException(String.format("Failed to execute HTTP %s request retry attempt %d/%d [URL: %s] due to exception %s", httpReq.getMethod(), attempt, redfishRequestMaxRetries,url, e));
                } else {
                    logger.warn(
                            String.format("Failed to execute HTTP %s request retry attempt %d/%d [URL: %s] due to exception %s", httpReq.getMethod(), attempt, redfishRequestMaxRetries,
                                    url, e));
                }
            }
        }

        if (response == null) {
            throw new RedfishException(String.format("Failed to execute HTTP %s request [URL: %s].", httpReq.getMethod(), url));
        }

        logger.debug(String.format("Successfully executed HTTP %s request [URL: %s].", httpReq.getMethod(), url));
        return response;
    }

    /**
     *  Returns the proper URL path for the given Redfish command ({@link RedfishCmdType}).
     */
    private String getRequestPathForCommand(RedfishCmdType cmd, String resourceId) {
        switch (cmd) {
        case GetSystemId:
            return SYSTEMS_URL_PATH;
        case GetManagerId:
            return MANAGERS_URL_PATH;
        case getChassisId:
            return CHASSIS_URL_PATH;
        case GetPowerState:
            if (StringUtils.isBlank(resourceId)) {
                throw new RedfishException(String.format("Command '%s' requires a valid resource ID '%s'.", cmd, resourceId));
            }
            return String.format("%s/%s", SYSTEMS_URL_PATH, resourceId);
        case ComputerSystemReset:
            if (StringUtils.isBlank(resourceId)) {
                throw new RedfishException(String.format("Command '%s' requires a valid resource ID '%s'.", cmd, resourceId));
            }
            return String.format("%s/%s/%s", SYSTEMS_URL_PATH, resourceId, COMPUTER_SYSTEM_RESET_URL_PATH);
        default:
            throw new RedfishException(String.format("Redfish client does not support command '%s'.", cmd));
        }
    }

    /**
     * Validates the host address. It needs to be either a valid host domain name, or a valid IP address (IPv6 or IPv4).
     */
    protected String validateAddressAndPrepareForUrl(String hostAddress) {
        if (NetUtils.isValidIp6(hostAddress)) {
            return String.format("[%s]", hostAddress);
        } else if (NetUtils.isValidIp4(hostAddress)) {
            return hostAddress;
        } else if (InternetDomainName.isValid(hostAddress)) {
            return hostAddress;
        } else {
            throw new RedfishException(String.format("Redfish host address '%s' is not a valid IPv4/IPv6 address nor a valid domain name.", hostAddress));
        }
    }

    /**
     * Sends a POST request for a ComputerSystem with the Reset command ({@link RedfishResetCmd}, e.g. RedfishResetCmd.GracefulShutdown). </br> </br>
     * <b>URL example:</b> https://[host address]/redfish/v1/Systems/[System ID]/Actions/ComputerSystem.Reset
     */
    public void executeComputerSystemReset(String hostAddress, RedfishResetCmd resetCommand) {
        String systemId = getSystemId(hostAddress);
        String url = buildRequestUrl(hostAddress, RedfishCmdType.ComputerSystemReset, systemId);
        JsonObject resetType = new JsonObject();
        resetType.addProperty(REDFISH_RESET_TYPE, resetCommand.toString());

        CloseableHttpResponse response = (CloseableHttpResponse)executePostRequest(url, resetType);

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode < HttpStatus.SC_OK || statusCode >= HttpStatus.SC_MULTIPLE_CHOICES) {
            throw new RedfishException(String.format("Failed to execute System power command for host by performing '%s' request on URL '%s' and host address '%s'. The expected HTTP status code is '%s' but it got '%s'.",
                    HttpPost.METHOD_NAME, url, hostAddress, EXPECTED_HTTP_STATUS, statusCode));
        }
        logger.debug(String.format("Sending ComputerSystem.Reset Command '%s' to host '%s' with request '%s %s'", resetCommand, hostAddress, HttpPost.METHOD_NAME, url));
    }

    /**
     *  Returns the System ID. Used when sending Computer System requests (e.g. ComputerSystem.Reset request).
     */
    public String getSystemId(String hostAddress) {
        String url = buildRequestUrl(hostAddress, RedfishCmdType.GetSystemId, null);
        CloseableHttpResponse response = (CloseableHttpResponse)executeGetRequest(url);

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            throw new RedfishException(String.format("Failed to get System ID for host '%s' with request '%s: %s'. HTTP status code expected '%s' but it got '%s'.", hostAddress,
                    HttpGet.METHOD_NAME, url, HttpStatus.SC_OK, statusCode));
        }

        String systemId = processGetSystemIdResponse(response);

        logger.debug(String.format("Retrieved System ID '%s' with request '%s: %s'", systemId, HttpGet.METHOD_NAME, url));

        return systemId;
    }

    /**
     * Processes the response of request GET System ID as a JSON object.
     */
    protected String processGetSystemIdResponse(CloseableHttpResponse response) {
        InputStream in;
        String jsonString;
        try {
            in = response.getEntity().getContent();
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            jsonString = streamReader.lines().collect(Collectors.joining());
        } catch (UnsupportedOperationException | IOException e) {
            throw new RedfishException("Failed to process system Response", e);
        }

        // retrieving the system ID (e.g. 'System.Embedded.1') via JsonParser:
        // (...) Members":[{"@odata.id":"/redfish/v1/Systems/System.Embedded.1"}] (...)
        JsonArray jArray = null;
        JsonElement jsonElement = new JsonParser().parse(jsonString);
        if (jsonElement.getAsJsonObject().get(MEMBERS) != null) {
            jArray = jsonElement.getAsJsonObject().get(MEMBERS).getAsJsonArray();
        } else if (jsonElement.getAsJsonObject().get(LINKS) != null){
            jArray = jsonElement.getAsJsonObject().get(LINKS).getAsJsonObject().get(MEMBERS).getAsJsonArray();
        }
        if (jArray == null || jArray.size() < 1) {
            throw new CloudRuntimeException("Members not found in the Redfish Systems JSON, unable to determine Redfish system ID");
        }
        JsonObject jsonObject = jArray.get(0).getAsJsonObject();
        String jsonObjectAsString = jsonObject.get(ODATA_ID).getAsString();
        String[] arrayOfStrings = StringUtils.split(jsonObjectAsString, '/');

        return arrayOfStrings[arrayOfStrings.length - 1];
    }

    /**
     * Returns the Redfish system Power State ({@link RedfishPowerState}).
     */
    public RedfishPowerState getSystemPowerState(String hostAddress) {
        String systemId = getSystemId(hostAddress);

        String url = buildRequestUrl(hostAddress, RedfishCmdType.GetPowerState, systemId);
        CloseableHttpResponse response = (CloseableHttpResponse)executeGetRequest(url);

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            throw new RedfishException(String.format("Failed to get System power state for host '%s' with request '%s: %s'. The expected HTTP status code is '%s' but it got '%s'.",
                    HttpGet.METHOD_NAME, url, hostAddress, HttpStatus.SC_OK, statusCode));
        }

        RedfishPowerState powerState = processGetSystemRequestResponse(response);
        logger.debug(String.format("Retrieved System power state '%s' with request '%s: %s'", powerState, HttpGet.METHOD_NAME, url));
        return powerState;
    }

    /**
     * Process 'ComputerSystem' GET request response and return as a RedfishPowerState
     */
    protected RedfishPowerState processGetSystemRequestResponse(CloseableHttpResponse response) {
        InputStream in;
        String jsonString = null;
        try {
            in = response.getEntity().getContent();
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            jsonString = streamReader.lines().collect(Collectors.joining());
            String powerState = new JsonParser().parse(jsonString).getAsJsonObject().get(POWER_STATE).getAsString();
            return RedfishPowerState.valueOf(powerState);
        } catch (UnsupportedOperationException | IOException e) {
            throw new RedfishException("Failed to process system response due to exception", e);
        }
    }

    /**
     * Ignores SSL certififcation validator.
     */
    private CloseableHttpClient ignoreSSLCertValidator() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[]{new TrustAllManager()};

        SSLContext sslContext = SSLContext.getInstance("SSL");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };

        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(sslContext, allHostsValid);
        return HttpClientBuilder.create().setSSLSocketFactory(socketFactory).build();
    }

    public String getManagerOrChassisId(String hostAddress, RedfishCmdType type) {
        String url = buildRequestUrl(hostAddress, type, null);
        CloseableHttpResponse response = (CloseableHttpResponse)executeGetRequest(url);

        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode != HttpStatus.SC_OK) {
            throw new RedfishException(String.format("Failed to get System ID for host '%s' with request '%s: %s'. HTTP status code expected '%s' but it got '%s'.", hostAddress,
                    HttpGet.METHOD_NAME, url, HttpStatus.SC_OK, statusCode));
        }

        String systemId = processGetSystemIdResponse(response);

        logger.debug(String.format("Retrieved System ID '%s' with request '%s: %s'", systemId, HttpGet.METHOD_NAME, url));

        return systemId;
    }

    protected String buildRequestCustomUrl(String hostAddress, String requestPath) {
        String urlHostAddress = validateAddressAndPrepareForUrl(hostAddress);

        requestPath = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (useHttps) {
            return String.format("https://%s/%s", urlHostAddress, requestPath);
        } else {
            return String.format("http://%s/%s", urlHostAddress, requestPath);
        }
    }

    public String executeRedfishData(String hostAddress, String category) {
        String systemId = getSystemId(hostAddress);
        String managerId = getManagerOrChassisId(hostAddress, RedfishCmdType.GetManagerId);
        String chassisId = getManagerOrChassisId(hostAddress, RedfishCmdType.getChassisId);
        String url;
        CloseableHttpResponse response;
        JsonObject root;
        JsonArray retArray = new JsonArray();
        JsonObject retObj = new JsonObject();

        switch (category.toLowerCase()) {
            case "summary":
                logger.info(":::::SUMMARY DATA::::::::");
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s", SYSTEMS_URL_PATH, systemId));
                response = (CloseableHttpResponse) executeGetRequest(url);
                root = parseRedfishJsonResponse(response);
                return root.toString();
            case "processor":
                logger.info(":::::PROCESSOR DATA::::::::");
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", SYSTEMS_URL_PATH, systemId, "Processors"));

                try {
                    // OkHttp로 Members 병렬 상세조회
                    JsonObject processorRoot = getAsync(url).join();
                    if (processorRoot.has("Members") && processorRoot.get("Members").isJsonArray()) {
                        JsonArray members = processorRoot.getAsJsonArray("Members");
                        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
                        for (JsonElement member : members) {
                            String odataId = member.getAsJsonObject().get("@odata.id").getAsString();
                            String detailUrl = buildRequestCustomUrl(hostAddress, odataId);
                            futures.add(getAsync(detailUrl));
                        }
                        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                        allDone.join();

                        JsonArray resultArray = new JsonArray();
                        for (CompletableFuture<JsonObject> future : futures) {
                            resultArray.add(future.join());
                        }
                        return resultArray.toString();
                    } else {
                        return "{}";
                    }
                } catch (Exception ex) {
                    logger.error("Redfish processors fetch failed", ex);
                    return "{}";
                }
            case "memory":
                logger.info(":::::MEMORY DATA::::::::");
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", SYSTEMS_URL_PATH, systemId, "Memory"));
                try {
                    JsonObject memoryRoot = getAsync(url).join();

                    // OEM 정보
                    if (memoryRoot.has("Oem")) {
                        JsonObject oem = memoryRoot.getAsJsonObject("Oem");
                        if (oem.has("Hpe")) {
                            JsonObject hpe = oem.getAsJsonObject("Hpe");
                            retObj.add("memmorysummary", hpe);
                        }
                    }

                    // Members 상세 병렬 조회
                    if (memoryRoot.has("Members") && memoryRoot.get("Members").isJsonArray()) {
                        JsonArray members = memoryRoot.getAsJsonArray("Members");
                        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
                        for (JsonElement member : members) {
                            String odataId = member.getAsJsonObject().get("@odata.id").getAsString();
                            String detailUrl = buildRequestCustomUrl(hostAddress, odataId);
                            futures.add(getAsync(detailUrl));
                        }
                        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                        allDone.join();

                        JsonArray memListInfo = new JsonArray();
                        for (CompletableFuture<JsonObject> future : futures) {
                            memListInfo.add(future.join());
                        }
                        retObj.add("memlistinfo", memListInfo);
                        return retObj.toString();
                    } else {
                        return "{}";
                    }
                } catch (Exception ex) {
                    logger.error("Redfish memory fetch failed", ex);
                    return "{}";
                }
            case "network":
                logger.info(":::::NETWORK DATA::::::::");
                    url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", CHASSIS_URL_PATH, chassisId, "NetworkAdapters"));
                    root = getAsyncSync(url);

                    if (root.has("Members") && root.get("Members").isJsonArray()) {
                        JsonArray members = root.getAsJsonArray("Members");
                        List<CompletableFuture<JsonObject>> futures = new ArrayList<>();

                        for (JsonElement member : members) {
                            String odataId = member.getAsJsonObject().get("@odata.id").getAsString();
                            String detailUrl = buildRequestCustomUrl(hostAddress, odataId);
                            // NetworkAdapter 상세 조회
                            futures.add(CompletableFuture.supplyAsync(() -> {
                                JsonObject netDetail = getAsyncSync(detailUrl);
                                // 포트 정보 병렬 조회
                                JsonArray portArray = new JsonArray();
                                if (netDetail.has("Controllers")) {
                                    JsonObject controllers = netDetail.getAsJsonArray("Controllers").get(0).getAsJsonObject();
                                    if (controllers.has("Links")) {
                                        JsonObject links = controllers.getAsJsonObject("Links");
                                        String portsKey = links.has("Ports") ? "Ports" : "NetworkPorts";
                                        if (links.has(portsKey)) {
                                            JsonArray ports = links.getAsJsonArray(portsKey);
                                            List<CompletableFuture<JsonObject>> portFutures = new ArrayList<>();
                                            for (JsonElement portElem : ports) {
                                                String portOdataId = portElem.getAsJsonObject().get("@odata.id").getAsString();
                                                String portUrl = buildRequestCustomUrl(hostAddress, portOdataId);
                                                portFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(portUrl), executor));
                                            }
                                            CompletableFuture.allOf(portFutures.toArray(new CompletableFuture[0])).join();
                                            for (CompletableFuture<JsonObject> pf : portFutures) {
                                                portArray.add(pf.join());
                                            }
                                        }
                                    }
                                }
                                netDetail.add("port", portArray);
                                return netDetail;
                            }, executor));
                        }
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                        JsonArray resultArray = new JsonArray();
                        for (CompletableFuture<JsonObject> f : futures) {
                            resultArray.add(f.join());
                        }
                        return resultArray.toString();
                    } else {
                        return "{}";
                    }
            case "storage":
                logger.info(":::::STORAGE DATA::::::::");
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", SYSTEMS_URL_PATH, systemId, "Storage"));
                root = getAsyncSync(url);

                JsonArray controllerList = new JsonArray();
                JsonArray volumeList = new JsonArray();
                JsonArray driveList = new JsonArray();
                JsonArray enclosureList = new JsonArray();

                Set<String> controllerSet = new HashSet<>();
                Set<String> volumeSet = new HashSet<>();
                Set<String> driveSet = new HashSet<>();
                Set<String> enclosureSet = new HashSet<>();

                retObj = new JsonObject();

                if (root.has("Members") && root.get("Members").isJsonArray()) {
                    JsonArray storageMembers = root.getAsJsonArray("Members");
                    List<CompletableFuture<JsonObject>> sMemberFutures = new ArrayList<>();

                    for (JsonElement sMember : storageMembers) {
                        String sOdataId = sMember.getAsJsonObject().get("@odata.id").getAsString();
                        String sDetailUrl = buildRequestCustomUrl(hostAddress, sOdataId);
                        sMemberFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(sDetailUrl), executor));
                    }
                    CompletableFuture.allOf(sMemberFutures.toArray(new CompletableFuture[0])).join();

                    for (int i = 0; i < sMemberFutures.size(); i++) {
                        JsonObject rootObj = sMemberFutures.get(i).join();
                        String sOdataId = storageMembers.get(i).getAsJsonObject().get("@odata.id").getAsString();

                        // Controller
                        String controllerUrl = buildRequestCustomUrl(hostAddress, sOdataId + "/Controllers");
                        JsonObject controllerObj = getAsyncSync(controllerUrl);
                        if (controllerObj.has("Members") && controllerObj.get("Members").isJsonArray()) {
                            JsonArray ctrlMembers = controllerObj.getAsJsonArray("Members");
                            List<CompletableFuture<JsonObject>> ctrlFutures = new ArrayList<>();
                            for (JsonElement ctrl : ctrlMembers) {
                                String ctrlOdataId = ctrl.getAsJsonObject().get("@odata.id").getAsString();
                                if (controllerSet.add(ctrlOdataId)) {
                                    String ctrlUrl = buildRequestCustomUrl(hostAddress, ctrlOdataId);
                                    ctrlFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(ctrlUrl), executor));
                                }
                            }
                            CompletableFuture.allOf(ctrlFutures.toArray(new CompletableFuture[0])).join();
                            for (CompletableFuture<JsonObject> cf : ctrlFutures) controllerList.add(cf.join());
                        }

                        // Volume
                        if (rootObj.has("Volumes")) {
                            JsonObject volumesObj = rootObj.getAsJsonObject("Volumes");
                            if (volumesObj.has("@odata.id")) {
                                String volumesOdataId = volumesObj.get("@odata.id").getAsString();
                                String volumesUrl = buildRequestCustomUrl(hostAddress, volumesOdataId);
                                JsonObject volumesObjDetail = getAsyncSync(volumesUrl);
                                if (volumesObjDetail.has("Members") && volumesObjDetail.get("Members").isJsonArray()) {
                                    JsonArray volMembers = volumesObjDetail.getAsJsonArray("Members");
                                    List<CompletableFuture<JsonObject>> volFutures = new ArrayList<>();
                                    for (JsonElement vol : volMembers) {
                                        String volOdataId = vol.getAsJsonObject().get("@odata.id").getAsString();
                                        if (volumeSet.add(volOdataId)) {
                                            String volUrl = buildRequestCustomUrl(hostAddress, volOdataId);
                                            volFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(volUrl), executor));
                                        }
                                    }
                                    CompletableFuture.allOf(volFutures.toArray(new CompletableFuture[0])).join();
                                    for (CompletableFuture<JsonObject> vf : volFutures) volumeList.add(vf.join());
                                }
                            }
                        }
                        // Drive
                        if (rootObj.has("Drives") && rootObj.get("Drives").isJsonArray()) {
                            JsonArray drivesMembers = rootObj.getAsJsonArray("Drives");
                            List<CompletableFuture<JsonObject>> drvFutures = new ArrayList<>();
                            for (JsonElement drive : drivesMembers) {
                                String driveOdataId = drive.getAsJsonObject().get("@odata.id").getAsString();
                                if (driveSet.add(driveOdataId)) {
                                    String drvUrl = buildRequestCustomUrl(hostAddress, driveOdataId);
                                    drvFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(drvUrl), executor));
                                }
                            }
                            CompletableFuture.allOf(drvFutures.toArray(new CompletableFuture[0])).join();
                            for (CompletableFuture<JsonObject> df : drvFutures) driveList.add(df.join());
                        }
                        // Enclosure
                        if (rootObj.has("Links")) {
                            JsonObject links = rootObj.getAsJsonObject("Links");
                            if (links.has("Enclosures")) {
                                JsonArray enclosures = links.getAsJsonArray("Enclosures");
                                List<CompletableFuture<JsonObject>> encFutures = new ArrayList<>();
                                for (JsonElement enc : enclosures) {
                                    String encOdataId = enc.getAsJsonObject().get("@odata.id").getAsString();
                                    if (enclosureSet.add(encOdataId)) {
                                        String encUrl = buildRequestCustomUrl(hostAddress, encOdataId);
                                        encFutures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(encUrl), executor));
                                    }
                                }
                                CompletableFuture.allOf(encFutures.toArray(new CompletableFuture[0])).join();
                                for (CompletableFuture<JsonObject> ef : encFutures) enclosureList.add(ef.join());
                            }
                        }
                    }
                    retObj.add("controllerList", controllerList);
                    retObj.add("volumeList", volumeList);
                    retObj.add("driveList", driveList);
                    retObj.add("enclosureList", enclosureList);
                    return retObj.toString();
                } else {
                    return "{}";
                }
            case "device":
                logger.info(":::::DEVICE DATA::::::::");
                // HPE/PCIe 분기 처리
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s", CHASSIS_URL_PATH, chassisId));
                root = getAsyncSync(url);

                if (root.has("Oem") && root.getAsJsonObject("Oem").has("Hpe")) {
                    url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", CHASSIS_URL_PATH, chassisId, "Devices"));
                    root = getAsyncSync(url);
                } else {
                    url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", CHASSIS_URL_PATH, chassisId, "PCIeDevices"));
                    root = getAsyncSync(url);
                }

                if (root.has("Members") && root.get("Members").isJsonArray()) {
                    JsonArray members = root.getAsJsonArray("Members");
                    List<CompletableFuture<JsonObject>> futures = new ArrayList<>();

                    for (JsonElement member : members) {
                        String odataId = member.getAsJsonObject().get("@odata.id").getAsString();
                        String detailUrl = buildRequestCustomUrl(hostAddress, odataId);
                        futures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(detailUrl), executor));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    JsonArray resultArray = new JsonArray();
                    for (CompletableFuture<JsonObject> f : futures) {
                        resultArray.add(f.join());
                    }
                    retObj.add("devicelist", resultArray);
                    return retObj.toString();
                } else {
                    return "{}";
                }
            case "firmware":
                logger.info(":::::FIRMWARE DATA::::::::");
                url = buildRequestCustomUrl(hostAddress, FIRMWARE_URL_PATH);
                root = getAsyncSync(url);
                if (root.has("Members") && root.get("Members").isJsonArray()) {
                    JsonArray members = root.getAsJsonArray("Members");
                    List<CompletableFuture<JsonObject>> futures = new ArrayList<>();

                    for (JsonElement member : members) {
                        String odataId = member.getAsJsonObject().get("@odata.id").getAsString();
                        String detailUrl = buildRequestCustomUrl(hostAddress, odataId);
                        futures.add(CompletableFuture.supplyAsync(() -> getAsyncSync(detailUrl), executor));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                    JsonArray resultArray = new JsonArray();
                    for (CompletableFuture<JsonObject> f : futures) {
                        resultArray.add(f.join());
                    }
                    retObj.add("firmwarelist", resultArray);
                    return retObj.toString();
                } else {
                    return "{}";
                }
            case "log":
                 logger.info(":::::LOG DATA::::::::");

                // 1. SYSTEMS_URL_PATH 쪽 LogServices 먼저 시도
                url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", SYSTEMS_URL_PATH, systemId, "LogServices"));
                JsonObject logRoot = getAsync(url).join();
                boolean hasMembers = logRoot.has("Members") && logRoot.get("Members").isJsonArray();

                // 2. Members가 없으면 MANAGERS_URL_PATH 쪽 LogServices 시도
                if (!hasMembers) {
                    url = buildRequestCustomUrl(hostAddress, String.format("%s%s/%s", MANAGERS_URL_PATH, managerId, "LogServices"));
                    logRoot = getAsync(url).join();
                    hasMembers = logRoot.has("Members") && logRoot.get("Members").isJsonArray();
                    if (!hasMembers) {
                        return "{}";
                    }
                }

                // 3. Members 상세 Entries 비동기 병렬 조회
                JsonArray members = logRoot.getAsJsonArray("Members");
                List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
                for (JsonElement member : members) {
                    String odataId = member.getAsJsonObject().get("@odata.id").getAsString();

                    String lastPart = odataId.substring(odataId.lastIndexOf('/') + 1);
                    if (lastPart.contains("Event") || lastPart.contains("Fault")) continue;

                    String logServiceUrl = buildRequestCustomUrl(hostAddress, odataId);

                    // 3-1. LogService 정보 병렬 조회 (name 속성 포함)
                    CompletableFuture<JsonObject> logServiceFuture = getAsync(logServiceUrl);

                    // 3-2. LogEntries 정보 병렬 조회
                    String entriesUrl = buildRequestCustomUrl(hostAddress, odataId + "/Entries");
                    CompletableFuture<JsonObject> logEntriesFuture = getAsync(entriesUrl);

                    // 3-3. 두 요청 모두 완료되면 name 속성 주입하여 logEntriesObj 반환
                    CompletableFuture<JsonObject> combinedFuture = logServiceFuture.thenCombine(logEntriesFuture, (logServiceObj, logEntriesObj) -> {
                        if (logServiceObj.has("Name")) {
                            logEntriesObj.addProperty("name", logServiceObj.get("Name").getAsString());
                        }
                        return logEntriesObj;
                    });

                    futures.add(combinedFuture);
                }

                // 모든 비동기 요청 완료까지 대기
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                List<JsonObject> logEntryList = new ArrayList<>();
                for (CompletableFuture<JsonObject> f : futures) {
                    logEntryList.add(f.join());
                }

                // 정렬: "Id" 또는 "id" 값을 기준으로 내림차순
                logEntryList.sort((a, b) -> {
                    String aId = a.has("Id") ? a.get("Id").getAsString() : (a.has("id") ? a.get("id").getAsString() : "");
                    String bId = b.has("Id") ? b.get("Id").getAsString() : (b.has("id") ? b.get("id").getAsString() : "");
                    // 숫자형일 때
                    try {
                        long aNum = Long.parseLong(aId.replaceAll("[^0-9]", ""));
                        long bNum = Long.parseLong(bId.replaceAll("[^0-9]", ""));
                        return Long.compare(bNum, aNum); // 내림차순
                    } catch (NumberFormatException e) {
                        // 문자열일 때
                        return bId.compareTo(aId);
                    }
                });

                retArray = new JsonArray();
                for (JsonObject obj : logEntryList) {
                    retArray.add(obj);
                }

                retObj.add("loglist", retArray);
                return retObj.toString();
            default:
                throw new IllegalArgumentException("Unknown category: " + category);
        }
    }

    // Util: JsonObject 반환 메서드 (한 번만 InputStream 읽도록)
    protected JsonObject parseRedfishJsonResponse(CloseableHttpResponse response) {
        try (
            InputStream in = response.getEntity().getContent();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        ) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JsonParser().parse(sb.toString()).getAsJsonObject();
        } catch (UnsupportedOperationException | IOException e) {
            throw new RedfishException("Failed to process system response due to exception", e);
        }
    }
    private OkHttpClient getClient() {
        return ignoreSsl ? unsafeClient : safeClient;
    }

    private CompletableFuture<JsonObject> getAsync(String url) {
        OkHttpClient client = getClient();
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("Accept", "application/json");
        if (username != null && !username.isEmpty()) {
            String credential = Credentials.basic(username, password);
            builder.header("Authorization", credential);
        }
        Request request = builder.build();

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String body = responseBody != null ? responseBody.string() : "";
                    JsonObject json = new JsonParser().parse(body).getAsJsonObject();
                    future.complete(json);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }

    private JsonObject getAsyncSync(String url) {
        try {
            return getAsync(url).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void shutdownExecutor() {
        executor.shutdown();
    }

    // 싱글톤 unsafe OkHttpClient 생성 메서드
    private static OkHttpClient createUnsafeClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
            };
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(300, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
