# SafeAI Desk — SOURCE_AUDIT

> Область: загруженные пользователем архивные snapshot на 24.11.2026. Не заменяет Git commit SHA и проверку окружения.

## Проверенные архивы

| Архив | SHA-256 | Содержательных файлов |
|---|---|---:|
| `Архив(20261124-164251).zip` | `a6cfd2712d1da90720a62bb7aa2f76b77e44ac7996f8c13eaa739adca8a3972f` | 758 |
| `resources.zip` | `58ade8cb1a3c1791456b57da2fa7d3a725fa4c8d1bb35ec08c680fabbd20a514` | 126 |
| `resources_test.zip` | `8dbea00f0c5d013dd7999a2068b8f770cf40ed04e296f4e8bbd66de0f4e5510b` | 6 |
| `tests.zip` | `f3ac8e9a8b53b2a5e9c46f4cd482258fba2c12d8d26355d63741b0db0386a6bc` | 474 |
| `frontend(4).zip` | `b2cf215a1cdc31f492117666ba369958c17d17c260cde7d4b134b398d8f0c0e8` | 584 |
| `Архив(20261124-164535).zip` | `fc8675518aed5a15af5abe8bd2edd3d02e0918f63d7b39023660178e94c08327` | 76 |

Дополнительный контекст: предыдущие девять тематических архивов, `.github` и `infra` из архивов 22.11.2026. 
Эти вспомогательные источники могут быть старее новой кодовой выборки.

## Подтверждённые файлы-основания

| Путь внутри восстановленной структуры | SHA-256 |
|---|---|
| `backend/src/main/java/ru/safeai/gateway/user/service/UserCommandOperations.java` | `e0fe06c6b3ed6bf461af3da28e40b86bb0dc801e34bde376cd637855a2388fa4` |
| `backend/src/main/java/ru/safeai/gateway/chat/service/ChatLifecycleService.java` | `ca0fdfbb21f2cfe787cdf391332591a2ea5ecd30bfd31c7aa000b090c2373ff9` |
| `backend/src/main/java/ru/safeai/gateway/knowledge/service/KnowledgeDocumentDeletionAcceptanceService.java` | `295267701fd2e87be67fafe9445d771ca701a97dc8b5192bcc1235cba488e2b9` |
| `backend/src/main/java/ru/safeai/gateway/knowledge/storage/S3ObjectStorage.java` | `2859ff784146ddef4aec1816c11b694fc0b58b9b278e9e5a8af7c4fe0afe1ec0` |
| `backend/src/main/java/ru/safeai/gateway/model/service/ModelCatalogService.java` | `046870849c4aeca1c4c8bbd394a85a5ae354b323fc7836f5a4c7f1cfe7165607` |
| `backend/src/main/java/ru/safeai/gateway/organization/service/OrganizationCommandOperations.java` | `c9076229538ba3c9c9b9978f8d0dff6fa4981d859950aefce2fbf7539a2cdce9` |
| `backend/src/main/java/ru/safeai/gateway/auth/security/SecurityConfig.java` | `c8bf2114af1816c6a31e3f464b7256b0eb39b5ebb7b48edb48818ea4b264b5c3` |
| `backend/src/main/java/ru/safeai/gateway/retention/RetentionManagementService.java` | `c01a46a6dbb591b453aecf431a23a8e8ba2dc97c95b6199b530c5eed13eb6c98` |
| `backend/src/main/resources/db/migration/V45__model_control_plane.sql` | `b4def4b8c88e7972635602470e0b1bf2cb248919e107fe099733a363494d5c3d` |
| `backend/src/main/resources/db/migration/V86__chat_archive_restore_erasure.sql` | `33cc423243016c49f1d9e1a7fa609cec01337c4c3d69b0030377f7e3ffa68afb` |
| `backend/src/main/resources/db/migration/V102__platform_erasure_admission_policy.sql` | `767091c7cf9f5b6a2d9c81d9269a258baac1d64c32fb0c3fa85ddd4b2c794266` |
| `frontend/src/features/admin/ui/RetentionWorkspace.tsx` | `3fbb94e344288df58a97bf10a80ae5c97cc38350c3ab38ed78068806771ba437` |
| `frontend/src/shared/api/http/httpConfig.ts` | `cec69d37d701b61200b9e4050f8f2202aa55dcea7526d8f595dcc9b7cd3e3672` |

## Проверка миграций

- Все 111 строк SHA-256 reviewed manifest сверены с файлами `db/migration`: 0 несовпадений.
- SQL-манифест не доказывает, что миграции применены в конкретной БД.
- Текущая ветка Git и незакоммиченные локальные изменения пользователя в ZIP не определяются.

## Что не проверено

- Реальный staging / production PostgreSQL и его `pg_catalog`, загруженные наборы данных и PII.
- Реальный S3-compatible provider, внешний объектный lock, версии, репликация и провайдерское хранение.
- Конфигурация секретов, подключений и параметры retention в развёрнутом окружении.
- Полный актуальный Git HEAD и `.github`/`infra` после последних локальных изменений.
- Реальное выполнение тестов, браузера, Docker и restore drills на текущем окружении.
- Юрисдикции, правовые основания хранения и согласованные бизнес-процессы.

Таким образом, **факты из исходников** отделяются от **проектируемых контрактов** и **условий будущей приёмки**.
