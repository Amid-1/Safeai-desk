@echo off
docker exec safeai-postgres psql -U safeai -d safeai -c "select event_type, user_id, details, created_at from audit_events order by created_at desc limit 10;"
