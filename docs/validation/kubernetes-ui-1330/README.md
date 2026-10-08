<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
# Europa Kubernetes UI 실제 시험 기록

대상은 #1330 및 시험 중 발견한 #1331/#1334이다. 가상머신 목록·상세 표준, 공통 네트워크 화면, 관리형/외부 관리형 생성과 생명주기를 실제 31번 Mold UI에서 검증했다. 세부 판정은 [UI-MATRIX.md](UI-MATRIX.md), 승인 설계는 [설계 문서](../../design/kubernetes-ui-unification-20261008/)를 참조한다.

## 변경과 시험 범위

- 목록은 VM 공통 표·페이지네이션·우클릭 작업을 사용한다. 상세 정보 탭은 정보만 표시하고 작업은 전역 작업 메뉴/좌측 정보 우클릭으로 제공한다. 이벤트·코멘트는 기존 공통 화면을 사용한다.
- 관리형 VM 탭은 확장을 메인, 외부 노드 추가를 보조 작업으로 표시한다. 외부 관리형은 노드 연결을 메인으로 표시하고 API가 받지 않는 생성 필드는 제출하지 않는다.
- 공통 네트워크 툴바는 왼쪽 메인 버튼 다음 업데이트, gap8px이다. PF는 프로토콜·공인 포트 pair·게스트 포트 pair·VM/NIC를 목적별로 구분한다. LB는 선택된 VM/NIC만 연결하며 무선택 시 규칙만 생성한다. 정책/대상/태그 작업과 API·Service 소유 규칙 보호를 공통 구성요소로 제공한다.
- 다크모드 안내/레이블/입력/표/버튼은 테마 토큰을 사용한다. section은 기본 글자 크기의 굵기와 선으로 구분한다. Kubernetes 아이콘은 currentColor를 따른다.
- 부하분산 업데이트는 규칙·대상·정책 조회가 끝난 한 번의 결과를 반영한다. 대기/실패에는 기존 목록을 보존하고 버튼에만 갱신 상태를 표시한다. 상세 재조회가 이미 로드된 탭/대화상자를 다시 만들지 않는다.
- 확장·축소 폼의 고정 450px 너비를 제거해 760px 공통 대화상자 전체 너비를 사용한다. 기본 오퍼링/AS 전환은 전체 행, 역할별 오퍼링과 AS 최소·최대는 두 열, 390px에서는 한 열이다. 클러스터 정보 표도 좁은 화면에서 한 열로 바뀐다. 버튼 간격은8px이며 글자 크기를 확대하지 않는다.
- 공통 AutogenView 작업 창은 실행 트리거를 보존해 Escape/취소 후 초점을 복구한다. 상세 메뉴는 남아 있는 작업 버튼으로 복귀하며 삭제된 트리거 및 새로 열린 모달의 초점을 빼앗지 않는다.
- PF/LB 공통 VM 후보는 `state=Present`로 페이지네이션/count 전에 Destroyed/Expunging을 제외하며 Stopped는 유지한다. 조회 실패 시 로딩도 해제한다.
- 실제 UI에서 발견한 기본 KVM 제출, strict custom root 반복 초기화, 외부 관리형 번역 키, 언어 변경 시 VM 머리글 잔류, SourceBased 세션 정책 값 복원 오류도 보완했다.

## 환경 및 실제 동작 결과

31번 관리 서버와 31.1/31.2/31.3 호스트를 사용했다. Primary는 SharedMountPoint `/mnt/glue-gfs`, 실제 findmnt 결과 GFS2 `/dev/mapper/vg_glue-lv_glue`이다. 노드와 VR 모두 strict GFS2 오퍼링을 선택했으며 clvm/clvm-ng는 사용하지 않았다.

관리형 cluster70에서 생성·워커 확장/축소·AS 활성화/비활성화·정지/시작·Affinity·1.34.9→1.34.12 업그레이드·외부 워커 조인/분리·삭제를 수행했다. 각 단계는 UI/API 및 실제 Node Ready 결과로 확인했다. 최종 기본 KVM 제출 재시험 cluster75는 1.34.12로 Running, control1/worker1이 Ready이며 둘 다 root20GB GFS2 볼륨을 사용한다. Provider/CNI/DNS/Headlamp 등 13개 pod가 Running 1/1이다.

