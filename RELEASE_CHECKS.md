# Source release preparation

Prepared 2026-11-01 for version 1.48.0.

## Included

Application and test source, native worker source, the vendored JSON header and
license, verified Gradle wrapper, build and test helpers, generic examples,
public API documentation, MIT license, third-party notices and a source-safety CI
workflow, portable modelling skill and generic agent instructions, an original SVG banner and two reviewed synthetic-demo viewport images.
The PNGs contain no metadata chunks and are pinned by SHA-256 in the image approval
manifest; unreviewed or changed images fail the publication guard. Upstream author/copyright notices are retained as required.

## Excluded

Private CAD projects and imported models; recovered work; old sample project
archives; private exports and screenshots; assistant/account state; credentials; caches;
local app bundles and binaries; development logs; personal session notes and
personal agent instructions. Generic test fixtures are generated from source at test time
and excluded from version control. No development Git history was imported.

## Validation

- Publication scanner passed across the complete release inventory and staged files.
- Targeted personal-name, private-project-name and personal-path checks passed.
- No private model assets, credential/config files, unexpected binaries or symlinks
  are included in the staged source.
- Official Gradle 8.13 wrapper SHA-256 verified; matching official launcher used;
  Gradle distribution SHA-256 pinned. The wrapper archive was also scanned.
- Full Kotlin source compilation, test suite and application distribution build
  passed in a separate temporary checkout with generated synthetic fixtures.
- Fixture-dependent migration/sketch tests passed after the final fixture update.
- Seven publication-guard regression tests passed, including detection of ignored
  but already-tracked private files, common fabricated token formats, home paths
  and unreviewed, changed or misplaced images.
- Native worker compiled from the release source using locally available pinned
  dependencies; all five native self-test fixtures and solver checks passed.
- Portable modelling helper tests passed: revisions preserve saved content and
  metadata, unknown formats are rejected, and app paths are configurable.
- No GUI smoke application was launched and no user project was modified.

This is a source deliverable. Native dependencies and compiled test artifacts used
for verification are not included. No binary app release or notarization is being
claimed. Pattern scanning is not a mathematical proof of absence of all secrets;
the allowlisted inventory and exclusion of private assets form the primary boundary.

## Publication status

The local release repository is initialized on `main`, with files staged for review.
It has no commits, author identity, remote URL or imported history. Nothing has been
uploaded or published. Commit and publish only this clean source copy, not a working
directory that also contains private projects or local runtime state.

`RELEASE_MANIFEST.sha256` records the distributable files (excluding the manifest
itself). The ZIP contains these files plus the manifest, without Git metadata.
