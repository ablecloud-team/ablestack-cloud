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

# CNI 선택 계약 복구 검증 (#1396)

2026-10-17 KST. Epic #1395 누적 PR #1409의 첫 구현이다. 기준은 최신 upstream/ablestack-europa `dc31262567f2afe5f6278fae61c1cef7e307fe3e`, 구현/배포 소스는 `34b37e9870c`이다. WSL ext4 전용 worktree와 `dhslove/epic-1395-cni` 브랜치를 사용했고 기존 Windows/WSL FTCTL 작업 사본은 수정하지 않았다.

## 변경과 검증 범위

- 공용 선택기에 종류를 명시하여 `listCniConfiguration/listcniconfigurationresponse/cniconfig`와 CNI 권한을 연결했다. 기본 UserData 계약은 유지한다.
- 계정/도메인/프로젝트/Zone/고급 설정 변경과 CNI 변경·해제 시 UUID/변수/값을 초기화한다. 소유 범위와 조회 세대로 지연 응답을 차단한다.
- params 없는 CNI와 빈 응답을 안전하게 처리하고, 선택한 CNI의 조회 실패·유실·권한 상실 중에는 제출을 차단한다. 선언된 현재 변수만 요청에 넣고 비동기 폼 검증 전후의 선택/소유 범위를 대조한다.
- 생성 검토 요약에 선택 CNI를 표시한다. 등록 검증·사용처/삭제 보호·본문 보기·스타일 통일은 이 변경에 포함하지 않았다.

설계: [cni-selection-1396.ko.md](../../design/cni-selection-1396.ko.md).

## 자동 검증

- CNI 실제 소비 컴포넌트 결합, Kubernetes 생성, 공용 선택기: **3 suite / 47개 통과**.
- VM 사용자 데이터 소비/재설정: **2 suite / 58개 통과**. 합계 **5 suite / 105개**.
- 변경 Vue 2개와 테스트 파일 lint 통과.
- 결합 테스트는 CreateKubernetesCluster와 실제 UserDataSelection을 함께 렌더링하고 Ant Design 컨트롤을 대체한다. 제출 요청 검증은 같은 선택/소유 상태를 사용해 제출 메서드를 검증한다. 관리자/도메인 관리자/일반 사용자, 프로젝트, 페이지, params 없음, 변경/해제, 지연 응답, 오류/재시도, 권한 상실 및 검증 중 소유 범위 변경을 포함한다.
- 실행 환경 WSL ext4 / Node 20.20.2 / `NODE_OPTIONS=--openssl-legacy-provider`, Jest `--runInBand --coverage=false`. 전체 커버리지 계측이나 전체 Release CI 결과로 해석하지 않는다.
- Java 변경이 없어 Maven 변경 모듈 빌드는 필요하지 않다. 전체 Cloud/Maven/RPM 빌드와 수동 Actions 실행은 하지 않았다.

[집중 테스트](focused-final.log) · [기존 소비자 회귀](consumer-tests.log) · [lint](lint-final.log).

## 전용 테스트와 기준선

31번 Mold에서 기본 계정 CNI 2개(변수 있음/없음), 프로젝트 CNI 1개, 프로젝트 `cni1396-project`, 격리 네트워크 `cni1396-net`을 준비했다. 기존 CNI 3개와 Kubernetes 7개, 기존 UserData와 소유 범위는 별도 API/DB 기준선으로 보존한다. 기존 Kubernetes 1개의 Alert 상태는 테스트 전부터 존재한다.

전용 CNI [custom-cni.yaml](custom-cni.yaml)은 ISO의 Calico 매니페스트를 복사해 MTU 1450으로 적용하고 configmap annotation으로 식별한다. `write_files`와 systemd drop-in을 추가해 기본 Kubernetes cloud-config를 보존하는 기존 #1323 병합을 함께 검사한다. `audit_token`은 API의 `cniconfigdetails`를 통해 게스트 메타데이터로 전달되는 변수이다. `{{ audit_token }}`의 임의 문자열 치환을 서버가 수행한다고 가정하지 않는다.

## 환경 불일치 진단과 재검증 조건

