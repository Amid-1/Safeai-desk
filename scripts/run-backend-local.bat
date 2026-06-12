@echo off
cd /d "D:\Java projects\Safeai-desk\backend"
set SAFEAI_JWT_SECRET=safeai-local-development-secret-key-change-this-value-please-123456789
set SAFEAI_JWT_EXPIRATION_MINUTES=60
mvnw.cmd spring-boot:run
