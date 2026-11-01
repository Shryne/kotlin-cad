# Kotlin CAD agent guidance

This repository contains the CAD application and a portable modelling skill.

For creating, continuing, revising, comparing or exporting a **model**, read
`skills/kotlin-cad-projects/SKILL.md` and follow its project workflow. Keep model
projects outside this source checkout. The initializer creates project-specific
`AGENTS.md` instructions, editable geometry, revision records and handoff notes.
Respect an existing project's instructions and inspect its live state before edits.

For **application development**, inspect the relevant code and tests; do not run
the modelling initializer. Build and test instructions are in README.md. Do not
launch GUI smoke sessions or replace an installed app merely to validate docs.

Before preparing source for publication, run `python3 scripts/check_secrets.py`
and `python3 scripts/test_publication_guard.py`. Never add user models, session
records, credentials, generated exports or local app bundles. Public demo images
require review and an entry in `docs/images/approved-assets.json`.

AI setup and helper usage are documented in `docs/ai-setup.md`.
