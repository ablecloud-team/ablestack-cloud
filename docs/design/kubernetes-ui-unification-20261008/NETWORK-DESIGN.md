# 네트워크 공통 UI 표준과 이벤트·코멘트 재사용

## 공통 변경 범위

방화벽·포트 포워딩·로드밸런서의 기존 공통 화면을 VM 상세 표준으로 변경한다. Kubernetes 전용 레이아웃을 추가하거나 기존 네트워크 폼을 수정 없이 사용하는 것이 아니다. 공인 IP/네트워크 상세와 Kubernetes 상세가 개선된 공통 툴바·설정 폼·규칙 목록·행 작업·페이지네이션을 공유한다.

실행 목업은 `NetworkRulesTabLayout.vue`와 `NetworkRuleForm.vue`를 양쪽 컨텍스트에서 직접 재사용한다. `ip-firewall`, `ip-portforwarding`, `ip-loadbalancers`는 공인 IP 상세에서 같은 컴포넌트를 보여 주는 비교 화면이다. 공인 IP 상세는 현재 IP에 고정되고, Kubernetes 상세는 해당 클러스터 네트워크에 속한 실제 공인 IP 목록을 조회한다. 운영 구현은 기존 `FirewallRules.vue`, `PortForwarding.vue`, `LoadBalancing.vue`의 기능 어댑터를 보존하고 공통 표시 구조를 추출/적용하는 방식으로 진행한다. 구체적 파일 분할은 구현 PR에서 결정한다.

| 영역 | 공통 표준 |
| --- | --- |
| 상단 툴바 | 콘텐츠 최상단 좌측에 Plus 아이콘 + 파란 추가 버튼, Reload 아이콘 + 업데이트. 선택 규칙 작업도 상단, 검색은 우측. wrap/gap/여백은 DetailTab.scss |
| 컨텍스트 | 네트워크/VPC 티어, 공인 IP, 계정·프로젝트·Zone을 실제 범위에서 표시. 공인 IP 상세의 IP는 고정 |
| 입력 | MoldDialog + AForm vertical, 20px gutter, desktop 2열/mobile 1열. 필수·단위·조건별 입력·항목 아래 도움말/오류 |
| 목록 | 작은 상세 목록, 선택 열 30px, 필수 정보·소유권·상태·행 작업. 표 내부 가로 스크롤 허용 |
| 행 작업 | 설정, 추가 작업 메뉴(VM/NIC·세션·SSL·태그·삭제). 지원 기능·권한·소유권에 따라 노출/비활성 |
| 상세 페이지네이션 | 오른쪽 mini, 전체/표시 범위·페이지·페이지 크기·이동하기. 메인 목록의 왼쪽 페이지네이션과 구분 |
| 작업 완료 | API 접수와 async job 완료 구분, 목록/대상 재조회. 실패 시 입력과 마지막 정상 목록 보존 |

## 설정/API 계약

아래 API는 Europa 기준 소스의 Java Command와 현재 공통 Vue 화면을 확인했다. 목업에서는 운영 API를 호출하지 않는다.

