# Git: проверка изменений, создание коммита и отправка в удалённый репозиторий


Обновление из GitHub:
```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
git status
git pull origin main
```

Если локальных изменений нет
```bash
cd ~/Workspace/Projects/Products/SafeAI-Desk
git pull
```

Если Git сообщит о незакоммиченных изменениях
```bash
git stash
git pull origin main
git stash pop
```


```bash


```


```bash


```


## Общая последовательность

Безопасная последовательность работы выглядит так:

```bash
# 1. Посмотреть изменённые файлы
git status --short

# 2. Проверить изменения на технические ошибки форматирования
git diff --check

# 3. Запустить тесты проекта
./mvnw clean test

# 4. Добавить изменения в будущий коммит
git add -A

# 5. Убрать commit-message.txt из будущего коммита
git restore --staged commit-message.txt

# 6. Проверить подготовленные к коммиту изменения
git diff --cached --check
git diff --cached --stat
git diff --cached

# 7. Создать коммит, взяв сообщение из файла
git commit -F commit-message.txt

# 8. Проверить созданный коммит
git log -1 --stat
git status

# 9. Отправить текущую ветку в origin
git push -u origin HEAD
```

После первого успешного `push -u` для этой ветки следующие отправки выполняются короче:

```bash
git push
```

---

# 1. Проверка текущего состояния

## Краткий список изменённых файлов

```bash
git status --short
```

Команда показывает изменения в компактном виде.

Пример:

```text
 M src/main/java/App.java
A  src/test/java/AppTest.java
 D old-file.txt
?? commit-message.txt
```

Обозначения:

| Обозначение | Значение |
|---|---|
| `M` | файл изменён |
| `A` | файл добавлен |
| `D` | файл удалён |
| `R` | файл переименован |
| `??` | новый файл, который Git ещё не отслеживает |

В выводе два столбца:

- первый столбец — состояние в индексе, то есть файл уже добавлен через `git add`;
- второй столбец — изменения в рабочей папке, которые ещё не добавлены через `git add`.

Например:

```text
M  file.java
```

Файл уже подготовлен к коммиту.

```text
 M file.java
```

Файл изменён, но ещё не добавлен в коммит.

---

## Полная информация о состоянии

```bash
git status
```

Показывает:

- текущую ветку;
- подготовленные к коммиту файлы;
- неподготовленные изменения;
- новые неотслеживаемые файлы;
- состояние относительно удалённой ветки.

---

## Проверка текущей ветки

```bash
git branch --show-current
```

Пример результата:

```text
main
```

или:

```text
feature/rate-limit
```

---

## Проверка подключённого удалённого репозитория

```bash
git remote -v
```

Пример:

```text
origin  git@github.com:user/project.git (fetch)
origin  git@github.com:user/project.git (push)
```

`origin` — стандартное имя удалённого репозитория.

---

# 2. Проверка изменений перед коммитом

## Проверка пробелов и конфликтов форматирования

```bash
git diff --check
```

Команда ищет:

- пробелы в конце строк;
- неправильные пробельные символы;
- следы неразрешённых конфликтов;
- некоторые другие ошибки форматирования diff.

При отсутствии проблем команда ничего не выводит.

Пример ошибки:

```text
src/main/java/App.java:42: trailing whitespace.
```

Это означает, что в строке 42 остались лишние пробелы в конце.

---

## Просмотр всех изменений, ещё не добавленных в коммит

```bash
git diff
```

Показывает полный diff между рабочими файлами и индексом Git.

Эта команда не показывает изменения, уже добавленные через `git add`.

---

## Краткая статистика неподготовленных изменений

```bash
git diff --stat
```

Пример:

```text
 RedisFixedWindowRateLimiter.java | 25 +++++++++++++-------
 LoginRateLimitServiceTest.java   | 12 +++++-----
 2 files changed, 24 insertions(+), 13 deletions(-)
```

---

# 3. Запуск тестов

## Полный запуск тестов Maven

```bash
./mvnw clean test
```

Что делает команда:

- `./mvnw` — запускает Maven Wrapper проекта;
- `clean` — удаляет старую папку `target`;
- `test` — компилирует проект и запускает тесты.

