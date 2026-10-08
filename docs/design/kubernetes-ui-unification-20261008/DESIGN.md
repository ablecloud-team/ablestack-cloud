# Kubernetes UI 통합 개선 설계

## 목적과 범위

Epic #1227의 Kubernetes 전 생명주기 기능을 VM 목록·상세와 같은 UI 언어로 표현한다. 기준은 최신 Europa 소스 `c169d9a203f49ce07e038297873bc3c24cd8ffb4`와 클러스터 31에서 확인한 현재 VM 목록·상세·생성 및 Kubernetes 상세이다.

운영 기능은 이미 배포된 상태이며 이번 산출물은 설계와 검토용 Vue 목업이다. 실제 `ui/src` 변경, 서버 배포, 클러스터 생성/삭제/확장, 전체 Cloud 빌드는 수행하지 않는다. 장기 시험 클러스터를 유지하고 향후 구현 검증은 필요 시 별도 시험 클러스터에서 수행한다. 31번 환경은 GFS2 Primary만 사용하며 clvm / clvm-ng는 시험 대상에서 제외한다.

## VM 레퍼런스와 레이아웃 계약

| 영역 | VM 기준 | Kubernetes 적용 |
| --- | --- | --- |
| 전역 셸 | 기존 헤더·왼쪽 내비게이션·breadcrumb | 기존 Mold 셸 유지. 개발 정보는 제품 화면에 넣지 않음 |
| 목록 툴바 | 업데이트 / 필터 / 프로젝트·보기 / 주요 추가 버튼 / 검색·아이콘 | 텍스트가 있는 주 버튼을 먼저 배치하고 검색·보조 아이콘을 오른쪽에 배치. 일괄 작업은 선택 후 표시 |
| 목록 테이블 | 표준 선택·이름 링크·상태·자원·소유자·Zone·페이지 | 표준 ListView 재사용. 이름 클릭과 상세 라우팅, 선택/필터/페이지 유지 |
| 상세 골격 | 공통 ResourceLayout + InfoCard + 우측 탭 | desktop 7/17, tablet 8/16, gutter 12. 좁은 화면은 24/24로 쌓임. breakpoint별 실제 그리드는 공통 컴포넌트 준수 |
| 상세 탭 | 데스크톱 세로 탭, 모바일 가로 탭 | 상세, 노드, 액세스, 부하 분산, 방화벽, 포트 포워딩, 이벤트, 코멘트. 기존 하위 기능을 얕은 탭 계층으로 정리 |
| 생성 흐름 | 번호가 있는 VM 생성 섹션 + 지속되는 우측 요약 | 기본 → 노드·스토리지 → 네트워크·역할 → 선택 구성 → 검토. 데스크톱 폼/요약 2열, 모바일 1열 |
| 대화상자 | 표준 헤더·닫기, 본문 스크롤, 고정 하단 | MoldDialog 재사용. 모든 장시간/위험 작업은 대상 요약과 명확한 동사 버튼 제공 |
| 폼 | 공통 AForm, 툴팁, 필수 표기, 단위, 오류 위치 이동 | 기존 API 메타데이터·validation 유지. 원시 true/false 대신 사용/사용 안 함, 상태는 기존 번역 사용 |
| 테마 | 현재 semantic CSS variables | 공통 배경·텍스트·경계·성공/경고/오류 토큰. 파괴적 주 버튼은 오류 의미 색상. 색상만으로 의미를 전달하지 않음 |

현재 ResourceLayout은 `md=24`와 `lg` 비율을 지정하지만 `xs` 값은 없다. 768px 미만에서 열이 내용 너비로 줄어드는 경계 조건을 확인했다. 목업은 해당 폭에서 두 열을 100%로 보정한다. 운영 적용은 공통 열의 `xs=24` 등으로 검토하고 VM/타 리소스 모바일 회귀를 반드시 확인한다.

기존 Kubernetes는 이미 일부 공통 ResourceLayout/InfoCard를 사용한다. 새 독립 레이아웃을 만들지 않고 툴바, 정보 우선순위, 값 표현과 분산된 custom/일반 API 폼을 공통 계약에 맞춘다. 목업의 데스크톱 너비는 1920px이고 모바일은 390px이다. 765/1279px의 device 분기와 실제 ResourceLayout의 AntD 그리드 분기를 구분한다.

