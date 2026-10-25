# #1479 스냅샷 작업 대상·최신 상태·비동기 추적 설계

기준 HEAD `fa60459bceccbab36abba8e098f3da4222f1f12a`, Epic #1473, 기존 PR #1492. #1477 보관과 #1478 일반/강제 삭제 계약은 유지한다.

## 대상과 실행 전 검사

대화상자를 열 때 API 이름, snapshot UUID, 원본 volume UUID, owner/project, 선택 Zone 및 대상 목록을 복사·고정한다. 정렬·선택·페이지 갱신은 이 값을 바꾸지 않는다. 로그인 세션/계정/역할/프로젝트/원본 경로가 바뀌거나 컴포넌트가 종료되면 늦은 조회로 제출하지 않는다.

listSnapshots/listVolumes의 선택적 `includesnapshotcontext=true` 조회에 현재 원본 볼륨/연결 VM 상태와 생성·되돌리기의 원본 제약을 제공한다. 기존 API의 권한과 조회 범위 안에서만 응답하며 일반 목록에는 추가 조회 비용을 주지 않는다. 실제 되돌리기도 같은 원본 상태 검사를 적용한다. 호스트 기능/스토리지 드라이버 등의 최종 제약은 기존 backend가 다시 검사한다.

공통 제출 처리는 현재 API 권한(listApis), 현재 snapshot·원본 정보·사본/Zone 및 작업별 조건을 다시 조회한다. 확인된 대상/영향 범위가 변하거나 필수 응답이 불완전하면 POST를 보내지 않고 이유와 재확인 경로를 표시한다. 생성, 새 볼륨/템플릿, 되돌리기, 보관, 복사, 삭제의 제약을 구분한다. 원본이 없어도 유효한 snapshot의 새 볼륨/템플릿 복구는 허용하고 되돌리기는 거부한다. 보관/삭제는 기존 dryrun/scopehash 계약도 사용한다.

## 비동기 제출과 결과

컴포넌트와 독립된 registry가 대상 snapshot/volume/VM별 제출 예약을 조회 시작 전에 잡는다. 같은 자원의 중복 클릭·다른 진입점 제출을 막는다. 검사 실패, 제출 중, job 접수, 실제 완료, 실제 실패, 제출/결과 미확인을 구분한다.

접수 응답이 사라지거나 정상 jobid 없이 돌아오면 unknown으로 보존하며 자동 POST 재시도를 하지 않는다. jobid가 있으면 조회 재시도만 제공한다. 이동 후에도 작업 목록에서 UUID/API/상태/jobid와 결과 확인 경로를 제공한다. 세션 저장소에는 job/대상/상태만 남기고 세션 비밀값과 전체 요청 payload를 저장하지 않는다. 보안 문맥은 세션/계정/프로젝트로 분리하고 원문 인증 값은 저장하지 않는다. 로그아웃은 이전 세션 기록을 정리한다. 프로젝트 전환/늦은 응답이 새 문맥을 변경하지 않는다.

POST를 보내기 전 취소와 POST 결과 미확인을 구분한다. 미확인 제출은 작업 이벤트/비동기 작업에서 운영자가 확인할 때까지 재제출 차단을 유지한다. 명확한 서버 실패나 종결 결과 후에는 새 확인 절차로 다시 요청할 수 있다.

## UI 연결

되돌리기/일반 삭제는 대상 요약과 확인을 가진 공통 스냅샷 작업 대화상자로 연결한다. TakeSnapshot/CreateVolume/CreateTemplate은 기존 입력 폼에 공통 대상 요약·검사·제출을 연결한다. SnapshotZones의 복사/삭제도 고정된 UUID/Zone을 사용한다. Zone을 식별하지 못하는 행은 범위가 확대되지 않도록 실행을 막는다. 삭제 잔여 복구의 강제 옵션/결과 분리는 유지한다. 보관 버튼/복사 지원 UX 전체는 #1481, 사본 행/집계는 #1480, 시각 표준 전체는 SS-15의 후속 범위다.

## 검증

단위/컴포넌트 검사는 지연 응답·선택/페이지 변경·세션/프로젝트/권한 변경·double click·불완전 조회·접수 응답 소실·job 조회 소실/복구와 원본 없는 복구를 검사한다. 31 GFS2/32 RBD에 전용 SNAP1479 자원을 생성해 실제 UI 작업, API job/오류, DB 대상·job 개수·상태, NFS/GFS/RBD 실물을 대조한다. Running/Stopped·연결/분리·VM snapshot/복원 충돌과 타 소유자/역할 경계를 확인한다. 기존 운영 VM/FTCTL 자원은 변경하지 않는다.

Java는 변경 모듈만 WSL ext4에서 빌드한다. UI는 production build 후 기존 WEB-INF/config를 보존해 static 자산만 배포한다. 전체 Cloud build/Actions dispatch, qemu 변경·빌드, PR 병합은 이번 요청에 포함하지 않는다.


## 실제 전송 재시도와 회복

브라우저가 socket reset 후 동일 POST를 재전송하는 것을 재현했다. UI 억제만으로는 부족하므로 기존 `customjobid` API 필드를 정규 UUID로 발급·보존하고, backend가 UUID별 GlobalLock 안에서 기존 job을 먼저 찾는다. 사용자/계정/명령/프로젝트/대상/입력 해시가 같은 요청은 새 자원 할당 전에 기존 job 접수만 돌려준다. 다른 요청의 UUID 재사용은 거부한다. 인증 값은 해시 입력과 저장 payload에서 제외한다. 이 보호는 async_job 이력이 유지되는 기간에 적용된다. job 저장 전 프로세스 crash의 분산 exactly-once 또는 job purge 이후의 영구 중복 방지를 보장하지 않는다.

미확인 제출은 보존된 요청 UUID를 queryAsyncJobResult로 조회한다. 확인되지 않은 job을 임의로 완료 처리하지 않고 POST를 다시 보내지 않는다. F5에서 token/user 초기화가 늦게 도착해도 초기 문맥 수화가 저장된 작업을 지우지 않는다. 실제 로그아웃/계정 교체는 이전 기록을 정리하고 프로젝트 전환은 별도 보관함으로 분리한다.

경로 전환 시 새 resource를 적용하기 전에 기존 대화상자를 종료한다. 느린 사전 조회 이후 추가 조회/POST를 실행하지 않으며, 취소된 양식이 새 화면의 createVolume 양식으로 바뀌어 남지 않는다. 권한 조회의 command별 오류 envelope도 공통 안내로 변환한다.

## 테스트 환경의 런타임 차이

32번 기존 fat JAR는 Epic 소스의 QueryManagerImpl보다 오래되어 ListTemplatesCmd/ListIsosCmd 및 VMInstanceDetailsDao/Impl 네 클래스가 필요한 것을 확인했다. 같은 Epic 소스의 api/engine-schema 변경 모듈만 WSL ext4에서 빌드하여 16개 작업 클래스와 이 4개 의존 클래스를 배포했다. 이 네 클래스는 이번 소스 수정이 아니다. 20개 클래스의 내부 method/field 참조, 정상 template/ISO/SSH-key 필터 조회, 원래 JAR의 나머지 entry 동일성을 확인한다.
