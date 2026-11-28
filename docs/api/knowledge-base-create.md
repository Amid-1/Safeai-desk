# Создание Knowledge Base

`POST /api/knowledge-bases` (ADMIN в своей организации, действующий CSRF).

При успешном синхронном создании возвращаются `201 Created`, заголовок
`Location: /api/knowledge-bases/<id>` и прежний JSON `KnowledgeBaseResponse`.
Поля DTO и правила авторизации не изменились. Получение созданного ресурса
через `GET /api/knowledge-bases/<id>` по-прежнему возвращает `200 OK`.

Клиент принимает успешные 2xx по `Response.ok`; другие ошибки и политика
tenant isolation сохраняются. Runtime contract и MockMvc проверяют код и Location.
