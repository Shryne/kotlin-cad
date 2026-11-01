# Third-party notices

The MIT license at the repository root covers this project's original source,
not third-party dependencies. Existing upstream notices must remain intact.

## Included in this source release

- **nlohmann/json 3.12.0**: MIT. The vendored header and complete license are in
  `native/third_party/nlohmann/`.
- **Gradle wrapper 8.13**: Apache License 2.0. The wrapper binary was obtained from
  the Gradle v8.13.0 repository and checked against Gradle's published wrapper
  checksum. Its archive retains upstream metadata. The Gradle distribution is
  downloaded separately with a pinned checksum.

## Build/runtime dependencies downloaded separately

- OpenCascade Technology 8.0.1: LGPL 2.1 with the OCCT exception. Native packaging
  includes the upstream license and exception texts, source URL and build recipe.
- Eigen 5.0.1: upstream licenses apply to individual components. Native packaging
  copies available Eigen license files; retain these and check the actual package
  before distributing a binary.
- Kotlin, Compose Desktop, the Kotlin MCP SDK, OpenJFX, JUnit and their transitive
  dependencies retain their upstream license and notice files in their artifacts.

This deliverable is a source release, not a reviewed binary distribution. Before
publishing an app bundle, review all bundled dependency notices and any applicable
source/relinking obligations. `scripts/bundle_native.py` preserves native dependency
notices and uses replaceable dynamic OpenCascade libraries.

Upstream sources:

- https://github.com/nlohmann/json/tree/v3.12.0
- https://github.com/gradle/gradle/tree/v8.13.0
- https://github.com/Open-Cascade-SAS/OCCT/tree/V8_0_1
- https://gitlab.com/libeigen/eigen/-/tree/5.0.1
