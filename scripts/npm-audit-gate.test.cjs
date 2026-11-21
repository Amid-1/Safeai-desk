'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluateAudit, EXCEPTION } = require('./npm-audit-gate.cjs');

function report(vulnerabilities = {}) {
    const counts = { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 };
    for (const finding of Object.values(vulnerabilities)) { counts[finding.severity]++; counts.total++; }
    return { auditReportVersion: 2, vulnerabilities, metadata: { vulnerabilities: counts } };
}
function finding(name, via, severity = 'high') {
    return { name, severity, via, nodes: [`node_modules/${name}`] };
}
function fixture() {
    const advisory = { source: EXCEPTION.source, name: 'picomatch', dependency: 'picomatch', url: EXCEPTION.url, severity: 'high', range: '<=4.0.7' };
    const findings = {
        picomatch: finding('picomatch', [advisory]),
        vite: finding('vite', ['picomatch']),
        vitest: finding('vitest', ['picomatch', '@vitest/coverage-v8']),
        '@vitest/coverage-v8': finding('@vitest/coverage-v8', ['vitest']),
    };
    const packages = Object.fromEntries(Object.keys(findings).map(name => [`node_modules/${name}`, { dev: true }]));
    Object.assign(packages['node_modules/picomatch'], { version: EXCEPTION.version, integrity: EXCEPTION.integrity, resolved: 'https://registry.npmjs.org/picomatch/-/picomatch-4.0.7.tgz' });
    return { full: report(findings), fullStatus: 1, production: report(), productionStatus: 0, lock: { lockfileVersion: 3, packages }, now: new Date('2026-11-17T19:00:00Z') };
}
test('allows only the pinned known dev advisory including the real Vitest cycle', () => {
    assert.equal(evaluateAudit(fixture()).accepted, true);
});
test('clean audit passes even after expiry without using exception', () => {
    const data = fixture(); data.full = report(); data.fullStatus = 0; data.now = new Date('2027-01-01');
    assert.equal(evaluateAudit(data).accepted, false);
});
test('expiry fails closed at the exact UTC deadline', () => {
    const data = fixture(); data.now = new Date(EXCEPTION.expiresAt);
    assert.throws(() => evaluateAudit(data), /expired/);
});
test('a production finding, even the same advisory, always blocks', () => {
    const data = fixture(); data.production = data.full; data.productionStatus = 1;
    assert.throws(() => evaluateAudit(data), /Production/);
});
test('wrong package version, integrity, non-dev or additional instance blocks', () => {
    for (const change of [{version: '4.0.8'}, {integrity: 'different'}, {dev: false}]) {
        const data = fixture(); Object.assign(data.lock.packages['node_modules/picomatch'], change);
        assert.throws(() => evaluateAudit(data), /reviewed dev-only/);
    }
    const data = fixture(); data.lock.packages['node_modules/vite/node_modules/picomatch'] = {dev: true};
    assert.throws(() => evaluateAudit(data), /instances/);
});
test('a second advisory on the same package is never implicitly allowed', () => {
    const data = fixture(); data.full.vulnerabilities.picomatch.via.push({ ...data.full.vulnerabilities.picomatch.via[0], source: 99 });
    assert.throws(() => evaluateAudit(data), /Unapproved/);
});
test('severity escalation, new packages, and separate advisories fail', () => {
    const data = fixture(); data.full.vulnerabilities.picomatch.severity = 'critical'; data.full = report(data.full.vulnerabilities);
    assert.throws(() => evaluateAudit(data), /Unreviewed/);
    const low = fixture(); low.full.vulnerabilities.other = finding('other', [{source: 10}], 'low'); low.full = report(low.full.vulnerabilities);
    assert.throws(() => evaluateAudit(low), /Unreviewed/);
    const known = fixture(); known.full.vulnerabilities.vite.via.push({ source: 555, url: 'https://example.invalid' });
    assert.throws(() => evaluateAudit(known), /Unapproved/);
});
test('audit malformed, truncated, failed or inconsistent response fails', () => {
    for (const change of [{full: {}}, {fullStatus: 2}, {fullStatus: 0}, {productionStatus: 1}]) {
        assert.throws(() => evaluateAudit({...fixture(), ...change}));
    }
    const data = fixture(); data.full.metadata.vulnerabilities.total++;
    assert.throws(() => evaluateAudit(data), /total/);
});
test('cannot exempt a disconnected cycle, a missing root or unknown node path', () => {
    const data = fixture(); data.full.vulnerabilities.vitest.via = ['@vitest/coverage-v8'];
    assert.throws(() => evaluateAudit(data), /Unexplained/);
    const missing = fixture(); delete missing.full.vulnerabilities.picomatch; missing.full = report(missing.full.vulnerabilities);
    assert.throws(() => evaluateAudit(missing), /Unknown/);
    const nonDev = fixture(); nonDev.lock.packages['node_modules/vite'].dev = false;
    assert.throws(() => evaluateAudit(nonDev), /non-dev node/);
});
