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

> 중간 기록입니다. 앱 재실행 후 구현·배포·UI 검증을 완료했습니다. 현재 결과와 유지 자원은 [최종 보고서](README.ko.md)를 확인하세요.

# #1566 재개 지점 — 앱 재실행 후 UI 검증 필요

2026-11-05 KST. 같은 Epic #1554 브랜치 `dhslove/epic-1554-guest-networks` / Open Draft PR #1577에서 작업 중이다. **구현 초안과 로컬 시험만 완료한 중간 기록**이며 #1566은 Open으로 유지한다. 31·32 배포·UI·실제 HAProxy 적용 검증은 아직 하지 않았다.

## 완료한 준비

- 사용자 지시대로 이전 #1567 시험 네트워크8개·VM2대·자동 라우터4대·역할4계정/2도메인을 공식 API로 정리했다. 양쪽 활성 network/NIC/VM/router/volume/account/domain 잔여0. [31](31-cleanup-done.json), [32](32-cleanup-done.json).
- 양쪽 전용 seed 오퍼링에서 keepaliveenabled=false 단독 업데이트가 저장에 반영되지 않고 true로 남는 현상을 DB 읽기·API 전후로 재현했다. listNetworkOfferings 응답에 keepaliveenabled 자체가 없는 점도 확인했다. [31](31-keepalive-before-reproduction.json), [32](32-keepalive-before-reproduction.json).
- 변경 초안: API 응답에 Keepalive 값 추가, 서버 조회 매핑·true/false 단독 수정·생략 보존, 생성 시 LB capability 입력 유무와 무관하게 maxconnections 반영, 음수 최대 연결 수 거절.
- UI 초안: 제공자/유형 판정, 전용 LB 연결 설정 대화상자·현재값 재조회·변경 필드만 전송·false/미확인 구분·결과 재조회. 생성/복제 폼에도 같은 필드와 조건을 연결했다. 생성/복제는 MoldDialog lg·고정 푸터, 설정 수정은 MoldDialog md를 사용한다. **실제 VM 기준 화면 비교와 반응형/테마 판정은 미실행**이다.
- WSL ext4 변경 api 모듈 install/1,129 tests, server 모듈 package/29 tests, UI21 tests·변경 lint 성공. Checkstyle은 API 기존6·server 기존62건으로 실패, 이번 변경 Java 파일 오류0. [상태](checkpoint.json). 전체 Cloud/qemu/수동 Actions 빌드를 시작하지 않았다.
- 주 FT dirty 작업의 HEAD·staged/unstaged diff hash를 보존했다. [전](protected-before.json), [후](protected-after.json).

## 앱 재실행으로 보류한 부분

브라우저 제어가 Work 설정에서 비활성화돼 실제 UI 접근이 거절됐다. 사용자는 설정 적용에 앱 재실행이 필요하다고 답했다. 따라서 UI 성공/배포 완료를 표시하거나 이슈를 닫지 않는다.

재실행 후 다음 순서로 이어간다.

1. 현재 branch/native·WSL·origin HEAD 및 dirty 상태를 다시 확인하고 이 기록과 RAW 도우미를 읽는다. 새 브랜치/PR을 만들지 않는다.
2. 브라우저를 선택하고 세션 이름을 지정한 뒤 31·32 로그인, 실제 컴퓨트 > 가상머신 목록/상세/탭/대화상자/breadcrumb/모바일 표준을 확인한다.
3. UI 생성/복제 폼의 service/provider 변경과 true/false/unknown·빈 값·경계·실패 입력 보존을 추가 단위/통합 시험하고 코드 초안을 검토한다. 특히 L2/Shared/미지원 제공자에는 수정 필드를 표시/전송하지 않는다. 현재 상세 metadata 표시는 미지원 유형에서 숨김 여부를 검토한다.
4. 새 전용 네트워크/VM/LB를 준비한다. 31 GFS2·32 Glue RBD의 기존 운영 자원을 보존한다. seed 오퍼링은 현재 LB 없음·네트워크 없음 상태다.
5. 변경 모듈 산출물만 배포한다. UI는 활성 webapp 정적 allowlist만, WEB-INF/META-INF/운영 config 보존. 관리 JAR 기준 SHA `623e7c2d738347b9e1eb3ef630cb3e2cd4a94ef3e1aff6aaa206db003ac52350`, 변경 예정 class는 NetworkOfferingResponse / NetworkOfferingJoinDaoImpl / ConfigurationManagerImpl이다. 배포 전 현재 SHA를 다시 확인하고 전체 JAR 다른 엔트리를 보존한다.
6. UI 생성→복제→수정의 실제 요청·저장값과 라우터 HAProxy를 대조한다. 저장 직후 기존 LB가 바뀌지 않는 점, 후속 LB 규칙 적용/명시 재시작 시점, maxconn·httpclose/keepalive, 실제 LB 접근을 따로 검증한다. 자동 망 재시작 없음.
7. 0·1·Integer.MAX_VALUE·음수/overflow와 true/false·무변경·미확인 조회를 분리한다. 큰 값은 실제 적용하지 않고 전용 미사용 오퍼링의 API 계약만 검증한다. **0의 실제 라우터 버전 의미는 haproxy -c 및 해당 버전 원문으로 확인**한다. 현재 UI 문구의 0 자동 산정은 배포 전 이 검증과 맞춰 최종 확정한다.
8. Isolated VR 경로를 기본 실제 적용 범위로 하며 VPC/InternalLB/Shared ELB/외부 제공자는 코드 지원·환경 부재·실행을 구별한다. ELB 소스의 offering lookup 경로도 추가 검토가 필요하다. LB CRUD·SSL/health/AutoScale 전체 완료로 확장하지 않고 #1573/#1576에 소유 범위를 남긴다.
9. 실제 UI 변경을 캡처하고 즉시 전환·목록/상세/탭/breadcrumb·1280/768/390 light/dark·키보드·가독성을 확인한다. 새 검토 자원을 유지하고 정책/보고/배포/복구/캡처를 게시한 뒤 #1566을 닫는다. Epic 기존23순서·63행렬과 Draft PR #1577을 갱신한다. 다음 이슈는 순서상 #1563이며 자동 착수하지 않는다.

## 유지한 seed와 도우미

| 클러스터 | 이름 | UUID |
|---|---|---|
| 31 | qa-gn1566-31-seed | d5f8bded-2ead-4d13-addc-17fe7ae8c7d7 |
| 32 | qa-gn1566-32-seed | b567eab0-6a63-455d-9b00-279d5e122d33 |

이들은 maxconnections257·Keepalive true인 전용 복제 오퍼링이며 네트워크/VM은 아직 만들지 않았다. API clone seed만 실행했으므로 UI clone 시험 결과가 아니다.

RAW Windows: `C:/Users/ablecloud/Documents/GitHub/dhslove/ablestack-cloud/docs/validation/guest-network-1566-20261105`.
WSL clone: `/home/ablecloud/work/dhslove/cloud-epic-1554`.
WSL 로그/빌드: `/home/ablecloud/work/guest-network-1566-validation`.
RAW `qa_lb.py`는 이전 정리의 main을 재실행하지 말고 Mold/db/call/save만 import한다. `seed.py`는 미배포 결함 재현용이므로 패치 배포 뒤 재실행하면 정상 false가 적용되어 의도적으로 assertion이 실패한다. 원본 WSL build_ui 경로는 ui/dist이고 아직 서버에 올리지 않았다.

커밋/PR/이슈에 자격 증명을 게시하지 않는다. 사용자 재실행 후 브라우저 기능이 켜졌는지 먼저 확인한다.