첫 UI 생성 건 `cni1396-ui`(cluster 76)는 CNI UUID/변수, 컨트롤 표식/메타데이터, MTU 1450, 두 노드 Ready와 Calico rollout까지 확인했으나 Mold 생성 작업은 providerID 일치 검사에서 대기했다. 조사 결과 `/usr/share/cloudstack-management/cks/conf/`의 control/worker/additional-control 파일 3개에 이전 내용이 남아 있었고, 설치 JAR 리소스와 최신 Europa 소스에는 있는 `--cloud-provider=external` 처리가 없었다. ISO에는 provider.yaml이 있었지만 실제 게스트 kubelet은 해당 옵션 없이 시작되어 providerID가 비었다. CNI 선택기의 오류로 분류하지 않는다.

3개 파일을 `/root/cni-1396-cks-conf-20261017-032927`에 백업하고 **기존 설치 JAR의 동일 리소스**로 동기화했다. 세 파일의 해시는 최신 Europa 소스와도 일치한다. JAR 교체·관리 서비스 재시작·새 Java 빌드는 하지 않았다. [동기화 해시와 백업](bootstrap-config-sync.txt), [수정 전 노드/ISO 진단](provider-diagnosis.txt).

첫 생성 건은 정리를 위해 표준 `node.cloudprovider.kubernetes.io/uninitialized` taint로 CCM의 실제 초기화를 요청했고, CCM이 실제 VM UUID의 providerID를 채운 뒤 생성 작업이 종료됐다. providerID/Cloud DB 상태를 직접 덮어쓰지 않았다. 이후 API로 cluster 76을 삭제했다. **이 수동 복구 건을 최종 통과로 계산하지 않는다.** 별도의 `cni1396-final` 클러스터를 새 UI 요청으로 생성해 게스트 보정 없이 검증한다. [첫 실행 복구 기록](initial-run-recovery.txt).

CNI cloud-config와 변수는 기존 서버 계약에 따라 주 컨트롤 노드에 전달되고, Calico DaemonSet이 워커 네트워크를 구성한다. 워커에 컨트롤 전용 표식 파일을 요구하는 것으로 판정하지 않으며 워커의 실제 Calico/Ready/인터페이스 MTU를 확인한다.

## 최종 실환경 결과

`cni1396-final` (cluster 77, UUID `5bdf23af-adec-4f03-984a-56ad4a559c80`)을 **새 UI 요청으로 생성**했다. 생성 작업 `122f4eec-973c-4dee-8fcc-c68fdba98f6f`는 03:31:06 시작, 03:33:21 성공(jobstatus=1, resultcode=0), Mold API/UI `Running`, DB provisioning phase `Ready`이다. 이 생성 건은 게스트 보정·서비스 재시작·DB 수정을 하지 않았다.

- 기본 계정/전용 프로젝트 목록, 선택·변경·해제와 소유 범위 전환 시 초기화를 실제 UI에서 확인했다. params 없는 구성도 정상 선택되고, 이전 입력값은 재선택 시 빈 값으로 초기화됐다.
- UI 선택 UUID `1edff270-5a2e-44b5-9d6a-d7c1db7f0790`가 API/DB에 일치하고 DB 변수는 `{audit_token=UI-1396-ok}`이다. 게스트의 메타데이터 값도 정확히 일치한다.
- 컨트롤 `i-2-609-VM`과 워커 `i-2-610-VM`은 자동으로 실제 VM UUID의 external-cloudstack providerID를 얻었다. 두 노드 Ready, 기본 kubeadm/배포 파일 보존, 부트스트랩 서비스 Result=success/ExecMainStatus=0, API readyz=ok이다.
- Calico configmap MTU **1450**, 사용자 annotation **calico-3.33.0-mtu1450**, Calico node/controller rollout 성공, kube-system 모든 Pod Ready를 확인했다.
- 워커에 ISO에 미리 실린 pause 이미지로 전용 검사 Pod를 배치하여 Ready/Pod IP와 실제 Calico veth MTU 1450을 확인했다. 워커에 Pod가 없는 시점에는 veth가 없으므로 단순 인터페이스 존재 검사를 네트워크 실패로 분류하지 않는다. 검사 Pod는 삭제했다.
- 첫 관측 로그의 providerID 빈 값/Headlamp 초기 준비 상태와 구분하여 [최종 안정 상태](final-guest-settled.txt)에서 자동 providerID·모든 Pod Ready를 다시 확인했다.