Использование `clean` важно после изменения Java-классов, чтобы Maven не использовал старые скомпилированные файлы.

При успешном выполнении будет:

```text
BUILD SUCCESS
```

При ошибках:

```text
BUILD FAILURE
```

Перед коммитом желательно добиться:

```text
Tests run: ..., Failures: 0, Errors: 0
BUILD SUCCESS
```

---

## Запуск одного тестового класса

```bash
./mvnw clean \
  -Dtest=LoginRateLimitServiceTest \
  test
```

---

## Запуск одного тестового метода

```bash
./mvnw clean \
  -Dtest=LoginRateLimitServiceTest#withinLimitsUsesOneAtomicDualIncrement \
  test
```

---

## Запуск нескольких тестовых классов

```bash
./mvnw clean \
  -Dtest=LoginRateLimitServiceTest,RedisRateLimitServiceTest \
  test
```

---

# 4. Добавление изменений в коммит

Перед созданием коммита Git должен получить список файлов, которые войдут в него. Это называется staging или добавлением в индекс.

## Добавить все изменения проекта

```bash
git add -A
```

Добавляет:

- новые файлы;
- изменённые файлы;
- удалённые файлы;
- переименованные файлы.

Это наиболее полный вариант.

---

## Добавить изменения из текущей папки

```bash
git add .
```

Добавляет изменения из текущей папки и всех вложенных папок.

Разница:

```bash
git add -A
```

учитывает изменения во всём репозитории.

```bash
git add .
```

учитывает изменения начиная с текущей папки.

При запуске из корня репозитория результат обычно одинаковый.

---

## Добавить конкретный файл

```bash
git add src/main/java/ru/safeai/gateway/ratelimit/RedisFixedWindowRateLimiter.java
```

---

## Добавить несколько конкретных файлов

```bash
git add \
  src/main/java/ru/safeai/gateway/ratelimit/RedisFixedWindowRateLimiter.java \
  src/test/java/ru/safeai/gateway/ratelimit/LoginRateLimitServiceTest.java
```

---

## Добавить целую папку

```bash
git add src/main/java/ru/safeai/gateway/ratelimit
```

---

## Выбирать отдельные части изменений вручную

```bash
git add -p
```

Git будет показывать изменения отдельными блоками.

Основные варианты ответа:

| Команда | Значение |
|---|---|
| `y` | добавить этот блок |
| `n` | не добавлять этот блок |
| `s` | разбить блок на более мелкие |
| `q` | завершить |
| `a` | добавить этот и все следующие блоки |
| `d` | не добавлять этот и все следующие блоки |

Это удобно, когда в одном файле есть изменения для разных коммитов.

---

# 5. Исключение commit-message.txt из коммита

После:

```bash
git add -A
```

файл `commit-message.txt` тоже может попасть в индекс.

Чтобы убрать его из будущего коммита, но оставить на диске:

```bash
git restore --staged commit-message.txt
```

Файл не удаляется. Он только исключается из подготовленного коммита.

Старый эквивалент:

```bash
git reset -- commit-message.txt
```

Предпочтительнее использовать современную команду:

```bash
git restore --staged commit-message.txt
```

---

## Убрать из индекса несколько файлов

```bash
git restore --staged \
  commit-message.txt \
  temporary-notes.txt
```

---

## Полностью отменить подготовку всех файлов

```bash
git restore --staged .
```

Изменения останутся в рабочих файлах, но будут удалены из индекса.

---

# 6. Проверка того, что войдёт в коммит

После `git add` необходимо проверять именно staged-изменения.

## Проверить staged-изменения на ошибки форматирования

```bash
git diff --cached --check
```

Команда аналогична:

```bash
git diff --check
```

но проверяет только файлы, уже подготовленные к коммиту.

---

## Посмотреть краткую статистику будущего коммита

```bash
git diff --cached --stat
```

Пример:

```text
 RedisFixedWindowRateLimiter.java | 25 +++++++++++++-------
 LoginRateLimitServiceTest.java   | 12 +++++-----
 2 files changed, 24 insertions(+), 13 deletions(-)
```

---

## Посмотреть полный diff будущего коммита

```bash
git diff --cached
```

