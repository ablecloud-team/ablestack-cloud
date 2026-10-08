# VM 표준 작업 메뉴와 관리 유형별 가상머신 탭 설계

이 문서는 #1330의 설계 보완이다. 운영 UI/backend 변경이나 실제 VM 배포·가입 시험 결과가 아니다. 기준은 Europa c169d9a203f49ce07e038297873bc3c24cd8ffb4의 소스이며 별도 목업 엔트리에서 기존 공통 메뉴를 직접 가져온다.

## 공통 메뉴 구조

| 위치 | VM 표준 | Kubernetes 적용 |
| --- | --- | --- |
| 목록 행 | ListView의 행 우클릭 → ResourceContextMenu | 이클립스/빠른 작업 버튼 제거. 클릭 위치에 같은 메뉴, 선택된 행이 있으면 선택 범위 우선 |
| 상세 상단 작업 | AutogenView의 click dropdown → ActionButton(dataView) | 같은 dropdown 내용·컴포넌트·그룹·아이콘·위험 색상 |
| 좌측 정보 영역 | InfoCard의 contextmenu → ResourceContextMenu | 같은 메뉴 내용. 상세 탭 본문에는 버튼을 넣지 않음 |
| 메뉴 내용 | ActionButton → ResourceActionMenu + actionMenu 유틸 | 같은 API 권한·상태 필터, 범주 구분, 삭제 위험 색상 |

단건 메뉴 제목은 대상 이름, 다중 선택 메뉴는 첫 대상 이름과 추가 개수를 표시한다. 다중 선택은 지원되는 groupAction만 표시하며 한 대상이라도 부적합하면 실행을 막고 이유를 안내한다. Running은 중지/확장/업그레이드, Stopped는 시작/확장/Affinity 등 실제 action.show 규칙을 따른다. 외부 관리형에 관리형 시작·중지·확장·업그레이드를 노출하지 않는다. 메뉴 활성 여부는 API/RBAC/관리 유형/상태/진행 중 job을 재조회해서 판정하고 실행 직전에도 재검증한다.

ResourceContextMenu의 화면 경계 보정, 스크롤/resize/외부 클릭/Escape 닫기, 첫 항목 포커스와 원래 포커스 복귀를 그대로 사용한다. 목업 목록 행과 좌측 정보에는 Shift+F10/ContextMenu 키 진입도 제공한다. 터치 대체 접근은 후속 구현에서 접근성을 확인한다. 네트워크 규칙 내부의 명시적 설정/추가 작업 버튼은 이 리소스 목록 메뉴와 구분한다.

목업은 운영 메뉴 파일을 수정하지 않는다. App의 예시 action 모델만 공급하고 작업 선택은 기존 설계 대화상자로 이동한다. 실제 API 권한/비동기 실행 통과를 의미하지 않는다.

## 관리 유형별 주 작업과 API

| 유형/목적 | 가상머신 탭 버튼 | API/실제 효과 |
| --- | --- | --- |
| CloudManaged 관리형 VM 증설 | 클러스터 확장: 아이콘 + primary | scaleKubernetesCluster. 수동 size 또는 AutoScaler autoscalingenabled/minsize/maxsize. AutoScaler 사용 중 버튼은 자동 확장 범위 설정으로 이동 |
| CloudManaged 기존 VM을 외부 워커로 가입 | 외부 노드 추가: 기본 버튼 | addNodesToKubernetesCluster(id,nodeids,mountcksiso,manualupgrade). ISO/가입/Ready 확인을 수행하는 별도 async 작업 |
| ExternalManaged 기존 VM 등록 | 외부 노드 추가: 아이콘 + primary | addVirtualMachinesToKubernetesCluster(id,virtualmachineids,iscontrolnode). 기존 VM의 연결과 제어/워커 역할만 기록 |
| ExternalManaged 연결 해제 | 해당 행/선택 범위 연결 해제 | removeVirtualMachinesFromKubernetesCluster. 등록 관계와 VM/Kubernetes 삭제를 구분 |

CloudManaged 확장 허용은 Created/Running/Stopped 또는 실제 부분 확장 복구 조건이다. API 존재·Operate 권한·가용 상태를 확인한다. AutoScaler ON에서 고정 워커 수 입력을 그대로 제공하지 않으며 자동 확장 범위 변경과 수동 확장 전환을 분리한다. 부분 복구에서는 검증된 복구 크기와 남은 작업만 제공한다. 외부 워커 추가는 Running/Alert, 실행 중 VM, CKS 템플릿/기본 NIC 같은 네트워크, 미등록 VM, 권한·Affinity를 검증한다. 외부 관리형 연결은 노드 생성이나 kubeadm join을 의미하지 않는다.

현재 compute.js의 외부 워커 action.show는 CloudManaged만 노출하고 있다. ExternalManaged 주 작업에는 이 action을 재사용하지 않고 addVirtualMachines API 어댑터를 연결해야 한다. KubernetesClusterManagerImpl.isCommandSupported에서도 CloudManaged와 ExternalManaged의 지원 명령이 구분되어 있다. 이 차이는 구현 수용 기준이다.

ExternalManaged의 후보 VM은 listVirtualMachines로 소유자/프로젝트/Zone 및 접근 권한을 제한하고 기존 연결을 제외한다. 등록 역할 iscontrolnode는 false=워커(기본), true=제어이며 한 API 요청은 같은 역할의 VM ID 배열이다. 두 역할을 한 화면에서 선택할 경우 역할별 요청을 분리하고 부분 실패를 구분한다. 목업은 역할을 하나 선택한 후 해당 역할로 연결할 VM을 명시 선택하는 형태이다. 기본 VM 선택은 0개, 제출은 최소 하나 선택 후 활성이다. ExternalManaged 등록 관계에는 CloudManaged 외부 워커의 자동 가입/ISO/Ready 검증 완료 표시를 섞지 않는다.

