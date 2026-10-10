# CREATE_NEW 할당 기록과 동일 자원 재개 정상 검증

소스8a47201908e는 기존 생성 기능을 재사용해 SharedFS/artifact와 VM/NIC/ROOT/DATA 할당을 시작 전에 같은 DB transaction으로 기록한다. 중복 할당 위험이 있는 실패·응답 유실 재시도는 이름 채택이나 새 VM 대체 없이 같은 기록을 검증해 재개한다. 표준 deploy 경로와 기존 native 진단 메서드·테스트·CLI는 보존했다.

초기 NIC 정체성·concrete 주소·디스크 원 source/UUID/size/offering/SPARSE·FAT를 고정한다. 첫 확인된 Ready placement의 pool/path/실형식과 Direct의 제한된 null→assigned 주소를 별도 CAS로 기록하고 이후 drift를 거절한다. QCOW2→Ready RBD RAW는 원 할당 증거와 실제 RBD 조건이 있는 경우만 허용한다. THIN 생성은 거절한다.

실제 controlled create→allocation callback→ROOT Ready/DATA Creating→start 예외 경로에서 ROOT Ready만 독립 CAS로 보존하고 전체 실패를 유지한다. 두 디스크의 Ready 기록이 없으면 성공으로 반환하지 않는다. 첫 관측 이전 미관측창과 외부 usage telemetry·MAC sequence 진행은 DB 원자성 범위와 구분한다. 네트워크는 실제 DirectNetworkGuru 또는 nonexternal ExternalGuestNetworkGuru와 source-defined DB-only component proxy 범위만 허용하며 외부 네트워크·미검토 구현은 할당 전에 거절한다.

직접 javac/controlled105 tests/4 suites 및 canonical diff/최장990자/기존 진단 bytes 보존을 확인했다. 정상 모듈은 API/schema/server/KVM/storagevm과 필요한 종속 모듈의 Epic127 selector를 실행했고, 실제959 tests/127 suites·failure/error/skipped0·Checkstyle/reactor가 통과했다. 이는 저장소 전체의 모든 테스트 실행이나 실제 DB/클러스터 복원 완료 주장이 아니다.

Java9906/tracked15557/native56의 빌드 전후 NUL hash 및 source8a archive를 대조해 차이0, 출력5모듈6318파일을 보존했다. 이전 정상053070 대비 production class byte delta는 API2/provider1/server9이며 추가·삭제0, KVM/schema0이다. 기존 public/protected name/descriptor/visibility 보존은 compile baseline에서 확인했다. 실제0cf 관리 런타임의 classspace/provider 계보에서 선택6클래스의 기존 ABI/계층·class signature 및 참조3239개 모두 해결됐고 missing0이다. 전체12 byte delta 중 동작/멤버 변경6만 배포하고 debug 차이6은 원 bytes를 유지했다. 정상 전체module12클래스와 byte-identical한 배포로 주장하지 않는다.

[정상 source/테스트 증거](clone-allocation-provenance-normal/normal-source-validation.json). 실제 CREATE_NEW UI/API 및 실패 재시도는 아직 미완료이며 이슈 #909는 열린 상태다. 최종 UI #1275는 미착수다.


## 실제 관리6클래스 제한 배포

기존0cf/PID1382861과 exact6 entry·Runtime3·libs·승인public5를 포함한UI3480 및trust51을 새로 확인했다. root0700 staging의4파일·manifest/payload/서명을 고정하고, O_EXCL 단일 attempt와 승인한 public trust map SHA를 확인한 뒤 원 JAR 백업·fsync/atomic overlay·mold 재시작을 한 번 수행했다.

새 JAR09e63fdc07561d9a5d5af1e2ba0a3b17d90ee48914880e150563c159155ccd15/PID1388662, 백업 /root/epic898-clone-allocation-8a472019-backup-20261010-122243이다. 적용1/재시작1/rollback0/guest0이며 선택6 외204642 entry의 논리 내용·순서, Runtime3/debug6/다른library8/UI3480/pub5/trust51을 보존했다. 원 ZIP의 전체 raw byte 동일성을 주장하지 않는다.

새 정상 로그인과 host3 Up/서비스8 Running, 원FS8/VM21/volume33/F1operation17 참조, ffef catalog051 AVAILABLE 및 archive/manifest/keyId/pin의 동일성을 확인했다. helper의 applicationReadiness=false 이력은 유지하고 별도 정상 API postcheck가 readiness 완료를 증명한다. nativeCLI/source/key/cipher/ROOT/DATA/DB schema/UI기능 변경은0이며 실제새clone 인수와 구분한다.

[실제 배포/보존 최종 proof](clone-allocation-provenance-normal/management-six-deployment-final-public-proof.json), [선택클래스 ABI 검증](clone-allocation-provenance-normal/selected-runtime-abi-manifest.json).
