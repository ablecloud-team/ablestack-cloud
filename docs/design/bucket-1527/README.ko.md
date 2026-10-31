# Epic #1527 하위 이슈 해결 설계 및 등록 결과

[상위 Epic #1527](https://github.com/ablecloud-team/ablestack-cloud/issues/1527)에 13개 하위 이슈를 실제 연결했다. 등록 당시 P1 6개/P2 7개였다. 이번 Epic 작업 브랜치는 #1528과 #1540을 구현·검증했으며 나머지 11개는 OPEN으로 유지한다. #1540의 결과는 [UI 표준 개선 보고서](../../validation/bucket-1540-20261031/REPORT.ko.md)를 따른다. #1528의 배포·검증 결과는 [검증 보고서](../../validation/bucket-1528-20261031/REPORT.ko.md)를 따른다.

각 본문에는 문제·재현, 소스 원인, 해결 순서, API/데이터/UI 계약, 실패·복구·호환성, 대상 소스와 검증 기준을 수록했다. 새 API·테이블·필드는 구현 제안임을 구분했다.

| 코드 | 우선순위 | 등록 이슈 | 설계 파일 | 필수 선행 |
|---|---|---|---|---|
| BKT-13 | P1 | [#1528 버킷 브라우저 탭 최초 진입·폴더 이동의 목록 로딩 정체 해소](https://github.com/ablecloud-team/ablestack-cloud/issues/1528) | [BKT-13](BKT-13.ko.md) | 독립 착수 |
| BKT-04 | P1 | [#1529 Glue 계정 키 저장 시 기존 상세 보존과 저장소별 자격 증명 분리](https://github.com/ablecloud-team/ablestack-cloud/issues/1529) | [BKT-04](BKT-04.ko.md) | 독립 착수 |
| BKT-01 | P1 | [#1530 버킷 암호화·객체 잠금의 실제 적용과 표시 상태 일치](https://github.com/ablecloud-team/ablestack-cloud/issues/1530) | [BKT-01](BKT-01.ko.md) | 독립 착수 |
| BKT-02 | P1 | [#1531 버킷 할당량 검증·예약과 Glue 변경 실패 복구](https://github.com/ablecloud-team/ablestack-cloud/issues/1531) | [BKT-02](BKT-02.ko.md) | 독립 착수 |
| BKT-03 | P1 | [#1532 빈 버킷 삭제 보장과 버전·강제 정리 경로 분리](https://github.com/ablecloud-team/ablestack-cloud/issues/1532) | [BKT-03](BKT-03.ko.md) | 독립 착수 |
| BKT-05 | P1 | [#1533 버킷·Object Store 자격 증명 노출 제거와 명시적 키 관리](https://github.com/ablecloud-team/ablestack-cloud/issues/1533) | [BKT-05](BKT-05.ko.md) | #1529 |
| BKT-06 | P2 | [#1534 Object Store UUID 버킷 필터와 소속 버킷 조회 동선 복구](https://github.com/ablecloud-team/ablestack-cloud/issues/1534) | [BKT-06](BKT-06.ko.md) | 독립 착수 |
| BKT-07 | P2 | [#1535 버킷 생성 UI의 소유 계정·도메인과 프로젝트 범위 연결](https://github.com/ablecloud-team/ablestack-cloud/issues/1535) | [BKT-07](BKT-07.ko.md) | #1529 |
| BKT-08 | P2 | [#1536 버킷 생성 저장소 이름 검색과 빈 목록·조회 오류 안내 수정](https://github.com/ablecloud-team/ablestack-cloud/issues/1536) | [BKT-08](BKT-08.ko.md) | 독립 착수 |
| BKT-09 | P2 | [#1537 버킷 사용량 동기화 작업·최신성·실패 상태 표시](https://github.com/ablecloud-team/ablestack-cloud/issues/1537) | [BKT-09](BKT-09.ko.md) | #1528 |
| BKT-10 | P2 | [#1538 Glue 객체 상세 메타데이터를 표준 HEAD 경로로 조회](https://github.com/ablecloud-team/ablestack-cloud/issues/1538) | [BKT-10](BKT-10.ko.md) | #1529, #1528 |
| BKT-11 | P2 | [#1539 Object Store 용량 단위·수정 초기값과 연결 상태 관리 개선](https://github.com/ablecloud-team/ablestack-cloud/issues/1539) | [BKT-11](BKT-11.ko.md) | #1533, #1534, #1537 |
| BKT-12 | P2 | [#1540 버킷·Object Store 화면의 테마·반응형·용어 일관성 개선](https://github.com/ablecloud-team/ablestack-cloud/issues/1540) | [BKT-12](BKT-12.ko.md) | #1528 |

브라우저 탭 제보는 #1528로 추가했다. 31/32에서 Glue 목록 HTTP 200과 실제 파일 XML을 확인했지만 첫 진입 UI는 로딩 상태였다. URL 정규화와 fullPath 요청 scope 충돌이 원인이다. 같은 URL 재접속은 양쪽 정상 표시를 확인했다. 상세 증거는 [#1528의 수정 전 재현 기록](https://github.com/ablecloud-team/ablestack-cloud/issues/1528)에 있다. 이 문단은 수정 전 감사 결과이며, 수정 후 결과는 새 검증 보고서에 기록한다.

최초 감사 자원은 정리했다. 이후 #1540 검증에서는 사용자 요청에 따라 32번 확인용 버킷 하나와 그 계정·저장소·RGW 키를 보존했고 나머지만 정리했다. Glue HEALTH_OK와 기존 dashboard 사용자 보존을 확인했다. 기존 FTCTL 변경은 수정하지 않았다. 전체 Cloud 빌드/CI는 실행하지 않았다.

등록 당시 13개 본문과 준비한 Markdown의 일치, native sub-issue ID 연결, OPEN 상태, Epic 본문 및 의존 관계를 API로 확인했다. [등록한 Epic과 하위 이슈](https://github.com/ablecloud-team/ablestack-cloud/issues/1527)을 함께 보존했다.
