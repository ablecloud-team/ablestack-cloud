# Europa Kubernetes UI 통합 설계와 실행 가능한 목업

현재 Mold의 Vue 3 / Ant Design Vue 개발 환경에서 VM 목록·상세·생성 화면을 기준으로 Kubernetes UI를 검토하는 설계 산출물입니다. 운영 UI 구현과 배포는 후속 작업입니다.

- 기준 소스: `upstream/ablestack-europa`, `c169d9a203f49ce07e038297873bc3c24cd8ffb4`.
- [통합 설계](DESIGN.md), [검증 기록](VALIDATION.md), [화면·대화상자 목록](prototype/scenes.json), [레이아웃 관측 원본](render-checks.json).
- 11개 화면 + 32개 대화상자/분기 상태 = 43개 시나리오. 데스크톱 밝은/어두운 테마 86장, 모바일 대표 8장.
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

실제 공통 `MoldDialog`, `ResourceLayout`, `vars.less`, `index.less`와 테마 토큰을 가져옵니다. `InfoCard` 자체는 운영 store/API 의존성이 있으므로 이 목업에서는 같은 시각 구조를 재현합니다. 운영 구현은 기존 `InfoCard`를 재사용합니다. 앱 셸과 데이터 테이블은 검토용 샘플이며 운영 `AutogenView`를 대체하지 않습니다. 768px 미만 열의 전체 너비와 어두운 테마 버튼 의미 색상은 목업에서 공통 토큰으로 보정한 제안입니다. 운영 공통 컴포넌트는 변경하지 않았습니다.

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

목업은 API 클라이언트나 프록시를 연결하지 않습니다. 버튼은 시나리오 이동 또는 안내 메시지만 표시합니다. 입력·선택 컴포넌트는 조작할 수 있지만 실제 검증·상태 전이·권한 제어·비동기 작업 성공을 증명하는 도구는 아닙니다.

## 이미지 카탈로그

각 대화상자의 두 테마 이미지는 아래와 통합 이슈에서 직접 열어 볼 수 있습니다. 긴 폼의 하단은 실행 가능한 갤러리에서 본문을 스크롤해 확인합니다.

<details>
<summary>클러스터 목록 · list</summary>

밝은 테마

![클러스터 목록 밝은 테마](images/list-light.jpg)

어두운 테마

![클러스터 목록 어두운 테마](images/list-dark.jpg)

</details>

<details>
<summary>클러스터 상세 · detail</summary>

밝은 테마

![클러스터 상세 밝은 테마](images/detail-light.jpg)

어두운 테마

![클러스터 상세 어두운 테마](images/detail-dark.jpg)

</details>

<details>
<summary>노드 목록 · nodes</summary>

밝은 테마

![노드 목록 밝은 테마](images/nodes-light.jpg)

어두운 테마

![노드 목록 어두운 테마](images/nodes-dark.jpg)

</details>

<details>
<summary>액세스 안내 · access</summary>

밝은 테마

![액세스 안내 밝은 테마](images/access-light.jpg)

어두운 테마

![액세스 안내 어두운 테마](images/access-dark.jpg)

</details>

<details>
<summary>서비스 부하 분산 · loadbalancers</summary>

밝은 테마

![서비스 부하 분산 밝은 테마](images/loadbalancers-light.jpg)

어두운 테마

![서비스 부하 분산 어두운 테마](images/loadbalancers-dark.jpg)

</details>

<details>
<summary>방화벽 규칙 · firewall</summary>

밝은 테마

![방화벽 규칙 밝은 테마](images/firewall-light.jpg)

어두운 테마

![방화벽 규칙 어두운 테마](images/firewall-dark.jpg)

</details>

<details>
<summary>포트 포워딩 · portforwarding</summary>

밝은 테마

![포트 포워딩 밝은 테마](images/portforwarding-light.jpg)

어두운 테마

![포트 포워딩 어두운 테마](images/portforwarding-dark.jpg)

</details>

<details>
<summary>이벤트 · events</summary>

밝은 테마

![이벤트 밝은 테마](images/events-light.jpg)

어두운 테마

![이벤트 어두운 테마](images/events-dark.jpg)

</details>

<details>
<summary>코멘트 · comments</summary>

밝은 테마

![코멘트 밝은 테마](images/comments-light.jpg)

어두운 테마

