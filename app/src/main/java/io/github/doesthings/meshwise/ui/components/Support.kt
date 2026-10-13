package io.github.doesthings.meshwise.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.doesthings.meshwise.R

const val REPO_URL = "https://github.com/doesthings/Meshwise"
const val KOFI_URL = "https://ko-fi.com/doesthings"

/** Ko-fi's brand coral. */
val KofiColor = Color(0xFFFF5E5B)

/** Opens [url] in the browser. The app has no network access of its own; links leave the app. */
fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

/** "Buy me a coffee" card: Ko-fi plus a GitHub star, shown in Settings and About. */
@Composable
fun SupportCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = KofiColor.copy(alpha = 0.10f), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = KofiColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.support_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Button(
                onClick = { openUrl(context, KOFI_URL) },
                colors = ButtonDefaults.buttonColors(containerColor = KofiColor, contentColor = Color.White),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.Rounded.Coffee, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.buy_coffee), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { openUrl(context, REPO_URL) },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Icon(Icons.Rounded.Star, null, modifier = Modifier.size(18.dp), tint = Color(0xFFF5B400))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.star_github))
            }
        }
    }
}
