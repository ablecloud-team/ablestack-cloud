# CREATE_NEW 할당 기록과 동일 자원 재개 정상 검증

소스8a47201908e는 기존 생성 기능을 재사용해 SharedFS/artifact와 VM/NIC/ROOT/DATA 할당을 시작 전에 같은 DB transaction으로 기록한다. 중복 할당 위험이 있는 실패·응답 유실 재시도는 이름 채택이나 새 VM 대체 없이 같은 기록을 검증해 재개한다. 표준 deploy 경로와 기존 native 진단 메서드·테스트·CLI는 보존했다.

초기 NIC 정체성·concrete 주소·디스크 원 source/UUID/size/offering/SPARSE·FAT를 고정한다. 첫 확인된 Ready placement의 pool/path/실형식과 Direct의 제한된 null→assigned 주소를 별도 CAS로 기록하고 이후 drift를 거절한다. QCOW2→Ready RBD RAW는 원 할당 증거와 실제 RBD 조건이 있는 경우만 허용한다. THIN 생성은 거절한다.

실제 controlled create→allocation callback→ROOT Ready/DATA Creating→start 예외 경로에서 ROOT Ready만 독립 CAS로 보존하고 전체 실패를 유지한다. 두 디스크의 Ready 기록이 없으면 성공으로 반환하지 않는다. 첫 관측 이전 미관측창과 외부 usage telemetry·MAC sequence 진행은 DB 원자성 범위와 구분한다. 네트워크는 실제 DirectNetworkGuru 또는 nonexternal ExternalGuestNetworkGuru와 source-defined DB-only component proxy 범위만 허용하며 외부 네트워크·미검토 구현은 할당 전에 거절한다.

직접 javac/controlled105 tests/4 suites 및 canonical diff/최장990자/기존 진단 bytes 보존을 확인했다. 정상 모듈은 API/schema/server/KVM/storagevm과 필요한 종속 모듈의 Epic127 selector를 실행했고, 실제959 tests/127 suites·failure/error/skipped0·Checkstyle/reactor가 통과했다. 이는 저장소 전체의 모든 테스트 실행이나 실제 DB/클러스터 복원 완료 주장이 아니다.

Java9906/tracked15557/native56의 빌드 전후 NUL hash 및 source8a archive를 대조해 차이0, 출력5모듈6318파일을 보존했다. 이전 정상053070 대비 production class byte delta는 API2/provider1/server9이며 추가·삭제0, KVM/schema0이다. 기존 public/protected name/descriptor/visibility 보존은 compile baseline에서 확인했다. 실제0cf 관리 런타임의 classspace/provider 계보와 ABI/linkage 검토는 진행 중이다.

[정상 source/테스트 증거](clone-allocation-provenance-normal/normal-source-validation.json). 실제 배포·CREATE_NEW UI/API 및 실패 재시도는 아직 미완료이며 이슈 #909는 열린 상태다. 최종 UI #1275는 미착수다.
