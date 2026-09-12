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

package com.cloud.template;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.cloud.storage.TemplateProfile;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.UriUtils;

public class TemplateUrlValidationOrderTest {
    @Test
    public void invalidTemplateAndIsoUrlsNeverReachRemoteSize() throws Exception {
        TemplateProfile profile = mock(TemplateProfile.class);
        when(profile.getUrl()).thenReturn("ftp://fixture.invalid/disallowed.qcow2");
        HypervisorTemplateAdapter adapter = mock(HypervisorTemplateAdapter.class, call -> {
            if (call.getMethod().getName().equals("prepare") && call.getArguments().length > 1) {
                return profile;
            }
            return call.callRealMethod();
        });
        Account account = mock(Account.class);
        User user = mock(User.class);
        AccountManager accounts = mock(AccountManager.class);
        FieldUtils.writeField(adapter, "_accountMgr", accounts, true);
        FieldUtils.writeField(adapter, "_imgStoreDao", mock(ImageStoreDao.class), true);
        java.lang.reflect.Field managerField = TemplateAdapterBase.class.getDeclaredField("templateMgr");
        managerField.setAccessible(true);
        managerField.set(adapter, mock(TemplateManager.class));
        CallContext.register(user, account);
        try (MockedStatic<UriUtils> uri = mockStatic(UriUtils.class)) {
            uri.when(() -> UriUtils.validateUrl(anyString(), anyString(), anyBoolean(), anyBoolean())).thenCallRealMethod();
            RegisterTemplateCmd template = mock(RegisterTemplateCmd.class);
            when(template.getHypervisor()).thenReturn("KVM");
            when(template.getFormat()).thenReturn("QCOW2");
            assertThrows(IllegalArgumentException.class, () -> adapter.prepare(template));
            RegisterIsoCmd iso = mock(RegisterIsoCmd.class);
            assertThrows(IllegalArgumentException.class, () -> adapter.prepare(iso));
            uri.verify(() -> UriUtils.getRemoteSize(any(), anyBoolean()), never());
        } finally {
            CallContext.unregister();
        }
    }
}
