package org.apache.cloudstack.wallAlerts.service;

import com.cloud.utils.component.ManagerBase;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.wall.alerts.ExpireWallAlertSilenceCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.ListWallAlertRulesCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.ListWallAlertSilencesCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.PauseWallAlertRuleCmd;
import org.apache.cloudstack.api.command.admin.wall.alerts.UpdateWallAlertRuleThresholdCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.WallAlertRuleResponse;
import org.apache.cloudstack.api.response.WallAlertRuleResponse.AlertInstanceResponse;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.wallAlerts.client.WallApiClient;
import org.apache.cloudstack.wallAlerts.client.WallApiClient.GrafanaRulesResponse;
import org.apache.cloudstack.wallAlerts.client.WallApiClient.RulerRulesResponse;
import org.apache.cloudstack.wallAlerts.config.WallConfigKeys;
import org.apache.cloudstack.wallAlerts.model.SilenceDto;
import org.apache.cloudstack.wallAlerts.model.SilenceMatcherDto;
import org.apache.cloudstack.wallAlerts.mapper.WallMappers;
import org.apache.log4j.Logger;

import javax.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rules API(상태/인스턴스) + Ruler API(임계치/연산자/expr/for)를 병합합니다.
 * 조인 순서: 1) dashUid:panelId → 2) group+title → 3) title → 4) group+kind → 5) expr 입니다.
 * 와일드카드 임포트를 사용하지 않습니다.
 */
public class WallAlertsServiceImpl extends ManagerBase implements WallAlertsService {

    private static final Logger LOG = Logger.getLogger(WallAlertsServiceImpl.class);

    @Inject
    private WallApiClient wallApiClient;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter KST_YMD_HM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public boolean start() { return true; }

    @Override
    public boolean stop() { return true; }

    @Override
    public String getName() { return "WallAlertsService"; }

    private static final Object WALL_RULES_CACHE_LOCK = new Object();
    private static final long WALL_RULES_CACHE_TTL_MS = 15_000L; // 15초. 필요하면 조정

    private static volatile String WALL_RULES_CACHE_KEY;
    private static volatile long WALL_RULES_CACHE_EXPIRES_AT;
    private static volatile java.util.List<WallAlertRuleResponse> WALL_RULES_CACHE_LIST;

