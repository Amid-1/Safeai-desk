// Regression gate: E2E test sources MUST be linted, generated reports MUST NOT.
// Node test runner, executed in architecture:check before regular ESLint.
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { ESLint } = require('eslint')

const frontendRoot = path.resolve(__dirname, '..')
const linter = new ESLint({ cwd: frontendRoot })

function pathInFrontend(relativePath) {
  return path.join(frontendRoot, relativePath)
}

test('generated Playwright report JS is ignored, including legacy nesting', async () => {
  const generated = [
    'e2e/playwright-report/trace/assets/generated.js',
    'e2e/test-results/example/artifact.js',
    'e2e/e2e/playwright-report/trace/assets/generated.js',
    'e2e/e2e/test-results/example/artifact.js',
  ]

  for (const file of generated) {
    assert.equal(await linter.isPathIgnored(pathInFrontend(file)), true, file)
  }
})

test('Playwright config and test sources remain inside ESLint scope', async () => {
  const sources = [
    'e2e/playwright.config.cjs',
    'e2e/tests/public-routes.spec.cjs',
    'e2e/tests/staging-auth.spec.cjs',
  ]

  for (const file of sources) {
    assert.equal(await linter.isPathIgnored(pathInFrontend(file)), false, file)
  }
})

test('E2E scope retains error-level no-undef (no blanket rule disable)', async () => {
  const pathToSpec = pathInFrontend('e2e/tests/public-routes.spec.cjs')
  const [result] = await linter.lintText('safeaiLintProbeNotDeclared;\n', {
    filePath: pathToSpec,
  })
  assert.ok(result, 'ESLint returned no lint result for E2E test source')
  assert.ok(result.messages.some((message) =>
    message.ruleId === 'no-undef' && message.severity === 2,
  ), 'E2E files must retain the strict no-undef rule')
})

// Regression contract: Vitest must not collect E2E specs, and Browser CI must
// still execute those same specs with Playwright. Never skip the browser gate.
test('Vitest ignores Playwright specs but browser CI still executes them', () => {
  const fs = require('node:fs')
  const viteConfig = fs.readFileSync(pathInFrontend('vite.config.ts'), 'utf8')
  const browserWorkflow = fs.readFileSync(
    path.join(frontendRoot, '..', '.github/workflows/browser-ci.yml'),
    'utf8',
  )

  // Insist on retaining Vitest defaults, the existing scripts exclusion, and
  // exactly our E2E boundary. Do not broaden the Vitest exclusion to src/**.
  assert.match(viteConfig,
    /exclude:\s*\[\s*\.\.\.configDefaults\.exclude,\s*'scripts\/\*\*\/\*\.test\.cjs',\s*'e2e\/\*\*'\s*\]/,
  )

  assert.match(browserWorkflow, /playwright test --config=e2e\/playwright\.config\.cjs/)
  assert.match(browserWorkflow, /--grep-invert @staging/)
  assert.match(browserWorkflow, /--grep @staging/)

  for (const file of [
    'e2e/tests/public-routes.spec.cjs',
    'e2e/tests/staging-auth.spec.cjs',
  ]) {
    assert.equal(fs.existsSync(pathInFrontend(file)), true, file)
  }
})
