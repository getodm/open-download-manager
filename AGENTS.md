# Open Download Manager Agent Guide

## Repository Shape
- The root Maven reactor builds `core` and `odm-gtk4`; `odm-lib` exists but is currently commented out of the reactor.
- `core` owns the download engine, lifecycle, settings, and aria2/yt-dlp/httrack/curl/proxy/Tor integrations; `odm-gtk4` owns the GTK4 application and depends on `core`.
- The production entry point is `org.odm.gtk4.OdmApplication`; startup initializes `ApplicationContext`, initializes the `DownloadManager`, then enters the GTK main loop.
- The build targets Java 25. The Docker image is based on `eclipse-temurin:25-jdk` and supplies the native tools and GTK libraries.

## Commands
- Use `make build` to build the `odm-dev` Docker image. Every other `make` target except `clean` also rebuilds the image first.
- Use `make compile` for the canonical compile/package check; it runs `mvn clean compile package -DskipTests=true` in Docker.
- Use `make test` for regular core and GTK tests, including `WindowSmokeTest`; it starts Xvfb in Docker and runs `mvn test`. The image provides aria2, yt-dlp, httrack, proxychains4, tor, GTK4, and Xvfb.
- Use `make test-integration` for only the integration/E2E tests excluded by `make test`; it runs `mvn test -Pintegration`. Run both commands for full non-performance coverage.
- Surefire reports are separated under each module's `target/surefire-reports/unit`, `integration`, and `performance` directories.
- The regular core suite enforces the existing JaCoCo coverage thresholds. Integration runs collect a separate coverage report without applying project-wide thresholds to that subset.
- Run one core test with `mvn -pl core -Dtest=ClassName#methodName test` inside the prepared Java/native-tool environment.
- Use `make run` to launch the GTK application with X11 forwarding; use `make debug` for the suspended JDWP server on port 5005.
- Use `make package` to create `.deb`, `.rpm`, and `.pkg.tar.zst` artifacts under `packaging/dist/`; packaging also builds a shaded GTK jar and bundled jlink runtime.

## Testing Constraints
- Surefire forks each test class with `reuseForks=false` because static `ApplicationContext`/download-manager lifecycle state can poison later tests.
- Keep suite selection in the parent POM and preserve the `**/*$*` exclusion: JUnit runs `@Nested` tests through their outer class, so Maven must not launch them independently too.
- Tests exercise real external tools and native GTK behavior; prefer the Docker commands over host execution when dependencies are missing.
- Add tests under the module they cover using JUnit 5 `*Test.java` naming. Do not bypass assertions or replace critical core behavior with mocks.

## GTK Boundaries
- GTK definitions live in `odm-gtk4/src/main/resources/ui/*.ui` and are loaded from the classpath by `UiLoader`; preserve widget IDs and signal wiring when changing UI behavior.
- Do not redesign or casually edit the existing `.ui` layouts. Widget lookup is intentionally fail-fast, so an ID mismatch breaks window construction.
- GTK widget access must run on the GTK thread. Marshal core event callbacks through `org.odm.gtk4.UiThread` rather than touching widgets directly.

## Project Rules
- Do not leave methods unimplemented.
- Keep changes aligned with the existing aria2, yt-dlp, httrack, and native Linux tool model.
- Do not add README, example, or demo files unless explicitly requested.
