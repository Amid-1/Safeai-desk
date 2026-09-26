# SafeAI-Desk/infra/secrets/README.md

Only templates/documentation belong in this directory in Git.

Production secret values live outside the repository, normally in
`/etc/safeai/secrets/`, and are referenced by `infra/docker-compose.yml`.

Committed here:

- `jwt-keys.yml.example`
- this README

Never commit `jwt-keys.yml`, password files, API keys or S3 credentials.
