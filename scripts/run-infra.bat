@echo off
cd /d "D:\Java projects\Safeai-desk\infra"
docker compose up -d postgres redis
docker compose ps
