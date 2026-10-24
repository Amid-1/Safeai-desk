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
// Проверяем, что после переноса компонентов Models старые копии
// не остались в controller/: одинаковые файлы вызывают ложные
// предупреждения IDE и создают риск исправить неиспользуемую копию.
const modelViewFiles = [
    'AdminModelsPageView.tsx',
    'AdminModelsUpper.tsx',
    'CatalogEmptyState.tsx',
]
for (const filename of modelViewFiles) {
    const current = path.join(src, 'features', 'models', 'ui', filename)
    const obsolete = path.join(src, 'features', 'models', 'controller', filename)
    if (!fs.existsSync(current)) {
        errors.push('Отсутствует UI-компонент models/ui/' + filename)
    }
    if (fs.existsSync(obsolete)) {
        errors.push('Дублирующий UI-компонент в models/controller/: ' + filename)
    }
    if (fs.existsSync(current)) {
        const text = fs.readFileSync(current, 'utf8')
        const header = '// frontend/src/features/models/ui/' + filename
        const headerCount = text.split('\n').filter(line => line === header).length
        if (headerCount !== 1) {
            errors.push('Заголовок пути должен встречаться один раз: models/ui/' + filename)
        }
    }
}

console.log('Проверено расположение файлов и заголовков:', checked)
if (errors.length) {
    for (const error of errors) console.error('ОШИБКА:', error)
    process.exitCode = 1
} else {
    console.log('Ошибок расположения страниц и заголовков: 0.')
}