    @Override
    public ListResponse<WallAlertRuleResponse> listWallAlertRules(final ListWallAlertRulesCmd cmd) {
        final String cacheKey = buildRulesCacheKey(cmd);
        final long now = System.currentTimeMillis();

        // 1) 캐시 확인 (같은 필터면 TTL 동안 wall API 재호출 안 함)
        java.util.List<WallAlertRuleResponse> base;
        synchronized (WALL_RULES_CACHE_LOCK) {
            if (WALL_RULES_CACHE_LIST != null
                    && cacheKey.equals(WALL_RULES_CACHE_KEY)
                    && now < WALL_RULES_CACHE_EXPIRES_AT) {
                base = WALL_RULES_CACHE_LIST;
            } else {
                // ===== 새로 빌드(정렬 없음, 네가 준 코드 그대로) =====
                final GrafanaRulesResponse rulesNow = wallApiClient.fetchRules();
                final ThresholdIndex tIndex = buildThresholdIndexSafe();

                final String idFilter    = cmd.getId();
                final String nameFilter  = cmd.getName();
                final String stateFilter = cmd.getState();
                final String kindFilter  = cmd.getKind();
                final String keyword     = cmd.getKeyword();

                final String nameFilterL  = nameFilter  == null ? null : nameFilter.toLowerCase(Locale.ROOT);
                final String stateFilterL = stateFilter == null ? null : stateFilter.toLowerCase(Locale.ROOT);
                final String kindFilterL  = kindFilter  == null ? null : kindFilter.toLowerCase(Locale.ROOT);
                final String keywordL     = keyword     == null ? null : keyword.toLowerCase(Locale.ROOT);

                final java.util.List<WallAlertRuleResponse> filtered = new java.util.ArrayList<>();

                if (rulesNow != null && rulesNow.data != null && rulesNow.data.groups != null) {
                    for (GrafanaRulesResponse.Group g : rulesNow.data.groups) {
                        if (g.rules == null) {
                            continue;
                        }

                        for (GrafanaRulesResponse.Rule r : g.rules) {
                            // 기본 키/메타
                            final String dashUid = r.annotations == null ? null : r.annotations.get("__dashboardUid__");
                            final String panelId = r.annotations == null ? null : r.annotations.get("__panelId__");
                            final String key = (dashUid != null && panelId != null)
                                    ? dashUid + ":" + panelId
                                    : (g.name + ":" + (r.name == null ? "rule" : r.name));

                            final String groupName = g.name;
                            final String ruleTitle = r.name;
                            final String ruleKind  = r.labels != null ? r.labels.get("kind") : null;
                            final String ruleExprN = normExpr(r.query);

                            // ----- 임계치/연산자 병합을 위한 정의 찾기 -----
                            ThresholdDef def = null;

                            // 1) dashUid:panelId
                            if (dashUid != null && panelId != null) {
                                def = tIndex.byKey.get(dashUid + ":" + panelId);
                                if (def == null) {
                                    LOG.debug("[Ruler][miss#1] key=" + dashUid + ":" + panelId);
                                }
                            }
                            // 2) group + title
                            if (def == null && groupName != null && ruleTitle != null) {
                                def = getFromNested(tIndex.byGroupTitle, groupName, ruleTitle);
                                if (def == null) {
                                    LOG.debug("[Ruler][miss#2] group=" + groupName + ", title=" + ruleTitle);
                                }
                            }
                            // 3) title
                            if (def == null && ruleTitle != null) {
                                def = tIndex.byTitle.get(ruleTitle);
                                if (def == null) {
                                    LOG.debug("[Ruler][miss#3] title=" + ruleTitle);
                                }
                            }
                            // 4) group + kind
                            if (def == null && groupName != null && ruleKind != null) {
                                def = getFromNested(tIndex.byGroupKind, groupName, ruleKind);
                                if (def == null) {
                                    LOG.debug("[Ruler][miss#4] group=" + groupName + ", kind=" + ruleKind);
                                }
                            }
                            // 5) expr (PromQL)
                            if (def == null && ruleExprN != null) {
                                def = tIndex.byExpr.get(ruleExprN);
                                if (def == null) {
                                    LOG.debug("[Ruler][miss#5] expr=" + ruleExprN);
                                }
                            }

                            // 집계(상태/인스턴스)
                            final Agg agg = aggregate(r);
                            final String computedState =
                                    agg.firing > 0 ? "ALERTING"
                                            : (agg.pending > 0 ? "PENDING"
                                            : ("nodata".equalsIgnoreCase(r.health) ? "NODATA" : "OK"));

                            final java.util.Set<String> stateWanted = parseStateFilter(cmd.getState());
                            if (!stateWanted.isEmpty()) {
                                final String cs = normalizeState(computedState);
                                if (!stateWanted.contains(cs)) {
                                    continue;
                                }
                            }

                            // ----- ▼ 필터 판정(응답 생성 전) ▼ -----
                            if (idFilter != null && !idFilter.isBlank()) {
                                if (key == null || !key.equals(idFilter)) {
                                    continue;
                                }
                            }
                            if (nameFilterL != null && !nameFilterL.isBlank()) {
                                final String nm = ruleTitle == null ? "" : ruleTitle.toLowerCase(Locale.ROOT);
                                if (!nm.equals(nameFilterL)) {
                                    continue;
                                }
                            }
                            if (stateFilterL != null && !stateFilterL.isBlank()) {
                                final String st = computedState.toLowerCase(Locale.ROOT);
                                if (!st.equals(stateFilterL)) {
                                    continue;
                                }
                            }
                            if (kindFilterL != null && !kindFilterL.isBlank()) {
                                final String kd = ruleKind == null ? "" : ruleKind.toLowerCase(Locale.ROOT);
                                if (!kd.equals(kindFilterL)) {
                                    continue;
                                }
                            }
                            if (keywordL != null && !keywordL.isBlank()) {
                                final String nm  = ruleTitle  == null ? "" : ruleTitle.toLowerCase(Locale.ROOT);
                                final String grp = groupName  == null ? "" : groupName.toLowerCase(Locale.ROOT);
                                final String q   = r.query    == null ? "" : r.query.toLowerCase(Locale.ROOT);
                                final String op  = (def != null && def.operator  != null) ? def.operator.toLowerCase(Locale.ROOT) : "";
                                final String th  = (def != null && def.threshold != null) ? String.valueOf(def.threshold).toLowerCase(Locale.ROOT) : "";
                                final String th2 = (def != null && def.threshold2 != null) ? String.valueOf(def.threshold2).toLowerCase(Locale.ROOT) : "";
                                if (!(nm.contains(keywordL) || grp.contains(keywordL) || q.contains(keywordL)
                                        || op.contains(keywordL) || th.contains(keywordL) || th2.contains(keywordL))) {
                                    continue;
                                }
                            }
                            // ----- ▲ 필터 판정 끝 ▲ -----

                            // 응답 객체 구성 (정렬 안 함, 받은 순서대로 추가)
                            final WallAlertRuleResponse resp = new WallAlertRuleResponse();

                            final String safeKey   = sanitizeXmlText(key);
                            final String safeName  = sanitizeXmlText(ruleTitle);
                            final String safeGroup = sanitizeXmlText(groupName);

                            resp.setId(safeKey);
                            resp.setName(safeName);
                            resp.setRuleGroup(safeGroup);

                            String forText = r.duration != null ? (r.duration + "s") : null;
                            if (def != null && def.forText != null && !def.forText.isBlank()) {
                                forText = def.forText;
                            }
                            resp.setDurationFor(sanitizeXmlText(forText));

                            resp.setHealth(sanitizeXmlText(r.health));
                            resp.setType(sanitizeXmlText(r.type));
                            String q = (r.query == null || r.query.isBlank()) && def != null && def.expr != null ? def.expr : r.query;
                            resp.setQuery(sanitizeXmlText(q));

                            if (r.lastEvaluation != null) {
                                final String lastEvalKst = KST_YMD_HM.format(r.lastEvaluation.atZoneSameInstant(KST));
                                resp.setLastEvaluation(sanitizeXmlText(lastEvalKst));
                            }

                            resp.setEvaluationTime(r.evaluationTime);
                            if (dashUid != null && panelId != null) {
                                resp.setDashboardUrl(sanitizeXmlText("/d/" + dashUid + "?viewPanel=" + panelId));
                            }
                            resp.setPanel(sanitizeXmlText(panelId));

                            resp.setLabels(sanitizeXmlMap(r.labels));
                            resp.setAnnotations(sanitizeXmlMap(r.annotations));
                            resp.setKind(sanitizeXmlText(ruleKind));
                            // pause 상태 반영: Ruler 인덱스(def)에서 가져와 내려줍니다
                            final boolean paused = (def != null && def.paused != null) ? def.paused : false;
                            resp.setIspaused(paused ? "Stopped" : "Running");
                            resp.setIsPaused(Boolean.valueOf(paused));

                            if (def != null) {
                                if (def.operator != null) {
                                    resp.setOperator(sanitizeXmlText(def.operator));
                                }
                                if (def.threshold != null) {
                                    resp.setThreshold(def.threshold);
                                }
                                if (def.threshold2 != null) {
                                    resp.setThreshold2(def.threshold2);
                                }
                            } else {
                                LOG.warn("[Ruler][no-match] group=" + groupName
                                        + ", name=" + ruleTitle
                                        + ", key=" + key
                                        + ", kind=" + ruleKind
                                        + ", exprN=" + ruleExprN);
                            }

                            final java.util.List<AlertInstanceResponse> instList = new java.util.ArrayList<>();
                            if (r.alerts != null) {
                                for (GrafanaRulesResponse.AlertInst a : r.alerts) {
                                    final AlertInstanceResponse ir = new AlertInstanceResponse();
                                    ir.labels = sanitizeXmlMap(a.labels);
                                    ir.annotations = sanitizeXmlMap(a.annotations);
                                    ir.state = sanitizeXmlText(a.state);
                                    ir.value = sanitizeXmlText(a.value);
                                    ir.activeAt = a.activeAt != null ? sanitizeXmlText(a.activeAt.toString()) : null;
                                    instList.add(ir);
                                }
                            }
                            resp.setInstances(instList);

                            resp.setFiringCount(agg.firing);
                            resp.setPendingCount(agg.pending);
                            resp.setState(sanitizeXmlText(computedState));
                            if (agg.lastActiveAt != null) {
                                resp.setLastTriggeredAt(KST_YMD_HM.format(agg.lastActiveAt.atZoneSameInstant(KST)));
                            } else if (r.lastEvaluation != null) {
                                resp.setLastTriggeredAt(KST_YMD_HM.format(r.lastEvaluation.atZoneSameInstant(KST)));
                            }

                            filtered.add(resp);
                        }
                    }
                }

                // 캐시에 저장(정렬 없이, 받은 순서 그대로)
                base = java.util.Collections.unmodifiableList(filtered);
                WALL_RULES_CACHE_LIST = base;
                WALL_RULES_CACHE_KEY = cacheKey;
                WALL_RULES_CACHE_EXPIRES_AT = now + WALL_RULES_CACHE_TTL_MS;
            }
        }

        // 2) 페이징(받은 순서 그대로 슬라이스)
        final int startIndex = (cmd.getStartIndex() == null) ? 0 : Math.max(0, cmd.getStartIndex().intValue());
        final Long psv = cmd.getPageSizeVal();
        final int pageSize = (psv == null || psv <= 0L) ? Integer.MAX_VALUE : psv.intValue();

        final int total = base.size();
        final int from = Math.min(startIndex, total);
        final int to   = Math.min(from + pageSize, total);
        final java.util.List<WallAlertRuleResponse> page = new java.util.ArrayList<>(base.subList(from, to));

        final ListResponse<WallAlertRuleResponse> out = new ListResponse<>();
        out.setResponses(page, total);
        return out;
    }


