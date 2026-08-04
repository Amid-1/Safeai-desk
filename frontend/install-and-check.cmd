@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
    echo Node.js is not installed.
    exit /b 1
)

for /f "usebackq delims=" %%V in (`node -p "process.versions.node"`) do set "NODE_VERSION=%%V"
for /f "tokens=1 delims=." %%M in ("!NODE_VERSION!") do set "NODE_MAJOR=%%M"

if not "!NODE_MAJOR!"=="24" (
    echo Required Node.js 24.x. Current version: !NODE_VERSION!
    exit /b 1
)

echo [1/5] Regenerating package-lock.json...
call npm install
if errorlevel 1 exit /b 1

echo [2/5] Clean reproducible install...
if exist node_modules rmdir /s /q node_modules
call npm ci
if errorlevel 1 exit /b 1

echo [3/5] Quality gate...
call npm run check
if errorlevel 1 exit /b 1

echo [4/5] Coverage...
call npm run test:coverage
if errorlevel 1 exit /b 1

echo [5/5] Production dependency audit...
call npm run audit:prod
if errorlevel 1 exit /b 1

echo Frontend verification completed successfully.
