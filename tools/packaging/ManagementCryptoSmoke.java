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

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import org.apache.cloudstack.utils.security.CertUtils;

/** Runs against the packaged management JAR and its runtime lib directory. */
public class ManagementCryptoSmoke {
    public static void main(String[] args) throws Exception {
        String[][] providers = {
            {"org.bouncycastle.jce.provider.BouncyCastleProvider", "bcprov-jdk18on-"},
            {"org.bouncycastle.cert.X509CertificateHolder", "bcpkix-jdk18on-"},
            {"org.bouncycastle.asn1.edec.EdECObjectIdentifiers", "bcutil-jdk18on-"},
            {"org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto", "bctls-jdk18on-"}
        };
        for (String[] provider : providers) {
            Class<?> type = Class.forName(provider[0]);
            String source = type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            if (!source.contains("/" + provider[1])) {
                throw new AssertionError("Unexpected crypto class source: " + source);
            }
            System.out.println(provider[0] + " -> " + source);
        }

        KeyPair caKey = CertUtils.generateRandomKeyPair(2048);
        X509Certificate ca = CertUtils.generateV1Certificate(caKey, "CN=packaging-smoke-ca",
                "CN=packaging-smoke-ca", 1, "SHA256withRSA");
        ca.checkValidity();
        ca.verify(caKey.getPublic());

        KeyPair clientKey = CertUtils.generateRandomKeyPair(2048);
        X509Certificate client = CertUtils.generateV3Certificate(ca, caKey, clientKey.getPublic(),
                "CN=packaging-smoke-client", "SHA256withRSA", 1,
                Arrays.asList("localhost"), Arrays.asList("127.0.0.1"));
        client.checkValidity();
        client.verify(caKey.getPublic());
        System.out.println("MANAGEMENT_CRYPTO_PACKAGING_PASS");
    }
}
