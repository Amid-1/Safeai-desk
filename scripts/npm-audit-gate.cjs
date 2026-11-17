'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

// Owner-approved, dev-only risk acceptance. Never renew automatically.
const EXCEPTION = Object.freeze({
    url: 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf',
    source: 1247710,
    version: '4.0.7',
    integrity: 'sha512-qcJu88Q2IWqJsDD529JKMdwGm/dvInW4HvQnRwiH9JtihJvzGOscDtHE3x1pBKeUOTysQ8kVmLnJ2kJu7yhcGA==',
    expiresAt: '2026-12-01T00:00:00Z',
});

const reviewedNames = new Set([
    'picomatch', 'fdir', 'tinyglobby', 'vite', 'vitest', '@vitest/coverage-v8',
    '@vitejs/plugin-react', 'typescript-eslint', '@typescript-eslint/eslint-plugin',
    '@typescript-eslint/parser', '@typescript-eslint/type-utils',
    '@typescript-eslint/typescript-estree', '@typescript-eslint/utils',
]);
const levels = ['info', 'low', 'moderate', 'high', 'critical'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function assert(condition, message) {
    if (!condition) throw new Error(message);
}

function validateReport(report, status) {
    assert(object(report) && report.auditReportVersion === 2 && !Object.hasOwn(report, 'error'), 'Invalid npm audit response');
    assert(status === 0 || status === 1, `Unexpected npm audit exit: ${status}`);
    assert(object(report.vulnerabilities) && object(report.metadata?.vulnerabilities), 'Missing vulnerability data');
    const entries = Object.entries(report.vulnerabilities);
    const counts = Object.fromEntries(levels.map(level => [level, 0]));
    for (const [name, finding] of entries) {
        assert(object(finding) && finding.name === name && levels.includes(finding.severity), 'Malformed vulnerability');
        assert(Array.isArray(finding.via) && finding.via.length > 0 && Array.isArray(finding.nodes) && finding.nodes.length > 0, `Incomplete finding: ${name}`);
        counts[finding.severity]++;
    }
    for (const level of levels) assert(report.metadata.vulnerabilities[level] === counts[level], `Inconsistent audit count: ${level}`);
    assert(report.metadata.vulnerabilities.total === entries.length, 'Inconsistent total');
    // Audit at low exits 1 for any finding at low or above; unexpected status is not accepted.
    const shouldFail = entries.some(([, finding]) => finding.severity !== 'info');
    assert(status === Number(shouldFail), 'npm audit status/report mismatch');
    return entries;
}

function evaluateAudit({ full, fullStatus, production, productionStatus, lock, now = new Date() }) {
    const productionEntries = validateReport(production, productionStatus);
    assert(productionEntries.length === 0, 'Production dependency findings are never exempted');
    const entries = validateReport(full, fullStatus);
    if (entries.length === 0) return { accepted: false, message: 'PASS: no vulnerabilities (temporary exception not used; remove it if fixed)' };

    assert(now instanceof Date && Number.isFinite(now.valueOf()) && now.valueOf() < Date.parse(EXCEPTION.expiresAt), 'Temporary picomatch exception expired; update or explicitly review');
    assert(object(lock) && lock.lockfileVersion === 3 && object(lock.packages), 'Invalid package-lock.json');
    const packages = lock.packages;
    const installedPicomatches = Object.keys(packages).filter(name => /(?:^|\/)node_modules\/picomatch$/.test(name));
    assert(installedPicomatches.length === 1 && installedPicomatches[0] === 'node_modules/picomatch', 'Unexpected picomatch instances');
    const pinned = packages['node_modules/picomatch'];
    assert(pinned.dev === true && pinned.version === EXCEPTION.version && pinned.integrity === EXCEPTION.integrity && pinned.resolved === 'https://registry.npmjs.org/picomatch/-/picomatch-4.0.7.tgz', 'picomatch is not the reviewed dev-only package');

    const graph = new Map();
    let directAdvisories = 0;
    for (const [name, finding] of entries) {
        assert(reviewedNames.has(name) && finding.severity === 'high', `Unreviewed finding: ${name}`);
        assert(finding.nodes.length === 1 && finding.nodes[0] === `node_modules/${name}` && packages[finding.nodes[0]]?.dev === true, `Unreviewed or non-dev node: ${name}`);
        const edges = [];
        for (const via of finding.via) {
            if (typeof via === 'string') {
                assert(name !== 'picomatch' && Object.hasOwn(full.vulnerabilities, via), `Unknown dependency advisory: ${name} -> ${via}`);
                edges.push(via);
            } else {
                assert(name === 'picomatch' && object(via) && via.name === 'picomatch' && via.dependency === 'picomatch' && via.url === EXCEPTION.url && via.source === EXCEPTION.source && via.severity === 'high' && via.range === '<=4.0.7', `Unapproved direct advisory: ${name}`);
                directAdvisories++;
            }
        }
        graph.set(name, edges);
    }
    assert(directAdvisories === 1 && graph.has('picomatch'), 'Expected exactly one approved root advisory');
    // npm reports a cycle between Vitest and coverage-v8. Fixed-point reachability
    // accepts only nodes with a path to the reviewed root, never a detached cycle.
    const connected = new Set(['picomatch']);
    let changed;
    do {
        changed = false;
        for (const [name, edges] of graph) {
            if (!connected.has(name) && edges.some(edge => connected.has(edge))) {
                connected.add(name);
                changed = true;
            }
        }
    } while (changed);
    assert(connected.size === graph.size, 'Unexplained transitive vulnerability');
    return { accepted: true, message: `TEMPORARY EXCEPTION: ${EXCEPTION.url}; ${entries.length} dev findings, one root; expires ${EXCEPTION.expiresAt}; vulnerability NOT fixed` };
}

function runAudit(cwd, args) {
    const result = spawnSync('npm', ['audit', '--json', '--audit-level=low', ...args], {
        cwd, encoding: 'utf8', timeout: 300_000, maxBuffer: 32 * 1024 * 1024,
    });
    if (result.stdout) process.stdout.write(result.stdout + '\n');
    if (result.stderr) process.stderr.write(result.stderr);
    assert(!result.error && !result.signal, `npm audit execution failed: ${result.error || result.signal}`);
    return { report: JSON.parse(result.stdout), status: result.status };
}

if (require.main === module) {
    try {
        const cwd = path.resolve(__dirname, '../frontend');
        const lock = JSON.parse(fs.readFileSync(path.join(cwd, 'package-lock.json'), 'utf8'));
        const production = runAudit(cwd, ['--omit=dev', '--include=prod', '--include=optional', '--include=peer']);
        const full = runAudit(cwd, ['--include=prod', '--include=dev', '--include=optional', '--include=peer']);
        const result = evaluateAudit({ full: full.report, fullStatus: full.status, production: production.report, productionStatus: production.status, lock });
        console.warn(result.message);
    } catch (error) {
        console.error(`BLOCKED: ${error.message}`);
        process.exitCode = 1;
    }
}

module.exports = { evaluateAudit, EXCEPTION };
