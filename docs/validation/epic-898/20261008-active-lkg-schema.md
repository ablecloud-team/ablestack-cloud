# 마지막 정상 구성의 단일 활성 제약과 revision 비교

cloud.storage_service_config_artifact.active_lkg_instance_id는 RESTORE_POINT/ACTIVE_LKG/removed IS NULL인 행에만 instance_id를 반환하는 STORED generated bigint 컬럼입니다. uk_storage_service_config_artifact__active_lkg unique index로 같은 서비스에서 두 지점이 동시에 활성화되는 것을 DB가 거절합니다. 다른 artifact 및 만료 이력은 NULL이므로 기존 보존 방식을 유지합니다.

승격은 CANDIDATE 행과 현재 활성 행을 잠근 트랜잭션에서 수행합니다. runtime 검증 전에 읽은 활성 ID·revision이 현재 값과 동일하고, candidate revision이 현재보다 큰 경우에만 이전 지점을 SUPERSEDED로 바꾸고 새 지점을 ACTIVE_LKG로 승격합니다. 충돌·실패 시 이전 활성 지점을 유지하고 candidate를 FAILED로 기록합니다.

신규 schema 두 경로와 기존 개발 DB의 idempotent ALTER 경로를 반영했습니다. 기존 활성 중복이 있으면 ALTER를 실패시켜 검증 없는 임의 삭제를 막습니다.

검증:
- 모듈 package 성공, DAO revision 회귀 3개 및 읽기 범위·stale source·원자적 변경 회귀 13개 통과.
- 13번 관리 서버 DB의 임시 테이블에서 중복 활성 UPDATE 거절, promotion rollback 후 기존 ID 유지, superseded source 재활성화 거절 확인.
- 실제 서비스 DB 제약과 API·UI 승격 검증은 후속 기록에 추가합니다.
