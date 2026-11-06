// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
import {createApp,h} from 'vue'
import Antd from 'ant-design-vue'
import {createRouter,createMemoryHistory} from 'vue-router'
import {createStore} from 'vuex'
import 'ant-design-vue/dist/antd.css'
import '/root/work/ablestack-cloud/ui/src/style/index.less'
import App from './App.vue'
import {HomeOutlined} from '@ant-design/icons-vue'
const empty={render:()=>h('div')}
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',name:'index',component:empty,meta:{icon:'HomeOutlined'},children:[{path:'sharedfs',name:'sharedfs',component:empty,meta:{title:'공유 파일 시스템'},children:[{path:':id',component:empty,meta:{title:'상세'}}]}]}]})
const store=createStore({state:{app:{device:'desktop',theme:'dark'}},actions:{SetProject(){},ToggleTheme(){}}})
const a=createApp(App);a.use(Antd);a.use(router);a.use(store)
a.component('render-icon',{props:['icon'],render:()=>h(HomeOutlined)})
a.config.globalProperties.$t=k=>({'label.close':'닫기','label.open.documentation':'문서'}[k]||k)
a.config.globalProperties.$config={docBase:''}
a.config.globalProperties.$i18n={locale:'ko'}
router.push(new URLSearchParams(location.search).get('view')==='list'?'/sharedfs':'/sharedfs/design-example').then(()=>a.mount('#app'))
