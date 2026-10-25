# #1493 정책 ID 충돌 방지와 이력 복구 설계

## 생성 계약

정책 ID는 DB 식별자이고 스냅샷 유형은 enum의 ordinal이다. 기존 `INTERNAL_POLICY_ID=8`은 두 이름 공간을 혼용했다. 내부 Java 호출 전용 정책 값은 `-1`로 바꾸고 MANUAL=0, 실제 정책 ID>0 계약을 사용한다. 저장 타입 INTERNAL=8은 바꾸지 않아 기존 DB·API·스토리지 계약을 유지한다. 공개 createSnapshot/createSnapshotBackup은 음수 정책 값을 거부한다. 실제 정책 8은 다른 양수 ID와 동일하게 조회한다.

스냅샷 할당은 원본 접근 권한 확인 후 정책의 존재·볼륨·계정·도메인·유효 주기를 검사한다. 정책이 삭제됐거나 다른 볼륨의 정책이면 할당하지 않는다. 정책의 HOURLY/DAILY/WEEKLY/MONTHLY는 저장 타입 3/4/5/6으로 변환한다. 수동과 내부 생성 의도는 각각 MANUAL/INTERNAL을 유지한다.

할당 때 숨김 snapshot_details에 `snapshot.creation.intent`와 정책 ID·UUID·주기를 기록한다. 정책·스케줄·job이 만료돼도 생성 근거를 보존하기 위한 서버 메타데이터이며 사용자 태그를 근거로 삼지 않는다. 스케줄러, 보관 개수 처리, 정책 삭제 API의 기존 경로를 유지한다.

## 기존 이력 복구

자동 시작 마이그레이션이나 이름·생성시각 기반 일괄 UPDATE는 하지 않는다. `scripts/util/repair-snapshot-policy-types.py`는 관리 서버의 로컬 Cloud DB에서 읽기 전용 계획을 만들고, 검토한 계획과 SHA256을 지정할 때만 적용한다. PyMySQL과 로컬 Unix socket을 사용하며 암호는 MYSQL_PWD 환경 변수로 받는다.

후보는 타입/설명이 INTERNAL이고 BackedUp 또는 Destroyed인 행이다. 다음 근거를 모두 확인한다.

- 성공한 CreateSnapshotCmd job의 instance_type/id, 명령의 snapshot id·volume id/UUID·policy id/UUID와 호출 계정 식별자가 일치한다.
- 실제 정책 ID가 8이고 정책의 볼륨·계정·도메인이 스냅샷·원본 볼륨과 일치하며 주기가 유효하다.
- 삭제된 정책은 관리자가 보존한 정책 UUID·원본 UUID·소유자·주기·근거 SHA256을 명시적으로 제공해야 한다. 숫자 ID만 남은 스케줄러 job은 삭제/재사용된 정책을 식별할 수 없으므로 보존 근거에 정확한 생성 job UUID도 필요하다.
- 명시적인 INTERNAL/MANUAL 생성 의도, 진행 중 작업, 증거 부족·충돌은 변경하지 않고 이유를 보고한다. 외부 보존 근거는 운영자가 출처를 확인해야 하며 도구가 서명을 인증한 자료로 간주하지 않는다.

적용은 후보·볼륨·정책·job·세부정보를 FOR UPDATE로 잠그고 계획과 다시 대조한다. 하나라도 달라지면 전체 트랜잭션을 롤백한다. snapshot_type/type_description과 숨김 복구 감사 내역만 변경한다. 상태·삭제 시각·스토리지 경로·소유권·용량·사용량은 바꾸지 않는다. Destroyed 이력은 유형만 바로잡고 자원을 되살리지 않는다. 동일 계획 재실행은 감사 해시를 확인해 중복 반영하지 않는다. 복구한 활성 스냅샷은 정상 주기 보관 개수의 대상이 된다. 다음 생성에서 오래된 스냅샷이 maxSnaps에 따라 정리되므로 적용 전에 보관 설정도 확인한다.

```sh
# 관리 서버에서 실행. MYSQL_PWD는 현재 세션의 비밀 환경 변수로 설정한다.
python3 repair-snapshot-policy-types.py --output plan.json
# 정책이 삭제된 경우에만, 출처를 검토한 보존 증거를 추가한다.
python3 repair-snapshot-policy-types.py --archive-policy-evidence archived-policies.json --output plan.json
python3 repair-snapshot-policy-types.py --apply plan.json --sha256 <조회에서 출력한 plan_sha256>
```

보존 증거는 JSON 배열이다. 각 항목은 `id=8`, `uuid`, `volume_id`, `volume_uuid`, `account_id`, `domain_id`, `interval`(0~3), `evidence_sha256`을 포함한다. 숫자 정책 ID로 기록된 삭제 정책의 job은 `creation_jobs`에 해당 job UUID를 포함한다. 근거가 소실된 INTERNAL 이력은 이 도구로 추정 복구할 수 없다. 별도 원본 백업/감사 자료를 확보해야 한다.

## 실환경 검증

31 GFS2·32 RBD에서 전용 도메인·일반 계정 2개·볼륨 2개를 사용한다. API로 display=false 정책을 생성하고, 관련 스케줄·세부정보·태그가 없고 정책 8이 비어 있는지 확인한 뒤 그 전용 행의 PK만 8로 고정한다. 전역 AUTO_INCREMENT나 운영 정책은 변경하지 않는다. 네 주기를 각각 수정 전 생성해 실제 오분류를 만들고 수정 후에는 같은 ID 8에서 정상 생성한다.

활성 오분류 4개 및 #1483 당시 이미 Destroyed인 원래 이력을 복구한다. INTERNAL 의도/근거 부족 대조군은 전용 수동 스냅샷의 메타데이터로 만든 합성 대조군임을 구분한다. 실제 내부 서비스의 INTERNAL 분류는 Java 검사, 공개 API 차단은 실환경 요청으로 검증한다. 복구 계획의 드리프트·롤백·재실행, 정책 삭제 후 검색, maxSnaps 보관 개수, 실제 스케줄러 생성과 다음 스케줄을 확인한다.

검색은 관리/일반 사용자, 볼륨·유형·주기 및 논리/사본 건수를 독립 DB 결과와 대조하고 실제 브라우저에서 필터와 행 표시를 확인한다. 기존 공통 검색 대화상자를 사용하며 새 UI 대화상자는 만들거나 변경하지 않는다. 정리는 정상 API로 수행하고 기존 운영 자원과 보호 FT 작업 트리를 보존한다. 전체 Cloud 빌드/CI를 시작하지 않고 WSL ext4의 변경 api/server 모듈만 빌드한다.