### 목록과 상세 정보의 의미

- 목록: 이름, 상태, 관리 유형, Kubernetes semver, 워커/제어 수, **할당 자원 합계**(vCPU, MB), AutoScaler 사용 설정, 권한에 따른 계정/프로젝트, Zone. 좁은 표에서는 표 내부 가로 스크롤을 허용하고 페이지 전체가 넘치지 않게 한다.
- 상세 좌측: 이름·관리 유형·semver, 상태, UUID, API endpoint, 할당 자원, 노드 구성, 실제 역할별 템플릿, 네트워크, AutoScaler 사용 설정, CSI 사용 설정, 계정/Zone. 긴 이름·UUID·URL은 줄바꿈/복사 기능을 제공한다.
- API endpoint는 실제 반환값을 사용한다. 없는 정보는 `—`/`관측 정보 없음`으로 표시한다. 예시 IP나 추측 포트를 제품에서 생성하지 않는다.
- `cpunumber`/`memory`는 할당량이다. 실시간 CPU/메모리 사용률로 표현하지 않는다.
- 등록 이름/긴 아티팩트 이름과 Kubernetes semver를 분리한다. ISO UUID·원본 이름·URL·체크섬·릴리즈 출처는 펼친 상세에서 확인한다.
- 1.37.1 AutoScaler는 **Mold 내 프로덕션 검증 판정**을 유지한다. 오래된 아티팩트 문자열의 DEV 표기를 등급 판정 근거로 쓰지 않는다. 이 판정을 다른 환경/업스트림 전체의 일반 지원 판정으로 확장하지 않는다.
- `autoscalingenabled`/`csienabled`는 사용 설정이다. AutoScaler 런타임 건강 상태·CSI 실제 설치/Pod 준비 상태와 구분한다. 현재 API에 관측 필드가 없으면 정상으로 추정하지 않는다. 관측 API 추가가 필요하다면 기존 관련 이슈와 연결한다.

## 공통 대화상자 계약

공통 MoldDialog의 centered, mask-closable=false, 본문만 스크롤하는 CSS를 유지한다. 너비 기본 760px, ISO 1000px, 생성 1120px, 노드 선택 920px, VM/일괄 선택 960px. 모바일은 viewport에 맞춰 8px 여백과 본문 padding 16px를 적용한다. 헤더·버튼 영역을 화면 밖으로 밀어내지 않는다.

대상 요약 → 입력 또는 변경 전/후 → 조건별 안내 → 하단 버튼 순서로 통일한다. 취소를 왼쪽, 실행을 오른쪽에 둔다. 생성에서는 취소/이전/다음 또는 생성 순서. 삭제는 빨간 의미 색상과 대상·삭제 범위 설명을 제공하며 새 이름 재입력 절차를 임의로 추가하지 않는다.

입력 오류는 해당 AForm 항목 아래에 표시하고 첫 오류로 이동한다. API 오류는 작업 요약 아래 Alert로 표시하고 입력값을 보존한다. 조회 실패·빈 후보·권한 없음·진행 중·부분 실패를 구분한다. 빈 후보에는 이유와 갱신/이전 화면으로 돌아가는 동작을 제공한다.

최종 구현은 중복 submit 방지, 제출 loading, 기존 job ID / $pollJob / 작업 알림 경로를 사용한다. API 접수와 작업 완료를 구분한다. 장기 작업을 시작한 후 대화상자를 닫아도 작업 상태를 상세/이벤트에서 다시 확인할 수 있어야 한다. 실패/재시도 결과는 서버 상태를 재조회하고 무조건 완료로 표시하지 않는다.

키보드 Tab/Shift+Tab 순서, 모달 focus trap과 닫은 후 focus 복귀, 접근 가능한 label/tooltip, Escape의 busy 상태 처리, 대비를 후속 구현 검증에 포함한다. 실행 단축키가 있다면 유효 입력/권한/비진행 상태에서만 처리한다. 이 목업의 렌더링 검사는 접근성 전체 통과를 의미하지 않는다.