외부 등록71/73/74에서 등록·역할별 VM 연결/분리·노드 보존 삭제를 실행했다. 외부 등록 삭제는 state가 Running으로 남아 있어도 removed 타임스탬프로 판정한다. VM352의 관리형 외부 워커 시험은 #1314에 기록한 cloud-init/DHCP/SSH/sudo/swap 준비조건을 시험 VM에 보정한 후 통과했다. 준비 전 원본 이미지의 무수정 성공으로 주장하지 않는다.

제한 CIDR `10.10.21.101/32`와 별도 시험 VM의 18080 서비스로 PF18443/LB18444 실제 통신을 확인했다. FW/PF 생성·교체/편집·삭제, LB 선택 VM/NIC 생성·편집·연결 해제/재연결·태그·세션 고정 경로를 실행했다. 운영 관리 API6443·SSH 규칙은 작업 비활성화로 보호했다.

## 최종 빌드 및 배포

관련18 suites / 152 tests PASS. Node20.20.2/npm10.8.2, WSL ext4에서 production UI 모듈을 빌드한다. 명령은 `NODE_OPTIONS=--openssl-legacy-provider npm run build`이다. webpack4의 OpenSSL 호환 옵션과 bundle 크기 경고가 있으며 전체 Cloud 빌드는 실행하지 않았다.

최종 UI 패키지 `kubernetes-ui-1330-20261009-001905.tgz`의 SHA-256은 `b46fb19232105395fc05a9bb648646fc2991dd6886d8a1433e1d036af1ec1de0`이다. 31번 active webapp의 정적 파일 840개 해시 일치를 확인했다. WEB-INF/config.json과 mold PID5641을 보존했고 mold active 및 `/client/` HTTP200을 확인했다. 백업은 `/root/kubernetes-ui-1330-deploy-20261009-001914/static-backup.tgz`이다. 서버 전체 디렉터리 교체와 rsync --delete는 사용하지 않았다.

최종 배포에서 부하분산 업데이트를 클릭한 직후에도 기존 2개 규칙과 대상 VM이 유지됐다. 전체 skeleton0/spinner0, 업데이트 버튼 loading1, 버튼 간격8px을 확인했고 번역 키가 화면에 노출되지 않았다. SourceBased 정책의 `10k/30m` 재열기, `20k/40m` 수정 후 재열기, 정책 제거까지 실제 UI/API로 통과했다. 다크모드 레이블·입력값·안내문을 실제 화면에서 재확인했다.

## 시험 자원 정리

관리형70/실패 등록72와 외부 등록71/73/74는 삭제 이력을 확인했다. 수동 시험 FW/PF/LB와 SourceBased 정책은 `removed` 시각으로 삭제를 검증했다. 규칙의 state가 Active/Add로 남는 삭제 이력을 활성 규칙으로 해석하지 않는다. 현재 network258의 활성 규칙은 최종 cluster75의 관리 SSH2222–2223/API6443용 5개뿐이다.

시험 VM352/356은 UI에서 영구 삭제 선택 없이 Destroyed 상태로 정리했으며 복구 가능한 삭제 상태이다. 물리 디스크 완전 제거로 판정하지 않는다. cluster75/network258은 최종 화면 검토용으로 유지한다. URL 등록 시험 ISO78/79와 로컬 업로드 시험 ISO80은 연결된 ISO까지 삭제하고 각각 ID 조회0건을 확인했다. 장기 시험 cluster31–36은 모두 Running, removed=NULL이며 변경하지 않았다.

## 최종 보완 및 다운로드 시험

확장 초안에서 워커1→2의 +1/4vCPU/8,192MB, 축소 초안에서 워커2→1의 -1/-4vCPU/-8,192MB 요약과 AS 최소2/최대3 필드 배치를 light/dark에서 확인했다. 초안은 취소했으며 장기 시험 클러스터의 AS 및 노드 수를 변경하지 않았다. 390×844에서 클러스터 정보가 한 열로 표시되고 전체 body 가로 넘침이 없음을 확인했다.

