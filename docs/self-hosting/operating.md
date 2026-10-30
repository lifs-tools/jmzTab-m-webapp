# Operating

## Running the JAR

```sh
java -Xmx1500m -jar mztab-validator-webapp-<version>.jar \
  --storage.location=/var/lib/mztabvalidator
```

The application listens on port 8083 below `/mztabvalidator` and logs to standard output. It is ready when `/mztabvalidator/actuator/health` answers `{"status":"UP",…}`.

A systemd unit, as an example:

```ini
[Unit]
Description=mzTab Validator
After=network-online.target
Wants=network-online.target

[Service]
User=mztabvalidator
WorkingDirectory=/opt/mztabvalidator
Environment=STORAGE_LOCATION=/var/lib/mztabvalidator
ExecStart=/usr/bin/java -Xmx1500m -jar /opt/mztabvalidator/mztab-validator-webapp.jar
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

The user needs write access to `storage.location`.

## Running the container

```sh
docker run -d --name mztabvalidator --restart unless-stopped \
  -p 127.0.0.1:8083:8083 -m 2g \
  -v mztabvalidator-data:/tmp/upload-dir \
  <image>:<version>
```

- Mount a volume at `/tmp/upload-dir`, the default `storage.location` in the images, or mount it elsewhere and set `STORAGE_LOCATION`.
- Set a memory limit (`-m`). The JVM sizes its heap to 75 % of it (`-XX:MaxRAMPercentage=75`). An `entrypoint` override replaces this flag; repeat it there.
- Publish the port on `127.0.0.1` only if a reverse proxy on the same host serves it; the application trusts `X-Forwarded-*` headers.

With Docker Compose:

```yaml
services:
  mztabvalidator:
    image: <image>:<version>
    restart: unless-stopped
    ports:
      - "127.0.0.1:8083:8083"
    environment:
      SPRING_APPLICATION_JSON: '{"minCleanupAge": 14, "storage.max-total-bytes": 21474836480}'
    volumes:
      - mztabvalidator-data:/tmp/upload-dir
    deploy:
      replicas: 1
      resources:
        limits:
          memory: 2g
volumes:
  mztabvalidator-data:
```

Keep `replicas: 1`. Results are files of one instance; a second instance would not find them.

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

The heap is used by:

- **Background validations:** at most one fewer than the number of processors at a time. A validation holds its parsed file, which needs more memory than the file itself.
- **Synchronous endpoints:** at most `rest.synchronous.max-concurrent` requests (default: the number of processors), each with its whole file, up to the upload limit.
- **The section cache** for the result page: `sections.cache.max-bytes`, 256 MiB by default.

2 GB (a 1.5 GB heap) serves the public instance with 64 MB uploads. On a host with many processors and little memory, lower `rest.synchronous.max-concurrent`. If the process dies, e.g. out of memory, the validations it was running or had queued are marked as failed when it starts again.

## Monitoring

- **Health:** `GET <context path>/actuator/health` answers 200 with `"status":"UP"` while the application runs. Its disk check is for the working directory, not `storage.location`; watch the storage volume separately.
- **Version:** `GET <context path>/rest/v2/app-info` (also shown in the footer of the application) and `/actuator/info`.
- **Logs:** standard output; unexpected errors are logged at `ERROR` with a stack trace.
- **Signals worth an alert:** 503 answers (storage full, or the synchronous endpoints are saturated), and a storage volume that is nearly full.

## Security

- There is **no login.** Everyone who can reach the application can validate files, and everyone who has a result link can read and delete that result. Result ids are random UUIDs. Restrict access at the reverse proxy if your instance must not be public.
- Users can upload their own CV mapping files (`validation.custom-mappings.enabled`). They are checked against the mapping schema and parsed without external entities. Turn the setting off if you only want to offer your validation profiles.
- `/actuator/health` shows details of the disk; block `/actuator/` at the proxy if that is a concern.

## Upgrading

1. Pull or build the new version.
2. Stop the old instance, start the new one with the same `storage.location`.

Results are kept across restarts and upgrades within 2.x. The release notes are in [`docs/release-notes`](../release-notes).

**Going back to 1.0.x deletes all stored results:** version 1.0.x empties the whole storage directory when it starts. Result links of the 2.x period stop working.

## Troubleshooting

| Symptom | Cause and remedy |
|---|---|
| An upload fails with an HTML "413 Request Entity Too Large" page | The reverse proxy's limit: raise `client_max_body_size` (nginx). |
| An upload answers 413 with a JSON body "The uploaded file is too large." | The application's limit: raise `spring.servlet.multipart.max-file-size` and `max-request-size`. |
| `/rest/v2/validate…` or `/convert…` answer 503 with `Retry-After` | All `rest.synchronous.max-concurrent` slots were busy for `rest.synchronous.wait`. Retry after the given seconds, send fewer requests at once, or raise the limit if memory allows. |
| New validations answer 503 "The validator's storage is full." | `storage.max-total-bytes` reached. Wait for the nightly cleanup, lower `minCleanupAge`, or raise the quota and the volume. |
| Semantic validation fails or reports unknown terms in a file that is correct | No access to the EBI Ontology Lookup Service. Allow outbound HTTPS to `www.ebi.ac.uk`, or validate without a profile. |
| Picking an example file fails | No access to `raw.githubusercontent.com`. Everything else still works. |
| The page loads without styles, or REST calls go to the wrong path | The proxy path differs from `server.servlet.contextPath`. Use the same path on both, or send `X-Forwarded-Prefix`. |
| Results disappear after a restart | `storage.location` is not on a persistent volume. |
