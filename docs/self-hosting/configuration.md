# Configuration

The application is a Spring Boot application, so every setting can be given in any of the usual ways. Later entries override earlier ones:

1. the defaults in the JAR (`server/src/main/resources/application.properties`);
2. an `application.properties` or `application.yml` in the working directory or in `./config/`, or a file named with `--spring.config.additional-location=file:/etc/mztabvalidator/`;
3. environment variables: upper case, with `.` and `-` replaced by `_`, e.g. `STORAGE_LOCATION`, `REST_SYNCHRONOUS_MAX_CONCURRENT`, `MINCLEANUPAGE`;
4. a JSON object in `SPRING_APPLICATION_JSON`, e.g. `SPRING_APPLICATION_JSON='{"storage.location":"/data"}'`;
5. command line arguments, e.g. `--storage.location=/data`.

Environment variables or `SPRING_APPLICATION_JSON` suit containers best.

## Server

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8083` | HTTP port. |
| `server.servlet.contextPath` | `/mztabvalidator` | The path the application is served below. The browser application finds its base path at runtime, so any value works, including `/`. |
| `server.forward-headers-strategy` | `FRAMEWORK` | The application trusts the `X-Forwarded-*` headers of a reverse proxy. Only expose the port to that proxy, or set this to `NONE`. |

## Storage and retention

| Property | Default | Meaning |
|---|---|---|
| `storage.location` | `${java.io.tmpdir}/upload-dir` (`/tmp/upload-dir` in the images) | Directory for uploads, validation results and the data shown on the result page. Use a persistent directory or volume. |
| `storage.max-total-bytes` | `10737418240` (10 GiB) | While the stored files take up at least this many bytes, new validations are refused (503). |
| `minCleanupAge` | `7` | Retention in days. The nightly cleanup removes results older than this. |
| `oldFileMaintenanceSchedule` | `0 0 23 * * *` (11 pm daily) | When the cleanup runs: a Spring cron expression (seconds, minutes, hours, day of month, month, day of week), in the time zone of the JVM. |
| `sections.cache.max-bytes` | `268435456` (256 MiB) | Size of the in-memory cache for the table and metadata data shown on the result page. |

## Uploads and the synchronous endpoints

| Property | Default | Meaning |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | `64MB` | The largest file a user can upload in the form or send to `/rest/v2/validations`. It is also the largest file accepted by the synchronous endpoints `/rest/v2/validate`, `/validatePlain`, `/convert` and `/convertPlain`. Larger files answer 413. |
| `spring.servlet.multipart.max-request-size` | `64MB` | The largest multipart request. Raise it together with `max-file-size`; the form also sends a mapping file. |
| `rest.synchronous.max-concurrent` | the number of processors | How many requests to the synchronous endpoints run at once. Each holds its whole file in memory, so lower this on hosts with little memory. |
| `rest.synchronous.wait` | `10s` | How long such a request waits for a free slot. Then it answers 503 with `Retry-After: 5`. |

Validations started in the form or with `POST /rest/v2/validations` run in the background, at most one fewer than the number of processors at once (at least one); further ones wait in a queue. This is not configurable.

## Validation

| Property | Default | Meaning |
|---|---|---|
| `validation.custom-mappings.enabled` | `true` | Whether users may upload their own CV mapping file for semantic validation. Uploaded mapping files are checked against the mapping schema, and external entities are not resolved. |
| `validation.profiles.index` | `classpath:/validation-profiles/index.json` | The catalog of validation profiles: a Spring resource location, e.g. `file:/etc/mztabvalidator/profiles/index.json`. See [Validation profiles](#validation-profiles). |

### Validation profiles

A validation profile is a set of semantic rules (a CV mapping file) that users select in the form or pass to `/rest/v2/validations`. The catalog `index.json` lists the profiles:

```json
[{"id": "mztab-m-2.0-default", "version": "1.0.0", "title": "mzTab-M 2.0 default rules",
  "description": "The reference semantic rules shipped with jmzTab-m.", "mzTabVersions": ["2.0.0-M"]}]
```

Each profile's mapping file is `<id>/<version>.xml` next to the catalog, e.g. `mztab-m-2.0-default/1.0.0.xml`; see `server/src/main/resources/validation-profiles/`. To offer your own profiles, copy that directory, add yours, and point `validation.profiles.index` at the copy. The catalog is served at `/rest/v2/validation-profiles`.

## Examples

The form offers example files. They are listed in `examples.properties` in the JAR (`server/src/main/resources/examples.properties`, prefix `example.exampleFile[n]`) and downloaded from their URIs when a user picks one (at most 64 MB each). The defaults point to `raw.githubusercontent.com`; without outbound access to it, the examples fail but everything else works.

## Analytics

Analytics are off by default.

| Property | Default | Meaning |
|---|---|---|
| `ga.id` | empty | Matomo site id. If empty, no tracker is loaded in the browser and no events are sent. |
| `ga.url` | empty | Matomo tracking endpoint for anonymous server-side events. |
| `ga.app.url` | empty | Public URL of the application, reported to Matomo. |
| `matomo.tracker-base` | `https://lifs-tools.org/matomo/` | Base URL of the Matomo instance used by the browser tracker. |

## Logging

The application logs to the console (standard output), in Spring Boot's default format.

| Property | Default | Meaning |
|---|---|---|
| `logging.level.org.lifstools` | `INFO` | The application. |
| `logging.level.uk.ac.ebi` | `INFO` | jmzTab-m's parser and validator. |
| `logging.level.org.springframework.web` | `INFO` | Request handling. |
| `logging.file.name` | not set | Also log to this file. |

## Profiles

| Profile | Effect |
|---|---|
| `dev` | Debug logging, and the cleanup runs every minute. For development only. |
| `microservice` | Registers the instance with a Eureka service registry at `EUREKA_URI` (default `http://localhost:28092/eureka`), with the health and info endpoints as its status pages. |

Activate a profile with `SPRING_PROFILES_ACTIVE=microservice` or `--spring.profiles.active=microservice`.

## Health and info endpoints

`/actuator/health` and `/actuator/info` (below the context path) are the only management endpoints. Health shows details, including the free disk space of the working directory. If you do not want them public, block `/actuator/` at the reverse proxy.
