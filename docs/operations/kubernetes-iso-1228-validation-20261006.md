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
