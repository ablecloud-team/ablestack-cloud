# C1 계약 검증 기록 — 2026-09-24

## 범위

Cloud #1171 공통 설계 계약의 구조·cross-field 불변조건·공유 가능성을 검증한다.
production API, guest agent, 프로세스 종료/재시작 구현 및 VM E2E는 이 변경에 포함하지 않는다.

## 재현

- Python 3.12 + jsonschema 4.23.0 (requirements.txt).
- `python verify.py`: 정상/오류 응답 26개 수용, 정적 계약 위반 12개 거부, malformed JSON 3개 거부.
- `python verify.py --file <producer-envelope.json>`: consumer가 생성한 단일 wire envelope 검증 진입점.
- schema meta-validation: Draft202012Validator.check_schema.
- UUID/UTC 시각 format 검증 및 SHA 문자열/정수 tick 문자열/상한 제약.
- Windows 로컬 원본과 WSL ext4 소비 사본은 같은 SHA256 파일 목록으로 대조한다.
- `git diff --check` 수행.

## 회귀 벡터

- READY이면서 RPC disabled인 모순, cross-VM identity, cross-boot snapshot.
- arbitrary command 필드, 잘못된 schemaVersion, 정밀도 손실을 유발하는 숫자 tick.
- service target 없는 restart, postcondition 없는 성공, UNKNOWN mutation replay.
- snapshot 중복 identity, 예산 상한 초과, operationId 없는 reconcile.
- 중복 JSON key, NaN, Infinity.
- 허용된 업무 오류는 schema-invalid와 구분: UNSUPPORTED_VERSION, PERMISSION_DENIED,
  STALE_AUTHORITY/IDENTITY/SNAPSHOT, REQUEST_CONFLICT, BUSY, NOT_FOUND, QGA_UNREACHABLE.

## 한계와 후속 gate

이 fixture는 실제 QGA를 호출하지 않는다. OS별 지원, 신호 전송, 서비스 실행 세대,
PID 재사용 race, distributed placement fencing, guest journal fsync 및 crash 시점은
qemu Q1~Q6 / Cloud C2~C7에서 실제 runtime 증거를 추가해야 한다.
예시 QGA 버전/도구 버전은 제품 인증 정보가 아니다. fixture 수용은 기능 구현 완료가 아니다.

변경된 Cloud Maven 모듈과 UI는 없으므로 Java/전체 Cloud 빌드 및 UI 빌드를 실행하지 않았다.
qemu/ISO 패키지 빌드 및 배포도 실행하지 않았다.
