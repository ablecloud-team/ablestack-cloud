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

package org.apache.cloudstack.kms.provider;

import com.cloud.utils.crypt.DBEncryptionUtil;
import org.apache.cloudstack.framework.kms.KMSException;
import org.apache.cloudstack.framework.kms.KeyPurpose;
import org.apache.cloudstack.framework.kms.WrappedKey;
import org.apache.cloudstack.kms.provider.database.KMSDatabaseKekObjectVO;
import org.apache.cloudstack.kms.provider.database.dao.KMSDatabaseKekObjectDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DatabaseKMSProviderTest {
    private DatabaseKMSProvider provider;
    private Map<String, KMSDatabaseKekObjectVO> stored;
    private MockedStatic<DBEncryptionUtil> databaseEncryption;

    @Before
    public void setUp() {
        provider = new DatabaseKMSProvider();
        stored = new HashMap<>();
        KMSDatabaseKekObjectDao dao = mock(KMSDatabaseKekObjectDao.class);
        ReflectionTestUtils.setField(provider, "kekObjectDao", dao);
        when(dao.existsByLabel(anyString())).thenAnswer(i -> stored.containsKey(i.getArgument(0)));
        when(dao.persist(any(KMSDatabaseKekObjectVO.class))).thenAnswer(i -> {
            KMSDatabaseKekObjectVO value = i.getArgument(0);
            stored.put(value.getLabel(), value);
            return value;
        });
        when(dao.findByLabel(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        // Isolate master-password configuration; wrap/unwrap still execute real Tink AES-GCM.
        databaseEncryption = Mockito.mockStatic(DBEncryptionUtil.class);
        databaseEncryption.when(() -> DBEncryptionUtil.encrypt(anyString())).thenAnswer(i -> "fixture:" + i.getArgument(0));
        databaseEncryption.when(() -> DBEncryptionUtil.decrypt(anyString()))
                .thenAnswer(i -> ((String) i.getArgument(0)).substring("fixture:".length()));
        provider.createKek(KeyPurpose.VOLUME_ENCRYPTION, "v1", 256);
        provider.createKek(KeyPurpose.VOLUME_ENCRYPTION, "v2", 256);
    }

    @After
    public void tearDown() {
        databaseEncryption.close();
    }

    @Test
    public void legacy48ByteSecretSurvivesWrapAndRotation() {
        byte[] legacy = new org.apache.cloudstack.secret.PassphraseVO(true).getPassphrase();
        byte[] raw = Base64.getDecoder().decode(legacy);
        WrappedKey first = provider.wrapKey(raw, KeyPurpose.VOLUME_ENCRYPTION, "v1");
        assertArrayEquals(legacy, Base64.getEncoder().encode(provider.unwrapKey(first)));
        WrappedKey rotated = provider.rewrapKey(first, "v2");
        stored.remove("v1");
        assertArrayEquals(legacy, Base64.getEncoder().encode(provider.unwrapKey(rotated)));
        assertThrows(KMSException.class, () -> provider.unwrapKey(first));
    }

    @Test
    public void nonceIsRandomAndTamperingFailsAuthentication() {
        byte[] raw = "fixture key material".getBytes(StandardCharsets.UTF_8);
        WrappedKey first = provider.wrapKey(raw, KeyPurpose.VOLUME_ENCRYPTION, "v1");
        WrappedKey second = provider.wrapKey(raw, KeyPurpose.VOLUME_ENCRYPTION, "v1");
        assertFalse(java.util.Arrays.equals(first.getWrappedKeyMaterial(), second.getWrappedKeyMaterial()));
        byte[] corrupted = first.getWrappedKeyMaterial();
        corrupted[13] ^= 1;
        WrappedKey tampered = new WrappedKey(first.getKekId(), first.getPurpose(), first.getAlgorithm(),
                corrupted, first.getProviderName(), first.getCreated(), first.getZoneId());
        assertThrows(KMSException.class, () -> provider.unwrapKey(tampered));
        assertArrayEquals(raw, provider.unwrapKey(second));
    }

    @Test
    public void newDeksHaveRequestedSizeAndSurviveRewrap() {
        for (int bits : new int[]{128, 192, 256}) {
            WrappedKey first = provider.generateAndWrapDek(KeyPurpose.VOLUME_ENCRYPTION, "v1", bits);
            byte[] raw = provider.unwrapKey(first);
            assertEquals(bits / 8, raw.length);
            assertArrayEquals(raw, provider.unwrapKey(provider.rewrapKey(first, "v2")));
        }
    }

    @Test
    public void missingKekCannotGenerateReplacement() {
        assertThrows(KMSException.class,
                () -> provider.generateAndWrapDek(KeyPurpose.VOLUME_ENCRYPTION, "missing", 256));
        assertEquals(2, stored.size());
    }
}
