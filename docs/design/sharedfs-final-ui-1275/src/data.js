// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

export const sections = {
  "nfs": [
    {
      "title": "수신 포트 그룹",
      "description": "수신 IP와 실제 접속 가능한 엔드포인트를 구분합니다.",
      "columns": [
        "수신 IP",
        "포트",
        "리스너 유형",
        "접근 가능 엔드포인트",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "0.0.0.0",
          "2049",
          "전체 수신",
          "10.1.1.9:2049",
          "1",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "NFS 내보내기 목록",
      "description": "내보내기 이름은 클라이언트에 노출되는 NFS 루트입니다.",
      "columns": [
        "내보내기 이름",
        "클라이언트 마운트 루트",
        "내부 백킹 경로",
        "프로토콜 모드",
        "IP / 포트",
        "권한",
        "Root Squash",
        "POSIX 권한",
        "접근 허용 목록",
        "용량",
        "백킹 볼륨",
        "상태"
      ],
      "rows": [
        [
          "nfs-test-nfs",
          "10.1.1.9:/nfs-test-nfs",
          "/export/nfs-test-nfs",
          "NFSv4 전용",
          "10.1.1.9:2049",
          "읽기/쓰기",
          "예",
          "65534:65534 / 0775",
          "기본 전체 허용",
          "100 GiB",
          "sharedfs-DATA-39",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "접근 허용 목록",
      "description": "NFS 내보내기에 적용된 접근 허용 항목입니다.",
      "columns": [
        "내보내기 이름",
        "대상 주체",
        "권한",
        "Root Squash",
        "All Squash",
        "익명 UID/GID",
        "동기화",
        "특권 포트 요구",
        "상태"
      ],
      "rows": [
        [
          "nfs-test-nfs",
          "10.1.1.0/24",
          "읽기/쓰기",
          "예",
          "아니오",
          "65534:65534",
          "예",
          "아니오",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "백킹 볼륨",
      "description": "이 서비스에 연결된 ABLESTACK 볼륨과 현재 장치 매핑 정보입니다.",
      "columns": [
        "볼륨 이름",
        "볼륨",
        "크기",
        "사용 용량",
        "디스크 오퍼링",
        "스토리지 풀",
        "파일 시스템",
        "현재 SystemVM 장치",
        "매핑 상태",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "sharedfs-DATA-39",
          "a5f4360d-5830-4c67-bac1-b078fb85c5fe",
          "100 GiB",
          "1.5 GiB",
          "SharedFS 데이터",
          "Primary Storage",
          "XFS",
          "/dev/sdb",
          "정확",
          "nfs-test-nfs",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "세션",
      "description": "활성 또는 마지막으로 확인된 클라이언트 세션입니다.",
      "columns": [
        "클라이언트",
        "상태",
        "연결 시간",
        "내보내기 이름",
        "서비스 엔드포인트"
      ],
      "rows": [
        [
          "10.1.1.21",
          "활성",
          "2026-10-06 09:30",
          "nfs-test-nfs",
          "10.1.1.9:2049"
        ]
      ]
    }
  ],
  "smb": [
    {
      "title": "수신 포트 그룹",
      "description": "수신 IP와 실제 접속 가능한 엔드포인트를 구분합니다.",
      "columns": [
        "수신 IP",
        "포트",
        "리스너 유형",
        "접근 가능 엔드포인트",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "0.0.0.0",
          "445",
          "전체 수신",
          "10.1.1.9:445",
          "1",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "SMB 공유 목록",
      "description": "SMB 공유 이름은 클라이언트에 노출되는 UNC 루트입니다.",
      "columns": [
        "공유 이름",
        "클라이언트 UNC 루트",
        "내부 백킹 경로",
        "IP / 포트",
        "탐색 허용",
        "게스트 접근",
        "권한",
        "용량",
        "백킹 볼륨",
        "상태"
      ],
      "rows": [
        [
          "team-share",
          "\\\\10.1.1.9\\team-share",
          "/export/team-share",
          "10.1.1.9:445",
          "예",
          "아니오",
          "읽기/쓰기",
          "100 GiB",
          "sharedfs-DATA-39",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "SMB 접근 및 계정",
      "description": "로컬/AD 주체와 공유 권한을 분리합니다.",
      "columns": [
        "공유 이름",
        "주체 유형",
        "대상 주체",
        "권한",
        "인증 방식",
        "상태"
      ],
      "rows": [
        [
          "team-share",
          "그룹",
          "EXAMPLE\\storage-users",
          "읽기/쓰기",
          "Active Directory",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "SMB 인증",
      "description": "SMB 서비스의 인증 방식과 도메인 가입 정보입니다.",
      "columns": [
        "인증 방식",
        "AD 도메인",
        "작업 그룹",
        "가입 상태",
        "Trust 검증",
        "DNS 서버"
      ],
      "rows": [
        [
          "Active Directory",
          "example.internal",
          "EXAMPLE",
          "가입 완료",
          "정상",
          "10.1.1.1"
        ]
      ]
    },
    {
      "title": "백킹 볼륨",
      "description": "이 서비스에 연결된 ABLESTACK 볼륨과 현재 장치 매핑 정보입니다.",
      "columns": [
        "볼륨 이름",
        "볼륨",
        "크기",
        "사용 용량",
        "디스크 오퍼링",
        "스토리지 풀",
        "파일 시스템",
        "현재 SystemVM 장치",
        "매핑 상태",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "sharedfs-DATA-39",
          "a5f4360d-5830-4c67-bac1-b078fb85c5fe",
          "100 GiB",
          "1.5 GiB",
          "SharedFS 데이터",
          "Primary Storage",
          "XFS",
          "/dev/sdb",
          "정확",
          "nfs-test-nfs",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "세션",
      "description": "활성 또는 마지막으로 확인된 클라이언트 세션입니다.",
      "columns": [
        "클라이언트",
        "사용자 이름",
        "SMB 공유 이름",
        "SMB 버전",
        "상태",
        "연결 시간",
        "서비스 엔드포인트",
        "트리/세션 ID"
      ],
      "rows": [
        [
          "10.1.1.21",
          "EXAMPLE\\storage-user",
          "team-share",
          "SMB 3.1.1",
          "활성",
          "2026-10-06 09:30",
          "10.1.1.9:445",
          "1001"
        ]
      ]
    }
  ],
  "iscsi": [
    {
      "title": "수신 포트 그룹",
      "description": "수신 IP와 실제 접속 가능한 엔드포인트를 구분합니다.",
      "columns": [
        "수신 IP",
        "포트",
        "리스너 유형",
        "접근 가능 엔드포인트",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "0.0.0.0",
          "3260",
          "전체 수신",
          "10.1.1.9:3260",
          "1",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "iSCSI 대상",
      "description": "iSCSI 대상과 연결된 LUN의 정보입니다.",
      "columns": [
        "대상 이름",
        "IQN",
        "포트",
        "인증 방식",
        "LUN 수",
        "상태"
      ],
      "rows": [
        [
          "block-store",
          "iqn.2026-10.example:storage.block-store",
          "3260",
          "CHAP",
          "1",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "LUN 목록",
      "description": "대상에 연결된 LUN과 백킹 볼륨 정보입니다.",
      "columns": [
        "LUN",
        "백킹 볼륨",
        "용량",
        "매핑 상태",
        "상태"
      ],
      "rows": [
        [
          "0",
          "sharedfs-block-01",
          "100 GiB",
          "정확",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "인증 및 접근 허용 목록",
      "description": "비밀값은 표시하지 않습니다.",
      "columns": [
        "대상",
        "Initiator IQN",
        "접근 정책",
        "인증 방식",
        "상태"
      ],
      "rows": [
        [
          "block-store",
          "iqn.2026-10.example:client.app-01",
          "허용",
          "CHAP 구성됨",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "백킹 볼륨",
      "description": "이 서비스에 연결된 ABLESTACK 볼륨과 현재 장치 매핑 정보입니다.",
      "columns": [
        "볼륨 이름",
        "볼륨",
        "크기",
        "사용 용량",
        "디스크 오퍼링",
        "스토리지 풀",
        "파일 시스템",
        "현재 SystemVM 장치",
        "매핑 상태",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "sharedfs-DATA-39",
          "a5f4360d-5830-4c67-bac1-b078fb85c5fe",
          "100 GiB",
          "1.5 GiB",
          "SharedFS 데이터",
          "Primary Storage",
          "XFS",
          "/dev/sdb",
          "정확",
          "nfs-test-nfs",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "세션",
      "description": "활성 또는 마지막으로 확인된 클라이언트 세션입니다.",
      "columns": [
        "클라이언트",
        "사용자",
        "서비스 리소스",
        "상태",
        "연결 시간",
        "엔드포인트"
      ],
      "rows": [
        [
          "10.1.1.21",
          "운영 사용자",
          "예시 연결",
          "활성",
          "2026-10-06 09:30",
          "10.1.1.9"
        ]
      ]
    }
  ],
  "nvmeof": [
    {
      "title": "수신 포트 그룹",
      "description": "수신 IP와 실제 접속 가능한 엔드포인트를 구분합니다.",
      "columns": [
        "수신 IP",
        "포트",
        "리스너 유형",
        "접근 가능 엔드포인트",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "0.0.0.0",
          "4420",
          "전체 수신",
          "10.1.1.9:4420",
          "1",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "NVMe-oF 서브시스템",
      "description": "NVMe-oF 서브시스템과 서비스 엔드포인트입니다.",
      "columns": [
        "서브시스템",
        "NQN",
        "전송 방식",
        "엔드포인트",
        "인증",
        "상태"
      ],
      "rows": [
        [
          "nvme-store",
          "nqn.2026-10.example:storage.nvme-store",
          "TCP",
          "10.1.1.9:4420",
          "DH-HMAC-CHAP",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "네임스페이스",
      "description": "네임스페이스와 백킹 볼륨을 구조화해 표시합니다.",
      "columns": [
        "NSID",
        "백킹 볼륨",
        "용량",
        "매핑 상태",
        "상태"
      ],
      "rows": [
        [
          "1",
          "sharedfs-nvme-01",
          "100 GiB",
          "정확",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "Host ACL 및 인증",
      "description": "비밀값 대신 인증 구성 상태를 표시합니다.",
      "columns": [
        "Host NQN",
        "Host ID",
        "인증 방식",
        "허용 정책",
        "상태"
      ],
      "rows": [
        [
          "nqn.2026-10.example:client.app-01",
          "예시 호스트",
          "구성됨",
          "허용",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "백킹 볼륨",
      "description": "이 서비스에 연결된 ABLESTACK 볼륨과 현재 장치 매핑 정보입니다.",
      "columns": [
        "볼륨 이름",
        "볼륨",
        "크기",
        "사용 용량",
        "디스크 오퍼링",
        "스토리지 풀",
        "파일 시스템",
        "현재 SystemVM 장치",
        "매핑 상태",
        "연결 리소스",
        "상태"
      ],
      "rows": [
        [
          "sharedfs-DATA-39",
          "a5f4360d-5830-4c67-bac1-b078fb85c5fe",
          "100 GiB",
          "1.5 GiB",
          "SharedFS 데이터",
          "Primary Storage",
          "XFS",
          "/dev/sdb",
          "정확",
          "nfs-test-nfs",
          "사용 가능"
        ]
      ]
    },
    {
      "title": "세션",
      "description": "활성 또는 마지막으로 확인된 클라이언트 세션입니다.",
      "columns": [
        "클라이언트",
        "사용자",
        "서비스 리소스",
        "상태",
        "연결 시간",
        "엔드포인트"
      ],
      "rows": [
        [
          "10.1.1.21",
          "운영 사용자",
          "예시 연결",
          "활성",
          "2026-10-06 09:30",
          "10.1.1.9"
        ]
      ]
    }
  ],
  "network": [
    {
      "title": "네트워크 인터페이스",
      "description": "연결된 네트워크 인터페이스와 주소 설정입니다.",
      "columns": [
        "장치",
        "네트워크",
        "MAC 주소",
        "IP 주소",
        "할당 방식",
        "게이트웨이",
        "DNS",
        "상태"
      ],
      "rows": [
        [
          "eth0",
          "서비스 네트워크",
          "02:00:00:00:00:09",
          "10.1.1.9/24",
          "고정 IP",
          "10.1.1.1",
          "10.1.1.1",
          "연결됨"
        ]
      ]
    },
    {
      "title": "서비스 IP",
      "description": "기본/보조 IP와 프로토콜 연결을 유지합니다.",
      "columns": [
        "IP 주소",
        "유형",
        "프로토콜",
        "수신 포트",
        "상태"
      ],
      "rows": [
        [
          "10.1.1.9",
          "기본",
          "NFS / SMB",
          "2049 / 445",
          "사용 가능"
        ],
        [
          "10.1.1.19",
          "보조",
          "iSCSI / NVMe-oF",
          "3260 / 4420",
          "사용 가능"
        ]
      ]
    }
  ],
  "events": [
    {
      "title": "이벤트 목록",
      "description": "공유 파일 시스템과 관련된 작업 이력입니다.",
      "columns": [
        "유형",
        "상태",
        "설명",
        "사용자",
        "생성 시간"
      ],
      "rows": [
        [
          "STORAGE.SERVICE.CONFIG.UPDATE",
          "완료",
          "공유 파일 시스템 설정 확인",
          "admin",
          "2026-10-06 09:30"
        ],
        [
          "STORAGE.SERVICE.RUNTIME.UPGRADE",
          "완료",
          "승인된 런타임 적용",
          "admin",
          "2026-10-06 09:10"
        ]
      ]
    }
  ]
}
