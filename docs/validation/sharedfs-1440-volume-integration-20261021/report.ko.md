# SharedFS #1440 — 볼륨 Epic 변경 통합 및 종료 검토

검토일: 2026-10-21 KST. 상위 Epic #1438, SharedFS 통합 PR #1470.

## 통합 결과

공통 볼륨 기능을 별도로 재구현하지 않고, 볼륨 Epic #1422 / PR #1435의 소스 이력을 SharedFS 작업 브랜치에 merge한다.

- 기준 Europa: 0e6b5093d51079f0cfd9bfc88b6e37a795be55a3. 로컬 기준 브랜치와 upstream divergence 0 0 확인 후 진행.
- 수용한 볼륨 Epic head: ba4cfd1d52979f487959786f6b40b364ad11b359.
- 대상 브랜치: epic/1438-sharedfs.
- 공개된 SharedFS head 5a819eab8597ab843de55dd8ab7402d172cfb0dc도 조상으로 보존한다. 기준 브랜치 갱신에 따른 로컬 rebase 이력을 정리할 때 공개된 이력을 버리거나 force-push하지 않는다.
- 볼륨 Epic 전체 변경을 수용한다. #1423 경로 보존·명시적 변경 보호/확장 오류 전달, #1428 부분 요청·삭제 보호·경로 편집, #1427 수명주기 보호와 나머지 볼륨·오퍼링 변경을 포함한다. SharedFS만의 경로 setter나 별도 편집 폼을 덧붙이지 않는다.
- 충돌은 en.json/ko_KR.json의 파일 끝 번역 추가 두 곳이다. locale별 SharedFS 읽기 문구 7개와 볼륨 Epic 신규 키 126개를 모두 보존했고 중복 키는 없다.
- 볼륨 Epic 대비 제품 소스 차이는 기존 SharedFS 변경뿐이다. locale 외 기존 SharedFS 파일은 통합 전 바이트와 동일하다. upstream의 추가 CNI 테스트·문서도 유지했다.
- PR #1435 자체의 Draft/미병합 상태는 바꾸지 않았다. 이번 작업은 SharedFS 브랜치에 수용한 것이며 upstream 병합·볼륨 Epic 전체 종료가 아니다.

## 검증

- 백엔드: server, plugins/storage/volume/ablestack, plugins/storage/volume/default 및 의존성 **37개 모듈 clean test 성공**.
- 선택한 백엔드 7개 suite **231 tests / failures 0 / errors 0 / skipped 0**. 부분 수정 15개, 기존 VolumeApiServiceImpl 159개, 할당 정책/수명주기/변경 보호 39개, 두 driver 응답 처리 18개다.
- UI: 볼륨 편집·크기 변경·작업 모달·오퍼링·정책과 SharedFS 조회/생성/locale **11 suites / 197 tests 모두 통과**.
- UI production npm run build 성공. pre/postbuild가 바꾼 소스 public/config.json은 빌드 전 바이트로 복원해 제품 설정을 변경하지 않았다. buildVersion은 기존 값이고 배포용 버전을 새로 발행하지 않았다.
- 이번 Maven 명령은 checkstyle.skip=true, rat.skip=true를 사용했다. Checkstyle/RAT 또는 전체 CI 통과로 표현하지 않는다.

~~~bash
mvn -B -pl server,plugins/storage/volume/ablestack,plugins/storage/volume/default -am clean test \
  -Dcheckstyle.skip=true -Drat.skip=true \
  -Dtest=VolumeApiServiceImplUpdateTest,VolumeApiServiceImplTest,VolumeAllocationMutationTest,VolumeAllocationPolicyTest,VolumeAllocationLifecycleTest,CloudStackPrimaryDataStoreResizeResponseTest,AblestackPrimaryDataStoreResizeResponseTest \
  -Dsurefire.failIfNoSpecifiedTests=false
~~~

UI 단위 시험은 summary.json의 11개 경로를 --runTestsByPath --runInBand로 지정했다. UI 운영 빌드는 NODE_OPTIONS에 --openssl-legacy-provider --max-old-space-size=4096을 지정했다.

관련 시험은 이 통합 소스에서 새로 실행한 결과다. 기존 31·32 실환경 완료 기록을 이번 13번 인수 결과로 재사용하지 않는다. 전체 Cloud/Release CI, 전체 UI 시험과 모든 KVM 정책 실환경 시험을 수행한 것은 아니다.

제품 경로의 git diff --check는 통과했다. 수용한 기존 접근성 화면 기록 파일의 trailing whitespace 26건은 원본 증거를 보존하기 위해 변경하지 않았다.

## #1440 종료 판정

**OPEN 유지: 공통 구현 중복은 해소했으나 원래 인수 기준의 13번 실제 UI·영속 상태·실물 디스크·SharedFS 바인딩/데이터 보존 검증을 완료하지 않았다.**

이전 독립 수정안은 채택하지 않는다. 현재 실행 계약은 #1423/#1427/#1428의 경로 보존·권한·상태·할당 정책 보호·부분 요청 계약이다. 초기 목업은 과거의 미구현 대안으로만 남긴다.

현재 범위에서 완료한 것은 소스 통합, 관련 컴파일/회귀 시험, UI 운영 빌드다. **13번 서버에 이 통합본을 배포하지 않았고 VM/볼륨/파일/네트워크/MAC을 변경하지 않았다.** 검증용 빌드 산출물을 최종 배포 후보로 간주하지 않는다.

남은 인수:

1. 이 통합 commit 기준으로 대상 모듈을 패키징하고 13번의 실제 런타임 구성을 대조해 필요한 모듈만 정합하게 배포한다. 백엔드를 UI보다 먼저 적용한다. 전체 볼륨 Epic을 수용했으므로 과거 단일 메서드 교체 설계를 그대로 배포 절차로 사용하지 않는다.
2. UI에서 새 SPARSE SharedFS와 필요한 시험 클라이언트를 생성한다. 이름·삭제 보호 변경의 실제 UI 요청에서 path 미제출, 작업 성공, API/DB/디스크 식별 보존을 확인한다.
3. SharedFS의 볼륨 선택·연결·조회와 NFS 재접속·데이터 checksum, #1439 조회 회귀를 실제 UI/클라이언트 콘솔로 확인한다. 명시적 경로 보호는 안전한 별도 시험 볼륨에서 확인한다.

사용자는 이번에 브랜치 반영과 종료 검토를 요청했다. 이 보고서에서 실환경 시험을 완료했다고 확대하거나 이슈를 닫지 않는다. 최종 UI 정리 #1468의 HOLD도 유지한다.

## 근거

- [볼륨 경로 보존 #1423](https://github.com/ablecloud-team/ablestack-cloud/issues/1423)
- [부분 요청·삭제 보호·경로 편집 #1428](https://github.com/ablecloud-team/ablestack-cloud/issues/1428)
- [수명주기 보호 #1427](https://github.com/ablecloud-team/ablestack-cloud/issues/1427)
- [수용 원본 PR #1435](https://github.com/ablecloud-team/ablestack-cloud/pull/1435)
- 로컬 실행 로그: /root/work/epic1438-preparation/20261021/issue1440-design/related-volume/integration-*.log
- 기계 판독 근거: summary.json.
