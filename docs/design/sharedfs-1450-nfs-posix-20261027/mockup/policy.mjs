// Design-only prototype. No production imports or API calls.
const integer = value => value === null || value === undefined || value === '' ? null : Number(value)
const mode = value => value ? String(value).trim().replace(/^0+/, '').padStart(4, '0') : null
export function recommendation (policy, readonly, anonuid = 65534, anongid = 65534) {
 const anon = policy !== 'NONE' && !readonly
 return {owneruid: anon ? integer(anonuid) : 0, ownergid: anon ? integer(anongid) : 0, mode: anon ? '0775' : '0755', recursivepermission: false}
}
export function permissionDelta (baseline, draft, enabled) {
 if (!enabled) return {}
 const out = {}
 for (const key of ['owneruid', 'ownergid', 'mode']) {
  const normalize = key === 'mode' ? mode : integer
  const oldValue = normalize(baseline[key]); const newValue = normalize(draft[key])
  if (newValue !== null && newValue !== oldValue) out[key] = newValue
 }
 // Stored true never causes a new recursive action by merely opening the form.
 if (draft.recursivepermission === true) out.recursivepermission = true
 return out
}
export function mappingDelta (baseline, draft) {
 const out={}
 for (const key of ['anonuid','anongid']) {
  const value=integer(draft[key])
  if (value !== null && value !== integer(baseline[key])) out[key]=value
 }
 return out
}
export function squashFlags (policy) {
 return {rootsquash:policy !== 'NONE', allsquash:policy === 'ALL'}
}
