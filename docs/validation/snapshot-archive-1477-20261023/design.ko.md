# #1477 스냅샷 보관 적격성·멱등성 설계

기준: Epic 브랜치 #1478 구현 HEAD da23e0bd98d. 기존 PR #1492에 #1477을 추가한다. #1478 삭제 종결/복구 계약과 참조 ID를 공유하며 archive와 delete가 동일 snapshot 잠금을 사용한다.

## 상태 계약

- Primary-only Ready: managed 또는 RBD primary에서 지원한다. 백업 생성/확인 → 정확한 Secondary 실물 파일 확인 → Primary 삭제 → 위치 SECONDARY 순서다. 다른 unmanaged primary-only 조합은 UNSUPPORTED_PRIMARY로 거부한다.
- Primary+Secondary Ready: REUSE_SECONDARY. 새 사본/참조를 생성하지 않고 기존 백업을 검증한 뒤 Primary만 제거한다.
- Secondary-only Ready: ALREADY_ARCHIVED. 실물 검증 후 무변경 성공한다.
- Primary Destroying+Secondary Ready: RETRY_PRIMARY_CLEANUP. 기존 백업을 재검증하고 원본 정리만 재시도한다.
- Primary Ready+Secondary Allocated: RETRY_BACKUP. 같은 참조 ID에서 백업을 다시 수행한다. 일반 snapshot 생성 실패의 원본/전역 삭제 정리를 archive에 적용하지 않는다.
- 다중 참조, Hidden/비정상 상태, snapshot/volume 의존 관계, 비안정 전역 상태는 명확한 reason으로 거부한다. 기존 중복 삭제 복구는 #1478 제품 경로를 사용한다.

`archiveSnapshot(dryrun=true)`은 eligible/reason/message/참조 개수/scopehash를 반환한다. 실제 실행 전 ACL·그룹·삭제 여부·백업 제약·참조/상태를 잠금 안에서 재검증한다. expectedscopehash가 달라지면 새 진단을 요구한다. 기존 일반 보관 성공 wire 응답은 유지한다.

## 안전 조건

기존 QuerySnapshotZoneCopyCommand의 응답에서 정확한 기록 경로가 포함되는지 확인한다. 해당 명령이 경로 누락 시 형제 파일을 나열할 수 있으므로 형제 파일/디렉터리 목록을 백업 검증으로 인정하지 않는다. Ready, 비어 있지 않은 경로, 양수 물리 크기, endpoint의 정확한 파일 확인을 모두 만족해야 한다. endpoint 미지원/부재, 파일 부재/다른 파일만 존재는 실패하고 Primary를 보존한다. 이는 존재·복사 완료 검증이며 전체 데이터 체크섬 검사와 다르다.

복사 실패는 원본 Primary와 전역 snapshot을 삭제하지 않는다. 생성 중 Secondary는 Allocated로 돌아가 같은 행에서 재시도한다. Primary 제거 실패는 검증된 Secondary를 유지하고 부분 실패를 반환한다. 성공 여부와 관계없이 실제 변경을 시도한 소유자의 snapshot/secondary_storage 계수를 표준 재계산한다. 반복·무변경 호출은 새 사본·사용량 이벤트를 만들지 않는다.

## 검증 계획과 범위

31(GFS2)의 Secondary-only, 32(RBD)의 Primary+Secondary 및 통제된 Primary-only, 실제 백업/Primary 정리 장애와 재시도, 동시 호출, #1478 UI 삭제·잔여 복구를 API/UI/DB/NFS/RBD로 검증한다. 새로운 전용 SNAP1477 자원만 사용하며 SQL은 실패 fixture 구성에만 사용한다. 제품 실행 후 SQL 종결은 하지 않는다. 실제 managed primary가 없는 환경에서는 해당 적격성/백업 순서를 단위 계약으로 검증하고 실물 지원 장비 미검증을 구분한다. archive 버튼/폼은 #1481(SS-08)의 후속 범위이며 API 가용 조건을 제공한다.

