// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License. You may
// obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 . Unless required
// by applicable law or agreed to in writing, software distributed under the
// License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS.

const ordinaryUpdateFields = new Set(['id', 'name', 'displaytext', 'networkdomain', 'networkofferingid', 'guestvmcidr', 'updateinsequence', 'displaynetwork', 'publicmtu', 'privatemtu', 'dns1', 'dns2', 'ip6dns1', 'ip6dns2', 'sourcenatipaddress', 'keepmacaddressonpublicnic', 'hideipaddressusage'])

// General editing does not opt into recovery/identity/CIDR-transition APIs.
export function standardNetworkUpdateParams (params, networkType) {
  return Object.fromEntries(Object.entries(params).filter(([key]) => ordinaryUpdateFields.has(key) && (key !== 'guestvmcidr' || networkType === 'Isolated')))
}
