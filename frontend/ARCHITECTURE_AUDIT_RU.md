# SafeAI Desk Frontend — архитектурный аудит зависимостей

> **Актуализировано:** 25 октября 2026 года.
> **Файл в репозитории:** `frontend/ARCHITECTURE_AUDIT_RU.md`.
> **Область:** только архитектурная проверка frontend TypeScript/React и её автоматизация.
> **Подтверждение состояния:** вывод локальных команд пользователя от 25.10.2026 на ветке `refactor/frontend-audit-boundary`, предоставленные исходники скриптов и пакет изменений `frontend-review-after-ci.zip`.
> **Смежные документы:** `frontend/README.md` (устройство приложения) и `frontend/FRONTEND_AUDIT_REPORT_2026-10-25.md` (все работы, исправления и результаты CI).

## 1. Назначение

Аудит выявляет нежелательные связи в коде **до выполнения приложения**. Он не меняет production-исходники и HTTP/API-контракты. Результат — перечень зависимостей, циклов и нарушений слоёв, а в строгом режиме — код завершения, пригодный для CI.

Архитектура проекта — слоистая, feature-oriented: `app`, `pages`, `features`, `entities`, `shared`, тестовые модули. Проверка позволяет сохранять уже исправленные границы после следующих рефакторингов.

Это **не аудит информационной безопасности**: успешная проверка импортов не подтверждает корректность backend ACL, RBAC, tenant isolation, CSRF, обработки файлов или авторизации.

## 2. Инструменты и их ответственность

| Файл внутри `frontend/` | Назначение | Характер проверки |
| --- | --- | --- |
| `scripts/audit-dependencies.cjs` | Читает TypeScript AST, строит граф импортов, находит SCC (Tarjan), нарушения слоёв, зависимости production → tests и отсутствующие относительные пути, формирует JSON/Markdown | Диагностический; с `--strict` — CI gate для определённого набора нарушений |
| `scripts/audit-dependencies.test.cjs` | Три регрессионных теста алгоритма Tarjan, классификации зависимостей и запуска на изолированном fixture | Проверка самого анализатора |
| `scripts/architecture-feature-rules.cjs` | Узкое правило: `features/audit` не должен импортировать `features/admin` | Архитектурная политика конкретной границы |
| `scripts/architecture-feature-rules.test.cjs` | Четыре теста запрещённого и разрешённых направлений | Проверка политики границы |
| `scripts/check-architecture.cjs` | Разрешимость относительных импортов, обратные слоевые зависимости и вызов `isForbiddenFeatureDependency` | Блокирующая проверка импортов, включая `audit → admin` |
| `scripts/verify-source-layout.cjs` | Расположение route pages, присутствие девяти страниц, соответствие заголовков путям и отсутствие старых копий UI моделей в `controller/` | Блокирующая проверка структуры |

**Различать:** `audit-dependencies.cjs --strict` **не** применяет отдельное правило `audit → admin`. Это правило выполняется через `check-architecture.cjs`; поэтому необходима **вся** команда `npm run architecture:check`, а не только самостоятельный запуск сканера.

Скрипты `.cjs` — CommonJS-инструменты для Node.js, **не** часть браузерного React bundle. Их тесты запускаются встроенным `node:test`, а не Vitest.

## 3. Установка и системные требования

Файлы уже добавлены в рабочее дерево текущей ветки в ходе аудита 24–25 октября; инструкции из первоначального архивного патча «скопируйте архив в корень» теперь **исторические** и не требуются для этой ветки. Не распаковывайте старые патчи поверх актуальных `package.json`, `README.md` или исходников.

В `frontend/package.json` заявлены Node.js `>=24.0.0 <25` и npm 11. TypeScript — локальная devDependency. **Последний подтверждённый прогон** сделан на macOS с Node.js 26.5.0 и npm 11.17.0, с `NODE_OPTIONS=--no-experimental-webstorage` для Vitest. Проверка на официально заявленной Node.js 24.x остаётся отдельной задачей.

Из корня репозитория:

```bash
cd frontend
npm ci
```

`npm ci` устанавливает зависимости по lock-файлу; не нужно запускать его повторно только ради чтения отчёта, если зависимости уже установлены.

## 4. Команды

### 4.1. Полный архитектурный контроль (предпочтительный вариант)

```bash
cd frontend
npm run architecture:check
```

