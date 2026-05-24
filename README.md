# SafeAI Desk

Corporate AI Gateway for controlled and auditable AI usage inside organizations.

SafeAI Desk is a backend-first MVP for managing AI usage inside a company:
users work with AI through a controlled internal gateway, while the organization can track requests, responses, usage, roles and future document-based RAG access.

## MVP Scope

- User authentication
- Role-based access
- AI chat
- Request/response logging
- Usage tracking
- Admin panel
- Future: document RAG, data masking, limits, Telegram notifications

## Tech Stack

- Java 21
- Spring Boot
- Spring Security
- Spring Data JPA
- Flyway
- PostgreSQL
- Redis
- React + TypeScript
- Docker Compose

## Project Structure

```text
Safeai-desk/
  backend/
  frontend/
  infra/
  docs/
  README.md
```

## Local Development

### Requirements

- Java 21+
- Node.js 20+
- Docker Desktop
- Docker Compose
- Git

### Start infrastructure

```bash
cd infra
docker compose up -d
```

### Check containers

```bash
docker compose ps
```

Expected containers:

```text
safeai-postgres
safeai-redis
```

## Start backend

### Windows CMD / PowerShell

```bat
cd backend
mvnw.cmd spring-boot:run
```

### Git Bash / Linux / macOS

```bash
cd backend
./mvnw spring-boot:run
```

Backend URL:

```text
http://localhost:8080
```

## Start frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend URL:

```text
http://localhost:5173
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

Database schema is managed by Flyway migrations:

```text
backend/src/main/resources/db/migration
```

## Current Status

Initial project setup is in progress.

Implemented/planned first milestone:

- Spring Boot backend skeleton
- PostgreSQL and Redis through Docker Compose
- Flyway migration setup
- Basic database schema
- Future: authentication, AI chat and audit logging
