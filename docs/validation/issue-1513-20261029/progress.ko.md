# #1513 다운로드 복원 구현 및 중간 검증

2026-10-29 KST · Epic #1510 · Draft PR #1514. **진행 중 / 이슈 미종료**.

## 구현 및 배포

- 제품 소스: `7eefbf9324fc2bd1ec4f5ba7553038fa28bf9699`.
- 일반 copy/move 검사는 유지하고 Ready Primary → Image의 읽기 전용 `exportVolume`을 분리했다. 원본 정책·형식·경로·연결·관측 증거를 보존한다.
- GFS2/RBD 원본을 독립 QCOW2로 변환한 뒤 형식·크기·전체 내용 비교를 통과한 파일만 공개한다. 출력 검증은 원본 allocation evidence와 분리한다.
- Full/falloc zero cluster가 다운로드 필요 용량으로 오인되는 조건을 제거하고 실제 남은 공간을 시작 전·변환 중 검사한다.
- 실행 직전 Ready/정지·분리, 암호화, Flatten, 장애 보호와 스냅샷 작업을 검사한다. 볼륨별 잠금과 VM 작업 큐를 사용한다.
- stale Image와 URL만 정리한다. Primary expunge나 원본 백업 메타데이터 정리를 호출하지 않으며, 삭제 응답 실패 시 Image 참조를 보존한다.
- 22·31·32 관리 서버와 Agent 9대에 반영했다. 활성 JAR 48개 해시, Mold active·HTTP 200, 관리 서버별 정적 파일 931개 해시 및 설정/WEB-INF 보존을 확인했다.
- 정리 수정본의 첫 배포에서 22.3 SSH handshake가 일시 종료돼 배포를 롤백했다. 22.3의 실제 미변경과 QEMU 프로세스를 확인한 후 재시도로 세 클러스터 배포를 완료했다.

## 완료한 검증

- WSL ext4에서 변경 Maven 모듈 5개만 빌드. Java 11개 스위트 205개, UI 1개 스위트 20개 검사와 UI 전체 lint/production 빌드 통과. 전체 Cloud/Release 빌드·CI는 실행하지 않았다.
- 실제 UI 다운로드 제출 및 Job 성공 7개 조합을 확인했다. 22 DATA의 완료 후 재요청은 동일 URL/파일을 재사용했다.
- UI에 표시된 링크의 HTTP 본문을 실제 파일로 저장했다. 모든 파일은 독립 QCOW2이며, 내려받은 파일과 Secondary의 SHA256이 일치하고 `qemu-img compare` 전체 비교가 통과했다. DATA 3개에서 기록한 두 위치의 패턴도 일치했다.
- 성공 경로 실환경 검사는 22의 `5753f797450`, 31의 `9e691be1d28`에서 수행했다. 후속 정리 수정 `7eefbf9324f`의 빌드·배포와 집중 검사는 완료했으며, 최종 수정본의 추가 UI 회귀는 아래 미완료 범위다.

| 클러스터 | 볼륨 | 원본 정책/형식 | 논리 크기 | 파일 바이트 | UI Job / HTTP / 전체 비교 |
|---|---|---|---:|---:|---|
| 22 | qa1513-22-off-raw-data | off / raw | 1073741824 | 524288 | PASS |
| 22 | ROOT-3 | off / raw | 107374182400 | 2454978560 | PASS |
| 31 | ROOT-795 | falloc / qcow2 | 107374182400 | 2744844288 | PASS |
| 31 | ROOT-794 | metadata / qcow2 | 107374182400 | 2744844288 | PASS |
| 31 | qa1513-31-full-raw-data | full / raw | 1073741824 | 524288 | PASS |
| 31 | qa1513-31-full-qcow2-data | full / qcow2 | 1073741824 | 524288 | PASS |
| 31 | ROOT-796 | thin / qcow2 | 107374182400 | 2744844288 | PASS |

- 별도 일반 계정의 타 계정 ROOT 추출 요청은 HTTP 531 권한 오류로 거절됐다. 해당 임시 계정은 정상 삭제했다.
- 원본 DB의 path/format/size/pool/state/장치/VM 연결과 allocation·clone 상세는 22·31·32 모두 기준값과 동일하다.
- 22 `rocky98-perftest`는 전원·호스트·ROOT/DATA·정책을 보존했다. QEMU PID `219715`, 시작 tick `191152358`도 동일하다.
- legacy GFS2 Image ref의 size/physical_size는 기존 값 0이어서 실제 파일 바이트와 할당량은 별도로 측정했다. explicit 출력에는 실제 파일 크기가 기록된다.

## 남은 검증과 정리

**17개 계획 조합 중 7개 검증, 10개 UI 조합 미검증.** 다음 항목이 남아 있으므로 #1513을 닫지 않았다.

- 22 Full/legacy DATA, 31 detached off/raw·legacy DATA·소형 falloc ROOT, 32 legacy ROOT·off/Full/legacy DATA·소형 Full ROOT의 실제 UI 실행.
- Windows 브라우저 안전 검사에서 현재 URL을 식별하지 못해 이번 턴의 컴퓨터 조작이 중지됐다. IAB와 Chrome의 다운로드 이벤트는 완료를 반환하지 않았다. 브라우저의 OS 파일 저장 완료는 확인하지 못했으며, HTTP 파일 확보와 동일하게 표현하지 않는다.
- 실환경의 실행 중/동시 요청, 오류 후 재시도, 작업 중단, URL 만료 및 stale 캐시 재생성과 물리 파일·URL 정리를 확인해야 한다. 해당 상태/실패/시간 제한/중단/삭제 실패 검사는 집중 테스트에서 수행했다.
- 검증용 VM 7대는 정지 상태로 보존했다. 신규 시험 볼륨 17개·패턴 템플릿 2개·오퍼링 및 Secondary 출력/URL의 최종 정리는 미완료다. 운영 자원을 삭제하지 않았다.
- 다운로드 이미지와 활성 URL이 포함된 화면은 비공개 작업 경로에 보존하며, 활성 토큰과 디스크 파일을 저장소에 게시하지 않는다.

[설계](../../design/issue-1513-volume-export.ko.md) · [검증 근거](evidence-progress.json)