![코멘트 어두운 테마](images/comments-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 목록 · iso-list</summary>

밝은 테마

![Kubernetes ISO 목록 밝은 테마](images/iso-list-light.jpg)

어두운 테마

![Kubernetes ISO 목록 어두운 테마](images/iso-list-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 상세 · iso-detail</summary>

밝은 테마

![Kubernetes ISO 상세 밝은 테마](images/iso-detail-light.jpg)

어두운 테마

![Kubernetes ISO 상세 어두운 테마](images/iso-detail-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-basic</summary>

밝은 테마

![쿠버네티스 클러스터 생성 밝은 테마](images/create-basic-light.jpg)

어두운 테마

![쿠버네티스 클러스터 생성 어두운 테마](images/create-basic-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-nodes</summary>

밝은 테마

![쿠버네티스 클러스터 생성 밝은 테마](images/create-nodes-light.jpg)

어두운 테마

![쿠버네티스 클러스터 생성 어두운 테마](images/create-nodes-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-advanced</summary>

밝은 테마

![쿠버네티스 클러스터 생성 밝은 테마](images/create-advanced-light.jpg)

어두운 테마

![쿠버네티스 클러스터 생성 어두운 테마](images/create-advanced-dark.jpg)

</details>

<details>
<summary>클러스터 생성 · HA / 외부 etcd · create-ha-etcd</summary>

밝은 테마

![클러스터 생성 · HA / 외부 etcd 밝은 테마](images/create-ha-etcd-light.jpg)

어두운 테마

![클러스터 생성 · HA / 외부 etcd 어두운 테마](images/create-ha-etcd-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-addons</summary>

밝은 테마

![쿠버네티스 클러스터 생성 밝은 테마](images/create-addons-light.jpg)

어두운 테마

![쿠버네티스 클러스터 생성 어두운 테마](images/create-addons-dark.jpg)

</details>

<details>
<summary>쿠버네티스 클러스터 생성 · create-review</summary>

밝은 테마

![쿠버네티스 클러스터 생성 밝은 테마](images/create-review-light.jpg)

어두운 테마

![쿠버네티스 클러스터 생성 어두운 테마](images/create-review-dark.jpg)

</details>

<details>
<summary>클러스터 시작 · start</summary>

밝은 테마

![클러스터 시작 밝은 테마](images/start-light.jpg)

어두운 테마

![클러스터 시작 어두운 테마](images/start-dark.jpg)

</details>

<details>
<summary>클러스터 중지 · stop</summary>

밝은 테마

![클러스터 중지 밝은 테마](images/stop-light.jpg)

어두운 테마

![클러스터 중지 어두운 테마](images/stop-dark.jpg)

</details>

<details>
<summary>클러스터 확장 / 축소 · scale</summary>

밝은 테마

![클러스터 확장 / 축소 밝은 테마](images/scale-light.jpg)

어두운 테마

![클러스터 확장 / 축소 어두운 테마](images/scale-dark.jpg)

</details>

<details>
<summary>오토스케일링 설정 · autoscale</summary>

밝은 테마

![오토스케일링 설정 밝은 테마](images/autoscale-light.jpg)

어두운 테마

![오토스케일링 설정 어두운 테마](images/autoscale-dark.jpg)

</details>

<details>
<summary>부분 확장 복구 · scale-recovery</summary>

밝은 테마

![부분 확장 복구 밝은 테마](images/scale-recovery-light.jpg)

어두운 테마

![부분 확장 복구 어두운 테마](images/scale-recovery-dark.jpg)

</details>

<details>
<summary>클러스터 업그레이드 · upgrade</summary>

밝은 테마

![클러스터 업그레이드 밝은 테마](images/upgrade-light.jpg)

어두운 테마

![클러스터 업그레이드 어두운 테마](images/upgrade-dark.jpg)

</details>

<details>
<summary>Affinity 그룹 변경 · affinity</summary>

밝은 테마

![Affinity 그룹 변경 밝은 테마](images/affinity-light.jpg)

어두운 테마

![Affinity 그룹 변경 어두운 테마](images/affinity-dark.jpg)

</details>

<details>
<summary>외부 노드 추가 · add-nodes</summary>

밝은 테마

![외부 노드 추가 밝은 테마](images/add-nodes-light.jpg)

어두운 테마

![외부 노드 추가 어두운 테마](images/add-nodes-dark.jpg)

</details>

<details>
<summary>외부 노드 연결 해제 · remove-nodes</summary>

밝은 테마

![외부 노드 연결 해제 밝은 테마](images/remove-nodes-light.jpg)

어두운 테마

![외부 노드 연결 해제 어두운 테마](images/remove-nodes-dark.jpg)

</details>

<details>
<summary>워커 노드 삭제 · delete-worker</summary>

밝은 테마

![워커 노드 삭제 밝은 테마](images/delete-worker-light.jpg)

어두운 테마

![워커 노드 삭제 어두운 테마](images/delete-worker-dark.jpg)

</details>

<details>
<summary>클러스터 삭제 · delete-cluster</summary>

밝은 테마

![클러스터 삭제 밝은 테마](images/delete-cluster-light.jpg)

어두운 테마

![클러스터 삭제 어두운 테마](images/delete-cluster-dark.jpg)

</details>

<details>
<summary>외부 관리형 클러스터 삭제 · delete-external</summary>

밝은 테마

![외부 관리형 클러스터 삭제 밝은 테마](images/delete-external-light.jpg)

어두운 테마

![외부 관리형 클러스터 삭제 어두운 테마](images/delete-external-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 시작 · bulk-start</summary>

밝은 테마

![선택한 클러스터 시작 밝은 테마](images/bulk-start-light.jpg)

어두운 테마

![선택한 클러스터 시작 어두운 테마](images/bulk-start-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 중지 · bulk-stop</summary>

밝은 테마

![선택한 클러스터 중지 밝은 테마](images/bulk-stop-light.jpg)

어두운 테마

![선택한 클러스터 중지 어두운 테마](images/bulk-stop-dark.jpg)

</details>

<details>
<summary>선택한 클러스터 삭제 · bulk-delete</summary>

밝은 테마

![선택한 클러스터 삭제 밝은 테마](images/bulk-delete-light.jpg)

어두운 테마

![선택한 클러스터 삭제 어두운 테마](images/bulk-delete-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO URL 등록 · iso-register</summary>

밝은 테마

![Kubernetes ISO URL 등록 밝은 테마](images/iso-register-light.jpg)

어두운 테마

![Kubernetes ISO URL 등록 어두운 테마](images/iso-register-dark.jpg)

</details>

<details>
<summary>Kubernetes ISO 파일 업로드 · iso-upload</summary>

밝은 테마

![Kubernetes ISO 파일 업로드 밝은 테마](images/iso-upload-light.jpg)

어두운 테마

![Kubernetes ISO 파일 업로드 어두운 테마](images/iso-upload-dark.jpg)

</details>

<details>
<summary>지원 버전 상태 변경 · iso-state</summary>

밝은 테마

![지원 버전 상태 변경 밝은 테마](images/iso-state-light.jpg)

어두운 테마

![지원 버전 상태 변경 어두운 테마](images/iso-state-dark.jpg)

</details>

<details>
<summary>지원 버전 등록 삭제 · iso-delete</summary>

밝은 테마

![지원 버전 등록 삭제 밝은 테마](images/iso-delete-light.jpg)

어두운 테마

![지원 버전 등록 삭제 어두운 테마](images/iso-delete-dark.jpg)

</details>

<details>
<summary>네트워크 규칙 태그 편집 · network-tags</summary>

밝은 테마

![네트워크 규칙 태그 편집 밝은 테마](images/network-tags-light.jpg)

어두운 테마

![네트워크 규칙 태그 편집 어두운 테마](images/network-tags-dark.jpg)

</details>

<details>
<summary>포트 포워딩 VM 선택 · pf-vm</summary>

밝은 테마

![포트 포워딩 VM 선택 밝은 테마](images/pf-vm-light.jpg)

어두운 테마

![포트 포워딩 VM 선택 어두운 테마](images/pf-vm-dark.jpg)

</details>

<details>
<summary>선택한 네트워크 규칙 삭제 · rules-delete</summary>

밝은 테마

![선택한 네트워크 규칙 삭제 밝은 테마](images/rules-delete-light.jpg)

어두운 테마

![선택한 네트워크 규칙 삭제 어두운 테마](images/rules-delete-dark.jpg)

</details>

<details>
<summary>코멘트 삭제 · comment-delete</summary>

밝은 테마

![코멘트 삭제 밝은 테마](images/comment-delete-light.jpg)

어두운 테마

![코멘트 삭제 어두운 테마](images/comment-delete-dark.jpg)

</details>

<details>
<summary>코멘트 공개 범위 변경 · comment-visibility</summary>

밝은 테마

![코멘트 공개 범위 변경 밝은 테마](images/comment-visibility-light.jpg)

어두운 테마

![코멘트 공개 범위 변경 어두운 테마](images/comment-visibility-dark.jpg)

</details>

<details>
<summary>업그레이드 가능 버전 없음 · upgrade-empty</summary>

밝은 테마

![업그레이드 가능 버전 없음 밝은 테마](images/upgrade-empty-light.jpg)

어두운 테마

![업그레이드 가능 버전 없음 어두운 테마](images/upgrade-empty-dark.jpg)

</details>

<details>
<summary>ISO 등록 입력 오류 · validation-error</summary>

밝은 테마

![ISO 등록 입력 오류 밝은 테마](images/validation-error-light.jpg)

어두운 테마

![ISO 등록 입력 오류 어두운 테마](images/validation-error-dark.jpg)

</details>
