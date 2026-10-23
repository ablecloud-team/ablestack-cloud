부모 Epic: #1422

추적 코드: **VOL-14** · 우선순위: **P1** · 담당: Cloud 볼륨 연결/스토리지 선택

## 재현과 실제 결과

#1210의 표시이름 검색 기능을 31 GFS2·32 kernel RBD에서 검증하며 독립적인 기존 서버 결함을 발견했다.

1. 전용 프로젝트에 startvm=false로 VM을 생성한다. VM은 Stopped, ROOT는 Allocated이며 pod_id/last_host_id가 없는 최초 기동 전 상태다.
2. 같은 프로젝트에 1GiB 데이터 볼륨을 생성한다. 볼륨은 Allocated이며 pool/path가 없다.
3. 볼륨 연결 UI에서 변경된 표시이름으로 정확한 VM UUID를 선택한다.
4. attachVolume 요청의 volume/VM/project UUID는 정확하지만 비동기 작업은 Internal Server Error로 실패하고 연결이 설정되지 않는다.

31과 32에서 모두 재현했다. 31 job 14712/14713, 32 job 2224/2225. 사용자 화면에 실패가 표시되었으며 성공으로 집계하지 않았다. 실제 일반 계정의 Running VM 연결과 Ready 데이터 볼륨 → 최초 기동 전 Stopped VM 연결은 별도로 성공했다. 일반 계정 Allocated 조합은 공통 소스 경로이며 추가 실증이 필요하다.

## 확인된 원인

VolumeApiServiceImpl.getPoolForAllocatedOrUploadedVolumeForAttach가 nullable vm.getPodIdToDeployIn()를 primitive long으로 바로 언박싱한다.

    java.lang.NullPointerException: Cannot invoke "java.lang.Long.longValue()"
    because the return value of "com.cloud.vm.UserVmVO.getPodIdToDeployIn()" is null

실행 JAR 스택: getPoolForAllocatedOrUploadedVolumeForAttach:2737 → createVolumeOnPrimaryForAttachIfNeeded:2772 → orchestrateAttachVolumeToVM.
Epic 소스 5c9a982064df4f05ad9b5623520a1cb2dbe3f9ad의 server/src/main/java/com/cloud/storage/VolumeApiServiceImpl.java:2813에서 같은 primitive 언박싱을 확인했다.

## 구현·검증 범위

- 최초 기동 전 VM의 null pod/cluster/host와 Allocated ROOT/DATA를 명시적으로 처리한다. 단순 null→0 치환으로 임의 위치를 선택하지 않는다.
- ROOT가 아직 미생성일 때 DATA 연결을 지연 할당할지, 적격 저장소를 안전하게 선택할지 설계하고 기존 API 계약·Zone/계정/프로젝트·태그·로컬/공유 스토리지·신규 할당 정책을 보존한다.
- Uploaded/Ready·기동 이력 있는 Stopped·Running 경로와 오류 원인 전달을 회귀한다.
- 의미 있는 server 모듈 회귀, 변경 Maven 모듈만 WSL ext4 빌드, 31/32 관리 모듈 배포·실환경 기능 검증을 수행한다. 전체 Cloud CI는 별도 명시 요청 전 실행하지 않는다.
- 원래 startvm=false + Allocated 조합을 수정 후 그대로 재실행한다. VM을 먼저 기동하는 테스트 준비 변경을 원래 결함 해결로 판정하지 않는다.
- 실패 상태의 연결/volume/VM/DB·실물을 보존하고 전용 자원 정리·운영 자원 보존을 확인한다.

## 완료 기준

- [ ] null pod 경로가 NPE 없이 설계된 계약으로 처리된다.
- [ ] 31/32, 계정/프로젝트, 최초 기동 전과 기동 이력 있는 Stopped/Running의 지원표를 실증했다.
- [ ] 선택한 UUID·API/DB·Agent/실물·초기 실패/수정 후 성공을 구분했다.
- [ ] 변경 모듈 검사·빌드·배포·정리 증거를 Epic PR #1435에 연결했다.

이 결함은 #1210의 표시이름 검색/UUID 선택 수정과 구분한다. #1434 최종 통합 검증 전 해결·재검증해야 하는 제품 결함이다. 원본 로그는 인증 정보가 포함될 수 있어 비공개로 보존하고 공개 보고에는 제한된 예외/UUID/작업 ID만 사용한다.
