// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for ownership information.
// Licensed under the Apache License, Version 2.0; http://www.apache.org/licenses/LICENSE-2.0
// Distributed on an AS IS BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

const routedServices = ['SourceNat', 'StaticNat', 'Lb', 'PortForwarding', 'Vpn']

export function offeringServiceProviders (values, selected, services) {
  if (values.guestiptype === 'l2') return values.userdatal2 === true ? { UserData: 'ConfigDrive' } : {}
  return Object.fromEntries(Object.entries(selected).filter(([name, provider]) => {
    if (values.guestiptype === 'isolated' && values.networkmode === 'ROUTED' && routedServices.includes(name)) return false
    return services.some(service => service.name === name && service.provider?.some(option => option.name === provider && option.enabled !== false))
  }))
}

export function offeringFields (values, providers) {
  const { guestiptype: type, provider } = values
  const external = ['NSX', 'Netris'].includes(provider)
  const isolated = type === 'isolated'
  const shared = type === 'shared'
  const routing = isolated && (values.networkmode === 'ROUTED' || ['ipv6', 'dualstack'].includes(values.internetprotocol))
  const fields = ['name', 'displaytext', 'guestiptype', 'tags', 'enable']
  if (!external) fields.push('networkrate', 'specifyvlan')
  if (!shared) fields.push('ispersistent')
  if (shared) fields.push('specifyipranges')
  if (isolated) fields.push('forvpc', 'internetprotocol', 'networkmode')
  if (routing) fields.push('routingmode')
  if (routing && values.routingmode === 'dynamic' && provider === 'NSX' && !values.forvpc) fields.push('specifyasnumber')
  if (external) fields.push('provider')
  if (isolated && provider === 'NSX') {
    fields.push('nsxsupportlb')
    if (values.forvpc && values.nsxsupportlb) fields.push('nsxsupportsinternallb')
  }
  if ((isolated || shared) && ['VirtualRouter', 'VpcVirtualRouter'].some(router => Object.values(providers).includes(router))) fields.push('serviceofferingid')
  if ((isolated || shared) && providers.SourceNat) fields.push('conservemode')
  if (isolated && providers.SourceNat) fields.push('availability')
  const params = Object.fromEntries(fields.filter(key => values[key] !== '' && values[key] != null).map(key => [key, values[key]]))
  if (shared) params.specifyipranges = true
  if (providers.Firewall && values.egressdefaultpolicy !== undefined) {
    params.egressdefaultpolicy = values.egressdefaultpolicy === true || values.egressdefaultpolicy === 'allow'
  }
  if (!external) {
    for (const key of ['promiscuousmode', 'macaddresschanges', 'forgedtransmits', 'maclearning']) {
      if (values[key] === '' || values[key] == null) continue
      params[`details[0].${key}`] = values[key]
    }
  }
  return params
}

export function cloneOfferingServiceParams (source, providers, params) {
  const original = (source.service || []).filter(service => service.name !== 'Gateway')
  const entries = Object.entries(providers)
  if (!entries.length && original.length) {
    params.dropservices = original.map(service => service.name).join(',')
  }
  if (original.length === entries.length && original.every(service => providers[service.name] === service.provider?.[0]?.name)) {
    delete params.supportedservices
    for (const key of Object.keys(params)) {
      if (key.startsWith('serviceProviderList[')) delete params[key]
    }
    return
  }
  for (const key of Object.keys(params)) {
    if (key.startsWith('serviceProviderList[')) delete params[key]
  }
  let index = 0
  for (const [name, provider] of entries) {
    const previous = original.find(service => service.name === name)?.provider || []
    const names = provider === previous[0]?.name ? previous.map(p => p.name) : [provider]
    for (const value of names) {
      params[`serviceProviderList[${index}].service`] = name
      params[`serviceProviderList[${index++}].provider`] = value
    }
  }
}
