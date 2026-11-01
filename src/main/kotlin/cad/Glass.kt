package cad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tint and edge light over the live, blurred scene backdrop. */
val GlassBackdrop = Brush.linearGradient(listOf(Color(0xffe4ecf7),Color(0xfff0edf7),Color(0xffe8f1f6),Color(0xffe1eaf3)))
@Composable
fun Modifier.glassSurface(radius: Dp = 20.dp,elevation: Dp = 5.dp,strong: Boolean = false,backdrop: Boolean = false,captureInput: Boolean = true): Modifier {
    val shape=RoundedCornerShape(radius)
    val dark=AppAppearance.dark
    val tint=if(dark) Color(0xff303a49) else Color(0xfff6f9fd)
    val fill=Brush.verticalGradient(listOf(
        tint.copy(alpha=if(strong) .94f else if(backdrop) (if(dark) .38f else .86f) else .82f),
        tint.copy(alpha=if(strong) .86f else if(backdrop) (if(dark) .28f else .80f) else .68f)
    ))
    val rim=Brush.verticalGradient(listOf(Color.White.copy(alpha=if(dark) .16f else .74f),(if(dark) Color.White.copy(alpha=.05f) else Color(0xff8291a4).copy(alpha=.75f))))
    // A parent click target handles empty panel space without cancelling child clicks.
    val surface=if(captureInput) clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick={}) else this
    return surface.shadow(elevation,shape,clip=false,ambientColor=Color(0xff67809c).copy(alpha=.12f),spotColor=Color(0xff627c9b).copy(alpha=.15f))
        .clip(shape).background(fill).border(.8.dp,rim,shape)
}
