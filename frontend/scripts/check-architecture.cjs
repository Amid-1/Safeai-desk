// frontend/scripts/check-architecture.cjs
// Проверка разрешимости относительных импортов и направления зависимостей Frontend.
// Запуск из frontend: npm run architecture:check
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const root = path.resolve(__dirname, '..')
const src = path.join(root, 'src')

const files = []
function walk(directory) {
    for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
        const filename = path.join(directory, item.name)
        if (item.isDirectory()) walk(filename)
        else if (/\.(ts|tsx)$/.test(filename)) files.push(filename)
    }
}
walk(src)

const errors = []
let resolvedImports = 0
const crossFeatureEdges = new Map()
const exists = (filename) => fs.existsSync(filename) && fs.statSync(filename).isFile()
const relative = (filename) => path.relative(src, filename).replace(/\\/g, '/')
function resolveImport(from, specifier) {
    const base = path.resolve(path.dirname(from), specifier)
    return [
        base, `${base}.ts`, `${base}.tsx`, `${base}.css`, `${base}.d.ts`,
        path.join(base, 'index.ts'), path.join(base, 'index.tsx'),
    ].find(exists)
}
function segment(file) {
    const parts = relative(file).split('/')
    return { name: parts[0], feature: parts[0] === 'features' ? parts[1] : undefined }
}
function check(from, specifier) {
    if (!specifier.startsWith('.')) return
    const to = resolveImport(from, specifier)
    if (!to) {
        errors.push(`${relative(from)}: не найден ${specifier}`)
        return
    }
    resolvedImports += 1
    const a = segment(from)
    const b = segment(to)
    const forbidden =
        (a.name === 'shared' && ['entities', 'features', 'pages', 'app'].includes(b.name)) ||
        (a.name === 'entities' && ['features', 'pages', 'app'].includes(b.name)) ||
        (a.name === 'features' && ['pages', 'app'].includes(b.name))
    if (forbidden) errors.push(`${relative(from)} → ${relative(to)}: нарушены границы слоёв`)
    if (a.name === 'features' && b.name === 'features' && a.feature !== b.feature) {
        const pair = `${a.feature} → ${b.feature}`
        crossFeatureEdges.set(pair, (crossFeatureEdges.get(pair) ?? 0) + 1)
    }
    if (!/\.(test|spec)\.(ts|tsx)$/.test(from) && !relative(from).startsWith('test/')
        && relative(to).startsWith('test/fixtures/')) {
        errors.push(`${relative(from)}: production-импорт тестовой фикстуры ${relative(to)}`)
    }
}
function visit(node, file) {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node))
        && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
        check(file, node.moduleSpecifier.text)
    }
    if (ts.isCallExpression(node) && node.arguments.length > 0
        && ts.isStringLiteral(node.arguments[0])) {
        if (node.expression.kind === ts.SyntaxKind.ImportKeyword ||
            (ts.isPropertyAccessExpression(node.expression)
                && /^(vi|jest)$/.test(node.expression.expression.getText())
                && ['mock', 'doMock', 'importActual'].includes(node.expression.name.text))) {
            check(file, node.arguments[0].text)
        }
    }
    ts.forEachChild(node, (child) => visit(child, file))
}
for (const file of files) {
    const text = fs.readFileSync(file, 'utf8')
    const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS
    const ast = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, kind)
    if (ast.parseDiagnostics.length) errors.push(`${relative(file)}: ошибка синтаксиса TypeScript`)
    visit(ast, file)
}
console.log(`Архитектура: ${files.length} TS/TSX, проверено ${resolvedImports} относительных импортов.`)
if (crossFeatureEdges.size) {
    console.log('Горизонтальные зависимости features, подлежащие поэтапному устранению:')
    for (const [pair, count] of [...crossFeatureEdges].sort()) console.log(`  ${pair}: ${count}`)
}
if (errors.length) {
    errors.forEach((error) => console.error('ОШИБКА:', error))
    process.exitCode = 1
} else {
    console.log('Отсутствующие импорты и нарушения направления слоёв: 0.')
}
