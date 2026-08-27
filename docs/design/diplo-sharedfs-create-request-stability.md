# Diplo SharedFS 생성 요청 안정화 설계

## 배경

Diplo 공유 파일시스템 생성 화면은 일반 디스크 오퍼링을 선택해 IOPS 입력란이 없는 경우에도 `miniops`와 `maxiops`를 요청 객체에 포함했다. 공통 API 직렬화기는 JavaScript의 `undefined`를 문자열 `"undefined"`로 전송했고, 관리 서버는 LONG 파라미터를 해석하는 단계에서 요청을 거부했다.

## 설계 원칙

| 계층 | AS-IS | TO-BE |
|---|---|---|
| 생성 화면 | 디스크 오퍼링 종류와 관계없이 IOPS 필드 포함 | 사용자 지정 IOPS 오퍼링이며 두 값이 모두 입력된 경우에만 포함 |
| 입력 상태 | 오퍼링 변경 후 숨겨진 IOPS 값이 남을 수 있음 | 일반 오퍼링으로 변경하면 두 값을 즉시 초기화 |
| 입력 검증 | 각 값의 양수 여부만 독립 검사 | 둘 다 입력하거나 둘 다 생략, 양의 정수, 최소값 이하 최대값 규칙 적용 |
| API 직렬화 | `undefined`와 `null`도 문자열로 직렬화 | 부재 값은 전송하지 않고 `0`, `false`, 빈 문자열은 기존 의미를 보존 |
| 서버 검증 | 사용자 지정 IOPS 지원 여부만 검사 | 쌍 입력, 양수, `miniops <= maxiops`를 최종 검증 |

## 코드 반영 대상

- `ui/src/views/storage/CreateSharedFS.vue`
  - `buildCreateSharedFsRequest`에서 생성 요청을 단일 경로로 조립한다.
  - IOPS는 사용자 지정 IOPS 오퍼링과 완전한 값 쌍을 만족할 때만 숫자로 전송한다.
  - 오퍼링 전환 시 숨겨진 IOPS 상태를 제거한다.
- `ui/src/api/index.js`
  - `appendApiData`를 통해 `undefined`와 `null`을 모든 POST 요청에서 제외한다.
- `server/src/main/java/org/apache/cloudstack/storage/sharedfs/SharedFSServiceImpl.java`
  - UI 우회 호출에도 동일한 IOPS 계약을 적용한다.
- 단위 테스트
  - 공통 직렬화의 부재 값 제거와 의미 있는 falsy 값 보존을 검증한다.
  - SharedFS 생성 요청의 일반/사용자 지정 IOPS 분기를 검증한다.
  - 서버의 불완전, 비양수, 역전 IOPS 범위를 검증한다.

## 배포 및 확인 기준

1. UI와 관리 서버 변경 산출물만 배포한다.
2. 기존 런타임 `config.json`을 보존한다.
3. `/usr/share/cloudstack-ui`는 정적 UI 산출물로 교체할 수 있지만, `/usr/share/cloudstack-management/webapp`은 `WEB-INF`를 포함하는 서버 웹앱이므로 기존 디렉터리에 정적 산출물만 덮어쓴다.
4. 배포 후 `WEB-INF/web.xml`, `/client/api/` 라우팅과 인증 응답을 먼저 확인한다.
5. 배포된 `index.html`의 JS/CSS 해시와 HTTP 200 응답을 확인한다.
6. 일반 디스크 오퍼링 생성 요청에 `miniops`, `maxiops`, `undefined`가 없음을 확인한다.
7. 관리 서버 로그에서 LONG 파라미터 변환 오류가 재발하지 않아야 한다.
8. DB 스키마와 SystemVM 템플릿은 변경하지 않는다.
