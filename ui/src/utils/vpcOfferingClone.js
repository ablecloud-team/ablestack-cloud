// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

export function cloneBoolean (value) {
  if (value === true || value === 'true') return true
  if (value === false || value === 'false') return false
}

export function cloneScopeIds (value) {
  return (Array.isArray(value) ? value : String(value || '').split(',')).filter(Boolean)
}

export function cloneCapability (resource, service, name, field) {
  const value = resource.service?.find(item => item.name === service)?.capability?.find(item => item.name === name)?.value
  return cloneBoolean(value) ?? cloneBoolean(resource[field]) ?? 'inherit'
}

// These values describe the source. Missing API values remain inheritance,
// rather than an unchecked switch that silently means something else.
export function vpcCloneDefaults (resource) {
  return {
    enable: resource.state === 'Enabled' ? true : resource.state === 'Disabled' ? false : undefined,
    internetprotocol: resource.internetprotocol?.toLowerCase() || 'ipv4',
    networkmode: resource.networkmode,
    routingmode: resource.routingmode?.toLowerCase(),
    specifyasnumber: cloneBoolean(resource.specifyasnumber),
    serviceofferingid: resource.serviceofferingid,
    ispublic: cloneScopeIds(resource.domainid).length === 0,
    domainid: cloneScopeIds(resource.domainid),
    zoneid: cloneScopeIds(resource.zoneid),
    regionlevelvpc: String(cloneCapability(resource, 'Connectivity', 'RegionLevelVpc', 'supportsregionLevelvpc')),
    distributedrouter: String(cloneCapability(resource, 'Connectivity', 'DistributedRouter', 'distributedvpcrouter')),
    redundantrouter: String(cloneCapability(resource, resource.service?.some(item => item.name === 'SourceNat') ? 'SourceNat' : 'Gateway', 'RedundantRouter'))
  }
}

// The server replaces the capability map as a whole. A partial override
// cannot preserve another capability whose source value was not returned.
export function mixedVpcCloneCapabilities (values, providers) {
  const fields = []
  if (providers.Connectivity) fields.push('regionlevelvpc', 'distributedrouter')
  if (providers.SourceNat || providers.Gateway) fields.push('redundantrouter')
  const flags = fields.map(field => cloneBoolean(values[field]))
  return flags.some(flag => flag !== undefined) && flags.some(flag => flag === undefined)
}

// Clone overrides must retain explicit false. Keep the Add offering helper's
// creation defaults and the other offering forms unchanged.
export function buildVpcCloneCapabilityParams (params, values, providers, hasRouter) {
  let index = 0
  const add = (service, type, value) => {
    const flag = cloneBoolean(value)
    if (!providers[service] || flag === undefined) return
    params[`serviceCapabilityList[${index}].service`] = service
    params[`serviceCapabilityList[${index}].capabilitytype`] = type
    params[`serviceCapabilityList[${index++}].capabilityvalue`] = flag
  }
  add('Connectivity', 'RegionLevelVpc', values.regionlevelvpc)
  add('Connectivity', 'DistributedRouter', values.distributedrouter)
  add(providers.SourceNat ? 'SourceNat' : 'Gateway', 'RedundantRouter', values.redundantrouter)
  if (hasRouter && values.serviceofferingid) params.serviceofferingid = values.serviceofferingid
}
