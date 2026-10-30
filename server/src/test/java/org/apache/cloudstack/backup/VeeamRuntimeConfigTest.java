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
package org.apache.cloudstack.backup;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.backup.GetVeeamRuntimeConfigCmd;
import org.apache.cloudstack.api.response.VeeamRuntimeConfigResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.dc.DataCenterVO;
import com.cloud.api.dispatch.ParamProcessWorker;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VeeamRuntimeConfigTest {
    private BackupManagerImpl manager;
    private AccountService accounts;
    private HostDao hosts;
    private HostVO host;
    private BackupProvider provider;
    private KeyPair pair;
    private String publicKey;
    private final Map<String, String> connection = Map.of("url", "https://vbr.test:9398/api/", "username", "vbr-account", "password", "synthetic-sensitive-password");

    @Before public void setup() throws Exception {
        manager = spy(new BackupManagerImpl());
        accounts = mock(AccountService.class);
        hosts = mock(HostDao.class);
        provider = mock(BackupProvider.class);
        ReflectionTestUtils.setField(manager, "accountService", accounts);
        ReflectionTestUtils.setField(manager, "hostDao", hosts);
        ReflectionTestUtils.setField(manager, "backupProvidersMap", Map.of(BackupProviderNameUtils.ABLESTACK_VEEAM, provider));
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(2L);
        CallContext.register(mock(User.class), account);
        when(accounts.isRootAdmin(2L)).thenReturn(true);
        host = mock(HostVO.class);
        when(host.getName()).thenReturn("host-test");
        when(host.getDataCenterId()).thenReturn(1L);
        when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(hosts.findByName("host-test")).thenReturn(host);
        DataCenterDao zones = mock(DataCenterDao.class);
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zones.findById(1L)).thenReturn(zone);
        ReflectionTestUtils.setField(manager, "dataCenterDao", zones);
        doNothing().when(manager).validateBackupForZone(1L);
        when(provider.getHostRuntimeConfiguration(1L)).thenReturn(connection);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    @After public void cleanup() { CallContext.unregister(); }

    private String decrypt(VeeamRuntimeConfigResponse response, KeyPair recipient) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsa.init(Cipher.DECRYPT_MODE, recipient.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] key = rsa.doFinal(Base64.getDecoder().decode(response.wrappedkey));
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(response.nonce)));
        aes.updateAAD(VeeamRuntimeConfigEncryption.CONTEXT.getBytes(StandardCharsets.US_ASCII));
        return new String(aes.doFinal(Base64.getDecoder().decode(response.ciphertext)), StandardCharsets.UTF_8);
    }

    @Test public void onlyTheRequestRecipientCanReadTheCurrentZoneConnection() throws Exception {
        VeeamRuntimeConfigResponse response = manager.getVeeamRuntimeConfig("host-test", publicKey);
        String serialized = new Gson().toJson(response);
        for (String value : connection.values()) { assertFalse(serialized.contains(value)); }
        Map<?, ?> settings = new Gson().fromJson(decrypt(response, pair), Map.class);
        assertEquals(connection.get("password"), settings.get("password"));
        assertEquals("host-test", settings.get("hostname"));
        assertEquals("zone-uuid", settings.get("zoneid"));
        verify(provider).getHostRuntimeConfiguration(1L);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        assertThrows(Exception.class, () -> decrypt(response, other));
    }

    @Test public void apiParameterValidationAcceptsSupportedRsaKeysBeforeExecutingTheCommand() throws Exception {
        ParamProcessWorker dispatcher = new ParamProcessWorker();
        ReflectionTestUtils.setField(dispatcher, "_accountMgr", mock(AccountManager.class));
        for (int bits : new int[]{2048, 4096}) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            KeyPair recipient = generator.generateKeyPair();
            String encoded = Base64.getEncoder().encodeToString(recipient.getPublic().getEncoded());
            assertTrue(encoded.length() > 255);
            GetVeeamRuntimeConfigCmd command = new GetVeeamRuntimeConfigCmd();
            ReflectionTestUtils.setField(command, "backupManager", manager);
            dispatcher.processParameters(command, Map.of("hostname", "host-test", "publickey", encoded));
            command.execute();
            VeeamRuntimeConfigResponse response = (VeeamRuntimeConfigResponse) command.getResponseObject();
            assertEquals(connection.get("password"), new Gson().fromJson(decrypt(response, recipient), Map.class).get("password"));
        }
    }

    @Test public void apiRejectsAnOversizedPublicKeyBeforeReadingProviderCredentials() {
        ParamProcessWorker dispatcher = new ParamProcessWorker();
        GetVeeamRuntimeConfigCmd command = new GetVeeamRuntimeConfigCmd();
        assertThrows(ServerApiException.class, () -> dispatcher.processParameters(command,
                Map.of("hostname", "host-test", "publickey", "A".repeat(8193))));
        verify(provider, never()).getHostRuntimeConfiguration(anyLong());
    }

    @Test public void domainAdminOrUserCannotReadTheConnection() {
        when(accounts.isRootAdmin(2L)).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> manager.getVeeamRuntimeConfig("host-test", publicKey));
        verify(hosts, never()).findByName("host-test");
        verify(provider, never()).getHostRuntimeConfiguration(anyLong());
    }

    @Test public void unknownRemovedOrNonKvmHostCannotReadTheConnection() {
        assertThrows(InvalidParameterValueException.class, () -> manager.getVeeamRuntimeConfig("unknown", publicKey));
        when(host.getRemoved()).thenReturn(new java.util.Date());
        assertThrows(InvalidParameterValueException.class, () -> manager.getVeeamRuntimeConfig("host-test", publicKey));
        when(host.getRemoved()).thenReturn(null);
        when(host.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.VMware);
        assertThrows(InvalidParameterValueException.class, () -> manager.getVeeamRuntimeConfig("host-test", publicKey));
        verify(provider, never()).getHostRuntimeConfiguration(anyLong());
    }

    @Test public void changingMoldSettingsDoesNotReuseAHostCredentialCache() throws Exception {
        VeeamRuntimeConfigResponse first = manager.getVeeamRuntimeConfig("host-test", publicKey);
        when(provider.getHostRuntimeConfiguration(1L)).thenReturn(Map.of("url", "https://vbr.test/api", "username", "changed", "password", "rotated"));
        VeeamRuntimeConfigResponse second = manager.getVeeamRuntimeConfig("host-test", publicKey);
        assertTrue(decrypt(first, pair).contains(connection.get("password")));
        assertEquals("rotated", new Gson().fromJson(decrypt(second, pair), Map.class).get("password"));
        assertFalse(first.nonce.equals(second.nonce));
    }

    @Test public void tamperingAndInvalidPublicKeysFailWithoutCredentialDisclosure() throws Exception {
        VeeamRuntimeConfigResponse response = manager.getVeeamRuntimeConfig("host-test", publicKey);
        byte[] ciphertext = Base64.getDecoder().decode(response.ciphertext);
        ciphertext[0] ^= 1;
        response.ciphertext = Base64.getEncoder().encodeToString(ciphertext);
        assertThrows(Exception.class, () -> decrypt(response, pair));
        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> manager.getVeeamRuntimeConfig("host-test", "invalid"));
        assertFalse(error.getMessage().contains(connection.get("password")));
    }

    @Test public void apiIsAdminOnlyAndItsResponseIsMarkedSensitive() {
        APICommand command = GetVeeamRuntimeConfigCmd.class.getAnnotation(APICommand.class);
        assertEquals(1, command.authorized().length);
        assertEquals(RoleType.Admin, command.authorized()[0]);
        assertTrue(command.responseHasSensitiveInfo());
    }
}
