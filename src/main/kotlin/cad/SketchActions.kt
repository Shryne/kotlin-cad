package cad

import java.util.UUID

internal fun DocumentState.renameSketch(id: String,name: String): DocumentState {
    require(sketches.any { it.id==id }) { "Skizze existiert nicht." }
    val trimmed=name.trim()
    require(trimmed.isNotEmpty() && trimmed.length<=128) { "Der Name muss 1 bis 128 Zeichen enthalten." }
    return copy(sketches=sketches.map { if(it.id==id) it.copy(name=trimmed) else it })
}

/** Copy the complete profile (including reflected contours), never its extrusion. */
internal fun DocumentState.duplicateSketch(id: String): DocumentState {
    val source=sketches.firstOrNull { it.id==id } ?: throw IllegalArgumentException("Skizze existiert nicht.")
    var suffix=" Kopie";var number=2
    fun name()=source.name.take(128-suffix.length)+suffix
    while(sketches.any { it.name==name() }) { suffix=" Kopie ${number++}" }
    val duplicate=source.copy(id=UUID.randomUUID().toString(),name=name(),depth=null,visible=true)
    return copy(sketches=sketches+duplicate)
}
