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

최신 Provider에 필요한 `updateLoadBalancerRule.cidrlist`를 API/schema/backend에 반영하며 규칙 적용 실패 시 이전 CIDR로 rollback합니다. 기존 custom backend SSL 변경을 보존합니다. WSL ext4에서 변경 api/schema/server/kubernetes-service 모듈을 빌드했고 LB 회귀 18개가 통과했습니다. 전체 Cloud 빌드 완료 결과는 없습니다. PR에서 자동 기동되는 Build는 취소하며, JAR/UI 배포 및 전체 생명주기 runtime 시험은 수행하지 않았습니다.

## URL 등록 주의사항

GitHub permanent asset URL은 HTTP 302를 사용합니다. 다운로드 중 Mold의 기존 동적 전역 설정 `store.download.follow.redirects=true`가 필요합니다. 기존 값을 기록하고 작업 후 운영 정책에 따라 복원합니다. API Ready/100%와 실제 secondary ISO SHA256을 함께 확인합니다. 기존 등록 ISO는 보존하고 시험에서 만든 항목만 새 전용 저장소 URL로 교체합니다.

1.37용 AutoScaler는 stable 미확보 개발 후보이며 공식 ISO 승격 대상이 아닙니다. ISO 등록 PASS와 실제 Service LB/VPC·pending Pod scale-up/down·업그레이드/삭제 PASS를 구분합니다. 공식 게이트는 구성요소 Upstream 승격과 minor별 runtime qualification 증거를 요구합니다.
