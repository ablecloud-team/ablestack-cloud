# SharedFS 생성의 ROOT 오퍼링 필터 갭

2026-10-09 읽기 전용 확인이다. 신원 포함 백업 UI 의 parent pin 이후 별도 2 개 소스 파일로 ROOT 생성 필터를 구현했다. 신규 9 개 포함 150 개 테스트 / 10 개 suite 와 no-fix lint 가 완료 상태로 통과했으며 고정 후보의 운영 빌드도 통과했다. 실제 VM / disk 생성과 기존 ROOT 변경은 수행하지 않았다.

## 실제 API 값과 연결 근거

부모의 현재 정상 API 읽기 증거는 `/root/work/epic898-preparation/root-offering-provisioning-current-20261009.json` 이다.

| 서비스 오퍼링 | SO UUID | 연결 ROOT DO UUID | provisioning |
| --- | --- | --- | --- |
| 2C4GB | ff8d160d-7523-46a7-ab98-faf49d16554b | 48bae5f6-dc30-451f-a7bb-b4e4a877a1ee | thin |
| epic898-sharedfs-sparse-2C4GB | fc808c8b-00fa-42d2-98b6-a082f12c347f | 90b3f7d0-bf5b-46dc-bb6c-d46c57fa8774 | sparse |
| epic898-sharedfs-sparse-4C8GB | 57c431e2-3330-4a1d-a0ff-11cbb5321899 | 1ae2dee3-dbf8-4626-9664-601a7dbef950 | sparse |

직접 `listDiskOfferings` 로 hidden ROOT DO 를 읽은 목록은 0 개다. 이를 ROOT DO 부재로 판정하지 않는다. `cloud.service_offering_view.sql` 은 disk_offering.provisioning_type 을 service_offering.disk_offering_id 로 join 하며 ServiceOfferingJoinDaoImpl 은 해당 enum 및 linked DO UUID 를 응답한다. 따라서 SO API 의 provisioning 값은 연결 ROOT DO 의 조회값이다.

## 현재 소스의 불일치

`CreateSharedFS.fetchServiceOfferings` 는 provisioning 을 무시하고 listStorageServiceOfferingConstraints 의 compatible 값만으로 첫 오퍼링을 기본 선택한다. 공통 SharedFSOfferingValidator 는 CPU, 메모리, HA, scaling, template / hypervisor 조건을 확인하며 ROOT provisioning 을 검사하지 않는다. 생성의 validateSparseNewRootOffering 은 별도로 SPARSE / FAT 를 강제하므로 THIN 기본 선택은 실제 생성 시 거절된다.

공통 오퍼링 검증은 기존 VM 의 online scale 에도 쓰인다. ROOT provisioning 거절을 공통 경로에 전역 추가해 기존 THIN ROOT 보존·조회·스케일을 막지 않는다.

## 승인된 후속 기능 단위

- Create 전용으로 literal SPARSE / FAT provisioning 및 linked ROOT DO UUID 를 요구한다.
- CPU compatibility 와 이 ROOT 조건을 함께 충족한 후보만 선택·default 로 사용한다. THIN / unknown 은 선택에서 차단한다.
- 선택한 ID 를 정상 create POST 직전에 현재 목록과 다시 대조한다. 없거나 foreign / stale / changed / failed 응답이면 새 요청이 없다.
- sparse / fat 기본 선택, 누락 / unknown / foreign ID, 문자열 또는 잘못된 값 유형, 선택 후 변경, error / stale list 를 의미 있는 테스트로 확인한다.
- 기존 ROOT 의 형식과 기존 오퍼링은 바꾸지 않는다. 소스 결과를 실제 SPARSE ROOT 할당 또는 최종 UI 인수 완료로 승격하지 않는다.

최종 UI #1275 의 style / theme / button 정렬 / keyboard QA 는 미착수다.

## 구현과 고정 소스 증거

Create 전용 literal provisioning, 연결된 ROOT DO UUID 및 서버 compute compatibility 를 함께 확인한다. 같은 owner / zone 의 정상 조회가 완료된 후보만 기본 선택하며, 조회 중·실패·scope 변경에서는 선택과 POST 를 차단한다. 실제 submit 메서드에서 create 요청 바로 전에 현재 ID 와 ROOT 조건을 다시 확인한다. 기존 THIN 오퍼링은 수정하거나 삭제하지 않고 표시만 비활성화한다. 공통 scale 검증과 locale 파일은 바꾸지 않았다. 기존 style block 과 footer button 은 byte 단위로 동일하다.

- 소스 검증: `/root/work/epic898-preparation/ui-create-root-offering-source-validation.json`
- 테스트 완료 로그 SHA: `1aeee5dc8685913c8490b78095d0277a3082b2f097e9a27addf9b8e2144f6eae`
- 운영 빌드 후보: `/root/work/epic898-preparation/ui-create-root-offering-production-candidate`
- 운영 빌드 로그: `/root/work/epic898-preparation/ui-create-root-offering-production-build.log`

| 소스 | SHA-256 |
| --- | --- |
| `ui/src/views/storage/CreateSharedFS.vue` | `84190fa32a09e004edb1f30a52e84ca13dd498f1dc726802e84d5f7c96cbe885` |
| `ui/tests/unit/views/storage/CreateSharedFSOfferingConstraints.spec.js` | `eab3f511bce489cb023ad006a408e094b1c06ff50a9419b2a72c177d002c5c80` |

후보는 커밋된 UI `465ec24b4d470a557c5f8ac5f0dabfe90f8decb7` 에 위 소스 2 개만 덧씌웠다. Node 14.21.3 / heap 12 GiB 를 사용하고 Java / native WIP 는 포함하지 않는다. template 선택 변경과 backend template compatibility 는 별도 조건이며 이 ROOT 필터를 템플릿의 실행 승인으로 사용하지 않는다. 실제 Cua 기본 SPARSE 및 THIN 비활성화 검증은 부모 배포 뒤 수행한다. 새 VM / disk 생성은 아직 0 이다.

## 운영 빌드 완료

2026-10-08T22:15:18Z 에 운영 빌드와 설정 생성이 모두 종료 코드 `0` 으로 통과했다. 실제 산출물은 850 개이며 candidate 및 canonical 소스 2 개의 해시는 검증 시 값과 같다.

- 산출물: `/root/work/epic898-preparation/ui-create-root-offering-production-candidate/ui/dist`
- `index.html` SHA: `01f36241c976986f95ed1602b08aa614330b05b356366402ed8a6aefbddec0ea`
- `config.json` SHA: `44d080ee12cd291185d65c81a605e71e455dada1a295b46d51e7de86c877b860`
- 빌드 로그 SHA: `9d77de2af35b1d9c51a52f06b9453b7e1fb3d1f07bf6c2e1a390bac15cac58f8`
- manifest: `/root/work/epic898-preparation/ui-create-root-offering-production-dist-manifest.json`
- manifest SHA: `849317afe0928eaaef922c6dd3d106451f2b47a14c3774362b37682d09e12891`

실제 Cua 기본 선택 / THIN 비활성화 / submit 거절 검증은 부모 배포 뒤 진행한다. 새 VM 또는 disk 생성, 기존 THIN ROOT 변경, 공통 scale 제약 변경과 최종 UI 표준화는 이번 단위에서 수행하지 않았다.
