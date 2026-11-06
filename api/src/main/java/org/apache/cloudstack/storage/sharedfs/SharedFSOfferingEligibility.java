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
package org.apache.cloudstack.storage.sharedfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/** Creation-only constraints shared by the catalog and the final SharedFS request. */
public final class SharedFSOfferingEligibility {
    public static final String ERROR_PREFIX = "SHAREDFS_OFFERING_INELIGIBLE:";

    public static final class Constraint {
        @SerializedName("custom") private final boolean custom;
        @SerializedName("min") private final int min;
        @SerializedName("max") private final int max;
        @SerializedName("value") private final Integer value;

        private Constraint(Integer fixed, int min, int max) {
            custom = fixed == null;
            value = fixed;
            this.min = min;
            this.max = max;
        }

        public boolean isCustom() { return custom; }
        public int getMin() { return min; }
        public int getMax() { return max; }
        public Integer getValue() { return value; }
    }

    public static final class Reason {
        @SerializedName("code") private final String code;
        @SerializedName("field") private final String field;
        @SerializedName("params") private final Map<String, Object> params;

        private Reason(String code, String field, Map<String, Object> params) {
            this.code = code;
            this.field = field;
            this.params = params;
        }

        public String getCode() { return code; }
        public String getField() { return field; }
        public Map<String, Object> getParams() { return Collections.unmodifiableMap(params); }
    }

    @SerializedName("selectable") private boolean selectable = true;
    @SerializedName("inputrequired") private boolean inputRequired;
    @SerializedName("mode") private String mode = "fixed";
    @SerializedName("constraints") private final Map<String, Constraint> constraints = new LinkedHashMap<>();
    @SerializedName("reasons") private final List<Reason> reasons = new ArrayList<>();
    @SerializedName("root") private final Map<String, String> root = new LinkedHashMap<>();
    @SerializedName("requirements") private final Map<String, Object> requirements = new LinkedHashMap<>();

    public SharedFSOfferingEligibility(ServiceOffering offering, Map<String, String> details,
            int minCpu, int minRam, int maxCpu, int maxRam) {
        requirements.put("mincpu", Math.max(1, minCpu));
        requirements.put("minmemory", Math.max(32, minRam));
        requirements.put("ha", true);
        requirements.put("dynamicscalingrequired", false);
        if (!offering.isOfferHA()) {
            reject("HA_REQUIRED", "offerha");
        }
        addConstraint("cpunumber", offering.getCpu(), Math.max(1, minCpu), upper(maxCpu), details, "mincpunumber", "maxcpunumber");
        addConstraint("memory", offering.getRamSize(), Math.max(32, minRam), upper(maxRam), details, "minmemory", "maxmemory");
        // A fixed zero MHz is the existing unlimited CPU-speed contract. Custom speed must be positive.
        addConstraint("cpuspeed", offering.getSpeed(), offering.getSpeed() == null ? 1 : 0,
                Integer.MAX_VALUE, details, null, null);
        inputRequired = constraints.values().stream().anyMatch(Constraint::isCustom);
        if (inputRequired) {
            mode = details.keySet().stream().anyMatch(k -> k.equals("mincpunumber") || k.equals("maxcpunumber")
                    || k.equals("minmemory") || k.equals("maxmemory")) ? "ranged" : "free";
        }
    }

    private static int upper(int value) {
        return value == 0 ? Integer.MAX_VALUE : value;
    }

    private void addConstraint(String field, Integer fixed, int minimum, int maximum, Map<String, String> details,
            String lowerKey, String upperKey) {
        int low = minimum;
        int high = maximum;
        if (fixed == null) {
            try {
                if (lowerKey != null && details.containsKey(lowerKey)) {
                    low = Math.max(low, positiveBound(details.get(lowerKey)));
                }
                if (upperKey != null && details.containsKey(upperKey)) {
                    high = Math.min(high, positiveBound(details.get(upperKey)));
                }
            } catch (IllegalArgumentException e) {
                reject("RANGE_INVALID", field);
                high = -1;
            }
        }
        constraints.put(field, new Constraint(fixed, low, high));
        if (low > high) {
            reject("RANGE_EMPTY", field, Map.of("min", low, "max", high));
        } else if (fixed != null && (fixed < low || fixed > high)) {
            reject("OUT_OF_RANGE", field, Map.of("min", low, "max", high, "value", fixed));
        }
    }

    private static int positiveBound(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid bound");
        }
        int result = Integer.parseInt(value);
        if (result <= 0) {
            throw new IllegalArgumentException("Invalid bound");
        }
        return result;
    }

    public void reject(String code, String field) {
        reject(code, field, Collections.emptyMap());
    }

    public void reject(String code, String field, Map<String, Object> params) {
        selectable = false;
        reasons.add(new Reason(code, field, new LinkedHashMap<>(params)));
    }

    public void setRoot(String id, String policy, String source) {
        if (id != null) {
            root.put("diskofferingid", id);
        }
        if (policy != null) {
            root.put("policy", policy);
        }
        root.put("source", source);
    }

    public void setArchitecture(String architecture) {
        if (architecture != null) {
            requirements.put("architecture", architecture);
        }
    }

    public Map<String, String> validateCustomValues(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        Map<String, String> keys = Map.of("cpunumber", "cpuNumber", "cpuspeed", "cpuSpeed", "memory", "memory");
        for (String key : values.keySet()) {
            if (!keys.containsValue(key)) {
                reject("PARAMETER_UNSUPPORTED", key);
            }
        }
        for (Map.Entry<String, Constraint> entry : constraints.entrySet()) {
            String field = entry.getKey();
            String key = keys.get(field);
            Constraint constraint = entry.getValue();
            if (!constraint.custom) {
                if (values.containsKey(key)) {
                    reject("FIXED_OVERRIDE", field);
                }
                continue;
            }
            String raw = values.get(key);
            if (raw == null || raw.isEmpty()) {
                reject("REQUIRED", field);
                continue;
            }
            try {
                int value = positiveBound(raw);
                if (value < constraint.min || value > constraint.max) {
                    throw new IllegalArgumentException("Outside range");
                }
                normalized.put(key, Integer.toString(value));
            } catch (IllegalArgumentException e) {
                reject("OUT_OF_RANGE", field, Map.of("min", constraint.min, "max", constraint.max));
            }
        }
        requireSelectable();
        return normalized;
    }

    public void requireSelectable() {
        if (!selectable) {
            throw new InvalidParameterValueException(ERROR_PREFIX + new Gson().toJson(reasons));
        }
    }

    public boolean isSelectable() { return selectable; }
    public boolean isInputRequired() { return inputRequired; }
    public String getMode() { return mode; }
    public List<Reason> getReasons() { return Collections.unmodifiableList(reasons); }
    public Map<String, Constraint> getConstraints() { return Collections.unmodifiableMap(constraints); }

    public static String normalizeArchitecture(String value) {
        if (value == null) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "amd64": case "x86_64": case "x86-64": return "x86_64";
            case "arm64": case "aarch64": return "aarch64";
            case "i686": return "i686";
            case "s390x": return "s390x";
            default: return null;
        }
    }
}
