# SafeAI Desk

SafeAI Desk is a backend-first MVP of a corporate AI Gateway. The goal of the project is to let employees use AI through a controlled internal service while the organization can manage users, roles, chat history, audit events, usage statistics, limits and future document-based RAG workflows.

## Current MVP status

Implemented:

- Spring Boot backend
- PostgreSQL and Redis local infrastructure through Docker Compose
- Flyway database migrations
- Organization API
- User API
- roles `ADMIN` and `USER`
- password hashing with BCrypt
- centralized JSON error handling
- JWT authentication
- protected API endpoints
- basic test baseline

Planned next:

- Chat Core: chat sessions and messages
- Mock AI provider
- audit events
- usage tracking
- React + TypeScript frontend
- document upload and RAG
- limits, data masking and admin dashboards

## Tech stack

Backend:

- Java 21
- Spring Boot
- Spring Web MVC
- Spring Security
- OAuth2 Resource Server JWT
- Spring Data JPA
- Flyway
- PostgreSQL
- Redis
- Maven

Frontend:

- React
- TypeScript
- Vite

Infrastructure:

- Docker Compose
- PostgreSQL 16
- Redis 7

## Project structure

```text
Safeai-desk/
├── backend/
│   ├── src/main/java/ru/safeai/gateway/
│   │   ├── auth/
│   │   ├── common/
│   │   │   ├── exception/
│   │   │   └── security/
│   │   ├── organization/
│   │   └── user/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/
│   ├── src/test/java/
│   ├── Dockerfile
│   ├── .env.example
│   └── pom.xml
├── frontend/
├── infra/
│   └── docker-compose.yml
├── docs/
└── README.md
```

## Backend package structure

```text
ru.safeai.gateway
├── auth
│   ├── controller
│   ├── dto
│   └── service
├── common
│   ├── exception
│   └── security
├── organization
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   └── service
├── user
│   ├── controller
│   ├── dto
│   ├── entity
│   ├── repository
│   └── service
└── 
    ├── chat
    ├── ai
    ├── audit
    ├── usage
    └── admin
```

## Requirements

Install before running the project:

- Java 21+
- Docker Desktop
- Docker Compose
- Git
- Node.js 20+ for the future frontend

Check versions:

```bash
java -version
docker --version
docker compose version
git --version
node -v
npm -v
```

## Environment variables

The backend uses environment variables for secrets and local configuration.

Create a local file from the example:

```bash
cd backend
cp .env.example .env
```

On Windows CMD, if `cp` is not available:

```bat
cd backend
copy .env.example .env
```

Example `.env.example`:

```env
SAFEAI_JWT_SECRET=change-me-use-long-random-secret
SAFEAI_JWT_EXPIRATION_MINUTES=60
```

Use a long random value for `SAFEAI_JWT_SECRET` in real environments.

Do not commit real `.env` files.

Expected `.gitignore` entries:

```gitignore
.env
*.env
!*.env.example
target/
```

## Database

PostgreSQL runs in Docker.

Default local database settings:

```text
Host: localhost
Port: 5432
Database: safeai
Username: safeai
Password: safeai_password
```

When the backend runs inside Docker Compose, it must connect to PostgreSQL by Docker service name:

```text
jdbc:postgresql://postgres:5432/safeai
```

When the backend runs locally on the host machine, it connects through the exposed local port:

```text
jdbc:postgresql://localhost:5432/safeai
```

The backend `application.yml` should support both modes:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/safeai}
    username: ${SPRING_DATASOURCE_USERNAME:safeai}
    password: ${SPRING_DATASOURCE_PASSWORD:safeai_password}

app:
  security:
    jwt:
      secret: ${SAFEAI_JWT_SECRET}
      expiration-minutes: ${SAFEAI_JWT_EXPIRATION_MINUTES:60}
