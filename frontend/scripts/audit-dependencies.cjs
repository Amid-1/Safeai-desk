// frontend/scripts/audit-dependencies.cjs
// Read-only architecture audit of TypeScript modules. Node.js 24 + local TypeScript.
// Run: node scripts/audit-dependencies.cjs [--out /path] [--strict]
'use strict'

const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const {execFileSync} = require('node:child_process')

function usage() {
    console.log(`SafeAI Desk frontend dependency audit\n\nUsage:\n  node scripts/audit-dependencies.cjs [--root FRONTEND] [--out DIRECTORY] [--strict]\n\nOptions:\n  --root  Frontend directory (default: parent of this script's directory)\n  --out   Report directory (default: ~/SafeAI-Audit)\n  --strict  Exit with status 1 for P1 findings (missing imports, illegal layer edges, runtime cycles, production -> test imports)\n  --help  Show this help\n\nThe script reads frontend source files and writes only its reports outside the source tree by default.`)
}

function parseArgs(argv) {
    const options = {
        root: path.resolve(__dirname, '..'),
        out: path.join(os.homedir(), 'SafeAI-Audit'),
        strict: false,
    }
    for (let i = 0; i < argv.length; i++) {
        const token = argv[i]
        if (token === '--help' || token === '-h') return {...options, help: true}
        if (token === '--strict') { options.strict = true; continue }
        if (token === '--root' || token === '--out') {
            if (i + 1 >= argv.length || argv[i + 1].startsWith('--')) {
                throw new Error(`Для ${token} нужно указать путь`)
            }
            options[token.slice(2)] = path.resolve(argv[++i])
            continue
        }
        throw new Error(`Неизвестный аргумент: ${token}`)
    }
    return options
}

function loadTypeScript(root) {
    try { return require(require.resolve('typescript', {paths: [root]})) }
    catch (error) {
        // In normal use TypeScript comes from frontend/node_modules.
        // The fallback supports a globally installed compiler in an isolated audit.
        try { return require('typescript') }
        catch { throw new Error('Не установлен TypeScript. Выполните npm ci в frontend/ перед аудитом.', {cause: error}) }
    }
}

function walk(directory, files) {
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        if (entry.isSymbolicLink()) continue
        const filename = path.join(directory, entry.name)
        if (entry.isDirectory()) walk(filename, files)
        else if (/\.(?:ts|tsx|mts|cts)$/.test(entry.name) && !/\.d\.(?:ts|mts|cts)$/.test(entry.name)) files.push(path.resolve(filename))
    }
}

function norm(file) { return file.split(path.sep).join('/') }
function isTest(relative) {
    return /(?:^|\/)(?:__tests__|test)(?:\/|$)/.test(relative) || /\.(?:test|spec)\.(?:ts|tsx|mts|cts)$/.test(relative)
}
function section(relative) {
    const parts = relative.split('/')
    return {
        layer: parts[0] === 'main.tsx' ? 'entry' : parts[0],
        feature: parts[0] === 'features' ? parts[1] : null,
        entity: parts[0] === 'entities' ? parts[1] : null,
    }
}
function safeGit(root, args) {
    try { return execFileSync('git', args, {cwd: root, encoding: 'utf8', timeout: 3000, stdio: ['ignore', 'pipe', 'ignore']}).trim() }
    catch { return null }
}

function tarjan(nodes, edges) {
    const graph = new Map(nodes.map(node => [node, new Set()]))
    for (const edge of edges) if (graph.has(edge.from) && graph.has(edge.to)) graph.get(edge.from).add(edge.to)
    let index = 0
    const stack = [], onStack = new Set(), ids = new Map(), lows = new Map(), groups = []
    function visit(v) {
        ids.set(v, index); lows.set(v, index); index++
        stack.push(v); onStack.add(v)
        for (const w of graph.get(v)) {
            if (!ids.has(w)) { visit(w); lows.set(v, Math.min(lows.get(v), lows.get(w))) }
            else if (onStack.has(w)) lows.set(v, Math.min(lows.get(v), ids.get(w)))
        }
        if (lows.get(v) === ids.get(v)) {
            const group = []
            let w
            do { w = stack.pop(); onStack.delete(w); group.push(w) } while (w !== v)
            if (group.length > 1 || graph.get(v).has(v)) groups.push(group.sort())
        }
    }
    for (const node of nodes) if (!ids.has(node)) visit(node)
    return {groups: groups.sort((a, b) => a[0].localeCompare(b[0])), graph}
}