## 생성 폼의 기존 API 보존

| 단계 | UI / 조건 | 유지할 데이터 계약 |
| --- | --- | --- |
| 기본 정보 | 이름·설명·Zone·하이퍼바이저·버전·계정/프로젝트 | name, description, zoneid, kubernetesversionid, clustertype=CloudManaged; owner의 account/domainid 또는 projectid. 권한·프로젝트 선택 조건 유지 |
| 노드·스토리지 | 기본 오퍼링·워커 수·ROOT 크기·HA·제어 수·SSH 키 | serviceofferingid, size, noderootdisksize, controlnodes, keypairid/keypair. HA/버전 메타데이터 조건 유지. KubernetesStoragePreflight 재사용 |
| 네트워크·역할 | network, 역할별 offering/template/Affinity, 외부 etcd, HA LB IP | nodeofferings[], nodetemplates[], nodeaffinitygroups[], etcdnodes, externalloadbalanceripaddress, networkid. 기존 역할별 비동기 조회와 템플릿 검증 유지 |
| CNI 조건 | 선택 CNI 동적 필드·AS number | cniconfigurationid, cniconfigdetails[], asnumber. network offering의 routingmode/specifyasnumber와 CNI 구성에 따라 렌더링. AS_NUMBER의 서버 결정 의미 유지 |
| 선택 구성 | CSI, private registry | enablecsi, dockerregistryusername/password/url. 기존 kubernetesclusterexperimentalfeaturesenabled 등의 기능 flag·API metadata 조건 유지 |
| 검토 | 실제 역할별 배치·할당량·ISO 준비·한도·네트워크·스토리지 재조회 | UI에서 임의로 Ready/프로파일 지원을 판정하지 않음. 기존 사전 검증만으로 보장되지 않는 정보는 확인 필요로 표현하고 새 backend 계약을 선행 조건으로 기록 |

단계를 바꿔도 입력을 보존한다. 상위 Zone/버전/네트워크 선택이 바뀌면 종속 선택을 다시 검증하고 이유를 표시한다. 운영 화면의 자동 기본값과 optional 필드 포함/제외 규칙을 유지한다. 목업의 예시 선택값과 요약 합계는 정적이며 서버 검증을 대신하지 않는다.

### CSI와 ISO 등록

기본 `mold-cks` ISO에는 CSI가 포함되지 않는다. 생성 폼 기본값은 설치 안 함이며 별도 CSI 릴리즈 설치 안내를 제공한다. 설치 옵션은 해당 아티팩트/버전의 실제 지원 메타데이터와 기존 사전 검증에 따라 제공한다. 이름만 보고 CSI 포함/설치 가능을 추정하지 않는다. 지원 메타데이터가 없는 경우 기본 배포를 막지 않고 `확인 필요 · 별도 설치`로 안내한다. 프로파일 metadata 확장이 필요하면 기존 ISO/CSI 기능 이슈의 계약으로 관리한다.

등록 이름은 표시용이며 semanticversion과 별도다. 권장 이름 예시 `Mold-Kubernetes-1.37.1-amd64-r1`; 버전 판정은 semanticversion/UUID를 사용한다. URL 등록과 local upload는 동일한 폼 그룹을 사용하지만 실제 API와 진행 상태가 다르므로 명확히 구분한다.

Mold 등록 체크섬은 실제 Release registration JSON이 제공하는 값과 알고리즘을 사용한다. 릴리즈 파일의 SHA256 무결성 검증과 Mold 등록용 checksum을 동일 필드로 가정하거나 64자리 SHA256 형식을 강제하지 않는다. directdownload 및 Zone/edge 제약은 기존 AddKubernetesSupportedVersion 동작을 유지한다. `Enabled` 지원 버전과 ISO `Ready` 다운로드 상태를 서로 다른 항목으로 표시한다.

## 작업별 상태·권한 계약

