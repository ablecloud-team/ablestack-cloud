package org.apache.cloudstack.wallAlerts.mapper;

import org.apache.cloudstack.wallAlerts.client.WallApiClient.GrafanaRulesResponse;
import org.apache.cloudstack.wallAlerts.model.AlertRuleDto;
import org.apache.cloudstack.wallAlerts.model.AlertInstanceDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import java.time.OffsetDateTime;
import java.util.StringJoiner;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.api.response.WallSilenceResponse.SilenceMatcherResponse;
import org.apache.cloudstack.wallAlerts.model.SilenceDto;
import org.apache.cloudstack.wallAlerts.model.SilenceMatcherDto;

public final class WallMappers {
    private WallMappers() {}

    // ---------------------------
    // Rules -> AlertRuleDto
    // ---------------------------
    public static List<AlertRuleDto> toRuleDtos(GrafanaRulesResponse src) {
        if (src == null || src.data == null || src.data.groups == null) return List.of();

        List<AlertRuleDto> out = new ArrayList<>();
        for (var g : src.data.groups) {
            if (g.rules == null) continue;

            for (var r : g.rules) {
                AlertRuleDto d = new AlertRuleDto();

                // 1) 기본 메타
                d.setRuleGroup(g.name);
                d.setTitle(r.name);
                d.setDurationFor(formatDurationFor(r.duration)); // e.g. 90s -> "1m30s"
                d.setThreshold(extractThreshold(r));             // annotations 등에서 추출 시도
                d.setOperator(extractOperator(r));               // annotations 등에서 추출 시도

                // (선택) folderUid는 소스 필드가 없을 수 있음 → null 허용
                // d.setFolderUid(extractFolderUid(r)); // 필요 시 구현

                // 2) uid & dashboard url
                String dashUid = r.annotations == null ? null : r.annotations.get("__dashboardUid__");
                String panelId = r.annotations == null ? null : r.annotations.get("__panelId__");
                String uid = (dashUid != null && panelId != null)
                        ? (dashUid + ":" + panelId)
                        : (g.name + ":" + (r.name == null ? "rule" : r.name));
                d.setUid(uid);
                if (dashUid != null && panelId != null) {
                    d.setDashboardUrl("/d/" + dashUid + "?viewPanel=" + panelId);
                }

                // 3) 라벨/주석(원본 그대로 + null safe)
                d.setLabels(zeroIfNull(r.labels));
                d.setAnnotations(zeroIfNull(r.annotations));

                out.add(d);
            }
        }
        return out;
    }

    // ---------------------------
    // Rules(alerts 포함) -> AlertInstanceDto
    // ---------------------------
    public static List<AlertInstanceDto> toInstanceDtos(GrafanaRulesResponse src) {
        if (src == null || src.data == null || src.data.groups == null) return List.of();

        List<AlertInstanceDto> out = new ArrayList<>();
        for (var g : src.data.groups) {
            if (g.rules == null) continue;

            for (var r : g.rules) {
                // ruleUid 구성 로직은 toRuleDtos와 동일하게
                String dashUid = r.annotations == null ? null : r.annotations.get("__dashboardUid__");
                String panelId = r.annotations == null ? null : r.annotations.get("__panelId__");
                String ruleUid = (dashUid != null && panelId != null)
                        ? (dashUid + ":" + panelId)
                        : (g.name + ":" + (r.name == null ? "rule" : r.name));

                if (r.alerts == null) continue;

                for (var a : r.alerts) {
                    AlertInstanceDto d = new AlertInstanceDto();

                    // 1) fingerprint (없으면 라벨 기반으로 안정적 해시 생성)
                    d.setFingerprint(fingerprintOf(ruleUid, a.labels));

                    // 2) 상태/시각
                    d.setState(normalizeState(a.state));
                    d.setStartsAt(a.activeAt);   // OffsetDateTime 그대로
                    d.setEndsAt(null);           // rules 응답엔 보통 없음

                    // 3) 라벨: 원본 + 규칙 식별 보강
                    Map<String, String> labels = new HashMap<>(zeroIfNull(a.labels));
                    labels.putIfAbsent("ruleUid", ruleUid);
                    labels.putIfAbsent("rulegroup", g.name);
                    if (r.name != null) labels.putIfAbsent("alertname", r.name);
                    if (r.type != null) labels.putIfAbsent("kind", r.type);
                    d.setLabels(labels);

                    // 4) 주석
                    d.setAnnotations(zeroIfNull(a.annotations));

                    out.add(d);
                }
            }
        }
        return out;
    }

    // ---------------------------
    // Helpers
    // ---------------------------