function representativeCycle(group, graph) {
    const allowed = new Set(group)
    const states = new Map()
    const stack = []
    function dfs(v) {
        states.set(v, 1); stack.push(v)
        for (const w of graph.get(v) || []) {
            if (!allowed.has(w)) continue
            if (states.get(w) === 1) return [...stack.slice(stack.indexOf(w)), w]
            if (!states.has(w)) {
                const cycle = dfs(w)
                if (cycle) return cycle
            }
        }
        stack.pop(); states.set(v, 2)
        return null
    }
    for (const v of group) if (!states.has(v)) { const found = dfs(v); if (found) return found }
    return group.length ? [...group, group[0]] : []
}

function toCycleRecords(result) {
    return result.groups.map(group => ({
        size: group.length,
        members: group,
        example: representativeCycle(group, result.graph),
    }))
}

function checkLayer(from, to) {
    const a = section(from), b = section(to)
    const forbidden = {
        shared: ['entities', 'features', 'pages', 'app'],
        entities: ['features', 'pages', 'app'],
        features: ['pages', 'app'],
        pages: ['app'],
    }
    return (forbidden[a.layer] || []).includes(b.layer)
}

function readConfig(ts, root) {
    const filename = path.join(root, 'tsconfig.app.json')
    if (!fs.existsSync(filename)) throw new Error(`Не найден ${filename}`)
    const config = ts.readConfigFile(filename, ts.sys.readFile)
    if (config.error) throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText, '\n'))
    const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root, undefined, filename)
    if (parsed.errors.length) {
        throw new Error(parsed.errors.map(x => ts.flattenDiagnosticMessageText(x.messageText, '\n')).join('\n'))
    }
    return parsed.options
}

