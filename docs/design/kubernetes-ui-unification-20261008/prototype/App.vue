<!-- Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership. The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not use
this file except in compliance with the License. You may obtain a copy at
http://www.apache.org/licenses/LICENSE-2.0 . Unless required by applicable law or
agreed to in writing, software distributed under the License is distributed on
an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND. See the License
for the specific language governing permissions and limitations. -->
<template>
  <a-config-provider :locale="koKR">
    <div v-if="!capture" class="review-bar">
      <strong>Kubernetes UI 설계 검토</strong><a-tag color="blue">Vue 3 + Ant Design Vue</a-tag>
      <a-select v-model:value="sceneId" :options="sceneOptions" style="min-width:330px" @change="navigate" />
      <a :href="url(scene.id, !dark ? 'dark' : 'light')">{{ dark ? '라이트' : '다크' }} 테마</a>
      <a href="?s=gallery">전체 목업 목차</a>
    </div>
    <div v-if="sceneId === 'gallery'" class="gallery">
      <h1>Kubernetes UI 설계 목업</h1>
      <p>가상머신 목록·상세 레퍼런스 · 운영 API 호출 없는 예시 데이터 · 각 화면은 라이트/다크로 검토할 수 있습니다.</p>
      <div class="gallery-grid"><a-card v-for="item in scenes" :key="item.id" :title="item.title"><a :href="url(item.id, 'light')">라이트 목업</a> · <a :href="url(item.id, 'dark')">다크 목업</a><p>{{ item.source || 'ListView / ResourceLayout / ResourceView' }}</p><a v-if="['list','events','nodes','comments'].includes(item.id)" :href="url(item.id,'light')+'&sample=pages&page=3'">복수 페이지 목업</a></a-card></div>
    </div>
    <div v-else class="mold-shell">
      <aside class="mold-sidebar">
        <div class="mold-logo">☁ ABLESTACK</div>
        <div class="nav-label">◉ &nbsp; 대시보드</div><div class="nav-group"><cloud-outlined /> &nbsp; 컴퓨트</div>
        <a class="nav-item" :href="url('list')"><cloud-server-outlined /> &nbsp; 가상머신</a>
        <div class="nav-item">▣ &nbsp; VM 스냅샷</div>
        <a class="nav-item selected" :href="url('list')">⎈ &nbsp; 쿠버네티스</a>
        <div class="nav-item">↔ &nbsp; 오토스케일 VM 그룹</div><div class="nav-item">♧ &nbsp; 가상머신 그룹</div><div class="nav-item">⚿ &nbsp; SSH 키 쌍</div><div class="nav-item">☷ &nbsp; CNI 구성</div><div class="nav-item">⇄ &nbsp; Affinity 그룹</div>
        <div class="nav-group"><hdd-outlined /> &nbsp; 스토리지</div><div class="nav-group">⌘ &nbsp; 네트워크</div><div class="nav-group">▧ &nbsp; 이미지</div>
        <a :href="url('iso-list')" class="nav-item">⎈ &nbsp; Kubernetes ISO</a><div class="nav-group">◷ &nbsp; 이벤트</div><div class="nav-group">⚙ &nbsp; 구성</div>
      </aside>
      <section class="mold-workspace">
        <header class="mold-header"><menu-fold-outlined /><a-select value="기본 보기" style="width:400px" :options="[{value:'기본 보기'}]" /><span class="header-spacer"/><a-button type="primary">생성 <down-outlined /></a-button><translation-outlined /><bell-outlined /><a-avatar :size="24">DA</a-avatar><span>demo-admin</span></header>
        <div class="view-toolbar">
          <a-breadcrumb><a-breadcrumb-item><home-outlined /></a-breadcrumb-item><a-breadcrumb-item>{{ isIso ? 'Kubernetes ISO' : '쿠버네티스' }}</a-breadcrumb-item><a-breadcrumb-item v-if="!isList">{{ isIso ? 'Mold-Kubernetes-1.37.1-amd64-r1' : 'k8s-app-demo' }}</a-breadcrumb-item></a-breadcrumb>
          <question-circle-outlined /><a-button shape="round"><reload-outlined /> 업데이트</a-button>
          <a-select v-if="isList" value="모두" :options="[{value:'모두'}, {value:'Mold 관리형'}, {value:'외부 관리형'}]" style="width:130px"/>
          <a-switch v-if="isList" checked-children="프로젝트" un-checked-children="프로젝트" />
          <span class="toolbar-spacer"/>
          <a-button v-if="isList" type="primary" shape="round" :href="url(isIso ? 'iso-register' : 'create-basic')"><plus-outlined/> {{ isIso ? 'ISO 등록' : '클러스터 생성' }}</a-button>
          <a-button v-else type="primary" @click="menuOpen=!menuOpen">작업 <down-outlined /></a-button>
          <a-button v-if="isList" aria-label="필터"><filter-outlined/></a-button><a-input-search v-if="isList" placeholder="검색" class="list-search" />
        </div>
        <a-card v-if="menuOpen" class="action-menu"><a v-for="item in actionScenes" :key="item.id" :href="url(item.id)">{{ item.title }}</a></a-card>
        <main class="resource-main">
          <template v-if="isList">
            <a-table class="prototype-list-table" :columns="listColumns" :data-source="visibleListRows" :row-selection="isIso?null:{selectedRowKeys:selectedRows,onChange:keys=>selectedRows=keys,columnWidth:30}" row-key="id" size="middle" :pagination="false" :row-class-name="(_,index)=>index%2===0?'light-row':'dark-row'">
              <template #bodyCell="{ column, text, record }">
                <span v-if="column.key==='name'" class="list-resource-name"><kubernetes-icon class="list-resource-symbol"/><a :href="url(isIso ? 'iso-detail' : 'detail')">{{text}}</a><a-button v-if="!isIso" type="text" shape="circle" size="small" aria-label="빠른 작업" class="list-quick-action" @click="message.info('목업: 실제 작업은 호출하지 않습니다.')"><more-outlined/></a-button></span>
                <status v-else-if="column.key==='state'" :text="record.state==='실행 중'?'Running':record.state==='사용'?'Enabled':'Stopped'" :display-label="text" display-text :show-tooltip="false"/>
                <span v-else-if="column.key==='resources' && record.type==='외부 관리형'">N/A</span><div v-else-if="column.key==='resources'" class="list-resource-summary"><span class="list-resource-item"><font-awesome-icon :icon="faMicrochip" class="list-cpu-icon"/>{{record.cpu}} CPU</span><span class="list-resource-item"><font-awesome-icon :icon="faMemory" class="list-memory-icon"/>{{record.memory}} MB</span></div>
                <span v-else-if="column.key==='autoscale'">{{text}}</span>
                <a-tag v-else-if="column.key === 'isostate'" color="green">{{ text }}</a-tag>
                <a v-else-if="['account','zone'].includes(column.key)">{{ text }}</a><span v-else>{{ text }}</span>
              </template>
              <template #customFilterDropdown><div class="filter-dropdown" style="padding:8px"><a-menu><a-menu-item v-for="c in (isIso?isoColumns:clusterColumns)" :key="c.key" @click="toggleColumn(c.key)"><a-checkbox :checked="!hiddenColumns.includes(c.key)"/> {{c.title}}</a-menu-item></a-menu></div></template>
            </a-table>
            <a-pagination class="row-element prototype-list-pagination" style="margin-top:10px" size="small" :current="page" :page-size="pageSize" :total="listRows.length" :show-total="showListTotal" :page-size-options="pageSizeOptions" show-size-changer show-quick-jumper @change="changeListPage" @show-size-change="changeListPage">
              <template #buildOptionText="props"><span>{{props.value}} / 쪽</span></template>
            </a-pagination>
            <div v-if="selectedRows.length" class="selection-actions"><span>{{ selectedRows.length }}개 선택</span><a-button :href="url('bulk-start')">시작</a-button><a-button :href="url('bulk-stop')">중지</a-button><a-button danger :href="url('bulk-delete')">삭제</a-button></div>
          </template>
          <resource-layout v-else>
            <template #left>
              <a-card class="info-card">
                <div class="resource-name"><span class="k8s-mark">⎈</span><strong>{{ isIso ? 'Mold-Kubernetes-1.37.1-amd64-r1' : 'k8s-app-demo' }}</strong></div>
                <a-space wrap><a-tag>{{ isIso ? 'ISO · x86_64' : 'Mold 관리형' }}</a-tag><a-tag color="blue">{{ isIso ? '1.37.1' : '1.35.9' }}</a-tag><a-tag>mold-cks</a-tag></a-space><a-divider />
                <div v-for="item in info" :key="item[0]" class="info-item"><strong>{{ item[0] }}</strong><div><a-badge v-if="item[0]==='상태'" status="success" :text="item[1]"/><a v-else-if="item[0].includes('템플릿')||item[0]==='아이디'">{{item[1]}}</a><span v-else>{{ item[1] }}</span></div></div>
              </a-card>
            </template>
            <template #right>
              <a-card class="resource-content-card">
                <a-tabs :active-key="activeTab" :tab-position="mobile ? 'top' : 'left'" :animated="false" @change="changeTab">
                  <a-tab-pane v-for="tab in tabs" :key="tab.id" :tab="tab.title">
                    <div class="detail-tab-toolbar">
                      <a-button v-if="tab.id==='nodes'" type="primary" :href="url('add-nodes')"><template #icon><plus-outlined/></template>외부 노드 추가</a-button>
                      <a-button v-if="tab.id==='access'" type="primary" @click="primary"><template #icon><download-outlined/></template>구성 다운로드</a-button>
                      <a-button v-if="tab.id==='firewall'" type="primary" :href="url('network-tags')"><template #icon><setting-outlined/></template>태그 편집</a-button>
                      <a-button v-if="tab.id==='portforwarding'" type="primary" :href="url('pf-vm')"><template #icon><plus-outlined/></template>VM 선택</a-button>
                      <a-button v-if="tab.id==='comments'" type="primary" @click="primary"><template #icon><plus-outlined/></template>코멘트 추가</a-button>
                      <a-button v-if="isIso && tab.id==='details'" type="primary" :href="url('iso-state')"><template #icon><setting-outlined/></template>상태 변경</a-button>
                      <a-button v-if="tab.id==='portforwarding'" :href="url('network-tags')"><template #icon><setting-outlined/></template>태그 편집</a-button>
                      <a-button @click="message.info('목업: 현재 탭의 예시 데이터를 다시 표시합니다.')"><template #icon><reload-outlined/></template>업데이트</a-button>
                    </div>
                    <template v-if="tab.id==='details'"><div v-for="item in detailRows" :key="item[0]" class="detail-row"><strong>{{item[0]}}</strong><p>{{item[1]}}</p></div></template>
                    <template v-else-if="tab.id==='nodes'"><p>노드 {{detailTotal('nodes')}}개</p><a-table :columns="nodeColumns" :data-source="detailData('nodes')" row-key="id" size="small" :pagination="false" :scroll="{x:790}"><template #bodyCell="{column,text,record}"><a v-if="column.key==='name'">{{text}}</a><a-badge v-else-if="column.key==='state'" status="success" :text="text"/><a-button v-else-if="column.key==='action' && record.role==='워커'" size="small" disabled title="AutoScaler 사용 중에는 수동 노드 삭제를 제한합니다.">삭제</a-button><span v-else>{{text}}</span></template></a-table><a-alert type="info" show-icon message="노드 역할·실제 버전·VM 상태·SSH 포트를 구분합니다." class="section-space"/></template>
                    <template v-else-if="tab.id==='access'"><a-card title="클러스터 구성 (kubeconfig)" class="section-space"><p>클러스터에 접근할 수 있는 인증 정보입니다. 필요한 경우 다운로드해서 사용하세요.</p><a-button @click="primary">구성 표시</a-button></a-card><a-card title="kubectl 사용" class="section-space"><p>선택한 Kubernetes 버전의 kubectl을 사용합니다.</p><a-space><a>Linux</a><a>macOS</a><a>Windows</a></a-space><pre>kubectl --kubeconfig ./kube.conf get nodes</pre></a-card><a-card title="Headlamp 대시보드" class="section-space"><p>로컬 port-forward → 읽기 전용 토큰으로 로그인 → 사용 후 정리</p><a-tag color="blue">한국어</a-tag><a-tag>읽기 전용</a-tag><a-tag>15분 토큰</a-tag><a-collapse class="section-space"><a-collapse-panel key="1" header="접속·권한·정리 명령 보기"><p>토큰 생성과 정리는 운영자가 안내에 따라 수행합니다. Mold가 임의로 인증 정보를 발급하지 않습니다.</p></a-collapse-panel></a-collapse></a-card></template>
                    <template v-else-if="tab.id==='loadbalancers'"><a-alert type="info" show-icon message="Mold가 관측한 네트워크 자원입니다. native 서비스 건강 상태를 보장하지 않습니다."/><a-table class="section-space" :columns="lbColumns" :data-source="detailData('loadbalancers')" size="small" row-key="id" :pagination="false" :scroll="{x:750}"/><a-alert class="section-space" type="warning" show-icon message="관리 포트 및 Service UID로 식별한 규칙은 직접 삭제하지 않습니다."/></template>
                    <template v-else-if="['firewall','portforwarding'].includes(tab.id)"><p>사용자 규칙</p><a-alert type="info" show-icon message="Kubernetes API·SSH 관리 포트 규칙은 보호됩니다."/><a-table class="section-space" :columns="ruleColumns" :data-source="detailData(tab.id)" size="small" row-key="id" :pagination="false" :scroll="{x:710}"/><a-button danger :href="url('rules-delete')" class="section-space">사용자 규칙 삭제</a-button></template>
                    <template v-else-if="tab.id==='events'"><a-input-search placeholder="이벤트 검색" style="max-width:350px"/><a-table class="section-space" :columns="eventColumns" :data-source="detailData('events')" size="middle" :row-class-name="(_,index)=>index%2===0?'light-row':'dark-row'" row-key="id" :pagination="false"/></template>
                    <template v-else-if="tab.id==='comments'"><a-textarea placeholder="코멘트 입력" :rows="3"/><a-divider/><strong>코멘트 ({{detailTotal('comments')}})</strong><a-list size="small" :data-source="detailData('comments')" :pagination="false"><template #renderItem="{item}"><a-list-item><div><strong>{{item.author}}</strong><p>{{item.content}}</p><a :href="url('comment-visibility')">공개 범위 변경</a> · <a :href="url('comment-delete')">삭제</a></div></a-list-item></template></a-list></template>
                    <a-pagination v-if="detailListTabs.includes(tab.id)" class="detail-tab-pagination" size="small" :current="detailPage" :page-size="detailPageSize" :total="detailTotal(tab.id)" :show-total="showDetailTotal" :page-size-options="tab.id==='comments'?['10']:pageSizeOptions" :show-size-changer="tab.id!=='comments'" show-quick-jumper @change="changeDetailPage" @show-size-change="changeDetailPage"><template #buildOptionText="props"><span>{{props.value}} / 쪽</span></template></a-pagination>
                  </a-tab-pane>
                </a-tabs>
              </a-card>
            </template>
          </resource-layout>
        </main>
      </section>
    </div>
    <mold-dialog v-if="scene.kind==='dialog'" :visible="true" :title="scene.title" :width="scene.width||760" @cancel="cancel">
      <div v-if="scene.create" class="creation-progress"><a-steps :current="scene.step" size="small"><a-step v-for="t in creationSteps" :key="t" :title="t"/></a-steps></div>
      <div :class="{'creation-body':scene.create}">
        <section>
          <a-descriptions v-if="scene.summary && !scene.create" class="mold-dialog-summary" :column="mobile?1:2" size="small" bordered><a-descriptions-item v-for="row in scene.summary" :key="row[0]" :label="row[0]">{{row[1]}}</a-descriptions-item></a-descriptions>
          <a-alert v-if="scene.alert" :type="scene.alertType||'info'" show-icon :message="scene.alert" class="section-space"/>
          <h3 v-if="scene.section">{{scene.section}}</h3>
          <div v-if="scene.cards" class="cards-grid"><a-card v-for="row in scene.cards" :key="row[0]" size="small"><span class="muted">{{row[0]}}</span><strong>{{row[1]}}</strong></a-card></div>
          <a-form layout="vertical" class="dialog-form">
            <a-form-item v-if="scene.create && scene.step===0" label="관리 유형" required><a-radio-group :value="scene.external?'ExternalManaged':'CloudManaged'" @change="e=>navigate(e.target.value==='ExternalManaged'?'create-external-basic':'create-basic')"><a-radio-button value="CloudManaged">Mold 관리형</a-radio-button><a-radio-button value="ExternalManaged">외부 관리형</a-radio-button></a-radio-group></a-form-item>
            <a-row :gutter="20"><a-col v-for="(field,index) in scene.fields||[]" :key="field.label" :xs="24" :md="scene.width>=1000 && !['upload','password'].includes(field.type)?12:24">
              <a-form-item :label="field.label" :required="field.required" :validate-status="field.error?'error':''" :help="field.error" :name="'field-'+index">
                <a-select v-if="field.type==='multi'" v-model:value="field.value" mode="multiple" :options="field.value.map(v=>({value:v,label:v}))"/>
                <a-select v-else-if="field.type==='select'" v-model:value="field.value" :disabled="field.disabled" :options="[{value:field.value,label:field.value}]"/>
                <a-input-number v-else-if="field.type==='number'" v-model:value="field.value" :disabled="field.disabled" :min="0" style="width:100%"/>
                <a-switch v-else-if="field.type==='switch'" v-model:checked="field.value" :disabled="field.disabled" checked-children="사용" un-checked-children="사용 안 함"/>
                <a-checkbox v-else-if="field.type==='checkbox'" v-model:checked="field.value">{{field.label}}</a-checkbox>
                <a-input-password v-else-if="field.type==='password'" value="example-secret"/>
                <a-upload-dragger v-else-if="field.type==='upload'" :before-upload="()=>false" :file-list="[]"><p><global-outlined/></p><p>{{field.value}}</p><p class="muted">파일을 선택하거나 이곳에 끌어다 놓으세요.</p></a-upload-dragger>
                <a-input v-else v-model:value="field.value" :disabled="field.disabled"/>
              </a-form-item>
            </a-col></a-row>
          </a-form>
          <a-table v-if="scene.nodeTable" :columns="selectNodeColumns" :data-source="externalNodes" row-key="id" :row-selection="{}" :pagination="false" :scroll="{x:650}"/>
          <a-table v-if="scene.ruleTable" :columns="ruleColumns" :data-source="rules.slice(1)" row-key="id" :pagination="false" :scroll="{x:650}"/>
          <a-table v-if="scene.bulk" :columns="bulkColumns" :data-source="bulkRows" row-key="id" :pagination="false" :scroll="{x:650}"/>
          <div v-if="scene.checks" class="checks section-space"><p v-for="check in scene.checks" :key="check"><check-circle-outlined/> {{check}}</p></div>
          <a-alert v-if="scene.hint" type="info" show-icon :message="scene.hint" class="section-space"/>
        </section>
        <aside v-if="scene.create" class="creation-summary"><h3>클러스터 요약</h3><div class="resource-name"><span class="k8s-mark">⎈</span><strong>{{scene.external?'k8s-external-demo':'k8s-app-demo'}}</strong></div><a-divider/><div v-for="row in createSummary" :key="row[0]" class="info-item"><strong>{{row[0]}}</strong><div>{{row[1]}}</div></div></aside>
      </div>
      <template #footer><a-button @click="cancel">취소</a-button><a-button v-if="scene.create&&scene.step>0" @click="navigate(createScenes[scene.step-1])">이전</a-button><a-button type="primary" :danger="scene.danger" :disabled="scene.disabled" @click="primary">{{scene.button||'저장'}}</a-button></template>
    </mold-dialog>
  </a-config-provider>
