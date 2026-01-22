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
package org.apache.cloudstack.saml;


import org.apache.cloudstack.framework.config.ConfigKey;

public class SAML2Config {
    public static final ConfigKey<String> SAMLIdentityProviderPortalUrl =
        new ConfigKey<>("Advanced", String.class, "saml2.idp.portal.url",
            "http://localhost:19000", "SAML2 IDP Portal URL", true);

    public static final ConfigKey<String> SAMLIdentityProviderPassword =
        new ConfigKey<>("Secure", String.class, "saml2.idp.admin.password",
            "admin", "SAML2 IDP Admin password", true);
}
