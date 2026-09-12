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

package org.apache.cloudstack.ca.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.utils.security.CertUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RootCAConfigurationTest {
    private final Map<Field, Object> original = new HashMap<>();
    private RootCAProvider provider;
    private ConfigurationDao dao;
    private KeyPair pair;
    private String publicPem;
    private String privatePem;
    private String certificatePem;

    @Before
    public void setUp() throws Exception {
        provider = new RootCAProvider();
        for (String name : new String[] {"caKeyPair", "caCertificate", "caCertificates", "managementKeyStore"}) {
            Field field = RootCAProvider.class.getDeclaredField(name);
            field.setAccessible(true);
            original.put(field, field.get(null));
            field.set(null, null);
        }
        dao = mock(ConfigurationDao.class);
        setField("configDao", dao);
        pair = CertUtils.generateRandomKeyPair(2048);
        publicPem = CertUtils.publicKeyToPem(pair.getPublic());
        privatePem = CertUtils.privateKeyToPem(pair.getPrivate());
        X509Certificate certificate = CertUtils.generateV3Certificate(null, pair, pair.getPublic(), "CN=Europa fixture CA", "SHA256withRSA", 365, null, null);
        certificatePem = CertUtils.x509CertificateToPem(certificate);
    }

    @After
    public void restoreConfiguration() throws Exception {
        for (Map.Entry<Field, Object> entry : original.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = RootCAProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(provider, value);
    }

    private void config(String name, String value) throws Exception {
        Field field = RootCAProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        original.putIfAbsent(field, field.get(null));
        ConfigKey<String> key = mock(ConfigKey.class);
        when(key.value()).thenReturn(value);
        field.set(null, key);
    }

    private void configure(String publicKey, String privateKey, String certificate) throws Exception {
        config("rootCAPublicKey", publicKey);
        config("rootCAPrivateKey", privateKey);
        config("rootCACertificate", certificate);
    }

    private void refusesWithoutOverwriting() {
        assertFalse(provider.setupCA());
        verifyNoInteractions(dao);
    }

    @Test
    public void partialKeysPreserved() throws Exception {
        configure(publicPem, null, null);
        refusesWithoutOverwriting();
    }

    @Test
    public void malformedKeyPreserved() throws Exception {
        configure(publicPem, "invalid PEM", certificatePem);
        refusesWithoutOverwriting();
    }

    @Test
    public void missingCertificatePreserved() throws Exception {
        configure(publicPem, privatePem, null);
        refusesWithoutOverwriting();
    }

    @Test
    public void mismatchedPrivateKeyPreserved() throws Exception {
        configure(publicPem, CertUtils.privateKeyToPem(CertUtils.generateRandomKeyPair(2048).getPrivate()), certificatePem);
        refusesWithoutOverwriting();
    }

    @Test
    public void mismatchedCertificatePreserved() throws Exception {
        KeyPair other = CertUtils.generateRandomKeyPair(2048);
        X509Certificate certificate = CertUtils.generateV3Certificate(null, other, other.getPublic(), "CN=Other fixture CA", "SHA256withRSA", 365, null, null);
        configure(publicPem, privatePem, CertUtils.x509CertificateToPem(certificate));
        refusesWithoutOverwriting();
    }

    @Test
    public void validCertificateConfigurationLoadsWithoutWrites() throws Exception {
        configure(publicPem, privatePem, certificatePem);
        setField("managementKeyStore", KeyStore.getInstance("JKS"));
        assertTrue(provider.setupCA());
        verifyNoInteractions(dao);
        assertTrue(provider.getCaCertificate().get(0).getPublicKey().equals(pair.getPublic()));
    }
}
