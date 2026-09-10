# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Flowable,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


BASE_DIR = Path(__file__).resolve().parent
OUTPUT_PDF = BASE_DIR / "cross-hypervisor-dr-customer-guide.pdf"
FONT_REGULAR = Path(r"C:\Windows\Fonts\malgun.ttf")
FONT_BOLD = Path(r"C:\Windows\Fonts\malgunbd.ttf")


pdfmetrics.registerFont(TTFont("Malgun", str(FONT_REGULAR)))
pdfmetrics.registerFont(TTFont("Malgun-Bold", str(FONT_BOLD)))


PAGE_WIDTH, PAGE_HEIGHT = landscape(A4)
PRIMARY = colors.HexColor("#1677ff")
PRIMARY_DARK = colors.HexColor("#0958d9")
TEXT = colors.HexColor("#1f2933")
MUTED = colors.HexColor("#667085")
LINE = colors.HexColor("#d0d7de")
SURFACE = colors.HexColor("#f6f8fb")
SURFACE_BLUE = colors.HexColor("#eaf3ff")
SURFACE_GREEN = colors.HexColor("#ecfdf3")
SURFACE_ORANGE = colors.HexColor("#fff7e6")


def build_styles():
    styles = getSampleStyleSheet()
    styles.add(
        ParagraphStyle(
            name="TitleKo",
            fontName="Malgun-Bold",
            fontSize=25,
            leading=34,
            alignment=TA_CENTER,
            textColor=colors.white,
            spaceAfter=8,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SubtitleKo",
            fontName="Malgun",
            fontSize=11,
            leading=17,
            alignment=TA_CENTER,
            textColor=colors.HexColor("#dbeafe"),
        )
    )
    styles.add(
        ParagraphStyle(
            name="SectionKo",
            fontName="Malgun-Bold",
            fontSize=16,
            leading=22,
            textColor=PRIMARY_DARK,
            spaceBefore=8,
            spaceAfter=8,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SubSectionKo",
            fontName="Malgun-Bold",
            fontSize=11,
            leading=16,
            textColor=TEXT,
            spaceBefore=6,
            spaceAfter=4,
        )
    )
    styles.add(
        ParagraphStyle(
            name="BodyKo",
            fontName="Malgun",
            fontSize=9.2,
            leading=14,
            textColor=TEXT,
            spaceAfter=5,
        )
    )
    styles.add(
        ParagraphStyle(
            name="SmallKo",
            fontName="Malgun",
            fontSize=8,
            leading=11,
            textColor=MUTED,
        )
    )
    styles.add(
        ParagraphStyle(
            name="TableHeadKo",
            fontName="Malgun-Bold",
            fontSize=8.2,
            leading=11,
            alignment=TA_CENTER,
            textColor=colors.white,
        )
    )
    styles.add(
        ParagraphStyle(
            name="TableBodyKo",
            fontName="Malgun",
            fontSize=7.7,
            leading=10.6,
            alignment=TA_LEFT,
            textColor=TEXT,
        )
    )
    styles.add(
        ParagraphStyle(
            name="CardTitleKo",
            fontName="Malgun-Bold",
            fontSize=10,
            leading=14,
            textColor=PRIMARY_DARK,
        )
    )
    return styles


STYLES = build_styles()


def p(text, style="BodyKo"):
    return Paragraph(text, STYLES[style])


def bullet(text):
    return Paragraph(f"- {text}", STYLES["BodyKo"])


def make_table(rows, col_widths, header=True):
    converted = []
    for row_index, row in enumerate(rows):
        style_name = "TableHeadKo" if header and row_index == 0 else "TableBodyKo"
        converted.append([p(str(cell), style_name) for cell in row])
    table = Table(converted, colWidths=col_widths, repeatRows=1 if header else 0, hAlign="LEFT")
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), PRIMARY_DARK if header else colors.white),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white if header else TEXT),
                ("GRID", (0, 0), (-1, -1), 0.4, LINE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
                ("BACKGROUND", (0, 1), (-1, -1), colors.white),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#fbfdff")]),
            ]
        )
    )
    return table


