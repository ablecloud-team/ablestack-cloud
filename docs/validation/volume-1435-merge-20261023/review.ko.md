# PR #1435 Europa 최종 검토

2026-10-23 / Epic #1422 / `dhslove/epic-1422-volume-offerings`.

검토 판정: 발견한 Java 11 호환성 문제를 수정했고, 기존 통합 검증 범위에서 추가 병합 차단 결함을 확인하지 못했다. 사용자가 승인한 Europa 병합·Epic 종료·참조 동기화를 진행한다.

## 검토 범위와 발견 사항

- 실제 누적 diff의 할당 계약·생성/복사 어댑터·실패 후 정리·불변 증거·수명주기 제한·권한 검사와 공용 UI의 라우트/요청 경계, 대화상자, 오퍼링 입력을 확인했다. #1434의 생성 완료/연결 대상·장치 ID·RBD 형식·템플릿 실패 전파·QoS 직렬화 수정과 [통합 검증](../volume-1434-20261023/report.ko.md)을 대조했다.
- GFS2의 임시 파일 정리와 실패 증거 보존에서 Java 16부터 제공되는 `Stream.toList()` 호출 2곳을 발견했다. 프로젝트의 Java 11 기준에 맞춰 `collect(Collectors.toList())`로 바꿨다. 할당 정책·재시도 횟수·증거 보존 범위는 동일하다.
- 수정 커밋: `218ca73875a788d44d0e1f603e255a23819b968f`.
- RBD는 RAW off/full 생성과 실체화된 전체 복사만 지원 판정을 유지한다. falloc·QCOW2·COW clone·Full 확장·영구 예약을 지원한다고 해석하지 않는다. #1437의 새 이미지 최대 2회 재시도 조치는 완료 상태이며 커널 수정은 Epic 종료 조건에 넣지 않는다.
- 최종 검토 당시 upstream Europa는 `929cbff799d95e6fa1266f5d440e016e3d23b33d`였다. 이전 기준 이후 변경된 80개 파일과 본 PR의 938개 파일은 경로 중복이 0이며, `git merge-tree --write-tree`가 충돌 없이 성공했다. 병합 직전 원격 HEAD를 다시 확인한다.
- 변경 파일에서 알려진 시험 암호·GitHub 토큰·개인 키·실제 API 자격 증명 패턴을 점검했고 일치 항목은 0이었다. 실행 코드의 `git diff --check`도 통과했다. 과거 UI 접근성 원문/검증 문서의 공백·CRLF는 실행 코드 결함으로 세지 않았다.

## 추가 검사

WSL ext4 `/home/ablecloud/work/dhslove/cloud-epic-1422`에서 변경 KVM 모듈만 빌드했다.

| 검사 | 결과 |
|---|---|
| 이전 두 소스 Java 11 컴파일 | `javac --release 11`에서 `toList()` 미지원 오류 2개 재현 |
| 수정 KVM 모듈 | 실제 OpenJDK 11.0.25 / Maven 3.6.3, clean package 성공 |
| GFS2 할당 | 44개 성공 |
| GFS2 명령 실행기·실패 증거 | 4개 + 4개 성공 |
| RBD 할당 | 30개 성공 |
| 합계 | 82개, 실패/오류/건너뜀 0 |
| Checkstyle | 모듈 0 위반 |

첫 빌드는 기본 JDK 17에서 82개가 통과했다. 별도 JDK 11 검사에서 `--release` 옵션을 Maven에 추가한 시도는 기존 `--add-exports` 설정과 충돌해 실패했다. 프로젝트의 원래 source/target 11 설정으로 실제 JDK 11을 사용한 최종 clean package는 성공했다. 앞선 실패를 최종 성공으로 세지 않았다.

기존 #1434의 UI 321개·server 96개·스크립트 12개와 31/32 실물 생성·부팅·데이터 보존 검증은 해당 보고서의 범위로 유지한다. 이번 추가 수정은 Java 11 호환성에 대한 모듈 빌드/회귀이며 클러스터 재배포나 전체 Cloud/Release CI 성공을 주장하지 않는다. 전체 빌드·Release workflow는 요청하지 않아 시작하지 않는다. 자동 전체 빌드를 피하도록 최종 문서 및 병합 커밋에 `[skip ci]`를 사용한다.

## 종료 및 보존 절차

- Epic 하위 17개 이슈가 모두 Closed/completed임을 조회했다. 정확히 검토한 최종 PR HEAD를 `--match-head-commit`으로 고정해 병합한 뒤 Epic을 completed로 닫는다.
- Windows 35개·WSL 57개 기존 작업 트리의 HEAD·변경 목록·diff와 변경/미추적 파일 내용 해시를 사전 보존했다. Windows FT 1389개 상태 항목과 WSL FT를 포함한 기존 변경 작업 트리는 checkout/reset 없이 그대로 유지한다.
- 깨끗한 Windows/WSL Europa 작업 트리를 fast-forward하고 origin/upstream Europa와 병합 SHA 일치를 확인한다. 검토 브랜치와 다른 작업 브랜치는 보존한다.
- 병합 SHA·실제 부모/트리 비교·최종 원격 상태·Epic 종료 및 보존 결과는 PR/Epic의 종료 기록에 남긴다. 병합 전 시점의 과거 검증 문서를 사후 완료 상태로 덮어쓰지 않는다.