실제 network258의 기존 VM 후보4건에는 Destroyed VM352/356이 포함됐다. PF/LB 후보와 pager는 Present 적용 후2건이며 삭제 VM 이름 검색은0건이다. 검색 초기화 후2건이 복원되고 활성 control VM의 기본 NIC10.133.0.191를 선택할 수 있다. 두 테마의 레이블·머리글·선택 안내를 실제 UI로 확인했다. Stopped 유지 및 paging/count 계약은 단위 회귀 검사로 확인했다.

최종 cluster75의 구성 다운로드를 실제 UI에서 실행하고 사용자 Windows 저장 파일 `ui1330-final-kube.conf` 5,636bytes를 확인했다. API 구성과 SHA-256이 일치하며 사용자 저장 파일로 kubectl을 실행해 Node2개 Ready 및 시스템 Pod13개 Running을 확인했다. 인증 내용은 게시하지 않는다.

사용자 승인으로 `store.download.follow.redirects`를 잠시 true로 변경해 GitHub Release ISO의 URL 등록 및 Ready/Successfully Installed를 확인하고 원래 false로 복구했다. 공개 Release ISO832,899,072bytes의 SHA-256을 확인한 뒤 사용자가 로컬 파일을 선택했고 실제 UI 제출로 전송·Ready/Successfully Installed까지 확인했다. 파일 선택을 실제 전송 성공으로 대신 판정하지 않았다. 시험 ISO3개는 정리했다.

별도 VPC·Tungsten·SSL·LB AS 그룹 환경은 이번31번 실 동작 통과로 판정하지 않는다. Kubernetes/ISO 목록의 Shift+F10·Escape와 Tab/Shift+Tab 순환·Escape/취소 및 트리거 초점 복귀를 확인했지만 전체 접근성 인증을 의미하지 않는다. 일반 공통 화면의 API 조건과 단위 검증 범위는 UI-MATRIX에 구분한다.

## 추가 다크모드 재검증

사용자 첨부 및 실제 31번 화면에서 관리 주체 / Service UID 내장 pagination의 prev/next 배경이 rgb(255,255,255)이고, Headlamp 안내 제목 4개가 rgba(0,0,0,0.65)임을 확인했다. 기존 일반 입력/문단 검사로 해당 보조 목록과 Timeline 제목까지 통과한 것으로 해석하지 않는다.

관리 주체 목록을 공통 detail-tab-pagination의 small pager로 변경했고 전체 항목/10·20·40·80·100 페이지 크기 선택을 적용했다. 갱신은 현재 페이지를 보존하며 행 감소로 범위를 벗어난 페이지는 유효한 마지막 페이지로 보정한다. 공통 dark-mode의 prev/next 버튼 배경/테두리도 보완했다. 최종 실제 펼친 목록에서 흰 배경 제거, 비활성 화살표의 어두운 상태, 전체 1건 및 크기 선택을 light/dark에서 확인했다. 실제 UI에서 페이지 크기를 10→20→10으로 바꾸어 값 반영과 행 유지도 확인했다. 다중 페이지·갱신 보존·행 감소 경계는 단위 회귀로 확인했다.

Headlamp 접속·읽기 전용 계정과 15분 토큰·기존 Kubernetes Dashboard·접근 계정 정리 제목 4개는 Timeline content가 기존 테마 primary 텍스트를 상속하도록 변경했다. head/tail도 테마 surface/border를 따른다. 실제 두 테마에서 네 제목의 글자 크기 14px 유지, 대비 및 본문/링크/명령 예시 가독성을 확인했다. 최종 computed style 기준으로 다크 텍스트 rgb(240,243,246)/배경 rgb(34,40,47)의 대비는 13.35:1(수정 전 1.30:1), 라이트 텍스트 rgb(31,41,55)/흰 배경의 대비는 14.68:1이었다. 자격증명은 생성하거나 게시하지 않았고 kubeconfig는 계속 숨긴 상태로 검증했다.

![관리 주체 목록 다크 페이지네이션 수정](images/ownership-pagination-dark-after.jpg)
![관리 주체 목록 라이트 페이지네이션](images/ownership-pagination-light-after.jpg)
![Headlamp 안내 다크 제목 수정](images/headlamp-labels-dark-after.jpg)
![기존 Dashboard·정리 제목 다크 수정](images/headlamp-labels-dark-cleanup-after.jpg)
![Headlamp 안내 라이트 제목](images/headlamp-labels-light-after.jpg)