    @Override
    public WallAlertRuleResponse updateWallAlertRuleThreshold(final UpdateWallAlertRuleThresholdCmd cmd)
            throws ServerApiException {
        final String id = cmd.getId();
        final Double newThreshold = cmd.getThreshold();
        final Double threshold2 = cmd.getThreshold2();
        final String operator = cmd.getOperator();

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id는 'group:title' 또는 'dashboardUid:panelId' 형식이어야 합니다.");
        }
        if (newThreshold == null) {
            throw new IllegalArgumentException("threshold는 필수입니다.");
        }

        final int sep = id.indexOf(':');
        if (sep <= 0 || sep >= id.length() - 1) {
            throw new IllegalArgumentException("id 형식이 올바르지 않습니다. 예) group:title 또는 dashboardUid:panelId");
        }
        final String left = id.substring(0, sep).trim();
        final String right = id.substring(sep + 1).trim();

        // 1) Ruler 전체 로드
        final RulerRulesResponse rulerAll = wallApiClient.fetchRulerRules();
        if (rulerAll == null || rulerAll.folders == null) {
            throw new RuntimeException("Ruler rules를 불러오지 못했습니다.");
        }

        // 2) 'group:title' 직접 매칭
        Mapping m = mapGroupTitle(rulerAll, left, right);

        // 3) 못 찾으면 'dashboardUid:panelId' 역매핑 (Ruler → 실패 시 Rules → 다시 Ruler)
        if (m == null && looksLikeDashboardUid(left) && looksLikePanelId(right)) {
            m = mapDashPanelFromRuler(rulerAll, left, right);
            if (m == null) {
                final GrafanaRulesResponse rulesAll = wallApiClient.fetchRules();
                final DashPanel dp = mapDashPanelFromRules(rulesAll, left, right);
                if (dp != null) {
                    m = mapGroupTitle(rulerAll, dp.group, dp.title);
                }
            }
        }

        if (m == null) {
            throw new IllegalArgumentException(
                    "해당 id('" + id + "')로 룰을 찾지 못했습니다. group:title이 정확한지 또는 dashboardUid/panelId가 실제 룰 주석과 일치하는지 확인해 주세요."
            );
        }

        // 4) 임계치 업데이트 실행 (operator/threshold2 전달)
        final String opInput = (operator == null || operator.isBlank()) ? "gt" : operator.trim();
        final String op = normalizeOperator(opInput);
        boolean ok;
        if ("between".equals(op) || "outside".equals(op)) {
            if (threshold2 == null) {
                throw new IllegalArgumentException("operator가 '" + op + "'일 때 threshold2는 필수입니다.");
            }
            ok = wallApiClient.updateRuleThreshold(m.namespace, m.group, m.title, op, newThreshold, threshold2);
        } else {
            ok = wallApiClient.updateRuleThreshold(m.namespace, m.group, m.title, op, newThreshold, null);
        }

