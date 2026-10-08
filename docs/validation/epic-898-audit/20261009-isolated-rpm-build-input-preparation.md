# Epic #898 최종 모듈·패키지 빌드 입력 준비

저장소 rocky97-rpm.yml의 official Rocky 9.7 container URL과 SHA-256을 사용했다. 다운로드 아카이브 SHA-256은 2213bb44c0f1bfd0bbef16118ab5a1ec6875362c5ad6c6dafdecf28d14151ad2로 CI pin과 일치한다. OCI image ID는 9e021fdeb530397bee9505039ee4349da531384721884871692ac86dc4119c21이다.

WSL의 기존 Podman storage는 vfs/overlay 설정 충돌이 있었다. 기존 storage 삭제·설정 변경 없이 Epic 전용 storage/runroot와 vfs override를 사용해 official OCI를 로드했다. 컨테이너 내부 /etc/os-release는 Rocky Linux 9.7, dnf·rpm·curl 실행 경로가 확인됐다.

normal RPM helper b7750cc192fa93c3f043343f600f4548dacdaf8fc9e20356c1a54444c526eb2d의 dependency 준비 단계만 분리 실행했다. 별도 컨테이너의 vault/CRB/EPEL 및 376개 패키지 목록을 보존했다. inventory SHA-256은 ee6a29d8e796f42313f999261041607662d895cd13c70ca474ca104a2b00edf6, dependency image ID는 23b7da86208444d3e1597a7402178c53294d5f8ee90efb15af86a0e6449cfd17이다. canonical 저장소는 마운트하지 않았고 WSL·클러스터 DNF 설정 변경은 0이다.

Node 14.21.3 archive의 SHA-256 05c08a107c50572ab39ce9e8663a2a2d696b5d262d5bd6f98d84b997ce932d9a는 nodejs.org의 해당 버전 SHASUMS256과 일치한다. 별도 컨테이너에서 설치·toolchain probe를 수행했다. build-ready image ID는 09d3055c6dd6ecb9d51cf8241a81f40040d444c2092e94063aa7ac0fedd09234이며, Node 14.21.3 / OpenJDK 17.0.19 / Maven 3.6.3 / UTF-8을 확인했다. Maven/NPM dependency 내용은 최종 빌드에서 추가로 보존한다.

최종 SharedFS source는 아직 고정되지 않았다. 이 준비는 source 기능 빌드·RPM/SRPM 생성·서명·배포 성공의 증거가 아니다. 최종 AD·복원·기능 입력 코드가 검증되면 사용자 승인 범위에서 단일 source 모듈·템플릿·런타임 빌드를 이어간다. noredist floating nonoss 입력을 사용해야 하는 경우 선택 commit와 archive/hash를 별도 고정해야 한다. 최종 UI #1275는 시작하지 않는다.