    private static Map<String, String> zeroIfNull(Map<String, String> m) {
        return m == null ? Map.of() : m;
    }

    private static String formatDurationFor(Number duration) {
        if (duration == null) return null;
        int total = duration.intValue(); // seconds
        if (total <= 0) return "0s";
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h");
        if (m > 0) sb.append(m).append("m");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString();
    }

    private static Double extractThreshold(GrafanaRulesResponse.Rule r) {
        if (r == null || r.annotations == null) return null;
        String v = firstNonNull(
                r.annotations.get("threshold"),
                r.annotations.get("THRESHOLD"),
                r.annotations.get("__threshold__")
        );
        if (v == null) return null;
        try { return Double.valueOf(v); } catch (Exception ignore) { return null; }
    }

    private static String extractOperator(GrafanaRulesResponse.Rule r) {
        if (r == null || r.annotations == null) return null;
        return firstNonNull(
                r.annotations.get("operator"),
                r.annotations.get("OPERATOR"),
                r.annotations.get("__operator__")
        );
    }

    private static String normalizeState(String s) {
        if (s == null) return "UNKNOWN";
        switch (s.toLowerCase(Locale.ROOT)) {
            case "alerting": return "ALERTING";
            case "pending":  return "PENDING";
            case "normal":   return "OK";
            case "nodata":   return "NODATA";
            default:         return s.toUpperCase(Locale.ROOT);
        }
    }

    private static String fingerprintOf(String ruleUid, Map<String, String> labels) {
        String base = ruleUid == null ? "" : ruleUid;
        if (labels == null || labels.isEmpty()) {
            return Integer.toHexString(base.hashCode());
        }
        List<Map.Entry<String, String>> es = new ArrayList<>(labels.entrySet());
        es.sort(Map.Entry.comparingByKey());
        String joined = es.stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
        return Integer.toHexString((base + "|" + joined).hashCode());
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... items) {
        if (items == null) return null;
        for (T it : items) if (it != null) return it;
        return null;
    }

    public static WallSilenceResponse toResponse(final SilenceDto s) {
        final WallSilenceResponse r = new WallSilenceResponse();
        if (s == null) {
            return r;
        }
        r.setId(s.getId());
        r.setState(s.getStatus() != null ? s.getStatus().getState() : null);
        r.setCreatedBy(s.getCreatedBy());
        r.setComment(s.getComment());
        r.setStartsAt(fmtIso(s.getStartsAt()));
        r.setEndsAt(fmtIso(s.getEndsAt()));
        r.setCreatedAt(fmtIso(s.getCreatedAt()));
        r.setUpdatedAt(fmtIso(s.getUpdatedAt()));

        final List<SilenceMatcherDto> ms = s.getMatchers();
        if (ms != null && !ms.isEmpty()) {
            r.setMatchers(ms.stream()
                    .map(WallMappers::toResponse)
                    .collect(Collectors.toList()));
            r.setMatchersText(joinMatchersText(ms));
        }
        return r;
    }

    // SilenceMatcherDto -> SilenceMatcherResponse
    public static SilenceMatcherResponse toResponse(final SilenceMatcherDto m) {
        final SilenceMatcherResponse r = new SilenceMatcherResponse();
        if (m == null) {
            return r;
        }
        r.setName(m.getName());
        r.setValue(m.getValue());
        r.setIsRegex(Boolean.TRUE.equals(m.getIsRegex()));
        // Alertmanager v2에서 isEqual null은 true로 간주
        r.setIsEqual(m.getIsEqual() == null ? Boolean.TRUE : m.getIsEqual());
        return r;
    }

    // UI 표시용 matcher 문자열 생성: name(=,!=,=~,!~)value 조합
    private static String joinMatchersText(final List<SilenceMatcherDto> matchers) {
        final StringJoiner sj = new StringJoiner(", ");
        for (final SilenceMatcherDto m : matchers) {
            if (m == null) {
                continue;
            }
            final boolean isRegex = Boolean.TRUE.equals(m.getIsRegex());
            final boolean isEqual = m.getIsEqual() == null ? true : m.getIsEqual();
            final String op;
            if (isRegex) {
                op = isEqual ? "=~" : "!~";
            } else {
                op = isEqual ? "=" : "!=";
            }
            final String name = m.getName() == null ? "" : m.getName();
            final String value = m.getValue() == null ? "" : m.getValue();
            sj.add(name + op + value);
        }
        return sj.toString();
    }

    // OffsetDateTime -> ISO 문자열(기존 포맷터가 있다면 그걸로 교체 가능)
    private static String fmtIso(final OffsetDateTime t) {
        return t == null ? null : t.toString();
    }
}
