package org.apache.cloudstack.wallAlerts.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.cloudstack.wallAlerts.config.WallConfigKeys;
import org.apache.cloudstack.wallAlerts.exception.WallApiException;
import org.apache.cloudstack.wallAlerts.model.SilenceDto;
import org.apache.log4j.Logger;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 월(Wall, Grafana) API 호출 구현체입니다.
 * - 리스트/매퍼 관련 경로는 전혀 수정하지 않았습니다.
 * - 업데이트 경로만 최소한으로 보강(네임스페이스 해석 + POST/PUT 폴백)했습니다.
 */
public class WallApiClientImpl implements WallApiClient {

    private static final Logger LOG = Logger.getLogger(WallApiClientImpl.class);

    private final HttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;
    private final String bearer;      // null 가능
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public WallApiClientImpl() {
        this(
                WallConfigKeys.WALL_BASE_URL.value(),
                WallConfigKeys.WALL_API_TOKEN.value(),
                WallConfigKeys.CONNECT_TIMEOUT_MS.value(),
                WallConfigKeys.READ_TIMEOUT_MS.value()
        );
    }

    public WallApiClientImpl(final String baseUrl,
                             final String bearerTokenOrNull,
                             final int connectTimeoutMs,
                             final int readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.bearer = (bearerTokenOrNull == null || bearerTokenOrNull.isBlank()) ? null : bearerTokenOrNull;
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.readTimeoutMs = Math.max(1000, readTimeoutMs);

        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .connectTimeout(Duration.ofMillis(this.connectTimeoutMs))
                .build();

        this.om = new ObjectMapper()
                .registerModule(new JavaTimeModule()) // OffsetDateTime
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (this.bearer == null) {
            LOG.warn("[WallApiClient] no API token configured; Authorization header will not be sent");
        }
    }

    private String bearerNow() {
        final String v = org.apache.cloudstack.wallAlerts.config.WallConfigKeys.WALL_API_TOKEN.value();
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    // 매 호출 시 글로벌 세팅에서 Base URL을 읽습니다(비어있으면 생성자 값 사용).
    private String baseUrlNow() {
        final String v = org.apache.cloudstack.wallAlerts.config.WallConfigKeys.WALL_BASE_URL.value();
        return (v == null || v.isBlank()) ? this.baseUrl : trimTrailingSlash(v);
    }

    public static class Rule {
        @JsonProperty("uid")
        public String uid;

        @JsonProperty("title")
        public String title;

        @JsonProperty("labels")
        public Map<String, String> labels;

        @JsonProperty("annotations")
        public Map<String, String> annotations;

        @JsonProperty("for")
        public String forText;

        // ★ 중요: grafana_alert → alert로 매핑
        @JsonProperty("grafana_alert")
        public RulerRulesResponse.GrafanaAlert alert;
    }

    public static class GrafanaAlert {
        @JsonProperty("uid")
        public String uid;

        @JsonProperty("title")
        public String title;

        @JsonProperty("is_paused")
        @JsonAlias({ "isPaused", "paused" })
        public Boolean paused;
    }

    // --------------------------- Rules API ---------------------------

    @Override
    public GrafanaRulesResponse fetchRules() {
        final String url = baseUrlNow() + "/api/prometheus/grafana/api/v1/rules";
        try {
            // 토큰이 "설정되어 있는 경우"에만 /api/user로 인증 상태를 선확인
            final String b = bearerNow();
            if (b != null) {
                final HttpRequest.Builder rbUser = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrlNow() + "/api/user"))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .header("Authorization", "Bearer " + b);

                final HttpResponse<String> who = http.send(rbUser.GET().build(), HttpResponse.BodyHandlers.ofString());
                if (who.statusCode() != 200) {
                    throw new WallApiException("Wall /api/user returned " + who.statusCode() + " (invalid API token?)");
                }
                final boolean isAnonymous = om.readTree(who.body()).path("isAnonymous").asBoolean(false);
                if (isAnonymous) {
                    throw new WallApiException("API token provided but treated as anonymous (invalid/unauthorized token)");
                }
            }

            // Rules API 호출
            final HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "application/json");
            if (b != null) rb.header("Authorization", "Bearer " + b);

