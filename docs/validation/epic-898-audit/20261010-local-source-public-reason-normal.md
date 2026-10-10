# 원 SOURCE 복구의 공개 예외 종류 보존

원 df11 정상 UI 복구가 native import-local-source에서 실패했지만 관리 서버가 기존 공개 예외 종류를 일반 오류로 덮는 문제를 보완했다. 소스 053070은 LOCAL_SOURCE_CHECKPOINT_REJECTED의 고정 세 필드만 엄격하게 확인하고 허용된 Python 예외 종류를 nativeReason으로 보존한다. 임의 메시지·중첩 자료·키·payload·중복/추가 필드·잘못된 JSON은 일반 오류로 유지한다.

실패와 성공의 의미 및 원 native19ad·SOURCE capsule·writer lease·STOP/resume 절차는 그대로다. 새 API·UI·DB 스키마는 추가하지 않았다. 실제 오류 메서드나 줄 번호까지 확인할 수 있다는 뜻은 아니다.

집중 검증은 50 tests/5 suites이고, 정상 모듈은 931 tests/126 suites, failure/error/skipped 0 및 Checkstyle/reactor 성공이다. Java 9,905개와 code archive 15,538개(파일·symlink 포함)를 대조했고, 실제 빌드 HEAD dc6a의 문서 6개 추가를 코드와 구분했다. 빌드 전후 코드·tracked bytes 및 archive 차이는 0이며, 출력 6,315개를 별도 보존했다.

정상 산출물을 live 6b 관리 배포와 대조한 server delta는 14개 클래스다. API/schema/KVM/provider의 delta는 0이다. 내부13클래스는 javap 명령/member가 동일하고 debug metadata만 다름을 확인했다. 전체14 ABI/참조2,708개와 선택outer1 참조2,547개 모두 해결되었고, 기존 내부13클래스를 유지하는 제한 배포를 선택했다. 정상 module 전체14클래스와 byte-identical한 배포로 주장하지 않는다.

최종 UI 표준 정리 #1275는 미착수다.


## 실제 관리 클래스 제한 배포

보호된 root0700 staging과 원 JAR 백업을 검증한 뒤 outer1만 반영하고 관리 서버를 재시작했다. apply1/exit0/rollback0이며 2026-10-10 11:29:57 KST에 새 JAR 0cf93b6a4d9652d976c8df4a3f894556c3ba2b26fc303d45d48b565a018db2cf/PID1382861이 활성화됐다. 백업은 /root/epic898-local-source-reason-053070-backup-20261010-112845다.

새 정상 API 로그인, host3 Up/Enabled, service8 Running 및 원 SharedFS8/VM21/volume33/F1operations17의 정확한 공개 참조 보존을 확인했다. 원 내부13·RuntimeUpgradeManager3·기타 라이브러리·UI/index/config symlink/WEB-INF/assets를 보존했고 guest/nativeCLI/key/cipher/ref/journal/DDL/KVM/UI 변경은0이다. ZIP의 다른 논리 entry 내용과 순서를 보존하며 ZIP 전체의 raw byte 동일성을 주장하지 않는다.

원 df11은 배포 직후에도 RECOVERY_REQUIRED/rev11/100이었다. 복구 제출 전 fresh signedCLI status의 source19ad/BOOT/GEN10/pending·원ref/PUB/cipher·소유권/세션없음/복원지원과 기존 writer-idle을 확인했다. 두 FILE filesystem·NFS/SMB sentinel과 RAW blank를 보존했다. 정상 UI 원 SOURCE 복구 한 번을 허용했고 현재 결과를 수집 중이며, 배포 성공을 원 복구나 all4 인수 완료로 확대하지 않는다.

[실제 관리 배포 후속 증빙](local-source-public-reason-normal/outer-one-deployment-postcheck-public.json), [원 SOURCE fresh 복구 가드](local-source-public-reason-normal/fresh-df11-public-guard-preservation-proof.json).
