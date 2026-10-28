package com.lyentech.nano.ui.litellm


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyentech.nano.http.LiteRtLmModelStore
import kotlin.math.roundToInt

@Composable
fun ModelSetupBar(progress: Float?, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        if (progress == null) {
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Download model (${LiteRtLmModelStore.DOWNLOAD_SIZE_LABEL})")
            }
        } else {
            Text(
                "Downloading model… ${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}