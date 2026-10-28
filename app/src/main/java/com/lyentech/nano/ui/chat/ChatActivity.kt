package com.lyentech.nano.ui.chat

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyentech.nano.data.model.Project
import com.lyentech.nano.global.AppTheme
import com.lyentech.nano.global.ScopedActivity
import com.lyentech.nano.ui.project.ProjectListScreen
import java.io.File
import kotlinx.coroutines.launch

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 专业 Agent 聊天页。承载抽屉导航、项目管理与完整的对话交互。
 *
 * 页面只做「把状态接到组件、把事件转成 ViewModel 调用」，业务逻辑都在 [ChatViewModel] 里。
 */
class ChatActivity : ScopedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: ChatViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            AppTheme(mode = themeMode) {
                ChatRoute(vm)
            }
        }
    }
}

/** 页面内导航：聊天主页 <-> 项目列表。两个页面共享同一个 ViewModel。 */
private enum class Screen { CHAT, PROJECTS }

@Composable
private fun ChatRoute(vm: ChatViewModel) {
    var screen by remember { mutableStateOf(Screen.CHAT) }

    when (screen) {
        Screen.CHAT -> ChatHome(vm = vm, onOpenProjects = { screen = Screen.PROJECTS })

        Screen.PROJECTS -> {
            val projects by vm.projects.collectAsState()
            ProjectListScreen(
                projects = projects,
                onBack = { screen = Screen.CHAT },
                onCreate = { name, desc -> vm.createProject(name, desc) },
                onDelete = { project -> vm.deleteProject(project.id) },
                onOpen = { project ->
                    // 进入项目：新建一个归属于该项目的会话，符合"项目内新建聊天"的心智。
                    vm.newConversation(projectId = project.id)
                    screen = Screen.CHAT
                },
            )
        }
    }
}

@Composable
private fun ChatHome(vm: ChatViewModel, onOpenProjects: () -> Unit) {
    val items by vm.timeline.collectAsState()
    val sending by vm.sending.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val projects by vm.projects.collectAsState()
    val currentId by vm.currentConversationId.collectAsState()
    val projectName by vm.currentProjectName.collectAsState()
    val effort by vm.reasoningEffort.collectAsState()
    val themeMode by vm.themeMode.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var pendingImages by remember { mutableStateOf<List<PendingImage>>(emptyList()) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var assignDialogOpen by remember { mutableStateOf(false) }
    var createProjectDialogOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // 相册选择：用系统 Photo Picker，无需申请读存储权限。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) pendingImages = pendingImages + uris.map { PendingImage(it) }
    }

    // 拍照：相机把照片写进我们提供的 uri，所以要先在自己的 FileProvider 下建好目标文件。
    // 相机被取消时不会回调，目标文件会残留，属于可接受的少量垃圾，下次清理即可。
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val target = cameraTarget
        cameraTarget = null
        if (success && target != null) pendingImages = pendingImages + PendingImage(target)
    }

    val title = conversations.firstOrNull { it.id == currentId }?.title ?: "新对话"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                ChatDrawerContent(
                    conversations = conversations,
                    projects = projects,
                    themeMode = themeMode,
                    currentConversationId = currentId,
                    onSelectConversation = { conversation ->
                        vm.openConversation(conversation.id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteConversation = { conversation -> vm.deleteConversation(conversation.id) },
                    onAssignToProject = { conversation, projectId ->
                        vm.assignConversationToProject(conversation.id, projectId)
                    },
                    onNewChat = {
                        vm.newConversation()
                        scope.launch { drawerState.close() }
                    },
                    onThemeModeChange = { mode -> vm.setThemeMode(mode) },
                    onOpenProjects = {
                        scope.launch { drawerState.close() }
                        onOpenProjects()
                    },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            ChatScreenV2(
                title = title,
                subtitle = projectName,
                items = items,
                sending = sending,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onNewChat = { vm.newConversation() },
                onShowMoreMenu = { moreMenuOpen = true },
                inputBar = {
                    ChatInputBar(
                        value = inputText,
                        onValueChange = { inputText = it },
                        pendingImages = pendingImages,
                        onPickImage = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onTakePhoto = {
                            val target = createCameraTarget(context)
                            if (target != null) {
                                cameraTarget = target
                                cameraLauncher.launch(target)
                            }
                        },
                        onRemoveImage = { uri ->
                            pendingImages = pendingImages.filterNot { it.uri.toString() == uri }
                        },
                        reasoningEffort = effort,
                        onReasoningEffortChange = { vm.setReasoningEffort(it) },
                        sending = sending,
                        onSend = {
                            vm.send(inputText.trim(), pendingImages.map { it.uri })
                            inputText = ""
                            pendingImages = emptyList()
                        },
                        onStop = { vm.stop() },
                    )
                },
            )

            // 「更多」浮窗：添加到项目 / 复制 / 删除当前会话。
            // 必须对齐到右上角，否则 DropdownMenu 会以整个 Box 的左上角为锚点，弹到屏幕另一边。
            Box(Modifier.align(Alignment.TopEnd)) {
                ChatMoreMenu(
                    expanded = moreMenuOpen,
                    onDismiss = { moreMenuOpen = false },
                    onAddToProject = {
                        moreMenuOpen = false
                        assignDialogOpen = true
                    },
                    onCreateProject = {
                        moreMenuOpen = false
                        createProjectDialogOpen = true
                    },
                    onCopy = {
                        moreMenuOpen = false
                        clipboard.setText(AnnotatedString(items.toPlainText()))
                    },
                    onDelete = {
                        moreMenuOpen = false
                        currentId?.let { vm.deleteConversation(it) }
                    },
                )
            }
        }
    }

    if (assignDialogOpen) {
        AssignProjectDialog(
            projects = projects,
            onDismiss = { assignDialogOpen = false },
            onPick = { projectId ->
                assignDialogOpen = false
                currentId?.let { vm.assignConversationToProject(it, projectId) }
            },
            onCreateNew = {
                assignDialogOpen = false
                createProjectDialogOpen = true
            },
        )
    }

    if (createProjectDialogOpen) {
        CreateProjectDialog(
            onDismiss = { createProjectDialogOpen = false },
            onConfirm = { name ->
                createProjectDialogOpen = false
                vm.createProject(name)
            },
        )
    }
}

