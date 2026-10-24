# #1479 UI 표준 보완 및 31·32 검증 결과

## 결과

**PASS.** Epic 브랜치 `dhslove/epic-1473-storage-snapshots`에서 사용자 승인안인 **기존 알림 통합 + 공통 작업 상세 대화상자**를 구현했다. 상세 본문 위 작업 패널을 제거해 가상머신 상세의 정보 카드·탭 배치를 유지한다. 기존 #1479의 대상 고정·현재 상태 재검증·UUID 기반 재제출 보호는 유지한다. 이 보고서는 이전 #1479 보고서의 작업 패널 배치와 해당 UI 완료 판단을 보완한다.

- [설계](design.ko.md)
- [자동 증거 대조 결과](evidence/verification.json), [파일 해시](evidence/files-sha256.json)
- [31 기본 상세](evidence/ui/31-snapshot-standard-final.png), [32 기본 상세](evidence/ui/32-snapshot-standard-final.png)
- [31 작업 상세](evidence/ui/31-job-complete-final.png), [32 작업 상세/F5](evidence/ui/32-f5-result-link-restored-final.png)
- [31 모바일](evidence/ui/31-job-mobile-final.png), [32 모바일](evidence/ui/32-job-mobile-final.png)

## 구현

`AutogenView`에서 `StorageSnapshotOperations`를 제거하고 기존 `HeaderNotice`에 현재 보안 범위의 registry 작업을 병합한다. 작업 이름·상태·날짜를 현지화하고, 선택한 작업 하나를 `MoldDialog(size=md)`로 연다. UUID와 수동 연결/차단 해제는 접힌 고급 영역에 둔다. 생성 리소스와 기존 이벤트 경로로 연결한다. 헤더의 기존 action 클래스도 단일 루트로 전달해 가상머신 상세 헤더 높이/간격을 유지한다.

알림 지우기는 종결 항목만 숨긴다. checking/submitting/accepted/unknown 작업의 자원 잠금과 F5 복원 정보는 유지한다. 숨긴 항목이 다시 미해결 상태가 되어도 표시한다. 스냅샷 작업은 일반 `$pollJob`에 중복 등록하지 않고 기존 tracker를 사용한다. 조회 오류와 대화상자 닫기는 차단을 해제하지 않는다.

F5 후 결과 본문이 없는 완료 작업은 **동일 job**을 읽어 링크를 복원한다. 작업 대상은 제출 시점의 UUID/이름을 유지하며 현재 페이지의 VM/볼륨으로 바꾸지 않는다. 종결 시 영향을 받는 현재 스냅샷/볼륨만 `irefresh` 하고 활성 탭을 보존한다. 강제 삭제는 `dbcleanupcomplete=true`를 확인한 경우에만 DB 정리 완료를 표시하고 물리 잔여 가능성을 구분한다.

가상머신의 공통 `ResourceLayout`, `InfoCard`, `ResourceView`, `MoldDialog` 네 파일은 수정하지 않았다. [기준 파일 해시](evidence/vm-standard-components.json)를 대조했다.

## 검증

