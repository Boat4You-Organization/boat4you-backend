# Boat4You Web Service

### Prerequisites

- <i>Java 21</i>.
- <i>Docker</i>. Get it from [HERE](https://docs.docker.com/get-docker/) and follow instructions

## Running

### Only database for local development

First run the Postgres database in Docker:

```
docker run --rm --name=boat4you_postgres --env=POSTGRES_USER=boat4you_owner --env=POSTGRES_PASSWORD=boat4you_owner --env=POSTGRES_DB=boat4you_db --env=LANG=en_US.utf8 -p 5433:5432 postgres:17-alpine
```
or use docker compose
```
  cd model
  docker compose up -d
```

Then run the following command and wait for "_Test data imported. Application ready._" log output.

```
./gradlew bootRun
```

Application will run on port 8443.

### Entire application in Docker

```
    docker compose up -d
```
It will start database (port 5435) and application on port 8443 (https).

If you want to force app rebuild, use
```
    docker compose up -d --build
```

If you want to use http, uncomment the following line in `docker-compose.yml` file:
```yaml
    ports:
      - "8085:8085"
    environment:
      - SERVER_SSL_ENABLED=false
      - SERVER_PORT=8085
```

## Email Service Instructions

1) Go to Google Account Security https://myaccount.google.com/security
and turn on 2-Step Verification under "Signing in to Google" section.
2) After enabling 2FA, scroll to the bottom of the page, a new section "App passwords" will appear so click on it.
3) Under "Your app passwords" section enter new "App name" and click on "Create" button.
4) After clicking create the password will be shown (e.g., abcd efgh ijkl mnop) one time so copy it.

5) After acquiring the password replace the following variables in application.yml with your email and generated password:
    ```yaml
      mail:
        host: smtp.gmail.com
        port: 587
        username: ${MAIL_USERNAME:your@gmail.com}
        password: ${MAIL_PASSWORD:your-app-password}
        properties:
          mail:
            smtp:
              auth: true
              starttls:
                enable: true
    ```

## Swagger

Swagger UI is available at https://localhost:8443/swagger

## Test users

Test users are seeded by `src/main/resources/db/migration/V9_00__insert_test_data.sql`
and only run on environments that include V9_* in their Flyway target. Production
deploys must set `FLYWAY_TARGET_VERSION=1.90` (or the latest V1.xx) so V9_* are
skipped — see `README_PROD.md`.

Local-dev passwords + seeded SYSTEM_ADMIN accounts are kept out-of-tree (operator's
password manager) — the README is public and previously listed shared dev
credentials.

## Data sync in development

Admin sync triggers require the `SYSTEM_ADMIN` role and the `data-sync` Spring
profile. All endpoints are **POST** (state-changing — fetched-by-browser-or-proxy
would otherwise re-trigger heavyweight syncs):

NauSys:
- `POST /admin/nausys/sync`
- `POST /admin/nausys/agencies`
- `POST /admin/nausys/yachts`
- `POST /admin/nausys/offer`
- `POST /admin/nausys/availability`

MMK:
- `POST /admin/mmk/sync`
- `POST /admin/mmk/yachts`
- `POST /admin/mmk/yachts-lang`
- `POST /admin/mmk/offer`
- `POST /admin/mmk/availability`

Example:
```
curl -X POST -H "Authorization: Bearer $JWT" https://localhost:8443/admin/mmk/sync
```