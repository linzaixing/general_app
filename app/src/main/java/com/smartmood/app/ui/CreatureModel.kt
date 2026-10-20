package com.smartmood.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.filament.MaterialInstance
import com.smartmood.app.domain.Creature
import com.smartmood.app.domain.Eyes
import com.smartmood.app.domain.Shape
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale

import io.github.sceneview.NodeScope

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CreatureMaterials(
    val body: MaterialInstance,
    val accent: MaterialInstance,
    val ink: MaterialInstance,
    val blush: MaterialInstance,
)

@Composable
fun rememberCreatureMaterials(creature: Creature, loader: MaterialLoader): CreatureMaterials =
    remember(creature.id, loader) {
        CreatureMaterials(
            body = loader.createColorInstance(androidx.compose.ui.graphics.Color(creature.body), metallic = 0f, roughness = 0.55f),
            accent = loader.createColorInstance(androidx.compose.ui.graphics.Color(creature.accent), metallic = 0f, roughness = 0.6f),
            ink = loader.createColorInstance(androidx.compose.ui.graphics.Color(0xFF3B2F2F), metallic = 0f, roughness = 0.3f),
            blush = loader.createColorInstance(androidx.compose.ui.graphics.Color(0xFFF08A7E), metallic = 0f, roughness = 0.8f),
        )
    }

private const val R = 0.06f

private data class BodySize(val sx: Float, val sy: Float, val sz: Float)

private fun bodySize(shape: Shape) = when (shape) {
    Shape.ROUND -> BodySize(1.0f, 0.92f, 1.0f)
    Shape.TALL -> BodySize(0.8f, 1.3f, 0.8f)
    Shape.FLAT -> BodySize(1.25f, 0.68f, 1.1f)
    Shape.DROP -> BodySize(0.9f, 1.1f, 0.9f)
    Shape.SPIKY -> BodySize(1.0f, 0.9f, 1.0f)
    Shape.PETAL -> BodySize(0.95f, 0.9f, 0.95f)
}

/** 椭球体表面上正前方（+Z）的点，用来贴眼睛和脸颊。 */
private fun frontPoint(size: BodySize, cy: Float, x: Float, y: Float): Position {
    val rx = R * size.sx
    val ry = R * size.sy
    val rz = R * size.sz
    val nx = x / rx
    val ny = (y - cy) / ry
    val inside = 1f - nx * nx - ny * ny
    val z = rz * sqrt(inside.coerceAtLeast(0.02f))
    return Position(x, y, z)
}

@Composable
fun NodeScope.CreatureModel(creature: Creature, m: CreatureMaterials) {
    val size = bodySize(creature.shape)
    val cy = R * size.sy

    SphereNode(
        radius = R,
        materialInstance = m.body,
        position = Position(y = cy),
        scale = Scale(size.sx, size.sy, size.sz),
    )

    when (creature.shape) {
        Shape.ROUND -> {
            for (side in listOf(-1f, 1f)) {
                SphereNode(
                    radius = R * 0.28f,
                    materialInstance = m.body,
                    position = Position(x = side * R * 0.62f, y = cy + R * 0.82f),
                )
            }
        }
        Shape.DROP -> {
            ConeNode(
                radius = R * 0.45f,
                height = R * 0.9f,
                materialInstance = m.body,
                position = Position(y = cy + R * size.sy + R * 0.25f),
            )
        }
        Shape.SPIKY -> {
            for (i in 0 until 5) {
                val a = Math.toRadians(-60.0 + 30.0 * i)
                ConeNode(
                    radius = R * 0.18f,
                    height = R * 0.55f,
                    materialInstance = m.accent,
                    position = Position(x = (R * 0.85f * sin(a)).toFloat(), y = cy + R * size.sy * 0.85f * cos(a).toFloat() + R * 0.18f),
                    rotation = Rotation(z = (-Math.toDegrees(a)).toFloat()),
                )
            }
        }
        Shape.PETAL -> {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i)
                SphereNode(
                    radius = R * 0.3f,
                    materialInstance = m.accent,
                    position = Position(x = (R * 1.05f * cos(a)).toFloat(), y = cy + (R * 0.9f * sin(a)).toFloat(), z = -R * 0.25f),
                )
            }
        }
        else -> Unit
    }

    val eyeX = R * size.sx * 0.38f
    val eyeY = cy + R * size.sy * 0.12f
    val eyeScale = when (creature.eyes) {
        Eyes.OPEN -> Scale(1f, 1.35f, 0.7f)
        Eyes.SLEEPY -> Scale(1.5f, 0.3f, 0.6f)
        Eyes.HAPPY -> Scale(1.4f, 0.55f, 0.6f)
    }
    for (side in listOf(-1f, 1f)) {
        SphereNode(
            radius = R * 0.13f,
            materialInstance = m.ink,
            position = frontPoint(size, cy, side * eyeX, eyeY),
            scale = eyeScale,
        )
        SphereNode(
            radius = R * 0.15f,
            materialInstance = m.blush,
            position = frontPoint(size, cy, side * eyeX * 1.65f, eyeY - R * size.sy * 0.2f),
            scale = Scale(1.3f, 0.7f, 0.4f),
        )
    }
    SphereNode(
        radius = R * 0.08f,
        materialInstance = m.ink,
        position = frontPoint(size, cy, 0f, eyeY - R * size.sy * 0.2f),
        scale = Scale(1.6f, 0.8f, 0.6f),
    )
}
