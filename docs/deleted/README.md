# SafeAI Desk — Production Erasure Roadmap v1.1

Дата: 24.11.2026. Статус: REVIEWED ARCHITECTURE, NO PRODUCTION GO.

Файлы:
- `roadmap.md`: полный самодостаточный план внедрения R0–R9.
- `REVIEW_NOTES.md`: изменения и результаты проверки.
- `SHA256SUMS`: контрольные суммы.

Для IntelliJ IDEA: откройте `roadmap.md` как Markdown.
Жёсткая ширина строк — 108 символов. Горизонтально широких таблиц нет.
При необходимости включите Editor -> General -> Soft Wraps.

Для установки в репозиторий (из корня, после просмотра diff):

```bash
cp /path/to/extracted/roadmap.md docs/production/erasure-roadmap.md
git diff --check
git diff -- docs/production/erasure-roadmap.md
```

Важно: `openapi-erasure-DRAFT.yaml` версии 1.0 является предыдущим черновиком.
Перед разработкой R1 его надо синхронизировать с контрактом v1.1 и пройти
contract tests. Никакие миграции и реальные данные этот архив не изменяет.
