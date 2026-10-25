import fs from 'node:fs'
import { api, job, login, logout, root, prefix, cluster, registryFile } from './api.mjs'
const phase = process.argv[3], r = JSON.parse(fs.readFileSync(registryFile)), file = root + '/' + cluster + '-cleanup-' + phase + '.json'
const actions = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file)) : []
const save = () => fs.writeFileSync(file, JSON.stringify(actions, null, 2))
async function remove(command, args) {
  if (actions.some(x => x.command === command && x.id === args.id)) return
  await job(command, args); actions.push({ command, id: args.id, complete: true }); save()
}
await login()
try {
  if (phase === 'objects') {
    for (const t of r.templates || []) { if (!t.name.startsWith(prefix)) throw Error('Foreign template'); await remove('deleteTemplate', { id: t.id }) }
    for (const v of r.volumes.filter(x => x.scenario === 'ui-volume')) { if (!v.name.startsWith(prefix)) throw Error('Foreign volume'); await remove('deleteVolume', { id: v.id }) }
    for (const s of r.snapshots) {
      if (!s.name.startsWith(prefix)) throw Error('Foreign snapshot')
      const scope = s.projectid ? { projectid: s.projectid } : {}
      const tags = (await api('listTags', { resourceid: s.id, resourcetype: 'Snapshot', listall: true, page: 1, pagesize: 100, ...scope })).tag || []
      for (const t of tags) await job('deleteTags', { resourceids: s.id, resourcetype: 'Snapshot', 'tags[0].key': t.key, 'tags[0].value': t.value, ...scope })
      const active = (await api('listSnapshots', { id: s.id, listall: true, ...scope })).snapshot || []
      if (!active.length) { actions.push({ command: 'deleteSnapshot', id: s.id, alreadyDeletedByUi: true, complete: true }); save(); continue }
      const plan = await job('deleteSnapshot', { id: s.id, dryrun: true })
      if (!plan.scopehash) throw Error('No scope hash')
      await remove('deleteSnapshot', { id: s.id, expectedscopehash: plan.scopehash })
    }
    const data = r.volumes.find(x => x.scenario === 'vmdata')
    if (r.vmAttached) { await remove('detachVolume', { id: data.id }); r.vmAttached = false; fs.writeFileSync(registryFile, JSON.stringify(r, null, 2)) }
    for (const v of r.vms) { if (!v.displayname.startsWith(prefix)) throw Error('Foreign VM'); await remove('destroyVirtualMachine', { id: v.id }); await remove('expungeVirtualMachine', { id: v.id }) }
    for (const v of r.volumes.filter(x => !['root', 'deleted', 'ui-volume'].includes(x.scenario))) { if (!v.name.startsWith(prefix)) throw Error('Foreign volume'); await remove('deleteVolume', { id: v.id }) }
    await remove('deleteProject', { id: r.project.id })
    for (const a of r.accounts) { if (!a.name.startsWith(prefix)) throw Error('Foreign account'); await remove('deleteAccount', { id: a.id }) }
    await remove('deleteDomain', { id: r.domain.id })
  } else if (phase === 'infra') {
    const hosts = (await api('listHosts', { listall: true, page: 1, pagesize: 100 })).host || []
    for (const h of hosts.filter(x => r.zones.some(z => x.name === prefix + '-' + z.key))) {
      if (h.type !== 'SecondaryStorageVM') throw Error('Foreign endpoint type')
      await remove('deleteHost', { id: h.id, forced: true, forcedestroystorage: false })
    }
    for (const z of r.zones) {
      if (!z.name.startsWith(prefix) || !z.store.name.startsWith(prefix)) throw Error('Foreign Zone/store')
      await remove('deleteImageStore', { id: z.store.id })
      await api('updateZone', { id: z.id, allocationstate: 'Disabled' })
      await remove('deletePhysicalNetwork', { id: z.physical.id }); await remove('deletePod', { id: z.pod.id }); await remove('deleteZone', { id: z.id })
    }
    const me = (await api('listAccounts', { name: 'admin', listall: true, page: 1, pagesize: 100 })).account.find(x => x.name === 'admin')
    if (!me) throw Error('Root account lookup failed')
    await api('updateResourceCount', { account: me.name, domainid: me.domainid, resourcetype: 11 })
    const alerts = (await api('listAlerts', { page: 1, pagesize: 500 })).alert || []
    const own = alerts.filter(x => x.description?.includes(prefix) || r.snapshots.some(s => x.description?.includes(s.id)))
    if (own.length) await api('archiveAlerts', { ids: own.map(x => x.id).join(',') })
  } else throw Error('Explicit cleanup phase required')
  console.log(cluster, phase, 'normal API cleanup PASS', actions.length)
} finally { save(); await logout() }
