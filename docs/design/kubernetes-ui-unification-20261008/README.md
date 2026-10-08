# Europa Kubernetes UI 통합 설계와 실행 가능한 목업

현재 Mold의 Vue 3 / Ant Design Vue 개발 환경에서 VM 목록·상세·생성 화면을 기준으로 Kubernetes UI를 검토하는 설계 산출물입니다. 운영 UI 구현과 배포는 후속 작업입니다.

- 기준 소스: `upstream/ablestack-europa`, `c169d9a203f49ce07e038297873bc3c24cd8ffb4`.
- [네트워크 공통/API 상세 설계](NETWORK-DESIGN.md), [통합 설계](DESIGN.md), [검증 기록](VALIDATION.md), [화면·대화상자 목록](prototype/scenes.json), [레이아웃 관측 원본](render-checks.json).
- 17개 화면 + 48개 대화상자 + 11개 메뉴·확인 상태 = 76개 시나리오. 데스크톱 두 테마 152장, 모바일 대표 18장, 복수 페이지 4장, ICMP/SSL/선택 VM/규칙만 생성 분기 8장(총 182장).
- 이미지와 목업 데이터는 예시 계정·클러스터·문서용 IP만 사용합니다. 클러스터 31의 운영 스크린샷이나 자격증명은 포함하지 않습니다.

## VM 레퍼런스의 적용 결과

![클러스터 목록](images/list-light.jpg)

![클러스터 상세](images/detail-dark.jpg)

![클러스터 생성: HA·외부 etcd](images/create-ha-etcd-light.jpg)

## 동일한 UI 개발 환경

새 프레임워크나 CDN을 추가하지 않습니다. 체크아웃의 `ui/package.json`, `ui/package-lock.json`, `ui/vue.config.js`, Babel 설정을 그대로 사용하고, 별도 엔트리만 이 문서 아래에 둡니다.

| 구성 | 잠금 파일 / 설치 확인 버전 |
| --- | --- |
| Vue | 3.2.37 |
| Ant Design Vue | 3.2.20 |
| Vue CLI Service | 4.5.19 |
| Webpack | 4.46.0 |
| Ant Design Icons Vue | 7.0.1 |
| 검증 런타임 | Node 20.20.2 / npm 10.8.2 |

실제 공통 `EventsTab`, `AnnotationsTab`, `ListView`, `MoldDialog`, `ResourceLayout`, `vars.less`, `index.less`와 테마 토큰을 가져옵니다. `InfoCard` 자체는 운영 store/API 의존성이 있으므로 이 목업에서는 같은 시각 구조를 재현합니다. 운영 구현은 기존 `InfoCard`를 재사용합니다. 앱 셸과 데이터 테이블은 검토용 샘플이며 운영 `AutogenView`를 대체하지 않습니다. 768px 미만 열의 전체 너비와 어두운 테마 버튼 의미 색상은 목업에서 공통 토큰으로 보정한 제안입니다. 운영 공통 컴포넌트는 변경하지 않았습니다.

### 실행

이 설계 브랜치를 포함하는 클론/포크에서 실행합니다. WSL에서는 `/home/...`의 ext4 체크아웃을 사용합니다. Windows 체크아웃은 Windows Node로 실행합니다.

```bash
# 저장소 루트에서
cd ui
npm ci
NODE_OPTIONS=--openssl-legacy-provider \
VUE_CLI_SERVICE_CONFIG_PATH=../docs/design/kubernetes-ui-unification-20261008/prototype/vue.config.js \
npm run serve -- --host 127.0.0.1 --port 8775
```

Windows PowerShell:

```powershell
cd ui
npm ci
$env:NODE_OPTIONS='--openssl-legacy-provider'
$env:VUE_CLI_SERVICE_CONFIG_PATH='../docs/design/kubernetes-ui-unification-20261008/prototype/vue.config.js'
npm run serve -- --host 127.0.0.1 --port 8775
```

