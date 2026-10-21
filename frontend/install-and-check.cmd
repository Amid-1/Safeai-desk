@REM frontend/install-and-check.cmd — строгая проверка Windows без изменения Git.
@echo off
setlocal
cd /d "%~dp0"
for /f "delims=." %%V in ('node -p "process.versions.node"') do set NODE_MAJOR=%%V
for /f "delims=." %%V in ('npm -v') do set NPM_MAJOR=%%V
if not "%NODE_MAJOR%"=="24" (echo Требуется Node.js 24 & exit /b 2)
if not "%NPM_MAJOR%"=="11" (echo Требуется npm 11 & exit /b 2)
call npm ci || exit /b 1
call npm run architecture:check || exit /b 1
call npm run typecheck || exit /b 1
call npm run lint || exit /b 1
call npm run test || exit /b 1
call npm run test:coverage || exit /b 1
call npm run build || exit /b 1
echo Проверки завершены успешно.
