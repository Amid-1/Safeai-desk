@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================================
REM SafeAI Desk - API + JWT verification script
REM ============================================================================
REM File: SAFEAI_DESK_API_JWT_CHECK.cmd
REM
REM Purpose:
REM   Manually verify the current SafeAI Desk backend after JWT authentication.
REM
REM Requirements:
REM   1. Docker Desktop must be running.
REM   2. Backend must be running in a separate terminal:
REM        cd /d "D:\Java projects\Safeai-desk\backend"
REM        mvnw.cmd spring-boot:run
REM
REM This script does NOT start the backend, because backend must keep running in
REM a separate terminal window.
REM ============================================================================

set "PROJECT_ROOT=D:\Java projects\Safeai-desk"
set "INFRA_DIR=%PROJECT_ROOT%\infra"
set "BASE_URL=http://localhost:8080"

echo.
echo ============================================================================
echo SafeAI Desk - API + JWT verification
echo ============================================================================
echo.
echo Backend must already be running in another terminal:
echo   cd /d "%PROJECT_ROOT%\backend"
echo   mvnw.cmd spring-boot:run
echo.
echo If backend is not running, stop this script with Ctrl+C.
echo.
pause

echo.
echo ============================================================================
echo [0] Docker Compose infrastructure
echo ============================================================================
echo.
echo What this checks:
echo   PostgreSQL and Redis containers must be running.
echo.
echo Command:
echo   cd /d "%INFRA_DIR%"
echo   docker compose up -d
echo   docker compose ps
echo.
echo Expected result:
echo   safeai-postgres  Running / Up
echo   safeai-redis     Running / Up
echo.
cd /d "%INFRA_DIR%"
docker compose up -d
echo.
docker compose ps
echo.
pause

echo.
echo ============================================================================
echo [1] Protected endpoint without JWT
echo ============================================================================
echo.
echo What this checks:
echo   /api/users must be closed without token after JWT security is enabled.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/users
echo.
echo Expected result:
echo   HTTP/1.1 401
echo.
curl -i %BASE_URL%/api/users
echo.
pause

echo.
echo ============================================================================
echo [2] Login with correct password
echo ============================================================================
echo.
echo What this checks:
echo   /api/auth/login must accept email/password and return JWT.
echo.
echo Command:
echo   curl -i -X POST %BASE_URL%/api/auth/login ^
echo     -H "Content-Type: application/json" ^
echo     -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"
echo.
echo Expected result:
echo   HTTP/1.1 200
echo   JSON with fields: token, tokenType, user
echo.
curl -i -X POST %BASE_URL%/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"admin123\"}"

echo.
echo ----------------------------------------------------------------------------
echo Copy ONLY the token value from the response above.
echo.
echo Correct:
echo   eyJhbGciOiJIUzI1NiJ9.eyJ...signature
echo.
echo Incorrect:
echo   "token":"eyJ..."
echo   Authorization: Bearer "token":"eyJ..."
echo.
echo Paste token below.
echo ----------------------------------------------------------------------------
set /p TOKEN=TOKEN: 

echo.
echo ============================================================================
echo [3] TOKEN variable check
echo ============================================================================
echo.
echo What this checks:
echo   TOKEN must contain only JWT in this format: header.payload.signature
echo.
echo Current TOKEN:
echo.
echo %TOKEN%
echo.
echo If it is empty or contains "token": then token was pasted incorrectly.
echo.
pause

echo.
echo ============================================================================
echo [4] /api/auth/me with JWT
echo ============================================================================
echo.
echo What this checks:
echo   Backend must accept Authorization: Bearer %%TOKEN%% and return current user.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/auth/me ^
echo     -H "Authorization: Bearer %%TOKEN%%"
echo.
echo Expected result:
echo   HTTP/1.1 200
echo   JSON with id, organizationId, email, enabled, roles
echo.
curl -i %BASE_URL%/api/auth/me ^
  -H "Authorization: Bearer %TOKEN%"
echo.
pause

echo.
echo ============================================================================
echo [5] /api/users with JWT
echo ============================================================================
echo.
echo What this checks:
echo   Protected endpoint /api/users must work with valid JWT.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/users ^
echo     -H "Authorization: Bearer %%TOKEN%%"
echo.
echo Expected result:
echo   HTTP/1.1 200
echo   JSON array of users
echo.
curl -i %BASE_URL%/api/users ^
  -H "Authorization: Bearer %TOKEN%"
echo.
pause

echo.
echo ============================================================================
echo [6] Login with wrong password
echo ============================================================================
echo.
echo What this checks:
echo   Wrong password must return 401, not 500.
echo.
echo Command:
echo   curl -i -X POST %BASE_URL%/api/auth/login ^
echo     -H "Content-Type: application/json" ^
echo     -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
echo.
echo Expected result:
echo   HTTP/1.1 401
echo   JSON: status=401, error=UNAUTHORIZED, message=Wrong email or password
echo.
curl -i -X POST %BASE_URL%/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"email\":\"admin@test.com\",\"password\":\"wrong-password\"}"
echo.
pause

echo.
echo ============================================================================
echo [7] Invalid token
echo ============================================================================
echo.
echo What this checks:
echo   Invalid JWT must return 401 invalid_token.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/users ^
echo     -H "Authorization: Bearer wrong-token"
echo.
echo Expected result:
echo   HTTP/1.1 401
echo   WWW-Authenticate: Bearer error="invalid_token"
echo.
curl -i %BASE_URL%/api/users ^
  -H "Authorization: Bearer wrong-token"
echo.
pause

