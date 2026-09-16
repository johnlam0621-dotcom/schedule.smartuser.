import test from 'node:test'
import assert from 'node:assert/strict'
import { assignedProductKeys, resolveAssignedProduct } from './assignedProducts.js'

test('only offers products assigned to the job', () => {
  const cases = [
    ['MAC', ['mac']], ['DAC', ['dac']],
    ['Battery only', ['battery_solar']], ['SP', ['battery_solar']],
    ['Solar Panels', ['battery_solar']], ['HP', ['heat_pump']],
    ['Heat Pump', ['heat_pump']],
    ['MAC + DAC', ['mac', 'dac']],
    ['MAC + HP + Battery', ['mac', 'battery_solar', 'heat_pump']],
    ['DAC + SP', ['dac', 'battery_solar']],
    ['all products', ['mac', 'dac', 'battery_solar', 'heat_pump']],
    ['', []], ['unknown', []], ['MACARON', []],
    ['mac / dac', ['mac', 'dac']], [null, []], [undefined, []]
  ]
  for (const [projects, expected] of cases) {
    assert.deepEqual(assignedProductKeys(projects), expected, String(projects))
  }
})

test('rejects stale selections when a job assignment changes', () => {
  assert.equal(resolveAssignedProduct('MAC', 'dac'), 'mac')
  assert.equal(resolveAssignedProduct('MAC + DAC', 'dac'), 'dac')
  assert.equal(resolveAssignedProduct('HP', 'mac'), 'heat_pump')
  assert.equal(resolveAssignedProduct('', 'mac'), '')
  assert.equal(resolveAssignedProduct('unknown', 'mac'), '')
})