</template>
<script>
import { reactive, computed, ref } from 'vue'
import { useStore } from 'vuex'
import { message } from 'ant-design-vue'
import koKR from 'ant-design-vue/es/locale/ko_KR'
import MoldDialog from '@/components/view/MoldDialog.vue'
import ResourceLayout from '@/layouts/ResourceLayout.vue'
import sceneData from './scenes.json'
import KubernetesIcon from '@/assets/icons/kubernetes.svg?inline'
import Status from '@/components/widgets/Status.vue'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import { faMicrochip, faMemory } from '@fortawesome/free-solid-svg-icons'
import { MoreOutlined } from '@ant-design/icons-vue'
const col = (title,key,width) => ({title,key,dataIndex:key,width})
export default {
  components: { MoldDialog, ResourceLayout, KubernetesIcon, Status, FontAwesomeIcon, MoreOutlined },
  setup () {
    const params = new URLSearchParams(window.location.search)
    const sceneId = ref(params.get('s')||'gallery')
    const scenes = reactive(JSON.parse(JSON.stringify(sceneData)))
    const scene = computed(() => scenes.find(x=>x.id===sceneId.value)||scenes[0])
    const dark = params.get('theme')==='dark'
    const capture = params.get('capture')==='1'
    const store = useStore()
    const mobile = computed(()=>store.state.app.device==='mobile')
    const url = (id,theme=dark?'dark':'light')=>'?s='+id+'&theme='+theme+(capture?'&capture=1':'')
    const navigate = id=>{window.location.search=url(id)}
    const isIso = computed(()=>sceneId.value.startsWith('iso-')||sceneId.value==='validation-error')
    const isList = computed(()=>scene.value.create||['list','iso-list','iso-register','iso-upload','validation-error'].includes(sceneId.value))
    const cancel = ()=>navigate(isIso.value ? (isList.value?'iso-list':'iso-detail') : (isList.value?'list':'detail'))
    const activeTab = computed(()=>scene.value.tab||'details')
    const tabs = computed(()=>isIso.value?[{id:'details',title:'상세'},{id:'events',title:'이벤트'}]:[{id:'details',title:'상세'},{id:'access',title:'액세스'},{id:'nodes',title:'가상머신'},{id:'firewall',title:'방화벽'},{id:'portforwarding',title:'포트 포워딩'},{id:'loadbalancers',title:'부하 분산'},{id:'events',title:'이벤트'},{id:'comments',title:'코멘트'}])
    const managedCreateScenes=['create-basic','create-nodes','create-advanced','create-addons','create-review']
    const externalCreateScenes=['create-external-basic','create-external-options','create-external-review']
    const createScenes=computed(()=>scene.value.external?externalCreateScenes:managedCreateScenes)
    const creationSteps=computed(()=>scene.value.external?['기본 정보','선택 정보','검토']:['기본 정보','노드·스토리지','네트워크·역할','선택 구성','검토'])
    const primary=()=>scene.value.create&&scene.value.step<createScenes.value.length-1?navigate(createScenes.value[scene.value.step+1]):message.info('설계 목업: 운영 API를 호출하지 않습니다.')
    const info = computed(()=>isIso.value?[['상태','사용'],['Kubernetes','1.37.1'],['ISO 다운로드','Ready'],['아키텍처','x86_64'],['Zone','Zone-A'],['최소 자원','2 vCPU · 2,048 MB'],['ISO 프로파일','mold-cks · 기본']]:[['상태',['start','affinity','bulk-start'].includes(sceneId.value)?'중지됨':sceneId.value==='scale-recovery'?'부분 실패':'실행 중'],['아이디','demo-cluster-uuid'],['API 엔드포인트','https://203.0.113.10:6443'],['할당 자원 합계','12 vCPU · 24,576 MB'],['노드 구성','제어 1 · 워커 2 · 외부 etcd 0'],['제어 노드 템플릿','app-node-template'],['워커 노드 템플릿','app-node-template'],['네트워크','app-isolated-network'],['AutoScaler 사용 설정',['scale','delete-worker'].includes(sceneId.value)?'사용 안 함':'사용 · 최소 2 / 최대 3'],['CSI 사용 설정','사용 안 함'],['계정 / Zone','demo-team / Zone-A']])
    const detailRows = computed(()=>isIso.value?[['등록 이름','Mold-Kubernetes-1.37.1-amd64-r1'],['Kubernetes 버전','1.37.1'],['지원 버전 상태','사용'],['ISO 다운로드 상태','Ready'],['ISO 프로파일','기본 mold-cks · CSI 별도 설치'],['검증 대상','ABLESTACK Europa'],['참조 ISO UUID','demo-iso-uuid'],['등록 URL','GitHub 공식 Release의 고정 다운로드 URL'],['최소 자원','2 vCPU · 2,048 MB'],['아키텍처 / Zone','x86_64 / Zone-A']]:[['이름','k8s-app-demo'],['설명','애플리케이션 운영 클러스터'],['Kubernetes 버전','1.35.9'],['관리 유형','Mold 관리형'],['AutoScaler 사용 설정',['scale','delete-worker'].includes(sceneId.value)?'사용 안 함':'사용 · 최소 2 / 최대 3'],['AutoScaler 런타임 관측','현재 제공된 관측 정보 없음'],['CSI 사용 설정','사용 안 함 · 기본 ISO에서 별도 설치'],['노드 구성','제어 1 · 워커 2 · 외부 etcd 0'],['할당 자원 합계','12 vCPU · 24,576 MB'],['스토리지','Primary · GFS2'],['SSH 키 쌍 / CNI','app-team-key / Calico'],['계정 / 도메인 / Zone','demo-team / ROOT / Zone-A']])
    const clusterColumns=[col('이름','name',230),col('상태','state',110),col('할당 자원 합계','resources',225),col('워커 / 제어','nodes',115),col('Kubernetes','version',120),col('AutoScaler 설정','autoscale',180),col('관리 유형','type',130),col('계정','account',120),col('Zone','zone',110)]
    const clusterRows=[['k8s-app-demo','실행 중','1.35.9','사용','Mold 관리형'],['k8s-batch-demo','실행 중','1.37.1','사용','Mold 관리형'],['k8s-test-demo','중지됨','1.34.12','사용 안 함','Mold 관리형'],['k8s-external-demo','실행 중','1.36.5','외부 관리','외부 관리형']].map((r,i)=>({id:i,name:r[0],state:r[1],version:r[2],autoscale:r[3],type:r[4],cpu:12,memory:'24,576',nodes:'2 / 1',account:'demo-team',zone:'Zone-A'}))
    const isoColumns=[col('등록 이름','name',340),col('상태','state',100),col('Kubernetes','version',125),col('ISO 다운로드','isostate',130),col('최소 CPU','cpu',100),col('최소 메모리','memory',130),col('아키텍처','arch',125),col('Zone','zone',100)]
    const isoRows=['1.34.2','1.34.9','1.34.12','1.35.9','1.36.5','1.37.1'].map((v,i)=>({id:i,name:'Mold-Kubernetes-'+v+'-amd64-r1',version:v,state:'사용',isostate:'Ready',cpu:'2 vCPU',memory:'2,048 MB',arch:'x86_64',zone:'Zone-A'}))
    const hiddenColumns=ref([])
    const selectedRows=ref([])
    const page=ref(Math.max(1,Number(params.get('page'))||1))
    const pageSize=ref(20)
    const listRows=computed(()=>isIso.value?isoRows:params.get('sample')==='pages'?Array.from({length:42},(_,i)=>({...clusterRows[i%clusterRows.length],id:i,name:'k8s-page-demo-'+String(i+1).padStart(2,'0')})):clusterRows)
    const visibleListRows=computed(()=>listRows.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
    const pageSizeOptions=computed(()=>[...(store.state.app.device==='desktop'?[]:[10]),20,50,100,200].map(String))
    const showListTotal=total=>'전체 '+total+' 개 항목 중 '+Math.min(total,1+(page.value-1)*pageSize.value)+'-'+Math.min(page.value*pageSize.value,total)+' 표시'
    const changeListPage=(newPage,newSize)=>{pageSize.value=newSize;page.value=Math.max(1,Math.min(newPage,Math.ceil(listRows.value.length/newSize)));selectedRows.value=[]}
    const toggleColumn=key=>{hiddenColumns.value=hiddenColumns.value.includes(key)?hiddenColumns.value.filter(k=>k!==key):[...hiddenColumns.value,key]}
    const listColumns=computed(()=>[...(isIso.value?isoColumns:clusterColumns).filter(c=>!hiddenColumns.value.includes(c.key)).map(({width,...c})=>({...c,sorter:(a,b)=>String(a[c.key]??'').localeCompare(String(b[c.key]??''),undefined,{numeric:true})})),{key:'filtercolumn',dataIndex:'filtercolumn',title:'',customFilterDropdown:true,width:5}])
    const nodeColumns=[col('VM 표시 이름','name',180),col('역할','role',85),col('VM 상태','state',100),col('Kubernetes','version',115),col('주소','ip',130),col('SSH 포트','ssh',110),col('작업','action',70)]
    const nodes=[{id:1,name:'control-01',role:'제어',state:'실행 중',version:'1.35.9',ip:'192.0.2.10',ssh:'2201'},{id:2,name:'worker-01',role:'워커',state:'실행 중',version:'1.35.9',ip:'192.0.2.11',ssh:'2202'},{id:3,name:'worker-02',role:'워커',state:'실행 중',version:'1.35.9',ip:'192.0.2.12',ssh:'2203'}]
    const selectNodeColumns=[col('VM 표시 이름','name',170),col('역할','role',95),col('VM 상태','state',110),col('네트워크 주소','ip',130),col('관리 유형','owner',130)]
    const externalNodes=nodes.slice(1).map((n,i)=>({...n,name:'external-worker-0'+(i+1),owner:'외부 노드'}))
    const lbColumns=[col('서비스 / 소유권','name',180),col('공인 주소','ip',150),col('포트','port',85),col('backend VM','backend',150),col('관측 범위','scope',160)]
    const lbRows=[{id:1,name:'API · Mold 관리',ip:'203.0.113.10',port:'6443',backend:'control-01',scope:'Mold 네트워크 규칙'},{id:2,name:'app/web · Service UID',ip:'203.0.113.11',port:'80',backend:'worker-01, 02',scope:'Provider 소유 규칙'}]
    const ruleColumns=[col('프로토콜','protocol',100),col('공인 / 게스트 포트','port',150),col('소스 CIDR','cidr',180),col('소유권 / 보호','owner',160)]
    const rules=[{id:1,protocol:'TCP',port:'6443 / 6443',cidr:'198.51.100.0/24',owner:'Mold 관리 · 보호됨'},{id:2,protocol:'TCP',port:'8080 / 80',cidr:'198.51.100.0/24',owner:'사용자 규칙'}]
    const bulkColumns=[col('클러스터','name',200),col('현재 상태','state',110),col('노드 수','nodes',100),col('작업 가능 여부','eligible',180)]
    const bulkRows=computed(()=>[{id:1,name:'k8s-app-demo',state:'실행 중',nodes:3,eligible:'권한·작업별 상태 재조회'},{id:2,name:'k8s-batch-demo',state:'실행 중',nodes:3,eligible:'권한·작업별 상태 재조회'}].map(row=>({...row,state:sceneId.value==='bulk-start'?'중지됨':row.state,eligible:sceneId.value==='bulk-start'?'시작 가능':sceneId.value==='bulk-stop'?'중지 가능':'삭제 가능'})))
    const eventColumns=[col('시간','time',160),col('작업','action',170),col('상태','state',100),col('내용','description')]
    const eventRows=[{id:1,time:'2026-10-08 11:00',action:'클러스터 확장',state:'완료',description:'워커 목표 수 3 → Ready 3'},{id:2,time:'2026-10-08 10:55',action:'클러스터 확장',state:'실행 중',description:'노드 생성 및 준비 확인'}]
    const detailListTabs=['nodes','loadbalancers','firewall','portforwarding','events','comments']
    const detailPage=ref(Math.max(1,Number(params.get('page'))||1))
    const detailPageSize=ref(activeTab.value==='comments'?10:20)
    const commentRows=[{id:1,author:'demo-admin',content:'운영 점검 기록 (예시)'}]
    const detailAllRows=id=>{const base=({nodes,loadbalancers:lbRows,firewall:rules,portforwarding:rules,events:eventRows,comments:commentRows})[id]||[];return params.get('sample')==='pages'?Array.from({length:id==='comments'?21:42},(_,i)=>({...base[i%base.length],id:i+1,name:base[i%base.length]?.name+'-'+(i+1)})):base}
    const detailData=id=>detailAllRows(id).slice((detailPage.value-1)*detailPageSize.value,detailPage.value*detailPageSize.value)
    const detailTotal=id=>detailAllRows(id).length
    const showDetailTotal=total=>'전체 '+total+' 개 항목 중 '+Math.min(total,1+(detailPage.value-1)*detailPageSize.value)+'-'+Math.min(detailPage.value*detailPageSize.value,total)+' 표시'
    const changeDetailPage=(newPage,newSize)=>{detailPageSize.value=newSize;detailPage.value=Math.max(1,Math.min(newPage,Math.ceil(detailTotal(activeTab.value)/newSize)))}
    const createSummary=computed(()=>scene.value.external?[['관리 유형','외부 관리형'],['Zone / 소유자','Zone-A / demo-team'],['VM 자동 배포','수행하지 않음'],['Kubernetes 설치·업그레이드','외부 운영자가 수행'],['기존 VM 연결','등록 완료 후 별도 작업']]:[['Kubernetes','1.37.1'],['Zone / 관리 유형','Zone-A / Mold 관리형'],['노드','제어 1 · 워커 2'],['할당 자원','12 vCPU · 24,576 MB'],['ROOT 디스크','3 × 40 GiB'],['스토리지','Primary · GFS2'],['CSI','기본 제외 · 별도 설치']].map(row=>scene.value.ha ? ({'노드':['노드','제어 3 · 워커 2 · etcd 3'],'할당 자원':['할당 자원','32 vCPU · 65,536 MB'],'ROOT 디스크':['ROOT 디스크','8 × 40 GiB']}[row[0]]||row):row))
    const changeTab=id=>navigate(isIso.value?'iso-detail':({details:'detail',nodes:'nodes',access:'access',loadbalancers:'loadbalancers',firewall:'firewall',portforwarding:'portforwarding',events:'events',comments:'comments'}[id]))
    return { koKR,message,faMicrochip,faMemory,listColumns,listRows,visibleListRows,page,pageSize,pageSizeOptions,showListTotal,changeListPage,hiddenColumns,toggleColumn,cancel,scenes,scene,sceneId,dark,capture,mobile,url,navigate,primary,isIso,isList,activeTab,tabs,info,detailRows,createSummary,createScenes,creationSteps,detailPage,detailPageSize,detailData,detailTotal,showDetailTotal,changeDetailPage,detailListTabs,commentRows,changeTab,clusterColumns,clusterRows,isoColumns,isoRows,nodeColumns,nodes,selectNodeColumns,externalNodes,lbColumns,lbRows,ruleColumns,rules,bulkColumns,bulkRows,eventColumns,eventRows,selectedRows,menuOpen:ref(false),sceneOptions:scenes.map(s=>({value:s.id,label:s.title})),actionScenes:scenes.filter(s=>s.kind==='dialog'&&!s.create&&!s.id.startsWith('iso-')).slice(0,13) }
  }
}
</script>
<style lang="less">
* { box-sizing: border-box; }
body { margin:0; font-family: -apple-system,BlinkMacSystemFont,"Segoe UI","Malgun Gothic",sans-serif; }
.review-bar { display:flex; gap:16px; padding:12px 20px; align-items:center; background:var(--ui-bg-elevated); border-bottom:1px solid var(--ui-border); flex-wrap:wrap; }
.gallery { padding:32px; }.gallery-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(270px,1fr)); gap:16px; }.gallery p { margin-top:12px; }
.mold-shell { min-height:100vh; display:flex; background:var(--ui-bg-page); }.mold-sidebar { width:240px; flex-shrink:0; background:var(--ui-bg-surface); border-right:1px solid var(--ui-border); }.mold-logo { font-size:28px; letter-spacing:1px; font-weight:650; padding:15px 12px 21px; color:var(--ui-text-primary); }.nav-label,.nav-group { padding:18px 24px; }.nav-group { font-weight:500; }.nav-item { display:block; padding:12px 20px 12px 46px; color:var(--ui-text-secondary); }.nav-item.selected { color:var(--ui-link); background:var(--ui-bg-selected); border-right:3px solid var(--ui-focus); }.mold-workspace { flex:1; min-width:0; }.mold-header { height:64px; display:flex; align-items:center; gap:22px; padding:0 24px; border-bottom:1px solid var(--ui-border); background:var(--ui-bg-surface); }.header-spacer,.toolbar-spacer { flex:1; }.view-toolbar { min-height:86px; display:flex; flex-wrap:wrap; gap:10px; padding:24px 16px; align-items:center; background:var(--ui-bg-surface); border-bottom:1px solid var(--ui-border); }.view-toolbar .ant-breadcrumb { margin-right:4px; }.view-toolbar .ant-breadcrumb-link,.view-toolbar .ant-breadcrumb-separator { color:var(--ui-text-secondary); }.list-search { width:340px; }.resource-main { padding:16px; }.resource-main .ant-table { background:var(--ui-bg-surface); }.allocation { white-space:nowrap; }.small { font-size:12px; }.muted { color:var(--ui-text-muted); }.info-card { min-height:625px; }.resource-name { display:flex; align-items:center; gap:16px; font-size:18px; margin-bottom:14px; overflow-wrap:anywhere; }.k8s-mark { font-size:42px; color:var(--ui-text-primary); }.info-item { margin-bottom:24px; overflow-wrap:anywhere; }.info-item strong { display:block; margin-bottom:7px; }.resource-content-card { min-height:640px; }.resource-content-card>.ant-card-body { padding:24px; }.resource-content-card .ant-tabs-tab { padding:13px 20px; }.detail-row { padding:0 0 14px; margin-bottom:15px; border-bottom:1px solid var(--ui-border); }.detail-row p { margin-top:7px; margin-bottom:0; }.tab-toolbar { display:flex; gap:8px; align-items:center; margin-bottom:16px; }.section-space { margin-top:16px; }.right { text-align:right; }.button-gap { margin-left:8px; }pre { padding:16px; background:var(--ui-bg-input); border:1px solid var(--ui-border); white-space:pre-wrap; border-radius:4px; }.dialog-form { margin-top:20px; }.dialog-form .ant-form-item { margin-bottom:20px; }.checks p { margin-bottom:9px; }.checks .anticon { color:var(--ui-success-icon); }.cards-grid { display:flex; flex-wrap:wrap; gap:12px; margin:16px 0; }.cards-grid .ant-card { flex:1; min-width:155px; }.cards-grid strong { display:block; margin-top:6px; }.creation-progress { margin-bottom:24px; }.creation-body { display:grid; grid-template-columns:minmax(0,1fr) 270px; gap:24px; }.creation-summary { border-left:1px solid var(--ui-border); padding-left:22px; }.creation-summary .info-item { margin-bottom:18px; }.creation-body h3 { margin-top:0; }.mold-dialog .ant-modal-title { font-weight:600; }.mold-dialog .ant-modal-footer { text-align:right; }.mold-dialog .ant-alert-message { line-height:1.6; }.selection-actions { display:flex; gap:10px; margin-top:16px; align-items:center; }.action-menu { position:absolute; right:20px; z-index:20; width:300px; }.action-menu a { display:block; padding:9px; }
@media(max-width:1279px) { .mold-sidebar { width:200px; }.mold-header .ant-select { max-width:270px; }.mold-header { gap:15px; } }
@media(max-width:765px) { .mold-sidebar { display:none; }.mold-header { padding:0 12px; gap:12px; height:56px; }.mold-header>.ant-select,.mold-header>.anticon:not(:first-child),.mold-header>span:last-child { display:none; }.view-toolbar { padding:14px 12px; min-height:75px; }.view-toolbar .ant-breadcrumb { width:100%; }.list-search { width:200px; }.resource-main { padding:10px; }.info-card { min-height:0; }.resource-content-card>.ant-card-body { padding:14px; }.resource-content-card .ant-tabs-tab { padding:9px; }.creation-body { display:block; }.creation-summary { border-left:0; border-top:1px solid var(--ui-border); padding:16px 0 0; margin-top:20px; }.creation-progress .ant-steps { flex-direction:column; }.creation-progress { max-height:120px; overflow-y:auto; }.mold-dialog .ant-modal-body { padding:16px; }.mold-dialog .ant-modal-footer { padding:12px; }.review-bar .ant-select { min-width:0!important; width:100%; }.resource-name { font-size:17px; }.mold-dialog .ant-descriptions-item-label { min-width:100px; }.cards-grid .ant-card { min-width:125px; }.mold-dialog .ant-modal-footer .ant-btn { margin-top:4px; } }