Показывает точное содержимое, которое попадёт в коммит.

---

## Посмотреть список файлов будущего коммита

```bash
git diff --cached --name-status
```

Пример:

```text
M	src/main/java/RedisFixedWindowRateLimiter.java
M	src/test/java/LoginRateLimitServiceTest.java
```

---

## Посмотреть только имена файлов

```bash
git diff --cached --name-only
```

---

# 7. Создание коммита

## Коммит с сообщением из файла

```bash
git commit -F commit-message.txt
```

Параметр:

```text
-F
```

означает: взять текст сообщения коммита из указанного файла.

Первая строка файла станет заголовком коммита.

Остальной текст станет подробным описанием.

Пример `commit-message.txt`:

```text
fix(rate-limit): стабилизация Redis rate limiter

- исправлена нормализация TTL
- добавлены интеграционные тесты
- удалены неиспользуемые методы
```

---

## Коммит с коротким сообщением

```bash
git commit -m "fix(rate-limit): исправлена обработка TTL"
```

Подходит для небольшого коммита.

---

## Коммит с заголовком и описанием

```bash
git commit \
  -m "fix(rate-limit): исправлена обработка TTL" \
  -m "Добавлена нормализация PTTL = -2 и расширены интеграционные тесты."
```

Первый `-m` — заголовок.

Второй `-m` — описание.

---

## Открыть редактор для сообщения коммита

```bash
git commit
```

Git откроет настроенный текстовый редактор.

---

## Изменить последний коммит

Добавить забытые изменения:

```bash
git add путь/к/файлу
git commit --amend --no-edit
```

`--no-edit` сохраняет старое сообщение коммита.

Изменить сообщение последнего коммита:

```bash
git commit --amend
```

После `amend` идентификатор коммита изменяется.

Если старый коммит уже был отправлен в удалённый репозиторий, обычный `git push` может быть отклонён. В таком случае используется:

```bash
git push --force-with-lease
```

Не рекомендуется использовать:

```bash
git push --force
```

`--force-with-lease` безопаснее, поскольку проверяет, что удалённая ветка не была изменена другим разработчиком.

---

# 8. Проверка созданного коммита

## Показать последний коммит и статистику

```bash
git log -1 --stat
```

Показывает:

- идентификатор коммита;
- автора;
- дату;
- сообщение;
- изменённые файлы;
- количество добавленных и удалённых строк.

---

## Показать последний коммит полностью

```bash
git show
```

Показывает сообщение и полный diff последнего коммита.

---

## Показать только сообщение последнего коммита

```bash
git log -1 --pretty=full
```

---

## Проверить состояние после коммита

```bash
git status
```

При отсутствии незакоммиченных изменений:

```text
nothing to commit, working tree clean
```

Если остался только локальный `commit-message.txt`, он может отображаться как неотслеживаемый файл:

```text
Untracked files:
  commit-message.txt
```

---

# 9. Отправка коммита в удалённый репозиторий

## Первый push текущей ветки

Самый простой вариант:

```bash
git push -u origin HEAD
```

Где:

- `origin` — имя удалённого репозитория;
- `HEAD` — текущая локальная ветка;
- `-u` — установить связь локальной ветки с удалённой.

После этого для следующих отправок достаточно:

```bash
git push
```

---

## Push с явным получением имени ветки

```bash
BRANCH="$(git branch --show-current)"
git push -u origin "$BRANCH"
```

Первая команда:

```bash
BRANCH="$(git branch --show-current)"
```

сохраняет имя текущей ветки в переменную `BRANCH`.

Например:

```text
feature/rate-limit
```

Вторая команда:

```bash
git push -u origin "$BRANCH"
```

отправляет эту ветку в `origin`.

---

## Push с явным именем ветки

```bash
git push -u origin feature/rate-limit
```

---

## Обычный push после настройки upstream

```bash
git push
```

---

## Проверить связь текущей ветки с удалённой

```bash
git branch -vv
```

Пример:

```text
* feature/rate-limit abc1234 [origin/feature/rate-limit] сообщение коммита
```

Это означает, что локальная ветка связана с:

```text
origin/feature/rate-limit
```

---