```

## Flyway migrations

Database schema is managed by Flyway.

Migration directory:

```text
backend/src/main/resources/db/migration
```

Current expected migrations:

```text
V1__init_schema.sql
V2__seed_roles.sql
V3__seed_demo_admin.sql
```

Typical local seed data:

```text
Organization: Demo Company
Admin email:  admin@test.com
Admin pass:   admin123
Role:         ADMIN
```

Passwords must be stored only as BCrypt hashes.

Do not edit already applied Flyway migrations. Add a new migration instead.

## Recommended development mode

For daily development, run only PostgreSQL and Redis in Docker, and run the backend locally from the IDE or Maven.

This mode is the fastest and most convenient while editing Java code.

### Start infrastructure only

From the project root:

```bash
cd infra
docker compose up -d postgres redis
docker compose ps
```

Expected containers:

```text
safeai-postgres
safeai-redis
```

### Start backend locally

In a separate terminal:

Windows CMD:

```bat
cd backend
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
mvnw.cmd spring-boot:run
```

PowerShell:

```powershell
cd backend
$env:SAFEAI_JWT_SECRET="safeai-local-development-secret-key-change-this-value-please-123456789"
$env:SAFEAI_JWT_EXPIRATION_MINUTES="60"
.\mvnw.cmd spring-boot:run
```

Linux/macOS/Git Bash:

```bash
cd backend
export SAFEAI_JWT_SECRET="safeai-local-development-secret-key-change-this-value-please-123456789"
export SAFEAI_JWT_EXPIRATION_MINUTES="60"
./mvnw spring-boot:run
```

Backend URL:

```text
http://localhost:8080
```

Health check:

```bash
curl -i http://localhost:8080/actuator/health
```

Expected result:

```text
HTTP/1.1 200
```

```json
{"status":"UP"}
```

## Full Docker mode

Use this mode to verify that the whole project can be started through Docker Compose.

This mode is useful before a demo, after Dockerfile changes, after dependency changes, or before pushing infrastructure updates.

### Recommended compose behavior

The `backend` service should be placed behind a Docker Compose profile, for example:

```yaml
services:
  backend:
    profiles:
      - full
    build:
      context: ../backend
    container_name: safeai-backend
    env_file:
      - ../backend/.env
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/safeai
      SPRING_DATASOURCE_USERNAME: safeai
      SPRING_DATASOURCE_PASSWORD: safeai_password
    ports:
      - "8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
```

With this setup:

- `docker compose up -d` starts only base infrastructure.
- `docker compose --profile full up -d --build` starts infrastructure and backend.

### Start full Docker stack

```bash
cd infra
docker compose --profile full up -d --build
docker compose ps
```

Expected containers:

```text
safeai-postgres
safeai-redis
safeai-backend
```

Check backend logs:

```bash
docker compose logs -f backend
```

Exit log follow mode with:

```text
Ctrl + C
```

This does not stop the containers.

### When to use `--build`

Use `--build` when running the backend inside Docker and after changing:

- Java code
- `pom.xml`
- `Dockerfile`
- `application.yml`
- Flyway migrations
- dependency versions

If you run the backend locally with `mvnw.cmd spring-boot:run`, `--build` is not needed.

## Do not run two backends at the same time

The backend uses port `8080`.

Do not run both at the same time:

- backend locally through Maven or IDE
- backend inside Docker Compose

If the backend container is running and you want to start the backend locally:

```bash
cd infra
docker compose stop backend
```

PostgreSQL and Redis will remain running.

## API verification

All protected endpoints require:

```http
Authorization: Bearer <token>
```

Public endpoints:

```text
POST /api/auth/login
GET  /actuator/health
```

Protected endpoints:

```text
GET  /api/auth/me
GET  /api/users
POST /api/users
GET  /api/organizations
POST /api/organizations
GET  /api/organizations/{id}
```

If `/api/users` and `/api/organizations` are restricted to admins, a regular `USER` role should receive `403 Forbidden`.

## Login

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Windows CMD:

```bat
curl -i -X POST http://localhost:8080/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
```

Expected result:

```text
HTTP/1.1 200
```

Response contains:

```json
{
  "token": "eyJ...",
  "tokenType": "Bearer",
  "user": {
    "email": "admin@test.com",
    "roles": ["ADMIN"]
  }
}
```

Copy only the token value.

Correct:

```text
eyJhbGciOiJIUzI1NiJ9.eyJ...signature
```

Incorrect:

```text
"token":"eyJ..."
```

## Save token in shell

Windows CMD:

```bat
set "TOKEN=PASTE_TOKEN_HERE"
```

PowerShell:

```powershell
$env:TOKEN="PASTE_TOKEN_HERE"
```

Linux/macOS/Git Bash:

```bash
export TOKEN="PASTE_TOKEN_HERE"
```

## Check current user

Windows CMD:

```bat
curl -i http://localhost:8080/api/auth/me ^
  -H "Authorization: Bearer %TOKEN%"
```

Linux/macOS/Git Bash:

```bash
curl -i http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

Expected result:

```text
HTTP/1.1 200
```

## Protected endpoint without token

```bash
curl -i http://localhost:8080/api/users
```

Expected result:

```text
HTTP/1.1 401
```

## Users with token

Windows CMD:

```bat
curl -i http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%"
```

Expected result for admin:

```text
HTTP/1.1 200
```

## Organizations with token

Windows CMD:

```bat
curl -i http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%"
```

Expected result for admin:

```text
HTTP/1.1 200
```

## Wrong password check

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
```

Expected result:

```text
HTTP/1.1 401
```

## Invalid token check

```bash
curl -i http://localhost:8080/api/users \
  -H "Authorization: Bearer wrong-token"
```

Expected result:

```text
HTTP/1.1 401
```

## 404 check for missing organization

Windows CMD:

```bat
curl -i http://localhost:8080/api/organizations/11111111-2222-3333-4444-555555555555 ^
  -H "Authorization: Bearer %TOKEN%"