/** 「更多」浮窗，条目与需求一致：添加到项目、复制、删除当前会话。 */
@Composable
private fun ChatMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddToProject: () -> Unit,
    onCreateProject: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Box {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text("添加到项目") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                onClick = onAddToProject,
            )
            DropdownMenuItem(
                text = { Text("新建项目") },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = onCreateProject,
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("复制本会话内容") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = onCopy,
            )
            DropdownMenuItem(
                text = { Text("删除当前会话") },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                onClick = onDelete,
            )
        }
    }
}

/** 选择项目对话框；列表为空时也能从这里转去新建。 */
@Composable
private fun AssignProjectDialog(
    projects: List<Project>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到项目") },
        text = {
            Column {
                if (projects.isEmpty()) {
                    Text(
                        "还没有项目，先新建一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                projects.forEach { project ->
                    Text(
                        project.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(project.id) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreateNew) { Text("新建项目") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建项目（从聊天页触发），只需要一个名称。 */
@Composable
private fun CreateProjectDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("项目名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 把时间线拼成纯文本，便于复制或导出整个会话。 */
internal fun List<ChatTimelineItem>.toPlainText(): String =
    joinToString("\n\n") { item ->
        when (item) {
            is ChatTimelineItem.UserMessage -> "【我】${item.text}"
            is ChatTimelineItem.AgentMessage -> "【AI】${item.text}"
            is ChatTimelineItem.Reasoning -> "【思考】${item.text}"
            is ChatTimelineItem.ToolCall ->
                "【工具】${item.name} -> ${item.result ?: item.error ?: "执行中"}"
            is ChatTimelineItem.Notice -> "【提示】${item.text}"
        }
    }

/**
 * 为这次的拍照准备一个可写的目标 uri（走 FileProvider，相机才有权限写入）。
 *
 * 建文件属于磁盘 IO，放在主线程会很小的概率触发 StrictMode；这里用 try/catch 兜住失败，
 * 相机不可用的设备上点了不会崩。
 */
private fun createCameraTarget(context: Context): Uri? = try {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (e: Exception) {
    null
}

/** 待发送图片：只持 uri，读取字节推迟到真正发送时，避免在 UI 线程做 IO。 */
data class PendingImage(val uri: Uri)
