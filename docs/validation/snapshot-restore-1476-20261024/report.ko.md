# #1476 스냅샷 복원 볼륨 크기·오퍼링 검증

Epic #1473 / PR #1492 / 브랜치 `dhslove/epic-1473-storage-snapshots`

## 결과

31(GFS2), 32(RBD)에서 구현 범위 검증을 통과했다. 순수 복원은 ROOT/DATADISK 구분 없이 스냅샷 생성 시 크기·오퍼링을 상속한다. UI는 잘못된 필수 크기/ROOT 오퍼링 선택을 없애고 읽기 전용 상속 정보를 표시한다. 오퍼링 목록을 조회하지 않으므로 첫 항목과 목록 순서에 의존하지 않는다. API는 다른 용량·무시되던 IOPS/KMS 요청을 거부한다. 크기 변경은 복원 후 기존 볼륨 크기 변경 작업으로 분리했다.

스냅샷 조회의 virtualsize가 원본 볼륨의 현재 크기를 따라가는 문제도 수정했다. 원본을 1→2GiB로 확장·삭제한 뒤에도 API snapshotcontext.restore, virtualsize, DB snapshots.size와 복원 실물은 모두 1GiB였다.

| 검증 | 31 GFS2 | 32 RBD |
| --- | --- | --- |
| 변경 전 size=2 요청→1GiB 완료 재현 | 재현 | 재현 |
| 다른 크기/0/음수/IOPS/DATADISK 명시 오퍼링/ROOT 고정 크기 불일치 요청 | 6건 즉시 거부 | 6건 즉시 거부 |
| size=1 명시 요청 및 순수 API 복원 | 요청·최종 1GiB 일치 | 요청·최종 1GiB 일치 |
| DATADISK 사용자 크기/고정 1GiB UI 복원 | 크기·오퍼링·데이터 일치 | 크기·오퍼링·데이터 일치 |
| ROOT UI 복원 | 100GiB·원래 오퍼링 상속 | 100GiB·원래 오퍼링 상속 |
| ROOT 전체 논리 이미지 SHA256 | 삭제 전후 일치 | 삭제 전후 일치 |
| 원본 DATADISK 2개·ROOT 삭제 | DB Expunged·실물 부재 | DB Expunged·실물 부재 |
| Secondary 시험 파일 누락 | 실제 job 실패·폼 유지 | 실제 job 실패·폼 유지 |
| 파일 SHA 확인 후 같은 입력 재시도 | 성공·더블 클릭 1요청 | 성공·더블 클릭 1요청 |
| VM 미선택 유효성 검사 | 해당 필드 오류·입력 유지 | 해당 필드 오류·입력 유지 |
| F5·폼 재진입 | 새 mutation 0 | 새 mutation 0 |
| 비인증 mutation | HTTP 401 | HTTP 401 |

각 사이트에서 UI 제출은 실패 1건, 명시적 재시도 성공 1건, ROOT 성공 1건, 최종 사용자 크기 성공 1건이었다. 캡처된 4건의 요청에는 size/diskofferingid/IOPS/KMS/storageid가 없고 대상 snapshotid와 customjobid가 고정돼 있다. DB와 실제 qemu-img virtual-size, 디스크 오퍼링 UUID, 처음 1MiB=0x5a/마지막 1MiB=0xa5를 대조했다. 데이터 디스크 6개/사이트를 확인했으며, ROOT는 100GiB 전체 논리 이미지의 SHA256을 비교했다.

ROOT의 compute-only 오퍼링은 기존 볼륨 완료 응답에서 serviceoffering* 필드로 표현된다. 이름과 새 snapshotcontext.restore의 디스크 오퍼링 UUID, DB의 disk_offering_id를 함께 대조했다. 일반 볼륨/오퍼링 API 직렬화 계약은 #1422 범위를 유지했다.

## UI 표준 및 실패 처리

VM 표준 MoldDialog md(데스크톱 720px), 헤더·본문 스크롤·하단 작업 영역을 유지했다. 다크/라이트 모드와 1280×720/459×488에서 상속 정보, 버튼, 입력 영역의 경계와 간격을 확인했다. 텍스트는 테마 토큰을 사용하며 상속 문구의 계산 명암비를 별도 검증한다. 브라우저 원본 JPEG 12장과 DOM 치수를 `browser/`에 보존했다.

