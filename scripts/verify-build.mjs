import {
    readFile,
    readdir,
} from 'node:fs/promises'
import { join } from 'node:path'
import process from 'node:process'

const DIST_DIR = new URL('../dist/', import.meta.url)
const MANIFEST_URL = new URL('../dist/.vite/manifest.json', import.meta.url)

const TEXT_EXTENSIONS = new Set([
    '.css',
    '.html',
    '.js',
    '.json',
    '.svg',
    '.txt',
])

const GENERIC_SECRET_PATTERNS = [
    {
        name: 'OpenAI-style API key',
        pattern: /\bsk-[A-Za-z0-9_-]{20,}\b/g,
    },
    {
        name: 'Anthropic API key',
        pattern: /\bsk-ant-[A-Za-z0-9_-]{20,}\b/g,
    },
    {
        name: 'PEM private key',
        pattern: /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/g,
    },
]

function getExtension(fileName) {
    const dotIndex = fileName.lastIndexOf('.')

    return dotIndex >= 0
        ? fileName.slice(dotIndex)
        : ''
}

async function collectFiles(directoryUrl, relativePath = '') {
    const entries = await readdir(directoryUrl, {
        withFileTypes: true,
    })

    const files = []

    for (const entry of entries) {
        const childRelativePath = relativePath
            ? join(relativePath, entry.name)
            : entry.name

        const childUrl = new URL(
            `${childRelativePath.replaceAll('\\', '/')}${entry.isDirectory() ? '/' : ''}`,
            DIST_DIR,
        )

        if (entry.isDirectory()) {
            files.push(
                ...await collectFiles(
                    childUrl,
                    childRelativePath,
                ),
            )
        } else {
            files.push(childRelativePath)
        }
    }

    return files
}

function requireCondition(condition, message) {
    if (!condition) {
        throw new Error(message)
    }
}

const files = await collectFiles(DIST_DIR)

const sourceMaps = files.filter((file) => file.endsWith('.map'))

requireCondition(
    sourceMaps.length === 0,
    `Production bundle contains source maps: ${sourceMaps.join(', ')}`,
)

const configuredSecrets = [
    process.env.OPENAI_API_KEY,
    process.env.ANTHROPIC_API_KEY,
    process.env.SAFEAI_JWT_SECRET,
]
    .filter((value) => typeof value === 'string' && value.length >= 8)

for (const file of files) {
    if (!TEXT_EXTENSIONS.has(getExtension(file))) {
        continue
    }

    const content = await readFile(
        new URL(file.replaceAll('\\', '/'), DIST_DIR),
        'utf8',
    )

    for (const secret of configuredSecrets) {
        requireCondition(
            !content.includes(secret),
            `Production secret value was found in dist/${file}`,
        )
    }

    for (const {
        name,
        pattern,
    } of GENERIC_SECRET_PATTERNS) {
        pattern.lastIndex = 0

        requireCondition(
            !pattern.test(content),
            `${name} was found in dist/${file}`,
        )
    }
}

const manifest = JSON.parse(
    await readFile(MANIFEST_URL, 'utf8'),
)

const mainEntry = Object.values(manifest).find(
    (entry) => entry.isEntry,
)

const usageEntry = Object.entries(manifest).find(
    ([source]) => source.endsWith(
        'src/pages/AdminUsagePage.tsx',
    ),
)?.[1]

requireCondition(
    mainEntry,
    'Vite manifest does not contain the application entry.',
)

requireCondition(
    usageEntry,
    'Vite manifest does not contain AdminUsagePage.',
)

requireCondition(
    usageEntry.isDynamicEntry === true,
    'AdminUsagePage is not emitted as a lazy dynamic entry.',
)

requireCondition(
    usageEntry.file !== mainEntry.file,
    'AdminUsagePage is bundled into the initial application chunk.',
)

console.log('Production bundle verification passed.')
console.log(`Initial entry: ${mainEntry.file}`)
console.log(`Admin usage lazy chunk: ${usageEntry.file}`)
