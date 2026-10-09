# 원본 SMB 정지 상태의 코드 검증과 서비스 가용성 분리

> f493의 887/456 검증·배포 시점 기록이다. 후속 readback pin 수정은 Java888, receipt 검증 수정은 native457로 검증·반영됐다. 현재 적용 상태와 실제 원본 복구 결과는 [최신 검증](20261010-code29-source-recovery-actual.md)을 따른다.

정상 코드 업그레이드가 원래 복구 작업이 정지한 SMB를 일반 서비스 장애로 판단해 이전 코드로 돌아가는 순환을 수정했다. 일반 `operation verify`의 degraded 상태는 유지하며, 원본 정지 권한과 새 코드의 서명을 별도로 입증한 경우에만 코드 적용을 완료한다. SMB가 아직 재개되지 않은 동안 서비스 가용성을 확인된 상태로 표시하지 않는다.

## 구현과 검증

통합 커밋 `f493d77f52f5006c2ced3c8d3011e3508430b232`의 Java3/native3 파일을 고정했다. native 집중43·정상456/49 selectors, Java 집중8·정상887/124 selectors와 Checkstyle을 통과했다. 실패·오류·생략0이다. Java9896개 NUL digest ce0ccf85…와 nativeStorage141개 fde0d722…를 전후 비교해 동일함을 확인했다. generic health·identity capsule·SID helper·CURRENT 신원 모듈은 원래 바이트를 유지했다.

- 관리 서버는 같은 instance의 유일한 일반 LOCAL RECOVERY 작업에서 보호된 SOURCE7/context/keyRef와 미출판 의도를 읽는다. ROOT/SERVICE/AD·formatter·외부 hold·변경된 ROOT·다른 writer를 거절한다.
- 서명된 대상 활성화 후 동일 transaction의 실제 signed/files/entrypoint readback과 대상 bundle/CLI pin을 검증한다. 일반 health의 다른 설정된 프로토콜·QGA 실패는 거절하며, 설정되지 않은 기본 포트의 비활성 상태는 정상으로 구분한다.
- native 상태 조회는 원 SOURCE/scope/BOOT·실제 정지 메타데이터·private holder 부재·cipher 부재·원본과 현재의 서명 ENTRY를 검증한다. 정확한 kind LOCAL_SOURCE_STOPPED_RUNTIME_QUARANTINE과 15필드만 반환하며 증빙/자료를 쓰지 않는다.
- 기존 엄격한 코드 호환 정규화에 지정된 상태 조회 IF 한 개의 AST/바이트 구간만 추가했다. 나머지 바이트를 변경하면 전체 원본 CLI2dc 비교에서 거절한다. 원 journal SHA와 키를 덮어쓰지 않는다.
- 검증된 정지의 CODE 결과는 runtimeCodeVerified=true, healthSuccess=false, serviceAvailabilityVerified=false를 기록한다. 조건이 없거나 잘못되면 기존 자동 롤백을 유지한다. 고정 validationStage/exceptionType만 기록하고 context·키·원문 payload는 기록하지 않는다.

실제 Native 클래스의 공개15필드 issuer86e80…을 새 Java 고정 출력으로 소비해6긍정/9거절을 통과했다. 설치·서비스 제어는 격리 제공자이며, actual producer CLI90495…와 provider의 synthetic ENTRY SHA e4484a…를 구분했다. 이 bridge는 실제 클러스터의 코드 적용·서비스 재개 완료를 대신하지 않는다.

## 실제 관리 서버 배포

정상 immutable5모듈6295파일을 이전882 출력과 비교했다. 변경은 RuntimeUpgradeManager 계열3개와 LocalSourceProof1개뿐이며 다른5242 production 파일은 동일하다. 기존 실제 라이브러리/class chain의 signature와620 member 참조를 검증했다. 원 JAR52cc…와 PID1310175 및 각 교체 entry hash가 일치할 때만4개를 반영했다.

- 적용 JAR SHA-256 `2c84b10478744d125daad6da7a26f8d96acfdaded5b64be5dc63c9d62bb4a6d1`, PID1328703.
- 백업 `/root/epic898-owned-source-f493-backup-20261010-015831`, 적용2026-10-10 01:59:27 KST.
- UI334의 index4595c74e…·configd3e28531…/symlink/locales 및 다른 JAR entry를 보존했다. DDL/API/interface/UI 변경0이다. 기존 `storage_service_operation.previous_snapshot_json`과 `storage_service_runtime_upgrade.verification_json`을 재사용한다.
- 실제 HTTP200/API 로그인·8서비스 VM Running·3호스트 Up와 cfe ROLLED_BACK/219b COMPLETE/f85 BLOCKED/fef RECOVERY_REQUIRED 기록을 확인했다. F1의 SMB 정지는 원래 작업이 소유한 상태를 유지했다.

## 서명 번들과 실제 인수 경계

같은 커밋의 CLI SHA-256 `90495d2827de4127427efd9827f689e12c1ee19027a52c2a64ea8685860beb81`로 version epic898-smb-quarantine-f493d7-20261010 번들을 만들었다. RAM Ed25519 테스트 키만 사용했고 private key 파일0이다. archive397087B/1e9c16bd…·manifest1443B/72a04d20…·sig64B·public113B를 검증했다. 공개5파일을 게시하고 HTTP200/크기/SHA를 다시 확인했으며 기존 trust를 보존했다. 정식 CI 키 및 기존 전체 이미지b6aa와 구분한다.

[고정887/456 검증·실제4클래스 배포·서명 게시의 공개 증거](20261010-source-pause-code-health-887-456-deployment-proof.json).

이 문서의 배포 시점에는 새 CODE와 같은 원 fef 작업의 정상 UI 복구가 아직 완료되지 않았다. 단일 F1 actor가 정상 UI 카탈로그·CODE와 typed 결과를 확인한 뒤 원 작업을 복구하고 LOCAL ACL·실제 SMB 인증/RW/fresh reconnect/거절을 검증한다. 수동 start·force clear·journal 재해시·재포맷은 하지 않는다. 최종 UI 표준 정리 #1275는 미착수다.
