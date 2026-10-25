// frontend/scripts/audit-dependencies.test.cjs
// Regression tests for the read-only TypeScript dependency scanner.
'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const {spawnSync} = require('node:child_process')
const {tarjan, isTest, checkLayer} = require('./audit-dependencies.cjs')

function fixture(root, name, content) {
    const target = path.join(root, name)
    fs.mkdirSync(path.dirname(target), {recursive: true})
    fs.writeFileSync(target, content)
}

test('Tarjan returns only strongly connected components, including self loop', () => {
    const result = tarjan(['a', 'b', 'c', 'd'], [
        {from:'a', to:'b'}, {from:'b', to:'a'}, {from:'c', to:'c'}, {from:'b', to:'d'},
    ])
    assert.deepEqual(result.groups, [['a','b'], ['c']])
})

test('layer constraints and production/test identification', () => {
    assert.equal(checkLayer('shared/api/http.ts', 'features/auth/api/authApi.ts'), true)
    assert.equal(checkLayer('features/auth/ui/Login.tsx', 'shared/ui/Modal.tsx'), false)
    assert.equal(isTest('features/auth/ui/Login.test.tsx'), true)
    assert.equal(isTest('test/fixtures/data.ts'), true)
    assert.equal(isTest('features/chat/controller/useChatPageController.ts'), false)
})

test('scanner distinguishes runtime cycles, type cycles, forbidden layers and assets', (t) => {
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'safeai-frontend-audit-'))
    t.after(() => fs.rmSync(work, {recursive: true, force: true}))
    const root = path.join(work, 'frontend')
    const out = path.join(work, 'reports')
    fixture(root,'tsconfig.app.json', JSON.stringify({compilerOptions:{target:'ES2022',module:'ESNext',moduleResolution:'Bundler',noEmit:true},include:['src/**/*']}))
    fixture(root,'src/features/a/a.ts', 'import { b } from "../b/b"; export const a = b;')
    fixture(root,'src/features/b/b.ts', 'import type { A } from "../a/t"; export type B = A; export const b = 1;')
    fixture(root,'src/features/a/t.ts', 'import type { B } from "../b/b"; export type A = B;')
    fixture(root,'src/features/c/c.ts', 'import { d } from "./d"; export const c = d;')
    fixture(root,'src/features/c/d.ts', 'import { c } from "./c"; export const d = c;')
    fixture(root,'src/features/a/assets.ts', 'import "./style.css"; export const n = 1;')
    fixture(root,'src/features/a/style.css', '/* exists */')
    fixture(root,'src/shared/bad.ts', 'import "../features/a/a"; export const bad = 1;')
    fixture(root,'src/features/a/test-import.ts', 'import "../../test/fixtures/data";')
    fixture(root,'src/test/fixtures/data.ts', 'export const data = 1;')
    fixture(root,'src/features/a/missing.ts', 'import "./does-not-exist";')
    const script = path.resolve(__dirname, 'audit-dependencies.cjs')
    const result = spawnSync(process.execPath, [script, '--root', root, '--out', out, '--strict'], {encoding:'utf8'})
    assert.equal(result.status, 1, result.stderr + result.stdout)
    const report = JSON.parse(fs.readFileSync(path.join(out,'safeai-frontend-dependencies.json'), 'utf8'))
    assert.equal(report.totals.runtimeCycles, 1)
    assert.equal(report.totals.cyclesWithTypes, 2)
    assert.equal(report.totals.layerViolations, 1)
    assert.equal(report.totals.productionTestEdges, 1)
    assert.equal(report.totals.unresolvedImports, 1)
    assert.equal(report.totals.featureCycleGroups, 1)
    assert.ok(report.runtimeCycles[0].example.join(' ').includes('features/c/c.ts'))
    assert.equal(fs.existsSync(path.join(root,'src/features/a/does-not-exist.ts')), false)
})