.dark-mode .mold-shell .ant-btn-primary:not(.ant-btn-dangerous):not([disabled]), .dark-mode .mold-dialog .ant-btn-primary:not(.ant-btn-dangerous):not([disabled]) { background:var(--ui-focus)!important; border-color:var(--ui-focus)!important; color:#141414!important; }
.mold-dialog .ant-btn.ant-btn-primary.ant-btn-dangerous { background:var(--ui-error-icon)!important; border-color:var(--ui-error-icon)!important; color:#fff!important; }
.dark-mode .mold-dialog .ant-btn.ant-btn-primary.ant-btn-dangerous { background:var(--ui-error-icon)!important; border-color:var(--ui-error-icon)!important; color:#141414!important; }
@media(max-width:767px) { .resource-main .page-header-wrapper-grid-content-main > .ant-row > .ant-col { flex:0 0 100%; max-width:100%; } }

.prototype-list-table { margin-bottom:10px; overflow-x:auto; }
.prototype-list-table table { min-width:1100px; }
.prototype-list-table .ant-table-thead>tr>th,.prototype-list-table .ant-table-tbody>tr>td { overflow-wrap:anywhere; padding:12px 8px; }
.list-resource-name { display:flex; align-items:center; gap:6px; min-width:120px; }
.list-resource-symbol { width:18px; height:18px; flex-shrink:0; }
.list-quick-action { margin-left:auto; background:transparent!important; border:0!important; }
.list-resource-summary { display:inline-flex; align-items:center; gap:8px; flex-wrap:nowrap; }
.list-resource-item { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; }
.list-cpu-icon,.list-memory-icon { font-size:13px; }
.list-cpu-icon { color:#5b6b84; }.list-memory-icon { color:#68758a; }
.prototype-list-pagination { margin-bottom:10px; text-align:left; }

html[data-mock-capture] *,html[data-mock-capture] *::before,html[data-mock-capture] *::after { animation:none!important; transition:none!important; }
</style>
