# #1452 구현·검증 기록

## 배포 전 체크포인트

- Epic #1438 / #1452. 기존 4개 configuration DAO overlay와 instance writer·journal·recovery를 재사용한다.
- 공통 POSIX 정책의 전용 테이블·3개 API·세로 탭을 추가한다. NFS/SMB 표시 경로의 기존 /export/name 규칙은 유지하고, 등록된 정책만 실제 backingPath를 공유한다.
- 기존 단독 경로를 유지하며, 공통 경로에서만 중복 권한 초기화를 생략한다. 명시적 변경·fingerprint·재귀 before-image 상한·실패 원복을 유지한다.
- Ganesha bind alias를 findmnt TARGET/FSROOT·UUID와 device/inode로 관리 볼륨에 매핑한다. 다른 디렉터리/파일시스템을 제외하고 매핑을 preflight unit 벡터에 포함한다.
- 새 Python 모듈은 서명 관리 payload에 포함한다. 기존 완전한 9-file bundle은 호환되며 공통 정책 활성 VM의 호환 모듈 없는 원복은 보호한다.

검증: API/실제 DAO 22개, UI 컴포넌트 46개와 lint·production build, runtime 20개, POSIX unit 11개가 통과했다. 실제 SPARSE XFS SystemVM에서 bind mount와 NFS 두 포트·SMB 교차 I/O·재연결 차단·원복 등 6개 검사가 통과했다.

[native 결과](native-bind-result.json) / [회귀 요약](predeployment-checks.json)

이 체크포인트의 native 주입 시험은 이후 13번 배포·실제 Mold UI·별도 클라이언트 2대 검증을 대체하지 않는다. 최종 빌드·배포 버전과 인수 결과는 후속 기록에 추가한다.
