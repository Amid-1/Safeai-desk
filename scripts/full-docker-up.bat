@echo off
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose --profile full up -d --build
docker compose ps
