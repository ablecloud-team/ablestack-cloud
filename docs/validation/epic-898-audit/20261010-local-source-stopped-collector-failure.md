# LOCAL SMB 정지 후 수집 실패의 실제 원인과 복구 경계

13번 클러스터 F1에서 정상 UI 복구가 소유 SMB/nmbd를 정지한 뒤 암호화 체크포인트를 만들기 전에 실패했다. 실제 설치 코드의 기본 collector 어댑터가 `posix_policies` 키워드를 받지 못하는 TypeError가 최초 오류임을 확인했다. 기존 테스트에서 collector를 교체한 fixture가 이 불일치를 가렸다. 원본 DATA·신원 자료를 보존한 상태에서 기본 어댑터를 통과하는 회귀와 최소 수정으로 복구를 진행한다.

## 실제 적용 코드와 관리 서버

- native 소스 `6bc43598bc01f7ab1ed57a5fbb9196f479801782`: 정상 454 tests/49 selectors, 실패·오류·생략0. 실행기 부모/자식·systemd cgroup·역사적 서명 ENTRY 소유권과 상태 요청 stdin 전달을 수정했다.
- 정상 UI CODE 작업 `9292382a-8ad5-4dae-b025-7ee39750b27d`: COMPLETE/100, 카탈로그 `224ab0b1-3a29-4821-8762-bc26eeca7b44`, transaction `runtime-71674b05-f59f-4e85-9291-e36decddbf67`, healthSuccess/consumerCompatible=true. 실제 CLI SHA-256은 `2dc47c3a31ac5f1e7250281e52bce50e10e428d541252b099f3cb8a539548c38`이다.
- 테스트 Ed25519 키는 RAM에서만 사용했다. archive389841B/SHA-256 `741696daa9020868dd6049db3a3023a12addae37fc230beb177f3a4a9a35f878`, manifest1439B/SHA-256 `edfdcde86160bd141a30e48e5a2dbe275b56c9959759868cfa5c1d0f616db4d8`, signature64B이다. 기존 trust를 보존하고 정식 CI 키와 구분했다.
- 관리 서버는 기존 정상 b9c/882 검증 출력과 JAR SHA-256 `52cc153e3a7f509d4bcb43894977594cd2c9f8c9abc63fc4cca299702ef97136`을 유지했다. UI도 기존 정상334 출력과 index SHA-256 `4595c74ee9cacf57aa625e8906023451a76d39aec4bc9358603da3164bb7cdc1`을 유지했다.

## 실제 실패 위치

대상 공유 파일 시스템 `ee35189f-4914-4e9c-b4e6-64b86164f031`, instance `3480bb2c-99ee-42f2-91cf-715ded5dd35f`, VM `i-2-54-VM`에서 원래 작업 `fef126a6-936a-4936-b22c-201b7e5b9b4d`/revision5를 정상 UI로 복구했다. journal은 RECOVERY_REQUIRED이며, LOCAL checkpoint `5a7319c4-d43f-3f1d-a941-ec40f6c81dec`의 cipher는 아직 없다. SMB와 nmbd는 이 작업이 소유권을 확인해 정지한 상태다.

설치 코드의 정의를 RAM에 읽어 효과 없이 순서별로 관찰한 결과는 다음과 같다.

| 단계 | 실제 결과 |
| --- | --- |
| 원본 SOURCE7·scope·boot 권한 | 통과 |
| 원래 정지 DB 메타데이터와 현재 관측 비교 | 동일, 신원 DB holder0 |
| 원본 POSIX receipt 수집 | 통과, records0 |
| 기본 collector 호출 | TypeError: `SmbSourceCheckpoint.__init__.<locals>.<lambda>() got an unexpected keyword argument 'posix_policies'` |
| 이 진단에서 encrypt/export/import/resume | 호출0 |

기존 진단의 sourceSmbCanonicalPresent=false는 원하는 상태 파일의 축약 키를 잘못 사용한 관측 오류였다. 전체 SOURCE7 키를 확인한 후 실제 값 true로 정정했다. 별도 진단 스크립트의 launcher NameError도 이 최초 제품 오류와 구분한다.

## 보존과 다음 검증

boot `dc4b6429-35d3-4224-b199-3a0c6c829de0`, current generation4/구성 SHA-256 `dafecb8bd68c29c0abd2b6e5ff31422361bb325768a54c77de7e305f24db544a`, 원래 PREPARED 작업, 두 신원 DB 메타데이터와 두 SPARSE20GiB DATA의 장치·XFS UUID·mount를 보존했다. NFS4096바이트 sentinel SHA-256 `9ba0b0280276cad982cfa3df6e6821f2b4b8ea04761b905cc1d089cbd72c4c8a`도 유지됐다. 새 포맷·수동 서비스 시작·journal 재해시·force clear·기존 키/참조 삭제는 하지 않았다.

collector의 키워드 전달만 수정하고 실제 기본 어댑터→generic collector→RSA/AEAD 회귀를 추가한다. 새 코드로 원래 정지 작업을 재개할 때도 journal의 정지 당시 CLI SHA를 보존해야 한다. 서명된 이전 ENTRY·설치 receipt와 현재 코드의 제한된 바이트 호환성을 검증하는 별도 경로를 검토 중이며, 정상 서명 CODE와 정상 UI 복구를 통과하기 전에는 ACL·외부 SMB I/O 완료로 표시하지 않는다.

공개 관측은 준비 디렉터리의 `actual-current-recovery-500/stopped-collector-6bc-readonly.json` 및 `after-fef-ui-recovery-6bc-readonly.json`, 실제 CODE DTO `6bc-code-runtime-public.json`에 있다. 비밀번호·개인키·private DB 원문/SID는 기록하지 않았다. 최종 UI 표준 정리 #1275는 미착수다.
