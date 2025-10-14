package org.apache.cloudstack.wallAlerts.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.cloudstack.wallAlerts.model.SilenceDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 월(Wall, Grafana) Ruler/Rules API 클라이언트 인터페이스와 DTO입니다.
 * - 리스트/서비스/매퍼가 기대하는 필드만 최소 보강했습니다.
 * - 시그니처는 변경하지 않았습니다.
 */
public interface WallApiClient {

    /** Rules API (상태/인스턴스) 조회 */
    GrafanaRulesResponse fetchRules();

    /** Ruler API (임계/연산자/표현식) 전체 조회 */
    RulerRulesResponse fetchRulerRules();

    /**
     * 단일 룰의 임계치(threshold) 값을 업데이트합니다.
     * @param namespaceHint 폴더/네임스페이스 힌트(UID 또는 이름). null 가능
     * @param groupName     Ruler 그룹명(필수)
     * @param ruleTitle     grafana_alert.title (또는 rule.title) (필수)
     * @param newThreshold  새 임계값(단일 임계형)
     * @return true=성공, false=실패
     */
    boolean updateRuleThreshold(String namespaceHint, String groupName, String ruleTitle,
                                String operator, Double newThreshold, Double threshold2);

    boolean pauseRule(String namespaceHint, String groupName, String ruleUid, boolean paused);

    boolean pauseByUid(String ruleUid, boolean paused);

    /**
     * Alertmanager v2 사일런스 목록을 가져옵니다.
     * 서버는 전체 목록을 반환하므로 state 필터는 클라이언트에서 적용합니다.
     *
     * @param stateFilter optional: active|pending|expired
     * @return silences
     */
    List<SilenceDto> listSilences(String stateFilter);

    SilenceDto getSilence(String id, boolean ruleMetadata, boolean accessControl);

    /**
     * 특정 사일런스를 만료(삭제)합니다.
     *
     * @param silenceId silence id
     */
    void expireSilence(String silenceId);



    // -------------------- DTOs (Rules API) --------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    final class GrafanaRulesResponse {
        public Data data;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Data {
            public List<Group> groups;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Group {
            public String name;
            public List<Rule> rules;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Rule {
            // 기본 필드
            public String name;
            public String query;
            public String state;                 // "firing" | "pending" | "inactive" 등
            public String health;                // "ok" | "no_data"/"nodata" 등
            public Double evaluationTime;        // 초 단위
            public OffsetDateTime lastEvaluation;
            public Map<String, String> labels;
            public Map<String, String> annotations;
            public List<AlertInst> alerts;       // 인스턴스들

            // ▼ 서비스/매퍼에서 참조하는 필드 호환용(환경에 따라 존재)
            public Number duration;              // 일부 환경에서 초 단위 duration 제공 가능
            public String type;                  // 규칙 유형
            public Map<String, Integer> totals;  // firing/pending 합계
            public String expr;                  // 일부 응답에서 직접 expr 제공
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class AlertInst {
            public String state;                 // ALERTING | PENDING | INACTIVE
            public OffsetDateTime activeAt;
            public Map<String, String> labels;
            public Map<String, String> annotations;

            //  서비스 코드가 라벨에 그대로 넣기 위해 문자열 타입을 기대함
            public String value;                 // (중요) Double → String 으로 변경
        }
    }

    // -------------------- DTOs (Ruler API) --------------------

    /**
     * Ruler 응답을 폴더(네임스페이스) 단위로 정규화한 구조입니다.
     * 구현체에서 다양한 실제 응답을 본 구조로 어댑트하여 반환합니다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    final class RulerRulesResponse {
        public List<Folder> folders;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Folder {
            public String name;             // 네임스페이스(폴더) 식별자(보통 UID)
            public List<Group> groups;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Group {
            public String name;
            public String interval;
            public List<Rule> rules;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Rule {
            @JsonProperty("uid")
            public String uid;
            @JsonProperty("grafana_alert")
            public GrafanaAlert alert;

            public String title;            // 일부 스키마에서 grafana_alert.title과 별도 제공
            @JsonProperty("for")
            public String forText;          // "5m" 등

            public Map<String, String> labels;
            public Map<String, String> annotations;

            // 구형 포맷 호환(조건 직접 포함)
            public List<Condition> conditions;

            //  서비스 코드가 r.expr를 직접 참조하는 경우를 위한 호환 필드
            public String expr;             // (중요) Rule 레벨 expr 호환 추가

            // 경고 알림 정지
            @JsonProperty("isPaused")
            public Boolean isPaused;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class GrafanaAlert {
            @JsonProperty("uid")
            public String uid;
            public String title;
            public String condition;         // "A", "B" 등
            public List<DataNode> data;
            public Boolean isPaused;
            @JsonProperty("is_paused")
            @JsonAlias({ "isPaused", "paused" })
            public Boolean paused;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class DataNode {
            @JsonProperty("refId")
            public String refId;

            @JsonProperty("queryType")
            public String queryType;             // 예: "promql" (없을 수도 있음)

            @JsonProperty("datasourceUid")
            public String datasourceUid;         // 필수: 여기 있어야 함

            @JsonProperty("relativeTimeRange")
            public JsonNode relativeTimeRange;   // {"from":..., "to":...} 통째 보존

            @JsonProperty("datasource")
            public JsonNode datasource;          // (환경에 따라 오는 경우가 있어 보존)

            @JsonProperty("model")
            public Model model;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Model {
            /** "query" | "reduce" | "threshold" | "math" 등 */
            public String type;

            /** 신규 포맷 조건 */
            public List<Condition> conditions;

            /** 혼재 호환 */
            public String expression;
            public String expr;

            public String reducer;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Condition {
            public Reducer reducer;
            public Evaluator evaluator;
            public Query query;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Reducer {
            public String type;
            public String name;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Evaluator {
            public String type;              // gt/gte/lt/lte/within_range/outside_range ...
            public List<Object> params;      // 숫자/문자 혼재 대비
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static final class Query {
            public List<String> params;      // 예: ["A"]
        }
    }
}