function audit(root, ts) {
    const src = path.join(root, 'src')
    if (!fs.existsSync(src)) throw new Error(`Каталог исходников отсутствует: ${src}`)
    const options = readConfig(ts, root)
    const files = []
    walk(src, files)
    files.sort()
    const known = new Set(files)
    const rel = x => norm(path.relative(src, x))
    const production = files.filter(f => !isTest(rel(f)))
    const prodSet = new Set(production)
    const edges = []
    const errors = [], assets = [], unresolved = []
    let imports = 0

    function resolve(from, specifier) {
        const result = ts.resolveModuleName(specifier, from, options, ts.sys).resolvedModule
        if (result && result.resolvedFileName) return path.resolve(result.resolvedFileName)
        if (!specifier.startsWith('.')) return null
        const base = path.resolve(path.dirname(from), specifier)
        const candidates = [base, `${base}.ts`, `${base}.tsx`, `${base}.mts`, `${base}.cts`, `${base}.d.ts`, `${base}.css`, `${base}.svg`, `${base}.png`, `${base}.json`, path.join(base, 'index.ts'), path.join(base, 'index.tsx')]
        return candidates.find(p => { try { return fs.statSync(p).isFile() } catch { return false } }) || null
    }

    for (const file of files) {
        const content = fs.readFileSync(file, 'utf8')
        const syntax = /\.tsx$/.test(file) ? ts.ScriptKind.TSX : ts.ScriptKind.TS
        const ast = ts.createSourceFile(file, content, ts.ScriptTarget.Latest, true, syntax)
        if (ast.parseDiagnostics.length) errors.push({file: rel(file), message: 'Ошибка синтаксиса TypeScript'})

        function add(specifier, typeOnly, kind, line) {
            imports++
            const target = resolve(file, specifier)
            if (!target) {
                if (specifier.startsWith('.')) unresolved.push({from: rel(file), specifier, line})
                return
            }
            if (!known.has(target)) {
                if (target.startsWith(src + path.sep)) assets.push({from: rel(file), to: rel(target), kind})
                return
            }
            edges.push({from: rel(file), to: rel(target), typeOnly: !!typeOnly, kind, line})
        }

        function visit(node) {
            const line = ast.getLineAndCharacterOfPosition(node.getStart(ast)).line + 1
            if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
                const clause = node.importClause
                const named = clause && clause.namedBindings
                const fullyType = !!(clause && (clause.isTypeOnly || (!clause.name && named && ts.isNamedImports(named) && named.elements.length && named.elements.every(e => e.isTypeOnly))))
                add(node.moduleSpecifier.text, fullyType, 'import', line)
            } else if (ts.isExportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
                const named = node.exportClause
                const fullyType = !!(node.isTypeOnly || (named && ts.isNamedExports(named) && named.elements.length && named.elements.every(e => e.isTypeOnly)))
                add(node.moduleSpecifier.text, fullyType, 'export', line)
            } else if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference) && node.moduleReference.expression && ts.isStringLiteral(node.moduleReference.expression)) {
                add(node.moduleReference.expression.text, node.isTypeOnly, 'import-equals', line)
            } else if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument) && ts.isStringLiteral(node.argument.literal)) {
                add(node.argument.literal.text, true, 'import-type', line)
            } else if (ts.isCallExpression(node) && node.arguments.length > 0 && ts.isStringLiteral(node.arguments[0])) {
                const exp = node.expression
                if (exp.kind === ts.SyntaxKind.ImportKeyword) add(node.arguments[0].text, false, 'dynamic-import', line)
                else if (ts.isIdentifier(exp) && exp.text === 'require') add(node.arguments[0].text, false, 'require', line)
                else if (ts.isPropertyAccessExpression(exp) && ['vi', 'jest'].includes(exp.expression.getText(ast)) && ['mock', 'doMock', 'importActual'].includes(exp.name.text)) {
                    add(node.arguments[0].text, false, 'test-mock', line)
                }
            }
            ts.forEachChild(node, visit)
        }
        visit(ast)
    }

    const layerViolations = [], productionTestEdges = [], crossFeatures = new Map(), crossEntities = new Map()
    const productionEdges = edges.filter(edge => prodSet.has(path.join(src, edge.from)) && prodSet.has(path.join(src, edge.to)))
    // prodSet uses absolute paths, above conversion assumes POSIX-safe relative paths in a Linux/Mac project.
    for (const edge of edges) {
        if (checkLayer(edge.from, edge.to) && !isTest(edge.from)) layerViolations.push(edge)
        if (!isTest(edge.from) && isTest(edge.to)) productionTestEdges.push(edge)
        if (isTest(edge.from) || isTest(edge.to)) continue
        const a = section(edge.from), b = section(edge.to)
        if (a.layer === 'features' && b.layer === 'features' && a.feature !== b.feature) {
            const key = `${a.feature} -> ${b.feature}`
            if (!crossFeatures.has(key)) crossFeatures.set(key, [])
            crossFeatures.get(key).push(edge)
        }
        if (a.layer === 'entities' && b.layer === 'entities' && a.entity !== b.entity) {
            const key = `${a.entity} -> ${b.entity}`
            if (!crossEntities.has(key)) crossEntities.set(key, [])
            crossEntities.get(key).push(edge)
        }
    }

    const prodNames = production.map(rel)
    const runtimeEdges = productionEdges.filter(e => !e.typeOnly)
    const runtimeResult = tarjan(prodNames, runtimeEdges)
    const allResult = tarjan(prodNames, productionEdges)
    const runtimeCycles = toCycleRecords(runtimeResult)
    const allCycles = toCycleRecords(allResult)
    const runtimeMembers = new Set(runtimeCycles.flatMap(c => c.members))
    const cyclesWithTypeImports = allCycles.filter(c => !c.members.every(m => runtimeMembers.has(m)))
    // Full SCCs can merge several cycles; the report lists both full and runtime SCCs.

    const featureNames = [...new Set(production.map(rel).filter(r => r.startsWith('features/')).map(r => r.split('/')[1]))].sort()
    const featureEdges = [...crossFeatures.keys()].map(key => {
        const [from, to] = key.split(' -> ')
        return {from, to}
    })
    const featureResult = tarjan(featureNames, featureEdges)

    return {
        metadata: {
            generatedAt: new Date().toISOString(),
            root,
            commit: safeGit(root, ['rev-parse', '--short=12', 'HEAD']),
            branch: safeGit(root, ['branch', '--show-current']),
            gitClean: (() => {const s=safeGit(root,['status','--porcelain']);return s === null ? null : s === ''})(),
            typescriptVersion: ts.version,
        },
        totals: {
            sourceFiles: files.length,
            productionFiles: production.length,
            testFiles: files.length - production.length,
            importsEncountered: imports,
            internalEdges: edges.length,
            productionEdges: productionEdges.length,
            runtimeCycles: runtimeCycles.length,
            cyclesWithTypes: allCycles.length,
            layerViolations: layerViolations.length,
            productionTestEdges: productionTestEdges.length,
            unresolvedImports: unresolved.length,
            parseErrors: errors.length,
            featureDependencies: crossFeatures.size,
            featureCycleGroups: featureResult.groups.length,
            otherEntityDependencies: crossEntities.size,
            assetImports: assets.length,
        },
        runtimeCycles,
        allCycles,
        cyclesWithTypeImports,
        featureCycles: toCycleRecords(featureResult),
        featureDependencies: [...crossFeatures].sort((a,b) => a[0].localeCompare(b[0])).map(([direction, links]) => ({direction, count: links.length, examples: links.slice(0, 12)})),
        entityDependencies: [...crossEntities].sort((a,b) => a[0].localeCompare(b[0])).map(([direction, links]) => ({direction, count: links.length})),
        layerViolations,
        productionTestEdges,
        unresolvedImports: unresolved,
        parseErrors: errors,
    }
}

