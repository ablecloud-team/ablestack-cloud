# VOL-04: RBD RAW 생성 시점 할당 설계

부모 Epic #1422 · 작업 #1426 · 공통 계약 #1424 · 후속 지원표 #1427

## 지원 범위

RBD RAW의 `off`는 일반 이미지 생성, `full`은 `rbd create --thick-provision`으로 생성한다. 템플릿을 사용하는 경우 새 대상에 전체 데이터를 복사한다. Full 복사는 thick 대상에 `qemu-img convert -n -S 0 -f <source> -O raw`를 사용한다. Ceph RBD의 COW clone 경로로 대체하지 않는다. 지원하는 원본은 같은 RBD 풀의 RAW 템플릿과 Agent가 읽을 수 있는 일반 RAW/QCOW2 템플릿 파일이다. 다른 RBD 풀, 암호화, 별도 data-pool, namespace는 이 구현의 지원 대상에 포함하지 않는다.

RBD의 falloc/lazy thick, QCOW2 metadata, Full 확장, snapshot 복원, 기존 볼륨의 정책 변경·이동·VM clone은 사전에 거절한다. GFS2 실행은 #1425에서 별도로 구현한다. 일반 RBD 생성에서 legacy `sparse/fat`를 성공한 정책으로 보고하지 않으며, 명시적인 RAW off/full 선택을 안내한다. 기존 볼륨의 legacy 기록은 그대로 보존하고 실제 적용 정책은 증거가 없으면 unknown으로 유지한다.

## 관리 서버와 Agent 경계

1. 오퍼링의 요청 계약을 기존 `volume_details`에 한 번 저장한다. 오퍼링의 향후 변경을 기존 볼륨으로 소급하지 않는다. DDL·기존 볼륨 backfill은 없다.
2. 새 정책 생성 전에 풀 유형, 태그, 연결 호스트의 Up 상태, Agent 프로토콜과 실제 도구 지원을 확인한다. API가 준비되어도 Agent 재접속을 기다려야 한다.
3. `VolumeObjectTO`에 요청 계약을 전달한다. 구 Agent가 필드를 무시하거나 결과에 증거가 없으면 경로·크기·Ready 갱신을 거절한다.
4. Agent가 이미지 크기, COW parent 부재, 데이터 비교, image별 `rbd du --exact` 결과를 확인한 뒤 적용 증거를 반환한다. Full은 image used_size가 가상 크기 이상이어야 한다.
5. 관리 서버가 요청, 실제 풀 UUID, 이미지 UUID, RAW, 크기, 정책·메서드·관측 출처를 다시 검증하고 증거를 저장한다.

## 실패·중복·재시도

최종 UUID를 직접 덮어쓰지 않고 `<volume UUID>.allocation-<random UUID>` 전용 임시 이미지를 사용한다. 검증과 요청 fingerprint 기록 이후 덮어쓰지 않는 RBD rename으로 최종 이름을 공개한다. 오류 시 해당 호출이 만든 임시 이름만 제한 시간 내 정리한다. 원본·최종 이미지·풀 전체는 정리하지 않는다. 정리 실패는 정확한 임시 이미지 이름을 오류로 보고한다.

동일 요청의 응답이 유실된 경우에는 이미지에 저장된 요청 fingerprint(풀·볼륨 UUID, 볼륨 DB ID, 소유자, 유형, 계약 버전, 형식, 정책, 크기, 작업, 원본)가 정확히 일치하고 데이터·할당 검증을 다시 통과할 때만 기존 이미지를 채택한다. 증거가 없는 동명 이미지, 서로 다른 요청, 데이터 불일치, discard 후 Full 할당량 부족은 거절한다. 호스트 간 중복 요청은 최종 rename 충돌로 처리하며 다른 요청이 공개한 이미지를 삭제하지 않는다.

Full은 가상 크기 전체에 대한 공간과 zeroing·읽기·복사 시간이 필요하다. 생성/복사 command의 대기 시간을 최소 1시간으로 확보하고 실제 작업에는 제한된 예산을 적용한다. 실패하면 async job에 구체적 오류가 전달되며 Ready로 전환하지 않는다. Full capability와 실행 시에는 같은 풀의 인증 정보로 `ceph df`의 `max_avail`을 확인한다. 이 값은 복제·CRUSH·full ratio 등을 반영한 풀의 쓰기 가능량 추정치이며 raw 클러스터 여유 바이트로 대체하지 않는다. 조회할 수 없으면 Full capability를 제공하지 않고 실행도 거절한다. Thin은 해당 Full 용량 보장과 분리한다. 사전 가용 공간 검사는 예약이 아니므로 동시 사용으로 인한 ENOSPC도 동일 실패 계약으로 처리한다.

## 증거와 의미

`allocation.applied.*`는 검증된 생성/복사 메서드이며 `allocation.observed.bytes/at/source`는 해당 시점 이미지의 관측값이다. `rbd du`의 image used_size는 복제본을 포함한 클러스터 물리 소비량이 아니다. Full은 생성/복사 시점의 전체 할당이며 지속적인 공간 예약을 의미하지 않는다. 이후 guest discard, zero 최적화, snapshot 등은 현재 할당량을 바꿀 수 있다. 화면은 이를 현재 물리 사용량 또는 영구 예약으로 표현하면 안 된다.

## 기술 근거

- [Ceph Reef rbd](https://docs.ceph.com/en/reef/man/8/rbd/): thick-provision은 전체 이미지 zeroing을 수행하며 오래 걸릴 수 있다.
- [Ceph 풀 용량 지표](https://docs.ceph.com/en/reef/rados/operations/monitoring/): MAX AVAIL은 대상 풀에 쓸 수 있는 논리 데이터량의 추정값이다.
- [QEMU qemu-img](https://www.qemu.org/docs/master/tools/qemu-img.html): convert의 -n, -S 0 및 compare의 크기 차이/zero tail 의미.
- Epic의 32번 실증: 64MiB thick 전체 할당·zero 읽기, 미리 thick 생성한 대상에 qcow2→RAW 전체 복사 및 동일성 확인. 파일용 resize preallocation의 RBD 적용은 Unsupported로 실패했다.

최종 배포·실물 검증 및 제한은 별도 검증 보고서에 기록한다.
