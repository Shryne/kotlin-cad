# Compact modular assemblies

This update prioritizes transactional edits and reusable geometry, with bounded,
cancellable inspection tools. Exact schemas are in
[api/assembly-tools.schema.json](api/assembly-tools.schema.json).

## Batch changes and incremental evaluation

`cad_apply_batch` accepts 1–256 ordered operations: `set_parameter`,
`delete_parameter`, `put_feature`, `delete_feature`, `set_position`,
`set_body_color`, `set_area_color`, and `remove_area_color`.
`put_feature` takes a complete canonical feature object (read existing definitions
with `cad_get_feature`); reuse the same ID for edits. Existing IDs cannot change
type. Definitions and parameters are staged, then the final dependency graph is
validated once. Colors require a body already staged; area selectors resolve on
final geometry and apply in their listed order. A successful batch creates one
history entry; earlier undo history is retained. Failed or cancelled candidates
never replace the current document.

The result is a job ID. Poll `cad_get_assembly_job` until `complete`, `failed`,
`cancelled`, or `stale`; `running` and `committing` are intermediate states.
`cad_cancel_assembly_job` is accepted while running. Each background job owns a
private native worker, so cancelling it cannot kill the shared viewer worker.
Document edits during a job cause its candidate/result to become stale instead of
overwriting newer work. Completed read-only reports also become stale when the
document changes. Cancellation cannot roll back a completed/committing batch;
use ordinary undo for an already committed transaction.

Geometry is cached by resolved shape content, independently of body name, ID,
placement, and appearance. Identical native parts share the evaluated mesh.
Unchanged geometry and constraint results are reused across documents; only
referenced resolved parameter values enter sketch evaluation keys. Pure color
changes reuse the evaluated graph. Native mesh caching is bounded to 512 entries
and 64 MiB of mesh payload (plus key/collection overhead), and sketch/document
caches are bounded separately. Batch results include geometry build/cache-hit
counts; these include inexpensive instance/mirror transformations as well as
native geometry builds. No claim of constant-time rebuilds for arbitrarily large
assemblies is made.

## Linked parts

`cad_create_instance` / `cad_update_instance` create or edit `instance` features.
They return the same asynchronous batch-job response. Each instance stores:

- Stable ID, name, and `sourceFeatureId`.
- Position expressions `x`, `y`, `z`.
- Angle expressions `rotationX`, `rotationY`, `rotationZ`.
- `inheritColors` (default true).

The source is a generated body; it remains the first physical part, not a hidden
abstract definition. Nested instances are supported; cycles and deletion of a
referenced source are rejected. Instances use source-local geometry: the source's
placement translation is excluded. Rotation is about the source-local origin,
in X/Y/Z order, followed by instance translation. Source shape changes propagate
through the dependency graph. Source placement changes do not relocate instances.

Colors inherit by triangle order through rotation. An explicit instance base color
replaces inherited colors; local instance areas override that base. Resetting the
instance base color restores inheritance. Source surface-area invalidation still
applies after topology changes. Independent spatial areas use instance-local
coordinates after rotation but before its placement translation.

In the UI, right-click a generated object → **Verknüpfte Instanz …**. Existing
instances also offer **Instanz bearbeiten …**. Positions and angles accept named
parameter expressions. Progress and cancellation appear in the header.

## Assembly checks

`cad_check_assembly_path` checks a specified sequence of translation offsets relative
to saved placement. Insertion typically ends at zero; removal starts at zero.
It reports the first sampled overlap. If no sample overlaps, continuous clearance
is certified only where endpoint distances exceed a conservative displacement bound;
otherwise the result is **inconclusive**, not “clear”. Reduce `stepMm` to improve
resolution. Limits: 201 poses and 256 body-pair tests per path, 16 obstacles,
translations only. Rotation, elastic insertion and deformation are not simulated.

`cad_check_retention` probes six straight world-axis escape directions. It reports
blocked directions as geometric evidence, **not proof of mechanical retention**.
It cannot establish strength, friction, snap-fit flexibility, off-axis or rotational
escape, or retention under load. These remain engineering/physical validation tasks.

`cad_check_manufacturing` samples inward mesh-normal thickness and exact/faceted
body-to-body clearances. Configurable thresholds: `minWallMm`, `minFeatureMm`,
`minClearanceMm`, and `samplesPerBody`. Thin-section and small-feature findings
include positions and geometry revisions. The latter are fragility candidates,
not stress predictions. Sampling can miss narrow or oblique features, and unresolved
rays are counted. Intentional joint contact/overlap must be interpreted in context.

Use `cad_highlight_manufacturing` to mark thickness findings and nearest-point
clearance locations in red. The object context menu also starts manufacturing
screening; the information panel offers **Warnstellen anzeigen** for the report.
No manufacturing check changes geometry, colors, or history.

## Documentation and inspection

- `cad_get_assembly` returns counts grouped by linked source definition, visible
  counts, body IDs and instance definitions. The source counts as one part, even
  if hidden. Independent but identical bodies remain separate definitions.
- `cad_preview_exploded` displays a temporary radial exploded view with colors.
  It returns offsets and works with `cad_get_preview` for a saved illustration.
  Picking is disabled while exploded. It is an illustration, not an assembly-path
  plan. `cad_clear_assembly_preview` restores the view/camera and clears warning
  markers. The information panel offers **Explosionsansicht / Normale Ansicht**.
- `cad_compare_assembly` reads a saved `.kcad` revision without opening/overwriting
  it and reports added/removed IDs, changed definitions/colors/parameters, geometry
  fingerprints and bounds changes. Geometry fingerprints refer to evaluated meshes;
  they are not a certified surface-deviation comparison.

New saves use project format 12; formats 1–11 continue to load. Existing feature
IDs and explicit colors persist. Undo history remains session-local, as before;
saving/opening a project retains definitions, not the in-memory undo stack.

## Minimal batch example

Send this to `cad_apply_batch` in a new project, then poll the returned job:

```json
{
  "operations": [
    {"op":"set_parameter","name":"width","expression":"20 mm"},
    {"op":"put_feature","feature":{
      "type":"nativeShape","id":"plate","name":"Shared plate","singleSolid":true,
      "shape":{"type":"box","x":{"expression":"width"},"y":10,"z":2}
    }},
    {"op":"put_feature","feature":{
      "type":"instance","id":"plate-2","name":"Second plate",
      "sourceFeatureId":"plate","x":"35 mm","rotationZ":"90 deg"
    }},
    {"op":"set_body_color","bodyId":"plate","color":"#4477DD"}
  ]
}
```

Both parts inherit blue. Updating `width` resizes the source and the rotated instance;
explicitly coloring `plate-2` overrides its inherited appearance. A later batch can
update multiple dimensions and colors in one undoable change.