| 항목 | 31 GFS2 / 밝은 테마 | 32 RBD / 어두운 테마 | 근거 |
|---|---|---|---|
| 기본 상세·좌측 정보 카드·우측 탭·헤더, 본문 작업 패널 없음 | PASS | PASS | `*-vm-standard-final`, `*-snapshot-standard-final` |
| 미확인 작업을 기존 알림에서 열기, 원래 대상 유지, 고급 정보 기본 접힘 | PASS | PASS | `*-job-unknown-final`, `*-job-unknown-identification` |
| 조회 실패/닫기/ESC 후 재제출 잠금과 초점 복귀 | PASS | PASS | `*-escape-focus`, `*-final-f5-clear-still-blocked` |
| 알림 지우기·F5·프로젝트 전환 후 미해결 작업 유지/다른 범위 비노출 | PASS | PASS | `*-f5-notification-retained`, `*-project-job-hidden`, `*-final-f5-clear-still-blocked` |
| 390 / 768 / 1280px 공통 작업 상세, 문서/본문 가로 넘침 없음 | PASS | PASS | `*-job-mobile-final`, `*-job-tablet-final`, `*-job-unknown-final`의 PNG/DOM 측정 |
| 제출 직전 Stopped→Running 변동 시 복원 POST 차단, 현재 사유 표시 | PASS | PASS | `*-state-drift-blocked`, `*-running-reason-blocked`; UI revert POST 0 |
| Running 원본 복원 API 거절, Stopped 원본 복원/데이터 유지 | PASS | PASS | `*-running-api-rejection.json`, `*-checks-stopped.json`, `*-pattern-verify-source.json` |
| 원본 볼륨 없는 스냅샷의 새 볼륨 생성, 응답 유실/기존 UUID 조회 | PASS | PASS | `*-orphan-context.json`, `*-submit-lost`, `*-ui-dedupe-db.json` |
| 브라우저 POST 2회 → UUID당 DB job 1개 / 볼륨 1개 | PASS | PASS | job `74c2b543-b7a9-4e48-8f4f-a62f75bdfe6f` / `055c9b36-c5d4-45ef-bcc0-f014c31486ec` |
| 생성 볼륨의 1MiB `0x5a` 데이터 일치 | PASS | PASS | `*-pattern-verify-lost-ui-volume.json` |
| 완료 조회 후 Zone 탭 유지, 올바른 생성 리소스 이동 | PASS | PASS | `*-zone-preserved-final`, `*-result-volume-final` |
| 볼륨 화면 F5 후 같은 job으로 결과 링크 복원, 원래 스냅샷 유지 | PASS | PASS | `*-f5-result-link-restored-final` |
| 강제 삭제 결과를 알림 상세에서 구분, 삭제된 리소스 링크 없음 | PASS | PASS | `*-job-force-complete-final`, `*-force-verified.json` |
| 기존 최근 이벤트/경보 연결, 완료 알림 제거 | PASS | PASS | `activity-link-final.json`, `*-completed-notices-cleared-final` |

강제 삭제 시험의 DB 상태/참조는 `Destroyed`로 종결됐고 NFS 사본이 사라졌다. 32의 RBD 스냅샷도 제거됐으며 원본 볼륨의 데이터는 유지됐다. **DB-only/물리 잔여 안내 및 DB 완료 필드가 누락된 응답의 미완료 처리는 새 단위 테스트로 확인**했다. 이번 강제 삭제 실화면의 서버 결과는 두 클러스터 모두 DB·물리 정리 완료였다.

최초 배포 후 대상/유실/프로젝트/상태 변동을 검증했고, 헤더 정렬·안내 문구를 포함한 최종 빌드를 다시 배포해 F5, 미해결 잠금, 알림 지우기, 결과 조회/링크, 탭, 공통 상세, 반응형, 기존 활동 링크를 재검증했다. 초기·최종 배포의 manifest/해시는 구분해 보존했다. 최종 빌드의 VM 상세 탭 위치/폭은 기준 화면과 같다.

프로젝트 전환/페이지 초기화가 완료된 뒤 다음 UI 동작을 수행했다. 초기화 도중의 자동 경로 전환이나 보안 범위 변경으로 닫힌 알림을 stale 컨트롤로 조작한 시도는 성공 증거로 사용하지 않았다.

## 소스 검사·배포

