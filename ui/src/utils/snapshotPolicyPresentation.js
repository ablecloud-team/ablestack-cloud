// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
export function policyInterval (value, t) {
  const index = typeof value === 'number' ? value : ['HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY'].indexOf(value)
  return t(['label.hourly', 'label.daily', 'label.weekly', 'label.monthly'][index] || 'label.snapshot.list.unknown')
}
export function policySchedule (policy, t) {
  const interval = Number(policy.intervaltype); const parts = String(policy.schedule || '').split(':')
  if (!Number.isInteger(interval) || interval < 0 || interval > 3 || parts.length !== (interval === 0 ? 1 : interval === 1 ? 2 : 3) || parts.some(p => !/^\d{1,2}$/.test(p)) || +parts[0] > 59 || (parts[1] && +parts[1] > 23) || (parts[2] && (+parts[2] < 1 || +parts[2] > (interval === 2 ? 7 : 28)))) return t('label.snapshot.list.unknown')
  if (interval === 0) return parts[0] + t('label.min.past.hour')
  const time = parts[1].padStart(2, '0') + ':' + parts[0].padStart(2, '0')
  if (interval === 2) return time + ' · ' + t('label.every') + ' ' + t('label.' + ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'][+parts[2] - 1])
  return interval === 3 ? time + ' · ' + t('label.snapshot.policy.month.day', { day: +parts[2] }) : time
}
export const policyScheduling = (policy, t) => t(typeof policy.schedulingenabled === 'boolean' ? policy.schedulingenabled ? 'label.snapshot.policy.schedule.enabled' : 'label.snapshot.policy.schedule.paused' : 'label.snapshot.list.unknown')