| 작업 | 유지할 기준 |
| --- | --- |
| 시작 | CloudManaged + Stopped. 단건 rotatecontrollercredentials 선택은 기존 권한/API 인자 유지; 일괄 시작에는 임의로 추가하지 않음 |
| 중지 | 기존 compute.js show 조건과 서버 상태 검증을 유지. 전이 상태에서의 충돌 동작은 기존 상태 일관성 이슈와 연결 |
| 수동 확장 / AutoScaler | 기존 Created/Running/Stopped 또는 partialScaleRecoverySize 허용 조건 유지. 변경 후 목표·역할별 오퍼링·할당량 표시. AS 사용 중 수동 변경 제약 유지 |
| 부분 확장 복구 | 원래 목표 크기의 재시도만 제공. 동시에 offering/AutoScaler 값을 변경하지 않음. 부분 실패 노드와 결과를 서버 응답에서 표시 |
| 업그레이드 | CloudManaged + Created/Running. eligible 후보만 표시; 동일·하향·마이너 건너뛰기 제거, PDB/cordon/CSI gate·UID 보존 유지 |
| Affinity | CloudManaged + Stopped. 제어/워커/etcd 각각 복수 그룹, etcd 없는 경우 해당 입력 제외 |
| 외부 VM 추가/연결 해제 | 기존 CloudManaged Running/Alert 상태에서 외부 노드 membership을 관리. 외부 관리형 클러스터와 혼동하지 않음; 외부 VM 소유권/삭제 범위 명시 |
| 워커 삭제 | 실제 KubernetesServiceTab의 역할/상태/AS 조건 유지. 제어 노드 삭제 보호. drain/PDB 실패 시 삭제 완료로 표시하지 않음 |
| 클러스터 삭제 | Destroyed/Destroying 제외 및 backend 재조회. 관리형은 기존 자동 정리 정책 적용. 외부 관리형 cleanup/expunge는 Admin/allowuserexpungerecovervm 인자 노출 조건 유지 |
| 일괄 작업 | 선택 대상별 state/RBAC 재검증. 실행 불가 이유·개별 결과·부분 실패 유지. cleanup/expunge 적용은 각 대상 관리 유형에 맞춤 |
| 네트워크 | managed API/SSH 포트 보호, 실제 NIC/포트/CIDR·VPC ACL 권한 유지. Provider 소유 서비스 규칙과 사용자 규칙을 구분 |
| 삭제와 CSI | UID 기반 PV 정리, Retain/사용자 네트워크 보존, 정리 실패 시 노드 보존·재시도 의미 유지. 단순 UI 취소로 삭제 성공 판정 금지 |

API/secret key, kubeconfig, Headlamp 토큰, registry password는 정보 카드·URL·디버그 로그·목업 이미지에 노출하지 않는다. 기존 Mold HMAC-SHA256 인증 및 권한·유효기간 정책은 변경하지 않는다. 구성 다운로드와 Headlamp 열기는 액세스 탭의 기존 실행 경로를 유지하며 다운로드 방식이나 로그인 토큰을 새로 저장하지 않는다.

## 범위와 기존 이슈 연결

이슈 번호는 `ablecloud-team/ablestack-cloud` 기준이다. 동일 장애의 새 이슈를 중복 생성하지 않고 아래 기능 계약을 이어받는다. 현재 open/closed 상태만으로 기능 미구현/완료를 다시 판정하지 않는다.

| 기존 이슈 | 이번 UI 설계에서 유지할 계약 |
| --- | --- |
| #1227 / #1230 | Epic 및 Europa 31 생명주기 시험 기준 |
| #1228 | 별도 ISO 저장소·Release 등록 산출물 |
| #1262 / #1259 | 실제 역할별 템플릿 및 비동기 조회 |
| #1253 / #1295 | 목록→상세 라우팅과 삭제 후 화면 복귀 |
| #1237 | 시작/삭제 상태와 작업 결과 일관성 |
| #1256 / #1245 | AutoScaler 설정과 실제 관측/인스턴스 매핑 |
| #1278 | 업그레이드 후보 적격성 |
| #1273 / #1257 | 실제 SSH 포트·NIC 및 관리 규칙 보호 |
| #1241 / #1300 | Provider 서비스별 LB 및 VPC ACL 권한 |
| #1239 | Headlamp 토큰·언어 |
| #1280 / #1284 | CSI 릴리즈·UID 기반 정리·실패 시 노드 보존 |

