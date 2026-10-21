# #1424 볼륨 할당 정책 공통 계약 구현·검증

- Epic: [#1422](https://github.com/ablecloud-team/ablestack-cloud/issues/1422)
- 구현 이슈: [#1424 / VOL-02](https://github.com/ablecloud-team/ablestack-cloud/issues/1424)
- 누적 Draft PR: [#1435](https://github.com/ablecloud-team/ablestack-cloud/pull/1435)
- 브랜치: `dhslove/epic-1422-volume-offerings`
- 검증한 소스: `a320e4a6b790bd99e7d8e36d57b0cbc390015864`
- 설계: [할당 정책 계약 v1](../../design/mold-volume-1424-allocation-contract.ko.md)
- 기계 판독 결과: [summary.json](summary.json)

## 결과와 범위

신규 정책 요청을 기존 thin/sparse/fat 열과 분리하고, 오퍼링 상속·Agent capability·적용 증거·관측 사용량의 공통 계약을 구현했다. 관련 검사 434개, 환경별 JAR 기반 고유 관련 검사 397개, 실제 API 행렬 31번 91건·32번 75건을 통과했다. UI에서도 신규 정책 거절과 기존 thin 생성·목록·상세를 확인했다.

이 이슈는 공통 계약 구현이다. **신규 정책의 실제 생성·복사 실행 어댑터는 #1425/#1426의 범위이며 현재 비활성화되어 있다.** 구 Agent에 새 정책을 넘기거나 thin으로 대체하지 않는다. 신규 정책 선택 UI는 #1431, VM 기준 UI 통일은 #1433에서 진행한다. 이번 UI 작업은 기존 화면과 API의 호환 검증이다.

## 구현

1. `createDiskOffering`/`createServiceOffering`에 `allocationpolicy`, `allocationformat`을 추가했다. 두 필드는 함께 지정하고, 기존 `provisioningtype`과 동시 지정하지 않는다. raw metadata 등 잘못된 값은 명확한 파라미터 오류로 거절한다.
2. `VolumeAllocationPolicy`로 요청 정규화, 버전 검증, 내부 디스크/연결 디스크 상속과 충돌, 백엔드·형식·작업·방법 검증을 공통화했다. 기존 sparse를 falloc으로 바꾸지 않는다.
3. 기존 details 테이블에 계약 v1을 저장한다. DDL·SQL view 변경·기존 행 백필은 없다. ROOT/DATADISK와 기존 RBD fat 기록을 실제 적용 정책으로 소급 확정하지 않는다.
4. 오퍼링·볼륨 응답의 `allocation`에서 요청, 확정 정책, 실제 적용 방식, 관측 근거를 분리한다. 검증되지 않은 값은 unknown/unverified이며 손상된 증거는 invalid로 표시한다. 성공한 어댑터가 사용할 evidence 생성·검증 계약도 제공한다.
5. 관리자 API `getVolumeAllocationCapabilities`와 Agent Command/Answer v1을 추가했다. Agent 광고가 없는 경우 새 명령을 보내지 않는다. 광고·조회 실패, null/일반 Answer, 잘못된 버전·범위·방법은 지원으로 간주하지 않는다. 실제 Spring XML 등록과 JSON 객체 이름도 검사한다.
6. 신규 정책이 기존 실행기로 진입하지 않게 생성·오퍼링 변경·확장·복제·import 경로를 차단했다. VM의 공통 디스크 검증은 VM/NIC DB 생성 전에 실행한다. 일반 메타데이터 API의 예약 필드 쓰기·삭제·전체 삭제 우회도 차단했다.

## 빌드와 검사

WSL ext4 전용 Epic 작업 트리에서 JDK 17/Maven 3.9.10으로 변경 모듈만 빌드했다. 전체 Cloud 빌드는 실행하지 않았다.

| 항목 | 결과 |
|---|---|
| 변경 Maven 모듈 | api, core, server, engine/orchestration 성공 |
| 최종 관련 검사 | 434 통과, 실패·오류·skip 0 |
| 생산 Spring XML/JSON 직렬화 | 빈 등록·주입·명령 발견·응답 객체/false 값 검사 통과 |
| 배포 JAR 기반 검사 | 각 환경 397개 고유 관련 검사 통과, JVM 검증 사용 |
| Checkstyle 변경 파일 | 위반 0 |
| 모듈 전체 Checkstyle | core/orchestration 통과; API 4건·server 15건 기존 위반 |

Checkstyle 위반 19건의 파일은 기준 커밋 `00b8b80da26`과 동일함을 바이트로 비교했다. 이번 변경과 무관한 기존 CNI/userdata 테스트의 wildcard import 위반이다.

검사 한계도 구분했다. `UserVmManagerImplTest` 전체 257건에서 10건의 실패/오류가 있었고, 변경 전 코드에서도 동일한 10개 이름의 실패가 재현됐다. 생성 소스 mock 누락과 userdata 작업 잠금 mock 관련 기존 실패이며, 해당 파일의 관련 디스크 크기 검사 5건과 신규 생성 전 검사 4건은 통과했다. 배포본의 기존 orchestration 검사에서도 미변경 동작에 대한 불필요한 stub 1건이 원본 JAR에서 재현됐다. 모듈별 검사를 한 JVM에 합친 시도의 전역 설정 간섭은 JVM을 분리해 확인했다. 이를 전체 서버 검사나 전체 CI 성공으로 보고하지 않는다.

## 31·32 배포

서로 다른 이력의 합본 JAR을 기반으로 빌드한 변경 메서드·필드·신규 클래스와 API 등록 XML만 반영했다. 변경할 기존 메서드는 기준 소스와 배포본의 명령을 먼저 비교했다. 서비스 오퍼링 응답의 기존 차이는 원래 명령을 유지하고 할당 응답 호출만 추가했다.

| 항목 | 31 GFS2 | 32 RBD |
|---|---|---|
| 활성 JAR SHA256 | `b6e02c6f9a5ece4fb14975c4f097b3c2193a1b23016876159f9f771f70afd02f` | `2af187c1badc29d0cb218c9152066795673e8e72e8f3aeb7e7eeaf2a49647d84` |
| 비대상 메서드 code 바이트 보존 | 1,290개 | 1,288개 |
| 기존 JAR 항목 보존 | 204,488개 | 204,483개 |
| 최종 배포 직전 백업 | `/root/volume-1424-a320e4a6b79` | 같은 경로 |
| 상태 | mold active, client 200 | mold active, client 200 |

#1423의 클래스·메타데이터를 보존했다. WEB-INF와 정적 UI 해시, 배포 전후 DB 핵심 행과 6개 관련 테이블의 스키마가 일치했다. UI와 호스트 패키지는 배포하지 않았다. 31번의 최초 #1424 배포 전 백업은 `/root/volume-1424-76d9d9684e4`에 추가로 보관했다. 구 코드로 롤백할 때는 신규 정책 오퍼링의 사용 여부를 먼저 확인해야 한다.

## 실제 기능 행렬

| 검증 | 결과 |
|---|---|
| 실제 API | 31번 91/91, 32번 75/75 |
| 구 Agent 지원 조회 | 31번 3호스트 × 2형식 × 6작업 = 36건, 32번 3호스트 × raw × 6작업 = 18건 모두 미지원 명시 |
| 권한 | 전용 일반 User의 관리자 capability API 접근 거절 |
| 오퍼링 요청·상속 | 저장 가능한 요청 7조합 각각 일반 디스크/내부 디스크/연결 디스크 응답 일치 |
| 충돌·잘못된 입력 | 신규/기존 동시 지정, 누락, raw metadata, 잘못된 문자열, 연결 정책 중복·충돌 거절 |
| 신규 데이터·루트 | 데이터 7조합, 루트 내부/연결 14조합 실행 거절; 루트 거절 후 VM 잔여 0 |
| 수명주기 차단 | 오퍼링 변경/확장의 새 정책 유입 거절, 기존 path/size/state/offering 보존 |
| 복제·메타데이터 | 신규 정책 소스 clone, 예약 필드 쓰기·삭제·전체 삭제 거절 |
| 기존 API | thin/sparse/fat의 실제 1GiB 생성, 이름 변경 시 path 보존 |
| DB 계약 | 환경마다 일반 7·내부 7·연결 7 = 21행 조합 확인; 버전·정책·형식·호환 열 일치 |
| 부분 오퍼링 수정 | 표시 이름 변경 후 정책 응답 보존 |
| UI | 새 정책 431 원인 표시·볼륨 미생성, 같은 대화상자의 기존 thin 재시도 성공, 목록→상세 정상 |

생성 전 검증을 늦게 수행하면 Error VM/NIC가 남는 경로를 실제 테스트에서 발견해, VM 생성 전 공통 검사로 보완했다. 최종 소스로 새 전용 자원에서 행렬을 다시 실행했다. 중간 단계의 전용 Error VM 14개는 정상 API로 정리했다. 기존 운영 자원은 수정하지 않았다.

기존 정책의 루트 생성은 ISO를 사용한 **Stopped VM과 16GiB Allocated 루트 레코드**까지 검증했다. 게스트 부팅이나 루트 이미지의 실제 생성·I/O 성공으로 확대 해석하지 않는다.

## 실물과 RBD 제한

각 환경에서 API 3개와 UI 1개, 총 4개의 데이터 이미지가 실제 1GiB임을 확인했다.

| 기존 요청 | GFS2 qcow2 관측 actual-size | RBD 관측 used_size |
|---|---:|---:|
| thin | 208,896 B | 0 B |
| sparse | 348,160 B | 0 B |
| fat | 1,076,203,520 B | 0 B |
| UI thin | 208,896 B | 0 B |

이 관측값을 적용 정책이나 영구 예약 보장으로 바꾸지 않았다. 특히 RBD의 기존 fat도 Full이라고 응답하지 않는다. 신규 RBD는 raw Thin 및 검증된 Full create/copy만 계약에 포함하고, falloc/lazy thick·raw metadata·미검증 Full resize/clone 등은 거절한다. 이번 작업에서 신규 Full 실제 생성·복사는 수행하지 않았다. #1426에서 기존 실험으로 확인한 thick create와 precreated thick 대상의 `convert -n -S 0` 경로를 제품에 연결하고 재검증해야 한다.

## 정리와 보존

- 두 환경 모두 API/DB의 활성 테스트 볼륨·루트 볼륨·VM·NIC·계정·디스크/컴퓨트 오퍼링 0건.
- GFS2 8개·RBD 5개 대상 이미지 부재 확인. 중간 단계 이미지와 Allocated 루트의 UUID도 포함한다.
- 기존 볼륨 31번 140개/32번 48개, VM 111개/48개, 디스크 오퍼링 7개/6개, 컴퓨트 오퍼링 15개/4개의 핵심 값이 최초 API 스냅샷과 일치.
- `volume_details`의 신규 allocation 행은 0개. 과거 볼륨의 적용 정책을 백필하지 않았다.
- 제품의 삭제 이력·실패 작업 이력과 비활성 오퍼링 이력은 보존했다.
- 기존 Windows FT 작업 트리를 수정하지 않았고, 별도 WSL FT 작업 트리의 diff SHA256도 전후 일치했다.

## UI 증거

테스트 자원은 캡처 후 정리했다. 현재 존재하는 운영 볼륨이라는 의미가 아니다.

| 환경 | 신규 정책 거절 | 기존 thin 생성·상세 |
|---|---|---|
| 31 | [화면](ui/31-new-policy-blocked.png) · [AX](ui/31-new-policy-blocked.txt) | [화면](ui/31-legacy-ui-created.png) · [AX](ui/31-legacy-ui-created.txt) |
| 32 | [화면](ui/32-new-policy-blocked.png) · [AX](ui/32-new-policy-blocked.txt) | [화면](ui/32-legacy-ui-created.png) · [AX](ui/32-legacy-ui-created.txt) |

[다음 작업 순서](next-steps.ko.md). #1424는 구현·검증 완료 후 PR 병합 대기이며, Epic과 다른 하위 이슈는 계속 진행한다.
