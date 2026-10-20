package com.nikosm.voiceassistant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The About section at the very bottom of the Settings list.
 *
 * Shows the app's name and version, the project page and releases links, and the licence.
 * URLs are clickable when a browser is available; otherwise they are shown as plain text so
 * the address is never hidden.
 *
 * It sits after the last category, which puts it at the bottom of the scrolling list.
 */
@Composable
internal fun SettingsAboutSection() {
    val context = LocalContext.current
    val version = android.os.Build.VERSION.RELEASE
    val appVersion = BuildConfig.VERSION_NAME

    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        Text(
            text = "About",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.05f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "VoiceAssistant",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Version $appVersion",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Android $version · GPL-3.0-only · no telemetry",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "GitHub",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AboutLink(PROJECT_URL, context)

                Text(
                    text = "Releases",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AboutLink(projectReleasesUrl(), context)
            }
        }
    }
}

/**
 * A URL in the section: clickable when a browser is available, plain text otherwise.
 * The address is shown either way, so the link destination is never hidden.
 */
@Composable
private fun AboutLink(url: String, context: android.content.Context) {
    val canOpen = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        0
    ) != null
    Text(
        text = url,
        fontSize = 13.sp,
        color = if (canOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (canOpen) Modifier.clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } else Modifier,
    )
}