비동기 실패 때 이름·소유 범위·대상과 서버 오류가 유지되며 최신 상태 재조회 후 명시적으로 재시도한다. 기존 대상 고정·권한 범위·fingerprint·작업 lease를 재사용한다. VM 연결 전환 시 필수 규칙이 누락돼 일반 상태 변경 오류가 나오던 문제를 실제 UI에서 발견해 수정했다. VM 선택만 필수이며 장치 ID 자동 배정은 유지한다.

1차 UI로 고정 오퍼링 실패/재시도 및 ROOT 복원을 검증했고, VM 선택 규칙을 보완한 최종 UI에서 사용자 크기 복원·필드 오류·테마·반응형·재진입을 재검증했다. 최종 배포에서 두 사이트의 클래스 6개 및 정적 파일 921개가 검증된 산출물과 일치한다.

## 빌드·배포

- WSL ext4의 변경 api/server Maven 모듈만 빌드. 서버 4개 suite/25개 검사 PASS.
- UI 6개 관련 suite에서 고유 70개 검사 PASS(초기 68개 및 추가 2개, 마지막 관련 2개 suite 21개). lint와 최종 production UI build PASS.
- 전체 Cloud 빌드·GitHub Actions·qemu 패키지 빌드는 실행하지 않았다. 위 결과는 전체 Cloud CI 성공을 뜻하지 않는다.
- 관리 JAR의 변경 클래스만 교체하고 다른 엔트리는 그대로 보존했다. SSVM/호스트 설치 코드는 변경하지 않았다.
- 정적 UI 파일만 배포하고 WEB-INF/META-INF/config를 보존했다. 최종 Mold active, /client/ HTTP 200, 각 사이트 에이전트 5개 Up.
- 롤백 백업: `/root/snapshot-restore-1476-20261024-management-capacity-final`, `/root/snapshot-restore-1476-20261024-ui-contract-final-backup` 및 이전 단계 백업을 유지한다.

## 정리·범위

전용 접두어 `SNAPR1476-31/32` 자원을 정상 API로 정리했다. 활성 스냅샷·볼륨·VM·사본 참조·오퍼링은 0이고, NFS 파일/일시 누락 파일, GFS2/RBD 이미지·RBD trash·도메인·논리 해시용 임시 파일도 0이다. 스냅샷 DELETE 이벤트는 3개/사이트 각각 1회다. 정상 삭제된 고정 오퍼링은 제품 계약에 따라 Inactive 이력을 유지한다. 삭제 이력/비활성 메타데이터를 임의 SQL로 지우지 않았다.

기존 볼륨·스냅샷·VM·템플릿·디스크 오퍼링의 운영 투영을 대조했고 Windows/WSL의 보호 FT 작업 트리 HEAD·status·diff 해시가 전후 동일하다.

별도 테넌트/프로젝트/암호화 전체 조합의 실환경 회귀와 VM 부팅 재검증은 이번 범위가 아니다. 기존 소유자/프로젝트·암호화·할당 정책의 제약을 소스와 관련 안전 검사로 보존했으며, 전체 권한·조합 회귀는 #1489에서 수행한다. ROOT 템플릿 기반 부팅은 선행 #1474 증거와 구분한다.

## 다음 작업

#1480(Zone·스토리지별 사본 표시·건수·페이징 계약)을 먼저 진행한다. 이 계약은 #1481 보관/복사 UI와 #1486 상세·#1487 탭의 선행 조건이다. 남은 대화상자 일괄 정리 #1488은 기능 구현 후, #1489 통합 회귀는 최종 단계에서 진행한다. 새/변경 대화상자는 계속 VM 표준을 적용한다.

## 증거 재검증

`python3 scripts/verify.py`로 파일/소스 SHA256, 두 사이트 API·DB·실물·UI·정리·배포 증거를 오프라인 대조한다. 운영 전체 목록, 로그인 세션/비밀키, JAR, 다운로드 이미지와 해시용 대형 파일은 이 증거 묶음에 포함하지 않는다. `31-validation.json`, `32-validation.json`, 빌드/검사 로그와 `verification.json`을 참고한다.

로그 파일은 UTF-8/LF와 행 끝 공백을 정규화했으며, manifest에 원본 로그 SHA256도 기록했다. 브라우저 JPEG는 원본 바이트를 그대로 보존했다.
