# Diplo SharedFS 단일 기능 플래그 설계

## 목적

Diplo의 Shared FileSystem과 Storage Service 기능은 하나의 제품 기능이다. 따라서 `sharedfs.feature.enabled`만을 기능 노출과 런타임 동작의 기준으로 사용하고, 별도 `storage.service.feature.enabled` 설정은 제거한다.

## 문제

| 구분 | AS-IS | 영향 |
| --- | --- | --- |
| 기능 플래그 | SharedFS와 Storage Service가 서로 다른 플래그를 사용 | 메뉴는 표시되지만 Storage Service 모델 생성은 생략될 수 있음 |
| 생성 완료 | SharedFS VM과 볼륨은 생성되지만 `storage_service_instance`가 없을 수 있음 | UI 초기 서비스 구성이 인스턴스를 찾지 못하고 실패 |
| 기존 데이터 | 플래그가 꺼진 동안 생성된 SharedFS를 자동 보정하지 않음 | 설정을 바꿔도 기존 서비스는 계속 불완전 |
| DB 설정 | 폐기 대상 설정이 `configuration`에 남음 | 운영자가 의미 없는 이중 설정을 관리하게 됨 |

## TO-BE

1. `sharedfs.feature.enabled=true`
   - SharedFS API와 Storage Service API를 함께 등록한다.
   - SharedFS 생성 및 수명주기 변경 시 Storage Service 호환 모델을 항상 동기화한다.
   - NFS, SMB, iSCSI, NVMe-oF 탭과 작업 API를 사용할 수 있다.
2. `sharedfs.feature.enabled=false`
   - SharedFS API와 Storage Service API를 모두 등록하지 않는다.
   - API 권한을 기준으로 구성되는 SharedFS 메뉴도 UI에서 숨겨진다.
   - 관리 서버 재시작 후 적용한다.
3. 관리 서버 시작 시 보정
   - VM과 데이터 볼륨이 연결된 비삭제 SharedFS를 조회한다.
   - `storage_service_instance`와 기본 NFS 프로토콜 모델을 idempotent하게 생성 또는 갱신한다.
   - Destroyed, Expunging, Expunged 및 연결 정보가 불완전한 레코드는 제외한다.
4. 설치 및 업데이트
   - `storage.service.feature.enabled` ConfigKey를 소스에서 제거한다.
   - Diplo 후처리 스키마에서 기존 `configuration` 행을 삭제한다.
   - `storage.service.command.timeout`의 상위 기능 키는 `sharedfs.feature.enabled`로 변경한다.

## 코드 변경 대상

| 구성요소 | 파일 | 변경 |
| --- | --- | --- |
| API 설정 | `api/.../StorageServiceInstance.java` | 별도 기능 키 제거, 명령 타임아웃의 SharedFS 종속성 설정 |
| SharedFS 수명주기 | `server/.../SharedFSServiceImpl.java` | 단일 플래그 사용, 관리 서버 시작 시 기존 모델 보정 |
| Storage Service API | `server/.../StorageServiceManagerImpl.java` | SharedFS 플래그가 꺼지면 명령 목록을 등록하지 않음 |
| DB 업데이트 | `engine/schema/.../schema-Diplo-After.sql` | 폐기 설정 행을 idempotent하게 삭제 |
| 테스트 | `server/src/test/...` | 보정 대상 판정 및 설정 종속성 검증 |

## 검증 기준

- 소스 전체에서 `storage.service.feature.enabled`와 `StorageServiceFeatureEnabled` 참조가 남지 않는다.
- `sharedfs.feature.enabled=true`에서 `listSharedFileSystems`와 `listStorageServiceInstances` API가 모두 노출된다.
- 기존 Ready SharedFS의 VM ID와 볼륨 ID가 있으면 관리 서버 재시작 후 `storage_service_instance`가 생성된다.
- 기존 폐기 설정 행이 DB에서 제거된다.
- 로그인 화면과 `/client/` 정적 리소스가 정상 응답하고 SharedFS 상세 탭이 서비스 인스턴스를 조회한다.