class TitleBand(Flowable):
    def __init__(self):
        super().__init__()
        self.width = PAGE_WIDTH - 36 * mm
        self.height = 78 * mm

    def draw(self):
        c = self.canv
        c.setFillColor(PRIMARY_DARK)
        c.roundRect(0, 0, self.width, self.height, 12, fill=1, stroke=0)
        c.setFillColor(PRIMARY)
        c.roundRect(12 * mm, 12 * mm, self.width - 24 * mm, self.height - 24 * mm, 10, fill=1, stroke=0)
        title = p("ABLESTACK Cross Hypervisor DR", "TitleKo")
        subtitle = p("고객 및 잠재고객 설명을 위한 기능 개요, 적용 시나리오, RPO/RTO 도입 안내", "SubtitleKo")
        title.wrapOn(c, self.width - 38 * mm, 30 * mm)
        subtitle.wrapOn(c, self.width - 38 * mm, 16 * mm)
        title.drawOn(c, 19 * mm, 42 * mm)
        subtitle.drawOn(c, 19 * mm, 29 * mm)
        c.setFont("Malgun", 8.5)
        c.setFillColor(colors.HexColor("#eff6ff"))
        c.drawCentredString(self.width / 2, 18 * mm, "문서 기준일: 2026-07-01  |  대상: 일반 사용자, 고객, DR 인프라 검토자")


class FlowDiagram(Flowable):
    def __init__(self, labels, title=None, rows=1, fill=colors.white):
        super().__init__()
        self.labels = labels
        self.title = title
        self.rows = rows
        self.fill = fill
        self.width = PAGE_WIDTH - 36 * mm
        self.height = (18 + rows * 30) * mm

    def draw(self):
        c = self.canv
        if self.title:
            c.setFont("Malgun-Bold", 10)
            c.setFillColor(PRIMARY_DARK)
            c.drawString(0, self.height - 6 * mm, self.title)
        top = self.height - (18 * mm if self.title else 8 * mm)
        row_labels = []
        if self.rows == 1:
            row_labels = [self.labels]
        else:
            split = (len(self.labels) + 1) // 2
            row_labels = [self.labels[:split], self.labels[split:]]
        box_h = 17 * mm
        gap = 8 * mm
        for row_index, row in enumerate(row_labels):
            count = len(row)
            box_w = (self.width - gap * (count - 1)) / count
            y = top - (row_index + 1) * box_h - row_index * 9 * mm
            for index, label in enumerate(row):
                x = index * (box_w + gap)
                c.setFillColor(self.fill if index % 2 == 0 else SURFACE_BLUE)
                c.setStrokeColor(PRIMARY)
                c.roundRect(x, y, box_w, box_h, 6, fill=1, stroke=1)
                para = Paragraph(label, ParagraphStyle("DiagramLabel", fontName="Malgun-Bold", fontSize=7.7, leading=10, alignment=TA_CENTER, textColor=TEXT))
                para.wrapOn(c, box_w - 5 * mm, box_h - 4 * mm)
                para.drawOn(c, x + 2.5 * mm, y + (box_h - para.height) / 2)
                if index < count - 1:
                    x1 = x + box_w + 1.5 * mm
                    x2 = x + box_w + gap - 1.5 * mm
                    yy = y + box_h / 2
                    c.setStrokeColor(PRIMARY_DARK)
                    c.line(x1, yy, x2, yy)
                    c.setFillColor(PRIMARY_DARK)
                    c.line(x2, yy, x2 - 2.2 * mm, yy + 1.6 * mm)
                    c.line(x2, yy, x2 - 2.2 * mm, yy - 1.6 * mm)
            if self.rows > 1 and row_index == 0:
                c.setStrokeColor(PRIMARY_DARK)
                c.line(self.width - 4 * mm, y - 1 * mm, self.width - 4 * mm, y - 8 * mm)