**Фактическая цепочка после патча v9 (25.10.2026):**

```bash
node --test scripts/architecture-feature-rules.test.cjs scripts/audit-dependencies.test.cjs
node scripts/check-architecture.cjs
node scripts/verify-source-layout.cjs
node scripts/audit-dependencies.cjs --strict
```

В `package.json` команды соединены так, чтобы неуспех одной останавливал цепочку. Это **7 отдельных тестов Node** (4 для feature boundary и 3 для анализатора) плюс проверки исходников.

### 4.2. Независимый запуск сканера

```bash
node --test scripts/audit-dependencies.test.cjs
node scripts/audit-dependencies.cjs
node scripts/audit-dependencies.cjs --strict
```

В обычном режиме сканер сообщает проблемы, но возвращает `0`, если успешно выполнил анализ. В режиме `--strict` возвращает `1`, если обнаружен хотя бы один блокирующий пункт. Ошибка запуска или конфигурации приводит к другому ненулевому коду (в текущей реализации `2`).

Вывести отчёт в отдельный каталог:

```bash
node scripts/audit-dependencies.cjs --out "$HOME/SafeAI-Audit/frontend-2026-10-25"
```

Просканировать другой checkout с собственной конфигурацией:

```bash
node scripts/audit-dependencies.cjs \
  --root /path/to/SafeAI-Desk/frontend \
  --out /path/to/report-directory \
  --strict
```

Просмотреть доступные аргументы:

```bash
node scripts/audit-dependencies.cjs --help
```

### 4.3. Полный frontend CI

```bash
NODE_OPTIONS=--no-experimental-webstorage npm run ci
```

Переменная `NODE_OPTIONS` показана **для подтверждённого локального окружения Node 26**, а не как обязательный параметр Node 24. Сам `ci` дополнительно проверяет ESLint, Vitest/coverage и production build согласно `package.json`.

## 5. Что именно анализирует TypeScript-сканер

1. Обходит файлы `src` с расширениями `.ts`, `.tsx`, `.mts`, `.cts` и пропускает декларационные `.d.ts`/`.d.mts`/`.d.cts`.
2. Загружает опции разрешения модулей из `tsconfig.app.json` через TypeScript Compiler API.
3. Разбирает исходники в AST и извлекает статические `import`, `export ... from`, `import type`, `export type`, `import = require`, литеральные `import('…')`, `require('…')`, `vi.mock('…')`, `jest.mock('…')` и `import('…')` в типах.
4. Разрешает локальные пути; отдельно регистрирует ассеты (CSS/изображения/JSON), а не считает их TS-модулями графа.
5. Отделяет production-файлы от тестов и фикстур по имени файла/каталога.
6. Строит граф production-модулей **без type-only** рёбер для runtime-циклов и граф **со всеми** рёбрами для дополнительных циклов с типами.
7. Строит отдельный направленный граф связей между `features`; аналогично регистрирует межсущностные (`entities`) связи.
8. Формирует `safeai-frontend-dependencies.json` и `safeai-frontend-dependencies.md`.

**Граница точности:** это сканирование синтаксиса и разрешения путей, а не полная компиляция или выполнение программы. Комплексную проверку типов даёт `npm run typecheck`.

### 5.1. Алгоритм Tarjan (SCC)

**Strongly Connected Component** — группа вершин направленного графа, в которой от каждой вершины есть путь к каждой другой. Алгоритм Тарьяна ищет такие группы за время порядка `O(V + E)`.

- Для runtime-графа циклическая группа означает настоящий цикл между production-модулями, исходя из извлечённых runtime-импортов.
- Для графа с `import type` цикл может существовать только на уровне типов и **не** обязательно означает runtime-инициализационный цикл.
- Self-import учитывается как цикл из одной вершины.
- Отчёт показывает **один действительный пример пути** и полный список модулей SCC; он не перечисляет все возможные циклы внутри группы.
- На уровне features возможна циклическая группа `A ↔ B`, даже если сам граф файлов **не имеет** циклов, например из-за различных файлов-фасадов. Поэтому feature-граф выводится отдельно.

### 5.2. Запрещённые слоевые направления

```text
app      → pages, features, entities, shared
pages    → features, entities, shared
features → entities, shared
entities → shared
shared   → shared
```

