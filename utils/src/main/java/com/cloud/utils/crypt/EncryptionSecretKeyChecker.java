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

package com.cloud.utils.crypt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import javax.annotation.PostConstruct;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.utils.db.DbProperties;
import com.cloud.utils.exception.CloudRuntimeException;

public class EncryptionSecretKeyChecker {

    protected Logger logger = LogManager.getLogger(getClass());

    // Two possible locations with the new packaging naming
    private static final String s_altKeyFile = "key";
    private static final String s_keyFile = "key";
    private static final String s_envKey = "CLOUD_SECRET_KEY";
    private static CloudStackEncryptor s_encryptor = null;
    private static boolean s_useEncryption = false;

    @PostConstruct
    public void init() {
        /* This will call DbProperties, which will call this to initialize the encryption. Yep,
         * round about and annoying */
        DbProperties.getDbProperties();
    }

    public void check(Properties properties, String property) throws IOException {
        String encryptionType = properties.getProperty(property);

        logger.debug("Encryption Type: " + encryptionType);

        if (encryptionType == null || encryptionType.equals("none")) {
            return;
        }

        if (s_useEncryption) {
            logger.warn("Encryption already enabled, is check() called twice?");
            return;
        }

        String secretKey = null;
        InputStream isEncKey = null;
        if (encryptionType.equals("file")) {
            InputStream is = this.getClass().getClassLoader().getResourceAsStream(s_keyFile);
            if (is == null) {
              is = this.getClass().getClassLoader().getResourceAsStream(s_altKeyFile);
            }
            if(is == null) {  //This is means we are not able to load key file from the classpath.
              throw new CloudRuntimeException(s_keyFile + " File containing secret key not found in the classpath: ");
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(is));) {
                secretKey = in.readLine();
                //Check for null or empty secret key
            } catch (IOException e) {
                throw new CloudRuntimeException("Error while reading secret key from: " + s_keyFile, e);
            }

            if (secretKey == null || secretKey.isEmpty()) {
                throw new CloudRuntimeException("Secret key is null or empty in file " + s_keyFile);
            }
            isEncKey = this.getClass().getClassLoader().getResourceAsStream("key.enc");
            if (isEncKey != null) {
                Path filePath = Paths.get("/etc/cloudstack/management/key");
                // 파일 삭제
                Files.deleteIfExists(filePath);
            }
        } else if (encryptionType.equals("env")) {
            secretKey = System.getenv(s_envKey);
            if (secretKey == null || secretKey.isEmpty()) {
                throw new CloudRuntimeException("Environment variable " + s_envKey + " is not set or empty");
            }
        } else if (encryptionType.equals("web")) {
            int port = 8097;
            try (ServerSocket serverSocket = new ServerSocket(port);) {
                logger.info("Waiting for admin to send secret key on port " + port);
                try (
                        Socket clientSocket = serverSocket.accept();
                        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    ) {
                    String inputLine;
                    if ((inputLine = in.readLine()) != null) {
                        secretKey = inputLine;
                    }
                } catch (IOException e) {
                    throw new CloudRuntimeException("Accept failed on " + port);
                }
            } catch (IOException ioex) {
                throw new CloudRuntimeException("Error initializing secret key receiver", ioex);
            }
        } else {
            throw new CloudRuntimeException("Invalid encryption type: " + encryptionType);
        }

        if (secretKey == null) {
            throw new CloudRuntimeException("null secret key is found when setting up server encryption");
        }

        initEncryptor(secretKey);

        if (isEncKey != null) {
            Random random;
            //secretKey 지우기 (0, 1 로 덮어쓰기 5회)
            for (int i = 0; i < 5; i++) {
                random = new Random(System.currentTimeMillis());
                secretKey = Integer.toString(random.nextInt(899)+100, 2); //100~999사이의 정수를 2진수(0과 1)로 변환한 값을 변수에 5회 덮어쓰기
            }
            s_logger.info("Overwritten final secretKey value : " + secretKey);
        }
    }

    public static CloudStackEncryptor getEncryptor() {
        return s_encryptor;
    }

    public static boolean useEncryption() {
        return s_useEncryption;
    }

    public static void initEncryptor(String secretKey) {
        s_encryptor = new CloudStackEncryptor(secretKey, null, EncryptionSecretKeyChecker.class);
        s_useEncryption = true;
    }

    public static void resetEncryptor() {
        s_encryptor = null;
        s_useEncryption = false;
    }

    protected static String decryptPropertyIfNeeded(String value) {
        if (s_encryptor == null) {
            throw new CloudRuntimeException("encryptor not initialized");
        }

        if (value.startsWith("ENC(") && value.endsWith(")")) {
            String inner = value.substring("ENC(".length(), value.length() - ")".length());
            return s_encryptor.decrypt(inner);
        }
        return value;
    }

    public static void decryptAnyProperties(Properties properties) {
        if (s_encryptor == null) {
            throw new CloudRuntimeException("encryptor not initialized");
        }

        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String value = (String) entry.getValue();
            properties.replace(entry.getKey(), decryptPropertyIfNeeded(value));
        }
    }
}
