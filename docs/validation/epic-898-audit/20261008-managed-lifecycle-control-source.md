# 런타임·ROOT·컴퓨트 자원 제어 연결 — 소스 검증

12137ed9ea536c3348d10694b8bf9d4034c0880f는 런타임/rollback의 실제 bundle 크기·고정 attempt/target/root 범위를 별도 operation에 연결하고, 효과 직전 임대 및 취소 CAS를 확인한다. 정상 완료 projection 전에 임대를 해제하며 불확실한 recovery와 해제 실패는 정상 완료로 승격하지 않는다. ROOT 교체는 보호된 승인 maintenance/bootHeld 범위를 사용하며 세션 드레인으로 표시하지 않는다. 컴퓨트 변경도 준비·활성화·rollback 및 중단/부팅 사이의 임대 수명주기를 연결한다.

민감 rendered/SMB/CHAP/AD payload의 KVM 전달을 보호 QGA stdin으로 분류하여 guest 임시 JSON 파일 및 ARGV의 credential 내용을 제거한다. 읽기 전용 render-status/boot-gate/POSIX plan·inspect의 분류도 검증했다. 기존 호스트 런타임에 필요한 wrapper/API 클래스만 배포할 수 있도록 전체 JAR 교체를 피한다.

정상 Checkstyle와 server/KVM/storagevm -am package에서 fresh92classes/561tests가 failures/errors/skipped0으로 통과했다. 새 테스트의 wildcard/unusedimport/장문 오류와 누락 static import를 수정한 뒤 정상 검사로 재실행했으며 skip한 중간 실행은 최종 통과로 계수하지 않았다. sorted9846Java path NUL bytes NUL SHA는 d14b018042727166f26b96dc87213ff9dfce0ba00ac8705b47194ccc6693e1c2로 실행 전후 일치했다.

| 검증 구성 | 결과 |
|---|---|
| 최신 full source | 561 tests/92 classes, 정상 Checkstyle 및 모듈package PASS |
| 현장 기존 b38 RuntimeImpl 혼합 | immutable module outputs 기반151 tests/18 classes 및 interface15methods ABI PASS |
| 기존 Runtime linkage 표시 | interface default false, 기존 두 클래스는 PENDING. 새 full Impl만 true |
| ROOT target logical lease | native3de 소스13focused/native123/Storage214 PASS; 실제 protected marker 정확 scope만 허용 |
| 실제 정책 상태 | MGT79/7instance globalfalse·서비스false·rev0, 활성 임대0 |
| 실제 신규 배포 | 121 source 배포0, native3de 배포0 |

Full artifact162managemententries SHA138cb0f71e8394034ebe0f492e96e119c6d17f0a08fdf68f90e5ad1c0c768cee를 immutable snapshot에 보존했다. 임시 현장 구성 후보는 새로운 RuntimeImpl의 outer/$1/$RuntimeResourceScope 세 항목 전체를 제외하고 기존 b38 두 항목의 정확한 SHA와 family count를 검증한다. Orphan helper를 남기지 않는다. 선택159entry candidate SHA12f0f50119c25fa1abfe0b50998753cea66d1ad3b044145358ce32febbccdeeb. 현장 배포 전에는 이전 guest의 postApply receipt 미지원에 대한 효과 전 capability 거부와 새 typed gate를 통합한다.

실제 관리 JAR manifest와 세 호스트 reportedversion은4.23.0.0-Mold.Europa-202610011115로 확인했다. 기존 template5part 및 보호 manifest 부재는 UNKNOWN이며 trim이나 이름 추론으로 strict gate를 완화하지 않는다. fresh 단일 source template과 네 프로토콜 ROOT/AD·임대·취소 실증은 계속 진행 중이다. 최종 UI 표준화#1275 착수 직전 전체 작업 중단·사용자 보고·추가 지시 대기 경계는 유지한다.
