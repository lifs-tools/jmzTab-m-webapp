# Building

## Prerequisites

- **A JDK, version 24 or later.** The code is compiled for Java 24 (`maven.compiler.release` in `pom.xml`).
- **No Maven installation.** Use the Maven wrapper `./mvnw` (`mvnw.cmd` on Windows); it downloads the Maven version the project expects.
- **No Node.js installation.** The `frontend` module downloads its own Node.js (version in `frontend/pom.xml`, `frontend.node.version`) into `frontend/node` and installs the npm packages with `npm ci`.
- **Internet access during the build** to:
  - Maven Central;
  - `https://m.lifs-tools.org/artifactory/`, which hosts jmzTab-m and its snapshots;
  - the npm registry;
  - for an image, the registry of the base image (Docker Hub).
- **Docker** (or another tool that builds from a `Dockerfile`) only if you build an image locally.

## The modules

The build is a Maven reactor:

| Module | Contents |
|---|---|
| `server` | The webapp: a Spring Boot application with the REST API, the storage of results and the browser application. It has no mzTab-M parser of its own; it sends every file to the engine of its mzTab-M version. |
| `frontend` | The Angular browser application. Maven builds it and packages the result as a JAR that `server` depends on. |
| `engine-api` | The contract between the webapp and the engines (request and result types, the engine OpenAPI document). |
| `engine-core-src` | The engine sources, shared by all engines. Not a module of its own: each engine compiles them. |
| `engine-parent` | The build every engine shares (dependencies, executable JAR, image). |
| `engine-mztabm-2.0` | The mzTab-M 2.0 engine: `engine-core-src` with jmzTab-m 1.0.x. Port 8084. |
| `engine-mztabm-2.1` | The mzTab-M 2.1 engine: `engine-core-src` with jmzTab-m 2.1.x. Port 8085. Built by default, left out with `-DskipSnapshotEngines` (see [Releases](#releases)). |

The jmzTab-m versions of the engines are the properties `jmztabm20.version` and `jmztabm21.version` in `pom.xml`.

## Build and test

Build all modules and run the unit tests of the engines, the server and the browser application:

```sh
./mvnw -B verify
```

The results are three executable JARs:

- `server/target/mztab-validator-webapp-<version>.jar`;
- `engine-mztabm-2.0/target/mztab-validator-engine-2.0-<version>.jar`;
- `engine-mztabm-2.1/target/mztab-validator-engine-2.1-<version>.jar`.

The server tests start the packaged 2.0 engine, so build the server together with it: `./mvnw -B -pl server -am verify`.

Faster variants:

| Command | Effect |
|---|---|
| `./mvnw -B package -DskipTests` | Builds the JARs without running tests. |
| `./mvnw -B verify -Dfrontend.skip=true` | Builds and tests without the browser application. The webapp JAR then has **no browser application**; use it for the REST API or development only. |
| `./mvnw -B verify -DskipSnapshotEngines` | Leaves out the 2.1 engine. |

### Running all three locally

`scripts/run-dev-stack.sh` starts both engines and the webapp from the packaged JARs, as they run in production (building them first if a JAR is missing):

```sh
scripts/run-dev-stack.sh              # http://localhost:8083/mztabvalidator/
scripts/run-dev-stack.sh --build      # rebuild the JARs first (./mvnw -B package -DskipTests)
scripts/run-dev-stack.sh --no-21      # without the 2.1 engine
```

The engines log to `target/dev-stack/engine-2.0.log` and `engine-2.1.log`; Ctrl-C stops all three. `compose/dev-stack.yml` runs the same JARs in containers: `docker compose -f compose/dev-stack.yml up`.

### End-to-end tests

The Playwright end-to-end tests are not part of `verify`. They run against a webapp and both engines that are already running, e.g. started by `scripts/run-dev-stack.sh`:

```sh
scripts/run-dev-stack.sh --build
# in a second terminal
npm --prefix frontend ci
npx --prefix frontend playwright install chromium
npm --prefix frontend run e2e
```

`E2E_BASE_URL` overrides the default address `http://localhost:8083/mztabvalidator/`; it must end with a slash.

## Build-time settings

A few settings are written into the JAR's `application.properties` during the build, from Maven properties. All of them can still be overridden when the application starts (see [Configuration](configuration.md)), so you rarely need to change them at build time.

| Maven property | Default | Becomes |
|---|---|---|
| `contextPath` | `/mztabvalidator` | `server.servlet.contextPath` |
| `spring.profiles.active` | empty; `-Pdev` sets `dev`, `-Pmicroservice` sets `microservice` | `spring.profiles.active` |
| `ga.id`, `ga.url`, `ga.app.url` | empty | the analytics settings of the same name |

Example: `./mvnw -B package -DskipTests -DcontextPath=/validator`.

## Docker images

There are three images, one per application module:

| Image | Module | Port |
|---|---|---|
| `lifs/mztab-validator-webapp` | `server` | 8083 |
| `lifs/mztab-validator-engine-2.0` | `engine-mztabm-2.0` | 8084 |
| `lifs/mztab-validator-engine-2.1` | `engine-mztabm-2.1` | 8085 |

All three are built with [Jib](https://github.com/GoogleContainerTools/jib) (profile `docker`) on the Eclipse Temurin 25 JRE (`docker.base.image` in `pom.xml`), without development tools, and start the JVM with `-XX:MaxRAMPercentage=75`, so the heap follows the container's memory limit. The image name is `${docker.image.prefix}/<artifactId>` (prefix `docker.lifs-tools.org/lifs`), tagged with the project version.

### Locally, into the Docker daemon

```sh
./mvnw -B install -DskipTests
./mvnw -B -Pdocker jib:dockerBuild -pl server,engine-mztabm-2.0,engine-mztabm-2.1
```

This builds `docker.lifs-tools.org/lifs/mztab-validator-webapp:<version>` and the two engine images into the local Docker daemon, tagged with the version only (not `latest`), and pushes nothing. The first step installs the module JARs into your local Maven repository, so that the second finds them as JARs. In a single run such as `./mvnw -Pdocker compile jib:dockerBuild`, Maven hands Jib the modules of the build (`engine-api`, `frontend`) as class directories, which Jib leaves out of the image: the containers then fail at startup with a `NoClassDefFoundError`.

Add `-DskipSnapshotEngines` and leave out `engine-mztabm-2.1` to build only the webapp and the 2.0 engine.

`server/Dockerfile` still packages a webapp JAR you have built (`docker build --build-arg JAR_FILE=mztab-validator-webapp-<version>.jar -t mztab-validator-webapp:<version> server`); there is no Dockerfile for the engines.

### With Jib, to a registry

With the profile `docker`, Jib **pushes each image to a registry** as part of `package`. The default target, `docker.lifs-tools.org/lifs`, is the LIFS project's own registry; you cannot push to it. Name your registry with `docker.image.prefix`, which keeps one image name per module:

```sh
docker login registry.example.org        # Jib uses the Docker credential store
./mvnw -B package -Pdocker -DskipTests -Ddocker.image.prefix=registry.example.org/mztab
```

This pushes `registry.example.org/mztab/mztab-validator-webapp:<version>`, `…/mztab-validator-engine-2.0:<version>` and `…/mztab-validator-engine-2.1:<version>`. Add `-Djib.to.tags=<version>,latest` for a `latest` tag as well. Do not use `-Djib.to.image`: it would give all three modules the same image name.

Do not run `./mvnw package -Pdocker` without `-Ddocker.image.prefix`: it tries to push to `docker.lifs-tools.org`.

### Published images

The LIFS project publishes its images from GitHub Actions (`.github/workflows/maven.yml`) as `docker.lifs-tools.org/lifs/mztab-validator-webapp`, `docker.lifs-tools.org/lifs/mztab-validator-engine-2.0` and `docker.lifs-tools.org/lifs/mztab-validator-engine-2.1`, tagged with the version:

- a push to `master` publishes all three with the version and `latest`; a release version (without `-SNAPSHOT`) leaves out the 2.1 engine, as a release does, and is first checked with `./mvnw -B -Prelease -DskipSnapshotEngines -DskipTests validate`: if any dependency is still a SNAPSHOT, nothing is pushed;
- a manual run ("Run workflow") on the integration branch `feat/validator-spa` publishes all three with the version only (e.g. `2.0.0-SNAPSHOT`), never `latest`.

## Releases

A release depends only on released artifacts. The `release` profile runs the Maven Enforcer rule `requireReleaseDeps` on every module: a SNAPSHOT dependency, jmzTab-m included, fails the build. The modules of this build (`org.lifs-tools:mztab-validator-*`) are exempt; they are released together.

The 2.1 engine is built on jmzTab-m 2.1.0-SNAPSHOT, so it stays out of releases until jmzTab-m 2.1.0 is released. Build releases with `-DskipSnapshotEngines`:

```sh
./mvnw -B -Prelease -DskipSnapshotEngines verify
```

- Pass `-DskipSnapshotEngines` as a bare flag. The profile `snapshot-engines`, which adds `engine-mztabm-2.1`, is active while the property is **not set at all** (activation `!skipSnapshotEngines`), so `-DskipSnapshotEngines=false` leaves the 2.1 engine out as well. To build it, leave the property out.
- Without the flag the reactor lists `engine-mztabm-2.1`, and the release build fails on its jmzTab-m SNAPSHOT.
- The release also needs a released jmzTab-m for the 2.0 engine (`jmztabm20.version`). As long as it is `1.0.8-SNAPSHOT`, even `./mvnw -B -Prelease -DskipSnapshotEngines -DskipTests validate` fails:

  ```
  [ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.3:enforce (enforce-release-dependencies) on project mztab-validator-engine-parent:
  [ERROR] Rule 0: org.apache.maven.enforcer.rules.dependency.RequireReleaseDeps failed with message:
  [ERROR] A release must not depend on SNAPSHOT versions: release them first (e.g. jmzTab-m), and build with -DskipSnapshotEngines.
  [ERROR] org.lifs-tools:mztab-validator-engine-parent:pom:2.0.0-SNAPSHOT
  [ERROR]    org.lifs-tools:jmztabm-io:jar:1.0.8-SNAPSHOT <--- is not a release dependency
  ```

  Release jmzTab-m 1.0.8 first and set `jmztabm20.version` to `1.0.8`.

**Bump versions without `-DskipSnapshotEngines`.** Only the release build itself takes the flag. Set the release version, and afterwards the next development version, with the 2.1 engine in the reactor, for example:

```sh
./mvnw -B versions:set -DnewVersion=2.0.0 -DgenerateBackupPoms=false
```

`engine-mztabm-2.1/pom.xml` names the version of its parent (`engine-parent`), so the bump must cover that module too. With `-DskipSnapshotEngines` the module is not part of the reactor: a tool that changes only the reactor's modules (such as the Maven Release Plugin's `release:prepare`) leaves its parent version stale, and the next default build, which includes the 2.1 engine, fails. (`versions:set` also updates modules of inactive profiles, but the flag gains nothing there either.)

A release therefore publishes `lifs/mztab-validator-webapp:<version>` and `lifs/mztab-validator-engine-2.0:<version>`. Once jmzTab-m 2.1.0 is released, `engine-mztabm-2.1` moves from the `snapshot-engines` profile into the modules of every build.