Стрелки здесь — направление **разрешённого использования**, а не требование обязательно импортировать каждый предыдущий слой. Допустимы зависимости внутри одного слоя и отдельно контролируемые `features → features`; запрещены обратные связи:

- `shared → entities/features/pages/app`;
- `entities → features/pages/app`;
- `features → pages/app`;
- `pages → app` (в строгом анализаторе).

Детальная feature-specific политика дополнительно запрещает **`features/audit → features/admin`** в `check-architecture.cjs`.

### 5.3. Что делает `--strict`, а что — нет

| Находка | `audit-dependencies.cjs --strict` | `npm run architecture:check` целиком |
| --- | --- | --- |
| Runtime SCC/цикл production | Ошибка | Ошибка |
| Запрещённое направление слоёв | Ошибка | Ошибка |
| Production импортирует тесты/фикстуры | Ошибка | Ошибка |
| Отсутствующий относительный импорт | Ошибка | Ошибка |
| Ошибка синтаксического разбора TS | Ошибка | Ошибка |
| Цикл только с `import type` | Отчёт, не отдельный gate | Отчёт |
| Любая межfeature-связь | Отчёт, не gate | Отчёт, если не нарушает специальное правило |
| Циклическая группа между features без runtime SCC | Отчёт, не gate | Отчёт; отдельного общего запрета всех feature-SCC нет |
| Конкретное `audit → admin` | Не проверяет как отдельное правило | **Ошибка** через `check-architecture.cjs` |
| Отсутствие route page / устаревший заголовок пути | Не проверяет | **Ошибка** через `verify-source-layout.cjs` |

Это важно при самостоятельном использовании одного из скриптов: отсутствие ошибок `--strict` **не равно** выполнению всех правил проекта.

## 6. Отчёты и интерпретация

При запуске без `--out` файлы сохраняются **вне репозитория** в `$HOME/SafeAI-Audit/`:

- `safeai-frontend-dependencies.md` — читаемая сводка, найденные цепочки, примеры строк импорта и ограничения анализа.
- `safeai-frontend-dependencies.json` — машинно-читаемые поля `metadata`, `totals`, `runtimeCycles`, `allCycles`, `featureCycles`, `featureDependencies`, `layerViolations`, `productionTestEdges`, `unresolvedImports` и др.

`metadata` включает время генерации (UTC), версию TypeScript, Git HEAD/ветку и признак чистоты дерева, **если Git-команды доступны**. `gitClean: false` не делает сам аудит неправильным — он означает, что результат относится к конкретной рабочей копии, а не обязательно к опубликованному коммиту.

Сканер только **читает `src`**, но пишет отчёты в `--out` (по умолчанию вне проекта). Если явно передать `--out` внутри репозитория, новые файлы появятся там — это следует учитывать перед `git add`.

## 7. Подтверждённый локальный результат на 25.10.2026

После удаления обратной зависимости `audit → admin`, установки новых правил и запуска v9 пользователь получил:

```text
✔ Tarjan returns only strongly connected components, including self loop
✔ layer constraints and production/test identification
✔ scanner distinguishes runtime cycles, type cycles, forbidden layers and assets
ℹ tests 3
ℹ pass 3
ℹ fail 0

SAFEAI FRONTEND ARCHITECTURE AUDIT
TS/TSX: 417; production: 286; tests: 131
Runtime cycles: 0; cycles with types: 0
Layer violations: 0; prod->test: 0; unresolved: 0
Cross-feature directions: 7; feature cycle groups: 0
```

Проверка `node scripts/audit-dependencies.cjs --strict` завершилась успешно; аналогичный анализатор включён в `npm run ci`, а полный прогон v9 подтвердил:

- `npm run ci` — **exit code 0**;
- `Test Files 127 passed (127)`;
- `Tests 1155 passed (1155)` (**1 155 / 1 155** Vitest-тестов);
- `Production bundle verification passed`;
- `Runtime cycles: 0; cycles with types: 0`;
- `Layer violations: 0; prod->test: 0; unresolved: 0`;
- `git diff --check` и `git diff --cached --check` — **exit code 0** после исправления завершающих пустых строк в трёх новых файлах.

**Важно о подсчётах:** 417 — анализируемые TS/TSX-модули (без декларационных файлов); `verify-source-layout.cjs` проверяет также CSS и декларации и в последнем подтверждённом прогоне сообщал 449 исходных файлов. 127 — файлы тестов, выполненные Vitest; 131 — все файлы, отнесённые анализатором к тестовым (в том числе вспомогательные). Эти числа не обязаны совпадать.