| 작업 / 목업 | API 및 보존할 인자 | 제한 / 실패 처리 |
| --- | --- | --- |
| 방화벽 추가 `firewall-create` | createFirewallRule: ipaddressid, protocol, cidrlist, startport/endport 또는 icmptype/icmpcode | TCP/UDP와 ICMP 입력 분기, CIDR/포트 검증, 관리 포트 보호 |
| 방화벽 변경 `firewall-replace` | deleteFirewallRule → createFirewallRule | updateFirewallRule은 표시 여부/식별자 변경용이다. 포트/CIDR 변경을 직접 update로 구현하지 않는다. 중단 가능성을 알리고 실패 단계·기존/신규 UUID·복구할 이전 설정을 보존 |
| 포트 포워딩 추가 `pf-create` | createPortForwardingRule: ipaddressid, networkid, protocol, publicport/publicendport, privateport/privateendport, virtualmachineid, vmguestip | VM/NIC는 같은 실제 네트워크·계정·프로젝트 범위. 기존 openfirewall=false 정책 유지. VPC의 CIDR 및 티어 제약 유지 |
| 포트 포워딩 변경 `pf-edit` | updatePortForwardingRule: id, privateport/privateendport, virtualmachineid, vmguestip, VPC cidrlist | 공인 포트·프로토콜은 변경 인자가 아니다. 비활성 표시, 변경 필요 시 교체 흐름으로 안내 |
| 로드밸런서 생성 `lb-create` | createLoadBalancerRule: publicipid, networkid, name/description, publicport/privateport, algorithm, protocol, cidrlist, backendssl | 규칙 생성 → assignToLoadBalancerRule → SSL일 때 assignCertToLoadBalancer. 단계별 결과와 생성된 규칙을 보존하고 실패를 성공으로 표시하지 않음 |
| 로드밸런서 변경 `lb-edit` | updateLoadBalancerRule: id, name/description, algorithm, protocol, cidrlist, backendssl | 포트는 변경 인자가 아니다. 인증서 연결은 별도 API/별도 작업으로 제공 |
| 대상 연결/해제 `lb-backends`, `lb-backend-remove` | assignToLoadBalancerRule / removeFromLoadBalancerRule: id, vmidipmap의 vmid/vmip | VM/NIC 목록·현재 연결 상태 재조회. 연결 해제는 VM 삭제가 아님. 지원 없는 가중치 필드를 추가하지 않음 |
| 세션 유지 `lb-stickiness` | listLBStickinessPolicies, listLBStickinessMethods, createLBStickinessPolicy, deleteLBStickinessPolicy | Provider가 반환한 method/params 메타데이터로 필드를 구성. 목업의 LbCookie/AppCookie/SourceBased는 예시이며 지원 없는 방식을 활성화하지 않음 |
| SSL `lb-tls` | listSslCerts, assignCertToLoadBalancer / removeCertFromLoadBalancer | 인증서 연결·해제와 backendssl 변경을 분리. backendssl은 규칙 설정에서 updateLoadBalancerRule로 처리 |
| 삭제 `firewall-delete`, `pf-delete`, `lb-delete` | deleteFirewallRule / deletePortForwardingRule / deleteLoadBalancerRule | 대상 IP·프로토콜·포트·VM을 표시하고 관리/API/SSH/Provider 규칙 및 조회 실패 상태의 변경을 차단 |
| 태그 / VM 선택 | 기존 TagsTab/선택 데이터 계약 | 실제 설정 기능의 보조 작업. 태그 화면만으로 설정 작업을 대신하지 않음 |

일반 공통 화면의 기존 VPC, 네트워크 offering, Provider, 권한 분기를 보존한다. VPC 방화벽은 ACL 어댑터 및 ACL 우선순위/action/trafficType 계약을 사용하며 격리 네트워크의 createFirewallRule을 대신 호출하지 않는다. VPC의 IP가 아직 네트워크에 연결되지 않았다면 실제 티어를 선택한다. Shared/Direct routing에서 해당 기능을 제공하지 않으면 ‘지원하지 않는 네트워크 유형’으로 표시한다.

LoadBalancing.vue의 Netris 알고리즘/프로토콜, TungstenFabric Health Check, AutoScale VM Group 등의 기존 조건별 기능을 삭제하지 않는다. 실제 Provider capability/API metadata에서 지원을 확인한 경우에만 해당 입력을 표시한다. Kubernetes Cluster AutoScaler와 로드밸런서 AutoScale VM Group은 서로 다른 기능이며 같은 제어로 합치지 않는다. 이 문서의 기본 목업은 격리형 IPv4 예시이며 모든 Provider/VPC 조합의 실행 검증을 뜻하지 않는다.

## Kubernetes 컨텍스트의 보호와 소유권

현재 KubernetesServiceTab은 방화벽·포트 포워딩 공통 컴포넌트를 이미 사용하지만 로드밸런서는 별도 읽기 전용 목록이다. 개선 시 읽기 전용 목록을 공통 로드밸런서 기능 어댑터와 연결하되 보호 기준을 먼저 유지한다.

- 실제 네트워크/IP·account/domain 또는 project 범위를 확인한다. 이름이나 6443 포트만으로 소유권을 판정하지 않는다.
- 관리/API/SSH 규칙과 유효한 Service UID·cluster/network/IP UID·allocation generation 등 Provider 소유권을 보호한다. API LB는 정확한 제어 노드 backend 집합 등 기존 소유권 검사도 유지한다.
- `kubernetesLoadBalancers.js`의 owner=null을 ‘안전한 사용자 규칙’으로 간주하지 않는다. 전체 보호 인벤토리와 Cloud 소유 범위를 검증한 수동 규칙에만 변경을 허용한다. 확인 불가 항목은 보호/확인 필요 상태로 표시한다.
- Provider가 생성한 LoadBalancer Service는 Kubernetes 서비스 관리 경로를 안내하고 Mold에서 임의로 대상·포트·인증서·태그를 변경하지 않는다.
- `network-unavailable`은 마지막 목록을 유지하되 추가·설정·선택·삭제를 비활성화한다. 부분 조회/권한 오류를 빈 정상 목록으로 표현하지 않는다.

## 이벤트·코멘트: 기존 공통 컴포넌트 그대로

`EventsTab.vue`(+ListView)와 `AnnotationsTab.vue`는 이미 표준을 적용한 공통 컴포넌트다. 운영 소스는 수정하지 않는다. 목업도 이 파일을 직접 import하며 복제한 이벤트 표/코멘트 폼과 추가 툴바/페이지네이션을 제거했다.

