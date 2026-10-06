# SharedFS 데이터 볼륨 보존 스키마

삭제의 기본 정책은 `PRESERVE_VOLUMES`이다. `DELETE_VOLUMES` 요청은 현재 영향 미리보기의 해시와 서비스 이름 확인을 모두 요구한다. 실행 전 전체 볼륨 관계를 검증하고, 기본·추가 데이터 볼륨을 분리해 실제 연결 해제를 확인한 다음 VM을 제거한다. 중간 분리 실패는 VM 제거와 데이터 삭제로 이어지지 않는다.

추가 마이그레이션 단계: `europa-4.23-sharedfs-retention-v1`.

- `cloud.shared_filesystem.data_volume_policy`: nullable `VARCHAR(32)`. 기존 NULL 값은 보존 정책으로 해석한다.
- `cloud.shared_filesystem.deletion_plan_json`: nullable `LONGTEXT`. 기본·추가·교차 프로토콜 볼륨의 중복 없는 영향 목록과 해시를 저장한다. GenericDao 필드 길이는 16777215로 지정해 255자 절단을 방지한다.
- `cloud.storage_service_deletion_audit`: ID, 서비스 ID/UUID, 소유 계정, 실행 사용자, 정책, 단계, 전체 계획 JSON 및 생성 시각을 보관한다. `PLANNED`, `STARTED`, `FAILED_RETRYABLE`, `COMPLETE` 이력을 남긴다.

볼륨과 VM 테이블을 SQL로 복원하지 않는다. 분리는 기존 Volume API를 사용하고 계정·도메인·zone 소유권을 바꾸지 않는다. 동일 인스턴스의 구성 및 런타임 작업과 공통 async queue/GlobalLock을 사용한다. VM 없는 초기 실패 리소스는 별도의 예약된 양수 큐 범위를 사용한다.

변경 범위 모듈 빌드와 보존·부분 실패·미리보기·잠금 및 기존 SharedFS 회귀 테스트는 통과했다. **이 문서 작성 시점에는 이 마이그레이션과 삭제 UI가 테스트 클러스터에 배포되지 않았고, 실제 삭제 기능 검증은 남아 있다.**
