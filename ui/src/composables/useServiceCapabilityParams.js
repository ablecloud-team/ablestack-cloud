// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

export function buildServiceCapabilityParams (params, values, providers, packages) {
  const services = Object.entries(providers)
  params.supportedservices = services.map(([service]) => service).join(',')
  services.forEach(([service, provider], index) => {
    params[`serviceProviderList[${index}].service`] = service
    params[`serviceProviderList[${index}].provider`] = provider
  })
  let index = 0
  const capability = (service, name, value) => {
    if (value == null || value === '') return
    params[`serviceCapabilityList[${index}].service`] = service
    params[`serviceCapabilityList[${index}].capabilitytype`] = name
    params[`serviceCapabilityList[${index++}].capabilityvalue`] = value
  }
  if (providers.Connectivity) {
    capability('Connectivity', 'RegionLevelVpc', values.supportsstrechedl2subnet)
    capability('Connectivity', 'DistributedRouter', values.supportspublicaccess)
  }
  if (providers.SourceNat) {
    capability('SourceNat', 'SupportedSourceNatTypes', values.sourcenattype)
    if (providers.SourceNat !== 'VpcVirtualRouter') capability('SourceNat', 'RedundantRouter', values.redundantroutercapability)
  }
  const shared = values.guestiptype === 'shared'
  if (shared && providers.StaticNat === 'Netscaler') {
    capability('StaticNat', 'ElasticIp', values.elasticip)
    if (values.elasticip) capability('StaticNat', 'associatePublicIP', values.associatepublicip ?? false)
  }
  const lb = providers.Lb
  if (lb) {
    if (['VirtualRouter', 'VpcVirtualRouter', 'Netscaler'].includes(lb)) capability('Lb', 'VmAutoScaling', values.vmautoscalingcapability)
    if (shared && lb === 'Netscaler') capability('Lb', 'ElasticLb', values.elasticlb)
    if (['F5BigIp', 'Netscaler'].includes(lb) && providers.Firewall === 'JuniperSRX') capability('Lb', 'InlineMode', values.inlinemode)
    capability('Lb', 'SupportedLbIsolation', values.isolation || 'dedicated')
    if (lb === 'InternalLbVm') capability('Lb', 'lbSchemes', 'internal')
    const pkg = lb === 'Netscaler' ? packages[values.netscalerservicepackages] : null
    if (pkg && values.netscalerservicepackagesdescription) {
      params['details[0].servicepackageuuid'] = pkg.id
      params['details[0].servicepackagedescription'] = values.netscalerservicepackagesdescription
    }
  }
}

/**
 * Build the VPC service capability params for Add/Clone VPC Offering forms.
 * Handles: RegionLevelVpc, DistributedRouter, RedundantRouter (SourceNat/Gateway)
 */
export function buildVpcServiceCapabilityParams (params, values, selectedServiceProviderMap, isVpcVirtualRouterForAtLeastOneService) {
  const supportedServices = Object.keys(selectedServiceProviderMap)
  let serviceCapabilityIndex = 0
  if (supportedServices.includes('Connectivity')) {
    if (values.regionlevelvpc === true) {
      params[`serviceCapabilityList[${serviceCapabilityIndex}].service`] = 'Connectivity'
      params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilitytype`] = 'RegionLevelVpc'
      params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilityvalue`] = true
      serviceCapabilityIndex++
    }
    if (values.distributedrouter === true) {
      params[`serviceCapabilityList[${serviceCapabilityIndex}].service`] = 'Connectivity'
      params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilitytype`] = 'DistributedRouter'
      params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilityvalue`] = true
      serviceCapabilityIndex++
    }
  }
  if (supportedServices.includes('SourceNat') && values.redundantrouter === true) {
    params[`serviceCapabilityList[${serviceCapabilityIndex}].service`] = 'SourceNat'
    params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilitytype`] = 'RedundantRouter'
    params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilityvalue`] = true
    serviceCapabilityIndex++
  } else if (values.redundantrouter === true) {
    params[`serviceCapabilityList[${serviceCapabilityIndex}].service`] = 'Gateway'
    params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilitytype`] = 'RedundantRouter'
    params[`serviceCapabilityList[${serviceCapabilityIndex}].capabilityvalue`] = true
    serviceCapabilityIndex++
  }
  if (values.serviceofferingid && isVpcVirtualRouterForAtLeastOneService) {
    params.serviceofferingid = values.serviceofferingid
  }
}