function markdown(report) {
    const t = report.totals
    const lines = [
        '# SafeAI Desk — аудит зависимостей frontend', '',
        `Дата: ${report.metadata.generatedAt}`, '',
        `Исходники: \`${report.metadata.root}\``,
        `Коммит: ${report.metadata.commit || 'не определён'}; ветка: ${report.metadata.branch || 'не определена'}; рабочая директория чистая: ${report.metadata.gitClean === null ? 'неизвестно' : report.metadata.gitClean ? 'да' : 'нет'}`,
        `TypeScript: ${report.metadata.typescriptVersion}`, '',
        '## Сводка', '',
        '| Проверка | Значение |', '| --- | ---: |',
        `| TypeScript файлов | ${t.sourceFiles} |`,
        `| Production файлов | ${t.productionFiles} |`,
        `| Тестовых файлов | ${t.testFiles} |`,
        `| Внутренних зависимостей файлов | ${t.internalEdges} |`,
        `| **Runtime-циклов (группы SCC)** | **${t.runtimeCycles}** |`,
        `| Циклов с учетом type-only (группы SCC) | ${t.cyclesWithTypes} |`,
        `| Нарушений направления слоёв | ${t.layerViolations} |`,
        `| Production → test | ${t.productionTestEdges} |`,
        `| Относительных импортов без файла | ${t.unresolvedImports} |`,
        `| Ошибок парсинга | ${t.parseErrors} |`,
        `| Направлений межfeature-зависимостей | ${t.featureDependencies} |`,
        `| Циклических групп features | ${t.featureCycleGroups} |`, '',
        'P1: runtime-циклы, запрещённые зависимости слоёв, production → test, отсутствующие относительные импорты. Межfeature-зависимости без нарушения слоёв показываются отдельно: они не обязательно являются ошибкой.', '',
    ]
    function cycleSection(title, cycles) {
        lines.push(`## ${title}`, '')
        if (!cycles.length) { lines.push('Не обнаружены.', ''); return }
        for (let i=0;i<cycles.length;i++) {
            const c=cycles[i]
            lines.push(`### ${i+1}. Группа из ${c.size} файлов`, '',
                'Один реальный путь по импорту:', '',
                '```text', c.example.join(' ->\n'), '```', '',
                '<details>', '<summary>Все файлы группы</summary>', '',
                ...c.members.map(m => `- \`${m}\``), '', '</details>', '')
        }
    }
    cycleSection('Runtime-циклы production', report.runtimeCycles)
    cycleSection('Циклы production с учётом import type / export type', report.allCycles)
    cycleSection('Циклы зависимостей между features', report.featureCycles)
    function list(title, items, render) {
        lines.push(`## ${title}`, '')
        if (!items.length) { lines.push('Не обнаружены.', ''); return }
        lines.push(...items.map(render), '')
    }
    list('Нарушения направления слоёв', report.layerViolations, x=> `- \`${x.from}:${x.line}\` → \`${x.to}\` (${x.typeOnly ? 'type' : 'runtime'})`)
    list('Зависимости production от тестов', report.productionTestEdges, x=> `- \`${x.from}:${x.line}\` → \`${x.to}\``)
    list('Не найденные относительные импорты', report.unresolvedImports, x=> `- \`${x.from}:${x.line}\`: \`${x.specifier}\``)
    list('Ошибки синтаксиса', report.parseErrors, x=>`- \`${x.file}\`: ${x.message}`)
    list('Межfeature-зависимости', report.featureDependencies, x=>{
        const examples = x.examples.slice(0,3).map(y => `\`${y.from}\` → \`${y.to}\``).join('; ')
        return `- **${x.direction}**: ${x.count} импортов. Примеры: ${examples}`
    })
    list('Межсущностные зависимости', report.entityDependencies, x=>`- **${x.direction}**: ${x.count} импортов`)
    lines.push('## Границы анализа', '',
        '- Анализируются статические TypeScript import/export, `import type`, `import("literal")`, `require("literal")` и прямые `vi.mock`/`jest.mock` со строковым аргументом.',
        '- Динамически вычисляемые пути, runtime-вызовы, CSS `@import`, DI/React context, API-контракты, сетевые запросы, права и безопасность выполнения здесь не проверяются.',
        '- Группа SCC означает, что файлы связаны направленными путями в обоих направлениях. Пример цепочки показывает реальный цикл, но не все пути в группе.',
        '- Если результат отличается от репозитория GitHub, приоритет у запуска на актуальном локальном checkout с зафиксированным коммитом.', '')
    return lines.join('\n')
}

