import fs from 'node:fs'
import { api, login, logout, root, prefix, cluster, registryFile } from './api.mjs'
const r = JSON.parse(fs.readFileSync(registryFile))
const save = () => fs.writeFileSync(registryFile, JSON.stringify(r, null, 2))
r.annotations ||= []
await login()
try {
  const config = (await api('listConfigurations', { name: 'snapshot.show.chain.size', page: 1, pagesize: 100 })).configuration[0]
  r.chainConfigBefore = config.value; save()
  const contract = {}
  for (const name of ['listEvents', 'listAnnotations', 'addAnnotation', 'removeAnnotation', 'updateAnnotationVisibility', 'listSnapshots', 'listSnapshotCopyTargets']) contract[name] = await api('listApis', { name })
  fs.writeFileSync(root+'/'+cluster+'-tab-contract.json', JSON.stringify(contract, null, 2))
  for (const [scenario, amount] of [['owner', 26], ['other', 2], ['project', 3], ['vm-standard', 2]]) {
    const entity = scenario === 'vm-standard' ? r.vms[0] : r.snapshots.find(s => s.scenario === scenario)
    const entitytype = scenario === 'vm-standard' ? 'VM' : 'SNAPSHOT'
    for (let i = 0; i < amount; i++) {
      const annotation = prefix+'-'+scenario+'-comment-'+String(i).padStart(2, '0')
      if (r.annotations.some(x => x.annotation === annotation)) continue
      const result = await api('addAnnotation', { entityid: entity.id, entitytype, annotation, adminsonly: scenario === 'owner' && i === 25 })
      const note = result.annotation || result
      if (!note.id) throw Error('Annotation identity absent')
      r.annotations.push({ ...note, scenario }); save()
    }
  }
  for (const s of r.snapshots) {
    const scope = s.projectid ? { projectid: s.projectid } : {}
    const notes = await api('listAnnotations', { entityid: s.id, entitytype: 'SNAPSHOT', annotationfilter: 'all', page: 1, pagesize: 100, ...scope })
    const events = await api('listEvents', { resourceid: s.id, resourcetype: 'Snapshot', listall: true, page: 1, pagesize: 200, ...scope })
    fs.writeFileSync(root+'/'+cluster+'-'+s.scenario+'-tab-baseline.json', JSON.stringify({ notes, events }, null, 2))
  }
  console.log(cluster, 'Tab fixtures ready:', r.annotations.length, 'comments, real snapshot/VM events')
} finally { await logout() }