[API/작업 성공 영수증](final-api-receipt.json) · [DB 일치](final-db-receipt.txt) · [컨트롤 CNI/변수/부트스트랩](final-guest-control.txt) · [최종 노드/Pod](final-guest-settled.txt) · [워커 검사 Pod](final-worker-probe.txt) · [워커 MTU](final-guest-worker.txt).

화면: [프로젝트 선택](project-selected.png) · [변수 없는 구성](account-no-params.png) · [변수 입력](account-variable.png) · [최종 생성 검토](final-create-review.png) · [실행 상태](final-running.png) · [연결 CNI](final-cni-link.png).

## UI 빌드와 배포

UI production build가 완료됐다. [빌드 요약](ui-build-summary.log)의 번들 크기 및 오래된 browserslist 안내는 집중 기능 검증과 구분한다. 정적 파일 **870개 해시 일치**, WEB-INF/META-INF(존재 시)·config.json·JAR·관리 PID **1247065** 보존, Mold active, /client/ HTTP 200이다. 백업 `/root/cni-1396-ui-20261017-031934/static-before.tgz`.

[배포 검증](deployment-verification.txt) · [산출물 해시](static-artifact.sha256). 정적 UI 외에는 위 환경 불일치를 해소하기 위해 CKS 설정 3개를 설치 JAR 리소스와 동기화했다. 전체 Cloud/Release CI, 전체 Maven 빌드, RPM 빌드, 수동 GitHub Actions는 실행하지 않았다.

## 정리 및 기존 데이터 보존

최종 검사 Pod, 최초/최종 클러스터 2개와 노드 VM 4개, CNI 3개, 프로젝트·프로젝트 계정·네트워크·라우터·볼륨을 정리했다. 생성 과정의 전용 컨트롤러 사용자 2개도 API로 제거했고 관련 활성 API 키는 0개이다. 기존 사용자/키는 변경하지 않았다.

- API의 기존 CNI **3개**, Kubernetes **7개**의 UUID/이름/소유 범위/상태/CNI 연결이 기준선과 일치한다.
- 기존 UserData/CNI 본문 해시·변수·소유 범위 및 Kubernetes 상태/소유/CNI 연결의 DB 직렬화 **12행이 바이트 단위로 동일**하다.
- 전용 활성 UserData/CNI·클러스터·프로젝트·네트워크·VM·노드 볼륨·클러스터 연결·컨트롤러 사용자/키·프로젝트 계정은 모두 **0**이다. 호스트 31.1/2/3의 전용 libvirt 도메인도 0개이다.
- Mold PID **1247065**, JAR/index 해시 보존, WEB-INF 존재, active 및 /client/ HTTP 200을 최종 확인했다.
- 기존 WSL FTCTL 작업 사본의 변경 diff 해시는 작업 전후 `4616f42e7bfef16d6dae3c185e8e23eb1fd80d91a0d941f3158958a858f77a45`로 동일하다.

[정리 API 영수증](cleanup-api-receipts.json) · [클러스터 삭제 작업](cleanup-cluster-jobs.json) · [컨트롤러 사용자 제거](cleanup-controller-users.json) · [활성 잔여 DB](cleanup-db.txt) · [기존 API 목록 대조](cleanup-api-inventory.json) · [호스트/서비스 최종 상태](runtime-final.txt) · [정리 후 CNI 화면](cleanup-cni-list.png).

## 후속 범위

#1396 설계·구현·집중 검증·31번 배포/실검증/정리를 완료했다. Epic #1395는 1/13 완료이며 누적 PR #1409는 미병합 초안으로 유지한다. 다음은 독립 P1 #1397 등록 검증/API 메타데이터, #1398 삭제 대상 유형 분리이며 이후 #1399 사용처/삭제 보호 → #1400 사용처 UI → #1403 삭제 화면으로 진행한다. 가상머신 표준 기반 목록/상세/탭/탭 콘텐츠/대화상자 전체 스타일은 #1408에서 추적한다.
