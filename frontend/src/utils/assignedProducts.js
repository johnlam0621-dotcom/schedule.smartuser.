export function assignedProductKeys(projects) {
  const text = String(projects || '').trim().toLowerCase()
  const tokens = new Set(text.split(/[^a-z0-9]+/).filter(Boolean))
  const all = text === 'all products'
  return [
    ['mac', all || tokens.has('mac')],
    ['dac', all || tokens.has('dac')],
    ['battery_solar', all || tokens.has('battery') || tokens.has('sp') || tokens.has('solar')],
    ['heat_pump', all || tokens.has('hp') || /heat[ -]*pump/.test(text)]
  ].filter(([, assigned]) => assigned).map(([key]) => key)
}

export function resolveAssignedProduct(projects, selected) {
  const allowed = assignedProductKeys(projects)
  return allowed.includes(selected) ? selected : (allowed[0] || '')
}
