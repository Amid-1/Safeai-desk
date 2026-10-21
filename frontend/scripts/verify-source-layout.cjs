// frontend/scripts/verify-source-layout.cjs
// Защита от повторного помещения route-компонентов в feature-папки и устаревших заголовков.
const fs = require('node:fs')
const path = require('node:path')
const src = path.resolve(__dirname, '../src')
const routes = new Set([
    'AdminAuditPage.tsx', 'AdminModelsPage.tsx', 'AdminOrganizationsPage.tsx',
    'AdminUsagePage.tsx', 'AdminUsersPage.tsx', 'KnowledgePage.tsx',
    'KnowledgeDetailsPage.tsx', 'LoginPage.tsx', 'ChatPage.tsx',
])
const errors = []
let checked = 0
function walk(dir) {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const file = path.join(dir, entry.name)
        if (entry.isDirectory()) { walk(file); continue }
        if (!/\.(ts|tsx|css)$/.test(file)) continue
        const rel = path.relative(src, file).split(path.sep).join('/')
        const content = fs.readFileSync(file, 'utf8')
        const header = (file.endsWith('.css') ? '/* ' : '// ') + 'frontend/src/' + rel
        if (!content.startsWith(header)) errors.push('Нет актуального заголовка пути: ' + rel)
        if (routes.has(entry.name) && !rel.startsWith('pages/')) {
            errors.push('Маршрут ошибочно находится вне pages/: ' + rel)
        }
        checked++
    }
}
walk(src)
for (const route of routes) {
    if (!fs.existsSync(path.join(src, 'pages', route))) errors.push('Не найдена страница: ' + route)
}
console.log('Проверено расположение файлов и заголовков:', checked)
if (errors.length) {
    for (const error of errors) console.error('ОШИБКА:', error)
    process.exitCode = 1
} else {
    console.log('Ошибок расположения страниц и заголовков: 0.')
}