function run(argv=process.argv.slice(2)) {
    const options = parseArgs(argv)
    if (options.help) { usage(); return 0 }
    const ts = loadTypeScript(options.root)
    const report = audit(options.root, ts)
    fs.mkdirSync(options.out, {recursive: true})
    const jsonPath = path.join(options.out, 'safeai-frontend-dependencies.json')
    const mdPath = path.join(options.out, 'safeai-frontend-dependencies.md')
    fs.writeFileSync(jsonPath, JSON.stringify(report, null, 2) + '\n', 'utf8')
    fs.writeFileSync(mdPath, markdown(report), 'utf8')
    console.log('SAFEAI FRONTEND ARCHITECTURE AUDIT')
    console.log(`TS/TSX: ${report.totals.sourceFiles}; production: ${report.totals.productionFiles}; tests: ${report.totals.testFiles}`)
    console.log(`Runtime cycles: ${report.totals.runtimeCycles}; cycles with types: ${report.totals.cyclesWithTypes}`)
    console.log(`Layer violations: ${report.totals.layerViolations}; prod->test: ${report.totals.productionTestEdges}; unresolved: ${report.totals.unresolvedImports}`)
    console.log(`Cross-feature directions: ${report.totals.featureDependencies}; feature cycle groups: ${report.totals.featureCycleGroups}`)
    console.log(`Report: ${mdPath}\nJSON:   ${jsonPath}`)
    if (report.runtimeCycles.length) for (const c of report.runtimeCycles.slice(0,10)) {
        console.log('RUNTIME CYCLE:', c.example.join(' -> '))
    }
    if (report.featureCycles.length) for (const c of report.featureCycles.slice(0,10)) {
        console.log('FEATURE CYCLE:', c.example.join(' -> '))
    }
    const high = report.totals.runtimeCycles || report.totals.layerViolations || report.totals.productionTestEdges || report.totals.unresolvedImports || report.totals.parseErrors
    return options.strict && high ? 1 : 0
}

if (require.main === module) {
    try { process.exitCode = run() }
    catch (error) { console.error('AUDIT ERROR:', error.message); process.exitCode = 2 }
}
module.exports = {audit, checkLayer, isTest, parseArgs, representativeCycle, tarjan}
