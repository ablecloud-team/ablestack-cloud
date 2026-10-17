// A partial clone resumes its recorded request; resolved server defaults are not a new request.
export const cloneResumeUnavailable = 'message.storage.config.clone.resume.unavailable'
const object = value => !!value && typeof value === 'object' && !Array.isArray(value)
const owns = (value, key) => Object.prototype.hasOwnProperty.call(value, key)
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(value)
const forbidden = new Set(['__proto__', 'constructor', 'prototype', 'plantoken', 'downloadtoken', 'credentials', 'password', 'chapsecret', 'mutualchapsecret', 'dhchapkey', 'dhchapctrlkey', 'adidentitycipherreference', 'ciphertext'])
const fail = () => { throw new Error(cloneResumeUnavailable) }
const requireValue = value => { if (!value) fail() }
function publicCopy (value) {
  if (Array.isArray(value)) return value.map(publicCopy)
  if (object(value)) {
    const result = {}
    for (const [key, item] of Object.entries(value)) {
      requireValue(!forbidden.has(key.toLowerCase()))
      result[key] = publicCopy(item)
    }
    return result
  }
  requireValue(value === null || ['string', 'boolean'].includes(typeof value) || (typeof value === 'number' && Number.isFinite(value)))
  return value
}
function canonical (value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (object(value)) return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}'
  return JSON.stringify(value)
}
const same = (left, right) => canonical(left) === canonical(right)
const frozenFields = ['targetMode', 'targetName', 'targetInstanceUuid', 'createNew', 'cloneTemplatePin', 'cloneRuntimePin', 'volumeAllocationPlan', 'resourceMappings', 'listenerAddressMappings']
export function partialConfigurationClone (metadata) {
  return object(metadata) && (owns(metadata, 'createdTargetInstanceUuid') || owns(metadata, 'cloneAllocation'))
}
export function configurationCloneResume (metadata) {
  if (!partialConfigurationClone(metadata)) return null
  requireValue(object(metadata.requestedVolumeMapping) && object(metadata.plan))
  const requested = publicCopy(metadata.requestedVolumeMapping)
  const plan = publicCopy(metadata.plan)
  requireValue(Object.keys(requested).every(key => ['createNew', 'volumes', 'newVolumes', 'initialVolumeSourceUuid', 'runtimeBundleUuid'].includes(key)))
  requireValue(object(requested.createNew) && object(requested.volumes) && Object.keys(requested.volumes).length > 0)
  requireValue(uuid(requested.initialVolumeSourceUuid) && uuid(requested.runtimeBundleUuid) && owns(requested.volumes, requested.initialVolumeSourceUuid))
  requireValue(Object.entries(requested.volumes).every(([source, target]) => uuid(source) && (target === 'NEW' || uuid(target))))
  requireValue(!owns(requested, 'newVolumes') || object(requested.newVolumes))
  requireValue(typeof requested.createNew.name === 'string' && requested.createNew.name.length > 0 && ['NEW', 'EXISTING'].includes(requested.createNew.backingvolumemode))
  requireValue(plan.targetMode === 'CREATE_NEW' && plan.targetName === requested.createNew.name && uuid(plan.targetInstanceUuid))
  requireValue(frozenFields.every(field => owns(plan, field)) && object(plan.volumeAllocationPlan) && object(plan.listenerAddressMappings) && object(plan.resourceMappings))
  requireValue(['create', 'update', 'keep', 'preserve', 'blockers', 'requiredCredentials'].every(field => Array.isArray(plan[field])))
  requireValue(object(plan.cloneTemplatePin) && object(plan.cloneRuntimePin) && uuid(plan.cloneTemplatePin.templateUuid))
  const blueprint = publicCopy(requested.createNew)
  if (plan.cloneTemplatePin.selectionMode === 'DEFAULT_SYSTEM') {
    requireValue(!owns(blueprint, 'templateid'))
    blueprint.templateid = plan.cloneTemplatePin.templateUuid
  } else requireValue(uuid(blueprint.templateid) && blueprint.templateid === plan.cloneTemplatePin.templateUuid)
  requireValue(same(blueprint, plan.createNew))
  const createdTarget = owns(metadata, 'createdTargetInstanceUuid') ? metadata.createdTargetInstanceUuid : null
  requireValue(createdTarget === null || uuid(createdTarget))
  requireValue(!owns(metadata, 'createdTargetInstanceUuid') || uuid(createdTarget))
  requireValue(!owns(metadata, 'cloneAllocation') || object(metadata.cloneAllocation))
  return {
    requested,
    plan,
    targetName: plan.targetName,
    createdTargetInstanceUuid: createdTarget,
    allocationRecorded: owns(metadata, 'cloneAllocation'),
    allocation: owns(metadata, 'cloneAllocation') ? publicCopy(metadata.cloneAllocation) : null,
    listenerAddresses: publicCopy(plan.listenerAddressMappings),
    confirmFileExecute: (plan.forcedFileExecutePolicies || []).length > 0 && plan.forceFileExecuteConfirmation === true
  }
}
export function configurationCloneResumeMapping (resume) {
  requireValue(object(resume) && object(resume.requested))
  const mapping = publicCopy(resume.requested)
  mapping.confirmFileExecute = resume.confirmFileExecute
  if (Object.keys(resume.listenerAddresses).length) mapping.listenerAddresses = publicCopy(resume.listenerAddresses)
  return mapping
}
export function requireConfigurationCloneResumePlan (resume, metadata) {
  requireValue(object(metadata) && object(metadata.plan))
  const plan = publicCopy(metadata.plan)
  requireValue(frozenFields.every(field => same(plan[field], resume.plan[field])))
  requireValue(same(metadata.requestedVolumeMapping, resume.requested))
  if (resume.createdTargetInstanceUuid !== null) requireValue(metadata.createdTargetInstanceUuid === resume.createdTargetInstanceUuid)
  if (resume.allocationRecorded) requireValue(same(metadata.cloneAllocation, resume.allocation))
  return plan
}
