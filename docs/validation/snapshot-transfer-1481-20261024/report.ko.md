# #1481 스냅샷 보관·복사 구현 및 검증 결과

대상: Epic #1473 / 이슈 #1481 / PR #1492
브랜치: `dhslove/epic-1473-storage-snapshots`
환경: 31 GFS2, 32 RBD / 검증일: 2026-10-24

## 변경 결과

스냅샷 상세 작업에 보관 진입점을 추가했다. 복사는 읽기 전용 `listSnapshotCopyTargets` API가 확인한 원본·대상·지원 사유를 사용한다. 조회 실패와 실제 빈 대상을 구분하고, 상태가 바뀐 선택은 제출 전에 차단한다. StorPool 복제 및 Primary 풀 선택은 UI와 실제 복사 요청에 없다.

보관·복사는 공통 MoldDialog md와 기존 알림·공통 작업 상세를 사용한다. 상세 페이지의 중복 정보 표를 추가하지 않았다. 다크 모드 색상은 테마 변수를 사용하며, 스크롤 영역과 하단 버튼을 분리해 좁은 화면에서도 겹침을 방지한다.

실제 전송 시험에서 확인한 기존 부분 실패 성공 판정, 미검증 완료 기록, 0 용량 참조와 가상 용량 기준 사용량 기록도 보완했다. 이미지 검증 후에만 완료 Zone·이벤트·물리 사용량을 기록한다. 검증 실패 참조는 재시도할 수 있고, 완료 사본은 재요청해도 중복 생성·과금하지 않는다. 기존 Epic 소스의 Checkstyle 오류 5건은 import만 정리했다.

## 핵심 검증

| 항목 | 31 | 32 | 근거 |
|---|---|---|---|
| 보관 진입·영향 안내·확인·비동기 완료 | PASS | PASS | 기존 알림, 보관 대화상자, 작업 완료 응답 |
| 이미 보관된 Secondary / 기존 Secondary 재사용 | 기존 파일 해시 유지 | 파일 해시 유지 후 RBD Primary 스냅샷 제거 | archive |
| 다중 참조 보관 제한 | 확인 비활성 | API 진단 확인 | conditions, 보관 미지원 UI |
| 비활성 Zone·읽기 전용·저장소 미구성·Edge 제외 | PASS | PASS | conditions, stalePlan |
| 조회 실패 / 권한 거부 / 진짜 빈 대상 구분 | 제어된 HTTP 503, 실제 빈 대상 | 제어된 HTTP 403, 실제 빈 대상 | browser JSON 및 화면 |
| 선택 후 상태 변경 차단 | UI 복사 POST 0건 | API의 이전 해시 거부 | stale UI, stalePlan |
| A 성공/B 쓰기 실패 | 작업 상태 2 | 작업 상태 2 | finalPartial, 네트워크 응답 |
| 실패 B의 성공 Zone·사용량 이벤트 미생성 | PASS | PASS | finalPartial |
| B 복구 후 실제 UI 재시도 | A Zone을 원본으로 고정, B만 전송 | 원래 Zone에서 B만 전송 | finalComplete, 요청 인자 |
| UI 중복 클릭 / 완료 사본 API 재요청 | POST 1건 / 참조·이벤트·사용량 유지 | 동일 | finalReplay, browser |
| 소유 범위와 저장소 정보 가시성 | 다른 계정 접근 거부, 소유 User 조회 가능·저장소 상세 비노출 | 동일 | conditions, ownerPlan |
| 이미지·용량·복구 데이터 | 원본·A·B 일치 | 원본·A·B 일치 | physical |
| 시험 자원 정리와 운영 항목 보존 | PASS | PASS | finalState, preservation, quota |

최종 전용 계정 시험 사본:

- 31: `ee7f1db6-856b-4006-942a-64b33535a2b0`
- 32: `7f890f16-ad98-4aef-a247-40da7075664c`

두 환경 모두 NFS 사본 3개의 가상 용량은 각각 1,073,741,824바이트이며 파일 크기는 각각 2,490,368바이트다. 첫/마지막 1 MiB 데이터 패턴과 전체 파일 SHA-256이 일치했다. Secondary 사용량은 원본 2,490,368 → A 완료 4,980,736 → B 재시도 완료 7,471,104바이트로 일치했고, 완료 사본 재요청 후에도 동일했다. SNAPSHOT.COPY 이벤트와 완료 Zone도 A/B 각 1개였다.

주요 증거: [31 검증](31-validation.json), [32 검증](32-validation.json), [설계](design.ko.md).

## UI 표준과 안전 기능