```

Expected result:

```text
HTTP/1.1 404
```

## 400 validation check

Windows CMD:

```bat
curl -i -X POST http://localhost:8080/api/organizations ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
```

Expected result:

```text
HTTP/1.1 400
```

## 400 invalid UUID in JSON check

Windows CMD:

```bat
curl -i -X POST http://localhost:8080/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"NOT_A_UUID\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Bad UUID\",\"roles\":[\"ADMIN\"]}"
```

Expected result:

```text
HTTP/1.1 400
```

## Database verification

Run commands from any terminal while `safeai-postgres` is running.

### Flyway history

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select installed_rank, version, description, success from flyway_schema_history order by installed_rank;"
```

Expected result:

```text
1 | 1 | init schema      | t
2 | 2 | seed roles       | t
3 | 3 | seed demo admin  | t
```

### Organizations

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, name, created_at from organizations;"
```

Expected result:

```text
Demo Company
```

### Users

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, organization_id, email, password_hash, enabled, created_at from users;"
```

Expected result:

```text
admin@test.com
enabled = t
password_hash starts with $2a$, $2b$ or $2y$
```

The database must not contain the plain password `admin123`.

### Roles

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, name from roles;"
```

Expected result:

```text
ADMIN
USER
```

### User roles

```bash
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select u.email, r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id;"
```

Expected result:

```text
admin@test.com | ADMIN
```

## Running tests

From the backend directory:

Windows CMD:

```bat
cd backend
mvnw.cmd test
```

Linux/macOS/Git Bash:

```bash
cd backend
./mvnw test
```

Recommended minimum test coverage for the current stage:

- Spring context loads
- user creation success
- duplicate email returns conflict
- missing organization returns not found
- login success
- login failure
- protected endpoint without token returns `401`
- `USER` role on admin endpoint returns `403`
- `ADMIN` role on admin endpoint returns `200`

## Common commands

### Start daily development infrastructure

```bash
cd infra
docker compose up -d postgres redis
```

### Stop containers

```bash
cd infra
docker compose stop
```

### Remove containers but keep volumes

```bash
cd infra
docker compose down
```

### Remove containers and database data

```bash
cd infra
docker compose down -v
```

Use `down -v` carefully. It deletes the PostgreSQL Docker volume and resets the local database.

### View containers

```bash
cd infra
docker compose ps
```

### View backend logs in Docker mode

```bash
cd infra
docker compose logs -f backend
```

### View PostgreSQL logs

```bash
docker logs safeai-postgres
```

### View Redis logs

```bash
docker logs safeai-redis
```

### View Docker disk usage

```bash
docker system df
```

## Troubleshooting

### `SAFEAI_JWT_SECRET` is not set

Reason:

- `.env` file is missing
- environment variable is not set
- Docker Compose cannot see the `.env` file

Fix for local backend run:

```bat
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
```

Fix for Docker Compose backend run:

```bash
cd backend
cp .env.example .env
```

Then edit `backend/.env`.

### Backend cannot connect to PostgreSQL

If backend runs locally, it should use:

```text
jdbc:postgresql://localhost:5432/safeai
```

If backend runs in Docker, it should use:

```text
jdbc:postgresql://postgres:5432/safeai
```

Check containers:

```bash
cd infra
docker compose ps
```

### Port 8080 is already in use

A backend process is already running.

If Docker backend is running:

```bash
cd infra
docker compose stop backend
```

If local backend is running, stop it with:

```text
Ctrl + C
```

### Token is malformed

Only paste the JWT value, not the full JSON field.

Correct:

```text
Authorization: Bearer eyJ...
```

Incorrect:

```text
Authorization: Bearer "token":"eyJ..."
```

### Database was reset

If you ran:

```bash
docker compose down -v
```

the PostgreSQL volume was deleted.

Start containers again and let Flyway recreate the schema:

```bash
cd infra
docker compose up -d postgres redis
```

If `V3__seed_demo_admin.sql` exists, the demo admin will be recreated automatically.

## Development roadmap

Recommended next stages:

1. Chat Core
    - `ChatSessionEntity`
    - `ChatMessageEntity`
    - chat repositories
    - create chat endpoint
    - send message endpoint
    - save `USER` and `ASSISTANT` messages

2. Mock AI Provider
    - common AI provider interface
    - mock response implementation
    - later replacement with real AI provider

3. Audit
    - login events
    - chat events
    - AI provider errors

4. Usage
    - model name
    - input tokens
    - output tokens
    - cost

5. Frontend MVP
    - login page
    - chat page
    - admin users
    - audit view
    - usage dashboard

## Commit checklist

Before pushing:

```bash
git status
```

Make sure the following are not committed:

```text
backend/target/
.env
*.env
API keys
production secrets
temporary files
IDE cache files
```

Files that can be committed:

```text
.env.example
Dockerfile
docker-compose.yml
README.md
docs/*.md
```

Run before commit:

```bash
cd backend
./mvnw test
```

On Windows CMD:

```bat
cd backend
mvnw.cmd test
```
