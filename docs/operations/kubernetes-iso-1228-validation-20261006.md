<!--
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 -->

# Europa Kubernetes ISO 소비 계약과 전용 저장소 인계

ISO 생성 Actions·recipe·독립 검사·Release publisher는 **Cloud 저장소에서 분리**합니다.

- 공식 ISO 저장소: [ablecloud-team/ablestack-kubernetes-iso](https://github.com/ablecloud-team/ablestack-kubernetes-iso), `main`
- Local/Origin 빌드·시험 Release: [dhslove/ablestack-kubernetes-iso](https://github.com/dhslove/ablestack-kubernetes-iso)
- 검증/등록 보고: [ISO 전용 저장소 보고서](https://github.com/dhslove/ablestack-kubernetes-iso/blob/codex/kubernetes-iso-1228/docs/validation/iso-registration-20261006.md)
- [분리 전 Cloud Origin 검증 이력](https://github.com/dhslove/ablestack-kubernetes-iso/blob/codex/kubernetes-iso-1228/docs/validation/cloud-origin-before-extraction-20261006.md)
- 설계 [#1228](https://github.com/ablecloud-team/ablestack-cloud/issues/1228), 전체 생명주기 [#1227](https://github.com/ablecloud-team/ablestack-cloud/issues/1227)

Cloud 최종 PR base는 `ablestack-europa`입니다. Cloud는 새 전용 저장소의 고정 ISO asset URL과 `{SHA-256}` checksum을 기존 등록 API로 소비합니다. Cloud의 기존 ISO 생성 진입점은 전용 저장소 안내 후 종료하며 자체 빌드/Release를 실행하지 않습니다.

## Cloud 변경 범위

노드 설치/확장/업그레이드 시 ISO checksum을 검사하고 `docker/images.list`의 tag/digest를 격리 image import 방식과 같은 계약으로 보존합니다. Provider/AutoScaler는 검증된 ISO 내부 manifest만 적용하고 외부 stock SHA1 fallback을 제거합니다. Secret 적용은 갱신을 지원하고 autoscaling 입력을 검증합니다. Headlamp/legacy dashboard 계약과 새 kubeadm v1beta4/CRI endpoint를 반영합니다.

최신 Provider에 필요한 `updateLoadBalancerRule.cidrlist`를 API/schema/backend에 반영하며 규칙 적용 실패 시 이전 CIDR로 rollback합니다. 기존 custom backend SSL 변경을 보존합니다. WSL ext4에서 변경 api/schema/server/kubernetes-service 모듈을 빌드했고 LB 회귀 18개가 통과했습니다. 전체 Cloud 빌드 완료 결과는 없습니다. PR에서 자동 기동되는 전체 Build는 취소합니다. 31번에서는 변경 클래스/리소스를 기존 Europa JAR에 제한 배포하여 아래 runtime 단계를 검증했습니다. 전체 Cloud 패키지 빌드 및 전체 생명주기 qualification은 완료하지 않았습니다.

## URL 등록 주의사항

GitHub permanent asset URL은 HTTP 302를 사용합니다. 다운로드 중 Mold의 기존 동적 전역 설정 `store.download.follow.redirects=true`가 필요합니다. 기존 값을 기록하고 작업 후 운영 정책에 따라 복원합니다. API Ready/100%와 실제 secondary ISO SHA256을 함께 확인합니다. 기존 등록 ISO는 보존하고 시험에서 만든 항목만 새 전용 저장소 URL로 교체합니다.

1.37용 AutoScaler는 stable 미확보 개발 후보이며 공식 ISO 승격 대상이 아닙니다. ISO 등록 PASS와 실제 Service LB/VPC·pending Pod scale-up/down·업그레이드/삭제 PASS를 구분합니다. 공식 게이트는 구성요소 Upstream 승격과 minor별 runtime qualification 증거를 요구합니다.

## 31번 runtime 검증 및 표시 언어 (2026-10-06)

VLAN 44 시험 네트워크와 이전 실패 클러스터를 삭제한 뒤 VLAN 181에서 `k8s-lifecycle31-v13412-gfs2-r4`를 UI로 신규 배포했습니다. control 1/worker 2의 root와 router 볼륨은 모두 Primary pool 1, 실제 GFS2이며 CLVM/CLVM_NG는 사용하지 않았습니다.

- 클러스터 UUID `d635db12-b80a-4d50-b7bd-f6956c909112`, 생성 job 성공/Mold Running, 노드 3개 v1.34.12 Ready 및 cloud-final exit 0.
- 배포 backend 소스 `582a63b2e26ac846ea9f542791b4192bf5d97696`, 제한 배포 JAR SHA256 `c9a92f24d21fada72acfe29996586a1081e4f598d24b22952c11b7a9fb449e20`.
- [RT01~RT03 결과](https://github.com/ablecloud-team/ablestack-cloud/issues/1230#issuecomment-6005040444): cross-worker Pod HTTP/DNS/Service/NodePort/NetworkPolicy 14개 검사 PASS, 서비스 HTTP 1,000회 오류 0.
- RT04: UI 다운로드 kubeconfig와 API config의 SHA256 일치, CA 검증을 유지한 외부 kubectl 접속으로 v1.34.12/Ready 3개 확인. Headlamp 읽기 전용 15분 TokenRequest 로그인 및 Pod 조회 성공, Secret 조회/워크로드 삭제 권한 거부 확인.
- Headlamp 일반 설정의 기존 선택값 `zh`를 `ko`로 바꾸고 새로고침 후 한국어 유지 확인. 이 관측만으로 브라우저 자동화가 중국어를 설정했다고 단정하지 않습니다.

Headlamp는 Mold와 별도로 브라우저에서 언어를 감지합니다. Mold의 접근 링크는 현재 Mold locale을 Headlamp `lng` parameter로 전달하며 Headlamp 미지원 locale은 영어로 연결합니다. 한국어 검증에서는 먼저 Mold 한국어와 Headlamp 일반 설정의 `ko` 및 실제 한국어 메뉴를 확인하고, 새로고침 후 같은 언어가 유지되는지 검사합니다. 불일치 상태에서는 언어 관련 버튼을 고정된 화면 좌표로 조작하지 않습니다. 토큰·kubeconfig가 보이는 접근 화면은 공개 screenshot 대상에서 제외합니다.

[Headlamp v0.40.1 언어 설정 소스](https://github.com/kubernetes-sigs/headlamp/blob/v0.40.1/frontend/src/i18n/config.ts)는 browser language detector와 한국어 지원을 사용합니다. [Headlamp 접근/번역 후속 이슈 #1239](https://github.com/ablecloud-team/ablestack-cloud/issues/1239)에서 최소 권한/단기 토큰 안내와 전체 번역 정리를 추적합니다. RT05 이후 앱/영속성, LB/AutoScaler, 확장·업그레이드·중지/시작·삭제, 다른 버전 및 장시간 관측은 후속 검증 대상입니다.

RT06의 독립 NFS fixture로 `rt1230-gfs2-nfs-r4` (UUID `654fe7a4-ac32-47f6-8ba6-37c45720809c`, IP `10.123.4.3`)를 Ubuntu 24.04 사용자 template으로 생성했습니다. root volume 208은 Primary pool 1/GFS2입니다. fixture 100 GiB와 노드 2→3 확장을 수용하도록 전용 시험 계정 Primary quota만 200→400 GiB로 조정했습니다. 템플릿은 SSH 키 주입을 지원하지 않고 QGA가 연결되지 않았으며 제공된 root 시험 암호로 로그인할 수 없어, fixture 구성과 데이터 시험은 로그인 정보 확보까지 BLOCKED입니다. 기존 사용자 저장소 export 또는 controller root를 외부 영속 저장소 PASS로 대체하지 않습니다. 시험 종료 시 fixture 소유 자원과 quota 복원을 함께 확인합니다.

## 2026-10-06 RT05~RT08 실행 추가

- r4 1.34.12에서 GFS2 Primary 위 전용 Ubuntu NFS fixture의 SSH 키 접근을 확보하고 static Retain PV/PVC를 배포했다. 원본 템플릿과 기존 사용자 VM은 변경하지 않았다.
- RT05: web 2 → API 2 → Redis StatefulSet 1, 기준선 HTTP 1,002회 오류 0, DB 100 records/64 files checksum 검증. rolling release/rollback, ConfigMap/Secret 변경 및 이전 token 거부를 확인했다. 변경 중 readiness 1,200회/123.78초 오류 0, 실제 HTTP 503 liveness fault 후 container restart 1/14.55초 Ready 복구 및 checksum 유지.
- RT06: worker1→worker2 Pod 재배치, 별도 RDB/file archive 백업과 새 namespace/PV 복원 및 AOF Pod 재시작 후 checksum 유지. RDB-only 복원은 appendonly=no 초기 로드 후 AOF rewrite를 완료하는 절차로 검증했다. 첫 잘못된 AOF 초기 복원 시도는 PASS로 계산하지 않았다. CSI/동적 provisioning 검증은 아니다.
- RT07: Provider가 별도 IP에 LB를 생성, HTTP 100회 성공, 동일 LB ID의 CIDR 변경 및 실제 허용/차단, firewall CIDR 재조정, ClientIP→source algorithm 반영을 확인했다. 클러스터 화면에서 별도 IP의 Service LB가 누락되는 UI 개선은 [#1241](https://github.com/ablecloud-team/ablestack-cloud/issues/1241).
- RT08: UI worker 2→3 확장 job 성공, 새 worker GFS2/Ready/cloud-final exit 0, LB backend 추가를 확인했다. PDB 차단 시 기존 drain이 끝나지 않는 결함은 [#1243](https://github.com/ablecloud-team/ablestack-cloud/issues/1243).
- RT10 부분: 실제 CCM Secret의 같은 자격증명으로 SHA256 서명 HTTP 200, SHA1 서명 HTTP 401을 확인했다. 자격증명 값은 증거/이슈에 포함하지 않았다. revoke/rotate/권한 범위와 AS runtime 전체 검증은 남아 있다.

### PDB 차단 시 축소 대기 개선

Kubernetes ScaleWorker의 원격 drain에 kubectl timeout 50초/request timeout 10초와 OS watchdog 55초/kill-after 5초를 적용한다. 원격 stdout/stderr를 합쳐 공용 SSH helper의 별도 출력 stream 읽기 대기를 피한다. Node 삭제/uncordon 명령도 실행 시간을 제한한다.

drain이 실패하면 Node/VM/map/count 삭제 경로로 진행하지 않고 best-effort uncordon으로 스케줄링을 복구한다. 공용 SshHelper와 다른 Cloud 기능은 변경하지 않는다.

검증: WSL ext4 clone에서 Kubernetes 변경 Maven 모듈의 ScaleWorker 테스트 7개(실패 0)와 package/checkstyle 성공. 31번 backend는 기존 시험 JAR에 ScaleWorker 클래스 1개만 겹쳐 배포하며 다른 204,441개 entry의 CRC/size를 비교한다. 이는 full Cloud package build 검증이 아니다. 실제 PDB 재시험과 정상 축소 결과는 #1243/#1230의 후속 실행 기록에서 판정한다.

전체 버전/upgrade/전원/삭제/장시간 시험과 RT06의 모든 생명주기 데이터 보존은 아직 완료하지 않았다.
### RT08 실제 PDB 차단 및 정상 축소 재검증

- #1243 수정 후보로 같은 PDB minAvailable=1 / allowedDisruptions=0에서 Mold UI 3→2를 재요청했습니다. job `0a475d5d-7a1f-4136-8839-203319aea3fd`는 51초에 status 2/error 530으로 종료했습니다. 강제 eviction 없이 클러스터 Running/worker 3, 보호 Pod, VM 169 및 GFS2 volume 209를 유지했습니다. 자동 uncordon 및 원격 drain 종료도 확인했습니다.
- 시험 PDB/guard만 제거한 후 Mold UI 정상 3→2 job `a339143a-4e7b-4731-83bf-f06985d2e7d3`가 14초에 status 1로 완료됐습니다. Node 3개 Ready(control 1/worker 2), VM 169 Expunging/removed, volume 209 Expunged, VM map 3개, SSH rule 2225 제거 및 Provider LB backend 2개가 일치했습니다.
- 원본 앱과 별도 clean restore 앱에서 각각 65/65 검사, HTTP 100회 오류 0, DB 100건·파일 64개/4 MiB의 원본 checksum 일치를 확인했습니다. 이 결과는 대표 1.34.12의 RT08 범위이며 AutoScaler/다른 버전/업그레이드/장시간/삭제 전체 PASS는 아닙니다.
- 수정 전 hang 작업은 management restart 후 stale Scaling을 남겨 #1237에 기록하고, terminal job/VM/map/count 확인 후 state만 조건부 수동 복구했습니다. 이 복구를 제품 동작 PASS로 계산하지 않았습니다.

## RT09 노드 역할 API 계약 (#1245)

Mold listKubernetesClusters의 virtualmachines 항목에 iscontrolnode를 추가한다. isetcdnode/isexternalnode와 독립적으로 반환하여 내부 워커와 제어/etcd/외부 노드를 구분한다. AS는 역할 메타데이터가 없으면 관리 대상에서 제외하고, 실제 Node 이름·VM UUID·SystemUUID를 현재 클러스터의 내부 워커로만 해석한다. 명시된 providerID가 다른 VM/Provider이면 이름으로 우회하지 않는다.

변경 api 모듈의 KubernetesUserVmResponseTest 1개 및 Kubernetes 모듈의 Worker 시험 22개가 통과했다. 전체 Cloud 빌드를 실행하지 않았다. 31 관리 서버는 변경 응답 클래스와 KubernetesClusterManagerImpl 및 생성된 내부 클래스만 기존 적용본에 반영하여 시험한다. 새로운 AS 이미지의 실제 그룹 카운트와 확장·축소 재검증은 진행 중이다.

### Canonical Provider ID 초기화 보완

실제 Node의 providerID가 비어 있으면 AS의 최신 core가 UUID 인스턴스를 미등록 노드로 중복 계산한다. 내부 Provider의 이름은 external-cloudstack이므로 canonical ID는 external-cloudstack://<VM UUID>이다. AS Nodes()도 같은 ID를 반환해야 한다.

신규/추가 제어 노드와 워커는 provider.yaml이 존재하는 Mold payload에서만 kubelet --cloud-provider=external을 사용한다. 기존 payload 경로는 해당 옵션을 추가하지 않는다. 업그레이드에서는 기존 kubelet 옵션을 보존하고 다른 cloud-provider가 있으면 실패한다. 메인 제어 노드가 새 CCM을 적용한 뒤 providerID가 비어 있는 기존 Node만 NoSchedule 초기화 taint로 CCM 조회를 요청한다. 실행 중 Pod를 퇴거시키지 않으며 CCM이 ID를 채우고 taint를 제거한다. ID 초기화 대기는 120초로 제한한다.

새 runtime shell 시험은 3개 그룹에서 세 node template의 payload 유무/빈 파일, upgrade 옵션 보존·quoted 값·멱등성·다른 Provider 거부를 실제 Bash로 검증했다. 기존 ISO 소비 shell 시험 6개 그룹 및 Kubernetes 모듈 22개 JUnit도 통과했다. 31번의 기존 4 Node를 native CCM으로 초기화하여 canonical ID와 Ready 및 초기화 taint 제거를 확인했다. 이 수동 migration은 clean 신규 bootstrap PASS를 대신하지 않는다. AS source 254f91edf의 4 minor build/test는 성공했고 신규 ISO source 48830e74는 검증 중이다.

### RT09 canonical ID·집계 및 자동 2→3→2 완료 범위

Cloud f8dca4fb86 / AS254f91ed / ISO48830e74에서 Local 및 Origin 새 ISO 6개를 빌드·독립 검사하고, 비로그인 전체 GET와 secondary 실제 파일 SHA256·크기 일치를 확인했다. 기존 15개 등록을 보존해 새 6개 모두 Ready, 총 21개이며 redirect 설정을 false로 복원했다. [상세 결과](https://github.com/ablecloud-team/ablestack-cloud/issues/1230#issuecomment-6007860800).

기존 r4 대표 클러스터에서 실제 Pending Pod로 GFS2 worker172/root212 생성→Ready→배치를 확인했다. 기본 unneeded 10분을 유지한 자동 축소 job8201은 11:02:41→11:02:55 성공했다. worker registered/ready/target 2→3→2, 미등록0, UUID identity 경고0 및 min2/max3을 확인했다. 삭제 VM/map/root/SSH/LB backend 정리가 일치하고 원본·clean restore 앱 각각 65/65 검사 및 HTTP100 오류0, 축소 중 연속 HTTP1200 오류0·checksum 보존을 확인했다. 이것은 기존 r4 개선 재검증이며 새 ISO의 모든 clean install/HA/minor PASS가 아니다.

### 신규 설치의 Provider 초기화 순서 (#1247)

새 ISO48830e74의 1.34.12 r5에서 Node Ready·cloud-final 성공 후에도 providerID가 비어 있고 모든 Node에 external initialization taint가 남았다. CCM을 배포하기 전에 Dashboard를 기다려 CoreDNS·Headlamp·Calico controller가 Pending인 순환 대기가 실제로 발생했다. 현재 원인 검증은 실제 CCM 수동 배포로 ID 초기화/taint 제거와 Pod Ready를 확인하는 진단 복구로 기록하며, clean PASS에 포함하지 않는다.

StartWorker는 API 준비 후 CCM을 먼저 배포하고 그 뒤 Node/dashboard 준비를 확인하도록 수정했다. Provider 실패를 CreateFailed로 처리한다. 변경 Kubernetes Maven 모듈의 22개 JUnit·package/checkstyle를 통과했다. 수정 후보 배포 후 새로운 clean run으로 재검증하며 Shared/L2 등 기존 Provider 생략 경로의 호환성은 남은 범위로 추적한다.

### Kubernetes 생성 소유자 검색 (#1246)

실제 계정 검색에서 option.label 누락에 의한 toLowerCase TypeError를 확인했다. OwnershipSelection의 계정 옵션에 account.name label을 추가한다. 해당 UI 파일 ESLint와 Node20/WSL ext4 production UI build가 통과했다. static UI 적용은 WEB-INF/config.json/management PID를 보존하고 브라우저의 정상·무결과·검색 해제·선택을 재검증한다.

## 새 ISO clean r6 및 생성 폼 검색 회귀

- ISO48830e74/Cloud795bfb65의 r6(2ea55ae0-7ea7-4145-ae0b-7945982c0ab2) UI 생성 job8222/b2ead0f9-ff72-4259-b7ad-f18fe4c2a8d0은 11:35:09→11:37:36 KST/147초 성공.
- 실제 CCM이 제어/워커 세 native Provider ID를 생성하고 uninitialized taint를 해제했으며 Node3/시스템 Pod15 Ready·재시작0. 수동 CCM/Secret/taint/ID 보정 없이 검증했다.
- VM179/180/181과 VR175의 root는 Primary pool1 GFS2, 세 노드 cloud-final ExecMainStatus0·bootstrap success·kubelet/containerd active·v1.34.12·ISO분리.
- 서로 다른 worker의 Pod/DNS/ClusterIP/NodePort/NetworkPolicy 14검사 및 HTTP1000/오류0 통과. 전체 lifecycle/다른 minor PASS를 선언하지 않는다.
- #1246 계정 검색 일치/무결과/검색 해제/선택 정상. 후속 네트워크 검색에서 빈 옵션 label의 undefined 접근이 재현되어 #1248을 추가했다.
- #1248 네트워크/SSH 키 빈 옵션 label을 빈 문자열로 제공하고 태그 밖에 있던 hypervisor filterOption을 속성으로 복원했다. 변경 파일 ESLint·UI production build PASS. 실제 배포 후 필드별 검색 회귀 검증을 진행한다.
