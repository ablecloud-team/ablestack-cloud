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

package com.cloud.kubernetes.cluster;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.cloudstack.api.response.CniConfigurationPreviewResponse;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.NetworkModel;
import com.cloud.network.Network;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.dao.ASNumberDao;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** A bounded literal renderer, shared by preview and actual CNI provisioning. */
public final class CniConfigurationRenderer {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern TOKEN = Pattern.compile("(\\\\)?\\{\\{[ \\t]*([^{}\\r\\n]*?)[ \\t]*\\}\\}");
    private static final String META = "ds.meta_data.";
    private static final Gson GSON = new Gson();
    private CniConfigurationRenderer() { }

    public static Long resolveAsNumber(Network network, ASNumberDao dao, Long proposed) {
        if (network == null) { return proposed; }
        ASNumberVO assigned = network.getVpcId() == null ? dao.findByZoneAndNetworkId(network.getDataCenterId(), network.getId())
                : dao.findByZoneAndVpcId(network.getDataCenterId(), network.getVpcId());
        if (assigned == null) { return proposed; }
        return assigned.getAsNumber();
    }

    public static List<String> parameters(String input) {
        List<String> keys = new ArrayList<>();
        if (input == null || input.isEmpty()) { return keys; }
        if (input.length() > 4096) { throw new InvalidParameterValueException("CNI variable names exceed 4096 characters"); }
        for (String raw : input.split(",", -1)) {
            String key = raw.replaceAll("(?U)^[\\s\\uFEFF]+|[\\s\\uFEFF]+$", "");
            if (!valid(key) || keys.contains(key)) {
                throw new InvalidParameterValueException("Invalid, reserved or duplicate CNI variable name");
            }
            keys.add(key);
        }
        return keys;
    }

    private static boolean valid(String key) {
        return key != null && KEY.matcher(key).matches() && !NetworkModel.metadataFileNames.contains(key);
    }

    public static Map<String, String> values(Map<?, ?> input) {
        Map<String, String> values = new LinkedHashMap<>();
        if (input != null) {
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String) || !valid((String) entry.getKey())) {
                    throw new InvalidParameterValueException("Invalid CNI variable value or key");
                }
                values.put((String) entry.getKey(), (String) entry.getValue());
            }
        }
        if (com.cloud.utils.StringUtils.unicodeEscape(GSON.toJson(values)).length() > 4096) {
            throw new InvalidParameterValueException("CNI variable values exceed 4096 serialized characters");
        }
        return values;
    }

    public static String serialize(Map<?, ?> input) { return com.cloud.utils.StringUtils.unicodeEscape(GSON.toJson(values(input))); }

    public static Map<String, String> deserialize(String input) {
        if (input == null || input.isBlank() || "{}".equals(input)) { return Collections.emptyMap(); }
        Map<String, String> values = new LinkedHashMap<>();
        if (input.stripLeading().startsWith("{\"")) {
            try {
                JsonElement root = JsonParser.parseString(input);
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException();
                    }
                    values.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (RuntimeException e) { throw new InvalidParameterValueException("Invalid stored CNI variable JSON"); }
        } else {
            if (!input.startsWith("{") || !input.endsWith("}")) { throw new InvalidParameterValueException("Invalid stored CNI variables"); }
            for (String pair : input.substring(1, input.length() - 1).split(", ")) {
                int separator = pair.indexOf('=');
                if (separator < 1) { throw new InvalidParameterValueException("Invalid stored CNI variables"); }
                values.put(pair.substring(0, separator).strip(), pair.substring(separator + 1));
            }
        }
        return values(values);
    }

    private static void add(List<String> list, String key) { if (!list.contains(key)) { list.add(key); } }

    public static CniConfigurationPreviewResponse preview(String encoded, String params, Map<?, ?> input, Long asNumber) {
        CniConfigurationPreviewResponse result = new CniConfigurationPreviewResponse();
        result.params = parameters(params);
        Map<String, String> values = values(input);
        result.asnumber = asNumber;
        for (String key : values.keySet()) {
            if (!result.params.contains(key) || "AS_NUMBER".equals(key)) { result.extra.add(key); }
            else { result.values.put(key, values.get(key)); }
        }
        for (String key : result.params) {
            if (!"AS_NUMBER".equals(key) && !values.containsKey(key)) { result.missing.add(key); }
        }
        if (encoded == null || encoded.length() > 1048576 || asNumber != null && (asNumber < 0 || asNumber > 4294967295L)) {
            throw new InvalidParameterValueException("CNI payload or AS number exceeds the supported bounds");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (RuntimeException e) { throw new InvalidParameterValueException("Invalid CNI Base64 payload"); }
        result.userdata = Base64.getEncoder().encodeToString(bytes);
        String source;
        try { source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { result.format = "binary"; return result; }
        String header = source.startsWith("\ufeff") ? source.substring(1) : source;
        boolean jinja = header.replace("\r\n", "\n").stripLeading().startsWith("## template: jinja\n");
        result.format = jinja ? "jinja" : "plain";
        Matcher matcher = TOKEN.matcher(source);
        StringBuffer rendered = new StringBuffer();
        int offset = 0;
        while (matcher.find()) {
            String expression = matcher.group(2).strip();
            String token = matcher.group();
            String replacement = token;
            if (matcher.group(1) != null) { replacement = token.substring(1); }
            else if ("AS_NUMBER".equals(expression)) {
                add(result.candidates, expression);
                if (asNumber != null) { replacement = asNumber.toString(); }
                else { add(result.unresolved, expression); }
            } else if (jinja) {
                String key = expression.startsWith(META) ? expression.substring(META.length()) : null;
                if (valid(key)) { add(result.candidates, key); }
                add(result.unsupported, expression);
            } else if (valid(expression)) {
                add(result.candidates, expression);
                if (result.params.contains(expression) && values.containsKey(expression)) { replacement = values.get(expression); }
                else { add(result.unresolved, expression); }
            } else { add(result.unsupported, expression); }
            if (rendered.length() + matcher.start() - offset + replacement.length() > 786432) {
                throw new InvalidParameterValueException("Rendered CNI payload exceeds the maximum size");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
            offset = matcher.end();
        }
        matcher.appendTail(rendered);
        result.userdata = Base64.getEncoder().encodeToString(rendered.toString().getBytes(StandardCharsets.UTF_8));
        if (result.userdata.length() > 1048576) { throw new InvalidParameterValueException("Rendered CNI payload exceeds the maximum size"); }
        result.complete = result.missing.isEmpty() && result.extra.isEmpty() && result.unresolved.isEmpty() && result.unsupported.isEmpty();
        return result;
    }

    public static CniConfigurationPreviewResponse requireValues(String encoded, String params, Map<?, ?> values, Long asNumber) {
        CniConfigurationPreviewResponse result = preview(encoded, params, values, asNumber);
        if (!result.missing.isEmpty() || !result.extra.isEmpty()) {
            throw new InvalidParameterValueException("CNI variable values must match the declared custom names");
        }
        return result;
    }
}