공통 MoldDialog md는 가상머신과 같은 720px 규격이다. 보관·복사의 취소/실행 버튼, 본문 간격, 스크롤, 작업 알림 경로를 공통 구조로 맞췄다. 보관은 1280×720, 복사는 1280×720 및 459×488에서 확인했다. 좁은 화면의 하단 버튼은 화면 안에 유지됐고, 본문은 스크롤되어 컴포넌트가 겹치지 않았다.

[복사 다크 모드](browser/31-copy-targets-dark.jpg), [라이트 모드](browser/32-copy-targets-light.jpg), [좁은 화면 다크](browser/32-copy-compact-dark.jpg), [보관 31](browser/31-archive-dark.jpg), [보관 32](browser/32-archive-dark.jpg), [실패 작업 상세](browser/31-final-partial-job-dark.jpg), [완료 작업 상세](browser/32-final-complete-job-light.jpg).

기존 lease·customjobid·응답 유실 보호와 화면/계정/프로젝트 변경 방어는 집중 테스트에 포함했다. 실제 UI에서는 중복 클릭 시 단일 제출, 고정된 스냅샷/원본 Zone/대상 Zone, 제출 전 상태 변경 차단을 확인했다. HTTP 503/403은 조회 실패 처리를 확인하기 위해 브라우저 XHR에 한정해 주입했으며 실제 계정 ACL 시험과 구분한다.

## 빌드와 배포

- WSL ext4의 API 모듈 빌드와 server 모듈 Checkstyle·컴파일·집중 Java 테스트 67개 통과.
- UI 집중 테스트 6개 스위트/93개, 변경 파일 린트, 프로덕션 UI 빌드 통과.
- 두 서버에서 최종 클래스 8개와 정적 파일 921개의 해시 일치 확인.
- WEB-INF·서버 파일·설정 보존, Mold active, /client/ HTTP 200, 기존 에이전트 5개 Up 확인.
- Windows/WSL의 보호된 FT 작업 트리 HEAD·status·diff 해시 동일.

전체 Cloud 빌드나 GitHub Actions 실행은 요청되지 않아 시작하지 않았다. 위 결과는 변경 모듈·집중 회귀·실제 클러스터 검증의 범위다. [로그 목록](logs.json), [소스 해시](source-sha256.json), [원본 이미지 목록](browser-images.json).

## fixture 구성과 정리

새 Zone·NFS 저장소를 제품 API로 만들고 기존 SSVM 안에 별도 TLS AgentShell 프로세스를 연결했다. 전용 마운트를 nosharecache로 분리했으며, 새 image_store의 parent에 실제 마운트를 등록했다. 복사 참조나 성공 결과는 수동 생성하지 않고 제품 HTTP 전송으로 만들었다.

초기 시험에서 NFS 마운트 캐시 공유와 마운트 등록 누락을 발견해 복구·분리한 뒤 새 전용 계정 사본으로 최종 시험을 반복했다. 운영 계정의 주기 사용량 재계산과 구분하기 위해 최종 사용량 검증을 전용 계정에서 수행했다.

시험용 볼륨·스냅샷·사본·Zone·저장소·네트워크·Pod·계정·프로젝트·에이전트와 전용 NFS 경로를 정리했다. 활성 API/DB 참조와 Primary/Secondary 파일·이미지 잔여는 0이다. 시험 스냅샷 UUID에 해당하는 알림만 보관했고 임시 사용량은 정식 재계산 API로 정리했다. 기존 운영 자원의 이름·상태·소유·크기·형식 등 비교 항목은 동일했다.

기존 SSVM PID·GUID·네트워크·인증서는 유지됐다. agent.properties의 바이트 해시는 재연결 후 변경됐다. 현행 GUID·관리 IP·Zone·Pod와 인증서 해시를 원래 프로세스/기준값과 별도 대조했으며, 전체 설정 파일의 바이트까지 동일하다고 판정하지 않았다.

이 구성은 별도 에이전트와 같은 NAS의 독립 경로를 사용한 기능 검증이다. 신규 SSVM VM 전체 프로비저닝, 독립 물리 NAS 간 성능, StorPool 실행은 이번 검증 범위 밖이다.

## 다음 작업

다음은 **#1482 전체 목록 서버 정렬과 안정적인 페이지 순서**다. #1483과 정렬·검색·페이지 계약을 조율한다. 나머지 대화상자의 최종 정리와 #1489 통합 회귀는 후속 기능 구현을 마친 뒤 진행한다. Epic #1473과 PR #1492는 계속 진행 중이다.