## 구현 단계와 수용 기준

1. **공통 골격**: ListView/InfoCard/ResourceLayout/기존 셸 재사용, 컬럼/semantic 값 변환, 탭 계층, i18n. K8S 분기에서 적용하고 VM/타 리소스 회귀 여부 확인.
2. **대화상자 통일**: 공통 MoldDialog/폼 섹션/대상 요약/버튼·오류 영역. 생성·ISO·확장·업그레이드·Affinity·외부 노드·삭제·일괄 작업 순으로 필드/API parity 확인.
3. **하위 탭**: node action/접근 안내/LB/방화벽/PF/이벤트/코멘트 및 nested 확인창. 공통 네트워크·코멘트 컴포넌트 변경 시 다른 리소스 화면 회귀 확인.
4. **상태와 회복**: busy/빈 후보/조회 실패/권한 거부/부분 실패/재시도, 기존 async job 결과와 route refresh/선택 보존 검증.
5. **Europa 31 검증**: 별도 GFS2 시험 클러스터에서 실제 UI/API 작업 결과를 함께 관측. 장기 시험 자원은 유지. 밝은/어두운 테마·390px 및 공통 breakpoint, 키보드·접근성·한국어/영어·i18n fallback 검증. Vue lint/UI build, License 및 Conflict 상태 확인 후 PR 준비. 최종 통합 빌드는 기존 Release 단계 정책을 따른다.

수용 기준: 모든 아래 시나리오가 같은 VM 레이아웃/공통 대화상자 계약을 따른다. 기존 API 인자와 상태·RBAC·스토리지·업그레이드·CSI 보호 조건을 잃지 않는다. 숫자/단위/버전/설정/관측 의미를 정확히 표시한다. 에러를 숨기거나 입력을 잃지 않고 재시도 대상을 분명히 한다. 실제 운영 기능 성공은 구현 후 #1230의 UI와 API/runtime 증거로 판정한다.

## 화면·대화상자별 목업과 소스 매핑

생성의 5단계와 HA/etcd 분기, 확장의 수동/AS/복구, 업그레이드의 빈 후보 등은 하나의 실제 기능에 대한 여러 상태 목업이다. 32개의 별개 신규 API 또는 신규 모달을 뜻하지 않는다. 네트워크·코멘트의 작은 확인창도 검토 범위에 포함한다.