이벤트는 기존 업데이트·열 선택·정렬·이벤트 기록 안내·상태 표현·상세 페이지네이션을 그대로 사용한다. 코멘트는 기존 보내기 버튼·업데이트·입력·관리자 공개 범위·작성자/시간·공개 범위 변경·삭제 확인 및 10개 고정 페이지네이션을 그대로 사용한다. 코멘트의 기존 ‘보내기’ 버튼에는 아이콘을 임의로 추가하지 않는다. 공통 레이아웃 그대로 사용하라는 요구가 해당 탭의 기준이며 새로운 일반 주 버튼 규칙으로 기존 동작을 덮어쓰지 않는다.

목업용 fixture-api.js는 API 모듈에 우선하는 **별도 엔트리의 exact alias**다. HTTP/axios/fetch/자격증명/프록시가 없으며 지원하지 않는 호출은 실패한다. 이벤트·코멘트 데이터와 코멘트 저장/공개 범위/삭제는 메모리 예시에만 적용하고 페이지를 새로 열면 초기화한다. 운영 API/공통 파일에는 alias나 fixture를 추가하지 않았다. 운영 store/locale를 로딩하는 plugins 모듈은 표시 경로의 순수 helper만 별도 목업 어댑터로 공급한다. 실행하지 않는 QuickView와 리소스 아이콘은 목업 등록용 placeholder이며 공통 이벤트·코멘트의 화면/동작은 실제 컴포넌트다.

## 다크모드 대화상자 안내 텍스트

방화벽 대화상자의 AForm 도움말을 실제 브라우저로 확인했을 때 `rgba(0,0,0,0.45)`가 다크 배경에 남아 읽기 어려웠다. `theme-guidance.css`에서 현재 테마의 `.mold-dialog` `ant-form-item-explain`, `ant-form-item-extra`, 단계 안내, 업로드 안내를 공통 `--ui-text-secondary`로 표현했다. 오류·경고·성공 도움말은 각각의 의미 텍스트 토큰을 유지한다. Alert의 의미 색상은 기존 테마를 그대로 사용한다.

이는 설계 목업에 반영한 공통 테마 수정안이며 실제 적용 위치는 운영 구현의 공통 dark-mode/theme 경로다. 대화상자별 검정색을 덮어 쓰거나 Kubernetes 전용 CSS로 제한하지 않는다. 이벤트·코멘트 공통 컴포넌트의 내부 스타일은 변경하지 않는다. 라이트·다크 모든 대화상자에 대해 안내 요소의 실제 글자색/배경 합성 대비를 기록하고 일반 안내의 최소 대비 4.5:1을 확인한다. 본문 스크롤 아래의 안내와 포커스/disabled/오류 상태의 실제 운영 회귀는 구현 수용 기준에 포함한다.

## 후속 구현 수용 기준

- [ ] 기존 방화벽/PF/LB 공통 표시 구조를 VM 상세 표준으로 개선하고 일반 네트워크/IP 및 Kubernetes 화면에 함께 적용한다.
- [ ] 현재 API·VPC·Provider·RBAC·태그·AutoScale VM Group·Health Check 조건을 보존한다.
- [ ] 조회 실패/unknown ownership/관리 포트/Provider Service의 변경 차단을 실제 응답으로 검증한다.
- [ ] 각 설정·연결·교체·삭제의 async 단계/부분 실패/재시도/재조회와 네트워크 트래픽을 시험한다.
- [ ] EventsTab/AnnotationsTab은 수정 없이 그대로 사용하고, 기존 입력·열 선택·공개 범위·페이지네이션을 회귀 확인한다.
- [ ] 공통 다크모드 도움말/오류/Alert 대비를 VM 및 다른 MoldDialog 화면에서도 검증한다.
- [ ] 31번 별도 시험 클러스터/GFS2 Primary에서 수행하고 기존 장기 시험 클러스터를 유지한다.

## 포트 포워딩 목적별 입력 그룹

추가/변경 폼은 프로토콜 1행 → **공인 포트 시작/끝 1행** → **게스트 포트 시작/끝 1행** → **대상 VM/NIC 1행** 순서로 묶는다. 공인 포트·게스트 포트·연결 대상 제목을 표시하고, 관련 없는 항목이 한 행에 섞이지 않도록 한다. 방화벽 설정은 마지막 별도 그룹이다. 모바일에서는 각 그룹 안의 두 항목을 세로로 쌓고 그룹의 경계와 순서를 유지한다. NetworkRuleForm의 그룹 metadata를 공유하며 추가/변경에 각각 별도 레이아웃을 복제하지 않는다.

## 로드밸런서 대상 선택과 규칙만 생성