        // 5) 응답
        final WallAlertRuleResponse resp = new WallAlertRuleResponse();
        resp.setObjectName("wallalertrule");
        resp.setId(id);
        resp.setRuleGroup(m.group);
        resp.setName(m.title);
        resp.setOperator(operator == null ? null : operator.trim());
        if (threshold2 != null) {
            resp.setThreshold2(threshold2);
        }
        resp.setThreshold(newThreshold);
        return resp;
    }


    @Override
    public boolean pauseWallAlertRule(final String namespaceHint,
                                      final String groupName,
                                      final String ruleUid,
                                      final boolean paused) {
        // WallApiClientImpl 에 이미 구현한 pauseRule(...) 호출
        return wallApiClient.pauseRule(namespaceHint, groupName, ruleUid, paused);
    }

    @Override
    public boolean pauseWallAlertRuleById(final String id, final boolean paused) {
        if (id == null || id.isBlank()) return false;
        LOG.warn("[Pause][Svc.in] rawId=" + id + ", paused=" + paused);

        // 1) 인덱스(byKey) 빠른 경로
        final ThresholdIndex tIndex = buildThresholdIndexSafe();
        final ThresholdDef defQuick = (tIndex != null) ? tIndex.byKey.get(id) : null;
        if (defQuick != null && defQuick.ruleUid != null) {
            LOG.warn("[Pause][fast] uid=" + defQuick.ruleUid + ", ns=" + defQuick.folder + ", group=" + defQuick.group);
            // 가장 확실한 경로: UID 직행
            final boolean okDirect = wallApiClient.pauseByUid(defQuick.ruleUid, paused);
            if (okDirect) return true;
            // 실패 시에만 기존 Ruler 경로로 폴백
            return pauseWallAlertRule(defQuick.folder, defQuick.group, defQuick.ruleUid, paused);
        }

        // 2) 전체 스캔 매핑
        final Mapping m = resolveMappingFromId(id);
        if (m == null || isBlank(m.group) || isBlank(m.namespace)) {
            LOG.warn("[Pause][byId] mapping not found or incomplete for id=" + id);
            return false;
        }
        // ruleUid 우선(없으면 제목으로 보강)
        String uid = m.ruleUid;
        if (isBlank(uid)) {
            uid = resolveUidByTitle(m.namespace, m.group, m.title);
            if (isBlank(uid)) {
                LOG.warn("[Pause][byId] cannot resolve UID (id=" + id
                        + ", ns=" + m.namespace + ", group=" + m.group + ", title=" + m.title + ")");
                return false;
            }
        }

        // 먼저 UID 직행
        final boolean okDirect = wallApiClient.pauseByUid(uid, paused);
        if (okDirect) return true;

        // 실패 시 Ruler 폴백(환경/버전에 따라 PUT/POST 404/400/403 나오는 경우가 있어서 보조 루트로만 사용)
        final boolean ok = pauseWallAlertRule(m.namespace, m.group, uid, paused);
        LOG.warn("[Pause][byId] ns=" + m.namespace + ", group=" + m.group + ", uid=" + uid
                + ", paused=" + paused + ", ok=" + ok);
        return ok;
    }

    @Override
    public boolean pauseWallAlertRuleByUid(final String ruleUid, final boolean paused) {
        if (ruleUid == null || ruleUid.isBlank()) return false;
        // Client의 직행 메서드로 바로 처리
        return wallApiClient.pauseByUid(ruleUid, paused);
    }

    /** UI/호출 파라미터의 operator 문자열을 내부 표준으로 정규화합니다.
     *  - within_range -> between, outside_range -> outside
     *  - 기호(>, <, >=, <=)는 각각 gt, lt, gte, lte로 변환합니다.
     */
    private static String normalizeOperator(final String op) {
        if (op == null) return "gt";
        final String t = op.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) return "gt";
        switch (t) {
            case "within_range": return "between";
            case "outside_range": return "outside";
            case ">": return "gt";
            case "<": return "lt";
            case ">=": return "gte";
            case "<=": return "lte";
            default: return t;
        }
    }

    @Override
    public ListResponse<WallSilenceResponse> listWallAlertSilences(final ListWallAlertSilencesCmd cmd) {
        final Map<String, String> rawLabels = cmd.getLabels();
        final Map<String, String> labels = normalizeLabels(rawLabels);
        final String stateFilter = cmd.getState();

        // 1) 서버에서 목록 가져오기 (상태 필터 적용 시도)
        List<SilenceDto> all = wallApiClient.listSilences(stateFilter);

        //폴백: 필터 호출이 빈 배열을 돌려줄 때, 무필터로 전체 받아서 나중에 상태/라벨로
        if ((all == null || all.isEmpty()) && stateFilter != null && !stateFilter.isBlank()) {
            all = wallApiClient.listSilences(null);
            if (all == null) all = java.util.Collections.emptyList();
        }

        // 2) 라벨 매칭 (기존 매칭 함수 그대로 사용)
        final java.util.List<SilenceDto> matched = new java.util.ArrayList<>();
        for (final SilenceDto s : all) {
            if (matchesAlertLabels(s, labels)) {
                matched.add(s);
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("[Wall][Silence][skip] id={} not matched. uid(silence)={}, labels.uid={}"
                );
            }
        }

        // (선택) 메타/권한 풍부화: matched 상위 N개만 getSilence(id, true, true)로 상세 채우기 (이미 구현했다면 그대로)

        // 3) 매핑 + 응답
        final java.util.List<WallSilenceResponse> out = new java.util.ArrayList<>(matched.size());
        for (final SilenceDto s : matched) {
            out.add(WallMappers.toResponse(s));
        }
        final ListResponse<WallSilenceResponse> resp = new ListResponse<>();
        resp.setResponses(out, out.size());
        return resp;
    }

    @Override
    public SuccessResponse expireWallAlertSilence(final ExpireWallAlertSilenceCmd cmd) {
        final String id = cmd.getId();
        if (id == null || id.isEmpty()) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "id is required");
        }
        wallApiClient.expireSilence(id);
        return new SuccessResponse(cmd.getCommandName());
    }

    // ---------------- Helper: Silence matcher 평가 ----------------

    private static String preview(final String s) {
        if (s == null) return "null";
        final String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 180 ? t.substring(0, 180) + "..." : t;
    }

    /**
     * CloudStack MAP 파라미터 정규화:
     *  labels[0].key=a, labels[0].value=b ... 로 들어오면
     *  서버에서 { "0": {"key":"a","value":"b"}, ... } 로 잡히는 경우가 있음.
     *  이걸 {"a":"b", ...} 로 평탄화한다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> normalizeLabels(final Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return java.util.Collections.emptyMap();

        // 이미 평탄화된 케이스 감지: 어느 값이든 내부 map이 아니고 문자열이면 그대로 사용
        boolean needsFlatten = false;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            final Object v = e.getValue();
            if (v instanceof Map) { needsFlatten = true; break; }
        }
        if (!needsFlatten) return raw;

        final Map<String, String> flat = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            final Object v = e.getValue();
            if (v instanceof Map) {
                final Map<Object, Object> inner = (Map<Object, Object>) v;
                final Object k0 = inner.get("key");
                final Object v0 = inner.get("value");
                if (k0 != null && v0 != null) {
                    flat.put(String.valueOf(k0), String.valueOf(v0));
                }
            } else if (v != null) {
                flat.put(String.valueOf(e.getKey()), String.valueOf(v));
            }
        }
        return flat;
    }

    // --- 라벨에서 키를 '대소문자 무시'로 가져오기 ---
    private String getLabelCI(final Map<String, String> labels, final String name) {
        if (labels == null || labels.isEmpty() || name == null) return null;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            final String k = e.getKey();
            if (k != null && k.equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    // --- 여러 동의어 중 첫 번째로 존재하는 라벨 값 찾기(대소문자 무시) ---
    private String getAny(final Map<String, String> labels, final String... keys) {
        if (labels == null || keys == null) return null;
        for (final String k : keys) {
            final String v = getLabelCI(labels, k);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // --- 입력 라벨에서 Rule UID 추출(동의어+대소문자 무시) ---
    private String extractRuleUidFromLabels(final Map<String, String> labels) {
        return getAny(labels,
                "__alert_rule_uid__", "rule_uid", "ruleUid", "__rule_uid__",
                "grafana_rule_uid", "grafanaRuleUid", "__alert_rule_uid");
    }

    // --- 입력 라벨에서 Rule Title(알럿 이름) 후보 추출 ---
    private String extractRuleTitleFromLabels(final Map<String, String> labels) {
        return getAny(labels,
                "alertname", "alert_name", "rule_title", "ruleTitle",
                "rulename", "title", "name");
    }

    // --- 사일런스 매처들에서 UID 값 추출 ---
    private String silenceRuleUid(final SilenceDto s) {
        if (s == null || s.getMatchers() == null) return null;
        for (final SilenceMatcherDto m : s.getMatchers()) {
            if (m != null && "__alert_rule_uid__".equals(m.getName())) {
                return m.getValue();
            }
        }
        return null;
    }

    /**
     * Alertmanager 규칙: 사일런스의 '모든' 매처가 알럿 라벨을 만족해야 매칭.
     * 보강:
     *  - 빠른 경로: UID 직접 비교
     *  - 폴백: UID가 라벨에 없으면 라벨의 alertname(~rule_title 계열)과 사일런스 metadata.rule_title 비교
     */
    private boolean matchesAlertLabels(final SilenceDto s, final Map<String, String> labels) {
        if (s == null || labels == null || labels.isEmpty()) return false;

        // 1) 빠른 경로 — UID 직접 비교
        final String silenceUid = silenceRuleUid(s);
        final String labelUid = extractRuleUidFromLabels(labels);
        if (silenceUid != null && labelUid != null && silenceUid.equalsIgnoreCase(labelUid)) {
            return true;
        }

        // 2) 폴백 — Rule Title 비교
        if (labelUid == null) {
            final String labelTitle = extractRuleTitleFromLabels(labels); // 예: alertname
            if (labelTitle != null && !labelTitle.isEmpty()) {
                String ruleTitle = null;
                if (s.getMetadata() != null && s.getMetadata().getRuleTitle() != null) {
                    ruleTitle = s.getMetadata().getRuleTitle();
                } else {
                    // metadata가 없으면 단건 조회로 보강 (ruleMetadata만 켬)
                    try {
                        final SilenceDto one = wallApiClient.getSilence(s.getId(), true, false);
                        if (one != null && one.getMetadata() != null) {
                            ruleTitle = one.getMetadata().getRuleTitle();
                            s.setMetadata(one.getMetadata()); // 캐시
                        }
                    } catch (Exception ignore) { }
                }
                if (ruleTitle != null && ruleTitle.equalsIgnoreCase(labelTitle)) {
                    return true;
                }
            }
        }

        // 3) 일반 경로 — 모든 매처 평가(라벨 키 대소문자 무시, 정규식은 find로 유연하게)
        final java.util.List<SilenceMatcherDto> ms = s.getMatchers();
        if (ms == null || ms.isEmpty()) return false;

        for (final SilenceMatcherDto m : ms) {
            if (m == null) return false;

            final String key = m.getName();
            final String right = m.getValue();
            if (key == null || key.isEmpty() || right == null) return false;

            final String left = getLabelCI(labels, key); // 라벨에서 '대소문자 무시'로 키 찾기
            if (left == null) {
                return false; // 해당 라벨 자체가 없으면 불일치
            }

            final boolean isRegex = Boolean.TRUE.equals(m.getIsRegex()); // null → false
            final boolean isEqual = (m.getIsEqual() == null) || Boolean.TRUE.equals(m.getIsEqual()); // null → true

            boolean hit;
            if (isRegex) {
                try {
                    final Pattern p = Pattern.compile(right);
                    hit = p.matcher(left).find();  // Prometheus 느낌에 가깝게 find() 사용
                } catch (PatternSyntaxException e) {
                    hit = false;
                }
            } else {
                hit = left.equals(right);
            }

            final boolean ok = isEqual ? hit : !hit;
            if (!ok) return false;  // 하나라도 실패하면 전체 불일치
        }
        return true;
    }

    /** id="dashboardUid:panelId" → (namespace/group/title/ruleUid) 해석 */
    private Mapping resolveMappingFromId(final String id) {
        final int sep = (id == null ? -1 : id.indexOf(':'));
        if (sep < 0) return null;

        final String dashboardUid = id.substring(0, sep);
        final String panelId      = id.substring(sep + 1);

        try {
            final RulerRulesResponse all = wallApiClient.fetchRulerRules();
            if (all == null || all.folders == null) return null;

            for (final var f : all.folders) {
                if (f == null || f.groups == null) continue;
                final String folderName = f.name;
                // 모델에 폴더 UID가 없으므로 null로 둡니다.
                final String folderUid  = null;

                for (final var g : f.groups) {
                    if (g == null || g.rules == null) continue;

                    for (final var r : g.rules) {
                        if (r == null) continue;

                        final Map<String, String> ann = r.annotations;
                        final String aDash  = ann == null ? null : ann.get("__dashboardUid__");
                        final String aPanel = ann == null ? null : ann.get("__panelId__");
                        if (!dashboardUid.equals(aDash) || !panelId.equals(aPanel)) {
                            continue;
                        }

                        final String title = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
                        final String uid   = (r.uid != null && !r.uid.isBlank())
                                ? r.uid
                                : (r.alert != null && r.alert.uid != null && !r.alert.uid.isBlank() ? r.alert.uid : null);

                        // 생성자 순서는 ns, g, t 고정. uid/nsUid는 뒤에 추가 인자.
                        return new Mapping(folderName, g.name, title, uid, folderUid);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[pause][resolveMappingFromId] failed: " + e.getMessage(), e);
        }
        return null;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 제목→UID 보강 (필요할 때만) */
    private String resolveUidByTitle(final String namespaceHint, final String groupName, final String ruleTitle) {
        try {
            final WallApiClient.RulerRulesResponse all = wallApiClient.fetchRulerRules();
            if (all == null || all.folders == null) return null;

            for (final var f : all.folders) {
                if (f == null || f.groups == null) continue;
                if (!nsEquals(f.name, namespaceHint)) continue;

                for (final var g : f.groups) {
                    if (g == null || g.rules == null) continue;
                    if (!groupName.equals(g.name)) continue;

                    for (final var r : g.rules) {
                        if (r == null) continue;
                        final String t = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
                        if (ruleTitle.equals(t)) {
                            final String uid = (r.uid != null && !r.uid.isBlank())
                                    ? r.uid
                                    : (r.alert != null ? r.alert.uid : null);
                            if (!isBlank(uid)) return uid;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[pause][resolveUidByTitle] failed: " + e.getMessage(), e);
        }
        return null;
    }

    private boolean nsEquals(String actual, String hint) {
        if (actual == null || hint == null) return false;
        if (actual.equals(hint)) return true;
        return slug(actual).equals(slug(hint));
    }
    private String slug(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }


    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmds = new ArrayList<>();
        cmds.add(ListWallAlertRulesCmd.class);
        cmds.add(UpdateWallAlertRuleThresholdCmd.class);
        cmds.add(PauseWallAlertRuleCmd.class);
        cmds.add(ListWallAlertSilencesCmd.class);
        cmds.add(ExpireWallAlertSilenceCmd.class);
        return cmds;
    }

    @Override
    public String getConfigComponentName() { return WallAlertsService.class.getSimpleName(); }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                WallConfigKeys.WALL_ALERT_ENABLED,
                WallConfigKeys.WALL_BASE_URL,
                WallConfigKeys.WALL_API_TOKEN,
                WallConfigKeys.CONNECT_TIMEOUT_MS,
                WallConfigKeys.READ_TIMEOUT_MS
        };
    }

    // ---------------- 내부 유틸 ----------------

    // 필터 기준 캐시 키(페이지 파라미터 제외)
    private String buildRulesCacheKey(final ListWallAlertRulesCmd cmd) {
        final String id    = cmd.getId();
        final String name  = cmd.getName();
        final String state = cmd.getState();
        final String kind  = cmd.getKind();
        final String kw    = cmd.getKeyword();

        long callerId = -1L;
        final org.apache.cloudstack.context.CallContext ctx = org.apache.cloudstack.context.CallContext.current();
        if (ctx != null && ctx.getCallingAccount() != null) {
            callerId = ctx.getCallingAccount().getId();
        }

        return "acct=" + callerId
                + "|id="    + (id    == null ? "" : id)
                + "|name="  + (name  == null ? "" : name.toLowerCase(java.util.Locale.ROOT))
                + "|state=" + (state == null ? "" : state.toLowerCase(java.util.Locale.ROOT))
                + "|kind="  + (kind  == null ? "" : kind.toLowerCase(java.util.Locale.ROOT))
                + "|kw="    + (kw    == null ? "" : kw.toLowerCase(java.util.Locale.ROOT));
    }

    private static class Agg {
        int firing;
        int pending;
        OffsetDateTime lastActiveAt;
    }

    /** Ruler 인덱스 묶음(여러 보조 인덱스 포함) */
    private static class ThresholdIndex {
        Map<String, ThresholdDef> byKey = new HashMap<>();                      // dashUid:panelId
        Map<String, ThresholdDef> byTitle = new HashMap<>();                    // title
        Map<String, Map<String, ThresholdDef>> byGroupTitle = new HashMap<>();  // group -> title -> def
        Map<String, Map<String, ThresholdDef>> byGroupKind = new HashMap<>();   // group -> kind  -> def
        Map<String, ThresholdDef> byExpr = new HashMap<>();                     // expr(normalized) -> def
    }

    private static class ThresholdDef {
        String operator;   // gt/gte/lt/lte/within_range/outside_range ...
        Double threshold;  // 하한(단일형도 여기에 저장)
        Double threshold2; // 상한(범위형일 때만 사용)
        String forText;    // "5m"
        String expr;       // prom expr
        String title;      // grafana_alert.title
        String group;      // Ruler group name
        String kind;       // labels.kind
        String dashUid;    // annotations.__dashboardUid__
        String panelId;    // annotations.__panelId__
        String folder;
        Boolean paused;
        String ruleUid;
    }

    /** 인덱스 안전 생성(실패 시 빈 인덱스 + 경고 로그) */
    private ThresholdIndex buildThresholdIndexSafe() {
        final ThresholdIndex out = new ThresholdIndex();
        try {
            final RulerRulesResponse rr = wallApiClient.fetchRulerRules();
            if (rr == null || rr.folders == null) {
                LOG.warn("[Ruler] no folders in response");
                return out;
            }
            int rulesCount = 0;
            for (RulerRulesResponse.Folder f : rr.folders) {
                if (f.groups == null) continue;
                for (RulerRulesResponse.Group g : f.groups) {
                    if (g.rules == null) continue;
                    for (RulerRulesResponse.Rule r : g.rules) {
                        final ThresholdDef def = new ThresholdDef();
                        def.operator = extractOperator(r);
                        def.threshold = extractThreshold(r);
                        def.threshold2 = extractThreshold2(r);
                        def.forText = r.forText;
                        def.expr = extractExpr(r);
                        def.title = (r.alert != null ? r.alert.title : r.title);
                        def.group = g.name;
                        def.folder = f.name;
                        def.kind = (r.labels != null ? r.labels.get("kind") : null);
                        def.dashUid = r.annotations == null ? null : r.annotations.get("__dashboardUid__");
                        def.panelId = r.annotations == null ? null : r.annotations.get("__panelId__");

                        // ====== 여기만 최소 수정 ======
                        // 목록(/api/ruler…)의 pause 값은 grafana_alert.is_paused에만 존재
                        def.paused = (r.alert != null) ? r.alert.paused : null;
                        // ============================

                        def.ruleUid = (r.uid != null && !r.uid.isBlank())
                                ? r.uid
                                : (r.alert != null && r.alert.uid != null && !r.alert.uid.isBlank() ? r.alert.uid : null);

                        // byKey
                        if (def.dashUid != null && def.panelId != null) {
                            out.byKey.put(def.dashUid + ":" + def.panelId, def);
                        }
                        // byTitle
                        if (def.title != null && !def.title.isBlank()) {
                            out.byTitle.put(def.title, def);
                        }
                        // byGroupTitle
                        if (def.group != null && def.title != null) {
                            out.byGroupTitle
                                    .computeIfAbsent(def.group, k -> new HashMap<>())
                                    .put(def.title, def);
                        }
                        // byGroupKind
                        if (def.group != null && def.kind != null) {
                            out.byGroupKind
                                    .computeIfAbsent(def.group, k -> new HashMap<>())
                                    .put(def.kind, def);
                        }
                        // byExpr (정규화)
                        final String exprN = normExpr(def.expr);
                        if (exprN != null) {
                            out.byExpr.put(exprN, def);
                        }
                        rulesCount++;
                    }
                }
            }
            LOG.info("[Ruler] indexed defs: totalRules=" + rulesCount
                    + ", byKey=" + out.byKey.size()
                    + ", byTitle=" + out.byTitle.size()
                    + ", byExpr=" + out.byExpr.size());
        } catch (Exception e) {
            LOG.warn("[Ruler] fetch/index failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static <K1, K2, V> V getFromNested(final Map<K1, Map<K2, V>> m, final K1 k1, final K2 k2) {
        final Map<K2, V> inner = m.get(k1);
        return inner == null ? null : inner.get(k2);
    }

    /** totals 우선 + alerts로 최신 activeAt/부족분 보정합니다. */
    private static Agg aggregate(final GrafanaRulesResponse.Rule r) {
        final Agg a = new Agg();
        if (r.totals != null) {
            a.firing  = getTotal(r.totals, "firing") + getTotal(r.totals, "alerting");
            a.pending = getTotal(r.totals, "pending");
        }
        if (r.alerts != null) {
            for (GrafanaRulesResponse.AlertInst inst : r.alerts) {
                final String st = inst.state == null ? "" : inst.state.toLowerCase(Locale.ROOT);
                if ("alerting".equals(st))      a.firing++;
                else if ("pending".equals(st))  a.pending++;
                if (inst.activeAt != null) {
                    a.lastActiveAt = (a.lastActiveAt == null || inst.activeAt.isAfter(a.lastActiveAt))
                            ? inst.activeAt : a.lastActiveAt;
                }
            }
        }
        return a;
    }

    private static int getTotal(final Map<String, Integer> totals, final String key) {
        final Integer v = totals == null ? null : totals.get(key);
        return v == null ? 0 : v;
    }

    /** PromQL 비교를 위한 정규화(공백 압축) */
    private static String normExpr(final String s) {
        if (s == null) return null;
        final String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /** 'threshold' 타입 노드의 evaluator.type을 우선 채택합니다. */
    private static String extractOperator(final RulerRulesResponse.Rule r) {
        if (r == null || r.alert == null || r.alert.data == null) return null;
        String last = null, thresholdType = null;
        for (RulerRulesResponse.DataNode dn : r.alert.data) {
            if (dn == null || dn.model == null) continue;
            if ("threshold".equalsIgnoreCase(dn.model.type) && dn.model.conditions != null) {
                for (RulerRulesResponse.Condition c : dn.model.conditions) {
                    if (c != null && c.evaluator != null && c.evaluator.type != null) {
                        thresholdType = c.evaluator.type; // 최우선
                    }
                }
            }
            if (thresholdType == null && dn.model.conditions != null) {
                for (RulerRulesResponse.Condition c : dn.model.conditions) {
                    if (c != null && c.evaluator != null && c.evaluator.type != null) {
                        last = c.evaluator.type;
                    }
                }
            }
        }
        return thresholdType != null ? thresholdType : last;
    }
    private static Double extractThreshold2(final RulerRulesResponse.Rule r) {
        if (r == null || r.alert == null || r.alert.data == null) return null;
        Double best = null, last = null;
        for (RulerRulesResponse.DataNode dn : r.alert.data) {
            if (dn == null || dn.model == null || dn.model.conditions == null) continue;
            for (RulerRulesResponse.Condition c : dn.model.conditions) {
                if (c == null || c.evaluator == null || c.evaluator.params == null) continue;
                if (c.evaluator.params.size() >= 2) {
                    final Double v = safeDouble(c.evaluator.params.get(1));
                    if (v != null) {
                        last = v;
                        if ("threshold".equalsIgnoreCase(dn.model.type)) {
                            best = v; // threshold 노드가 최우선
                        }
                    }
                }
            }
        }
        return best != null ? best : last;
    }


    private static Double extractThreshold(final RulerRulesResponse.Rule r) {
        if (r == null || r.alert == null || r.alert.data == null) return null;
        Double best = null, last = null;
        for (RulerRulesResponse.DataNode dn : r.alert.data) {
            if (dn == null || dn.model == null || dn.model.conditions == null) continue;
            for (RulerRulesResponse.Condition c : dn.model.conditions) {
                if (c == null || c.evaluator == null || c.evaluator.params == null) continue;
                if (c.evaluator.params.size() >= 1) {
                    final Double v0 = safeDouble(c.evaluator.params.get(0)); // 하한
                    if (v0 != null) {
                        last = v0;
                        if ("threshold".equalsIgnoreCase(dn.model.type)) {
                            best = v0; // threshold 노드 우선
                        }
                    }
                }
            }
        }
        return best != null ? best : last;
    }

    /** prom expr은 rule.expr → data[*].model.expr 순으로 찾습니다. */
    private static String extractExpr(final RulerRulesResponse.Rule r) {
        if (r == null) return null;
        if (normExpr(r.expr) != null) return normExpr(r.expr);
        if (r.alert != null && r.alert.data != null) {
            for (RulerRulesResponse.DataNode dn : r.alert.data) {
                if (dn != null && dn.model != null && normExpr(dn.model.expr) != null) {
                    return normExpr(dn.model.expr);
                }
            }
        }
        return null;
    }

    private static Double safeDouble(final Object o) {
        if (o == null) return null;
        try {
            return Double.valueOf(String.valueOf(o));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String sanitizeXmlText(String s) {
        if (s == null) return null;
        // BOM 제거
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        // XML 1.0에서 허용되는 문자만 유지 (탭/개행/CR + U+0020~)
        return s.replaceAll("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]", "");
    }

    private static Map<String, String> sanitizeXmlMap(Map<String, String> in) {
        if (in == null) return Collections.emptyMap();
        Map<String, String> out = new java.util.LinkedHashMap<>(in.size());
        for (Map.Entry<String, String> e : in.entrySet()) {
            out.put(sanitizeXmlText(e.getKey()), sanitizeXmlText(e.getValue()));
        }
        return out;
    }

    // "alerting|firing|alert" -> ALERTING, "no_data|no-data|nodata" -> NODATA 등으로 정규화합니다.
    private static String normalizeState(String s) {
        if (s == null) return null;
        final String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "alerting":
            case "firing":
            case "alert":
                return "ALERTING";
            case "pending":
            case "warming":
            case "wait":
                return "PENDING";
            case "ok":
            case "healthy":
            case "normal":
                return "OK";
            case "nodata":
            case "no_data":
            case "no-data":
            case "no data":
                return "NODATA";
            default:
                // 이미 표준 형태면 대문자 비교를 위해 올립니다.
                return t.toUpperCase(java.util.Locale.ROOT);
        }
    }

    // "ALERTING,PENDING" 또는 "alerting pending" 등을 Set 으로 파싱합니다.
    private static java.util.Set<String> parseStateFilter(String param) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        if (param == null) return out;
        for (String tok : param.split("[,\\s]+")) {
            if (tok == null || tok.isBlank()) continue;
            final String norm = normalizeState(tok);
            if (norm != null && !norm.isBlank()) out.add(norm);
        }
        return out;
    }

    private static boolean looksLikeDashboardUid(final String s) {
        // Grafana dashboard UID는 보통 8~40자의 영숫자/하이픈입니다.
        return s != null && s.matches("[A-Za-z0-9\\-]{6,64}");
    }

    private static boolean looksLikePanelId(final String s) {
        // 패널 ID는 대부분 정수 형태입니다.
        return s != null && s.matches("\\d{1,6}");
    }

    private static String firstNonBlank(final String... vs) {
        if (vs == null) return null;
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String get(final Map<String, String> m, final String k) {
        return m == null ? null : m.get(k);
    }

    private static final class Mapping {
        final String namespace;     // 폴더 이름(표시용)
        final String group;         // 그룹 이름
        final String title;         // 룰 제목(참고용)
        final String ruleUid;       // 룰 UID (r.uid 또는 grafana_alert.uid)
        final String namespaceUid;  // 폴더 UID(있으면 우선 사용)

        // 레거시 호환
        Mapping(final String ns, final String g, final String t) {
            this(ns, g, t, null, null);
        }
        // UID만 아는 경우
        Mapping(final String ns, final String g, final String t, final String uid) {
            this(ns, g, t, uid, null);
        }
        // UID와 폴더 UID 모두 아는 경우
        Mapping(final String ns, final String g, final String t, final String uid, final String nsUid) {
            this.namespace = ns;
            this.group = g;
            this.title = t;
            this.ruleUid = uid;
            this.namespaceUid = nsUid;
        }
    }

    private final class DashPanel {
        final String group;
        final String title;
        DashPanel(final String g, final String t) { this.group = g; this.title = t; }
    }

    // 헬퍼 메서드들(인스턴스 메서드: static 사용 안 함)
    private Mapping mapGroupTitle(final RulerRulesResponse all, final String group, final String title) {
        if (all == null || all.folders == null || group == null || title == null) return null;
        for (final RulerRulesResponse.Folder f : all.folders) {
            if (f == null || f.groups == null) continue;
            for (final RulerRulesResponse.Group g : f.groups) {
                if (g == null || g.rules == null) continue;
                if (!group.equals(g.name)) continue;
                for (final RulerRulesResponse.Rule r : g.rules) {
                    if (r == null) continue;
                    final String ruTitle = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
                    if (title.equals(ruTitle)) {
                        return new Mapping(f.name, g.name, ruTitle);
                    }
                }
            }
        }
        return null;
    }

    private Mapping mapDashPanelFromRuler(final RulerRulesResponse all, final String dashUid, final String panelId) {
        if (all == null || all.folders == null || dashUid == null || panelId == null) return null;
        for (final RulerRulesResponse.Folder f : all.folders) {
            if (f == null || f.groups == null) continue;
            for (final RulerRulesResponse.Group g : f.groups) {
                if (g == null || g.rules == null) continue;
                for (final RulerRulesResponse.Rule r : g.rules) {
                    if (r == null) continue;
                    final Map<String, String> ann = r.annotations;
                    final String uidDash = firstNonBlank(
                            get(ann, "__dashboardUid__"), get(ann, "dashboardUid"),
                            get(ann, "dashboardUID"),     get(ann, "dashboard_uid")
                    );
                    final String pid = firstNonBlank(
                            get(ann, "__panelId__"),      get(ann, "panelId"),
                            get(ann, "panelID"),          get(ann, "panel_id")
                    );
                    if (dashUid.equals(uidDash) && panelId.equals(pid)) {
                        final String ruTitle = (r.alert != null && r.alert.title != null) ? r.alert.title : r.title;
                        final String ruUid   = (r.uid != null && !r.uid.isBlank())
                                ? r.uid
                                : (r.alert != null && r.alert.uid != null && !r.alert.uid.isBlank() ? r.alert.uid : null);
                        return new Mapping(f.name, g.name, ruTitle, ruUid);
                    }
                }
            }
        }
        return null;
    }

    private DashPanel mapDashPanelFromRules(final GrafanaRulesResponse rules, final String dashUid, final String panelId) {
        if (rules == null || rules.data == null || rules.data.groups == null || dashUid == null || panelId == null) return null;
        for (final GrafanaRulesResponse.Group g : rules.data.groups) {
            if (g == null || g.rules == null) continue;
            for (final GrafanaRulesResponse.Rule r : g.rules) {
                if (r == null) continue;
                final Map<String, String> ann = r.annotations;
                final String uid = firstNonBlank(
                        get(ann, "__dashboardUid__"), get(ann, "dashboardUid"),
                        get(ann, "dashboardUID"),     get(ann, "dashboard_uid")
                );
                final String pid = firstNonBlank(
                        get(ann, "__panelId__"),      get(ann, "panelId"),
                        get(ann, "panelID"),          get(ann, "panel_id")
                );
                if (dashUid.equals(uid) && panelId.equals(pid)) {
                    final String title = (r.name != null) ? r.name :
                            (r.annotations != null ? r.annotations.get("title") : null);
                    if (title == null) continue;
                    return new DashPanel(g.name, title);
                }
            }
        }
        return null;
    }

}


