@echo off
setlocal

cd /d "%~dp0"

echo Removing stale dependencies...
if exist node_modules rmdir /s /q node_modules
if exist package-lock.json del /f /q package-lock.json

echo Installing dependencies...
call npm install
if errorlevel 1 exit /b 1

echo Running TypeScript build...
call npm run build
if errorlevel 1 exit /b 1

echo Running tests...
call npm test
