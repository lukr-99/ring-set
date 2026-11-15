package com.krejci.halo.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krejci.halo.BuildConfig
import com.krejci.halo.ui.AuthorLinks
import com.krejci.halo.ui.Coral
import com.krejci.halo.ui.components.SectionLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The About screen: what Halo is, who made it, how to send feedback, what it keeps, and credits. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = scheme.onBackground)
        }
        Text("About", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
    }

    Spacer(Modifier.height(8.dp))
    AboutHero()

    SectionLabel("The author")
    AuthorCard()

    SectionLabel("The app")
    FactsCard(
        listOf(
            "Version" to BuildConfig.VERSION_NAME,
            "Platform" to "Android 8.0 or newer",
            "Works with" to "Colmi R0x-family rings (QRing)",
            "Licence" to "PolyForm Noncommercial 1.0.0",
        ),
    )

    SectionLabel("What it keeps")
    InfoCard(
        "No account and no cloud. Your readings (heart rate, steps, SpO₂, stress, HRV, sleep), " +
            "workouts, known rings and your profile are stored on this phone only. You can export them " +
            "as CSV or a full JSON backup, and you decide where that file goes.",
        "The app asks for internet access for one thing: the optional update check, which reads " +
            "the public release list on GitHub. It sends none of your data.",
    )

    SectionLabel("Not a medical device")
    InfoCard(
        "Halo shows what the ring measured and explains what the numbers usually mean. It is general " +
            "information, not medical advice, and it cannot diagnose anything.",
    )

    SectionLabel("Credits")
    CreditsCard(
        listOf(
            "tahnok/colmi_r02_client" to "Ring protocol reverse-engineering",
            "Gadgetbridge" to "Ring protocol reverse-engineering",
            "QRing / Oudmon SDK" to "Real-time heart rate",
            "Jetpack Compose, Room, Kotlin coroutines" to "Built with these Android libraries",
        ),
        "An independent hobby project, not affiliated with Colmi or QRing.",
    )
    Spacer(Modifier.height(8.dp))
}

/** A slowly turning halo: the app's icon motif. Holds still when the system turns animations off. */
@Composable
private fun AboutHero() {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val turn by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    val angle = if (animate) turn else 300f
    val primary = scheme.primary

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(150.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension * 0.34f
            drawCircle(
                Brush.radialGradient(listOf(primary.copy(alpha = 0.32f), Color.Transparent), center = c, radius = size.minDimension / 2),
                radius = size.minDimension / 2, center = c,
            )
            val sw = 7.dp.toPx()
            drawCircle(
                Brush.sweepGradient(listOf(primary.copy(alpha = 0.15f), primary, primary.copy(alpha = 0.15f)), center = c),
                radius = r, center = c, style = Stroke(sw, cap = StrokeCap.Round),
            )
            val rad = angle * PI.toFloat() / 180f
            val node = Offset(c.x + r * cos(rad), c.y + r * sin(rad))
            drawCircle(primary.copy(alpha = 0.35f), radius = sw * 1.9f, center = node)
            drawCircle(primary, radius = sw * 0.9f, center = node)
            // Heartbeat pulse across the middle, coral like the app icon.
            val w = r * 1.1f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - w, c.y)
                lineTo(c.x - w * 0.4f, c.y)
                lineTo(c.x - w * 0.2f, c.y - r * 0.45f)
                lineTo(c.x + w * 0.1f, c.y + r * 0.4f)
                lineTo(c.x + w * 0.3f, c.y)
                lineTo(c.x + w, c.y)
            }
            drawPath(path, Coral, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("Halo", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onBackground)
        Text("Your ring. Your data.", fontSize = 14.sp, color = scheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "A private, offline companion for Colmi R0x smart rings. It talks to the ring over Bluetooth " +
                "and keeps everything on your phone.",
            fontSize = 13.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun AuthorCard() {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.22f), scheme.surface, Coral.copy(alpha = 0.12f))))
            .border(1.5.dp, Brush.linearGradient(listOf(scheme.primary, scheme.primary.copy(alpha = 0.2f), Coral.copy(alpha = 0.7f))), shape),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Made by ${AuthorLinks.NAME}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = scheme.onSurface)
            Text(
                "Feedback is welcome: what works, what does not, what is missing.",
                fontSize = 14.sp, color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            LinkButton("Instagram", "@${AuthorLinks.INSTAGRAM_HANDLE}", AuthorLinks.INSTAGRAM_URL)
            LinkButton("GitHub", AuthorLinks.GITHUB_HANDLE, AuthorLinks.GITHUB_URL)
            LinkButton("Report an issue", "${AuthorLinks.GITHUB_HANDLE}/${AuthorLinks.REPO_NAME}", AuthorLinks.ISSUES_URL)
        }
    }
}

@Composable
private fun LinkButton(title: String, detail: String, url: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(scheme.surface.copy(alpha = 0.7f))
            .border(1.dp, scheme.outline, RoundedCornerShape(14.dp))
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(detail, fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Opens in your browser", tint = scheme.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun FactsCard(rows: List<Pair<String, String>>) {
    PlainCard {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(vararg paragraphs: String) {
    PlainCard {
        paragraphs.forEach { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun CreditsCard(rows: List<Pair<String, String>>, footer: String) {
    PlainCard {
        rows.forEach { (name, what) ->
            Column {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(what, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(footer, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlainCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}
