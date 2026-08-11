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

import org.apache.commons.lang3.StringUtils;

public final class BackupProviderNameUtils {
    public static final String NAS = "nas";
    public static final String COMMVAULT = "commvault";
    public static final String NETBACKUP = "netbackup";
    public static final String ABLESTACK_NAS = "ablestack-nas";
    public static final String ABLESTACK_COMMVAULT = "ablestack-commvault";
    public static final String ABLESTACK_NETBACKUP = "ablestack-netbackup";
    public static final String ABLESTACK_VEEAM = "ablestack-veeam";

    private BackupProviderNameUtils() {
    }

    public static String canonicalize(final String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return providerName;
        }
        if (NAS.equalsIgnoreCase(providerName) || ABLESTACK_NAS.equalsIgnoreCase(providerName)) {
            return ABLESTACK_NAS;
        }
        if (COMMVAULT.equalsIgnoreCase(providerName) || ABLESTACK_COMMVAULT.equalsIgnoreCase(providerName)) {
            return ABLESTACK_COMMVAULT;
        }
        if (NETBACKUP.equalsIgnoreCase(providerName) || ABLESTACK_NETBACKUP.equalsIgnoreCase(providerName)) {
            return ABLESTACK_NETBACKUP;
        }
        if ("veeam".equalsIgnoreCase(providerName) || ABLESTACK_VEEAM.equalsIgnoreCase(providerName)) {
            return ABLESTACK_VEEAM;
        }
        return providerName;
    }

    public static String toDisplayName(final String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return providerName;
        }
        if (ABLESTACK_NAS.equalsIgnoreCase(providerName) || NAS.equalsIgnoreCase(providerName)) {
            return NAS;
        }
        if (ABLESTACK_COMMVAULT.equalsIgnoreCase(providerName) || COMMVAULT.equalsIgnoreCase(providerName)) {
            return COMMVAULT;
        }
        if (ABLESTACK_NETBACKUP.equalsIgnoreCase(providerName) || NETBACKUP.equalsIgnoreCase(providerName)) {
            return NETBACKUP;
        }
        if (ABLESTACK_VEEAM.equalsIgnoreCase(providerName) || "veeam".equalsIgnoreCase(providerName)) {
            return "veeam";
        }
        return providerName;
    }

    public static boolean isNasFamily(final String providerName) {
        return ABLESTACK_NAS.equalsIgnoreCase(canonicalize(providerName));
    }

    public static boolean isCommvaultFamily(final String providerName) {
        return ABLESTACK_COMMVAULT.equalsIgnoreCase(canonicalize(providerName));
    }

    public static boolean isNetBackupFamily(final String providerName) {
        return ABLESTACK_NETBACKUP.equalsIgnoreCase(canonicalize(providerName));
    }

    public static boolean isVeeamFamily(final String providerName) {
        return ABLESTACK_VEEAM.equalsIgnoreCase(canonicalize(providerName));
    }
}
