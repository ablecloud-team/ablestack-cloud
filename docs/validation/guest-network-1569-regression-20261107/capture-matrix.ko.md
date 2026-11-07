<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
 -->

# #1569 역할·조건부 회귀 원본 캡처

147개 JPEG 원본이다. 재구성·축소·합성·보정하지 않았다. 브라우저 캡처 시점의 화면/문서 크기와 JPEG 서명·디코딩 크기를 대조했다. [치수·SHA256 원자료](evidence/ui-captures.json). 같은 파일명으로 재촬영한 1건은 마지막 원본을 게시했다.

31번은 라이트→다크→라이트, 32번은 다크→라이트→다크로 확인했다. 프로젝트·역할·탭·작업별 캡처이며 전체 조합의 통과를 뜻하지 않는다. 표의 UI 상태는 촬영 시점이다. 원래 NIC 6개에 확인용 공유 NIC를 추가한 최종 상태는 7개다.

| 클러스터 | 확인 화면 | 디코딩 크기 | 원본 |
| --- | --- | --- | --- |
| 31 | owner-project-network-list | 1670×841 | [JPEG](screenshots/31-owner-project-network-list.jpg) |
| 31 | owner-isolated-detail | 1670×841 | [JPEG](screenshots/31-owner-isolated-detail.jpg) |
| 31 | owner-isolated-egress-toolbar | 1670×841 | [JPEG](screenshots/31-owner-isolated-egress-toolbar.jpg) |
| 31 | owner-egress-dialog | 1670×841 | [JPEG](screenshots/31-owner-egress-dialog.jpg) |
| 31 | egress-validation-input-preserved | 1670×841 | [JPEG](screenshots/31-egress-validation-input-preserved.jpg) |
| 31 | owner-egress-created | 1670×841 | [JPEG](screenshots/31-owner-egress-created.jpg) |
| 31 | owner-isolated-update-dialog | 1670×841 | [JPEG](screenshots/31-owner-isolated-update-dialog.jpg) |
| 31 | owner-shared-update-dialog | 1670×841 | [JPEG](screenshots/31-owner-shared-update-dialog.jpg) |
| 31 | owner-shared-updated | 1670×841 | [JPEG](screenshots/31-owner-shared-updated.jpg) |
| 31 | owner-l2-update-dialog | 1670×841 | [JPEG](screenshots/31-owner-l2-update-dialog.jpg) |
| 32 | owner-project-network-list | 1670×841 | [JPEG](screenshots/32-owner-project-network-list.jpg) |
| 32 | owner-nat-update-dialog | 1670×841 | [JPEG](screenshots/32-owner-nat-update-dialog.jpg) |
| 31 | owner-l2-updated | 1670×841 | [JPEG](screenshots/31-owner-l2-updated.jpg) |
| 31 | member-nat-update-dialog | 1670×841 | [JPEG](screenshots/31-member-nat-update-dialog.jpg) |
| 31 | member-nat-updated | 1670×841 | [JPEG](screenshots/31-member-nat-updated.jpg) |
| 31 | member-l2-update-dialog | 1670×841 | [JPEG](screenshots/31-member-l2-update-dialog.jpg) |
| 31 | member-l2-updated | 1670×841 | [JPEG](screenshots/31-member-l2-updated.jpg) |
| 31 | member-shared-update-dialog | 1670×841 | [JPEG](screenshots/31-member-shared-update-dialog.jpg) |
| 32 | owner-nat-updated | 1670×841 | [JPEG](screenshots/32-owner-nat-updated.jpg) |
| 32 | owner-l2-update-dialog | 1670×841 | [JPEG](screenshots/32-owner-l2-update-dialog.jpg) |
| 31 | member-shared-updated | 1670×841 | [JPEG](screenshots/31-member-shared-updated.jpg) |
| 32 | owner-l2-updated | 1670×841 | [JPEG](screenshots/32-owner-l2-updated.jpg) |
| 32 | owner-shared-update-dialog | 1670×841 | [JPEG](screenshots/32-owner-shared-update-dialog.jpg) |
| 31 | domainadmin-nat-update-dialog | 1670×841 | [JPEG](screenshots/31-domainadmin-nat-update-dialog.jpg) |
| 32 | owner-shared-updated | 1670×841 | [JPEG](screenshots/32-owner-shared-updated.jpg) |
| 32 | member-nat-update-dialog | 1670×841 | [JPEG](screenshots/32-member-nat-update-dialog.jpg) |
| 31 | domainadmin-nat-updated | 1670×841 | [JPEG](screenshots/31-domainadmin-nat-updated.jpg) |
| 31 | domainadmin-l2-update-dialog | 1670×841 | [JPEG](screenshots/31-domainadmin-l2-update-dialog.jpg) |
| 31 | domainadmin-l2-updated | 1670×841 | [JPEG](screenshots/31-domainadmin-l2-updated.jpg) |
| 31 | domainadmin-shared-update-dialog | 1670×841 | [JPEG](screenshots/31-domainadmin-shared-update-dialog.jpg) |
| 32 | member-nat-updated | 1670×841 | [JPEG](screenshots/32-member-nat-updated.jpg) |
| 32 | member-l2-update-dialog | 1670×841 | [JPEG](screenshots/32-member-l2-update-dialog.jpg) |
| 31 | domainadmin-shared-updated | 1670×841 | [JPEG](screenshots/31-domainadmin-shared-updated.jpg) |
| 32 | member-l2-updated | 1670×841 | [JPEG](screenshots/32-member-l2-updated.jpg) |
| 32 | member-shared-update-dialog | 1670×841 | [JPEG](screenshots/32-member-shared-update-dialog.jpg) |
| 31 | root-nat-update-dialog | 1670×841 | [JPEG](screenshots/31-root-nat-update-dialog.jpg) |
| 32 | member-shared-updated | 1670×841 | [JPEG](screenshots/32-member-shared-updated.jpg) |
| 32 | domainadmin-nat-update-dialog | 1670×841 | [JPEG](screenshots/32-domainadmin-nat-update-dialog.jpg) |
| 31 | root-nat-updated | 1670×841 | [JPEG](screenshots/31-root-nat-updated.jpg) |
| 31 | root-l2-update-dialog | 1670×841 | [JPEG](screenshots/31-root-l2-update-dialog.jpg) |
| 32 | domainadmin-nat-updated | 1670×841 | [JPEG](screenshots/32-domainadmin-nat-updated.jpg) |
| 32 | domainadmin-l2-update-dialog | 1670×841 | [JPEG](screenshots/32-domainadmin-l2-update-dialog.jpg) |
| 31 | root-l2-updated | 1670×841 | [JPEG](screenshots/31-root-l2-updated.jpg) |
| 31 | root-shared-update-dialog | 1670×841 | [JPEG](screenshots/31-root-shared-update-dialog.jpg) |
| 31 | root-shared-updated | 1670×841 | [JPEG](screenshots/31-root-shared-updated.jpg) |
| 32 | domainadmin-l2-updated | 1670×841 | [JPEG](screenshots/32-domainadmin-l2-updated.jpg) |
| 32 | domainadmin-shared-update-dialog | 1670×841 | [JPEG](screenshots/32-domainadmin-shared-update-dialog.jpg) |
| 31 | vm-standard-nic-revoked-resource | 1670×841 | [JPEG](screenshots/31-vm-standard-nic-revoked-resource.jpg) |
| 31 | vm-standard-nic-ready | 1670×841 | [JPEG](screenshots/31-vm-standard-nic-ready.jpg) |
| 32 | domainadmin-shared-updated | 1670×841 | [JPEG](screenshots/32-domainadmin-shared-updated.jpg) |
| 32 | root-nat-update-dialog | 1670×841 | [JPEG](screenshots/32-root-nat-update-dialog.jpg) |
| 32 | root-nat-updated | 1670×841 | [JPEG](screenshots/32-root-nat-updated.jpg) |
| 32 | root-l2-update-dialog | 1670×841 | [JPEG](screenshots/32-root-l2-update-dialog.jpg) |
| 31 | member-revoked-nic-table | 1670×841 | [JPEG](screenshots/31-member-revoked-nic-table.jpg) |
| 32 | root-l2-updated | 1670×841 | [JPEG](screenshots/32-root-l2-updated.jpg) |
| 32 | root-shared-update-dialog | 1670×841 | [JPEG](screenshots/32-root-shared-update-dialog.jpg) |
| 31 | member-revoked-summary-link-denied | 1670×841 | [JPEG](screenshots/31-member-revoked-summary-link-denied.jpg) |
| 32 | root-shared-updated | 1670×841 | [JPEG](screenshots/32-root-shared-updated.jpg) |
| 31 | readonly-nat-restricted-detail | 1670×841 | [JPEG](screenshots/31-readonly-nat-restricted-detail.jpg) |
| 31 | readonly-l2-restricted-detail | 1670×841 | [JPEG](screenshots/31-readonly-l2-restricted-detail.jpg) |
| 31 | readonly-shared-restricted-detail | 1670×841 | [JPEG](screenshots/31-readonly-shared-restricted-detail.jpg) |
| 32 | member-revoked-nic-table | 1670×841 | [JPEG](screenshots/32-member-revoked-nic-table.jpg) |
| 31 | readonly-project-network-list | 1670×841 | [JPEG](screenshots/31-readonly-project-network-list.jpg) |
| 32 | member-revoked-summary-link-redirect | 1670×841 | [JPEG](screenshots/32-member-revoked-summary-link-redirect.jpg) |
| 31 | user-only-nat-restricted-detail | 1670×841 | [JPEG](screenshots/31-user-only-nat-restricted-detail.jpg) |
| 31 | user-only-l2-restricted-detail | 1670×841 | [JPEG](screenshots/31-user-only-l2-restricted-detail.jpg) |
| 31 | user-only-shared-restricted-detail | 1670×841 | [JPEG](screenshots/31-user-only-shared-restricted-detail.jpg) |
| 32 | readonly-nat-restricted-detail | 1670×841 | [JPEG](screenshots/32-readonly-nat-restricted-detail.jpg) |
| 32 | readonly-l2-restricted-detail | 1670×841 | [JPEG](screenshots/32-readonly-l2-restricted-detail.jpg) |
| 32 | readonly-shared-restricted-detail | 1670×841 | [JPEG](screenshots/32-readonly-shared-restricted-detail.jpg) |
| 31 | user-only-default-create-restored | 1670×841 | [JPEG](screenshots/31-user-only-default-create-restored.jpg) |
| 31 | user-only-project-create-hidden-again | 1670×841 | [JPEG](screenshots/31-user-only-project-create-hidden-again.jpg) |
| 32 | user-only-nat-restricted-detail | 1670×841 | [JPEG](screenshots/32-user-only-nat-restricted-detail.jpg) |
| 32 | user-only-l2-restricted-detail | 1670×841 | [JPEG](screenshots/32-user-only-l2-restricted-detail.jpg) |
| 32 | user-only-shared-restricted-detail | 1670×841 | [JPEG](screenshots/32-user-only-shared-restricted-detail.jpg) |
| 31 | peer-main-project-unavailable | 1670×841 | [JPEG](screenshots/31-peer-main-project-unavailable.jpg) |
| 31 | outsider-main-project-unavailable | 1670×841 | [JPEG](screenshots/31-outsider-main-project-unavailable.jpg) |
| 32 | peer-main-project-unavailable | 1670×841 | [JPEG](screenshots/32-peer-main-project-unavailable.jpg) |
| 32 | outsider-main-project-unavailable | 1670×841 | [JPEG](screenshots/32-outsider-main-project-unavailable.jpg) |
| 31 | owner-1280-network-list-opposite-theme | 1270×900 | [JPEG](screenshots/31-owner-1280-network-list-opposite-theme.jpg) |
| 32 | owner-1280-network-list-opposite-theme | 1270×900 | [JPEG](screenshots/32-owner-1280-network-list-opposite-theme.jpg) |
| 31 | vm-standard-1280-list | 1270×900 | [JPEG](screenshots/31-vm-standard-1280-list.jpg) |
| 32 | vm-standard-1280-list | 1270×900 | [JPEG](screenshots/32-vm-standard-1280-list.jpg) |
| 31 | vm-standard-1280-nic | 1270×900 | [JPEG](screenshots/31-vm-standard-1280-nic.jpg) |
| 32 | vm-standard-1280-nic | 1270×900 | [JPEG](screenshots/32-vm-standard-1280-nic.jpg) |
| 31 | vm-standard-390-connect-dialog | 380×844 | [JPEG](screenshots/31-vm-standard-390-connect-dialog.jpg) |
| 31 | vm-standard-390-nic-cards | 380×844 | [JPEG](screenshots/31-vm-standard-390-nic-cards.jpg) |
| 32 | vm-standard-390-connect-dialog | 380×844 | [JPEG](screenshots/32-vm-standard-390-connect-dialog.jpg) |
| 32 | vm-standard-390-nic-cards | 380×844 | [JPEG](screenshots/32-vm-standard-390-nic-cards.jpg) |
| 31 | owner-390-nat-detail | 380×844 | [JPEG](screenshots/31-owner-390-nat-detail.jpg) |
| 31 | owner-390-l2-detail | 380×844 | [JPEG](screenshots/31-owner-390-l2-detail.jpg) |
| 31 | owner-390-shared-detail | 380×844 | [JPEG](screenshots/31-owner-390-shared-detail.jpg) |
| 32 | owner-390-nat-detail | 380×844 | [JPEG](screenshots/32-owner-390-nat-detail.jpg) |
| 32 | owner-390-l2-detail | 380×844 | [JPEG](screenshots/32-owner-390-l2-detail.jpg) |
| 32 | owner-390-shared-detail | 380×844 | [JPEG](screenshots/32-owner-390-shared-detail.jpg) |
| 31 | owner-390-project-selector | 380×844 | [JPEG](screenshots/31-owner-390-project-selector.jpg) |
| 32 | owner-390-project-selector | 380×844 | [JPEG](screenshots/32-owner-390-project-selector.jpg) |
| 31 | owner-390-default-network-list | 380×844 | [JPEG](screenshots/31-owner-390-default-network-list.jpg) |
| 32 | owner-390-default-network-list | 380×844 | [JPEG](screenshots/32-owner-390-default-network-list.jpg) |
| 31 | owner-390-project-network-list | 380×844 | [JPEG](screenshots/31-owner-390-project-network-list.jpg) |
| 32 | owner-390-project-network-list | 380×844 | [JPEG](screenshots/32-owner-390-project-network-list.jpg) |
| 31 | owner-390-shared-ip-tab | 380×844 | [JPEG](screenshots/31-owner-390-shared-ip-tab.jpg) |
| 32 | owner-390-shared-ip-tab | 380×844 | [JPEG](screenshots/32-owner-390-shared-ip-tab.jpg) |
| 31 | owner-390-shared-update-dialog | 380×844 | [JPEG](screenshots/31-owner-390-shared-update-dialog.jpg) |
| 31 | owner-768-shared-update-dialog | 758×1024 | [JPEG](screenshots/31-owner-768-shared-update-dialog.jpg) |
| 31 | owner-768-shared-ip-tab | 758×1024 | [JPEG](screenshots/31-owner-768-shared-ip-tab.jpg) |
| 32 | owner-390-shared-update-dialog | 380×844 | [JPEG](screenshots/32-owner-390-shared-update-dialog.jpg) |
| 32 | owner-768-shared-update-dialog | 758×1024 | [JPEG](screenshots/32-owner-768-shared-update-dialog.jpg) |
| 32 | owner-768-shared-ip-tab | 758×1024 | [JPEG](screenshots/32-owner-768-shared-ip-tab.jpg) |
| 31 | owner-network-tags-comment-escaped | 1270×900 | [JPEG](screenshots/31-owner-network-tags-comment-escaped.jpg) |
| 32 | owner-network-tags-comment-escaped | 1270×900 | [JPEG](screenshots/32-owner-network-tags-comment-escaped.jpg) |
| 31 | owner-network-events-page1 | 1270×900 | [JPEG](screenshots/31-owner-network-events-page1.jpg) |
| 32 | owner-network-events-page1 | 1270×900 | [JPEG](screenshots/32-owner-network-events-page1.jpg) |
| 31 | owner-network-events-empty-search | 1270×900 | [JPEG](screenshots/31-owner-network-events-empty-search.jpg) |
| 32 | owner-network-events-empty-search | 1270×900 | [JPEG](screenshots/32-owner-network-events-empty-search.jpg) |
| 31 | owner-nat-public-ip-conditions | 1270×900 | [JPEG](screenshots/31-owner-nat-public-ip-conditions.jpg) |
| 32 | owner-nat-public-ip-conditions | 1270×900 | [JPEG](screenshots/32-owner-nat-public-ip-conditions.jpg) |
| 31 | lb-null-protocol-and-explicit-tcp-rows | 1270×900 | [JPEG](screenshots/31-lb-null-protocol-and-explicit-tcp-rows.jpg) |
| 32 | lb-null-protocol-and-explicit-tcp-rows | 1270×900 | [JPEG](screenshots/32-lb-null-protocol-and-explicit-tcp-rows.jpg) |
| 31 | lb-null-protocol-edit-before-submit | 1270×900 | [JPEG](screenshots/31-lb-null-protocol-edit-before-submit.jpg) |
| 32 | lb-null-protocol-edit-before-submit | 1270×900 | [JPEG](screenshots/32-lb-null-protocol-edit-before-submit.jpg) |
| 31 | lb-null-protocol-ui-error | 1270×900 | [JPEG](screenshots/31-lb-null-protocol-ui-error.jpg) |
| 32 | lb-null-protocol-ui-error | 1270×900 | [JPEG](screenshots/32-lb-null-protocol-ui-error.jpg) |
| 31 | lb-explicit-tcp-stickiness-mobile | 380×844 | [JPEG](screenshots/31-lb-explicit-tcp-stickiness-mobile.jpg) |
| 31 | lb-stickiness-mobile-footer | 380×844 | [JPEG](screenshots/31-lb-stickiness-mobile-footer.jpg) |
| 32 | lb-explicit-tcp-stickiness-mobile | 380×844 | [JPEG](screenshots/32-lb-explicit-tcp-stickiness-mobile.jpg) |
| 32 | lb-stickiness-mobile-footer | 380×844 | [JPEG](screenshots/32-lb-stickiness-mobile-footer.jpg) |
| 31 | vpc-tier-detail | 1270×900 | [JPEG](screenshots/31-vpc-tier-detail.jpg) |
| 32 | vpc-tier-detail | 1270×900 | [JPEG](screenshots/32-vpc-tier-detail.jpg) |
| 31 | vpc-acl-rules-768 | 758×1024 | [JPEG](screenshots/31-vpc-acl-rules-768.jpg) |
| 31 | pvlan-primary-secondary-detail | 1270×900 | [JPEG](screenshots/31-pvlan-primary-secondary-detail.jpg) |
| 32 | vpc-acl-rules-768 | 758×1024 | [JPEG](screenshots/32-vpc-acl-rules-768.jpg) |
| 32 | pvlan-primary-secondary-detail | 1270×900 | [JPEG](screenshots/32-pvlan-primary-secondary-detail.jpg) |
| 31 | readonly-scope-verified | 1670×841 | [JPEG](screenshots/31-readonly-scope-verified.jpg) |
| 31 | readonly-vm-nic-write-protected | 1670×841 | [JPEG](screenshots/31-readonly-vm-nic-write-protected.jpg) |
| 32 | readonly-scope-verified | 1670×841 | [JPEG](screenshots/32-readonly-scope-verified.jpg) |
| 32 | readonly-vm-nic-write-protected | 1670×841 | [JPEG](screenshots/32-readonly-vm-nic-write-protected.jpg) |
| 31 | user-only-scope-verified | 1670×841 | [JPEG](screenshots/31-user-only-scope-verified.jpg) |
| 31 | user-only-vm-nic-write-protected | 1670×841 | [JPEG](screenshots/31-user-only-vm-nic-write-protected.jpg) |
| 32 | user-only-scope-verified | 1670×841 | [JPEG](screenshots/32-user-only-scope-verified.jpg) |
| 32 | user-only-vm-nic-write-protected | 1670×841 | [JPEG](screenshots/32-user-only-vm-nic-write-protected.jpg) |
| 31 | root-pvlan-primary-secondary-detail | 1270×900 | [JPEG](screenshots/31-root-pvlan-primary-secondary-detail.jpg) |
| 32 | root-pvlan-primary-secondary-detail | 1270×900 | [JPEG](screenshots/32-root-pvlan-primary-secondary-detail.jpg) |
| 31 | network-permissions-restored-account-project | 1270×900 | [JPEG](screenshots/31-network-permissions-restored-account-project.jpg) |
| 32 | network-permissions-restored-account-project | 1270×900 | [JPEG](screenshots/32-network-permissions-restored-account-project.jpg) |
| 31 | final-review-project-networks | 1670×841 | [JPEG](screenshots/31-final-review-project-networks.jpg) |
| 32 | final-review-project-networks | 1670×841 | [JPEG](screenshots/32-final-review-project-networks.jpg) |
