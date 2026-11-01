# AI modelling setup

The release includes the in-app assistant, a local MCP adapter, and the portable
[kotlin-cad-projects skill](../skills/kotlin-cad-projects/SKILL.md). The skill adds
persistent project organisation and revision records to an agent's modelling
workflow. It does not install the app, supply an AI account, or replace geometry
validation. The in-app assistant and external agent setup are separate.

## Connect to Kotlin CAD

Build and install Kotlin CAD using the README instructions, then open the app.
Configure your MCP-capable agent to launch the absolute path to `start-mcp.sh`
as a local stdio server. The script uses `~/Applications/Kotlin CAD.app` by default;
set `KOTLIN_CAD_APP` to a different app bundle if needed. Keep the connection local.
Discover tools using `tools/list`; the running app supplies the current schemas.

For an agent without a working connector, the bundled helper uses that same
installed app's adapter without starting another GUI. From the source root:

```sh
python3 skills/kotlin-cad-projects/scripts/cad.py tools
python3 skills/kotlin-cad-projects/scripts/cad.py call cad_get_model
```

Use `--app` before the command to select an app bundle or executable. Use
`--socket` only after identifying the intended running session if several are
open. Tool arguments can be supplied with `--arguments-file request.json`;
`--output result.json` creates a new output file without overwriting one.
A timed-out mutation may already have succeeded: inspect state before retrying.

## Make the skill available

Agents working in this checkout can follow the root `AGENTS.md` and read the
bundled skill directly. For reuse outside this checkout, install the whole
`skills/kotlin-cad-projects` folder in your agent's skill directory. For Codex,
the conventional user location is `${CODEX_HOME:-$HOME/.codex}/skills/`.
Preserve or compare any existing installation instead of overwriting it blindly.
The bundled `agents/openai.yaml` supplies discovery metadata. If your agent does
not support skills, explicitly point it to `SKILL.md` and the project instructions.

Example request: “Use kotlin-cad-projects to create a bracket project, with a
60 mm base, and record editable revisions as we refine it.”

## Create and resume a project

The helper requires Python 3 on macOS or Linux (it uses POSIX file locking).
Choose a folder outside the app source. From the source root:

```sh
python3 skills/kotlin-cad-projects/scripts/project.py init \
  "$HOME/Documents/CAD Models/Demo bracket" \
  --name "Demo bracket" --brief "A parametric bracket with a 60 mm base"
```

This creates an empty editable `current.kcad`, an initial immutable revision,
`AGENTS.md`, `PROJECT.md`, `STATUS.md`, `CHANGELOG.md`, `.cad-project.json`, and
`references/`, `previews/`, `exports/` folders. Nonempty destinations are refused.
No live document is opened or changed. Preserve unrelated unsaved geometry before
opening this model through the app or its tools.

After saving actual model changes to `current.kcad`:

```sh
python3 skills/kotlin-cad-projects/scripts/project.py checkpoint \
  "$HOME/Documents/CAD Models/Demo bracket" \
  --reason "Adjusted base length" --validation "Record the checks actually performed"
```

The helper preserves exact saved bytes, records parent revisions and hashes, and
skips unchanged content. It supports project schemas 10–12; it does not certify
geometry validity. Update STATUS.md with affected feature IDs, results and next
steps. New chats should read these files and inspect the live model. To restore
an old design, preserve current work, copy the old snapshot to `current.kcad`,
then create a new checkpoint; never rewrite an archived revision.

Project notes and snapshots can contain private designs and source paths. Keep
them outside the public source repository. This release contains only the generic
instructions and helpers, with no accounts, model projects or chat history.
