# Epic #898 AD 공통 승인·LKG·새 복제 준비 정상 검증

ec40eb3f618bc5dd5b0bff4b78b68848928d356b의 Java9를 정상 Maven -am package로 검증했다. Checkstyle 통과, 825개 테스트 /124개 클래스, 실패·오류·생략 0이다. API/schema/server/KVM/storagevm immutable5 산출물 6,279개를 보존했다.

- Java9893 경로·NUL·내용·NUL SHA-256: 192f60e8a2650dea1ddd070212fce584c8d87e338324bd96f67f940dcc379a4e (전후 동일).
- native union·template producer 해시 전후 동일.
- 정상 로그 SHA-256: b142119ff9dd13aa6aef74404d1232914fd7060ce8f636d2358933a3e1b7f669.
- 첫 시도는 테스트 한 행1313자가 Checkstyle1024 제한을 넘어서 실패했다. 로그·source hash를 보존하고 기능 변경 없이 해당 행을 분할·공백 점검한 뒤 full normal2로 재검증했다.
- 신규 DDL0, 실제 배포·AD·ROOT 교체0, production AD/fullFour false.

JOINED AD 일반 변경만 선택적 admaintenancewindow/adconfirmation을 요구해 SERVICE SOURCE 캡처·중지와 연결한다. 비 AD 동작과 다른 command의 confirmation 의미는 보존한다. LKG는 native commit 후 독립 TARGET capture/stop/export/resume와 암호화 원본 authority를 확인한 뒤 RESTORE_POINT를 승격하며 SOURCE/TARGET 역할·키 교환 및 failed resume는 승격하지 않는다.

F2 CREATE_NEW는 관리된 foundation에서 실제 ROOT·계획된 NEW DATA를 준비하고 정상 COMPLETE runtime 거래·서명 manifest의 실제 buildCommit/files CLI SHA·bundle/manifest 해시를 검증해 보호 profile을 확인한 뒤 domain-FIRST로 진행한다. buildCommit은 sealed sourceCommit에 명시 투영하며 builder rename·metadata 보충은 하지 않는다. 대상·계획 UUID·거래 변경·응답 유실·재시도와 capability 소비 전 pin 재검증을 시험했다.

최신 committed1bf9 CLI의 real crypto bridge 및 source/candidate before-after 증빙을 포함했다. ROOT forward/manual opaque authority, 일반 SERVICE retain 재개 및 native inverse 결과를 완료 롤백에 연결하는 backend 소비자는 남았다. 최종 동일 source image/runtime/module과 실제 isolated API/UI/AD/ROOT/I/O 인수도 남아 있다. 이 소스 결과를 전체 Epic 완료로 표시하지 않는다. 최종 UI #1275는 미착수다.
