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
package com.cloud.dr.cluster;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.dr.cluster.GetDisasterRecoveryClusterListResponse;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.XML;
import org.json.JSONObject;

import com.cloud.utils.nio.TrustAllManager;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

public class DisasterRecoveryClusterUtil {

    protected static Logger LOGGER = LogManager.getLogger(DisasterRecoveryClusterUtil.class);
    protected final static String boundary = Long.toHexString(System.currentTimeMillis());
    protected final static String LINE_FEED = "\r\n";
    protected final static String charset = "UTF-8";
    protected static OutputStream outputStream;
    protected static PrintWriter writer;

    // :::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    // Glue API
    // :::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::

    /**
     * Glue 상태 조회
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /glue
     * @param method
     *  GET
     * @return
     *  String
     *  ex) {"checks": "string", "mutes": "string", "status": "HEALTH_WARN"}
     */
    protected static String glueStatusAPI(String region, String subUrl, String method) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(sb.toString());
                String health = jObject.get("health").toString();
                return health;
            } else {
                String msg = "Failed to request glue status API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return null;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Glue 미러링 상태 조회
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror
     * @param method
     *  GET
     * @return
     *  String
     *  ex) {"daemon_health": "string", "health": "string", "image_health": "string", "states": "string"}
     */
    protected static String glueMirrorStatusAPI(String region, String subUrl, String method) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(sb.toString());
                String daemonHealth = jObject.get("daemon_health").toString();
                return daemonHealth;
            } else {
                // mirror pool 비활성화된 경우 처리
                if (connection.getResponseCode() == 500) {
                    String daemonHealth = "DISABLED";
                    return daemonHealth;
                } else {
                    String msg = "Failed to request glue mirror status API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Glue 미러링 클러스터 설정
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror
     * @param method
     *  POST
     * @param parameter
     *  localClusterName("local"), remoteClusterName("remote"), host(<scvmIP>), privateKeyFile(<scvmKey>), mirrorPool("rbd")
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueMirrorSetupAPI(String region, String subUrl, String method, Map<String, String> params, File privateKey) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Connection","Keep-Alive");
            connection.setRequestProperty("Content-type", "multipart/form-data;charset=" + charset + ";boundary=" + boundary);
            outputStream = connection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
            for(Map.Entry<String, String> param : params.entrySet()){
                String key = param.getKey();
                String value = param.getValue();
                addTextPart(key, value);
            }
            addFilePart("privateKeyFile", privateKey);
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.close();
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror setup API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링 클러스터 제거
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror
     * @param method
     *  DELETE
     * @param parameter
     *  host(string), privateKeyFile(file), mirrorPool(string)
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueMirrorDeleteAPI(String region, String subUrl, String method, Map<String, String> params, File privateKey) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Connection","Keep-Alive");
            connection.setRequestProperty("Content-type", "multipart/form-data;charset=" + charset + ";boundary=" + boundary);
            outputStream = connection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
            for(Map.Entry<String, String> param : params.entrySet()){
                String key = param.getKey();
                String value = param.getValue();
                addTextPart(key, value);
            }
            addFilePart("privateKeyFile", privateKey);
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.close();
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror delete API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링 클러스터 활성화
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/mirrorPool(string)
     * @param method
     *  POST
     * @param parameter
     *  localClusterName("local"), remoteClusterName("remote"), host(<scvmIP>), privateKeyFile(<scvmKey>), mirrorPool("rbd")
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueMirrorEnableAPI(String region, String subUrl, String method, Map<String, String> params, File privateKey) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Connection","Keep-Alive");
            connection.setRequestProperty("Content-type", "multipart/form-data;charset=" + charset + ";boundary=" + boundary);
            outputStream = connection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
            for(Map.Entry<String, String> param : params.entrySet()){
                String key = param.getKey();
                String value = param.getValue();
                addTextPart(key, value);
            }
            addFilePart("privateKeyFile", privateKey);
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.close();
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror enable API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링 클러스터 비활성화
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/mirrorPool(string)
     * @param method
     *  DELETE
     * @param parameter
     *  host(string), privateKeyFile(file), mirrorPool(string)
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueMirrorDisableAPI(String region, String subUrl, String method, Map<String, String> params, File privateKey) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Connection","Keep-Alive");
            connection.setRequestProperty("Content-type", "multipart/form-data;charset=" + charset + ";boundary=" + boundary);
            outputStream = connection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
            for(Map.Entry<String, String> param : params.entrySet()){
                String key = param.getKey();
                String value = param.getValue();
                addTextPart(key, value);
            }
            addFilePart("privateKeyFile", privateKey);
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.close();
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror disable API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링 이미지 프로모트
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/image/promote/<mirrorPool>/<imageName>
     * @param method
     *  POST
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueImageMirrorPromoteAPI(String region, String subUrl, String method) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror image promote API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                LOGGER.error(response);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링 이미지 디모트
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/image/demote/<mirrorPool>/<imageName>
     * @param method
     *  DELETE
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueImageMirrorDemoteAPI(String region, String subUrl, String method) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror image demote API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                LOGGER.error(response);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 미러링된 이미지 목록 조회
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/image
     * @param method
     *  GET
     * @return
     *  String
     *  {"Local": [{"image": "string","items": [{"interval": "string","start_time": "string"}],"namespace": "string","pool": "string"}],
        "Remote": [{"image": "string","items": [{"interval": "string","start_time": "string"}],"namespace": "string","pool": "string"}]}
     */
    protected static String glueImageMirrorAPI(String region, String subUrl, String method) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
                JsonParser jParser = new JsonParser();
                JsonObject jObject = (JsonObject)jParser.parse(sb.toString());
                return jObject.toString();
            } else {
                String msg = "Failed to request list of mirrored snapshot API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return null;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Glue 이미지 미러링 설정
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/image/mirrorPool(string)/imageName(string)
     * @param method
     *  POST
     * @param parameter
     *  mirrorPool(string), imageName(string), interval(string), startTime(string)
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueImageMirrorSetupAPI(String region, String subUrl, String method, Map<String, String> params) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            StringBuilder postData = new StringBuilder();
            for(Map.Entry<String, String> param : params.entrySet()){
                postData.append("&");
                postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
                postData.append("=");
                postData.append(URLEncoder.encode(String.valueOf(param.getValue()), "UTF-8"));
            }
            byte[] postDataBytes = postData.toString().getBytes("UTF-8");
            connection.getOutputStream().write(postDataBytes);
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "UTF-8"));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror image setup API. response code : " + connection.getResponseCode();
                LOGGER.error(response);
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * Glue 이미지 미러링 설정 변경
     * @param region
     *  https://<IP>:8080/api/v1
     * @param subUrl
     *  /mirror/image/mirrorPool(string)/imageName(string)
     * @param method
     *  PATCH
     * @param parameter
     *  mirrorPool(string), imageName(string), interval(string), startTime(string)
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static boolean glueImageMirrorSetupUpdateAPI(String region, String subUrl, String method, Map<String, String> params) {
        try {
            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            URL url = new URL(region + subUrl);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Authorization", "application/vnd.ceph.api.v1.0+json");
            connection.setRequestProperty("Connection","Keep-Alive");
            connection.setRequestProperty("Content-type", "multipart/form-data;charset=" + charset + ";boundary=" + boundary);
            outputStream = connection.getOutputStream();
            writer = new PrintWriter(new OutputStreamWriter(outputStream, charset), true);
            for(Map.Entry<String, String> param : params.entrySet()){
                String key = param.getKey();
                String value = param.getValue();
                addTextPart(key, value);
            }
            writer.append("--" + boundary + "--").append(LINE_FEED);
            writer.close();
            if (connection.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return true;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                String msg = "Failed to request glue mirror image setup update API. response code : " + connection.getResponseCode();
                LOGGER.error(msg);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Glue API endpoint not available"), e);
            return false;
        }
    }

    /**
     * @param params
     *  Map<String, String>
     *  ex) map.put("vol_name", "test");
     *  ex) map.put("size", "10");
     *  ...
     * @return String
     *  ex) vol_name=test&size=10
     */
    protected static String buildParamsGlue(Map<String, String> params) {
        StringBuffer paramString = new StringBuffer("");
        if (params != null) {
            try {
                for(Map.Entry<String, String> param : params.entrySet() ){
                    String key = param.getKey();
                    String value = param.getValue();
                    paramString.append("&" + param.getKey() + "=" + URLEncoder.encode(param.getValue(), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                LOGGER.error(e.getMessage());
                return null;
            }
        }
        return paramString.toString();
    }

    /**
     * glue post 요청 시 사용
     * @param key
     * @param value
     */
    protected static void addTextPart(String key, String value) throws IOException {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\""+key+"\"").append(LINE_FEED);
        writer.append("Content-Type: text/plain; charset=" + charset).append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }

    /**
     * glue post 요청 시 사용
     * @param key
     * @param value
     */
    protected static void addFilePart(String key, File file) throws IOException {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\""+key+"\"; filename=\"" + file.getName() + "\"").append(LINE_FEED);
        writer.append("Content-Type: octet-stream").append(LINE_FEED);
        writer.append("Content-Transfer-Encoding: binary").append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();

        FileInputStream inputStream = new FileInputStream(file);
        byte[] buffer = new byte[(int)file.length()];
        int bytesRead = -1;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();
        writer.append(LINE_FEED);
        writer.flush();
    }

    // :::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
    // Mold API
    // :::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::

    /**
     * Mold listScvmIpAddress API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  listScvmIpAddress
     * @param method
     *  GET
     * @param apiKey
     *  mold API Key
     * @param secretKey
     *  mold Secret Key
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldListScvmIpAddressAPI(String region, String command, String method, String apiKey, String secretKey) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, null);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode() + " , request command : " + command;
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode() + " , request command : " + command;
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("listscvmipaddressresponse");
            JSONObject ipaddress = (JSONObject) response.get("scvmipaddress");
            return ipaddress.get("ipaddress").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold getDisasterRecoveryClusterList API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  getDisasterRecoveryClusterList
     * @param method
     *  GET
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static List moldGetDisasterRecoveryClusterListAPI(String region, String command, String method, String apiKey, String secretKey) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, null);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode() + " , request command : " + command;
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode() + " , request command : " + command;
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("getdisasterrecoveryclusterlistresponse");
            List<GetDisasterRecoveryClusterListResponse> drList = new ArrayList<>();
            if (response.has("disasterrecoverycluster")) {
                Object drObject = response.get("disasterrecoverycluster");
                JSONArray drArray;
                if (drObject instanceof JSONArray) {
                    drArray = (JSONArray) drObject;
                } else {
                    drArray = new JSONArray();
                    drArray.put(drObject);
                }
                for (int i = 0; i < drArray.length(); i++) {
                    JSONObject drJSONObject = drArray.getJSONObject(i);
                    GetDisasterRecoveryClusterListResponse drResponse = new GetDisasterRecoveryClusterListResponse();
                    for (String key : drJSONObject.keySet()) {
                        try {
                            Field field = GetDisasterRecoveryClusterListResponse.class.getDeclaredField(key);
                            field.setAccessible(true);
                            Object value = getValue(drJSONObject, key, field.getType());
                            if (value != null) {
                                field.set(drResponse, value);
                            }
                        } catch (NoSuchFieldException e) {
                            // System.err.println("Field not found: " + key);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    }
                    drList.add(drResponse);
                }
            }
            return drList;
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold createDisasterRecoveryCluster API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  createDisasterRecoveryCluster
     * @param method
     *  POST
     * @param name
     *  primary cluster name
     * @param description
     *  primary cluster description
     * @param drClusterType
     *  primary
     * @param drClusterUrl
     *  primary cluster url
     * @param drClusterApiKey
     *  primary cluster api key
     * @param drClusterSecretKey
     *  primary cluster secret key
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldCreateDisasterRecoveryClusterAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("createdisasterrecoveryclusterresponse");
            return response.get("id").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold updateDisasterRecoveryCluster API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  updateDisasterRecoveryCluster
     * @param method
     *  GET
     * @param name
     *  primary cluster name
     * @param drClusterStatus
     *  primary cluster status
     * @param mirroringAgentStatus
     *  primary cluster mirroring agent status
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldUpdateDisasterRecoveryClusterAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("updatedisasterrecoveryclusterresponse");
            return response.get("disasterrecoverycluster").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold deleteDisasterRecoveryCluster API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  deleteDisasterRecoveryCluster
     * @param method
     *  GET
     * @param id
     *  primary cluster id
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldDeleteDisasterRecoveryClusterAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("deletedisasterrecoveryclusterresponse");
            return response.get("jobid").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold deployVirtualMachineForVolume API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  deployVirtualMachineForVolume
     * @param method
     *  POST
     * @param id
     *  primary cluster id
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldDeployVirtualMachineForVolumeAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("deployvirtualmachineforvolumeresponse");
            return response.get("jobid").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold listDiskOfferings API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  listDiskOfferings
     * @param method
     *  GET
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldListDiskOfferingsAPI(String region, String command, String method, String apiKey, String secretKey) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, null);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("listdiskofferingsresponse");
            String result = "";
            if (response.has("diskoffering")) {
                Object object = response.get("diskoffering");
                JSONArray array;
                if (object instanceof JSONArray) {
                    array = (JSONArray) object;
                } else {
                    array = new JSONArray();
                    array.put(object);
                }
                for (int i = 0; i < array.length(); i++) {
                    JSONObject jSONObject = array.getJSONObject(i);
                    LOGGER.info(jSONObject.get("iscustomized"));
                    if (jSONObject.get("iscustomized").equals(true)) {
                        result = jSONObject.get("id").toString();
                        break;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold listStoragePoolsMetrics API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  listStoragePoolsMetrics
     * @param method
     *  GET
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldListStoragePoolsMetricsAPI(String region, String command, String method, String apiKey, String secretKey) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, null);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("liststoragepoolsmetricsresponse");
            String result = "";
            if (response.has("storagepool")) {
                Object object = response.get("storagepool");
                JSONArray array;
                if (object instanceof JSONArray) {
                    array = (JSONArray) object;
                } else {
                    array = new JSONArray();
                    array.put(object);
                }
                for (int i = 0; i < array.length(); i++) {
                    JSONObject jSONObject = array.getJSONObject(i);
                    if (jSONObject.get("path").toString().equalsIgnoreCase("rbd")) {
                        result = jSONObject.toString();
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold listStoragePoolObjects API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  listStoragePoolObjects
     * @param method
     *  GET
     * @param id
     *  primary storage id
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldListStoragePoolObjectsAPI(String region, String command, String method, String apiKey, String secretKey,  Map<String, String> params, String volumeUuid) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("liststoragepoolobjectsresponse");
            String result = "";
            if (response.has("datastoreobject")) {
                Object object = response.get("datastoreobject");
                JSONArray array;
                if (object instanceof JSONArray) {
                    array = (JSONArray) object;
                } else {
                    array = new JSONArray();
                    array.put(object);
                }
                for (int i = 0; i < array.length(); i++) {
                    JSONObject jSONObject = array.getJSONObject(i);
                    if (jSONObject.get("name").toString().equalsIgnoreCase(volumeUuid)) {
                        result = jSONObject.toString();
                    }
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold listServiceOfferings, listNetworks API 요청
     * 재해복구용 가상머신 생성 모달에서 DR Secondary 클러스터를 선택했을 때 컴퓨트 오퍼링과 네트워크 목록을 불러오는 함수
     * @param region
     *  <url>/client/api/
     * @param command
     *  listServiceOfferings or listNetworks
     * @param method
     *  GET
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static List getSecDrClusterInfoList(String region, String command, String method, String apiKey, String secretKey) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, null);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            // mold api command에 따라 변경
            if (command.equals("listServiceOfferings")) {
                JSONObject jObject = XML.toJSONObject(sb.toString());
                JSONObject response = (JSONObject) jObject.get("listserviceofferingsresponse");
                List<ServiceOfferingResponse> serviceOfferingsList = new ArrayList<>();
                if (response.has("serviceoffering")) {
                    Object serviceOfferingObject = response.get("serviceoffering");
                    JSONArray serviceOfferingsArray;
                    if (serviceOfferingObject instanceof JSONArray) {
                        serviceOfferingsArray = (JSONArray) serviceOfferingObject;
                    } else {
                        serviceOfferingsArray = new JSONArray();
                        serviceOfferingsArray.put(serviceOfferingObject);
                    }
                    for (int i = 0; i < serviceOfferingsArray.length(); i++) {
                        JSONObject serviceOfferingJSONObject = serviceOfferingsArray.getJSONObject(i);
                        ServiceOfferingResponse serviceOfferingResponse = new ServiceOfferingResponse();
                        for (String key : serviceOfferingJSONObject.keySet()) {
                            try {
                                Field field = ServiceOfferingResponse.class.getDeclaredField(key);
                                field.setAccessible(true);

                                Object value = getValue(serviceOfferingJSONObject, key, field.getType());
                                if (value != null) {
                                    field.set(serviceOfferingResponse, value);
                                }
                            } catch (NoSuchFieldException e) {
                                // System.err.println("Field not found: " + key);
                            } catch (IllegalAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        serviceOfferingsList.add(serviceOfferingResponse);
                    }
                }
                return serviceOfferingsList;
            } else if (command.equals("listNetworks")) {
                JSONObject jObject = XML.toJSONObject(sb.toString());
                JSONObject response = jObject.optJSONObject("listnetworksresponse");
                List<NetworkResponse> networkResponsesList = new ArrayList<>();
                if (response != null && response.has("network")) {
                    Object networkObject = response.get("network");
                    JSONArray networksArray;
                    if (networkObject instanceof JSONArray) {
                        networksArray = (JSONArray) networkObject;
                    } else {
                        networksArray = new JSONArray();
                        networksArray.put(networkObject);
                    }
                    for (int i = 0; i < networksArray.length(); i++) {
                        JSONObject networkJSONObject = networksArray.getJSONObject(i);
                        NetworkResponse networkResponse = new NetworkResponse();
                        for (String key : networkJSONObject.keySet()) {
                            try {
                                Field field = NetworkResponse.class.getDeclaredField(key);
                                field.setAccessible(true);
                                Object value = getValue(networkJSONObject, key, field.getType());
                                if (value != null) {
                                    field.set(networkResponse, value);
                                }
                            } catch (NoSuchFieldException e) {
                            } catch (IllegalAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        networkResponsesList.add(networkResponse);
                    }
                }
                return networkResponsesList;
            } else {
                String msg = "Failed to request mold API.";
                LOGGER.error(msg);
                return null;
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold createVolume API 요청
     * @param region
     *  <url>/client/api/
     * @param command
     *  createVolume
     * @param method
     *  POST
     * @param diskOfferingId
     *  disk offering id
     * @param size
     *  disk size
     * @param name
     *  disk name
     * @param zoneid
     *  zone id
     * @return true = 200, 이외 코드는 false 처리
     */
    protected static String moldCreateVolumeAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection)url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
                if (connection.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    sb = new StringBuffer();
                    while ((readLine = br.readLine()) != null) {
                        sb.append(readLine);
                    }
                } else {
                    String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                    LOGGER.error(msg);
                    return null;
                }
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("createvolumeresponse");
            return response.get("id").toString();
        } catch (Exception e) {
            LOGGER.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    /**
     * Mold listServiceOfferings, listNetworks API 요청 시 사용
     * @param JSONObject
     * @param key
     * @param fieldType
     */
    private static Object getValue(JSONObject jsonObject, String key, Class<?> fieldType) {
        try {
            if (fieldType == String.class) {
                try {
                    return jsonObject.getString(key);
                } catch (JSONException e) {
                    return String.valueOf(jsonObject.get(key));
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @param command
     *  command Mold API 명
     * @param params
     *  Map<String, String>
     *  ex) map.put("accountId", "3");
     *  ex) map.put("storageId", "1");
     *  ...
     * @return String
     *  ex) command=listStoragePools&domain=ROOT&user=admin
     */
    protected static String buildParamsMold(String command, Map<String, String> params) {
        StringBuffer paramString = new StringBuffer("command=" + command);
        if (params != null) {
            try {
                for(Map.Entry<String, String> param : params.entrySet() ){
                    String key = param.getKey();
                    String value = param.getValue();
                    paramString.append("&" + param.getKey() + "=" + URLEncoder.encode(param.getValue(), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                LOGGER.error(e.getMessage());
                return null;
            }
        }
        return paramString.toString();
    }

    /**
     * Mold API 요청 최종 URL 생성
     */
    private static String buildUrl(String apiParams, String region, String apiKey, String secretKey) {
        String encodedApiKey;
        try {
            encodedApiKey = URLEncoder.encode(apiKey, "UTF-8");
            List<String> sortedParams = new ArrayList<String>();
            sortedParams.add("apikey=" + encodedApiKey.toLowerCase());
            StringTokenizer st = new StringTokenizer(apiParams, "&");
            String url = null;
            boolean first = true;
            while (st.hasMoreTokens()) {
                String paramValue = st.nextToken();
                String param = paramValue.substring(0, paramValue.indexOf("="));
                String value = paramValue.substring(paramValue.indexOf("=") + 1, paramValue.length());
                if (first) {
                    url = param + "=" + value;
                    first = false;
                } else {
                    url = url + "&" + param + "=" + value;
                }
                sortedParams.add(param.toLowerCase() + "=" + value.toLowerCase());
            }
            Collections.sort(sortedParams);
            String sortedUrl = null;
            first = true;
            for (String param : sortedParams) {
                if (first) {
                    sortedUrl = param;
                    first = false;
                } else {
                    sortedUrl = sortedUrl + "&" + param;
                }
            }
            String encodedSignature = signRequest(sortedUrl, secretKey);
            String finalUrl = region + "?" + apiParams + "&apiKey=" + apiKey + "&signature=" + encodedSignature;
            return finalUrl;
        } catch (UnsupportedEncodingException e) {
            LOGGER.error(e.getMessage());
            return null;
        }
    }

    /**
     * Mold Signature 생성
     */
    private static String signRequest(String request, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA256");
            mac.init(keySpec);
            mac.update(request.getBytes());
            byte[] encryptedBytes = mac.doFinal();
            return URLEncoder.encode(Base64.encodeBase64String(encryptedBytes), "UTF-8");
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
            return null;
        }
    }
}
