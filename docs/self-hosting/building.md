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

The build is a Maven reactor with two modules:

| Module | Contents |
|---|---|
| `server` | The Spring Boot application: REST API, validation, storage of results. Its JAR also contains the browser application. |
| `frontend` | The Angular browser application. Maven builds it and packages the result as a JAR that `server` depends on. |

## Build and test

Build both modules and run the unit tests of the server and of the browser application:

```sh
./mvnw -B verify
```

The result is `server/target/mztab-validator-webapp-<version>.jar`, an executable JAR with all dependencies.

Faster variants:

| Command | Effect |
|---|---|
| `./mvnw -B package -DskipTests` | Builds the JAR without running tests. |
| `./mvnw -B verify -Dfrontend.skip=true` | Builds and tests the server only. The JAR then has **no browser application**; use it for the REST API or development only. |

### End-to-end tests

The Playwright end-to-end tests are not part of `verify`. They run against a packaged server that is already running:

```sh
./mvnw -B package -DskipTests
java -jar server/target/mztab-validator-webapp-<version>.jar --storage.location=$(mktemp -d)
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

## Docker image

### Locally, from the Dockerfile

`server/Dockerfile` packages a JAR you have built on an Eclipse Temurin JRE:

```sh
./mvnw -B package -DskipTests
docker build --build-arg JAR_FILE=mztab-validator-webapp-<version>.jar -t mztab-validator-webapp:<version> server
```

The build context is `server`, and `JAR_FILE` is the name of the JAR in `server/target`. The image starts the JVM with `-XX:MaxRAMPercentage=75`, so the heap follows the container's memory limit.

### With Jib, to a registry

The project's own images are built with [Jib](https://github.com/GoogleContainerTools/jib) (profile `docker`). Jib builds without a Docker daemon and **pushes the image to a registry** as part of `package`. Its default target is `docker.lifs-tools.org/lifs/mztab-validator-webapp:<version>`, which you cannot push to. Name your own registry and tag instead:

```sh
docker login registry.example.org        # Jib uses the Docker credential store
./mvnw -B package -Pdocker -DskipTests \
  -Djib.to.image=registry.example.org/mztab-validator-webapp:<version> \
  -Djib.to.tags=<version>
```

The Jib image uses an Azul Zulu JRE base image (`docker.base.image` in `pom.xml`) and the same JVM flags as the Dockerfile.

Do not run `./mvnw package -Pdocker` without `-Djib.to.image`: it tries to push to `docker.lifs-tools.org`.

### Published images

The LIFS project publishes its images as `docker.lifs-tools.org/lifs/mztab-validator-webapp`, tagged with the version. Release builds are also tagged `latest`; snapshot builds never are.