## 외부 관리형 생성과 후속 연결

1. 기본 정보: 이름·설명·Zone·소유자. VM 자동 배포/실제 Kubernetes 설치를 하지 않는 등록임을 안내한다.
2. 선택 정보: 지원 버전·네트워크·SSH 키는 선택 메타데이터. 기본은 등록만, 선택하면 등록 후 외부 노드 추가를 계속한다. SSH/연결 선택은 별도 전체 행으로 배치한다.
3. 검토: 등록만 또는 등록 성공 후 VM·제어/워커 역할 연결을 구분한다. 등록 상태 Running은 실측 건강 상태가 아니다.
4. createKubernetesCluster(clustertype=ExternalManaged) 성공 응답의 UUID를 보존한다. 별도 external-vm-add에서 기존 VM/역할을 선택하고 addVirtualMachinesToKubernetesCluster를 호출한다. 생성 매개변수에 virtualmachineids/iscontrolnode를 잘못 포함하지 않는다.
5. 연결 실패는 클러스터 생성 실패와 구분한다. 등록 UUID와 성공한 VM/역할 연결을 유지하고 미완료 연결만 재시도한다. 다시 create를 호출하거나 등록/VM을 자동 삭제하지 않는다. 실제 Kubernetes 가입은 외부 운영자가 수행·검증한다.

목업의 등록 후 VM 연결 버튼은 이 흐름을 보여 주는 시나리오 이동이다. 서버 생성 성공이나 API 작업 실행 증거가 아니다. 후보 조회 실패/빈 후보/권한 없음/이미 연결/일부 역할 연결 실패는 구현 단계에서 동일한 공통 피드백과 재조회 규칙으로 검증한다.

## 아이콘·콘텐츠 가독성

목록의 Kubernetes SVG는 고정 검정 대신 currentColor와 공통 테마 텍스트 색상을 사용한다. 일반/줄무늬/hover/선택/disabled 행 모두 아이콘이 사라지지 않아야 한다. 라이트·다크 목업, 다크 일반/선택 행에서 확인했다. 18px 원래 크기를 유지하며 글자 확대나 필터 반전으로 해결하지 않는다.

네트워크 대화상자의 구분 제목과 라벨은 14px로 통일한다. 굵기 600·1px 선·일정한 여백으로 경계를 표시하고 포트 범위/VM·NIC처럼 목적이 같은 입력만 쌍으로 배치한다. 긴 CIDR은 전체 행을 사용한다. 기존 공통 메뉴 그룹은 기존 표준의 크기/굵기/간격을 그대로 준수한다.

## 기준 소스

- [ListView](../../../ui/src/components/view/ListView.vue): handleGlobalContextMenu, contextMenuActions, 선택 범위 우선.
- [InfoCard](../../../ui/src/components/view/InfoCard.vue): handleContextMenu 및 exec-action.
- [AutogenView](../../../ui/src/views/AutogenView.vue): 상세 작업 dropdown/ActionButton.
- [ResourceContextMenu](../../../ui/src/components/view/ResourceContextMenu.vue), [ResourceActionMenu](../../../ui/src/components/view/ResourceActionMenu.vue), [ActionButton](../../../ui/src/components/view/ActionButton.vue), [그룹 유틸](../../../ui/src/utils/actionMenu.js).
- [compute 설정](../../../ui/src/config/section/compute.js), [확장 폼](../../../ui/src/views/compute/ScaleKubernetesCluster.vue), [기존 외부 워커 폼](../../../ui/src/views/compute/KubernetesAddNodes.vue).
- [Manager/API 지원·VM 매핑·검증](../../../plugins/integrations/kubernetes-service/src/main/java/com/cloud/kubernetes/cluster/KubernetesClusterManagerImpl.java).
- [외부 관리형 VM 연결 API](../../../plugins/integrations/kubernetes-service/src/main/java/org/apache/cloudstack/api/command/user/kubernetes/cluster/AddVirtualMachinesToKubernetesClusterCmd.java), [관리형 외부 워커 가입 API](../../../plugins/integrations/kubernetes-service/src/main/java/org/apache/cloudstack/api/command/user/kubernetes/cluster/AddNodesToKubernetesClusterCmd.java), [가입 Worker](../../../plugins/integrations/kubernetes-service/src/main/java/com/cloud/kubernetes/cluster/actionworkers/KubernetesClusterAddWorker.java).

## 구현 수용 기준

- [ ] 이클립스 제거, 목록/상세 작업/좌측 정보의 공통 메뉴 공유 및 실행 대상 일치.
- [ ] API 권한/관리 유형/상태/진행 job/다중 선택 필터와 실행 직전 검증.
- [ ] CloudManaged 수동/자동/복구 확장과 기존 VM 가입, ExternalManaged VM 역할 연결을 각각 올바른 API로 처리.
- [ ] 외부 생성 성공 후 역할별 VM 연결 및 부분 실패 재시도; 중복 클러스터 생성·자동 VM 삭제 없음.
- [ ] 공통 컴포넌트 실제 운영 통합, 두 테마/키보드/화면 경계/포커스와 VM 목록·상세 회귀 검증.
- [ ] SVG 가독성과 네트워크 콘텐츠 그룹 표준, 이벤트/코멘트 기존 공통 레이아웃 유지.