class RpoRtoDiagram(Flowable):
    def __init__(self):
        super().__init__()
        self.width = PAGE_WIDTH - 36 * mm
        self.height = 45 * mm

    def draw(self):
        c = self.canv
        c.setFont("Malgun-Bold", 10)
        c.setFillColor(PRIMARY_DARK)
        c.drawString(0, self.height - 5 * mm, "RPO/RTO 판단 흐름")
        labels = ["운영 VM 변경", "복제 주기", "DR 사이트 반영", "복구 지점", "서비스 기동", "서비스 확인"]
        box_w = (self.width - 5 * 7 * mm) / 6
        y = 13 * mm
        for i, label in enumerate(labels):
            x = i * (box_w + 7 * mm)
            c.setFillColor(SURFACE_GREEN if i <= 3 else SURFACE_ORANGE)
            c.setStrokeColor(PRIMARY if i <= 3 else colors.HexColor("#f59e0b"))
            c.roundRect(x, y, box_w, 15 * mm, 5, fill=1, stroke=1)
            para = Paragraph(label, ParagraphStyle("RpoLabel", fontName="Malgun-Bold", fontSize=7.2, leading=9, alignment=TA_CENTER, textColor=TEXT))
            para.wrapOn(c, box_w - 4 * mm, 12 * mm)
            para.drawOn(c, x + 2 * mm, y + (15 * mm - para.height) / 2)
            if i < len(labels) - 1:
                c.setStrokeColor(PRIMARY_DARK)
                x1 = x + box_w + 1 * mm
                x2 = x + box_w + 6 * mm
                c.line(x1, y + 7.5 * mm, x2, y + 7.5 * mm)
                c.line(x2, y + 7.5 * mm, x2 - 1.8 * mm, y + 9 * mm)
                c.line(x2, y + 7.5 * mm, x2 - 1.8 * mm, y + 6 * mm)
        c.setFont("Malgun-Bold", 8)
        c.setFillColor(colors.HexColor("#039855"))
        c.drawString(3 * mm, 5 * mm, "RPO: 최신 복구 지점이 얼마나 최근인가")
        c.setFillColor(colors.HexColor("#b54708"))
        c.drawString(self.width - 65 * mm, 5 * mm, "RTO: 서비스가 다시 사용 가능해지는 시간")


def section(title):
    return [Spacer(1, 2 * mm), p(title, "SectionKo")]


def on_page(canvas, doc):
    canvas.saveState()
    canvas.setFont("Malgun", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 10 * mm, "ABLESTACK Cross Hypervisor DR 고객 안내서")
    canvas.drawRightString(PAGE_WIDTH - 18 * mm, 10 * mm, f"{doc.page}")
    canvas.restoreState()