`createLoadBalancerRule`에는 VM 대상 인자가 없다. `assignToLoadBalancerRule`의 virtualmachineids 또는 vmidipmap에 **명시한 VM/IP**를 전달해 별도 연결한다. 기존 LoadBalancing.vue 역시 listVirtualMachines(networkid) → 선택한 VM의 listNics(virtualmachineid, networkid) → 선택한 기본/보조 IP만 vmidipmap에 넣는다. VPC Conserve Mode의 vmnetworkid도 유지한다. VM을 선택하지 않으면 assign 호출을 건너뛴다.

목업의 기본 경로는 ‘VM 선택 후 연결’이며 체크된 VM은 **0개**다. VM 검색 → 행 선택 → 해당 VM의 NIC 기본/보조 IP 선택 → 선택한 VM/IP 개수 확인 → ‘규칙 생성 및 VM 연결’이다. VM/IP 선택이 없으면 이 경로의 제출을 비활성화한다. 표 머리글 전체 선택은 표시된 후보에만 적용하며 모든 서버 VM에 대한 포괄 연결을 의미하지 않는다. 선택된 VM마다 최소 1개 NIC 주소가 필요하다.

서버 API가 허용하는 대상 없는 생성은 ‘규칙만 생성 · 나중에 연결’로 명시적으로 선택한다. VM 후보 표 대신 대상 없이 생성한다는 안내와 ‘규칙만 생성’ 버튼을 표시한다. 이후 ‘대상 VM·NIC’ 공통 작업에서 연결한다. 선택된 대상이 없어도 모든 VM을 자동으로 연결하거나 서비스가 동작한다고 표시하지 않는다. 이는 API의 선택 인자와 UI 경로별 제출 조건을 구분한 설계다.

VM 연결 작업은 기존 VM 연결만 수행한다. VM 새 배포를 로드밸런서 API의 기능으로 표현하지 않는다. 후보/주소의 RBAC·계정·프로젝트·네트워크 검증, 후보 페이지 간 선택 보존, secondary IP 소유 검증, 연결 API 실패 후 생성된 규칙 보존·재시도는 운영 구현에서 검증한다.

확인 소스: [현재 공통 LoadBalancing](../../../ui/src/views/network/LoadBalancing.vue), [생성 Command](../../../api/src/main/java/org/apache/cloudstack/api/command/user/loadbalancer/CreateLoadBalancerRuleCmd.java), [대상 연결 Command](../../../api/src/main/java/org/apache/cloudstack/api/command/user/loadbalancer/AssignToLoadBalancerRuleCmd.java).

### 로드밸런서 다크 컨트롤 상태 보완

VM 선택 후 직접 확인한 다크 화면에서 미선택 VM의 disabled NIC 선택 입력에 밝은 배경이 남고, 선택 태그·행·라디오 상태에 서로 다른 색상이 섞였다. 공통 다크 대화상자 테마 수정안에 라디오 기본/선택 상태, multi-select의 선택 항목/삭제 아이콘, disabled 선택 입력/항목, 테이블 기본/선택/hover를 포함했다. 모두 기존 semantic 색상 토큰을 사용한다. 활성 입력·disabled 입력·선택 VM 행을 각각 구분하되 읽을 수 있는 텍스트를 유지한다. 이 수정은 모든 MoldDialog에 적용할 공통 테마 제안이며 LB 전용 색상을 만들지 않는다.

코멘트 공개 범위·삭제 확인의 별도 목업도 복제한 MoldDialog를 제거했다. 두 시나리오는 실제 AnnotationsTab의 코멘트 화면을 열며, 기존 공개 범위/삭제 액션을 눌렀을 때 기존 Popconfirm이 그대로 표시된다. 캡처도 이 실제 공통 확인창을 사용한다. 48개 대화상자/분기 상태에는 이 2개 Popconfirm 상태가 포함된다.

### 네트워크 입력 그룹의 글자 크기

구분 제목의 글자 크기는 확대하지 않는다. 폼 본문/필드 label과 동일한 14px, 굵기 600, 얇은 구분선으로 공인 포트·게스트 포트·연결 대상의 경계를 표현한다. 네트워크 공통 폼 및 LB 대상 선택 영역에 같은 group label을 적용한다. 색상은 기존 primary text/border 토큰이며 라이트·다크 모두 같은 크기다.

행 배치는 의미와 입력 길이에 맞춘다. LB의 이름/설명·공인/게스트 포트·알고리즘/프로토콜은 관련 쌍으로 배치하고, 길이가 긴 CIDR은 전체 너비를 사용한다. 남는 항목을 관련 없는 다른 필드와 억지로 한 행에 채우지 않는다. 그룹 간 여백·본문 정렬·선택 요약은 같은 공통 폼 규칙을 사용한다.
