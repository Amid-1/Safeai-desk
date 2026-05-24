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

## Working with project folders on Windows

The project is located here:

```text
D:\Java projects\Safeai-desk
```

Because the path contains a space in `Java projects`, always wrap the path in quotes.

### Open the project root

In Windows CMD:

```bat
cd /d "D:\Java projects\Safeai-desk"
```

Explanation:

- `cd` means change directory.
- `/d` allows switching the drive as well, for example from `C:` to `D:`.
- Quotes are required because the path contains a space.

In PowerShell:

```powershell
Set-Location "D:\Java projects\Safeai-desk"
```

or shorter:

```powershell
cd "D:\Java projects\Safeai-desk"
```

### Check current folder

In CMD:

```bat
cd
```

In PowerShell:

```powershell
Get-Location
```

Expected project root:

```text
D:\Java projects\Safeai-desk
```

### Go to backend

From the project root:

```bat
cd backend
```

Expected folder:

```text
D:\Java projects\Safeai-desk\backend
```

Start backend from this folder:

```bat
mvnw.cmd spring-boot:run
```

### Go to frontend

From the project root:

```bat
cd frontend
```

Expected folder:

```text
D:\Java projects\Safeai-desk\frontend
```

Start frontend from this folder:

```bat
npm install
npm run dev
```

### Go to infrastructure folder

From the project root:

```bat
cd infra
```

Expected folder:

```text
D:\Java projects\Safeai-desk\infra
```

Start Docker infrastructure from this folder:

```bat
docker compose up -d
```

Check Docker containers:

```bat
docker compose ps
```

### Go back one folder

```bat
cd ..
```

Example:
```text
D:\Java projects\Safeai-desk\backend
```

After:
```bat
cd ..
```

You will be here:
```text
D:\Java projects\Safeai-desk
```

### Go directly to any project folder

Instead of moving step by step, you can jump directly.

Project root:
```bat
cd /d "D:\Java projects\Safeai-desk"
```

Backend:
```bat
cd /d "D:\Java projects\Safeai-desk\backend"
```

Frontend:
```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
```

Infrastructure:
```bat
cd /d "D:\Java projects\Safeai-desk\infra"
```

### List files in the current folder

In CMD:
```bat
dir
```

In PowerShell:
```powershell
ls
```

### Stop a running command

If the backend or frontend is running in the terminal and you want to stop it:

```text
Ctrl + C
```

Then confirm if the terminal asks for confirmation.

### Common commands from the project root

Open the project root:
```bat
cd /d "D:\Java projects\Safeai-desk"
```

Start infrastructure:
```bat
cd infra
docker compose up -d
cd ..
```

Start backend:
```bat
cd backend
mvnw.cmd spring-boot:run
```

Start frontend in another terminal:
```bat
cd /d "D:\Java projects\Safeai-desk\frontend"
npm install
npm run dev
```

### Recommended terminal usage

Use separate terminal windows:

```text
Terminal 1: Docker / infrastructure
Terminal 2: backend
Terminal 3: frontend
```

Example:

```text
Terminal 1:
D:\Java projects\Safeai-desk\infra

Terminal 2:
D:\Java projects\Safeai-desk\backend

Terminal 3:
D:\Java projects\Safeai-desk\frontend
```

## Start infrastructure

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
