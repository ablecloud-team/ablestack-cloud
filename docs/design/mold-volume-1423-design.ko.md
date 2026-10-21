# Epic #1422 / VOL-01 #1423: 볼륨 부분 수정과 확장 실패 처리 설계

기준: Europa `248653b58920d2a950393ed3edc7b343b831105d`. Epic 브랜치는 `dhslove/epic-1422-volume-offerings`이며 이번 변경은 #1423만 구현한다.

## 수정 계약

`updateVolume`에서 미전달 필드는 유지한다. 이름/삭제 보호만 변경하면 path, chainInfo, pool, state, format, type을 건드리지 않는다. 경로 미전달을 경로 삭제로 해석하지 않는다.

| 경로 입력 | 동작 |
|---|---|
| 미전달 | 기존 경로 유지 |
| 빈 문자열/공백 | 명시적 잘못된 입력으로 거절 |
| 기존 경로와 동일 | 상태/스토리지 관계를 수정하지 않는 no-op |
| 다른 경로 | Root Admin의 기존 관리/복구 권한과 자원 접근 권한을 검증하고 명시적으로 변경 |

실제 경로 변경은 볼륨이 Allocated/Ready/Uploaded인 경우로 제한한다. 연결된 사용자 VM이 있으면 Stopped여야 한다. 진행 중 작업/실행 중 VM의 경로를 변경하지 않는다. 소유 VM을 확인할 수 없는 경우도 거절한다. 이름/삭제 보호 변경과 동일 경로의 재전송에는 이 제한을 적용하지 않는다. API는 실제 파일을 이름 변경하거나 이동하지 않으며, 관리자가 확인한 경로만 갱신한다. 기존 shareable 경로 참조의 pool/format 매핑은 실제 경로 변경에서만 유지한다.

DomainAdmin/User/Project의 기존 자원 접근 검증을 보존한다. 일반 편집은 이름/삭제 보호만 허용하며 관리자용 필드를 허용하지 않는다. 편집 폼의 삭제 보호 입력 및 payload 정리는 #1428의 범위다.

## 확장 응답

실제 실패가 관찰된 Ablestack primary driver와 동일한 cast 구조를 가진 default driver에서 먼저 `Answer`로 응답을 받는다.

1. null은 명시적 실패로 처리한다.
2. 실패 Answer는 원인 details를 보존한다. details가 비어 있어도 성공으로 바꾸지 않는다.
3. 성공이더라도 ResizeVolumeAnswer가 아니면 프로토콜 오류로 처리한다.
4. 성공한 ResizeVolumeAnswer만 size와 path/chain/pool을 갱신한다.

콜백에 원래 Answer를 남기고 각 요청을 한 번 완료한다. 기존 VolumeService의 OperationFailed 처리와 job 오류 전달을 사용한다. default driver의 CLVM 호스트 라우팅 및 암호 초기화 finally 경로를 보존한다. 실패 응답을 위해 Agent/호스트 패키지를 변경하지 않는다.

## 검증·빌드·배포

- 부분 수정 보존, 빈 경로, 권한·상태 거절, 명시적 관리자 복구에 대한 서비스 회귀 검사.
- 두 driver의 일반 실패/null/빈 사유/잘못된 성공 타입/예외/정상 성공 및 callback·메타데이터 검증. 기존 default CLVM 라우팅 검사도 실행.
- WSL ext4에서 변경된 `server`, `plugins/storage/volume/ablestack`, `plugins/storage/volume/default` 모듈만 빌드·검사한다. Ablestack 모듈의 무조건 테스트 skip 설정을 제거한다. 전체 Cloud 빌드는 실행하지 않는다.
- 31/32는 서로 다른 배포 이력의 합본 JAR을 사용하므로 원본 JAR을 기준으로 빌드된 updateVolume/resize 및 새 검증 메서드만 ASM으로 이식한다. 나머지 메서드의 code 바이트와 JAR 항목을 비교하고, 각 원본 런타임 의존성에서 JVM 전체 검증과 회귀 검사를 통과시킨 뒤 배포한다. 원본 JAR·해시·서비스 PID·WEB-INF/정적 자산·운영 자원 상태를 백업하며, 정상 구동/로그/API를 확인하고 실패하면 원본으로 복구한다.
- 각 클러스터 전용 자원으로 이름/삭제 보호 부분 수정→UI 연결/확장/분리를 확인하고 API/DB/실물 경로·용량을 대조한다. 실패 주입은 전용 볼륨의 확인된 원래 경로를 기록한 상태에서만 수행한다. 실패/복구/재시도의 결과를 별도로 기록한다.

## 기존 손상 데이터의 탐지와 복구

DB의 활성 볼륨 중 NULL/빈 path를 조회하되 Allocated 등 아직 실제 생성 전인 정상 레코드를 구분한다. pool, state, VM 연결, 원래 API/Agent/관리 로그 및 스토리지 파일/이미지를 함께 조사한다. UUID가 곧 경로라고 추정하지 않는다. qcow2 backing chain이나 RBD snapshot/clone 관계도 확인한다.

복구는 검증된 원래 경로가 있는 전용/승인 대상에 한해 수행한다. VM을 정지하고 진행 중 storage job을 배제한 다음 root admin API로 명시적 경로를 갱신하고, API/DB/실물 및 연결/확장 결과를 확인한다. 경로를 확인할 수 없으면 자동 수정하거나 데이터를 새로 만들지 않는다. 이번 작업은 운영 볼륨의 일괄 경로 수정/DB 물리 삭제를 수행하지 않는다.

구현·배포·검증 결과는 [검증 보고서](../validation/volume-1423-20261019/report.ko.md)에 기록한다.