- 최신 소스 **UI 144 tests / 11 suites PASS**. 기존 129개 회귀와 새 알림/작업 상세 15개를 함께 실행했다. [집중 테스트 결과](evidence/focused-tests.log)
- 전체 UI `lint --no-fix` PASS 후 최종 변경 파일 no-fix lint 재검사 PASS. [전체 린트](evidence/full-ui-lint.log), [최종 변경 파일](evidence/final-files-lint.log)
- WSL ext4에서 production UI build PASS. [빌드](evidence/production-build.log)
- 각 서버 정적 파일 920개를 manifest 해시로 대조했다. WEB-INF/META-INF를 교체하지 않았고 설정과 JAR를 보존했다. `/client/` HTTP 200, Mold active, FTCTL 기존 마커 및 새 작업 상세 마커를 확인했다. 각 Routing 3 + SSVM + Console Agent 5개가 Up이며 정상 조회 8종이 통과했다.
- 최종 백엔드 JAR SHA256: 31 `865a42e2b4de55c4c022dbf081f0e29703a684f895fab457b81d4ecd3b32041d`, 32 `18069c4dd03328b05ae5effb30afda906a9673c518ebfb84529b136c2d2a2546`. 이전 #1479 배포와 동일하다. 이번에는 Java 소스/JAR를 수정·재빌드하지 않았다. 이전 Java 93개 결과는 [기존 보고서](../snapshot-actions-1479-20261023/report.ko.md)에 별도 보존한다.
- 최종 배포 백업: 두 서버 `/root/snapshot-actions-1479-ui-notices-20261023-final`. [31 배포 증거](evidence/31-ui-deploy-final.json), [32 배포 증거](evidence/32-ui-deploy-final.json)

Jest는 기존 전역 비동기 작업이 있는 집중 검사 환경에서 `--forceExit --collectCoverage=false`로 실행했다. 반복 Vue 아이콘 등록 경고는 요약 로그에서 생략했고 원본 로그의 SHA256/로컬 경로를 보존했다. 오래된 Browserslist 및 번들 크기 경고는 빌드 로그에 남겼다. 전체 Cloud 빌드·Actions dispatch·qemu/FTCTL 빌드는 실행하지 않았다. 등록 CI 및 PR 병합과 구분한다.

## 정리·보존

새 전용 prefix `SNAP1479UI-31-*` / `SNAP1479UI-32-*`만 사용했다. 총 VM 2, 볼륨 10(ROOT 2 포함), 스냅샷 6, 프로젝트 2를 사용하고 정리했다. 원본 누락 시험에서 삭제한 볼륨과 강제 삭제한 스냅샷도 allowlist에 포함했다.

최종 **활성 API/DB 자원·미종결 참조·금지된 추가 생성·알려진 NFS/GFS2/RBD 실물·호스트 VM domain은 0**이다. 감사/종결 이력의 DB 행은 활성 잔여와 구분한다. UI에서 prefix로 조회한 스냅샷·볼륨 목록도 0건이다. 이번 fixture는 API를 통해 정리했으며 수동 파일 삭제/DB 보정은 하지 않았다.

운영 인벤토리 투영과 스냅샷/세컨더리 자원 카운터를 대조했다. Windows 및 WSL의 보호된 FTCTL worktree는 HEAD, tracked/untracked 상태, diff가 모두 동일하다. 검증용 브라우저 탭과 프록시 프로세스도 종료했다. 원시 운영 인벤토리·인증 정보·보호된 dirty diff는 이 문서에 포함하지 않았다.

## 남은 범위

#1479의 이번 UI 회귀와 안전 기능은 완료 기준을 충족한다. PR #1492는 검토·병합 대기이고 Epic #1473은 계속 열어 둔다. 기존 #1489의 절대 install_path 삭제 잔여·계정 삭제 usage 중복·32 hot-attach 진단은 이번 UI 변경으로 해결했다고 표시하지 않는다.

다음 P1은 **#1474 RBD 스냅샷 기반 템플릿의 이미지 형식·가상 용량 정합성**이다. 실제 다중 Zone 복사, NetBackup 엔진 복원, 새 템플릿 ROOT 부팅과 기능별 나머지 대화상자 전체 표준화(#1488)는 해당 후속 범위다. 현재 작업 패널 제거와 공통 작업 상세 개선을 그 이슈로 미루지 않았다.
