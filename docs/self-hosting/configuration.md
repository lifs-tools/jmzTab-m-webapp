# Configuration

The webapp and the engines are Spring Boot applications, so every setting can be given in any of the usual ways. Later entries override earlier ones:

1. the defaults in the JAR (webapp: `server/src/main/resources/application.properties`; engines: `engine-core-src/src/main/resources/application.properties`);
2. an `application.properties` or `application.yml` in the working directory or in `./config/`, or a file named with `--spring.config.additional-location=file:/etc/mztabvalidator/`;
3. environment variables: upper case, with `.` replaced by `_` and `-` removed (Spring Boot's canonical form) or replaced by `_`, e.g. `STORAGE_LOCATION`, `REST_SYNCHRONOUS_MAX_CONCURRENT`, `MINCLEANUPAGE`; a list index becomes `_<n>_`, e.g. `ENGINES_MZTABM_0_URL` for `engines.mztabm[0].url`;
4. a JSON object in `SPRING_APPLICATION_JSON`, e.g. `SPRING_APPLICATION_JSON='{"storage.location":"/data"}'`;
5. command line arguments, e.g. `--storage.location=/data`.

Environment variables or `SPRING_APPLICATION_JSON` suit containers best. In a compose file, quote values that look like numbers: `ENGINES_MZTABM_0_LINE: "2.0"`, not `2.0`, which YAML reads as a number.

The sections up to [Health and info endpoints](#health-and-info-endpoints) are the webapp's settings; the engines have [their own](#engines-mztab-m-20-and-21).

## Server

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8083` | HTTP port of the webapp. |
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

### Working copies (the editor)

"Fix this file" and "Open a file to fix" create a working copy of an upload. It is not configured separately; it uses the settings above.

- **Where it lives.** In the directory of the validation, in a slot named `WORKINGCOPY`, as one SQLite database file (`copy.db`) that holds the exact text of the file line by line, the undo history and the last validation. The upload stays untouched in its own slot. Deleting a result deletes its copy.
- **Retention.** The same as the result: `minCleanupAge` days after the last change. Every edit, undo and redo starts the time again. The nightly cleanup (`oldFileMaintenanceSchedule`) removes the whole directory.
- **Quota.** The copy counts against `storage.max-total-bytes`. Creating a copy needs about three times the size of the upload free (`storage.max-total-bytes` minus what is stored): the temporary text, the parsed lines and the database. When there is less, the request answers 503 (the same as a new validation) and the editor says the storage is full. Edits, undo and redo are refused with 503 while the stored files take up `storage.max-total-bytes` or more (the usage is re-measured at most every few seconds); reading and downloading still work.
- **Limits (fixed, not settings).** A copy has at most 2,000,000 lines, and a line at most 20,000 cells (tab-separated parts, the prefix included). A file over either limit is refused while it is read, with 422 and the error code `too_large`, and nothing is stored; the editor shows the message, which names the limit. No edit can go beyond them (400). An edit request holds at most 5,000 operations; one cell at most 100,000 characters; one raw line at most 1,000,000 characters; a page is 1 to 200 rows; a range of lines at most 1,000. The undo history keeps at most 100 steps and is bounded in size. Anything out of range answers 400.
- **SQLite.** The webapp uses `org.xerial:sqlite-jdbc`, which carries its native libraries inside the JAR and unpacks the one for the platform into the temporary directory (`java.io.tmpdir`) when the first copy is opened. The images are built on `eclipse-temurin:25-jre` (Ubuntu, glibc), which the bundled library supports. On a musl system (Alpine) or an unusual platform the library may be missing; use a glibc base image. The temporary directory must be writable and not mounted `noexec`.
- **Encoding.** Uploads must be UTF-8. If one is not, the editor offers to read it as Windows-1252 and stores it as UTF-8. The original is kept as uploaded and is what "Download original" returns.
- **Validation engines.** Each check of a copy sends its whole text to the engine of its mzTab-M version, like a new validation. The version is read again from the text after every edit, so changing `mzTab-version` can move a copy to the other engine.

## Validation engines

The webapp sends every file to the engine of its mzTab-M version: a `2.0.x-M` file to the 2.0 engine, a `2.1.x-M` file to the 2.1 engine. It reads the declared `mzTab-version` itself and parses nothing else. A version whose engine is not configured is not offered: a file of that version gets one error message, a profile of that version is not listed.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `engines.mztabm[n].line` | `ENGINES_MZTABM_<n>_LINE` | `engines.mztabm[0].line=2.0` | The mzTab-M version line of the n-th engine, `MAJOR.MINOR` (`2.0`, `2.1`). Each line may have one engine. |
| `engines.mztabm[n].url` | `ENGINES_MZTABM_<n>_URL` | `engines.mztabm[0].url=http://mztab-engine-2-0:8084` | Its base URL, `http` or `https`. The default is the compose service name of the 2.0 engine. |
| `engines.default-line` | `ENGINES_DEFAULTLINE` | `2.0` | The engine for files that declare no `mzTab-version`. Such a file still gets Error-2008 (missing version); the default only decides which version's other checks run. The webapp does not start if no engine is configured for this line. |
| `engines.unavailable-hint-url` | `ENGINES_UNAVAILABLEHINTURL` | empty | Where users are pointed when a version has no engine on this instance, e.g. another validator that has one. The message and the form say "Another validator may support mzTab-M <version>" with this link. The public production instance points to the development validator. Empty: no link. |
| `engines.connect-timeout` | `ENGINES_CONNECTTIMEOUT` | `5s` | How long the webapp waits for a connection to an engine, and for an engine's answer about itself. |
| `engines.job-timeout` | `ENGINES_JOBTIMEOUT` | `10m` | How long the webapp waits for an engine to finish a validation or conversion. A background validation that runs out of time ends as failed. |

- **A list replaces the default as a whole.** Setting `ENGINES_MZTABM_0_*` and `ENGINES_MZTABM_1_*` configures exactly these two engines. To keep the default 2.0 engine and add the 2.1 engine, set both:

  ```sh
  ENGINES_MZTABM_0_LINE=2.0
  ENGINES_MZTABM_0_URL=http://mztab-engine-2-0:8084
  ENGINES_MZTABM_1_LINE=2.1
  ENGINES_MZTABM_1_URL=http://mztab-engine-2-1:8085
  ```

- The environment variables with `_` for each `-` (`ENGINES_DEFAULT_LINE`, `ENGINES_UNAVAILABLE_HINT_URL`, `ENGINES_CONNECT_TIMEOUT`, `ENGINES_JOB_TIMEOUT`) work as well.
- Durations are Spring Boot durations: `30s`, `5m`, `1h`, or ISO-8601 (`PT10M`).
- The webapp starts without its engines and asks each one about itself every minute, on every health check and when a background validation goes to it, so the start order does not matter.
- A busy engine (all its jobs running) answers 503. Background validations then wait and retry, up to `engines.job-timeout`; the synchronous endpoints pass the 503 with `Retry-After` on to the client.
- There is no authentication between webapp and engines. Make the engines reachable from the webapp only (see [Operating](operating.md#the-services)).

## Uploads and the synchronous endpoints

| Property | Default | Meaning |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | `64MB` | The largest file a user can upload in the form or send to `/rest/v2/validations`. It is also the largest file accepted by the synchronous endpoints `/rest/v2/validate`, `/validatePlain`, `/convert` and `/convertPlain`. Larger files answer 413. |
| `spring.servlet.multipart.max-request-size` | `64MB` | The largest multipart request. Raise it together with `max-file-size`; the form also sends a mapping file. |
| `rest.synchronous.max-concurrent` | the number of processors | How many requests to the synchronous endpoints run at once. Each spools its file to the temporary directory (`java.io.tmpdir`) and waits for an engine. |
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
| `logging.level.org.springframework.web` | `INFO` | Request handling. |
| `logging.file.name` | not set | Also log to this file. |

## Profiles

| Profile | Effect |
|---|---|
| `dev` | Debug logging, and the cleanup runs every minute. For development only. |
| `microservice` | Registers the instance with a Eureka service registry at `EUREKA_URI` (default `http://localhost:28092/eureka`), with the health and info endpoints as its status pages. |

Activate a profile with `SPRING_PROFILES_ACTIVE=microservice` or `--spring.profiles.active=microservice`.

## Health and info endpoints

`/actuator/health` and `/actuator/info` (below the context path) are the only management endpoints. Health shows details, including the free disk space of the working directory and each engine (component `engines`, with `engine-2-0`, `engine-2-1`; see [Monitoring](operating.md#monitoring)). If you do not want them public, block `/actuator/` at the reverse proxy.

## Engines (mzTab-M 2.0 and 2.1)

The engines parse, validate and convert mzTab-M files, and ask the Ontology Lookup Service during semantic validation. They have no storage, no volume and no authentication, and serve only an internal API under `/engine/v1`, its OpenAPI document at `/v3/api-docs` (springdoc), and `/actuator/health` and `/actuator/info` (no context path). Both engines take the same settings.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8084` (2.0), `8085` (2.1) | HTTP port. If you change it, change `engines.mztabm[n].url` of the webapp as well. |
| `engine.max-concurrent-jobs` | `ENGINE_MAXCONCURRENTJOBS` | the number of processors | How many validations and conversions run at once. Further requests answer 503 with `Retry-After` (the webapp retries). Each job holds its parsed file in memory. |
| `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAXFILESIZE` | `64MB` | The largest file the engine accepts. Keep it at least the webapp's `spring.servlet.multipart.max-file-size`. |
| `spring.servlet.multipart.max-request-size` | `SPRING_SERVLET_MULTIPART_MAXREQUESTSIZE` | `70MB` | The largest request: the file plus a mapping file and the parameters. |
| `engine.ols.protocol` | `ENGINE_OLS_PROTOCOL` | `https` | The Ontology Lookup Service of semantic validation, as `<protocol>://<host>/<base-path>/api/…`. |
| `engine.ols.host` | `ENGINE_OLS_HOST` | `www.ebi.ac.uk` | Its host, optionally with a port (`ols.example.org:8443`). |
| `engine.ols.base-path` | `ENGINE_OLS_BASEPATH` | `ols` | Its base path, a single path segment. The defaults are the EBI OLS. |
| `logging.level.org.lifstools` | `LOGGING_LEVEL_ORG_LIFSTOOLS` | `INFO` | The engine. |
| `logging.level.uk.ac.ebi` | `LOGGING_LEVEL_UK_AC_EBI` | `INFO` | jmzTab-m's parser and validator, and the OLS client. |

`engine.line`, `engine.mztab-versions` and `engine.jmztabm-version` are set when the engine is built and describe what it validates; do not change them.

The engines answer `GET /engine/v1/info` with their version line, mzTab-M versions, jmzTab-m version, build and limits; the webapp shows it in its health endpoint and on the About page.
