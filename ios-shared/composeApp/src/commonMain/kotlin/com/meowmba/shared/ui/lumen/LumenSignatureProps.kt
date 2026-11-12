package com.meowmba.shared.ui.lumen

import com.meowmba.shared.companion.AnimFrame
import com.meowmba.shared.companion.CustomAnimationSpec
import com.meowmba.shared.companion.MouthMode

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*

/** Shared prop/eye target: bounded normalized positions, including entrance and recovery. */
internal object SignatureTimeline {
    data class Pose(val point: Offset, val rotation: Float, val scale: Float, val activity: Float)
    fun sample(prop: SignatureProp, progress: Float): Pose {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val t = LumenScenes.ease((p - .12f) / .72f)
        val wave = sin(t * PI.toFloat() * 2f)
        val pulse = sin(t * PI.toFloat()).coerceAtLeast(0f)
        val point = when (prop) {
            SignatureProp.COMPASS -> Offset(.24f - .05f * pulse, .22f)
            SignatureProp.TUNING_FORK -> Offset(-.24f + wave * .008f, .16f)
            SignatureProp.TEACUP -> Offset(.23f - .11f * pulse, .24f - .12f * pulse)
            SignatureProp.FEATHER -> Offset(wave * .28f, .10f - pulse * .29f)
            SignatureProp.FRISBEE -> Offset(.34f - t * .68f, .21f - pulse * .34f)
            SignatureProp.ROPE -> Offset(wave * .10f, .22f)
            SignatureProp.SEED_PACKET -> Offset(-.20f + t * .34f, .22f - pulse * .03f)
            SignatureProp.WATERING_CAN -> Offset(.26f - pulse * .05f, .15f)
            SignatureProp.LANTERN -> Offset(wave * .29f, .10f)
            SignatureProp.SAFE -> Offset(.22f, .23f)
            SignatureProp.WHISTLE -> Offset(.16f - pulse * .10f, .18f - pulse * .08f)
            SignatureProp.AGILITY_HOOP -> Offset(.28f - t * .56f, .13f)
            SignatureProp.TELESCOPE -> Offset(.21f, -.08f)
            SignatureProp.SCROLL -> Offset(.04f, .24f)
            SignatureProp.UMBRELLA -> Offset(wave * .13f, -.24f)
            SignatureProp.SAILBOAT -> Offset(-.30f + t * .60f, .26f + wave * .02f)
            SignatureProp.CARROT -> Offset(wave * .20f, .24f - abs(sin(t * PI.toFloat() * 3f)) * .28f)
            SignatureProp.KITE -> Offset(wave * .28f, -.24f + wave * .06f)
            SignatureProp.MIXING_BOWL -> Offset(.04f, .24f)
            SignatureProp.CUPCAKE -> Offset(.21f - pulse * .13f, .24f - pulse * .07f)
            SignatureProp.PLANET -> Offset(cos(t * PI.toFloat() * 2f) * .28f, .08f + wave * .23f)
            SignatureProp.UFO -> Offset(.29f - t * .27f, -.22f + t * .44f)
            SignatureProp.WRENCH -> Offset(.24f, .18f)
            SignatureProp.CIRCUIT -> Offset(.02f, .24f)
            SignatureProp.BAMBOO -> Offset(.20f + wave * .03f, .16f)
            SignatureProp.TEAPOT -> Offset(.22f - pulse * .08f, .19f)
            SignatureProp.FRUIT_BASKET -> Offset(wave * .13f, .25f)
            SignatureProp.PRISM -> Offset(.23f - pulse * .08f, .17f)
            SignatureProp.STAPLER -> Offset(.18f, .24f - pulse * .04f)
            SignatureProp.PAPER_PLANE -> Offset(-.32f + t * .64f, .18f - pulse * .39f)
            SignatureProp.CRYSTAL_BALL -> Offset(.02f, .24f - pulse * .04f)
            SignatureProp.WAND -> Offset(wave * .22f, -.10f + cos(t * PI.toFloat() * 2f) * .15f)
        }
        val rotation = when (prop) {
            SignatureProp.COMPASS, SignatureProp.CARROT, SignatureProp.PLANET -> t * 360f
            SignatureProp.FEATHER, SignatureProp.KITE, SignatureProp.BAMBOO -> wave * 24f
            SignatureProp.WATERING_CAN, SignatureProp.TEAPOT -> -pulse * 35f
            SignatureProp.WRENCH, SignatureProp.WAND -> wave * 48f
            SignatureProp.TELESCOPE -> -20f + wave * 16f
            SignatureProp.PAPER_PLANE -> -18f + t * 30f
            SignatureProp.FRISBEE -> wave * 20f
            else -> wave * 5f
        }
        return Pose(point, rotation, .85f + .15f * pulse, t)
    }
}

