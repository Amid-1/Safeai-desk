'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { isForbiddenFeatureDependency } = require('./architecture-feature-rules.cjs')

const feature = (name) => ({ name: 'features', feature: name })

test('rejects the audit -> admin compatibility-facade dependency', () => {
    assert.equal(isForbiddenFeatureDependency(feature('audit'), feature('admin')), true)
})

test('allows reverse admin -> audit and admin -> usage compatibility exports', () => {
    assert.equal(isForbiddenFeatureDependency(feature('admin'), feature('audit')), false)
    assert.equal(isForbiddenFeatureDependency(feature('admin'), feature('usage')), false)
})

test('allows necessary audit -> auth and unrelated feature imports', () => {
    assert.equal(isForbiddenFeatureDependency(feature('audit'), feature('auth')), false)
    assert.equal(isForbiddenFeatureDependency(feature('knowledge'), feature('auth')), false)
})

test('does not confuse feature dependencies with layer dependencies', () => {
    assert.equal(isForbiddenFeatureDependency({ name: 'shared' }, feature('admin')), false)
    assert.equal(isForbiddenFeatureDependency(feature('audit'), { name: 'entities' }), false)
})
