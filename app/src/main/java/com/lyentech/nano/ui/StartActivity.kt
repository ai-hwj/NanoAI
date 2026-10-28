package com.lyentech.nano.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyentech.nano.global.AppTheme
import com.lyentech.nano.global.ScopedActivity
import com.lyentech.nano.ui.chat.ChatActivity
import com.lyentech.nano.ui.litellm.LocalModelLabActivity

/**
 * @author by jason-何伟杰，2026/9/29
 * des:首页
 */
class StartActivity : ScopedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                StartScreen(KINDS) { entry -> startActivity(Intent(this, entry.activity)) }
            }
        }
    }

    private companion object {
        val KINDS = listOf(
            KindEntry(
                "Agent AI",
                "专业 Agent 应用：项目归类、会话历史、图片输入与推理过程显示",
                ChatActivity::class.java
            ),
            KindEntry(
                "LiteRT-LM chat",
                "On-device LiteRT-LM model with tool calling; downloads the model on first run",
                RtLlmChatActivity::class.java
            ),
            KindEntry(
                "本地模型测试",
                "只加载导入目录里的 .litertlm（不下载）：可选后端/线程/thinking、改提示词、对比加载耗时",
                LocalModelLabActivity::class.java
            ),
            KindEntry(
                "API chat",
                "Cloud model over HTTP with an API key; streams the reply",
                ApiChatActivity::class.java
            ),
        )
    }

    private class KindEntry(
        val title: String,
        val subTitle: String,
        val activity: Class<out Activity>
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun StartScreen(kinds: List<KindEntry>, onOpen: (KindEntry) -> Unit) {
        Scaffold(topBar = { TopAppBar(title = { Text("ADK Android examples") }) }) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(kinds) { example -> CardUi(example) { onOpen(example) } }
            }
        }
    }

    @Composable
    private fun CardUi(example: KindEntry, onClick: () -> Unit) {
        ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(example.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        example.subTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }}
    }