def build_story():
    story = [TitleBand(), Spacer(1, 7 * mm)]
    story.extend(
        [
            p("이 문서는 내부 구현 용어보다 고객이 이해해야 할 가치, 적용 시나리오, 도입 전 확인 사항을 중심으로 정리한 설명 자료이다."),
            p("Cross Hypervisor DR은 ABLESTACK와 VMware가 함께 존재하는 고객 환경에서 DR 운영을 하나의 절차로 표준화하는 것을 목표로 한다."),
            Spacer(1, 4 * mm),
            make_table(
                [
                    ["핵심 메시지", "고객에게 전달할 설명"],
                    ["이기종 DR 표준화", "운영 사이트와 DR 사이트의 플랫폼이 달라도 하나의 보호 계획과 복구 절차로 관리한다."],
                    ["UI 기반 운영", "DR 사이트 등록, 보호 계획, 복구 지점, 테스트 페일오버, 실제 페일오버를 Cloud UI에서 확인한다."],
                    ["비동기 작업", "복제와 복구 작업은 백그라운드로 진행되어 UI가 장시간 멈추지 않는다."],
                    ["RPO/RTO 중심", "최신 복구 지점과 목표 복구 시간을 기준으로 도입 효과를 검증한다."],
                ],
                [42 * mm, 205 * mm],
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("1. 왜 필요한가"))
    story.extend(
        [
            p("고객 인프라는 단일 가상화 플랫폼만으로 구성되지 않는 경우가 많다. 일부 업무는 ABLESTACK 기반 사설 클라우드에서 운영되고, 일부 업무는 VMware 환경에 남아 있을 수 있다. 또한 DR 센터의 표준 플랫폼이 운영 센터와 다를 수도 있다."),
            p("Cross Hypervisor DR은 이런 혼합 환경에서 DR 사이트, 보호 계획, 복구 지점, 실행 이력을 하나의 운영 모델로 묶어 장애 대응 절차를 단순화한다."),
            FlowDiagram(
                [
                    "사용자 / 운영자",
                    "Cloud UI",
                    "DR 사이트 및 보호 계획",
                    "DR 복제 엔진",
                    "복구 지점",
                    "테스트 / 실제 페일오버",
                ],
                title="고객 관점 전체 구성",
                fill=colors.white,
            ),
            make_table(
                [
                    ["기존 과제", "Cross Hypervisor DR 접근"],
                    ["플랫폼마다 다른 DR 도구와 절차", "하나의 UI와 보호 계획 흐름으로 통합"],
                    ["최신 복구 가능 시점 확인 어려움", "복구 지점과 RPO 상태를 기준으로 확인"],
                    ["DR 테스트가 복잡해 실제 훈련이 부족", "테스트 페일오버를 표준 운영 절차로 포함"],
                    ["운영/DR 플랫폼 선택 제약", "ABLESTACK와 VMware 조합을 시나리오별로 검토"],
                ],
                [80 * mm, 167 * mm],
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("2. 지원을 고려하는 4가지 DR 시나리오"))
    story.extend(
        [
            make_table(
                [
                    ["시나리오", "적합한 고객 상황", "핵심 설명"],
                    ["ABLESTACK -> VMware", "운영은 ABLESTACK, DR 센터는 기존 VMware 자산 활용", "ABLESTACK 운영 VM을 VMware DR 사이트에서 복구할 수 있도록 준비한다."],
                    ["VMware -> VMware", "기존 VMware 운영 환경에 별도 VMware DR 센터 구성", "VMware 변경 추적과 vCenter 운영 경험을 활용하는 동일 플랫폼 DR이다."],
                    ["ABLESTACK -> ABLESTACK", "ABLESTACK 사설 클라우드를 센터 단위로 이중화", "운영과 DR 모두 ABLESTACK 표준으로 관리한다."],
                    ["VMware -> ABLESTACK", "VMware 운영은 유지하고 DR 센터는 ABLESTACK으로 구성", "VMware 의존도를 낮추거나 ABLESTACK DR 센터를 전략적으로 활용한다."],
                ],
                [44 * mm, 87 * mm, 116 * mm],
            ),
            Spacer(1, 5 * mm),
            FlowDiagram(
                [
                    "운영 사이트 선택",
                    "DR 사이트 선택",
                    "보호 계획 생성",
                    "복구 지점 생성",
                    "테스트 페일오버",
                    "실제 전환 / 페일백",
                ],
                title="시나리오 공통 운영 흐름",
                fill=SURFACE,
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("3. 사용자 관점 운영 흐름"))
    story.extend(
        [
            p("사용자는 Cloud UI에서 사이트와 보호 계획을 등록하고, 복제 상태와 복구 지점을 확인한다. 동기화와 페일오버 같은 장기 작업은 백그라운드로 진행되며, UI는 작업 접수와 진행 상태를 보여준다."),
            FlowDiagram(
                [
                    "1. DR 사이트 등록",
                    "2. 보호 계획 생성",
                    "3. 동기화 시작",
                    "4. RPO 확인",
                    "5. 테스트 페일오버",
                    "6. 실제 페일오버",
                    "7. 페일백",
                    "8. 재보호 또는 종료",
                ],
                title="운영자가 보는 단계",
                rows=2,
                fill=colors.white,
            ),
            make_table(
                [
                    ["단계", "사용자 행동", "확인할 결과"],
                    ["사이트 등록", "운영 사이트와 DR 사이트 정보를 입력", "사이트 상태와 연결성 확인"],
                    ["보호 계획", "보호할 VM, 대상 사이트, RPO/RTO 목표, 매핑 설정", "보호 계획 생성 및 실행 가능 상태 확인"],
                    ["동기화", "Start Sync 실행", "복구 지점과 RPO 상태가 갱신되는지 확인"],
                    ["테스트", "테스트 페일오버 실행", "DR 사이트에서 복구 가능성 검증"],
                    ["전환", "계획 또는 재해 페일오버 실행", "서비스 기동과 애플리케이션 확인"],
                    ["복귀", "페일백 또는 재보호 선택", "원 운영 방향 또는 새 운영 방향의 보호 상태 확인"],
                ],
                [36 * mm, 101 * mm, 110 * mm],
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("4. RPO와 RTO 이해"))
    story.extend(
        [
            RpoRtoDiagram(),
            make_table(
                [
                    ["지표", "의미", "고객이 확인할 내용"],
                    ["RPO", "장애 발생 시 허용 가능한 데이터 손실 시간", "복제 주기, 변경 데이터량, 네트워크 대역폭, 스토리지 성능"],
                    ["RTO", "장애 후 서비스를 다시 사용할 수 있을 때까지 걸리는 시간", "VM 기동 자동화, 네트워크 전환, DNS/LB 변경, 운영 승인 절차"],
                ],
                [30 * mm, 92 * mm, 125 * mm],
            ),
            Spacer(1, 5 * mm),
            make_table(
                [
                    ["영향 요소", "RPO 영향", "RTO 영향"],
                    ["디스크 크기와 변경량", "변경량이 많으면 복제 완료 시간이 늘어난다.", "복구 지점 선택과 검증 시간이 늘어날 수 있다."],
                    ["센터 간 네트워크", "대역폭과 지연 시간이 복제 속도를 좌우한다.", "장애 시 운영자 접속과 서비스 전환 시간에 영향을 준다."],
                    ["대상 스토리지 성능", "DR 사이트에 데이터를 기록하는 시간이 늘어날 수 있다.", "VM 부팅과 초기 I/O 성능에 영향을 준다."],
                    ["VM 자동 생성/기동", "직접 영향은 작다.", "자동화 수준이 낮으면 복구 시간이 길어진다."],
                    ["운영 절차", "복제 자체에는 직접 영향이 작다.", "승인, 네트워크 전환, 애플리케이션 확인 절차가 RTO에 포함된다."],
                ],
                [48 * mm, 101 * mm, 98 * mm],
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("5. 도입 전 확인해야 할 사항"))
    story.extend(
        [
            make_table(
                [
                    ["영역", "확인 항목"],
                    ["보호 대상", "어떤 업무 VM을 보호할지, 우선순위와 중요도를 정한다."],
                    ["목표 지표", "업무별 목표 RPO와 목표 RTO를 정한다."],
                    ["사이트 구성", "운영 사이트와 DR 사이트의 플랫폼, 네트워크, 스토리지 용량을 확인한다."],
                    ["접속 정보", "ABLESTACK, VMware, 스토리지, 네트워크 장비 접근 권한과 보안 정책을 확인한다."],
                    ["데이터 전송", "센터 간 전용망, 방화벽, 포트, 대역폭, 암호화 요구사항을 확인한다."],
                    ["디스크 매핑", "운영 VM 디스크가 DR 사이트의 어떤 디스크 또는 데이터스토어에 대응되는지 정의한다."],
                    ["네트워크 매핑", "DR 전환 시 IP, VLAN, 보안그룹, 방화벽, DNS/LB 전환 방식을 정한다."],
                    ["복구 검증", "테스트 페일오버 주기와 검증 담당자를 정한다."],
                    ["운영 절차", "페일오버 승인, 페일백 승인, 비상 연락망, 점검 체크리스트를 준비한다."],
                ],
                [42 * mm, 205 * mm],
            ),
            Spacer(1, 5 * mm),
            p("도입 검토 단계에서는 기능 데모보다 고객 업무 VM 기준의 실제 복제 시간과 복구 시간을 측정하는 것이 중요하다.", "SubSectionKo"),
        ]
    )

    story.append(PageBreak())
    story.extend(section("6. 시나리오별 설명 포인트"))
    scenario_rows = [
        ["ABLESTACK -> VMware", "기존 VMware DR 자산을 활용하면서 ABLESTACK 운영 VM을 보호한다. VMware 대상 데이터스토어, 네트워크, VM 배치 정책이 준비되어야 한다."],
        ["VMware -> VMware", "기존 VMware 운영 경험을 유지하면서 별도 VMware DR 센터를 구성한다. vCenter, 데이터스토어, 네트워크 매핑이 핵심이다."],
        ["ABLESTACK -> ABLESTACK", "운영과 DR 모두 ABLESTACK 표준으로 관리한다. 대상 스토리지 용량, 디스크 매핑, 센터 간 네트워크 성능이 중요하다."],
        ["VMware -> ABLESTACK", "VMware 운영 환경을 유지하면서 DR 센터를 ABLESTACK으로 구성한다. 대상 ABLESTACK VM 스펙, 스토리지, 네트워크 매핑이 필요하다."],
    ]
    story.append(make_table([["시나리오", "고객 설명 포인트"], *scenario_rows], [52 * mm, 195 * mm]))
    story.append(Spacer(1, 6 * mm))
    story.extend(
        [
            p("단순 이관 도구와 DR은 목적이 다르다.", "SubSectionKo"),
            p("이관 도구는 한 번의 전환을 목표로 하지만, DR은 지속적인 변경 데이터 반영, 복구 지점 관리, 테스트 페일오버, 실제 페일오버, 페일백까지 포함해야 한다. 따라서 고객에게는 마이그레이션과 DR의 차이를 분명히 설명해야 한다."),
        ]
    )

    story.append(PageBreak())
    story.extend(section("7. 기대효과와 PoC 절차"))
    story.extend(
        [
            make_table(
                [
                    ["기대효과", "고객 가치"],
                    ["이기종 환경 DR 표준화", "ABLESTACK와 VMware가 섞인 환경에서도 하나의 DR 운영 절차를 만들 수 있다."],
                    ["운영 복잡도 감소", "사이트, 보호 계획, 복구 지점, 실행 이력을 UI에서 일관되게 확인한다."],
                    ["재해 대응 신뢰도 향상", "테스트 페일오버로 실제 장애 전 복구 가능성을 검증한다."],
                    ["인프라 선택권 확대", "운영/DR 사이트를 같은 플랫폼으로만 맞출 필요가 줄어든다."],
                    ["감사와 보고 용이성", "실행 이력과 복구 지점 정보로 DR 훈련 결과를 설명하기 쉽다."],
                ],
                [64 * mm, 183 * mm],
            ),
            Spacer(1, 5 * mm),
            FlowDiagram(
                [
                    "대상 업무 선정",
                    "목표 RPO/RTO 정의",
                    "사이트 연결성 확인",
                    "보호 계획 생성",
                    "초기 동기화",
                    "RPO 측정",
                    "테스트 페일오버",
                    "결과 보고",
                ],
                title="고객 PoC 권장 절차",
                rows=2,
                fill=colors.white,
            ),
        ]
    )

    story.append(PageBreak())
    story.extend(section("8. 도입 상담 시 확인 질문"))
    story.extend(
        [
            make_table(
                [
                    ["질문", "목적"],
                    ["보호해야 할 업무 VM은 몇 대이며, 중요도는 어떻게 나뉘는가?", "보호 범위와 우선순위 산정"],
                    ["목표 RPO/RTO는 업무별로 얼마인가?", "복제 주기와 인프라 성능 설계"],
                    ["운영 사이트와 DR 사이트의 가상화 플랫폼은 무엇인가?", "4개 DR 시나리오 중 적용 방향 결정"],
                    ["센터 간 네트워크 대역폭과 지연 시간은 어느 정도인가?", "복제 성능 검토"],
                    ["DR 사이트의 스토리지 용량과 성능은 충분한가?", "복구 지점 보관과 VM 기동 성능 검토"],
                    ["장애 시 IP, DNS, 방화벽, LB 전환은 누가 수행하는가?", "실제 RTO 산정"],
                    ["정기 DR 훈련 주기는 어떻게 운영할 것인가?", "테스트 페일오버 운영 계획"],
                    ["페일백까지 자동화가 필요한가?", "복구 이후 운영 정상화 범위 결정"],
                ],
                [138 * mm, 109 * mm],
            ),
            Spacer(1, 8 * mm),
            p("결론", "SectionKo"),
            p("ABLESTACK Cross Hypervisor DR은 ABLESTACK와 VMware가 함께 존재하는 현실적인 고객 환경에서 DR 운영을 단순화하고 표준화하기 위한 접근이다. 도입 시에는 기능 제공 여부뿐 아니라 목표 RPO/RTO, 네트워크, 스토리지, VM 기동 자동화, 운영 승인 절차를 함께 검토해야 한다."),
        ]
    )
    return story


def main():
    doc = SimpleDocTemplate(
        str(OUTPUT_PDF),
        pagesize=landscape(A4),
        rightMargin=18 * mm,
        leftMargin=18 * mm,
        topMargin=15 * mm,
        bottomMargin=15 * mm,
        title="ABLESTACK Cross Hypervisor DR 고객 안내서",
        author="ABLESTACK",
    )
    doc.build(build_story(), onFirstPage=on_page, onLaterPages=on_page)
    print(OUTPUT_PDF)


if __name__ == "__main__":
    main()
