# Epic #898 통합 구현·검증 기록

실제 기능 구현은 epic/898-sharedfs 통합 브랜치와 단일 통합 PR에서 관리한다. 목업 PR은 사용하지 않는다.

## 완료 판정

- 각 이슈는 실제 Mold UI 입력·실행·진행·성공/실패·재조회와 API/DB/SystemVM 결과를 함께 확인한다.
- API 단독 성공, 목업, unit test만으로 기능 완료를 선언하지 않는다.
- 별도 테스트 자원을 사용하며 기존 nfs-test의 데이터를 포맷·삭제하지 않는다.
- 최종 레이아웃 #1275는 기능과 API 계약이 확정된 뒤 마지막으로 수행한다.
- PR 생성, 검증 완료, 이슈 해결 및 병합 완료를 구분한다.

## 진행 표

| 이슈 | 기능 | 구현 및 검증 상태 |
| --- | --- | --- |
| #911 | [SharedFS][SystemVM] 운영 중 Storage Service 런타임 코드 인플레이스 업그레이드 지원 | 기존 CLOSED; 통합 회귀 검증 대상 |
| #1269 | [SharedFS][P0][UI] 사용 가능 상태의 상세 탭 무한 로딩 및 상태 정합성 복구 | 조회 deadline/부분 실패 복구 구현, UI 회귀 28건 통과; 실제 UI 배포·검증 진행 |
| #924 | [SharedFS][Runtime] Storage Service 런타임 번들 카탈로그 및 수명주기 관리 | 대기 |
| #892 | [SharedFS] 설정 변경 실패 시 원자적 롤백과 상태 복구 지원 | 대기 |
| #897 | [SharedFS] 장시간·고메모리 설정 변경을 위한 사전 점검과 장애 격리 | 대기 |
| #974 | [SharedFS][SystemVM] 대용량 백킹 볼륨 초기 포맷 timeout 및 재개 가능한 준비 작업 지원 | 대기 |
| #913 | [SharedFS][SystemVM] L2 고정 IP 기본 게이트웨이 재부팅 지속성 보장 및 DHCP 충돌 방지 | 원인 확인 및 보완 중; 실제 UI와 클러스터 검증 전 |
| #914 | [SharedFS][SystemVM][NFS] 다수 Export 부팅 Reconcile의 초기 Probe 지연과 고정 Timeout 제거 | 원인 확인 및 보완 중; 실제 UI와 클러스터 검증 전 |
| #918 | [SharedFS][NFS] 제한 ACL의 로컬 visibility probe 실패로 전체 Ganesha가 중지되는 문제 | 원인 확인 및 보완 중; 실제 UI와 클러스터 검증 전 |
| #895 | [SharedFS] 삭제 시 데이터 볼륨 보존 및 분리 정책 지원 | 대기 |
| #909 | [SharedFS] 구성 백업 번들 내보내기·업로드 및 마지막 정상 구성 복원 지원 | 대기 |
| #920 | [SharedFS][SystemVM] 운영 서비스의 SystemVM 템플릿 교체 업그레이드 및 설정 자동 복구 지원 | 대기 |
| #900 | [SharedFS][SMB] 프로토콜 반복 활성화 시 기존 엔드포인트 유실 방지 및 다중 IP 서비스 보장 | 대기 |
| #896 | [SharedFS] 컴퓨트 오퍼링 제약 조건 안내와 선택 UX 개선 | 대기 |
| #891 | [SharedFS] 온라인 스케일업 가능한 컴퓨트 오퍼링 사용 강제 | 대기 |
| #904 | [SharedFS] 초기 생성 시 기존 볼륨 경로의 디스크 오퍼링 의존성 제거 | 대기 |
| #905 | [SharedFS] 목록 용량을 모든 백킹 볼륨의 고유 합계로 표시 | 대기 |
| #894 | [SharedFS][NFS] squash 정책별 POSIX 권한 기본값과 가이드 정립 | 대기 |
| #906 | [SharedFS][NFSv4] 숫자 UID/GID 모드로 ID 매핑 불일치와 nobody 표시 방지 | 대기 |
| #903 | [SharedFS][NFS/SMB] 교차 프로토콜 공유 디렉터리의 POSIX 속성 단일화 | 대기 |
| #915 | [SharedFS][SMB] 부모 UID/GID 상속을 위한 inherit owner 및 setgid 정책 지원 | 대기 |
| #916 | [SharedFS][NFS/SMB] 공유 하위 디렉터리별 POSIX 소유권·ACL 정책 관리 | 대기 |
| #919 | [SharedFS][SMB] 공유별 create/directory mask와 force mode 정책 지원 | 대기 |
| #910 | [SharedFS][NFS/SMB] 동일 백킹 볼륨 하위 디렉터리의 중첩 Export/Share 지원 | 대기 |
| #908 | [SharedFS][SMB] 공유별 force user/group 기반 고정 POSIX UID/GID 지원 | 대기 |
| #902 | [SharedFS][SMB] AD 가입 시 다중 서비스 호스트명·DNS 별칭 등록 지원 | 대기 |
| #907 | [SharedFS][SMB] 공유별 클라이언트 IP/CIDR 접근 허용 정책 지원 | 대기 |
| #901 | [SharedFS][NFS] 프로토콜·리스너 정보를 행 기반 목록으로 분리 표시 | 대기 |
| #1275 | [SharedFS][UI][Final] 가상머신 상세 표준 기반 전체 탭·대화상자 레이아웃 최종 정리 | 대기 |

## 검증 증거

- 상세 조회: 기존 VM/탭 scope 변경 4건과 중복·deadline·부분 실패·늦은 응답 4건, 기존 SharedFS 17건 및 locale 3건 총 28건 통과.
- 제한 NFS ACL과 지연 probe: 실제 embedded Python 함수 실행 2건 통과. 실제 허용/비허용 클라이언트 I/O는 아직 미검증.
- 기존 배포 UI의 관리자 로그인/상세 직접 진입에서 nfs-test 속성과 NFS 개요 표시 확인. 새 보완본의 배포 검증과는 구분.
- SMB AD 실제 인증 검증용 DC/DNS/도메인/계정 정보는 사용자에게 요청함. 다른 구현과 검증은 계속 진행.

## DB migration 기록

새 테이블 및 컬럼을 추가할 때 정확한 이름, 적용 순서, additive migration, 재시작 후 상태 및 rollback 영향을 이 항목에 기록한다.