<details>
<summary>실제 수정 전 기준 화면</summary>

![관리 주체 목록 흰 pagination 버튼](images/ownership-pagination-dark-before.jpg)
![Headlamp 검정 제목](images/headlamp-labels-dark-before.jpg)

</details>

## 실제 화면 증거

각 이미지는 실제 배포 Chrome 화면이며 목업이 아니다. 구성 원문/자격 증명이 보이는 캡처는 게시하지 않는다.

### 관리형 생성 및 노드 작업

![최종 관리형 생성 검토: GFS2, root20GB, 1.34.12](images/managed-review-dark-final.jpg)
![관리형 노드 탭: 확장 메인, 외부 노드 보조, 버튼 간격](images/nodes-managed-dark-final.jpg)

### 액세스 및 네트워크

![기본 숨김 구성 및 액세스 카드](images/access-dark-final.jpg)
![PF 포트 pair와 VM/NIC 목적 구분](images/pf-create-dark-final.jpg)
![방화벽 다크모드 입력](images/firewall-create-dark-final.jpg)
![LB 다크모드 대상 VM 머리글과 선택 안내](images/lb-targets-dark-en-final.jpg)
![LB 라이트모드 대상 선택](images/lb-targets-light-en-final.jpg)

### 최종 갱신 및 세션 정책 재검증

![부하분산 갱신 중에도 규칙과 대상 VM 유지](images/loadbalancers-refresh-dark-final.jpg)
![SourceBased 재열기: 10k/30m 복원](images/lb-stickiness-restored-dark-final.jpg)
![SourceBased 수정 후 재열기: 20k/40m 유지](images/lb-stickiness-updated-dark-final.jpg)
![URL ISO 등록 후 Ready](images/iso-redirect-ready-dark.jpg)
![로컬 ISO 실제 전송 후 Ready](images/iso-local-ready-dark-final.jpg)

### 확장·축소 및 삭제 VM 제외 보완

![확장 폼 다크모드: 대화상자 전체 너비](images/scale-layout-dark-final.jpg)
![확장 폼 라이트모드](images/scale-layout-light-final.jpg)
![AS 최소·최대 필드 다크모드](images/scale-autoscaler-dark-final.jpg)
![축소 초안의 감소 요약](images/scale-shrink-light-final.jpg)
![PF 후보2개와 기본 NIC](images/pf-present-nic-dark-final.jpg)
![삭제 VM 이름 검색0건](images/pf-deleted-search-empty-dark-final.jpg)
![LB 활성 후보 및 NIC 다크모드](images/lb-present-nic-dark-final.jpg)
![LB 활성 후보 라이트모드](images/lb-present-vms-light-final.jpg)
![390px 확장 폼 클러스터 정보](images/scale-mobile-dark-final.jpg)
![390px 확장 폼 한 열 입력](images/scale-mobile-fields-dark-final.jpg)

![390px AutoScaler 최소·최대 입력](images/scale-mobile-autoscaler-dark-final.jpg)
![키보드로 연 Kubernetes 목록 메뉴](images/list-keyboard-menu-dark-final.jpg)
![키보드로 연 ISO 목록 메뉴](images/iso-keyboard-menu-dark-final.jpg)

![VM 상세 작업 버튼 초점 복귀](images/focus-return-detail-dark-final.jpg)

### 상세 작업 및 공통 탭

![상세 정보 탭 및 작업 메뉴](images/detail-actions-dark-final.jpg)
![좌측 정보 우클릭 작업](images/detail-context-dark-final.jpg)
![외부 관리형 작업 메뉴](images/external-actions-dark-final.jpg)
![공통 이벤트 탭](images/events-dark-final.jpg)
![공통 코멘트 탭 생성 검증](images/comments-dark-final.jpg)

### 좁은 화면

![390px 부하분산 공통 툴바](images/lb-mobile-dark-final.jpg)
![390px 대화상자](images/lb-dialog-mobile-dark-final.jpg)

캡처는 해당 기능을 시험한 시점의 실제 화면이다. 마지막 확장·축소/후보/ISO 검증 증거를 별도로 제시하며 모든 이미지가 동일 시점의 클러스터 상태를 의미하지 않는다.
