# Object and area colors

Bodies now have a saved base display color and up to 128 named color areas.
Use the object's context menu **Farbe und Bereiche …** to select a preset or enter
an exact `#RRGGBB` color. Select model faces and use **Flächenfarbe …** to color
those patches. The object dialog also lists areas for editing or removal; invalidated
surface assignments are marked **neu zuweisen**. Sidebar swatches show base colors.

Colors support undo/redo and self-contained `.kcad` save/load. New saves use format
11; existing formats 1–10 still load. Older app versions cannot read format 11.
Geometry, dimensions, manufacturing material, and STL/OBJ exports are unchanged.

## AI tools

- `cad_set_body_color`: `{ "bodyId": "…", "color": "#2266CC" }`.
  Omit `color` to restore the default; existing area overrides remain.
- `cad_get_color_regions`: `{ "bodyId": "…", "offset": 0, "limit": 100 }`.
  Returns patch IDs, local bounds, normals, current `sourceRevision`, appearance,
  and pagination (`total`, optional `nextOffset`).
- `cad_set_area_color`: stable `bodyId`, caller-chosen `areaId`, `name`, `color`,
  plus either `regionIds` and current `sourceRevision`, or `min`/`max` xyz bounds.
- `cad_remove_area_color`: `{ "bodyId": "…", "areaId": "lid" }`.

`cad_get_model.sceneBodies[].appearance` and `cad_get_feature.appearance` expose
base colors, area definitions, and `staleAreaIds`. The newest or most recently
updated area takes precedence in overlaps. Colors are opaque sRGB hex values.
Full tool schemas: [color-tools.schema.json](api/color-tools.schema.json).

## Working example

After creating a box with `cad_create_construction`, keep its returned `bodyId`.
Set its base color to blue with `cad_set_body_color`. Call `cad_get_color_regions`,
select the returned patch whose `normal.z` is 1, and send:

```json
{
  "bodyId": "<returned bodyId>",
  "areaId": "lid",
  "name": "Orange lid",
  "color": "#FF8800",
  "sourceRevision": "<returned sourceRevision>",
  "regionIds": ["<returned top patch ID>"]
}
```

For a spatial zone use `min` and `max`, each with numeric `x`, `y`, `z`, instead
of `sourceRevision` and `regionIds`. Coordinates are local millimeters before
feature position translation. Zones are evaluated on triangle centroids, so color
boundaries follow the existing mesh tessellation; they do not cut geometry or
create an exact paint boundary through a large triangle. Coplanar patch groups
can include disconnected faces in the same plane. Curves contain multiple faceted
patches; request subsequent pages as needed.

Surface assignments survive body translation and renaming. Geometry changes
invalidate them conservatively: stale assignments stop rendering and are reported
for reassignment. This avoids painting a different face after a Boolean or topology
change. Spatial zones instead re-evaluate against the new geometry.

The installed assistant and MCP adapter expose all four tools. A chat that caches
an older tool list can use the existing `scripts/mcp_client.py` adapter or reconnect.

