# #1458 구현 재개 — 진행 중

Mounted DATA source 계약을 신원 검증 후 전달하도록 교정했다. 실제 SystemVM 사전 시험11·native 회귀6·서버/API7·실제 DAO2·UI11 및 lint 통과. Maven package26모듈의29회귀 통과. 제품 UI 배포·새 VM/클라이언트 인수는 아직 미완료다.

이전 중단 기록은 ../sharedfs-1458-capacity-20261102/README.md 이력으로 보존한다.

## 조회 경계와 배포 정합성 보완

용량 응답은 기존 접근 검사를 통과한 listSharedFileSystems 목록 및 id 단건 조회에서만 추가한다. 생성·수정 async 응답의 기존 필드는 그대로 두며 UI는 종료 후 단건 GET으로 확정 상태를 읽는다. 공통 ApiResponseHelper 교체는 제외한다.

첫 부분 배포는 전체 Spring XML에 포함된 별도 VmCloneBatchManager 등록 때문에 503이 발생해 정상 JAR로 원복했다. 이어진 정적 의존성 점검에서 ApiResponseHelper의 별도 Bucket 기능 참조도 발견했다. 재배포 파일은 배포본 XML에 capacity bean 하나만 합성하고 변경 클래스의 의존성 폐쇄를 검증한다. 이는 배포 조립 오류의 자체 교정이며 별도 VM/버킷 기능은 이 과제에 추가하지 않는다.

빌드는 로컬 원칙이며 Actions는 직접 실행하지 않았다. 불필요한 자동 빌드를 피하도록 후속 게시 commit에 skip ci를 명시한다.
