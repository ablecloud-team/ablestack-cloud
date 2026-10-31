// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
export function relativePath (form) {
  return form.relativepath || (form.name ? 'export/' + form.name : '')
}
export function fileSharePathParameters (form, edit = false) {
  if (form.volumemode !== 'CURRENT' || !form.nestedpath) return {}
  const path = relativePath(form)
  if (edit && form.nestedpathbaseline && path === form.relativepathbaseline) return {}
  return { relativepath: path, createdirectory: !!form.createdirectory }
}
export function validRelativePath (value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 255 &&
    !value.includes('\\') && !Array.from(value).some(c => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127) &&
    value.split('/').every(part => part && part !== '.' && part !== '..' && new TextEncoder().encode(part).length <= 255)
}
export function shareRelativePath (row) {
  const config = typeof row.config === 'string' ? JSON.parse(row.config || '{}') : row.config || {}
  if (config.relativeSharePath) return config.relativeSharePath
  if (config.backingPath && config.volumeMountPath && config.backingPath.startsWith(config.volumeMountPath + '/')) return config.backingPath.slice(config.volumeMountPath.length + 1)
  return (row.path || '').replace(/^\//, '')
}
export function childShares (row, rows) {
  const root = shareRelativePath(row)
  return rows.filter(other => other.id !== row.id && other.volumeid === row.volumeid && (!root || shareRelativePath(other).startsWith(root + '/')))
}
