<!-- Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. -->

# CNI / 일반 UserData 삭제 대상 분리 (#1398)

Epic #1395의 세 번째 구현 단위다. 공용 저장소를 사용하더라도 CNI와 일반 UserData의 삭제 API는 서로의 유형을 삭제하지 않아야 한다. 현재 구현은 deleteCniConfiguration이 deleteUserData에 그대로 위임하여 양방향 혼용이 가능하다. 계정 간 무단 삭제 문제로 확대하지 않는다.

## 요청과 권한

API 명령·파라미터·응답 및 상속된 UUID `OperateEntry` ACL은 유지한다. 권한 검사는 API 진입부에서 실행되고, 공용 서버 경로는 기존 `finalizeOwner`의 계정/도메인/프로젝트 판정과 root 관리자의 제거된 계정 조회 예외를 보존한다. 소유자가 아닌 일반 사용자와 프로젝트 비회원의 접근은 UUID ACL에서 차단되어야 한다.

## 삭제 순서

1. 각 공개 진입점이 기대 유형을 명시한다. `deleteUserData`는 false, `deleteCniConfiguration`은 true로 공용 private helper를 호출한다. 명령 클래스의 instanceof로 유형을 추론하지 않는다.
2. 소유 범위를 확정하고 활성 UUID 레코드를 읽는다. 없는/이미 삭제된 UUID는 기존 파라미터 오류 흐름으로 거부한다.
3. 실제 `for_cks`가 기대값과 다르면 올바른 삭제 API를 안내하는 오류를 반환한다. 이 단계 이전에 본문·코멘트·참조 데이터를 수정하지 않는다.
4. 기존 활성 VM 및 템플릿 참조에 대한 삭제 방어를 그대로 적용한다.
5. 한 Cloud DB 트랜잭션 안에서 본체와 해당 `USER_DATA` Annotation을 삭제한다. 본체 삭제 false/예외 및 Annotation 예외는 성공으로 처리하지 않으며 커밋하지 않는다. Annotation이 0건인 정상 삭제는 허용한다.

유형은 등록 시 결정되며 변경 API가 없으므로 이번 범위에서 유형 변경 경쟁을 추가하지 않는다. Kubernetes 참조 및 생성 경쟁·사용 중 CNI 삭제 보호는 #1399에서 별도로 구현한다. 기존 VM/템플릿 검사와 생성 간 경쟁을 새로 해결했다고 주장하지 않는다.

## 검증 및 배포

두 유형을 파라미터화한 서버 테스트로 유형 혼용·없는 레코드·소유 판정 실패·root fallback·VM/템플릿 참조·정상 삭제·DB/코멘트 오류의 커밋 경계를 검증한다. API 테스트는 CNI 전용 dispatch, 실패 응답 및 상속된 UUID ACL/소유 파라미터를 확인한다. 기존 서버 및 CNI/UserData 등록 회귀도 실행한다.

클러스터 31에서 전용 자원으로 수정 전 양방향 혼용을 확인하고, 변경 서버 모듈을 빌드해 설치 JAR의 변경 클래스만 백업·교체한다. 수정 후 일반 사용자/도메인 관리자/root, 계정/프로젝트 소유 범위에서 정상 삭제 및 교차 삭제 차단, 본문·변수·Annotation 보존을 API/DB로 확인한다. 실제 CNI/UserData 메뉴의 정상 삭제도 검증한다. UI 소스는 변경하지 않으므로 UI 재빌드·정적 재배포는 필요하지 않으며 배포된 #1397 번들의 정적 해시와 실제 기능을 확인한다.

전체 Cloud 빌드/Release CI는 요청 범위가 아니다. 전용 도메인·계정·프로젝트·CNI/UserData·Annotation을 정리하고 기존 운영 CNI/Kubernetes 및 소유/코멘트 데이터의 안정 필드를 전후 비교한다.
