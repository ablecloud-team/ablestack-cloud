import {createStore} from 'vuex'
import {createApp,h} from 'vue'
import {createRouter,createMemoryHistory} from 'vue-router'
import Antd from 'ant-design-vue'
import * as icons from '@ant-design/icons-vue'
import 'ant-design-vue/dist/antd.css'
import '/root/work/ablestack-cloud/ui/src/style/index.less'
import App from './App.vue'
const app=createApp(App)
const router=createRouter({history:createMemoryHistory(),routes:[
 {path:'/',name:'index',meta:{icon:'HomeOutlined'},component:{render:()=>null},
 children:[{path:'sharedfs',name:'sharedfs',meta:{title:'공유 파일 시스템'},component:{render:()=>null},
 children:[{path:':id',meta:{title:'상세'},component:{render:()=>null}}]}]}
]})
app.use(router)
app.use(Antd)
app.use(createStore({state:{app:{device:'desktop'}},getters:{userInfo:()=>({id:'example'}),apis:()=>({})}}))
app.component('render-icon',{props:['icon'],render(){return h(icons[this.icon]||icons.HomeOutlined)}})
app.config.globalProperties.$t=k=>({'label.close':'닫기','label.search':'검색','label.previous':'이전','label.next':'다음'}[k]||k)
app.config.globalProperties.$i18n={locale:'ko',global:{locale:'ko'}}
app.config.globalProperties.$config={docBase:''}
router.push(new URLSearchParams(location.search).get('view')==='list'?'/sharedfs':'/sharedfs/design-example')
router.isReady().then(()=>app.mount('#app'))