| ID | 목업 | 현재 소스 / API | 검토 이미지 |
| --- | --- | --- | --- |
| `list` | 클러스터 목록 | compute.js + ListView.vue | [밝음](images/list-light.jpg) · [어두움](images/list-dark.jpg) |
| `detail` | 클러스터 상세 | ResourceView.vue + InfoCard.vue + KubernetesServiceTab.vue | [밝음](images/detail-light.jpg) · [어두움](images/detail-dark.jpg) |
| `nodes` | 노드 목록 | KubernetesServiceTab.vue | [밝음](images/nodes-light.jpg) · [어두움](images/nodes-dark.jpg) |
| `access` | 액세스 안내 | KubernetesServiceTab.vue | [밝음](images/access-light.jpg) · [어두움](images/access-dark.jpg) |
| `loadbalancers` | 서비스 부하 분산 | KubernetesLoadBalancers.vue | [밝음](images/loadbalancers-light.jpg) · [어두움](images/loadbalancers-dark.jpg) |
| `firewall` | 방화벽 규칙 | FirewallRules.vue | [밝음](images/firewall-light.jpg) · [어두움](images/firewall-dark.jpg) |
| `portforwarding` | 포트 포워딩 | PortForwarding.vue | [밝음](images/portforwarding-light.jpg) · [어두움](images/portforwarding-dark.jpg) |
| `events` | 이벤트 | KubernetesServiceTab.vue / 기존 이벤트 경로 | [밝음](images/events-light.jpg) · [어두움](images/events-dark.jpg) |
| `comments` | 코멘트 | AnnotationsTab.vue | [밝음](images/comments-light.jpg) · [어두움](images/comments-dark.jpg) |
| `iso-list` | Kubernetes ISO 목록 | image.js + ListView.vue | [밝음](images/iso-list-light.jpg) · [어두움](images/iso-list-dark.jpg) |
| `iso-detail` | Kubernetes ISO 상세 | image.js + ResourceView.vue | [밝음](images/iso-detail-light.jpg) · [어두움](images/iso-detail-dark.jpg) |
| `create-basic` | 쿠버네티스 클러스터 생성 | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-basic-light.jpg) · [어두움](images/create-basic-dark.jpg) |
| `create-nodes` | 쿠버네티스 클러스터 생성 | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-nodes-light.jpg) · [어두움](images/create-nodes-dark.jpg) |
| `create-advanced` | 쿠버네티스 클러스터 생성 | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-advanced-light.jpg) · [어두움](images/create-advanced-dark.jpg) |
| `create-ha-etcd` | 클러스터 생성 · HA / 외부 etcd | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-ha-etcd-light.jpg) · [어두움](images/create-ha-etcd-dark.jpg) |
| `create-addons` | 쿠버네티스 클러스터 생성 | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-addons-light.jpg) · [어두움](images/create-addons-dark.jpg) |
| `create-review` | 쿠버네티스 클러스터 생성 | CreateKubernetesCluster.vue / createKubernetesCluster | [밝음](images/create-review-light.jpg) · [어두움](images/create-review-dark.jpg) |
| `start` | 클러스터 시작 | compute.js / AutogenView API form / startKubernetesCluster | [밝음](images/start-light.jpg) · [어두움](images/start-dark.jpg) |
| `stop` | 클러스터 중지 | compute.js / AutogenView API form / stopKubernetesCluster | [밝음](images/stop-light.jpg) · [어두움](images/stop-dark.jpg) |
| `scale` | 클러스터 확장 / 축소 | ScaleKubernetesCluster.vue / scaleKubernetesCluster | [밝음](images/scale-light.jpg) · [어두움](images/scale-dark.jpg) |
| `autoscale` | 오토스케일링 설정 | ScaleKubernetesCluster.vue / scaleKubernetesCluster | [밝음](images/autoscale-light.jpg) · [어두움](images/autoscale-dark.jpg) |
| `scale-recovery` | 부분 확장 복구 | ScaleKubernetesCluster.vue / scaleKubernetesCluster | [밝음](images/scale-recovery-light.jpg) · [어두움](images/scale-recovery-dark.jpg) |
| `upgrade` | 클러스터 업그레이드 | UpgradeKubernetesCluster.vue / upgradeKubernetesCluster | [밝음](images/upgrade-light.jpg) · [어두움](images/upgrade-dark.jpg) |
| `affinity` | Affinity 그룹 변경 | ChangeKubernetesClusterAffinity.vue / updateKubernetesClusterAffinityGroups | [밝음](images/affinity-light.jpg) · [어두움](images/affinity-dark.jpg) |
| `add-nodes` | 외부 노드 추가 | KubernetesAddNodes.vue / addNodesToKubernetesCluster | [밝음](images/add-nodes-light.jpg) · [어두움](images/add-nodes-dark.jpg) |
| `remove-nodes` | 외부 노드 연결 해제 | KubernetesRemoveNodes.vue / removeNodesFromKubernetesCluster | [밝음](images/remove-nodes-light.jpg) · [어두움](images/remove-nodes-dark.jpg) |
| `delete-worker` | 워커 노드 삭제 | KubernetesServiceTab.vue / node Popconfirm / scaleKubernetesCluster(nodeids) | [밝음](images/delete-worker-light.jpg) · [어두움](images/delete-worker-dark.jpg) |
| `delete-cluster` | 클러스터 삭제 | compute.js / AutogenView API form / deleteKubernetesCluster | [밝음](images/delete-cluster-light.jpg) · [어두움](images/delete-cluster-dark.jpg) |
| `delete-external` | 외부 관리형 클러스터 삭제 | compute.js / AutogenView API form / deleteKubernetesCluster | [밝음](images/delete-external-light.jpg) · [어두움](images/delete-external-dark.jpg) |
| `bulk-start` | 선택한 클러스터 시작 | compute.js / ListView group action / startKubernetesCluster | [밝음](images/bulk-start-light.jpg) · [어두움](images/bulk-start-dark.jpg) |
| `bulk-stop` | 선택한 클러스터 중지 | compute.js / ListView group action / stopKubernetesCluster | [밝음](images/bulk-stop-light.jpg) · [어두움](images/bulk-stop-dark.jpg) |
| `bulk-delete` | 선택한 클러스터 삭제 | compute.js / ListView group action / deleteKubernetesCluster | [밝음](images/bulk-delete-light.jpg) · [어두움](images/bulk-delete-dark.jpg) |
| `iso-register` | Kubernetes ISO URL 등록 | AddKubernetesSupportedVersion.vue / addKubernetesSupportedVersion | [밝음](images/iso-register-light.jpg) · [어두움](images/iso-register-dark.jpg) |
| `iso-upload` | Kubernetes ISO 파일 업로드 | AddKubernetesSupportedVersion.vue / getUploadParamsForKubernetesSupportedVersion | [밝음](images/iso-upload-light.jpg) · [어두움](images/iso-upload-dark.jpg) |
| `iso-state` | 지원 버전 상태 변경 | UpdateKubernetesSupportedVersion.vue / updateKubernetesSupportedVersion | [밝음](images/iso-state-light.jpg) · [어두움](images/iso-state-dark.jpg) |
| `iso-delete` | 지원 버전 등록 삭제 | image.js / AutogenView API form / deleteKubernetesSupportedVersion | [밝음](images/iso-delete-light.jpg) · [어두움](images/iso-delete-dark.jpg) |
| `network-tags` | 네트워크 규칙 태그 편집 | FirewallRules.vue / PortForwarding.vue / createTags | [밝음](images/network-tags-light.jpg) · [어두움](images/network-tags-dark.jpg) |
| `pf-vm` | 포트 포워딩 VM 선택 | PortForwarding.vue / createPortForwardingRule | [밝음](images/pf-vm-light.jpg) · [어두움](images/pf-vm-dark.jpg) |
| `rules-delete` | 선택한 네트워크 규칙 삭제 | FirewallRules.vue / PortForwarding.vue / BulkActionView.vue / deleteFirewallRule / deletePortForwardingRule | [밝음](images/rules-delete-light.jpg) · [어두움](images/rules-delete-dark.jpg) |
| `comment-delete` | 코멘트 삭제 | AnnotationsTab.vue / Popconfirm / removeAnnotation | [밝음](images/comment-delete-light.jpg) · [어두움](images/comment-delete-dark.jpg) |
| `comment-visibility` | 코멘트 공개 범위 변경 | AnnotationsTab.vue / Popconfirm / updateAnnotationVisibility | [밝음](images/comment-visibility-light.jpg) · [어두움](images/comment-visibility-dark.jpg) |
| `upgrade-empty` | 업그레이드 가능 버전 없음 | UpgradeKubernetesCluster.vue / upgradeKubernetesCluster | [밝음](images/upgrade-empty-light.jpg) · [어두움](images/upgrade-empty-dark.jpg) |
| `validation-error` | ISO 등록 입력 오류 | AddKubernetesSupportedVersion.vue / addKubernetesSupportedVersion | [밝음](images/validation-error-light.jpg) · [어두움](images/validation-error-dark.jpg) |

소스 경로: 구성은 `ui/src/config/section/compute.js`, `image.js`; 화면은 `ui/src/views/compute/`, `views/image/`, `views/network/`; 공통은 `ui/src/components/view/`, `ui/src/layouts/ResourceLayout.vue`, `ui/src/views/AutogenView.vue`이다. VM 생성 레퍼런스는 `ui/src/views/compute/DeployVM.vue`이다.

이벤트·LB·주소·AutoScaler 관측 등 서버에 없는 필드를 mock 데이터처럼 운영에서 채우지 않는다. 필요한 응답 계약이 없는 경우 기능 표시는 관측 범위를 명시하고, backend 확장 필요 여부를 기존 해당 이슈에서 관리한다.