            final HttpResponse<String> res = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return om.readValue(res.body(), GrafanaRulesResponse.class);
            }

            LOG.warn("[Rules] GET " + url + " -> " + res.statusCode());
        } catch (WallApiException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("[Rules] fetchRules failed: " + e.getMessage(), e);
        }
        return null;
    }

    // --------------------------- Ruler API ---------------------------

    @Override
    public RulerRulesResponse fetchRulerRules() {
        final String base = baseUrlNow();
        final String[] urls = new String[] {
                base + "/api/ruler/grafana/api/v1/rules?subtype=cortex",
                base + "/api/ruler/grafana/api/v1/rules"
        };
        Exception lastErr = null;
        for (String url : urls) {
            try {
                final HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("Accept", "application/json");
                final String b = bearerNow();
                if (b != null) rb.header("Authorization", "Bearer " + b);

                final HttpResponse<String> res = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    LOG.warn("[Ruler] GET " + url + " -> " + res.statusCode());
                    continue;
                }

                final String body = res.body();
                final JsonNode root = om.readTree(body);

                // 응답 정규화: { "ns": [ {group}, ... ], ... } 형태 우선 처리
                final List<RulerRulesResponse.Folder> folders = new ArrayList<>();
                if (root.isObject()) {
                    for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext();) {
                        Map.Entry<String, JsonNode> e = it.next();
                        final String folderName = e.getKey();
                        final JsonNode groupsNode = e.getValue();
                        final List<RulerRulesResponse.Group> groups =
                                om.convertValue(groupsNode, new TypeReference<List<RulerRulesResponse.Group>>() {});
                        final RulerRulesResponse.Folder folder = new RulerRulesResponse.Folder();
                        folder.name = folderName;
                        folder.groups = groups;
                        folders.add(folder);
                    }
                } else if (root.has("folders") && root.get("folders").isArray()) {
                    for (JsonNode fn : root.get("folders")) {
                        final RulerRulesResponse.Folder folder = om.convertValue(fn, RulerRulesResponse.Folder.class);
                        folders.add(folder);
                    }
                } else {
                    LOG.warn("[Ruler] unexpected response format (preview): " + trimBody(body, 300));
                }

                final RulerRulesResponse out = new RulerRulesResponse();
                out.folders = folders;
                return out;

            } catch (Exception e) {
                lastErr = e;
                LOG.warn("[Ruler] fetch error for url=" + url + " msg=" + e.getMessage(), e);
            }
        }
        if (lastErr != null) {
            LOG.warn("[Ruler] fetchRulerRules failed: " + lastErr.getMessage());
        }
        return null;
    }

    @Override
    public boolean updateRuleThreshold(final String folderName,
                                       final String groupName,
                                       final String ruleTitle,
                                       final String operator,
                                       final Double newThreshold,
                                       final Double threshold2) {
        try {
            if (groupName == null || groupName.isBlank() || ruleTitle == null || ruleTitle.isBlank()) {
                LOG.warn("[Ruler][update] groupName and ruleTitle are required");
                return false;
            }
            if (newThreshold == null) {
                LOG.warn("[Ruler][update] threshold (newThreshold) is required");
                return false;
            }

            // 1) 표시명 NS 확보
            final String nsRaw = resolveNamespaceByHints(folderName, groupName, ruleTitle);
            if (nsRaw == null || nsRaw.isBlank()) {
                LOG.warn("[Ruler][update] namespace not resolved, group=" + groupName + ", title=" + ruleTitle);
                return false;
            }

            // 2) 그룹 로드(직접) + nsKey 정규화
            RulerRulesResponse.Group g = fetchRulerGroupDirect(nsRaw, groupName);
            String nsKeyForUrl;

            if (g == null) {
                final RulerRulesResponse all = fetchRulerRules();
                final GroupWithNs gw = selectGroupWithNsFromAll(all, nsRaw, groupName);
                if (gw == null) {
                    LOG.warn("[Ruler][update] group not found: nsRaw=" + nsRaw + ", group=" + groupName);
                    return false;
                }
                g = gw.group;
                nsKeyForUrl = gw.nsKey;
            } else {
                final RulerRulesResponse all = fetchRulerRules();
                final GroupWithNs gw = selectGroupWithNsFromAll(all, nsRaw, groupName);
                nsKeyForUrl = (gw != null && gw.nsKey != null && !gw.nsKey.isBlank()) ? gw.nsKey : nsRaw;
            }

            // 3) RAW 네임스페이스 JSON에서 해당 그룹만 추출
            final String folderUid = resolveFolderUidByTitle(nsRaw); // 먼저 계산
            ObjectNode groupJson = null;
            try {
                groupJson = fetchRulerGroupRawFlexible(nsRaw, nsKeyForUrl, folderUid, groupName);
            } catch (Exception e) {
                LOG.warn("[Ruler][update] fetch RAW failed: " + e.getMessage(), e);
            }
            if (groupJson == null) {
                LOG.warn("[Ruler][update] RAW group not found: nsRaw=" + nsRaw + ", nsKey=" + nsKeyForUrl + ", folderUid=" + folderUid + ", group=" + groupName);
                return false;
            }

            // 4) 임계치/연산자 수정(그 자리만)
            final String op = (operator == null || operator.isBlank()) ? "gt" : operator.trim();
            final boolean touched = mutateThresholdRaw(groupJson, ruleTitle, op, newThreshold, threshold2);

            // (A) 전송 직전 검증 로그
            try {
                final String target = ruleTitle;
                final JsonNode rules = groupJson.path("rules");
                String evalType = null;
                double p0 = Double.NaN, p1 = Double.NaN;
                for (JsonNode rn : rules) {
                    final String t1 = rn.path("title").asText(null);
                    final String t2 = rn.at("/grafana_alert/title").asText(null);
                    if ((target.equals(t1)) || (target.equals(t2))) {
                        final String condKey = rn.at("/grafana_alert/condition").asText("C");
                        final JsonNode data = rn.at("/grafana_alert/data");
                        if (data.isArray()) {
                            for (JsonNode dn : data) {
                                final String refId = dn.path("refId").asText("");
                                if (condKey.equals(refId)) {
                                    evalType = dn.at("/model/conditions/0/evaluator/type").asText(null);
                                    final JsonNode params = dn.at("/model/conditions/0/evaluator/params");
                                    if (params.isArray() && params.size() > 0) {
                                        p0 = params.get(0).asDouble(Double.NaN);
                                        if (params.size() > 1) p1 = params.get(1).asDouble(Double.NaN);
                                    }
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
                LOG.warn("[Ruler][update][preflight] group=" + groupName + " title=" + ruleTitle + " evalType=" + evalType + " p0=" + p0 + " p1=" + p1);
            } catch (Exception ignore) { /* no-op */ }

            if (!touched) {
                LOG.warn("[Ruler][update] evaluator not updated: nsKey=" + nsKeyForUrl + ", group=" + groupName + ", title=" + ruleTitle);
                return false;
            }

            // 5) 그룹 name 보정 + 읽기전용 필드 제거
            if (groupJson.get("name") == null || groupJson.get("name").asText().isBlank()) {
                groupJson.put("name", groupName);
            }
            sanitizeGroupForWrite(groupJson);

            // (B) rule.uid 없고 grafana_alert.uid만 있으면 rule.uid로 복사 (UID 재발급 방지)
            try {
                final JsonNode rules = groupJson.path("rules");
                if (rules.isArray()) {
                    for (JsonNode rn : rules) {
                        if (!(rn instanceof ObjectNode)) continue;
                        final ObjectNode ro = (ObjectNode) rn;
                        final String ruleUid = ro.path("uid").asText(null);
                        final String gaUid   = ro.at("/grafana_alert/uid").asText(null);
                        if ((ruleUid == null || ruleUid.isBlank()) && gaUid != null && !gaUid.isBlank()) {
                            ro.put("uid", gaUid);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[Ruler][update] uid backfill failed: " + e.getMessage());
            }

            // 바디 2종
            final String groupBody     = groupJson.toString();
            final String jsonGroupsObj = buildGroupsWrappedBody(groupJson);

            final String base = baseUrlNow();
            LOG.warn("[Ruler][update][payload] " + trimBody(groupBody, 1200));

            // 6) ns 후보 구성 (UID 우선)
            final java.util.LinkedHashSet<String> nsSet = new java.util.LinkedHashSet<>();
            if (folderUid != null && !folderUid.isBlank()) nsSet.add(folderUid);
            nsSet.add(nsKeyForUrl);
            nsSet.add(replaceSpacesWithHyphen(nsKeyForUrl));
            nsSet.add(slugify(nsKeyForUrl).toLowerCase(java.util.Locale.ROOT));

            // 7) 전송
            for (String nsCand : nsSet) {
                if (nsCand == null || nsCand.isBlank()) continue;
                final String encNs = encodePathSegment(nsCand);
                final String encGroup = encodePathSegment(groupName);

                final String[] putGroupUrls = new String[] {
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup + "?subtype=cortex",
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup
                };
                for (String u : putGroupUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", groupBody)) return true;
                }

                final String[] postNsUrls = new String[] {
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "?subtype=cortex",
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs
                };
                for (String u : postNsUrls) {
                    if (trySend(u, "POST", "application/json; charset=utf-8", groupBody)) return true;
                }
                for (String u : postNsUrls) {
                    if (trySend(u, "POST", "application/json; charset=utf-8", jsonGroupsObj)) return true;
                }
                for (String u : postNsUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", groupBody)) return true;
                }
                for (String u : postNsUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", jsonGroupsObj)) return true;
                }
            }

            LOG.warn("[Ruler][update] all attempts failed for nsKey=" + nsKeyForUrl + ", group=" + groupName + ", title=" + ruleTitle);
            return false;

        } catch (Exception e) {
            LOG.warn("[Ruler][update] failed: " + e.getMessage(), e);
            return false;
        }
    }


    @Override
    public boolean pauseRule(final String folderName,
                             final String groupName,
                             final String ruleUid,
                             final boolean paused) {
        try {
            if (groupName == null || groupName.isBlank() || ruleUid == null || ruleUid.isBlank()) {
                LOG.warn("[Ruler][pause] groupName and ruleUid are required");
                return false;
            }


            // 0) **** 런타임 API(UID) 우선 시도: orgId 쿼리 강제 ****
            if (pauseByRuntimeApi(ruleUid, paused)) {
                LOG.warn("[Pause][runtime] uid=" + ruleUid + " paused=" + paused + " OK");
                return true;
            } else {
                LOG.warn("[Pause][runtime] uid=" + ruleUid + " paused=" + paused + " failed; fallback to Ruler write");
            }
            if (tryPauseSingleRule(ruleUid, paused)) {
                LOG.warn("[Pause][direct] alert-rule uid=" + ruleUid + " paused=" + paused + " OK");
                return true;
            } else {
                LOG.warn("[Pause][direct] alert-rule uid=" + ruleUid + " paused=" + paused + " not supported or failed; fallback to Ruler");
            }

            // 1) 네임스페이스 표시명 확보
            final String nsRaw = (folderName != null && !folderName.isBlank())
                    ? folderName
                    : resolveNamespaceByHints(folderName, groupName, null);
            if (nsRaw == null || nsRaw.isBlank()) {
                LOG.warn("[Ruler][pause] namespace not resolved, group=" + groupName + ", uid=" + ruleUid);
                return false;
            }

            // 2) 그룹 존재 확인 및 nsKey 추정(기존 로직 유지)
            RulerRulesResponse.Group g = fetchRulerGroupDirect(nsRaw, groupName);
            String nsKeyForUrl;
            if (g == null) {
                final RulerRulesResponse all = fetchRulerRules();
                final GroupWithNs found = selectGroupWithNsFromAll(all, nsRaw, groupName);
                if (found == null) {
                    LOG.warn("[Ruler][pause] group not found: nsRaw=" + nsRaw + ", group=" + groupName);
                    return false;
                }
                g = found.group;
                nsKeyForUrl = found.nsKey;
            } else {
                final RulerRulesResponse all = fetchRulerRules();
                final GroupWithNs found = selectGroupWithNsFromAll(all, nsRaw, groupName);
                nsKeyForUrl = (found != null && found.nsKey != null && !found.nsKey.isBlank()) ? found.nsKey : nsRaw;
            }

            // 3)  폴더 UID 포함 후보 키 구성 (READ 단계에도 적용)
            final String folderUid = resolveFolderUidByTitle(nsRaw);
            final LinkedHashSet<String> nsCandidates = new LinkedHashSet<>();
            if (folderUid != null && !folderUid.isBlank()) nsCandidates.add(folderUid);
            if (nsKeyForUrl != null && !nsKeyForUrl.isBlank()) nsCandidates.add(nsKeyForUrl);
            nsCandidates.add(replaceSpacesWithHyphen(nsKeyForUrl));
            nsCandidates.add(slugify(nsKeyForUrl).toLowerCase(Locale.ROOT));

            // 4)  RAW 네임스페이스 JSON 로드(후보 순회)
            ObjectNode nsRawJson = null;
            String nsKeyResolved = null;
            for (String nsCand : nsCandidates) {
                if (nsCand == null || nsCand.isBlank()) continue;
                try {
                    final ObjectNode tryJson = fetchRulerNamespaceRaw(nsCand);
                    if (tryJson != null) {
                        nsRawJson = tryJson;
                        nsKeyResolved = nsCand;
                        break;
                    }
                } catch (Exception e) {
                    LOG.warn("[Ruler][pause] fetch RAW failed for ns=" + nsCand + ": " + e.getMessage());
                }
            }
            if (nsRawJson == null) {
                LOG.warn("[Ruler][pause] cannot load namespace RAW JSON for any candidate; folder=" + nsRaw);
                return false;
            }

            // 5) 그룹 추출
            final ObjectNode groupJson = findGroupObjectFlexible(nsRawJson, groupName, nsKeyResolved, nsKeyForUrl, nsRaw);
            if (groupJson == null) {
                LOG.warn("[Ruler][pause] cannot find group in RAW JSON: group=" + groupName);
                return false;
            }

            // 6) 룰 UID 기준 pause 토글(in-place)
            final boolean touched = mutatePauseRawInPlace(groupJson, ruleUid, paused);
            if (!touched) {
                LOG.warn("[Ruler][pause] target rule not found in group: uid=" + ruleUid + ", group=" + groupName);
                return false;
            }

            // 7) 그룹 name 보정
            if (!groupJson.hasNonNull("name") || groupJson.get("name").asText().isBlank()) {
                groupJson.put("name", groupName);
            }
            try {
                sanitizeGroupForWrite(groupJson); // 읽기전용/널 필드 제거 (id/orgId 등)
                // rule.uid 없고 grafana_alert.uid만 있으면 복사
                final JsonNode rules = groupJson.path("rules");
                if (rules.isArray()) {
                    for (JsonNode rn : rules) {
                        if (!(rn instanceof ObjectNode)) continue;
                        final ObjectNode ro = (ObjectNode) rn;
                        final String ru = ro.path("uid").asText(null);
                        final String ga = ro.at("/grafana_alert/uid").asText(null);
                        if ((ru == null || ru.isBlank()) && ga != null && !ga.isBlank()) {
                            ro.put("uid", ga);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[Ruler][pause] sanitize/backfill failed: " + e.getMessage(), e);
            }

            if (!groupJson.has("orgId")) {
                groupJson.put("orgId", 1); // 환경에 맞게 1 사용
            }

            // 8) 전송 바디 구성
            final String groupBody     = groupJson.toString();
            final String jsonGroupsObj = buildGroupsWrappedBody(groupJson);
            LOG.warn("[Ruler][pause][payload] " + trimBody(jsonGroupsObj, 1200));

            // 9)  POST도 동일 후보 순회 (UID 우선)
            final java.util.LinkedHashSet<String> postCandidates = new java.util.LinkedHashSet<>();
            if (folderUid != null && !folderUid.isBlank()) postCandidates.add(folderUid);
            if (nsKeyResolved != null && !nsKeyResolved.isBlank()) postCandidates.add(nsKeyResolved);
            if (nsKeyForUrl != null && !nsKeyForUrl.isBlank()) postCandidates.add(nsKeyForUrl);
            postCandidates.add(replaceSpacesWithHyphen(nsKeyForUrl));
            postCandidates.add(slugify(nsKeyForUrl).toLowerCase(java.util.Locale.ROOT));

            final String base = baseUrlNow();
            for (String nsCand : postCandidates) {
                if (nsCand == null || nsCand.isBlank()) continue;
                final String encNs    = encodePathSegment(nsCand);
                final String encGroup = encodePathSegment(groupName);

                // 1) 그룹 PUT
                final String[] putGroupUrls = new String[] {
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup + "?subtype=cortex",
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup
                };
                for (String u : putGroupUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", groupBody)) {
                        LOG.warn("[Ruler][pause] PUT accepted: " + u);
                        // 검증은 '보조'로만 수행 (성공/실패에 상관없이 true 확정)
                        try { verifyPausePersisted(nsCand, groupName, ruleUid, paused); } catch (Exception e) { LOG.warn("[verify] " + e.getMessage()); }
                        return true; // 조기 리턴
                    }
                }

                // 2) 네임스페이스 POST — 그룹 바디 우선
                final String[] postNsUrls = new String[] {
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs + "?subtype=cortex",
                        base + "/api/ruler/grafana/api/v1/rules/" + encNs
                };
                for (String u : postNsUrls) {
                    if (trySend(u, "POST", "application/json; charset=utf-8", groupBody)) {
                        LOG.warn("[Ruler][pause] POST accepted: " + u);
                        try { verifyPausePersisted(nsCand, groupName, ruleUid, paused); } catch (Exception e) { LOG.warn("[verify] " + e.getMessage()); }
                        return true; // 조기 리턴
                    }
                }

                // 3) 네임스페이스 POST — 래핑 바디 폴백
                for (String u : postNsUrls) {
                    if (trySend(u, "POST", "application/json; charset=utf-8", jsonGroupsObj)) {
                        LOG.warn("[Ruler][pause] POST(wrapped) accepted: " + u);
                        try { verifyPausePersisted(nsCand, groupName, ruleUid, paused); } catch (Exception e) { LOG.warn("[verify] " + e.getMessage()); }
                        return true; // 조기 리턴
                    }
                }

                // 4) 네임스페이스 PUT — 그룹 바디 폴백
                for (String u : postNsUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", groupBody)) {
                        LOG.warn("[Ruler][pause] PUT(ns,groupBody) accepted: " + u);
                        try { verifyPausePersisted(nsCand, groupName, ruleUid, paused); } catch (Exception e) { LOG.warn("[verify] " + e.getMessage()); }
                        return true; // 조기 리턴
                    }
                }

                // 5) 네임스페이스 PUT — 래핑 바디 최후 폴백
                for (String u : postNsUrls) {
                    if (trySend(u, "PUT", "application/json; charset=utf-8", jsonGroupsObj)) {
                        LOG.warn("[Ruler][pause] PUT(ns,wrapped) accepted: " + u);
                        try { verifyPausePersisted(nsCand, groupName, ruleUid, paused); } catch (Exception e) { LOG.warn("[verify] " + e.getMessage()); }
                        return true; // 조기 리턴
                    }
                }
            }
            // 최종 실패
            LOG.warn("[Ruler][pause] all attempts failed or were not accepted by server");
            return false;

        } catch (Exception e) {
            LOG.warn("[Ruler][pause] failed: " + e.getMessage(), e);
            return false;
        }
    }

    // WallApiClientImpl.java

    @Override
    public boolean pauseByUid(final String ruleUid, final boolean paused) {
        if (ruleUid == null || ruleUid.isBlank()) {
            LOG.warn("[Pause][direct] ruleUid is required");
            return false;
        }
        final String base = baseUrlNow();
        final String url  = base + "/api/alert-rules/" + encodePathSegment(ruleUid) + "/pause";
        final String body = "{\"paused\":" + (paused ? "true" : "false") + "}";
        LOG.warn("[Pause][direct] POST " + url + " body=" + body);
        // trySend는 2xx(200~299, 202 포함)면 true를 반환하도록 이미 구현되어 있습니다.
        return trySend(url, "POST", "application/json; charset=utf-8", body);
    }


    // -------------------- Silences --------------------
    @Override
    public List<SilenceDto> listSilences(final String stateFilter) {
        try {
            final StringBuilder sb = new StringBuilder();
            sb.append(baseUrl).append("/api/alertmanager/grafana/api/v2/silences");
            boolean hasQuery = false;
            if (stateFilter != null && !stateFilter.isBlank()) {
                // ✅ Alertmanager v2는 state 필터를 filter=state=active 로 받는다.
                sb.append(hasQuery ? "&" : "?")
                        .append("filter=")
                        .append(URLEncoder.encode("state=" + stateFilter.toLowerCase(java.util.Locale.ROOT),
                                java.nio.charset.StandardCharsets.UTF_8));
                hasQuery = true;
            }
            final String url = sb.toString();

            // GET + Org 헤더 포함
            final String body = tryGetForBody(url);
            List<SilenceDto> list = parseSilenceArray(body);
            // 폴백: 혹시 위 필터가 안 먹는 Grafana/프록시 환경이면 무필터 호출 후 자바에서 상태로 거른다.
            if ((list == null || list.isEmpty()) && stateFilter != null && !stateFilter.isBlank()) {
                final String body2 = tryGetForBody(baseUrl + "/api/alertmanager/grafana/api/v2/silences");
                list = parseSilenceArray(body2);
                if (list == null) list = java.util.Collections.emptyList();
                final String want = stateFilter.toLowerCase(java.util.Locale.ROOT);
                final java.util.List<SilenceDto> filtered = new java.util.ArrayList<>();
                for (final SilenceDto s : list) {
                    final String st = (s.getStatus() != null && s.getStatus().getState() != null)
                            ? s.getStatus().getState().toLowerCase(java.util.Locale.ROOT) : "";
                    if (want.equals(st)) filtered.add(s);
                }
                return filtered;
            }
            return (list != null) ? list : java.util.Collections.emptyList();
        } catch (Exception e) {
            LOG.warn("[Silence][list] failed: {}");
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void expireSilence(final String silenceId) {
        if (silenceId == null || silenceId.isBlank()) {
            throw new IllegalArgumentException("silenceId must not be null/empty");
        }
        final String enc = URLEncoder.encode(silenceId, StandardCharsets.UTF_8);
        final String url = baseUrl + "/api/alertmanager/grafana/api/v2/silence/" + enc;
        final boolean ok = trySend(url, "DELETE", null, null);
        if (!ok) {
            throw new RuntimeException("Failed to expire silence: " + silenceId);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("[Silences] expired {}");
        }
    }



    // --------------------------- 내부 헬퍼 ---------------------------


    private List<SilenceDto> parseSilenceArray(final String body) {
        try {
            if (body == null || body.isBlank()) return java.util.Collections.emptyList();
            final SilenceDto[] arr = om.readValue(body, SilenceDto[].class);
            if (arr == null || arr.length == 0) return java.util.Collections.emptyList();
            final java.util.List<SilenceDto> out = new java.util.ArrayList<>(arr.length);
            for (final SilenceDto s : arr) if (s != null) out.add(s);
            return out;
        } catch (Exception e) {
            LOG.warn("[Silence][parse] {}");
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 기존 trySend는 boolean만 반환하므로, GET 응답 바디가 필요한 조회 전용 헬퍼를 추가합니다.
     * trySend를 수정하지 않습니다.
     */
    private String tryGetForBody(final String url) {
        try {
            final HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "application/json")
                    .header("X-Grafana-Org-Id", "1")
                    .header("X-Scope-OrgID", "1");

            String b = null;
            try { b = bearerNow(); } catch (Throwable ignore) {}
            if (b == null || b.isBlank()) b = bearer;
            if (b != null && !b.isBlank()) {
                rb.header("Authorization", "Bearer " + b);
            }

            final HttpResponse<String> res =
                    http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            final int sc = res.statusCode();
            if (sc >= 200 && sc < 300) {
                return res.body();
            }
            LOG.warn("[Silence][get] {} -> {} (preview: {})");
            return null;
        } catch (Exception e) {
            LOG.warn("[Silence][get] {} failed: {}");
            return null;
        }
    }

    @Override
    public SilenceDto getSilence(final String id, final boolean ruleMetadata, final boolean accessControl) {
        try {
            if (id == null || id.isEmpty()) return null;

            final StringBuilder sb = new StringBuilder();
            sb.append(baseUrl)
                    .append("/api/alertmanager/grafana/api/v2/silence/")
                    .append(URLEncoder.encode(id, StandardCharsets.UTF_8.name()));

            boolean first = true;
            if (ruleMetadata) {
                sb.append(first ? "?" : "&").append("ruleMetadata=true");
                first = false;
            }
            if (accessControl) {
                sb.append(first ? "?" : "&").append("accesscontrol=true");
                first = false;
            }
            final String url = sb.toString();

            final String body = tryGetForBody(url);
            if (body == null || body.isBlank()) return null;

            // ObjectMapper 인스턴스가 필드로 있다면 그대로 사용 (예: om)
            return om.readValue(body, SilenceDto.class);

        } catch (Exception e) {
            LOG.warn("[Silence][get] id={} failed: {}");
            return null;
        }
    }

    private boolean mutateThresholdRaw(final ObjectNode groupJson,
                                       final String ruleTitle,
                                       final String operator,
                                       final Double newThreshold,
                                       final Double threshold2) {
        if (groupJson == null || ruleTitle == null || newThreshold == null) return false;

        final JsonNode rules = groupJson.path("rules");
        if (!rules.isArray()) return false;

        final String op = (operator == null || operator.isBlank()) ? "gt" : operator.trim();
        final String evalType =
                "between".equals(op) || "within_range".equals(op) ? "within_range" :
                        "outside".equals(op) || "outside_range".equals(op) ? "outside_range" :
                                "lte".equals(op) ? "lte" :
                                        "gte".equals(op) ? "gte" :
                                                "lt".equals(op)  ? "lt"  : "gt";

        for (JsonNode rn : rules) {
            final String t1 = rn.path("title").asText(null);
            final String t2 = rn.at("/grafana_alert/title").asText(null);
            if (!(ruleTitle.equals(t1) || ruleTitle.equals(t2))) continue;

            final String condKey = rn.at("/grafana_alert/condition").asText("C");
            final JsonNode data = rn.at("/grafana_alert/data");
            if (!data.isArray()) continue;

            for (JsonNode dn : data) {
                if (!condKey.equals(dn.path("refId").asText(""))) continue;

                final JsonNode conds = dn.at("/model/conditions");
                if (!conds.isArray() || conds.size() == 0) continue;

                final JsonNode c0 = conds.get(0);
                if (!(c0 instanceof ObjectNode)) continue;

                final ObjectNode evaluator = (ObjectNode) c0.path("evaluator");
                if (evaluator == null) continue;

                evaluator.put("type", evalType);
                final ArrayNode params = evaluator.withArray("params");
                params.removeAll();

                if ("within_range".equals(evalType) || "outside_range".equals(evalType)) {
                    if (threshold2 == null) return false;
                    final double lo = Math.min(newThreshold, threshold2);
                    final double hi = Math.max(newThreshold, threshold2);
                    params.add(lo);
                    params.add(hi);
                } else {
                    params.add(newThreshold);
                }
                return true;
            }
        }
        return false;
    }


    private boolean pauseByRuntimeApi(final String uid, final boolean paused) {
        if (uid == null || uid.isBlank()) return false;

        final String base = baseUrlNow();
        final String ct   = "application/json; charset=utf-8";
        final String body = "{\"paused\":" + (paused ? "true" : "false") + "}";

        // 배포/버전별 경로 차이를 모두 커버
        final String encUid = encodePathSegment(uid);
        final String[] urls = new String[] {
                // 가장 널리 쓰이는 경로
                base + "/api/alert-rules/"     + encUid + "/pause?orgId=1",
                // 일부 배포에서만 열려있는 경로
                base + "/api/alert-rules/uid/" + encUid + "/pause?orgId=1",
                // 구/변형 경로 호환
                base + "/api/alerting/rules/"  + encUid + "/pause?orgId=1"
        };

        for (String u : urls) {
            if (trySend(u, "POST", ct, body)) {
                return true; // 2xx(200/202/204 등)면 성공 처리
            }
        }
        return false;
    }

    private boolean tryPauseSingleRule(final String ruleUid, final boolean paused) {
        if (ruleUid == null || ruleUid.isBlank()) return false;

        final String base = baseUrlNow();
        final String body = "{\"paused\":" + (paused ? "true" : "false") + "}";

        // 최신 Grafana (Unified Alerting)
        final String u1 = base + "/api/alert-rules/" + encodePathSegment(ruleUid) + "/pause";
        // 일부 배포/리버스 프록시 환경 또는 UID 라우팅 버전
        final String u2 = base + "/api/alert-rules/uid/" + encodePathSegment(ruleUid) + "/pause";
        // 매우 구버전/레거시(대부분 404일 것) — 혹시 몰라 마지막 폴백
        final String u3 = base + "/api/alerts/" + encodePathSegment(ruleUid) + "/pause";

        final String ct = "application/json; charset=utf-8";

        if (trySend(u1, "POST", ct, body)) {
            LOG.warn("[Pause][direct] OK " + u1);
            return true;
        }
        if (trySend(u2, "POST", ct, body)) {
            LOG.warn("[Pause][direct] OK " + u2);
            return true;
        }
        if (trySend(u3, "POST", ct, body)) {
            LOG.warn("[Pause][direct] OK " + u3);
            return true;
        }
        LOG.warn("[Pause][direct] all endpoints failed for uid=" + ruleUid);
        return false;
    }


    private boolean verifyPausePersisted(final String nsCand, final String groupName,
                                         final String ruleUid, final boolean expected) {
        try {
            // 202 비동기 적용을 고려하여 짧게 재시도
            for (int i = 0; i < 6; i++) {
                final ObjectNode ns = fetchRulerNamespaceRaw(nsCand);
                final ObjectNode group = findGroupObject(ns, groupName);
                if (group != null) {
                    final JsonNode rules = group.path("rules");
                    if (rules.isArray()) {
                        for (JsonNode rn : rules) {
                            final String uid  = rn.path("uid").asText(null);
                            final String gaId = rn.at("/grafana_alert/uid").asText(null);
                            if (!ruleUid.equals(uid) && !ruleUid.equals(gaId)) continue;

                            // ★ 모든 변형 키에서 읽기
                            final boolean v = rn.path("is_paused").asBoolean(
                                    rn.path("isPaused").asBoolean(
                                            rn.path("paused").asBoolean(
                                                    rn.at("/grafana_alert/is_paused").asBoolean(
                                                            rn.at("/grafana_alert/isPaused").asBoolean(false)))));

                            if (v == expected) return true;
                        }
                    }
                }
                Thread.sleep(150L); // 짧은 backoff
            }
        } catch (Exception ignore) { /* no-op */ }
        return false;
    }


    private ObjectNode findGroupObjectFlexible(final ObjectNode nsRawJson,
                                               final String groupName,
                                               final String nsKeyResolved,   // 예: "fe4ec9efgaakgc"
                                               final String nsKeyForUrl,     // 예: "Alert Rule"
                                               final String folderUidOrTitle // 예: folderUid 또는 nsRaw
    ) {
        if (nsRawJson == null || groupName == null || groupName.isBlank()) return null;

        // 0) 기존 형태 우선: { "groups":[...] } 또는 { "data": { "groups":[...] } }
        ObjectNode group = findGroupObject(nsRawJson, groupName);
        if (group != null) return group;

        // 1) 후보 키들에서 배열을 찾아본다
        final String[] keys = new String[] { nsKeyResolved, nsKeyForUrl, folderUidOrTitle };
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            final JsonNode v = nsRawJson.get(key);
            if (v != null && v.isArray()) {
                final ObjectNode g = findByNameInArray((ArrayNode) v, groupName);
                if (g != null) return g;
            }
        }

        // 2) 최후: 루트 모든 필드 순회 — 값이 배열이고 그 배열 원소가 그룹 스키마인지 확인
        for (var it = nsRawJson.fields(); it.hasNext(); ) {
            final var e = it.next();
            final JsonNode val = e.getValue();
            if (val != null && val.isArray()) {
                final ObjectNode g2 = findByNameInArray((ArrayNode) val, groupName);
                if (g2 != null) return g2;
            }
        }

        return null;
    }

    private ObjectNode findByNameInArray(final ArrayNode groupsArray, final String groupName) {
        if (groupsArray == null) return null;
        for (int i = 0; i < groupsArray.size(); i++) {
            final JsonNode node = groupsArray.get(i);
            if (node instanceof ObjectNode) {
                final ObjectNode obj = (ObjectNode) node;
                final String name = obj.path("name").asText(null);
                // "rules"가 배열인 것도 간단히 확인(그룹 스키마)
                final boolean looksLikeGroup = obj.has("rules") && obj.get("rules").isArray();
                if (looksLikeGroup && groupName.equals(name)) {
                    return obj;
                }
            }
        }
        return null;
    }

    private String resolveFolderUidByTitle(final String title) {
        if (title == null || title.isBlank()) return null;
        try {
            final String url = baseUrl + "/api/search?type=dash-folder&query="
                    + java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "application/json");
            if (bearer != null) rb.header("Authorization", "Bearer " + bearer);

            final java.net.http.HttpResponse<String> res =
                    http.send(rb.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                LOG.warn("[Folders] GET " + url + " -> " + res.statusCode());
                return null;
            }

            final com.fasterxml.jackson.databind.JsonNode arr = om.readTree(res.body());
            if (!arr.isArray()) return null;

            for (final com.fasterxml.jackson.databind.JsonNode n : arr) {
                if (!"dash-folder".equals(n.path("type").asText())) continue;
                final String t = n.path("title").asText(null);
                if (t != null && (t.equals(title) || t.equalsIgnoreCase(title))) {
                    final String uid = n.path("uid").asText(null);
                    if (uid != null && !uid.isBlank()) return uid; // 폴더 UID
                }
            }
        } catch (Exception e) {
            LOG.warn("[Folders] resolveFolderUidByTitle failed: " + e.getMessage(), e);
        }
        return null;
    }

    private static String trimBody(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + ")";
    }

    /** 전송 전 정리: title 보정 + null 필드 제거(재귀). */
    private void sanitizeGroupForWrite(final ObjectNode groupJson) {
        if (groupJson == null) return;
        final JsonNode rules = groupJson.get("rules");
        if (rules != null && rules.isArray()) {
            final ArrayNode arr = (ArrayNode) rules;
            for (int i = 0; i < arr.size(); i++) {
                final JsonNode rn = arr.get(i);
                if (!(rn instanceof ObjectNode)) continue;
                final ObjectNode r = (ObjectNode) rn;

                // uid 백필
                backfillUidIfMissing(r);

                // pause 세 필드 동기화 (isPaused/paused/grafana_alert.isPaused)
                syncPauseFields(r);

                // title이 null/빈문자면 grafana_alert.title로 채움
                final String gaTitle = textOrNull(r.at("/grafana_alert/title"));
                final JsonNode tNode = r.get("title");
                final boolean titleMissingOrBlank =
                        (tNode == null || tNode.isNull() || (tNode.isTextual() && tNode.asText("").isBlank()));
                if (titleMissingOrBlank && gaTitle != null && !gaTitle.isBlank()) {
                    r.put("title", gaTitle);
                }

                pruneNullsDeep(r); // null 제거(빈 문자열은 그대로 둠)
            }
        }
        pruneNullsDeep(groupJson); // 루트에서도 null 제거
        LOG.warn("[pause][payload-check] " +
                groupJson.at("/rules/0/uid") + " isPaused=" + groupJson.at("/rules/0/isPaused") +
                " paused=" + groupJson.at("/rules/0/paused") +
                " ga.isPaused=" + groupJson.at("/rules/0/grafana_alert/isPaused"));
    }

    /** r.uid 가 비어있으면 grafana_alert.uid 로 백필합니다. */
    private void backfillUidIfMissing(final ObjectNode r) {
        String uid = (r.hasNonNull("uid") && r.get("uid").isTextual()) ? r.get("uid").asText() : null;
        if (uid == null || uid.isBlank()) {
            final String gaUid = r.at("/grafana_alert/uid").asText(null);
            if (gaUid != null && !gaUid.isBlank()) {
                r.put("uid", gaUid);
            }
        }
    }

    /** pause 필드를 isPaused/paused/grafana_alert.isPaused 세 군데 모두에 동기화합니다. */
    private void syncPauseFields(final ObjectNode r) {
        Boolean v = null;
        if (r.has("isPaused") && !r.get("isPaused").isNull()) {
            v = r.get("isPaused").asBoolean(false);
        } else if (r.has("paused") && !r.get("paused").isNull()) {
            v = r.get("paused").asBoolean(false);
        } else {
            final JsonNode ga = r.get("grafana_alert");
            if (ga != null && ga.isObject() && ga.has("isPaused") && !ga.get("isPaused").isNull()) {
                v = ga.get("isPaused").asBoolean(false);
            }
        }
        if (v != null) {
            r.put("isPaused", v.booleanValue());   // 네 YAML 스키마의 핵심
            r.put("paused",   v.booleanValue());   // 호환용
            final JsonNode ga = r.get("grafana_alert");
            if (ga != null && ga.isObject()) {
                ((ObjectNode) ga).put("isPaused", v.booleanValue());
            }
        }
    }

    /** 재귀적으로 null 값을 가진 필드를 제거합니다. */
    private void pruneNullsDeep(final ObjectNode obj) {
        final java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = obj.fields();
        final java.util.List<String> removeKeys = new java.util.ArrayList<>();
        while (it.hasNext()) {
            final java.util.Map.Entry<String, JsonNode> e = it.next();
            final JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                removeKeys.add(e.getKey());
            } else if (v.isObject()) {
                pruneNullsDeep((ObjectNode) v);
            } else if (v.isArray()) {
                final ArrayNode arr = (ArrayNode) v;
                for (int i = 0; i < arr.size(); i++) {
                    final JsonNode ai = arr.get(i);
                    if (ai != null && ai.isObject()) {
                        pruneNullsDeep((ObjectNode) ai);
                    }
                }
            }
        }
        for (String k : removeKeys) obj.remove(k);
    }

    /** 경로 세그먼트 전용 인코더: 공백은 %20, '+' 금지 */
    private static String encodePathSegment(final String s) {
        if (s == null) return null;
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    /** 전체 Ruler 트리에서 nsRaw와 group으로 그룹을 찾고, URL에 쓸 nsKey를 함께 반환합니다. */
    private static GroupWithNs selectGroupWithNsFromAll(final WallApiClient.RulerRulesResponse all,
                                                        final String nsRaw,
                                                        final String groupName) {
        if (all == null || all.folders == null || nsRaw == null || groupName == null) return null;
        for (WallApiClient.RulerRulesResponse.Folder f : all.folders) {
            if (f == null || f.groups == null) continue;
            final boolean nsMatch = nsRaw.equals(f.name)
                    || nsRaw.equalsIgnoreCase(f.name)
                    || slugify(nsRaw).equals(slugify(f.name))
                    || replaceSpacesWithHyphen(nsRaw).equals(replaceSpacesWithHyphen(f.name));
            if (!nsMatch) continue;

            for (WallApiClient.RulerRulesResponse.Group g : f.groups) {
                if (g == null) continue;
                if (groupName.equals(g.name) || groupName.equalsIgnoreCase(g.name)) {
                    return new GroupWithNs(f.name, g); // URL에는 f.name(키)을 사용
                }
            }
        }
        return null;
    }

    private static final class GroupWithNs {
        final String nsKey;
        final WallApiClient.RulerRulesResponse.Group group;
        GroupWithNs(final String nsKey, final WallApiClient.RulerRulesResponse.Group group) {
            this.nsKey = nsKey;
            this.group = group;
        }
    }

    /** 간단 슬러그: 공백→-, 영숫자/하이픈만 유지 */
    private static String slugify(final String s) {
        if (s == null) return null;
        String r = s.trim().replaceAll("\\s+", "-");
        r = r.replaceAll("[^A-Za-z0-9\\-]", "");
        r = r.replaceAll("-{2,}", "-");
        return r;
    }

    private static String replaceSpacesWithHyphen(final String s) {
        return s == null ? null : s.trim().replaceAll("\\s+", "-");
    }

    private String resolveNamespaceByHints(final String folderName,
                                           final String groupName,
                                           final String ruleTitle) {
        if (looksLikeNamespaceUid(folderName)) {
            return folderName;
        }
        // 1) 그룹명으로 1차 후보를 찾더라도, 그 그룹에 '해당 타이틀'이 실제 있는지 확인
        final String byGroup = resolveNamespaceByGroupName(groupName);
        if (byGroup != null) {
            final RulerRulesResponse.Group g0 = fetchRulerGroupDirect(byGroup, groupName);
            if (g0 != null && groupContainsTitle(g0, ruleTitle)) {
                return byGroup; // 그룹+타이틀 모두 일치할 때만 채택
            }
            // 일치하지 않으면 계속 진행해서 정확한 폴더를 찾음
        }

        // 2) 전체 트리에서 group+title 동시 매칭(원래 있던 정확 매칭 경로)
        final RulerRulesResponse all = fetchRulerRules();
        if (all == null || all.folders == null) return null;
        for (RulerRulesResponse.Folder f : all.folders) {
            if (f == null || f.groups == null) continue;
            for (RulerRulesResponse.Group g : f.groups) {
                if (g == null || g.rules == null) continue;
                if (!groupName.equals(g.name)) continue;
                for (RulerRulesResponse.Rule r : g.rules) {
                    final String title = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
                    if (ruleTitle.equals(title)) {
                        return f.name;
                    }
                }
            }
        }
        return null;
    }

    private String resolveNamespaceByGroupName(final String groupName) {
        if (groupName == null || groupName.isBlank()) return null;
        final RulerRulesResponse all = fetchRulerRules();
        if (all == null || all.folders == null) return null;
        for (RulerRulesResponse.Folder f : all.folders) {
            if (f == null || f.groups == null) continue;
            for (RulerRulesResponse.Group g : f.groups) {
                if (g != null && groupName.equals(g.name)) {
                    return f.name;
                }
            }
        }
        return null;
    }

    private RulerRulesResponse.Group fetchRulerGroupDirect(final String namespace, final String groupName) {
        final String encodedFolder = URLEncoder.encode(namespace, StandardCharsets.UTF_8);
        final String[] urls = new String[] {
                baseUrl + "/api/ruler/grafana/api/v1/rules/" + encodedFolder + "?subtype=cortex",
                baseUrl + "/api/ruler/grafana/api/v1/rules/" + encodedFolder
        };
        for (String url : urls) {
            try {
                final HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("Accept", "application/json");
                if (bearer != null) rb.header("Authorization", "Bearer " + bearer);
                final HttpResponse<String> res = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    LOG.warn("[Ruler] GET " + url + " -> " + res.statusCode());
                    continue;
                }
                final JsonNode node = om.readTree(res.body());

                final List<RulerRulesResponse.Group> groups;
                if (node.has("groups") && node.get("groups").isArray()) {
                    groups = om.convertValue(node.get("groups"), new TypeReference<List<RulerRulesResponse.Group>>() {});
                } else if (node.isArray()) {
                    groups = om.convertValue(node, new TypeReference<List<RulerRulesResponse.Group>>() {});
                } else {
                    groups = om.convertValue(node.get("groups"), new TypeReference<List<RulerRulesResponse.Group>>() {});
                }

                if (groups != null) {
                    for (RulerRulesResponse.Group g : groups) {
                        if (g != null && groupName.equals(g.name)) {
                            return g;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[Ruler] fetch group failed for " + url + ", msg=" + e.getMessage(), e);
            }
        }
        return null;
    }

    private boolean trySend(final String url,
                            final String method,
                            final String contentType,
                            final String body) {
        try {
            final HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofMillis(readTimeoutMs))
                    .header("Accept", "application/json");

            // Authorization — 동적 토큰 사용 권장 (bearerNow()가 있으면 그걸 쓰고, 없으면 this.bearer)
            String b = null;
            try { b = bearerNow(); } catch (Throwable ignore) {}
            if (b == null || b.isBlank()) b = bearer;
            if (b != null && !b.isBlank()) {
                rb.header("Authorization", "Bearer " + b);
            }

            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
                final String ct = (contentType == null || contentType.isBlank())
                        ? "application/json"
                        : contentType;
                rb.header("Content-Type", ct);
                rb.header("X-Grafana-Org-Id", "1");
                rb.header("X-Scope-OrgID", "1");

                final String payload = (body == null) ? "" : body;
                rb.method(method.toUpperCase(java.util.Locale.ROOT),
                        HttpRequest.BodyPublishers.ofString(payload, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                rb.GET();
            }

            final HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            final int sc = res.statusCode();
            if (sc >= 200 && sc < 300) {
                // 200/202 모두 성공 처리
                LOG.info("[Ruler][send] {} {} -> {}");
                return true;
            }
            LOG.warn("[Ruler][send] {} {} -> {} (preview: {})"
            );
            return false;

        } catch (IllegalArgumentException iae) { // URI.create 등
            LOG.warn("[Ruler][send] invalid URI for {}: {} ({})");
            return false;
        } catch (Exception e) {
            LOG.warn("[Ruler][send] {} {} failed: {}");
            return false;
        }
    }


    // --------------------------- 유틸 ---------------------------

    private java.util.LinkedHashSet<String> buildNsCandidates(final String nsRaw,
                                                              final String nsKeyForUrl,
                                                              final String folderUid) {
        final java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (folderUid != null && !folderUid.isBlank()) set.add(folderUid);
        if (nsKeyForUrl != null && !nsKeyForUrl.isBlank()) set.add(nsKeyForUrl);
        if (nsRaw != null && !nsRaw.isBlank()) {
            set.add(nsRaw);
            set.add(replaceSpacesWithHyphen(nsRaw));
            set.add(slugify(nsRaw).toLowerCase(java.util.Locale.ROOT));
        }
        return set;
    }

    // RAW 그룹(ObjectNode)만 유연하게 찾아서 반환
    private ObjectNode fetchRulerGroupRawFlexible(final String nsRaw,
                                                  final String nsKeyForUrl,
                                                  final String folderUid,
                                                  final String groupName) {
        final String base = baseUrl; // baseUrlNow() 쓰는 구조면 그걸로 교체해도 됨
        final java.util.LinkedHashSet<String> nsSet = buildNsCandidates(nsRaw, nsKeyForUrl, folderUid);
        final String encGroup = encodePathSegment(groupName);

        for (String ns : nsSet) {
            if (ns == null || ns.isBlank()) continue;
            final String encNs = encodePathSegment(ns);

            // 3가지 엔드포인트 × 그룹개별/네임스페이스 전부 시도
            final String[] urls = new String[] {
                    // 그룹 개별 엔드포인트 (있을 때 바로 반환)
                    base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup + "?subtype=cortex",
                    base + "/api/ruler/grafana/api/v1/rules/" + encNs + "/" + encGroup,

                    // 네임스페이스 전체 → 그 안에서 groupName 찾기
                    base + "/api/ruler/grafana/api/v1/rules/" + encNs + "?subtype=cortex",
                    base + "/api/ruler/grafana/api/v1/rules/" + encNs,

                    // prom config 경로까지 폴백
                    base + "/api/ruler/grafana/prometheus/config/v1/rules/" + encNs
            };

            for (String url : urls) {
                try {
                    final HttpRequest.Builder rb = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(java.time.Duration.ofMillis(readTimeoutMs))
                            .header("Accept", "application/json");
                    if (bearer != null) rb.header("Authorization", "Bearer " + bearer);

                    final HttpResponse<String> res = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
                    final int sc = res.statusCode();
                    if (sc < 200 || sc >= 300) {
                        LOG.debug("[Ruler][RAW] GET " + url + " -> " + sc);
                        continue;
                    }
                    final String body = res.body();
                    final JsonNode root = om.readTree(body);

                    // (A) 루트가 '그룹 객체' 자체일 때: {"name": "...", "rules":[...]}
                    if (root instanceof ObjectNode && root.has("rules")) {
                        final ObjectNode go = (ObjectNode) root;
                        final String gname = go.path("name").asText(null);
                        if (groupName.equals(gname)) {
                            return go.deepCopy();
                        }
                    }

                    // (B) 루트가 배열: [ {name:"...", rules:[...]}, ... ]
                    if (root instanceof ArrayNode) {
                        final ArrayNode arr = (ArrayNode) root;
                        for (JsonNode gn : arr) {
                            if (gn instanceof ObjectNode) {
                                final ObjectNode go = (ObjectNode) gn;
                                if (groupName.equals(go.path("name").asText(null))) {
                                    return go.deepCopy();
                                }
                            }
                        }
                    }

                    // (C) 루트가 객체 + groups 배열 포함: {"groups":[ {...}, ... ]}
                    if (root instanceof ObjectNode && root.has("groups") && root.get("groups").isArray()) {
                        final ArrayNode groups = (ArrayNode) root.get("groups");
                        for (JsonNode gn : groups) {
                            if (gn instanceof ObjectNode) {
                                final ObjectNode go = (ObjectNode) gn;
                                if (groupName.equals(go.path("name").asText(null))) {
                                    return go.deepCopy();
                                }
                            }
                        }
                    }

                    LOG.warn("[Ruler][RAW] group not found at " + url + " (preview: " + trimBody(body, 300) + ")");
                } catch (Exception e) {
                    LOG.warn("[Ruler][RAW] fetch error at " + url + ": " + e.getMessage());
                }
            }
        }
        return null;
    }


    private static boolean groupContainsTitle(final RulerRulesResponse.Group g, final String want) {
        if (g == null || g.rules == null || want == null) return false;
        for (final RulerRulesResponse.Rule r : g.rules) {
            final String t = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
            if (want.equals(t)) return true;
        }
        return false;
    }

    private static String trimTrailingSlash(final String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean looksLikeNamespaceUid(final String s) {
        if (s == null) return false;
        final String t = s.trim();
        return t.matches("[A-Za-z0-9\\-]{6,64}");
    }

    private static String textOrNull(final JsonNode n) {
        return (n == null || n.isMissingNode() || n.isNull()) ? null : n.asText(null);
    }

    private ObjectNode fetchRulerNamespaceRaw(final String nsKey) {
        try {
            final String url = baseUrl + "/api/ruler/grafana/api/v1/rules/" + encodePathSegment(nsKey);
            final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(readTimeoutMs, 10000)))
                    .header("Authorization", "Bearer " + bearer)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() / 100 == 2) {
                final JsonNode root = om.readTree(res.body());
                if (root instanceof ObjectNode) {
                    return (ObjectNode) root;
                }
                LOG.warn("[Ruler][fetchRaw] unexpected root: " + root);
            } else {
                LOG.warn("[Ruler][fetchRaw] GET " + url + " -> " + res.statusCode() + " " + trimBody(res.body(), 600));
            }
        } catch (Exception e) {
            LOG.warn("[Ruler][fetchRaw] failed: " + e.getMessage(), e);
        }
        return null;
    }

    // RAW 네임스페이스 JSON에서 그룹 ObjectNode 찾기
// - nsRawJson 구조가 { "groups":[...] } 또는 { "data":{ "groups":[...] } } 모두 대응한다고 가정
    private ObjectNode findGroupObject(final ObjectNode nsRawJson, final String groupName) {
        if (nsRawJson == null || groupName == null || groupName.isBlank()) return null;

        ArrayNode groups = null;
        if (nsRawJson.has("groups") && nsRawJson.get("groups").isArray()) {
            groups = (ArrayNode) nsRawJson.get("groups");
        } else if (nsRawJson.has("data")
                && nsRawJson.get("data").isObject()
                && nsRawJson.get("data").has("groups")
                && nsRawJson.get("data").get("groups").isArray()) {
            groups = (ArrayNode) nsRawJson.get("data").get("groups");
        }

        if (groups == null) return null;

        for (int i = 0; i < groups.size(); i++) {
            final JsonNode g = groups.get(i);
            if (g instanceof ObjectNode) {
                final ObjectNode go = (ObjectNode) g;
                if (go.has("name") && groupName.equals(go.get("name").asText())) {
                    return go;
                }
            }
        }
        return null;
    }

    // ---- 헬퍼: {"groups":[ groupJson ]} 래핑 바디 만들기 ----
    private String buildGroupsWrappedBody(final ObjectNode groupJson) {
        final ObjectNode wrapper = om.createObjectNode();
        final ArrayNode groupsArray = om.createArrayNode();
        groupsArray.add(groupJson);
        wrapper.set("groups", groupsArray);
        return wrapper.toString();
    }

    // ---- 헬퍼: RAW 그룹 JSON에서 단일 임계치(evaluator.params)만 바꾸기 ----
    private boolean mutateThresholdRaw(final ObjectNode groupJson,
                                       final String ruleTitle,
                                       final double newThreshold) {
        final JsonNode rulesNode = groupJson.get("rules");
        if (!(rulesNode instanceof ArrayNode)) return false;
        final ArrayNode rules = (ArrayNode) rulesNode;
        boolean touched = false;

        for (int i = 0; i < rules.size(); i++) {
            final JsonNode rn = rules.get(i);
            if (!(rn instanceof ObjectNode)) continue;
            final ObjectNode r = (ObjectNode) rn;

            final String title = r.path("title").asText(null);
            final String gaTitle = r.at("/grafana_alert/title").asText(null);
            final String resolved = (title != null && !title.isBlank()) ? title : gaTitle;
            if (!ruleTitle.equals(resolved)) continue;

            // 조건 refId (없으면 "C" 가 흔함)
            String condRef = r.at("/grafana_alert/condition").asText(null);
            if (condRef == null || condRef.isBlank()) condRef = "C";

            final JsonNode dataNode = r.at("/grafana_alert/data");
            if (!(dataNode instanceof ArrayNode)) continue;
            final ArrayNode data = (ArrayNode) dataNode;

            // 1) refId == condition 인 노드 우선
            ObjectNode target = null;
            for (int j = 0; j < data.size(); j++) {
                final JsonNode dn = data.get(j);
                if (!(dn instanceof ObjectNode)) continue;
                final ObjectNode d = (ObjectNode) dn;
                if (condRef.equals(d.path("refId").asText())) { target = d; break; }
            }
            // 2) fallback: conditions[0].evaluator 가 있는 노드 선택 (reduce/classic_conditions/threshold 대응)
            if (target == null) {
                for (int j = 0; j < data.size(); j++) {
                    final JsonNode dn = data.get(j);
                    if (!(dn instanceof ObjectNode)) continue;
                    final ObjectNode d = (ObjectNode) dn;
                    final JsonNode conds = d.at("/model/conditions");
                    if (conds != null && conds.isArray() && conds.size() > 0 && conds.get(0).has("evaluator")) {
                        target = d; break;
                    }
                }
            }
            if (target == null) continue;

            final JsonNode condsNode = target.at("/model/conditions");
            if (!(condsNode instanceof ArrayNode) || condsNode.size() == 0) continue;
            final ObjectNode c0 = (ObjectNode) condsNode.get(0);

            ObjectNode evaluator = (ObjectNode) c0.get("evaluator");
            if (evaluator == null) {
                evaluator = groupJson.objectNode();
                c0.set("evaluator", evaluator);
            }
            final String evalType = evaluator.path("type").asText("gt"); // gt/gte/lt/lte/within_range…

            ArrayNode params = (ArrayNode) evaluator.get("params");
            if (params == null) {
                params = groupJson.arrayNode();
                evaluator.set("params", params);
            } else {
                params.removeAll();
            }

            // 단일 임계 타입 우선 지원 (gt/gte/lt/lte). within_range 등은 필요 시 확장.
            params.add(om.getNodeFactory().numberNode(newThreshold));

            touched = true;
        }
        return touched;
    }

    // grafana_alert.isPaused 토글 (그룹 JSON in-place 수정)
    /**
     * Ruler RAW group JSON 안에서 ruleUid에 해당하는 룰의 pause 값을 in-place로 토글합니다.
     * 이 환경(YAML)에 맞춰서 isPaused/paused/grafana_alert.isPaused 모두 동기화합니다.
     */
    private boolean mutatePauseRawInPlace(final ObjectNode groupJson, final String targetUid, final boolean paused) {
        if (groupJson == null || targetUid == null || targetUid.isBlank()) return false;
        final JsonNode rules = groupJson.path("rules");
        if (!rules.isArray()) return false;

        boolean touched = false;
        for (JsonNode rn : rules) {
            if (!(rn instanceof ObjectNode)) continue;
            final ObjectNode ro = (ObjectNode) rn;

            final String uid = ro.path("uid").asText(null);
            final String gaUid = ro.at("/grafana_alert/uid").asText(null);
            if (!targetUid.equals(uid) && !targetUid.equals(gaUid)) {
                continue;
            }

            // 1) 모든 변형 키 동시 세팅 (호환성 최대화)
            ro.put("is_paused", paused);
            ro.put("isPaused",  paused);
            ro.put("paused",    paused);

            final JsonNode gaNode = ro.get("grafana_alert");
            if (gaNode instanceof ObjectNode) {
                final ObjectNode gao = (ObjectNode) gaNode;
                gao.put("is_paused", paused);
                gao.put("isPaused",  paused);
            }

            // 2) UID 백필(이미 있는 로직과 동일)
            final String ru = ro.path("uid").asText(null);
            final String ga = ro.at("/grafana_alert/uid").asText(null);
            if ((ru == null || ru.isBlank()) && ga != null && !ga.isBlank()) {
                ro.put("uid", ga);
            }

            touched = true;
            break;
        }
        return touched;
    }

}
