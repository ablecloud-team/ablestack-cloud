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
import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.cloudstack.acl.APIChecker;
import org.apache.cloudstack.acl.ApiKeyPairManagerImpl;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import com.cloud.api.ApiServer;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.TransactionLegacy;

/** Real HMAC request verification against the compiled ApiServer, with isolated identity/DB fixtures. */
public class EuropaApiAuthenticationSmoke {
    private static final String KEY = "fixture-existing-api-key";
    private static final String SECRET = "fixture-existing-secret";

    private static Map<String, Object[]> request() throws Exception {
        Map<String, Object[]> values = new HashMap<>();
        values.put("command", new String[] {"listVirtualMachines"});
        values.put("apikey", new String[] {KEY});
        values.put("response", new String[] {"json"});
        List<String> names = new ArrayList<>(values.keySet());
        Collections.sort(names);
        List<String> components = new ArrayList<>();
        for (String name : names) {
            components.add(name + "=" + URLEncoder.encode((String) values.get(name)[0], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        Mac signer = Mac.getInstance("HmacSHA256");
        signer.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(signer.doFinal(String.join("&", components).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        values.put("signature", new String[] {signature});
        return values;
    }

    private static void require(boolean value, String message) {
        if (!value) { throw new AssertionError(message); }
    }

    public static void main(String[] args) throws Exception {
        ApiServer server = new ApiServer();
        FieldUtils.writeStaticField(ApiServer.class, "s_apiNameCmdClassMap",
                Map.of("listVirtualMachines", List.of(org.apache.cloudstack.api.command.user.vm.ListVMsCmd.class)), true);
        AccountManager accounts = Mockito.mock(AccountManager.class);
        ApiKeyPairManagerImpl permissions = Mockito.mock(ApiKeyPairManagerImpl.class);
        Account account = Mockito.mock(Account.class);
        User user = Mockito.mock(User.class);
        Mockito.when(account.getId()).thenReturn(2L);
        Mockito.when(account.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(user.getId()).thenReturn(2L);
        Mockito.when(user.getAccountId()).thenReturn(2L);
        Mockito.when(user.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(user.getApiKeyAccess()).thenReturn(true);
        Mockito.when(accounts.getAccount(2L)).thenReturn(account);
        ApiKeyPairVO pair = new ApiKeyPairVO(7L, 2L);
        pair.setApiKey(KEY);
        pair.setSecretKey(SECRET);
        Mockito.when(accounts.findUserByApiKey(KEY)).thenReturn(new Ternary<>(user, account, pair));
        FieldUtils.writeField(server, "accountMgr", accounts, true);
        FieldUtils.writeField(server, "keyPairManager", permissions, true);
        server.setApiAccessCheckers(Collections.emptyList());
        InetAddress address = InetAddress.getLoopbackAddress();
        try (MockedStatic<TransactionLegacy> transactions = Mockito.mockStatic(TransactionLegacy.class)) {
            transactions.when(() -> TransactionLegacy.open(TransactionLegacy.CLOUD_DB)).thenReturn(Mockito.mock(TransactionLegacy.class));
            require(server.verifyRequest(request(), null, address), "Existing key signed request must authenticate");
            CallContext.unregister();
            Map<String, Object[]> tampered = request();
            tampered.put("response", new String[] {"xml"});
            require(!server.verifyRequest(tampered, null, address), "Tampered request must be rejected");
            pair.setRemoved(new Date());
            require(!server.verifyRequest(request(), null, address), "Removed key must be rejected");
            pair.setRemoved(null);
            pair.setEndDate(new Date(System.currentTimeMillis() - 1000));
            require(!server.verifyRequest(request(), null, address), "Expired key must be rejected");
            pair.setEndDate(null);
            Mockito.when(user.getState()).thenReturn(Account.State.DISABLED);
            require(!server.verifyRequest(request(), null, address), "Disabled user must be rejected");
            Mockito.when(user.getState()).thenReturn(Account.State.ENABLED);
            APIChecker checker = Mockito.mock(APIChecker.class);
            Mockito.doThrow(new PermissionDeniedException("fixture key permission denied")).when(checker)
                    .checkAccess(Mockito.eq(user), Mockito.eq("listVirtualMachines"), Mockito.eq(pair), Mockito.any(ApiKeyPairPermission[].class));
            server.setApiAccessCheckers(List.of(checker));
            try {
                server.verifyRequest(request(), null, address);
                throw new AssertionError("Key permission denial must be enforced after signature verification");
            } catch (ServerApiException expected) {
                require(expected.getErrorCode() == org.apache.cloudstack.api.ApiErrorCode.UNAUTHORIZED, "Expected unauthorized result");
            }
        } finally {
            CallContext.unregister();
        }
        System.out.println("PASS: existing API key HMAC authentication; tampered request, removed/expired key, disabled user and key permission denial");
    }
}
