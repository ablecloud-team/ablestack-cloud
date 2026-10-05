<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# Europa Kubernetes ISO #1228 검증 및 운영 인계 — 2026-10-06

[설계 #1228](https://github.com/ablecloud-team/ablestack-cloud/issues/1228)의 Local/Origin ISO 구현·검증과 31번 등록 결과다. 전체 생명주기 [Epic #1227](https://github.com/ablecloud-team/ablestack-cloud/issues/1227)은 계속 열려 있다.

## 결과와 검증 범위

요청한 **1.34.12, 1.35.9, 1.36.5, 1.37.1** 및 최초 대상 **1.34.2, 1.34.9**의 amd64 ISO 6개를 Origin Actions에서 생성하고 독립 검증한 뒤 공개 trial Release로 게시했다. 6개 모두 비로그인 전체 GET/hash 검사와 31번 URL 등록·다운로드를 통과했다. 관리 API의 Ready/100%와 secondary storage에 저장된 실제 파일의 SHA256을 각각 확인했다. 기존 Ready ISO 3개(1.34.2, 1.35.9, 1.35.10)의 원래 UUID와 메타데이터를 그대로 보존하여 총 9개 supported version이 존재한다.

**ISO 생성·등록 PASS는 클러스터 배포·LB·자동 확장·업그레이드의 런타임 PASS를 의미하지 않는다.** 이 시험에서 Cloud JAR/UI를 31번에 배포하거나 Kubernetes 클러스터를 생성하지 않았다. 등록 시 CPU 2/RAM 2048 MiB는 supported-version API 최소값이며 노드 offering의 용량 검증 결과가 아니다.

## 고정 소스와 공개 산출물

- Cloud 기준 Europa: `8f5621ab9d7f99e2d3b079e91f9640b759075b42`. 최종 PR 준비 시 Upstream을 다시 fetch하여 동일 HEAD를 확인했다.
- ISO producer/recipe commit: `b3a9a20a8592bd3768ddc789c36c712c4f057f61`.
- [Origin ISO Actions — 6개 build/validate/publish 성공](https://github.com/dhslove/ablestack-cloud/actions/runs/37331425944).
- 이 보고서와 PR head의 후속 문서 commit은 검증된 producer/recipe를 변경하지 않는다.
- profile `mold-cks`, arch `amd64`/Mold `x86_64`, revision `r1`. 전부 2 GiB 미만이다.
- 각 Release에는 ISO, 전체 ISO checksum, manifest, CycloneDX SBOM, validation, registration JSON 및 한글 notes를 함께 보존한다. Actions 임시 artifact는 7일 보존하며 영구 URL은 Release asset다.

| Kubernetes | 다운로드 | 크기(bytes) | Origin 독립 검증 | 31번 실파일/Ready |
|---|---|---:|---|---|
| 1.34.2 | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.34.2-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.34.2-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.34.2-mold-cks-amd64-r1-b3a9a20a) | 827795456 | PASS | SHA256 일치 / 100% Ready |
| 1.34.9 | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.34.9-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.34.9-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.34.9-mold-cks-amd64-r1-b3a9a20a) | 828563456 | PASS | SHA256 일치 / 100% Ready |
| 1.34.12 | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.34.12-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.34.12-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.34.12-mold-cks-amd64-r1-b3a9a20a) | 832880640 | PASS | SHA256 일치 / 100% Ready |
| 1.35.9 | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.35.9-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.35.9-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.35.9-mold-cks-amd64-r1-b3a9a20a) | 826480640 | PASS | SHA256 일치 / 100% Ready |
| 1.36.5 | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.36.5-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.36.5-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.36.5-mold-cks-amd64-r1-b3a9a20a) | 840015872 | PASS | SHA256 일치 / 100% Ready |
| 1.37.1 (AutoScaler 개발 후보) | [ISO](https://github.com/dhslove/ablestack-cloud/releases/download/k8s-v1.37.1-mold-cks-amd64-r1-b3a9a20a/kubernetes-v1.37.1-mold-cks-amd64-r1-b3a9a20a.iso) · [Release](https://github.com/dhslove/ablestack-cloud/releases/tag/k8s-v1.37.1-mold-cks-amd64-r1-b3a9a20a) | 848939008 | PASS | SHA256 일치 / 100% Ready |

### Origin ISO SHA256

| 버전 | SHA256 |
|---|---|
| 1.34.2 | `d2d48a67a199ff3cd25de91a4b8a14f5a0caa9d658f3f395937b0a36adc17344` |
| 1.34.9 | `e0f5e871324c248fbf829e7e99e7f4df2822c1a28f3fc88c87aa12f80d37dc5b` |
| 1.34.12 | `111f29ea5bc7f81f4219694dcdfcb23efe943c788922f76dcac5327ea7d6d919` |
| 1.35.9 | `597a4995c527e2f979992c120824bd7ccc23c789054177c0ed1b5dcf7d1558c9` |
| 1.36.5 | `3ac2b38d66c3147049e90e83d6b054c1aaf9fff9ccade792ecb9739a5b25a0d8` |
| 1.37.1 | `32d80248cff11b7a23de17ad208860f30e58b4fccd9987b5ff6b8b3e1bd063b8` |

### 31번 등록 식별자

| 버전 | supported version UUID | ISO UUID |
|---|---|---|
| 1.34.2 | `485dd99d-fb59-4413-93be-469cefc8b234` | `a1865224-0826-4357-b949-a292ead148b9` |
| 1.34.9 | `a9fe680b-43bb-4966-ba58-5b5eaa536d3d` | `e7ae778f-cff0-47df-a5c6-eac737a18adf` |
| 1.34.12 | `41599a0c-8224-4361-9e25-b639fa83ba79` | `7d217300-3e1c-4292-a93a-e76619403f5e` |
| 1.35.9 | `3fab57eb-d196-4101-84e3-8baf3252aca4` | `d2e8d621-7fa4-45da-b91b-26dfaa5e92cb` |
| 1.36.5 | `95e7fab5-aeca-4c49-bb6d-15170c8a0cf3` | `d64b298b-1251-42b5-aa7a-daff0f6a7874` |
| 1.37.1 | `3bdecd3b-20ac-4451-a742-2fcb76cee892` | `1bbd88d3-db69-469c-a46e-2b4e0c61510d` |

## Provider·SDK·AutoScaler 최신화와 인증

- [SDK PR #3](https://github.com/ablecloud-team/ablestack-mold-go/pull/3): Apache SDK v2.19.1 `e463cd1c0db2582865a310ad8053653b74ce1f22`를 내부 module에 반영. Mold 패치 SHA `7f1863866bc605456eb15c52bcde214c9ad9240c`, Origin tag `v2.19.2-mold-test.1`. [SDK Actions 성공](https://github.com/dhslove/ablestack-mold-go/actions/runs/37320794073).
- [Provider PR #2](https://github.com/ablecloud-team/ablestack-kubernetes-provider/pull/2): Apache main `2a46b8e43382bbd1564db7a9bfa56f9caa872d13`의 VPC ACL, CIDR/ProxyProtocol reconcile, fixed IP 소유권, UUID/providerID·zone/region, pagination/listAll 변경을 포함. 내부 SHA `34fe8294cd8c2337fbf6934eb75e4684cf3a9d85`. [Provider Actions 성공](https://github.com/dhslove/ablestack-kubernetes-provider/actions/runs/37329464966).
- [AutoScaler PR #3](https://github.com/ablecloud-team/autoscaler/pull/3): 내부 패치 SHA `b297d2ed89f67ea66da4dcde67abeade971fea01`. 각 minor의 전체 core/interface/options를 유지한 독립 checkout에 공통 Mold client만 적용. [4개 minor Actions 성공](https://github.com/dhslove/autoscaler/actions/runs/37329451678), [라이선스/형식 검사 성공](https://github.com/dhslove/autoscaler/actions/runs/37329451460).

| Kubernetes minor | AutoScaler baseline | baseline SHA | 판정 |
|---|---|---|---|
| 1.34 | 1.34.5 | `cb2123ed13148c38fbdb1ede42d8e99761ee2909` | 안정 원본 + 내부 패치 후보 |
| 1.35 | 1.35.2 | `2d42588803c71fe9b35dcd9e3669ac6bb550ca22` | 안정 원본 + 내부 패치 후보 |
| 1.36 | 1.36.1 | `35e8a280425c76a4040f57ae0fa232a952c02024` | 안정 원본 + 내부 패치 후보 |
| 1.37 | 고정 development main | `22575f5c8af3edd9a10ca10d7d9edef68d2982cd` | stable 미확보 / 공식 게시 차단 |

1.37.1 ISO는 빌드·등록 가능한 개발 시험 후보다. 31번 표시 이름에 `DEV`를 포함했다. 해당 minor의 stable AutoScaler와 실제 런타임 qualification을 확보하기 전 공식 지원·공식 Release로 승격하지 않는다.

SDK와 AutoScaler는 별도 client이며 공통 독립 서명 vector를 공유한다. Java 값 인코딩(공백 `%20`, 별표 유지, tilde `%7E`), parameter 정렬·소문자 canonicalization·HMAC-SHA256을 검증한다. SDK GET/POST, signatureversion 3/만료와 AutoScaler의 잘못된 map/list/async 응답을 회귀 검사했다. 31번 실제 SDK 요청의 capabilities/CKS 조회·제한 권한 거부·키 A/B 교체/폐기·만료, SHA1 요청 거부 및 AutoScaler 조회/폐기 키 거부를 확인했다. 시험 키는 폐기했고 비밀 값은 ISO/공개 증거에 포함하지 않았다.

이미지 digest와 소스/SDK module/Go build metadata는 각 recipe 및 manifest/provenance에 고정한다. AutoScaler 바이너리의 `vcs.revision`은 실제 minor baseline이며 내부 client 파일 hash와 내부 patch SHA를 별도로 기록한다. Go의 linked worktree metadata 누락을 피하도록 독립 Git clone에서 빌드하고 metadata가 없으면 게시를 거부한다. stock SHA1 이미지/manifest fallback은 제거했다. CSI는 별도 client SHA256 감사 전까지 기본 recipe에서 제외한다.

## ISO 독립 검사와 로컬 재현 범위

6개 Origin ISO를 별도 validator로 다시 열어 필수 payload/checksum, 실제 kubeadm/kubelet/kubectl 버전·ELF, 공식 keyless 서명, CNI/crictl/etcd 실행 파일, Kubernetes YAML, OCI manifest/config/compressed·uncompressed layer hash와 arch를 검사했다. UID 1000의 격리 containerd 1.7.28에 kubeadm tag 및 digest reference로 모두 import했다. workload는 실행하지 않았다.

요청한 추가 4개는 동일 `b3a9a20a` 소스로 WSL ext4에서 직접 빌드·독립 검증도 통과했다. 모든 6개 실제 kubeadm 바이너리로 external-etcd config를 검사했다. 1.31 이상은 `kubeadm.k8s.io/v1beta4`를 사용하고 actual version/`unix://` CRI endpoint를 지정하여 1.37의 v1beta3 거부에 대응한다.

| 추가 버전 | Local ISO SHA256 |
|---|---|
| 1.34.12 | `3e7c5a65295a7e729953fe03d7a3290ad0c1044ace8309e97c2661be5d547e04` |
| 1.35.9 | `7f9225822b8c7ad2a71e59e6840a891b5dda41d7f0d1c54f1dac61c0074a2ef6` |
| 1.36.5 | `cdb2a2b1cba6b70c5fe19021f6a0facbf0dfd12114a663dfa3cc95dd9e33af2a` |
| 1.37.1 | `701e66ef05c9b12e07b2cb0a9b13b663455507ae45e533d2ae31dfd630ff9d10` |

Local xorriso 1.5.4와 Origin 1.5.6 및 OCI archive envelope 차이로 최종 ISO hash는 채널 사이에 다르다. 공식 바이너리·이미지 content digest·source·recipe는 고정하지만 서로 다른 도구 환경의 바이트 재현성을 선언하지 않는다. 1.37.1은 같은 Local toolchain/cache에서 반복 생성한 두 ISO가 `701e66ef05c9b12e07b2cb0a9b13b663455507ae45e533d2ae31dfd630ff9d10`으로 동일했다.

## 다운로드/등록 운영 절차와 HTTP 302 실패 대응

1. 지원 version의 checked-in recipe를 선택한다. Local 빌드는 WSL ext4에서 README의 tool setup 및 `create-kubernetes-binaries-iso.sh --recipe ... --output ... --revision rN`을 사용한다. 미지원 입력은 시작 전에 거부한다.
2. Origin workflow에서 지정 version을 검증하고 trial Release로 게시한다. 모든 matrix job이 성공해야 게시 단계가 실행된다. 이미 게시된 source/revision/tag/image를 덮어쓰지 않는다. 수정은 새 commit/revision의 새 URL로 게시한다.
3. 공개 Release의 **ISO asset URL**과 `registration.json`의 `{SHA-256}` 값을 Mold Kubernetes 버전 등록에 사용한다. GitHub source ZIP, Actions 임시 artifact, 만료되는 CDN URL을 등록하지 않는다.
4. **GitHub permanent URL은 HTTP 302로 asset host에 이동한다. Mold의 기존 동적 전역 설정 `store.download.follow.redirects=true`가 다운로드 중 필요하다.** 기본 false이면 SSVM이 `302 Found`를 다운로드 실패로 처리한다. 이 옵션은 Kubernetes 이외의 store 다운로드에도 적용되므로 운영자가 설정 범위를 관리해야 한다. 시험에서 기존 false를 기록하고 등록 다운로드 동안 true로 변경했다.
5. 실패한 이번 시험 항목 6개만 supported-version 삭제 API/async job 성공을 확인한 뒤 다시 등록했다. 기존 ISO 3개는 삭제/변경하지 않았다. 영구 GitHub URL과 SHA256으로 새로 등록한 6개 모두 Ready가 됐다.
6. API Ready/100%, DB의 `DOWNLOADED`/Ready와 secondary의 실제 ISO SHA256을 대조한다. 시험 후 redirect 설정을 기존 **false로 복원**하고 재조회했다. 미래 GitHub URL 등록 시에도 필요한 다운로드 시간 동안 설정을 적용하거나 redirect 없는 검증된 배포 mirror를 사용한다.
7. 관리 서비스 `mold` active와 `/client/` HTTP 200을 확인했다. 노드 배포 시 별도 offering·kernel 최소 5.10·runtime/CNI 지원 여부를 사전 확인한다.

이 설정 확인은 신규 등록 UI의 KL-03 사전검사/운영 안내에도 반영해야 한다. 등록 Ready와 무관하게 Provider/AutoScaler 지원을 단순 capabilities/version 문자열만으로 판정하지 않는다.

## Cloud 모듈 검증과 남은 게이트

- WSL ext4에서 변경 `api`, `engine/schema`, `server`, `plugins/integrations/kubernetes-service` 모듈만 빌드했다. LB CIDR 변경/rollback 회귀를 포함한 server 테스트 18개가 통과했다. 기존 custom backend SSL 변경도 보존했다.
- ISO recipe/거부 계약 단위 테스트 11개, shell/YAML/Jinja normalization 검사, actionlint 및 diff whitespace 검사를 통과했다. 실제 ISO의 provider.yaml을 변조한 검사도 checksum 오류로 거부했다.
- tracked source snapshot의 RAT 결과는 기존 third-party license text 6개만 미인식이다. 기존 파일은 `docs/design/vm-snapshot-detail-tabs-20261004/assets/licenses/{AntDesignVue,Enquire,Vue,Vuex}-LICENSE.txt`와 `docs/design/vm-snapshot-list-20261003/assets/licenses/{AntDesignVue,Vue}-LICENSE.txt`이며 이 변경에서 수정하지 않았다. 새 소스의 라이선스 검사는 통과했다. 전체 License Check 성공으로 보고하지 않는다.
- 전체 Cloud build, RPM/JAR/UI 배포, 클러스터 생성·Service LB/VPC·pending Pod scale-up/down·PDB/quota·확장/업그레이드/삭제 E2E는 실행하지 않았다. API CIDR 실제 규칙 변경, Pod 실행 digest와 Secret rotation도 후속 runtime 게이트다.
- SDK Upstream 공식 버전 게시 → Provider Origin replacement 제거 → Provider/AutoScaler Upstream source/image 재빌드 → recipe 승격 → minor별 runtime qualification `PASS`/증거 URL 확보 → Europa 병합 source의 공식 tag Release 순서다. 공식 publisher는 Origin source/개발 AutoScaler/runtime pending을 거부한다.
- Provider P01~P06/AutoScaler V12·V13·V16~V19의 런타임 검증과 KL-04/06/07를 완료하기 전 #1228/Epic을 닫지 않는다. 원본 adapter의 zero-size/autoprovisioning/강제 노드 삭제 미구현 기능을 지원으로 표시하지 않는다.