# 10. Исключение commit-message.txt из Git

## Локальное исключение только на своём компьютере

```bash
grep -qxF '/commit-message.txt' .git/info/exclude \
  || echo '/commit-message.txt' >> .git/info/exclude
```

Команда делает следующее:

1. Проверяет, есть ли строка `/commit-message.txt` в `.git/info/exclude`.
2. Если строки нет, добавляет её.
3. Если строка уже есть, ничего не меняет.

Файл:

```text
.git/info/exclude
```

работает аналогично `.gitignore`, но:

- действует только на текущем компьютере;
- не отправляется в репозиторий;
- не влияет на других разработчиков.

---

## Простой вариант без проверки на дубликаты

```bash
echo '/commit-message.txt' >> .git/info/exclude
```

При повторном выполнении строка может добавиться несколько раз, поэтому вариант с `grep` аккуратнее.

---

## Добавить файл в общий .gitignore

```bash
echo '/commit-message.txt' >> .gitignore
git add .gitignore
git commit -m "chore(git): исключён локальный файл сообщения коммита"
```

В этом случае правило попадёт в репозиторий и будет действовать у всех разработчиков.

---

## Важно: ignore не действует на уже отслеживаемые файлы

Если `commit-message.txt` уже когда-либо был добавлен в Git, одного `.git/info/exclude` недостаточно.

Проверка:

```bash
git ls-files --error-unmatch commit-message.txt
```

Если команда выводит имя файла, он уже отслеживается.

Чтобы удалить его только из Git, но сохранить на компьютере:

```bash
git rm --cached commit-message.txt
```

Затем добавить локальное исключение:

```bash
grep -qxF '/commit-message.txt' .git/info/exclude \
  || echo '/commit-message.txt' >> .git/info/exclude
```

После этого закоммитить удаление файла из репозитория:

```bash
git commit -m "chore(git): исключён локальный commit-message.txt"
```

---

# Полная рекомендуемая последовательность

```bash
# Перейти в корень backend
cd ~/Workspace/Projects/Products/SafeAI-Desk/backend

# Посмотреть текущую ветку и изменения
git branch --show-current
git status --short

# Проверить изменения
git diff --check
git diff --stat

# Запустить тесты
./mvnw clean test

# Добавить все изменения
git add -A

# Не включать файл сообщения в коммит
git restore --staged commit-message.txt

# Проверить будущий коммит
git diff --cached --check
git diff --cached --stat
git diff --cached

# Создать коммит
git commit -F commit-message.txt

# Проверить созданный коммит
git log -1 --stat
git status

# Первый push текущей ветки
git push -u origin HEAD
```

---

# Короткий вариант: add, commit и первый push

```bash
git add -A
git restore --staged commit-message.txt
git commit -F commit-message.txt
git push -u origin HEAD
```

---

# Короткий вариант: add, commit и обычный push

Используется, когда ветка уже связана с удалённой:

```bash
git add -A
git restore --staged commit-message.txt
git commit -F commit-message.txt
git push
```

---

# Только add

## Добавить всё

```bash
git add -A
```

## Добавить конкретные файлы

```bash
git add путь/к/файлу1 путь/к/файлу2
```

## Добавлять изменения по частям

```bash
git add -p
```

## Убрать файл из будущего коммита

```bash
git restore --staged путь/к/файлу
```

---

# Только commit

## Сообщение из файла

```bash
git commit -F commit-message.txt
```

## Короткое сообщение

```bash
git commit -m "fix: описание изменений"
```

## Заголовок и подробное описание

```bash
git commit \
  -m "fix: краткое описание" \
  -m "Подробное описание выполненных изменений."
```

---

# Только push

## Первый push текущей ветки

```bash
git push -u origin HEAD
```

## Обычный push

```bash
git push
```

## Push конкретной ветки

```bash
git push -u origin имя-ветки
```

---

# Самый короткий рабочий сценарий

```bash
./mvnw clean test

git add -A
git restore --staged commit-message.txt
git commit -F commit-message.txt
git push -u origin HEAD
```

После первого push:

```bash
./mvnw clean test

git add -A
git restore --staged commit-message.txt
git commit -F commit-message.txt
git push
```