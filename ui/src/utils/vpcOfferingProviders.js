// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.

export function providerNames (providers) {
  return [...new Set((Array.isArray(providers) ? providers : [providers]).filter(Boolean))]
}

export function vpcServiceProviderMap (services = []) {
  return Object.fromEntries(services.map(service => [service.name, providerNames((service.provider || []).map(provider => provider.name))]))
}

export function vpcProviderChoices (choices, sourceServices = []) {
  const source = vpcServiceProviderMap(sourceServices)
  const services = choices.map(service => ({ ...service, provider: service.provider.map(provider => ({ ...provider })) }))
  sourceServices.forEach(service => {
    if (!services.some(choice => choice.name === service.name)) {
      services.push({ name: service.name, description: service.name, provider: [], preserveOnly: true })
    }
  })
  return services.map(service => {
    const selectedProviders = [...(source[service.name] || [])]
    selectedProviders.forEach(name => {
      if (!service.provider.some(provider => provider.name === name)) service.provider.push({ name })
    })
    return {
      ...service,
      selectedProviders,
      defaultChecked: !!source[service.name],
      preserveOnly: service.preserveOnly || (service.name !== 'Lb' && selectedProviders.length > 1)
    }
  })
}

// Provider order is not significant. An unchanged clone uses the server's
// inheritance contract, including providers unknown to this UI version.
export function sameVpcProviders (left, right) {
  const normalize = map => Object.keys(map).sort().map(service => [service, providerNames(map[service]).sort()])
  return JSON.stringify(normalize(left)) === JSON.stringify(normalize(right))
}

export function buildVpcCloneProviderParams (params, selected, source, externalProvider = false) {
  if (sameVpcProviders(selected, source)) return
  if (!externalProvider) params.supportedservices = Object.keys(selected).join(',')
  let index = 0
  Object.entries(selected).forEach(([service, providers]) => {
    providerNames(providers).forEach(provider => {
      params[`serviceProviderList[${index}].service`] = service
      params[`serviceProviderList[${index++}].provider`] = provider
    })
  })
}
