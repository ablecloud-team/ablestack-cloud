// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

/** Defines the ABLESTACK-to-ABLESTACK VM detail replication boundary. */
public final class DrVmDetailReplicationPolicy {
    public static final String REPLICATED_KEYS_DETAIL = "dr.source.vm.details.keys";
    private static final String[] TRANSIENT_PREFIXES = {"clone.", "dr.", "ftctl.", "message.", "kvm.vnc.", "runtime.", "host.", "last."};
    private static final String[] TARGET_COMPUTE_PARAMETERS = {"cpunumber", "cpuspeed", "memory"};

    private DrVmDetailReplicationPolicy() {
    }

    public static Map<String, String> copyableSourceDetails(String direction, Map<String, String> sourceDetails) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!StringUtils.equalsIgnoreCase(direction, DrConstants.DIRECTION_KVM_TO_KVM) || sourceDetails == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(sourceDetails).entrySet()) {
            if (isCopyable(entry.getKey()) && entry.getValue() != null) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    static boolean isCopyable(String key) {
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(key), Locale.ROOT);
        if (StringUtils.isBlank(normalized)
                || StringUtils.equalsAny(normalized, "volumeid", "deployvm", "boot.mode")
                || StringUtils.equalsAny(normalized, TARGET_COMPUTE_PARAMETERS)) {
            return false;
        }
        for (String prefix : TRANSIENT_PREFIXES) {
            if (StringUtils.startsWith(normalized, prefix)) {
                return false;
            }
        }
        return true;
    }
}