/** Reused paths and a fixed primitive budget; no bitmaps, allocations of media, or new frame loop. */
internal class LumenSignaturePropPainter {
    private val path = Path()
    fun draw(scope: DrawScope, scene: SignatureScene, progress: Float, center: Offset, unit: Float) = with(scope) {
        val alpha = LumenScenes.envelope(progress)
        if (alpha <= .001f) return
        val pose = SignatureTimeline.sample(scene.prop, progress)
        translate(center.x, center.y) { scale(unit, unit, Offset.Zero) {
            translate(pose.point.x, pose.point.y) { rotate(pose.rotation, Offset.Zero) {
                scale(pose.scale, pose.scale, Offset.Zero) { prop(scene.prop, pose.activity, alpha) }
            } }
        } }
    }
    private fun DrawScope.prop(prop: SignatureProp, t: Float, alpha: Float) {
        val ink = Color(0xFFD6E7F4).copy(alpha = alpha)
        val gold = Color(0xFFFFCE76).copy(alpha = alpha)
        val green = Color(0xFF7BDCB1).copy(alpha = alpha)
        val coral = Color(0xFFEF929C).copy(alpha = alpha)
        val blue = Color(0xFF8AAAF5).copy(alpha = alpha)
        fun line(x: Float, y: Float, x2: Float, y2: Float, c: Color = ink, w: Float = .004f) =
            drawLine(c, Offset(x,y), Offset(x2,y2), w, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float, c: Color = ink, filled: Boolean = false) {
            if (filled) drawCircle(c,r,Offset(x,y)) else drawCircle(c,r,Offset(x,y),style=Stroke(.004f))
        }
        fun box(x: Float, y: Float, w: Float, h: Float, c: Color = blue) =
            drawRoundRect(c,Offset(x,y),Size(w,h),CornerRadius(.007f))
        fun poly(c: Color, vararg pts: Float) {
            path.reset();path.moveTo(pts[0],pts[1]);var i=2
            while(i<pts.size){path.lineTo(pts[i],pts[i+1]);i+=2};path.close();drawPath(path,c)
        }
        val pulse = sin(t * PI.toFloat())
        when(prop) {
            SignatureProp.COMPASS -> { circle(0f,0f,.055f,gold);circle(0f,0f,.046f,ink);poly(coral,0f,-.040f,.012f,0f,0f,.032f,-.012f,0f);circle(0f,0f,.005f,ink,true) }
            SignatureProp.TUNING_FORK -> { line(0f,.06f,0f,0f);line(-.025f,-.06f,-.025f,0f);line(.025f,-.06f,.025f,0f);line(-.025f,0f,.025f,0f);repeat(3){i->line(.04f+i*.012f,-.04f,.04f+i*.012f,-.01f,blue.copy(alpha=alpha*(1f-i*.25f)))} }
            SignatureProp.TEACUP -> { circle(.045f,0f,.024f,gold);box(-.04f,-.026f,.08f,.065f,coral);line(-.06f,.047f,.06f,.047f,gold);repeat(2){i->line(-.012f+i*.025f,-.043f,-.005f+i*.025f,-.066f,ink.copy(alpha=alpha*.6f))} }
            SignatureProp.FEATHER -> { drawOval(blue,Offset(-.022f,-.075f),Size(.044f,.12f));line(0f,-.07f,0f,.065f,gold);repeat(5){i->line(0f,-.055f+i*.018f,.020f,-.070f+i*.018f,ink,.002f)} }
            SignatureProp.FRISBEE -> { drawOval(coral,Offset(-.07f,-.022f),Size(.14f,.044f));drawOval(gold,Offset(-.05f,-.013f),Size(.10f,.026f),style=Stroke(.003f)) }
            SignatureProp.ROPE -> { repeat(12){i->val x=-.08f+i*.014f;circle(x,sin(i*.7f+t*7f)*.008f,.009f,gold)};circle(-.09f,0f,.022f,coral);circle(.09f,0f,.022f,coral) }
            SignatureProp.SEED_PACKET -> { box(-.04f,-.06f,.08f,.12f,gold);line(0f,.022f,0f,-.019f,green);drawOval(green,Offset(-.025f,-.028f),Size(.025f,.015f));drawOval(green,Offset(0f,-.018f),Size(.025f,.015f));repeat(3){i->circle(.047f+i*.015f,.06f-pulse*.03f,.006f,gold,true)} }
            SignatureProp.WATERING_CAN -> { circle(.03f,-.016f,.037f,green);box(-.04f,-.028f,.066f,.065f,green);poly(green,-.035f,-.01f,-.09f,-.03f,-.083f,-.042f,-.024f,-.024f);repeat(4){i->circle(-.09f-i*.008f,.005f+i*.015f,.0035f,blue,true)} }
            SignatureProp.LANTERN -> { circle(0f,-.059f,.021f,gold);box(-.035f,-.04f,.07f,.09f,blue.copy(alpha=alpha*.45f));circle(0f,.008f,.018f,gold,true);line(-.04f,-.04f,.04f,-.04f);line(-.04f,.05f,.04f,.05f);line(-.033f,-.04f,-.033f,.05f);line(.033f,-.04f,.033f,.05f) }
            SignatureProp.SAFE -> { box(-.065f,-.055f,.13f,.11f,blue);drawRect(ink,Offset(-.052f,-.043f),Size(.104f,.086f),style=Stroke(.003f));circle(.01f,0f,.025f,gold);val a=t*16f;line(.01f,0f,.01f+cos(a)*.021f,sin(a)*.021f,gold);line(-.044f,-.018f,-.044f,.018f,gold) }
            SignatureProp.WHISTLE -> { circle(-.015f,.005f,.028f,gold,true);box(-.01f,-.01f,.075f,.02f,gold);box(.023f,-.008f,.015f,.008f,blue);line(-.04f,.02f,-.065f,.055f,coral) }
            SignatureProp.AGILITY_HOOP -> { drawCircle(coral,.075f,Offset.Zero,style=Stroke(.009f));line(-.052f,.05f,-.065f,.10f,gold);line(.052f,.05f,.065f,.10f,gold) }
            SignatureProp.TELESCOPE -> { box(-.07f,-.027f,.14f,.045f,blue);box(.044f,-.034f,.025f,.058f,gold);line(0f,.01f,0f,.074f);line(0f,.025f,-.045f,.075f);line(0f,.025f,.045f,.075f);circle(.065f,-.004f,.014f,ink,true) }
            SignatureProp.SCROLL -> { val w=.04f+.10f*pulse;box(-w/2,-.045f,w,.09f,gold);box(-w/2-.006f,-.057f,.014f,.114f,coral);box(w/2-.008f,-.057f,.014f,.114f,coral);repeat(3){i->line(-w*.3f,-.02f+i*.02f,w*.3f,-.02f+i*.02f,blue,.002f)} }
            SignatureProp.UMBRELLA -> { drawArc(blue,180f,180f,true,Offset(-.09f,-.055f),Size(.18f,.11f));line(0f,-.053f,0f,.078f,gold);drawArc(gold,0f,180f,false,Offset(-.025f,.063f),Size(.025f,.03f),style=Stroke(.004f));line(-.05f,-.037f,-.03f,0f,ink,.002f);line(.05f,-.037f,.03f,0f,ink,.002f) }
            SignatureProp.SAILBOAT -> { poly(coral,-.075f,.027f,.075f,.027f,.045f,.057f,-.044f,.057f);line(0f,-.08f,0f,.03f,gold);poly(ink,.005f,-.071f,.061f,.015f,.005f,.015f);poly(blue,-.007f,-.053f,-.046f,.015f,-.007f,.015f);line(-.095f,.07f,.095f,.07f,blue) }
            SignatureProp.CARROT -> { poly(gold,-.024f,-.035f,.024f,-.035f,0f,.08f);line(0f,-.035f,-.026f,-.073f,green,.01f);line(0f,-.035f,.022f,-.076f,green,.01f);line(-.016f,-.005f,.012f,.003f,coral,.003f) }
            SignatureProp.KITE -> { poly(coral,0f,-.085f,.057f,-.025f,0f,.042f,-.057f,-.025f);line(0f,-.085f,0f,.042f,gold);line(-.057f,-.025f,.057f,-.025f,gold);line(0f,.042f,-.015f,.10f);poly(blue,-.017f,.06f,.008f,.066f,-.022f,.082f) }
            SignatureProp.MIXING_BOWL -> { line(sin(t*18f)*.03f,-.05f,0f,.028f,gold,.006f);drawArc(blue,0f,180f,true,Offset(-.07f,-.02f),Size(.14f,.11f));drawOval(ink,Offset(-.07f,-.03f),Size(.14f,.04f),style=Stroke(.004f)) }
            SignatureProp.CUPCAKE -> { poly(gold,-.045f,0f,.045f,0f,.030f,.054f,-.03f,.054f);circle(-.025f,-.013f,.025f,coral,true);circle(.025f,-.013f,.025f,coral,true);circle(0f,-.035f,.028f,coral,true);circle(0f,-.060f,.011f,blue,true);repeat(4){i->line(-.026f+i*.017f,.01f,-.020f+i*.013f,.042f,coral,.002f)} }
            SignatureProp.PLANET -> { circle(0f,0f,.047f,blue,true);drawOval(gold,Offset(-.082f,-.016f),Size(.164f,.032f),style=Stroke(.006f));circle(-.01f,-.022f,.011f,ink.copy(alpha=alpha*.5f),true) }
            SignatureProp.UFO -> { drawArc(blue,180f,180f,true,Offset(-.037f,-.05f),Size(.074f,.075f));drawOval(ink,Offset(-.08f,-.005f),Size(.16f,.04f));repeat(4){i->circle(-.045f+i*.03f,.014f,.005f,gold,true)};poly(green.copy(alpha=alpha*.25f),-.015f,.037f,.015f,.037f,.055f,.105f,-.055f,.105f) }
            SignatureProp.WRENCH -> { line(-.045f,.060f,.035f,-.036f,blue,.018f);drawArc(gold,25f,285f,false,Offset(.01f,-.072f),Size(.059f,.059f),style=Stroke(.012f));circle(-.046f,.061f,.016f,gold) }
            SignatureProp.CIRCUIT -> { box(-.08f,-.045f,.16f,.09f,green.copy(alpha=alpha*.65f));box(-.025f,-.025f,.05f,.05f,blue);repeat(4){i->val y=-.024f+i*.016f;line(-.062f,y,-.03f,y,gold,.002f);line(.03f,y,.061f,y,gold,.002f)};circle(-.06f+t*.12f,.035f,.006f,gold,true) }
            SignatureProp.BAMBOO -> { line(0f,-.09f,0f,.09f,green,.018f);repeat(4){i->line(-.01f,-.06f+i*.04f,.01f,-.06f+i*.04f,gold,.003f)};drawOval(green,Offset(.01f,-.05f),Size(.05f,.018f));drawOval(green,Offset(-.06f,.00f),Size(.05f,.018f)) }
            SignatureProp.TEAPOT -> { circle(.05f,0f,.03f,green);drawOval(blue,Offset(-.05f,-.035f),Size(.095f,.075f));poly(blue,-.03f,-.012f,-.09f,-.038f,-.075f,.01f,-.039f,.024f);box(-.03f,-.043f,.057f,.011f,gold);circle(0f,-.049f,.009f,gold,true) }
            SignatureProp.FRUIT_BASKET -> { drawArc(gold,180f,180f,false,Offset(-.052f,-.064f),Size(.104f,.11f),style=Stroke(.006f));circle(-.027f,-.013f,.025f,coral,true);circle(.023f,-.013f,.029f,green,true);poly(gold,-.07f,.005f,.07f,.005f,.048f,.06f,-.048f,.06f);repeat(4){i->line(-.04f+i*.025f,.014f,-.03f+i*.019f,.052f,coral,.002f)} }
            SignatureProp.PRISM -> { poly(blue.copy(alpha=alpha*.65f),0f,-.064f,.058f,.043f,-.058f,.043f);line(-.10f,.00f,-.025f,.00f,ink);listOf(coral,gold,green,blue).forEachIndexed{i,c->line(.025f,0f,.11f,-.045f+i*.027f,c,.004f)} }
            SignatureProp.STAPLER -> { box(-.07f,.023f,.14f,.018f,blue);rotate(-12f*(1f-pulse),Offset(-.065f,.02f)){box(-.065f,-.014f,.132f,.035f,coral)};line(-.085f,.05f,.085f,.05f,ink) }
            SignatureProp.PAPER_PLANE -> { poly(ink,-.085f,-.04f,.09f,0f,-.073f,.05f,-.037f,.003f);line(-.037f,.003f,.09f,0f,blue,.002f);line(-.037f,.003f,-.073f,.05f,blue,.002f) }
            SignatureProp.CRYSTAL_BALL -> { circle(0f,-.012f,.055f,blue.copy(alpha=alpha*.5f),true);circle(0f,-.012f,.055f,ink);circle(-.017f,-.035f,.012f,ink.copy(alpha=alpha*.7f),true);box(-.055f,.04f,.11f,.018f,gold);val a=t*12f;circle(cos(a)*.03f,-.012f+sin(a)*.028f,.009f,coral,true) }
            SignatureProp.WAND -> { line(-.052f,.06f,.035f,-.038f,gold,.007f);poly(ink,.04f,-.07f,.046f,-.050f,.068f,-.047f,.05f,-.036f,.056f,-.014f,.038f,-.029f,.019f,-.014f,.024f,-.036f,.008f,-.049f,.030f,-.05f);repeat(4){i->circle(-.075f+i*.038f,.045f+sin(t*12f+i)*.018f,.004f,blue,true)} }
        }
    }
}
