# SafeAI-Desk: качество кода, тесты и покрытие

Этот файл служит справочником по проверкам CI. Он не является инструкцией
по восстановлению или публикации Production.

## Обязательные проверки

- **Backend:** Java 21, Maven `clean verify`, JUnit, интеграционные тесты,
  правила архитектуры и сохранённый baseline JaCoCo.
- **Frontend:** TypeScript, ESLint, Vitest/V8 coverage, сборка и проверки
  архитектуры; Playwright запускается отдельным workflow.
- **Качество:** Qodana, security-проверки и контроль CI/CD.
- **Java hygiene:** `.github/scripts/check-java-style.py` блокирует новые
  базовые нарушения: BOM, CRLF, табуляцию, хвостовые пробелы и отсутствие
  завершающего перевода строки. Это не полноценный автоформаттер Java.

Исторические нарушения Java зафиксированы в
`.github/style/java-hygiene-baseline.json`. Изменять baseline следует только
после review, а не ради обхода ошибки CI.

## Где смотреть результаты

1. GitHub → Actions → «Проверка бэкенда» или «Проверка фронтенда».
2. В `Summary` открыть таблицу модулей и диаграммы текущего запуска.
3. В `Artifacts` скачать HTML-отчёты JaCoCo или Vitest.

Графики показывают **текущий запуск и сравнение с baseline**, а не
исторический ряд всех релизов. Пройденное покрытие не заменяет
интеграционные, нагрузочные и эксплуатационные проверки.

## Локальные команды

```bash
cd backend
./mvnw clean verify
cd ..
python3 .github/scripts/check-backend-coverage.py --check
python3 .github/scripts/check-java-style.py

cd frontend
npm ci
npm run ci
```

Последняя команда `npm run ci` включает проверки архитектуры, ESLint,
Vitest coverage и сборку. Для Testcontainers нужен работающий Docker.

## Связанные инструкции

- [Релиз и Production-допуск](README-release-publish-deploy.md)
- [Проверка восстановления PostgreSQL](README-restore-rehearsal.md)
