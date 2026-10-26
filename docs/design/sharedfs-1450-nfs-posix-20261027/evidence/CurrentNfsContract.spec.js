// Design-only probe; no product source mutation and no real API calls.
import { shallowMount } from '@vue/test-utils'
import SharedFSTab from '@/views/storage/SharedFSTab'
jest.mock('@/api', () => ({ getAPI: jest.fn(), postAPI: jest.fn() }))
const fs = require('fs')
describe('#1450 current mounted NFS request contract (design evidence)', () => {
  let w
  beforeEach(() => {
    jest.spyOn(SharedFSTab.methods, 'fetchData').mockResolvedValue()
    jest.spyOn(SharedFSTab.methods, 'fetchStorageServiceData').mockResolvedValue()
    w = shallowMount(SharedFSTab, {
      props: {resource: {id: 'sharedfs'}},
      global: {provide: {parentFetchData: jest.fn()}, mocks: {
        $t:k=>k, $route:{fullPath:'/sharedfs/s?tab=nfs',query:{tab:'nfs'},params:{id:'s'}},
        $router:{push:jest.fn(),replace:jest.fn()},
        $store:{state:{app:{device:'desktop'}},getters:{apis:{},userInfo:{id:'u'}}},
        $message:{error:jest.fn()},$notifyError:jest.fn()
      }}
    })
    w.vm.storageService.instance={id:'instance'}
    w.vm.storageService.protocols=[{id:'listener',protocol:'NFS',port:2049,enabled:true}]
  })
  afterEach(()=>{w.unmount();jest.restoreAllMocks()})
  test('raw config omission becomes synthesized owner fields in an unchanged edit request', async()=>{
    const config={readOnly:false,rootSquash:true,allSquash:false,sync:true,secure:false}
    const record={id:'export',name:'fixture',path:'/export/fixture',config:JSON.stringify(config)}
    w.vm.actionModal.type='editNfsExport';w.vm.actionModal.context=record
    w.vm.populateNfsExportForm(record)
    const draft={...w.vm.forms.nfsExport}
    w.vm.validateNfsExportNameAndPath=()=>true
    w.vm.selectedNfsListenerPorts=()=>[2049]
    w.vm.runStorageAction=jest.fn().mockResolvedValue()
    await w.vm.updateNfsExport()
    const [kind,api,params]=w.vm.runStorageAction.mock.calls[0]
    expect(params.owneruid).toBe(65534)
    expect(params.ownergid).toBe(65534)
    expect(params.mode).toBe('0775')
    expect(config.ownerUid).toBeUndefined()
    fs.writeFileSync('/root/work/epic1438-preparation/20261027/issue1450-design/current-ui-request.json',
      JSON.stringify({designOnly:true,kind,api,rawConfig:config,draft:{owneruid:draft.owneruid,ownergid:draft.ownergid,mode:draft.mode},capturedRequest:params,noNetworkMutation:true},null,2))
  })
  test('fresh no-root-squash selection keeps reset 65534 owner rather than proposing 0:0',()=>{
    w.vm.resetNfsExportForm()
    w.vm.forms.nfsExport.rootsquash=false
    w.vm.applyNfsWritableDefaults()
    expect(w.vm.forms.nfsExport.owneruid).toBe(65534)
    expect(w.vm.forms.nfsExport.ownergid).toBe(65534)
  })
})