echo.
echo ============================================================================
echo [8] Organization API without token
echo ============================================================================
echo.
echo What this checks:
echo   /api/organizations must also be closed without JWT.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/organizations
echo.
echo Expected result:
echo   HTTP/1.1 401
echo.
curl -i %BASE_URL%/api/organizations
echo.
pause

echo.
echo ============================================================================
echo [9] Organization API with token
echo ============================================================================
echo.
echo What this checks:
echo   With valid JWT, /api/organizations must return organizations.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/organizations ^
echo     -H "Authorization: Bearer %%TOKEN%%"
echo.
echo Expected result:
echo   HTTP/1.1 200
echo   JSON array of organizations
echo.
curl -i %BASE_URL%/api/organizations ^
  -H "Authorization: Bearer %TOKEN%"
echo.
pause

echo.
echo ============================================================================
echo [10] 404 for missing organization with token
echo ============================================================================
echo.
echo What this checks:
echo   Missing organization id must return 404 JSON.
echo.
echo Command:
echo   curl -i %BASE_URL%/api/organizations/11111111-2222-3333-4444-555555555555 ^
echo     -H "Authorization: Bearer %%TOKEN%%"
echo.
echo Expected result:
echo   HTTP/1.1 404
echo   JSON: status=404, error=NOT_FOUND, message=Organization not found
echo.
curl -i %BASE_URL%/api/organizations/11111111-2222-3333-4444-555555555555 ^
  -H "Authorization: Bearer %TOKEN%"
echo.
pause

echo.
echo ============================================================================
echo [11] 400 for empty organization name with token
echo ============================================================================
echo.
echo What this checks:
echo   Validation must reject empty name.
echo.
echo Command:
echo   curl -i -X POST %BASE_URL%/api/organizations ^
echo     -H "Authorization: Bearer %%TOKEN%%" ^
echo     -H "Content-Type: application/json" ^
echo     -d "{\"name\":\"\"}"
echo.
echo Expected result:
echo   HTTP/1.1 400
echo   JSON: status=400, error=VALIDATION_ERROR, fieldErrors.name exists
echo.
curl -i -X POST %BASE_URL%/api/organizations ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"\"}"
echo.
pause

echo.
echo ============================================================================
echo [12] 400 for invalid UUID in JSON
echo ============================================================================
echo.
echo What this checks:
echo   If organizationId is not UUID, backend must return 400, not 500.
echo.
echo Command:
echo   curl -i -X POST %BASE_URL%/api/users ^
echo     -H "Authorization: Bearer %%TOKEN%%" ^
echo     -H "Content-Type: application/json" ^
echo     -d "{\"organizationId\":\"PASTE_ORGANIZATION_ID_HERE\",...}"
echo.
echo Expected result:
echo   HTTP/1.1 400
echo   JSON: status=400, error=BAD_REQUEST
echo.
curl -i -X POST %BASE_URL%/api/users ^
  -H "Authorization: Bearer %TOKEN%" ^
  -H "Content-Type: application/json" ^
  -d "{\"organizationId\":\"PASTE_ORGANIZATION_ID_HERE\",\"email\":\"bad-uuid@test.com\",\"password\":\"admin123\",\"fullName\":\"Admin User\",\"roles\":[\"ADMIN\"]}"
echo.
pause

echo.
echo ============================================================================
echo [13] PostgreSQL check: users
echo ============================================================================
echo.
echo What this checks:
echo   users table must contain admin@test.com.
echo   password_hash must be BCrypt hash, not plain admin123.
echo.
echo Command:
echo   docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, organization_id, email, password_hash, enabled, created_at from users;"
echo.
echo Expected result:
echo   admin@test.com
echo   password_hash starts with $2a$ or $2b$
echo   enabled = t
echo.
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select id, organization_id, email, password_hash, enabled, created_at from users;"
echo.
pause

echo.
echo ============================================================================
echo [14] PostgreSQL check: roles
echo ============================================================================
echo.
echo What this checks:
echo   roles table must contain ADMIN and USER.
echo.
echo Command:
echo   docker exec -it safeai-postgres psql -U safeai -d safeai -c "select * from roles;"
echo.
echo Expected result:
echo   ADMIN
echo   USER
echo.
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select * from roles;"
echo.
pause

echo.
echo ============================================================================
echo [15] PostgreSQL check: user_roles
echo ============================================================================
echo.
echo What this checks:
echo   admin@test.com must have ADMIN role.
echo.
echo Command:
echo   docker exec -it safeai-postgres psql -U safeai -d safeai -c "select u.email, r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id;"
echo.
echo Expected result:
echo   admin@test.com | ADMIN
echo.
docker exec -it safeai-postgres psql -U safeai -d safeai -c "select u.email, r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id;"
echo.
pause

echo.
echo ============================================================================
echo [16] Summary
echo ============================================================================
echo.
echo If all checks passed, current stage is working:
echo.
echo   OK: Docker Compose infrastructure
echo   OK: PostgreSQL is available
echo   OK: users/roles exist in database
echo   OK: login returns JWT
echo   OK: /api/auth/me works with JWT
echo   OK: /api/users is closed without JWT
echo   OK: /api/users works with JWT
echo   OK: wrong password returns 401
echo   OK: invalid token returns 401
echo   OK: 404/400 errors are returned correctly
echo.
echo Recommended commit:
echo.
echo   cd /d "%PROJECT_ROOT%"
echo   git status
echo   git add .
echo   git commit -m "add jwt authentication"
echo   git push
echo.
echo Next development stage:
echo   Chat Core: chat_sessions, chat_messages, create chat, send message,
echo   save history, return mock AI response.
echo.
echo ============================================================================
echo Verification completed.
echo ============================================================================
echo.

pause
endlocal