[전체 목업 갤러리](http://127.0.0.1:8775/?s=gallery)를 열고 화면과 테마를 선택합니다. 개별 화면은 `/?s=upgrade&theme=dark`처럼 열 수 있습니다. `capture=1`은 검토용 선택 바와 애니메이션을 감추는 캡처 모드입니다.

```bash
# ui 디렉터리에서 목업 엔트리만 빌드
NODE_OPTIONS='--openssl-legacy-provider --max-old-space-size=6144' \
VUE_CLI_SERVICE_CONFIG_PATH=../docs/design/kubernetes-ui-unification-20261008/prototype/vue.config.js \
npm run build
```

출력은 체크아웃의 형제 디렉터리 `kubernetes-ui-design-dist-20261008/`입니다. Full Cloud/Maven 빌드가 아닙니다. 기존 UI 전체 의존성과 공통 public 자산을 사용하는 검토용 빌드이므로 번들 크기 최적화는 이번 결과의 범위가 아닙니다.

목업은 운영 API 클라이언트나 프록시를 연결하지 않습니다. 일반 작업 버튼은 시나리오 이동/안내만 표시합니다. 기존 이벤트·코멘트는 실제 공통 컴포넌트를 직접 사용하며 별도 엔트리의 HTTP 없는 fixture alias로 메모리 예시를 연결합니다. 코멘트 보내기/공개 범위/삭제는 예시 배열에만 반영하고 재접속 시 초기화합니다. 입력·선택 컴포넌트는 조작할 수 있지만 실제 검증·상태 전이·권한 제어·비동기 작업 성공을 증명하는 도구는 아닙니다.


## 네트워크·공통 탭·가독성 보완

네트워크 공통 레이아웃 자체를 VM 상세 표준으로 개선하는 설계입니다. 공인 IP와 Kubernetes에서 같은 NetworkRulesTabLayout/NetworkRuleForm을 직접 재사용합니다. 설정 API와 보호/부분 실패 계약은 [네트워크 상세 설계](NETWORK-DESIGN.md)에 기록했습니다.

포트 포워딩은 프로토콜 → 공인 시작/종료 → 게스트 시작/종료 → 대상 VM/NIC 그룹으로 배치합니다. 로드밸런서는 기본 VM 선택 0개, 선택한 VM의 NIC IP만 연결하며 규칙만 생성하는 경로를 별도로 제공합니다. 상세 탭 본문은 버튼 없이 정보만 표시하고 이벤트·코멘트는 기존 공통 컴포넌트를 수정 없이 사용합니다.

다크 대화상자의 도움말·라디오·multi-select/disabled NIC·테이블 기본/선택/hover는 공통 테마 토큰 수정안으로 보완했습니다. 운영 공통 테마 반영은 후속 구현 범위입니다.

## 목록·상세 작업과 관리 유형별 노드 기능

[공통 작업 메뉴와 외부 관리형 API 설계](ACTION-DESIGN.md)를 참고합니다. 목록의 이클립스 버튼은 제거했습니다. 행 우클릭 또는 Shift+F10으로 실제 ResourceContextMenu를 열고, 상세 상단 작업 버튼은 AutogenView와 같은 dropdown/ActionButton을 사용합니다. 좌측 정보 영역 우클릭도 같은 공통 메뉴입니다. 갤러리의 메뉴 시나리오는 이름에 표시된 우클릭/작업 클릭으로 직접 열 수 있습니다. Escape/외부 클릭으로 닫습니다.

가상머신 탭은 Mold 관리형의 클러스터 확장이 주 버튼이며 기존 VM 가입은 별도 외부 노드 추가입니다. AutoScaler를 사용하면 자동 확장 범위 설정으로 이동합니다. 외부 관리형에서는 외부 노드 추가가 주 버튼이며 기존 Mold VM의 연결·제어/워커 역할만 등록합니다. 설치·가입·실제 확장은 외부 운영자가 수행합니다.

외부 관리형 생성은 기본 등록만/등록 후 VM 연결 계속하기를 구분합니다. 등록 성공 후 별도 VM·역할 선택이며 연결 실패 시 등록된 클러스터를 유지하고 연결만 재시도합니다. API 계약과 모형 검증은 실제 운영 API 검증과 구분합니다.

목록 Kubernetes SVG는 고정 검정 대신 currentColor/테마 텍스트 토큰으로 표시합니다. 밝은·어두운 테마와 선택 행을 확인했습니다. 네트워크 구분 제목/입력 라벨은 동일 14px이고 굵기·얇은 선·여백으로 그룹을 표현합니다. 긴 CIDR은 전체 행을 사용합니다.

## 이미지 카탈로그

<details>
<summary>클러스터 목록 · list</summary>

![클러스터 목록 라이트](images/list-light.jpg)

![클러스터 목록 다크](images/list-dark.jpg)

</details>

<details>
<summary>클러스터 상세 · detail</summary>

![클러스터 상세 라이트](images/detail-light.jpg)

![클러스터 상세 다크](images/detail-dark.jpg)

</details>

<details>
<summary>노드 목록 · nodes</summary>

![노드 목록 라이트](images/nodes-light.jpg)

![노드 목록 다크](images/nodes-dark.jpg)

</details>

<details>
<summary>액세스 안내 · access</summary>

![액세스 안내 라이트](images/access-light.jpg)

![액세스 안내 다크](images/access-dark.jpg)

</details>

<details>
<summary>서비스 부하 분산 · loadbalancers</summary>

![서비스 부하 분산 라이트](images/loadbalancers-light.jpg)

![서비스 부하 분산 다크](images/loadbalancers-dark.jpg)

</details>

<details>
<summary>방화벽 규칙 · firewall</summary>

![방화벽 규칙 라이트](images/firewall-light.jpg)

![방화벽 규칙 다크](images/firewall-dark.jpg)

</details>

<details>
<summary>포트 포워딩 · portforwarding</summary>

![포트 포워딩 라이트](images/portforwarding-light.jpg)

![포트 포워딩 다크](images/portforwarding-dark.jpg)

</details>

<details>
<summary>이벤트 · events</summary>

![이벤트 라이트](images/events-light.jpg)

![이벤트 다크](images/events-dark.jpg)

</details>

<details>
<summary>코멘트 · comments</summary>

![코멘트 라이트](images/comments-light.jpg)

![코멘트 다크](images/comments-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 목록 · iso-list</summary>

![Kubernetes ISO 목록 라이트](images/iso-list-light.jpg)

![Kubernetes ISO 목록 다크](images/iso-list-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 상세 · iso-detail</summary>

![Kubernetes ISO 상세 라이트](images/iso-detail-light.jpg)

![Kubernetes ISO 상세 다크](images/iso-detail-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-basic</summary>

![쿠버네티스 클러스터 생성 라이트](images/create-basic-light.jpg)

![쿠버네티스 클러스터 생성 다크](images/create-basic-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-nodes</summary>

![쿠버네티스 클러스터 생성 라이트](images/create-nodes-light.jpg)

![쿠버네티스 클러스터 생성 다크](images/create-nodes-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-advanced</summary>

![쿠버네티스 클러스터 생성 라이트](images/create-advanced-light.jpg)

![쿠버네티스 클러스터 생성 다크](images/create-advanced-dark.jpg)

</details>

<details>
<summary>클러스터 생성 · HA / 외부 etcd · create-ha-etcd</summary>

![클러스터 생성 · HA / 외부 etcd 라이트](images/create-ha-etcd-light.jpg)

![클러스터 생성 · HA / 외부 etcd 다크](images/create-ha-etcd-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-addons</summary>

![쿠버네티스 클러스터 생성 라이트](images/create-addons-light.jpg)

![쿠버네티스 클러스터 생성 다크](images/create-addons-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-review</summary>

![쿠버네티스 클러스터 생성 라이트](images/create-review-light.jpg)

![쿠버네티스 클러스터 생성 다크](images/create-review-dark.jpg)

</details>

<details>
<summary>클러스터 생성 · 외부 관리형 · create-external-basic</summary>

![클러스터 생성 · 외부 관리형 라이트](images/create-external-basic-light.jpg)

![클러스터 생성 · 외부 관리형 다크](images/create-external-basic-dark.jpg)

</details>

<details>
<summary>외부 관리형 · 선택 정보와 VM 연결 · create-external-options</summary>

![외부 관리형 · 선택 정보와 VM 연결 라이트](images/create-external-options-light.jpg)

![외부 관리형 · 선택 정보와 VM 연결 다크](images/create-external-options-dark.jpg)

</details>

<details>
<summary>외부 관리형 · 등록 검토 · create-external-review</summary>

![외부 관리형 · 등록 검토 라이트](images/create-external-review-light.jpg)

![외부 관리형 · 등록 검토 다크](images/create-external-review-dark.jpg)

</details>

<details>
<summary>클러스터 시작 · start</summary>

![클러스터 시작 라이트](images/start-light.jpg)

![클러스터 시작 다크](images/start-dark.jpg)

</details>

<details>
<summary>클러스터 중지 · stop</summary>

![클러스터 중지 라이트](images/stop-light.jpg)

![클러스터 중지 다크](images/stop-dark.jpg)

</details>

<details>
<summary>클러스터 확장 / 축소 · scale</summary>

![클러스터 확장 / 축소 라이트](images/scale-light.jpg)

![클러스터 확장 / 축소 다크](images/scale-dark.jpg)

</details>

<details>
<summary>오토스케일링 설정 · autoscale</summary>

![오토스케일링 설정 라이트](images/autoscale-light.jpg)

![오토스케일링 설정 다크](images/autoscale-dark.jpg)

</details>

<details>
<summary>부분 확장 복구 · scale-recovery</summary>

![부분 확장 복구 라이트](images/scale-recovery-light.jpg)

![부분 확장 복구 다크](images/scale-recovery-dark.jpg)

</details>

<details>
<summary>클러스터 업그레이드 · upgrade</summary>

![클러스터 업그레이드 라이트](images/upgrade-light.jpg)

![클러스터 업그레이드 다크](images/upgrade-dark.jpg)

</details>

<details>
<summary>Affinity 그룹 변경 · affinity</summary>

![Affinity 그룹 변경 라이트](images/affinity-light.jpg)

![Affinity 그룹 변경 다크](images/affinity-dark.jpg)

</details>

<details>
<summary>외부 노드 추가 · add-nodes</summary>

![외부 노드 추가 라이트](images/add-nodes-light.jpg)

![외부 노드 추가 다크](images/add-nodes-dark.jpg)

</details>

<details>
<summary>외부 노드 연결 해제 · remove-nodes</summary>

![외부 노드 연결 해제 라이트](images/remove-nodes-light.jpg)

![외부 노드 연결 해제 다크](images/remove-nodes-dark.jpg)

</details>

<details>
<summary>워커 노드 삭제 · delete-worker</summary>

![워커 노드 삭제 라이트](images/delete-worker-light.jpg)

![워커 노드 삭제 다크](images/delete-worker-dark.jpg)

</details>

<details>
<summary>클러스터 삭제 · delete-cluster</summary>

![클러스터 삭제 라이트](images/delete-cluster-light.jpg)

![클러스터 삭제 다크](images/delete-cluster-dark.jpg)

</details>

<details>
<summary>외부 관리형 클러스터 삭제 · delete-external</summary>

![외부 관리형 클러스터 삭제 라이트](images/delete-external-light.jpg)

![외부 관리형 클러스터 삭제 다크](images/delete-external-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 시작 · bulk-start</summary>

![선택한 클러스터 시작 라이트](images/bulk-start-light.jpg)

![선택한 클러스터 시작 다크](images/bulk-start-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 중지 · bulk-stop</summary>

![선택한 클러스터 중지 라이트](images/bulk-stop-light.jpg)

![선택한 클러스터 중지 다크](images/bulk-stop-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 삭제 · bulk-delete</summary>

![선택한 클러스터 삭제 라이트](images/bulk-delete-light.jpg)

![선택한 클러스터 삭제 다크](images/bulk-delete-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO URL 등록 · iso-register</summary>

![Kubernetes ISO URL 등록 라이트](images/iso-register-light.jpg)

![Kubernetes ISO URL 등록 다크](images/iso-register-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 파일 업로드 · iso-upload</summary>

![Kubernetes ISO 파일 업로드 라이트](images/iso-upload-light.jpg)

![Kubernetes ISO 파일 업로드 다크](images/iso-upload-dark.jpg)

</details>

<details>
<summary>지원 버전 상태 변경 · iso-state</summary>

![지원 버전 상태 변경 라이트](images/iso-state-light.jpg)

![지원 버전 상태 변경 다크](images/iso-state-dark.jpg)

</details>

<details>
<summary>지원 버전 등록 삭제 · iso-delete</summary>

![지원 버전 등록 삭제 라이트](images/iso-delete-light.jpg)

![지원 버전 등록 삭제 다크](images/iso-delete-dark.jpg)

</details>

<details>
<summary>네트워크 규칙 태그 편집 · network-tags</summary>

![네트워크 규칙 태그 편집 라이트](images/network-tags-light.jpg)

![네트워크 규칙 태그 편집 다크](images/network-tags-dark.jpg)

</details>

<details>
<summary>포트 포워딩 VM 선택 · pf-vm</summary>

![포트 포워딩 VM 선택 라이트](images/pf-vm-light.jpg)

![포트 포워딩 VM 선택 다크](images/pf-vm-dark.jpg)

</details>

<details>
<summary>선택한 네트워크 규칙 삭제 · rules-delete</summary>

![선택한 네트워크 규칙 삭제 라이트](images/rules-delete-light.jpg)

![선택한 네트워크 규칙 삭제 다크](images/rules-delete-dark.jpg)

</details>

<details>
<summary>코멘트 삭제 · comment-delete</summary>

![코멘트 삭제 라이트](images/comment-delete-light.jpg)

![코멘트 삭제 다크](images/comment-delete-dark.jpg)

</details>

<details>
<summary>코멘트 공개 범위 변경 · comment-visibility</summary>

![코멘트 공개 범위 변경 라이트](images/comment-visibility-light.jpg)

![코멘트 공개 범위 변경 다크](images/comment-visibility-dark.jpg)

</details>

<details>
<summary>업그레이드 가능 버전 없음 · upgrade-empty</summary>

![업그레이드 가능 버전 없음 라이트](images/upgrade-empty-light.jpg)

![업그레이드 가능 버전 없음 다크](images/upgrade-empty-dark.jpg)

</details>

<details>
<summary>ISO 등록 입력 오류 · validation-error</summary>

![ISO 등록 입력 오류 라이트](images/validation-error-light.jpg)

![ISO 등록 입력 오류 다크](images/validation-error-dark.jpg)

</details>

<details>
<summary>방화벽 규칙 추가 · firewall-create</summary>

![방화벽 규칙 추가 라이트](images/firewall-create-light.jpg)

![방화벽 규칙 추가 다크](images/firewall-create-dark.jpg)

</details>

<details>
<summary>방화벽 규칙 교체 · firewall-replace</summary>

![방화벽 규칙 교체 라이트](images/firewall-replace-light.jpg)

![방화벽 규칙 교체 다크](images/firewall-replace-dark.jpg)

</details>

<details>
<summary>포트 포워딩 규칙 추가 · pf-create</summary>

![포트 포워딩 규칙 추가 라이트](images/pf-create-light.jpg)

![포트 포워딩 규칙 추가 다크](images/pf-create-dark.jpg)

</details>

<details>
<summary>포트 포워딩 설정 변경 · pf-edit</summary>

![포트 포워딩 설정 변경 라이트](images/pf-edit-light.jpg)

![포트 포워딩 설정 변경 다크](images/pf-edit-dark.jpg)

</details>

<details>
<summary>로드밸런서 규칙 추가 · lb-create</summary>

![로드밸런서 규칙 추가 라이트](images/lb-create-light.jpg)

![로드밸런서 규칙 추가 다크](images/lb-create-dark.jpg)

</details>

<details>
<summary>로드밸런서 설정 변경 · lb-edit</summary>

![로드밸런서 설정 변경 라이트](images/lb-edit-light.jpg)

![로드밸런서 설정 변경 다크](images/lb-edit-dark.jpg)

</details>

<details>
<summary>로드밸런서 대상 VM·NIC 연결 · lb-backends</summary>

![로드밸런서 대상 VM·NIC 연결 라이트](images/lb-backends-light.jpg)

![로드밸런서 대상 VM·NIC 연결 다크](images/lb-backends-dark.jpg)

</details>

<details>
<summary>로드밸런서 대상 VM 연결 해제 · lb-backend-remove</summary>

![로드밸런서 대상 VM 연결 해제 라이트](images/lb-backend-remove-light.jpg)

![로드밸런서 대상 VM 연결 해제 다크](images/lb-backend-remove-dark.jpg)

</details>

<details>
<summary>로드밸런서 세션 유지 설정 · lb-stickiness</summary>

![로드밸런서 세션 유지 설정 라이트](images/lb-stickiness-light.jpg)

![로드밸런서 세션 유지 설정 다크](images/lb-stickiness-dark.jpg)

</details>

<details>
<summary>로드밸런서 SSL 인증서 연결 · lb-tls</summary>

![로드밸런서 SSL 인증서 연결 라이트](images/lb-tls-light.jpg)

![로드밸런서 SSL 인증서 연결 다크](images/lb-tls-dark.jpg)

</details>

<details>
<summary>방화벽 규칙 삭제 · firewall-delete</summary>

![방화벽 규칙 삭제 라이트](images/firewall-delete-light.jpg)

![방화벽 규칙 삭제 다크](images/firewall-delete-dark.jpg)

</details>

<details>
<summary>포트 포워딩 규칙 삭제 · pf-delete</summary>

![포트 포워딩 규칙 삭제 라이트](images/pf-delete-light.jpg)

![포트 포워딩 규칙 삭제 다크](images/pf-delete-dark.jpg)

</details>

<details>
<summary>로드밸런서 규칙 삭제 · lb-delete</summary>

![로드밸런서 규칙 삭제 라이트](images/lb-delete-light.jpg)

![로드밸런서 규칙 삭제 다크](images/lb-delete-dark.jpg)

</details>

<details>
<summary>공인 IP · 공통 방화벽 · ip-firewall</summary>

![공인 IP · 공통 방화벽 라이트](images/ip-firewall-light.jpg)

![공인 IP · 공통 방화벽 다크](images/ip-firewall-dark.jpg)

</details>

<details>
<summary>공인 IP · 공통 포트 포워딩 · ip-portforwarding</summary>

![공인 IP · 공통 포트 포워딩 라이트](images/ip-portforwarding-light.jpg)

![공인 IP · 공통 포트 포워딩 다크](images/ip-portforwarding-dark.jpg)

</details>

<details>
<summary>공인 IP · 공통 로드밸런서 · ip-loadbalancers</summary>

![공인 IP · 공통 로드밸런서 라이트](images/ip-loadbalancers-light.jpg)

![공인 IP · 공통 로드밸런서 다크](images/ip-loadbalancers-dark.jpg)

</details>

<details>
<summary>네트워크 조회 실패 · 설정 제한 · network-unavailable</summary>

![네트워크 조회 실패 · 설정 제한 라이트](images/network-unavailable-light.jpg)

![네트워크 조회 실패 · 설정 제한 다크](images/network-unavailable-dark.jpg)

</details>

<details>
<summary>클러스터 목록 · 실행 중 행 우클릭 · list-context</summary>

![클러스터 목록 · 실행 중 행 우클릭 라이트](images/list-context-light.jpg)

![클러스터 목록 · 실행 중 행 우클릭 다크](images/list-context-dark.jpg)

</details>

<details>
<summary>클러스터 목록 · 중지된 행 우클릭 · list-context-stopped</summary>

![클러스터 목록 · 중지된 행 우클릭 라이트](images/list-context-stopped-light.jpg)

![클러스터 목록 · 중지된 행 우클릭 다크](images/list-context-stopped-dark.jpg)

</details>

<details>
<summary>클러스터 목록 · 외부 관리형 우클릭 · list-context-external</summary>

![클러스터 목록 · 외부 관리형 우클릭 라이트](images/list-context-external-light.jpg)

![클러스터 목록 · 외부 관리형 우클릭 다크](images/list-context-external-dark.jpg)

</details>

<details>
<summary>클러스터 목록 · 다중 선택 우클릭 · list-context-multiple</summary>

![클러스터 목록 · 다중 선택 우클릭 라이트](images/list-context-multiple-light.jpg)

![클러스터 목록 · 다중 선택 우클릭 다크](images/list-context-multiple-dark.jpg)

</details>

<details>
<summary>클러스터 상세 · 작업 메뉴 · detail-actions</summary>

![클러스터 상세 · 작업 메뉴 라이트](images/detail-actions-light.jpg)

![클러스터 상세 · 작업 메뉴 다크](images/detail-actions-dark.jpg)

</details>

<details>
<summary>클러스터 상세 · 좌측 정보 우클릭 · detail-context</summary>

![클러스터 상세 · 좌측 정보 우클릭 라이트](images/detail-context-light.jpg)

![클러스터 상세 · 좌측 정보 우클릭 다크](images/detail-context-dark.jpg)

</details>

<details>
<summary>ISO 목록 · 행 우클릭 · iso-list-context</summary>

![ISO 목록 · 행 우클릭 라이트](images/iso-list-context-light.jpg)

![ISO 목록 · 행 우클릭 다크](images/iso-list-context-dark.jpg)

</details>

<details>
<summary>ISO 상세 · 작업 메뉴 · iso-detail-actions</summary>

![ISO 상세 · 작업 메뉴 라이트](images/iso-detail-actions-light.jpg)

![ISO 상세 · 작업 메뉴 다크](images/iso-detail-actions-dark.jpg)

</details>

<details>
<summary>ISO 상세 · 좌측 정보 우클릭 · iso-detail-context</summary>

![ISO 상세 · 좌측 정보 우클릭 라이트](images/iso-detail-context-light.jpg)

![ISO 상세 · 좌측 정보 우클릭 다크](images/iso-detail-context-dark.jpg)

</details>

<details>
<summary>가상머신 탭 · 수동 확장 · nodes-manual</summary>

![가상머신 탭 · 수동 확장 라이트](images/nodes-manual-light.jpg)

![가상머신 탭 · 수동 확장 다크](images/nodes-manual-dark.jpg)

</details>

<details>
<summary>가상머신 탭 · 외부 관리형 · nodes-external</summary>

![가상머신 탭 · 외부 관리형 라이트](images/nodes-external-light.jpg)

![가상머신 탭 · 외부 관리형 다크](images/nodes-external-dark.jpg)

</details>

<details>
<summary>외부 관리형 · 외부 노드 추가 · external-vm-add</summary>

![외부 관리형 · 외부 노드 추가 라이트](images/external-vm-add-light.jpg)

![외부 관리형 · 외부 노드 추가 다크](images/external-vm-add-dark.jpg)

</details>

<details>
<summary>외부 관리형 · 등록 후 VM 연결 검토 · create-external-connect-review</summary>

![외부 관리형 · 등록 후 VM 연결 검토 라이트](images/create-external-connect-review-light.jpg)

![외부 관리형 · 등록 후 VM 연결 검토 다크](images/create-external-connect-review-dark.jpg)

</details>

## 모바일·조건별 보완 이미지

<details>
<summary>create-external-basic-mobile-dark.jpg</summary>

![create-external-basic-mobile-dark.jpg](images/create-external-basic-mobile-dark.jpg)

</details>

<details>
<summary>create-external-basic-mobile-light.jpg</summary>

![create-external-basic-mobile-light.jpg](images/create-external-basic-mobile-light.jpg)

</details>

<details>
<summary>create-ha-etcd-mobile-dark.jpg</summary>

![create-ha-etcd-mobile-dark.jpg](images/create-ha-etcd-mobile-dark.jpg)

</details>

<details>
<summary>create-ha-etcd-mobile-light.jpg</summary>

![create-ha-etcd-mobile-light.jpg](images/create-ha-etcd-mobile-light.jpg)

</details>

<details>
<summary>delete-mobile-dark.jpg</summary>

![delete-mobile-dark.jpg](images/delete-mobile-dark.jpg)

</details>

<details>
<summary>delete-mobile-light.jpg</summary>

![delete-mobile-light.jpg](images/delete-mobile-light.jpg)

</details>

<details>
<summary>detail-mobile-dark.jpg</summary>

![detail-mobile-dark.jpg](images/detail-mobile-dark.jpg)

</details>

<details>
<summary>detail-mobile-light.jpg</summary>

![detail-mobile-light.jpg](images/detail-mobile-light.jpg)

</details>

<details>
<summary>events-pagination-dark.jpg</summary>

![events-pagination-dark.jpg](images/events-pagination-dark.jpg)

</details>

<details>
<summary>events-pagination-light.jpg</summary>

![events-pagination-light.jpg](images/events-pagination-light.jpg)

</details>

<details>
<summary>firewall-create-icmp-dark.jpg</summary>

![firewall-create-icmp-dark.jpg](images/firewall-create-icmp-dark.jpg)

</details>

<details>
<summary>firewall-create-icmp-light.jpg</summary>

![firewall-create-icmp-light.jpg](images/firewall-create-icmp-light.jpg)

</details>

<details>
<summary>firewall-mobile-dark.jpg</summary>

![firewall-mobile-dark.jpg](images/firewall-mobile-dark.jpg)

</details>

<details>
<summary>firewall-mobile-light.jpg</summary>

![firewall-mobile-light.jpg](images/firewall-mobile-light.jpg)

</details>

<details>
<summary>iso-register-mobile-dark.jpg</summary>

![iso-register-mobile-dark.jpg](images/iso-register-mobile-dark.jpg)

</details>

<details>
<summary>iso-register-mobile-light.jpg</summary>

![iso-register-mobile-light.jpg](images/iso-register-mobile-light.jpg)

</details>

<details>
<summary>lb-create-rule-only-dark.jpg</summary>

![lb-create-rule-only-dark.jpg](images/lb-create-rule-only-dark.jpg)

</details>

<details>
<summary>lb-create-rule-only-light.jpg</summary>

![lb-create-rule-only-light.jpg](images/lb-create-rule-only-light.jpg)

</details>

<details>
<summary>lb-create-selected-dark.jpg</summary>

![lb-create-selected-dark.jpg](images/lb-create-selected-dark.jpg)

</details>

<details>
<summary>lb-create-selected-light.jpg</summary>

![lb-create-selected-light.jpg](images/lb-create-selected-light.jpg)

</details>

<details>
<summary>lb-create-ssl-dark.jpg</summary>

![lb-create-ssl-dark.jpg](images/lb-create-ssl-dark.jpg)

</details>

<details>
<summary>lb-create-ssl-light.jpg</summary>

![lb-create-ssl-light.jpg](images/lb-create-ssl-light.jpg)

</details>

<details>
<summary>list-pagination-dark.jpg</summary>

![list-pagination-dark.jpg](images/list-pagination-dark.jpg)

</details>

<details>
<summary>list-pagination-light.jpg</summary>

![list-pagination-light.jpg](images/list-pagination-light.jpg)

</details>

<details>
<summary>loadbalancers-mobile-dark.jpg</summary>

![loadbalancers-mobile-dark.jpg](images/loadbalancers-mobile-dark.jpg)

</details>

<details>
<summary>loadbalancers-mobile-light.jpg</summary>

![loadbalancers-mobile-light.jpg](images/loadbalancers-mobile-light.jpg)

</details>

<details>
<summary>pf-create-mobile-dark.jpg</summary>

![pf-create-mobile-dark.jpg](images/pf-create-mobile-dark.jpg)

</details>

<details>
<summary>pf-create-mobile-light.jpg</summary>

![pf-create-mobile-light.jpg](images/pf-create-mobile-light.jpg)

</details>

<details>
<summary>portforwarding-mobile-dark.jpg</summary>

![portforwarding-mobile-dark.jpg](images/portforwarding-mobile-dark.jpg)

</details>

<details>
<summary>portforwarding-mobile-light.jpg</summary>

![portforwarding-mobile-light.jpg](images/portforwarding-mobile-light.jpg)

</details>