변경 Maven 모듈만 ext4에서 빌드한다. 전체 Cloud 빌드와 qemu 빌드는 실행하지 않는다. 기존 배포 JAR/WEB-INF/config 및 dirty FTCTL 작업 트리를 보존한다.

## Secondary 삭제 후 Primary 잔여 — 추가 검증

보관 요청은 마지막 Primary 사본을 지키며 Secondary 검증 실패를 반환한다. 명시적인 `deleteSnapshot` 요청은 #1478의 종결 경로를 사용해 Primary 실물과 누락된 Secondary 참조를 모두 정리한다. 두 실물이 모두 사라지고 Primary/Secondary DB 정보만 남은 경우에도 ENOENT를 멱등 처리하고 전역/참조/계수를 종결한다. 별도 fixture로 Secondary-only 누락(31), Secondary 누락+Primary 실물 존재(32), 양쪽 실물 부재+DB 잔여(32)를 구성해 UI/API 삭제와 반복 호출 후 잔여 0을 확인한다. 자동 보관 실패를 무기한 미삭제 상태의 완료로 간주하지 않는다.

## 명시적 강제삭제 — 사용자 추가 요구

`deleteSnapshot(force=true)`은 루트 관리자가 force=true dryrun 진단과 scopehash 확인 후 선택한다. 기존 일반 삭제와 구분하며, 알려진 자식 snapshot/volume 의존성과 진행 중 생성/백업 작업은 보호한다. 선택 Zone을 확인할 수 없는 참조는 모든 Zone 진단을 요구한다.

가능한 실물 삭제를 먼저 시도하고, 누락된 store/path/volume 정보 또는 저장소 실패 때문에 정리할 수 없는 참조도 Destroyed/display=false로 논리 종결한다. 전역/Zone과 계수는 선택 범위에 맞게 정리한다. 결과의 `dbcleanupcomplete`와 `physicalcleanupcomplete`, 참조별 PHYSICAL_DELETED/DB_ONLY, pendingcleanup을 구분해 UI에 표시한다. DB 정리를 물리 삭제 성공으로 보고하지 않는다.

실물 미확인 대상은 snapshot_details의 비공개 `snapshot.force-delete.pending.<ref>` 이력에 정확한 store/role/path를 보존한다. Destroyed 참조도 이 보류 이력이 있으면 강제 진단/재시도 대상이다. 누락 정보가 복구되거나 장애가 해제되면 같은 제품 경로로 실물을 재시도하고 보류를 해제한다. 매핑 자체가 전혀 없는 경우도 DB 종결을 허용하되 물리 부재를 증명할 수 없다는 별도 감사 이력을 남긴다. 이력 보존은 재시도 주소를 잃지 않기 위한 것이며 활성 snapshot은 목록에서 제거한다. 대량 DB 삭제나 알 수 없는 실물 탐색/삭제는 수행하지 않는다.

UI의 삭제 잔여 복구 대화상자에 관리자 강제삭제 선택, 영향 설명과 별도 확인 문구를 추가한다. 강제 모드 변경은 기존 진단과 확인을 무효화한다. DB만 정리한 결과는 경고로 표시하고, 정상 실물 정리와 구분한다.

일반 삭제는 null/빈 경로와 명확한 shared volume 디렉터리 참조를 저장소 명령에 보내지 않고 INCOMPLETE_STORE_REFERENCE로 거부한다. 관리자 강제 진단에서 실물 확인 가능 여부를 보고 DB 정리를 선택한다. 강제 모드에서도 volume 디렉터리를 통째로 삭제하지 않는다.

강제삭제의 force 파라미터는 API 파서에서 모든 역할에 인식시킨 뒤 서비스에서 root-admin 여부를 명시적으로 검증한다. 권한 annotation으로 값을 조용히 버리면 일반 삭제로 실행될 수 있기 때문이다. 일반 계정의 force 진단/실행이 실제 서버에서 거부되고 대상 상태가 유지되는지 검증한다. 기본 User 역할의 archive 권한은 변경하지 않으며 테스트 계정의 복제 역할에만 archiveSnapshot을 허용해 소유자/타 소유자 경계를 검사한다.
