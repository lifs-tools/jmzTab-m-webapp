# Operating

## The services

An instance is three processes (or containers):

| Service | Image | Port | Memory (container limit) | Volume | Reachable from |
|---|---|---|---|---|---|
| Webapp | `lifs/mztab-validator-webapp` | 8083 | 1024 MB | yes, at `storage.location` | the reverse proxy |
| mzTab-M 2.0 engine | `lifs/mztab-validator-engine-2.0` | 8084 | 2048 MB | none | the webapp only |
| mzTab-M 2.1 engine | `lifs/mztab-validator-engine-2.1` | 8085 | 2048 MB | none | the webapp only |

- **The webapp** serves the browser application and the REST API, stores uploads and results, and sends each file to the engine of its mzTab-M version. It needs no outbound internet access except for the example files.
- **The engines** parse, validate and convert. They are stateless: no volume, no session, nothing to back up. They need outbound HTTPS to the Ontology Lookup Service for semantic validation.
- **The 2.1 engine is optional.** Without it, the webapp handles mzTab-M 2.0 only, and a 2.1 file gets one error message (with a link, if `engines.unavailable-hint-url` is set). The 2.1 engine is built on a jmzTab-m SNAPSHOT and is not part of releases until jmzTab-m 2.1.0 is released.
- **Keep the engines internal.** They have no authentication: attach them to a network that only the webapp shares, publish no ports and give them no reverse-proxy route. The webapp finds them by the URLs in `engines.mztabm[n].url` (see [Configuration](configuration.md#validation-engines)); the defaults match the compose service names `mztab-engine-2-0` and `mztab-engine-2-1`.

## Running the JARs

```sh
java -jar mztab-validator-engine-2.0-<version>.jar &      # port 8084
java -jar mztab-validator-engine-2.1-<version>.jar &      # port 8085, optional
java -jar mztab-validator-webapp-<version>.jar \
  --storage.location=/var/lib/mztabvalidator \
  "--engines.mztabm[0].line=2.0" "--engines.mztabm[0].url=http://localhost:8084" \
  "--engines.mztabm[1].line=2.1" "--engines.mztabm[1].url=http://localhost:8085"
```

The webapp listens on port 8083 below `/mztabvalidator` and logs to standard output. It is ready when `/mztabvalidator/actuator/health` answers `{"status":"UP",…}`; the engines are ready when their `/actuator/health` (no context path) does. From a checkout, `scripts/run-dev-stack.sh` does the same (see [Building](building.md#running-all-three-locally)).

A systemd unit per process, as an example for the webapp:

```ini
[Unit]
Description=mzTab Validator
After=network-online.target
Wants=network-online.target

[Service]
User=mztabvalidator
WorkingDirectory=/opt/mztabvalidator
Environment=STORAGE_LOCATION=/var/lib/mztabvalidator
Environment=ENGINES_MZTABM_0_LINE=2.0
Environment=ENGINES_MZTABM_0_URL=http://localhost:8084
ExecStart=/usr/bin/java -Xmx768m -jar /opt/mztabvalidator/mztab-validator-webapp.jar
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

An engine's unit is the same without `STORAGE_LOCATION` and the engine settings, with its JAR and e.g. `-Xmx1500m`. The webapp's user needs write access to `storage.location`. If the engines run on the same host, bind them to localhost (`--server.address=127.0.0.1`).

## Running the containers

With Docker Compose (the engines on a network of their own, which the webapp joins):

```yaml
services:
  mztabvalidator:
    image: <registry>/mztab-validator-webapp:<version>
    restart: unless-stopped
    ports:
      - "127.0.0.1:8083:8083"
    environment:
      ENGINES_MZTABM_0_LINE: "2.0"
      ENGINES_MZTABM_0_URL: http://mztab-engine-2-0:8084
      ENGINES_MZTABM_1_LINE: "2.1"
      ENGINES_MZTABM_1_URL: http://mztab-engine-2-1:8085
      SPRING_APPLICATION_JSON: '{"minCleanupAge": 14, "storage.max-total-bytes": 21474836480}'
    volumes:
      - mztabvalidator-data:/tmp/upload-dir
    networks: [default, engines]
    deploy:
      replicas: 1
      resources:
        limits:
          memory: 1024m
  mztab-engine-2-0:
    image: <registry>/mztab-validator-engine-2.0:<version>
    restart: unless-stopped
    networks: [engines]
    deploy:
      resources:
        limits:
          memory: 2048m
  mztab-engine-2-1:                     # optional
    image: <registry>/mztab-validator-engine-2.1:<version>
    restart: unless-stopped
    networks: [engines]
    deploy:
      resources:
        limits:
          memory: 2048m
networks:
  engines: {}
volumes:
  mztabvalidator-data:
```

- Without the 2.1 engine, drop its service and the two `ENGINES_*` variables of index 0 and 1: the default configuration is the 2.0 engine at `http://mztab-engine-2-0:8084`.
- Mount a volume at `/tmp/upload-dir`, the default `storage.location` in the webapp image, or mount it elsewhere and set `STORAGE_LOCATION`. The engines need no volume.
- Set a memory limit for each container. The JVMs size their heap to 75 % of it (`-XX:MaxRAMPercentage=75`). An `entrypoint` override replaces this flag; repeat it there.
- Quote `"2.0"` and `"2.1"`: unquoted, YAML reads them as numbers.
- Do not mark the engines' network `internal`: the engines need outbound HTTPS to the Ontology Lookup Service.
- Publish the webapp's port on `127.0.0.1` only if a reverse proxy on the same host serves it; the application trusts `X-Forwarded-*` headers.

Keep `replicas: 1` for the webapp. Results are files of one instance; a second instance would not find them. The engines could run more replicas, but one per version is enough: each runs `engine.max-concurrent-jobs` jobs at once.

## Reverse proxy

Put a reverse proxy with TLS in front of the application. Serve it below the same path on the proxy as in the application (`server.servlet.contextPath`, default `/mztabvalidator`), so that links and redirects match. For nginx:

```nginx
location /mztabvalidator/ {
    proxy_pass http://127.0.0.1:8083;          # no path: the request path is passed unchanged
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    client_max_body_size 64m;                  # at least spring.servlet.multipart.max-file-size
    proxy_read_timeout 300s;                   # the synchronous endpoints answer when the validation is done
    proxy_request_buffering off;               # stream uploads; the application checks their size
}
```

- **Upload size.** nginx accepts 1 MB by default and answers larger uploads itself with an HTML 413. Set `client_max_body_size` to at least the application's limit.
- **Timeouts.** `POST /rest/v2/validate`, `/validatePlain`, `/convert` and `/convertPlain` answer only when the work is done; a large file with semantic validation can take minutes. The form and `/rest/v2/validations` work in the background and are not affected.
- **Different paths.** If the public path must differ from the context path, also send `X-Forwarded-Prefix` with the public path, or set `server.servlet.contextPath` to the public path.

## Storage and retention

Each validation gets a directory named after its id below `storage.location`, holding the uploaded file, its result and the section data the result page reads. The REST endpoints that answer directly (`/validate`, `/validatePlain`, `/convert`, `/convertPlain`) delete their files before they answer.

- **Retention.** Every night at 11 pm (`oldFileMaintenanceSchedule`), directories older than `minCleanupAge` days (default 7) are deleted. A result link stops working then.
- **Quota.** While all files take up at least `storage.max-total-bytes` (default 10 GiB), new validations answer 503 and the form shows an error. Size the volume above the quota: a validation that is accepted may still add its files.
- **Backups** are optional: results are temporary by design. Back up the volume if you want links to survive the loss of a host.
- **Several instances** must not share one directory.

## Memory

Parsing moves to the engines; the webapp streams files to them and their results into storage.

- **Engines:** each job holds its parsed file, which needs several times the memory of the file itself; at most `engine.max-concurrent-jobs` jobs (default: the number of processors) run at once. Start with 2048 MB per engine container (a 1.5 GB heap). On a host with many processors and little memory, lower `engine.max-concurrent-jobs`: further jobs wait (the webapp retries) instead of running out of memory.
- **Webapp:** mainly the section cache for the result page (`sections.cache.max-bytes`, 256 MiB by default) and request handling. Uploads and engine results are spooled to disk. Start with 1024 MB.

These are starting values; watch `docker stats` with your largest files and semantic validation. If the webapp dies, e.g. out of memory, the validations it was running or had queued are marked as failed when it starts again. If an engine dies during a validation, that validation ends as failed with "The mzTab-M <version> validator is temporarily unavailable. Please try again later."; it is never left running.

## Monitoring

- **Health:** `GET <context path>/actuator/health` answers 200 with `"status":"UP"` while the webapp runs. Its disk check is for the working directory, not `storage.location`; watch the storage volume separately.
- **Engines:** the same health response has a component `engines` with one entry per configured engine, `engine-2-0` and `engine-2-1`. An engine that answers is `UP`, with its version line, mzTab-M versions, jmzTab-m version, build and limits; one that does not is `UNAVAILABLE`. `UNAVAILABLE` does not change the webapp's own status or its HTTP 200, so an engine outage does not restart or unroute the webapp; alert on the component instead, e.g. `jq '.components.engines.components'`. Each engine also has its own `/actuator/health` on its port.
- **Version:** `GET <context path>/rest/v2/app-info` (also shown in the footer and on the About page): the webapp's version and, under `engines`, each engine's mzTab-M versions, jmzTab-m version and availability. Also `/actuator/info`.
- **Logs:** standard output; unexpected errors are logged at `ERROR` with a stack trace.
- **Signals worth an alert:** an engine `UNAVAILABLE`, 503 answers (storage full, the synchronous endpoints saturated, or an engine busy or down), and a storage volume that is nearly full.

## Security

- There is **no login.** Everyone who can reach the application can validate files, and everyone who has a result link can read and delete that result. Result ids are random UUIDs. Restrict access at the reverse proxy if your instance must not be public.
- Users can upload their own CV mapping files (`validation.custom-mappings.enabled`). They are checked against the mapping schema and parsed without external entities. Turn the setting off if you only want to offer your validation profiles.
- `/actuator/health` shows details of the disk and of the engines; block `/actuator/` at the proxy if that is a concern.
- The engines have no authentication. Reachable from outside, they would validate files for anyone; keep them on an internal network (see [The services](#the-services)).

## Upgrading

1. Pull or build the new version of all images. The webapp and the engines are released together, with the same version; run matching versions.
2. Stop the old instance, start the engines and the new webapp with the same `storage.location`.

From 1.0.x to 2.0.0, add at least the 2.0 engine: the webapp of 2.0.0 contains no validator of its own.

Results are kept across restarts and upgrades within 2.x. The release notes are in [`docs/release-notes`](../release-notes).

**Going back to 1.0.x deletes all stored results:** version 1.0.x empties the whole storage directory when it starts. Result links of the 2.x period stop working.

## Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| An upload fails with an HTML "413 Request Entity Too Large" page | The reverse proxy's limit: raise `client_max_body_size` (nginx). |
| An upload answers 413 with a JSON body "The uploaded file is too large." | The application's limit: raise `spring.servlet.multipart.max-file-size` and `max-request-size`. |
| `/rest/v2/validate…` or `/convert…` answer 503 with `Retry-After` | All `rest.synchronous.max-concurrent` slots were busy for `rest.synchronous.wait`. Retry after the given seconds, send fewer requests at once, or raise the limit if memory allows. |
| New validations answer 503 "The validator's storage is full." | `storage.max-total-bytes` reached. Wait for the nightly cleanup, lower `minCleanupAge`, or raise the quota and the volume. |
| Every validation fails with "The mzTab-M 2.0 validator is temporarily unavailable." | The webapp cannot reach the engine. Check the `engines` component of `/actuator/health`, that the engine runs, and that `engines.mztabm[n].url` names it on a network the webapp shares. |
| A 2.1 file gets "This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0." | No 2.1 engine is configured. Add it and `ENGINES_MZTABM_1_LINE` / `_URL`, or set `engines.unavailable-hint-url` to point users elsewhere. |
| The webapp does not start: "engines.default-line is 2.0, but no engine is configured for it" | `engines.default-line` names a line without an engine, often because `ENGINES_MZTABM_0_*` replaced the default list without a 2.0 entry. |
| Validations answer 503 with `Retry-After` or stay queued for a long time | The engine is busy: all `engine.max-concurrent-jobs` slots are taken. Wait, or raise the limit if the engine has memory to spare. |
| Semantic validation fails or reports unknown terms in a file that is correct | The engines cannot reach the Ontology Lookup Service. Allow the engines outbound HTTPS to `www.ebi.ac.uk` (or the host in `engine.ols.host`), or validate without a profile. |
| Picking an example file fails | No access to `raw.githubusercontent.com`. Everything else still works. |
| The page loads without styles, or REST calls go to the wrong path | The proxy path differs from `server.servlet.contextPath`. Use the same path on both, or send `X-Forwarded-Prefix`. |
| Results disappear after a restart | `storage.location` is not on a persistent volume. |
