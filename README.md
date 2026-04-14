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

## Hardcoded test users:

|          email           | password |       roles       |
|:------------------------:|:--------:|:-----------------:|
| pskvorcevic@workspace.hr |  123456  |      MANAGER      |
|    that@workspace.hr     |  123456  |   SYSTEM_ADMIN    |
|   bhokman@workspace.hr   |  123456  |       USER        |
|   vcutic@workspace.hr    |  123456  |      MANAGER      |
|   astekl@workspace.hr    |  123456  |   SYSTEM_ADMIN    |
|   eolcar@workspace.hr    |  123456  |       USER        |
| lbardundjek@workspace.hr |  123456  |      MANAGER      |
|   gjanjik@workspace.hr   |  123456  |   SYSTEM_ADMIN    |
|   avaljan@workspace.hr   |  123456  |       USER        |
|   pilanic@workspace.hr   |  123456  |      MANAGER      |
|   vcupin@workspace.hr    |  123456  |   SYSTEM_ADMIN    |
|   asimat@workspace.hr    |  123456  |       USER        |
|   lspruk@workspace.hr    |  123456  |      MANAGER      |
| kmarasovic@workspace.hr  |  123456  |   SYSTEM_ADMIN    |
|  egalijan@workspace.hr   |  123456  | no roles assigned |


## Data sync in development

For development purposes we have exposed following GET endpoints to sync data from MMK and NauSYS:
Nausys:
 - https://localhost:8443/nausys/agencies
 - https://localhost:8443/nausys/sync
 - https://localhost:8443/nausys/yachts
 - https://localhost:8443/nausys/offer

MMK:
 - https://localhost:8443/mmk/sync
 - https://localhost:8443/mmk/yachts
 - https://localhost:8443/mmk/offer