Семь межfeature-направлений не являются семью нарушениями: специальный запрет `audit → admin` выполняется другим инструментом, а в текущем графе feature-циклов **0**.

## 8. История — первый архивный прогон 24.10.2026

**Архивная исходная точка, не результат текущей ветки.** Первый анализ был выполнен на `SafeAI-Frontend-FINAL-STRUCTURE.zip`:

- 393 TS/TSX source-файла без деклараций;
- 0 runtime-циклов и 0 циклов с учётом типов;
- 0 нарушений направления слоёв и 0 неразрешённых относительных импортов;
- 8 направлений между features;
- 1 циклическая группа features: **`admin ↔ audit`**.

Причина: совместимый фасад `features/admin/api/adminApi.ts` реэкспортировал функции аудита, а файлы `features/audit/` импортировали тот же `adminApi.ts`, создавая обратную зависимость.

**Историческое предупреждение:** `main` GitHub от 24.10.2026 (коммит `4cbef6579b2ec2e23e5462a3242de18e4d403a55`) содержал на 18 TS/TSX-файлов больше, чем исследованный ZIP. Старые 393 файла и 1 feature-SCC нельзя использовать как текущие метрики ни для `main`, ни для рабочей ветки без нового запуска.

### Этапы устранения (выполнены)

1. Созданы и проверены `audit-dependencies.cjs` и три его теста.
2. Проверен граф; обнаружен источник `audit → admin`.
3. Импорты аудита перенаправлены на принадлежащий ему `features/audit/api/adminAuditApi.ts` без удаления совместимого admin-фасада.
4. Добавлены `architecture-feature-rules.cjs` и четыре теста, запрет подключён в `check-architecture.cjs`.
5. `audit-dependencies.cjs --strict` и оба набора тестов архитектуры интегрированы в `npm run architecture:check` и, следовательно, в frontend CI (v9).
6. Результат 25.10.2026: **0 циклических групп features, 7 межfeature-направлений, 0 блокирующих нарушений**.

Эти шаги не означают, что автоматически создан/слит PR: локальная ветка и staging проверялись отдельно, **commit/push из аудита не выполнялись**.

## 9. Границы и задачи после аудита

**Статический анализатор не проверяет:**

- динамические импорты с вычисляемыми значениями (`import(variable)`), динамические `require()` и нестандартные механизмы резолвинга;
- CSS `@import`, runtime dependency injection, React Context и реальные сетевые обращения;
- совместимость frontend-backend контрактов и ответы API;
- правильность бизнес-алгоритмов, RBAC, tenant isolation, IDOR, CSRF или доступность секретов;
- работоспособность в реальных браузерах (E2E), безопасность загрузок и response headers;
- полноценную TypeScript typecheck/линтинг/уязвимости npm (для этого отдельные проверки).

**Открытые задачи:**

- Подтвердить полный CI на официально поддерживаемой **Node.js 24.x**; локальный PASS на Node 26 с workaround не заменяет этого.
- Выполнить browser E2E, интеграционные сценарии backend и ревью серверных ACL/RBAC отдельно от статической проверки.
- Периодически изучать разрешённые 7 направлений межfeature-зависимостей и не расширять их без архитектурной причины.
- При изменении слоевой политики обновлять сразу оба checker'а и соответствующие тесты, не вводя расхождений между скриптами.
- При необходимости отдельно согласовать policy на feature-level циклы: **текущий `--strict` не делает все такие группы блокирующими**.

## 10. Правила изменений и воспроизводимость

Перед PR:

```bash
cd frontend
npm run architecture:check
npm run typecheck
npm run lint
NODE_OPTIONS=--no-experimental-webstorage npm run ci  # подтверждённый Node 26 environment
cd ..
git diff --check
git diff --cached --check
```

Для заявленной Node 24 параметр `NODE_OPTIONS` может быть не нужен; проверить в целевом CI. До публикации любого нового результата сохранять вывод команд вместе с branch/commit/Node/npm, не представлять архивные метрики как текущие.

**Связанные документы:** `frontend/README.md`, `frontend/FRONTEND_AUDIT_REPORT_2026-10-25.md`.
