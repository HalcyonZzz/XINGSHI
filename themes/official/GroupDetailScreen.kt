package com.xingshi.ai.feature.groupchat.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.common.ImageUtils
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.feature.groupchat.GroupChatViewModel
import com.xingshi.ai.feature.groupchat.GroupChatViewModelFactory
import com.xingshi.ai.theme.framework.themeText

import com.xingshi.ai.theme.framework.ThemePageAction
import com.xingshi.ai.theme.framework.ThemePageHost
import com.xingshi.ai.theme.framework.ThemePageItem
import com.xingshi.ai.theme.framework.ThemePageSlot
import com.xingshi.ai.theme.framework.ThemePages

@Composable
fun GroupDetailScreen(
    groupId: Long,
    onNavigateBack: () -> Unit,
    onGroupDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModelFactory(context.applicationContext as Application, groupId)
    )
    val groupData by viewModel.groupData.collectAsState()
    val companions by viewModel.allCompanions.collectAsState()
    val activeCompanionIds = groupData?.getCompanionIdList() ?: emptyList()
    val activeCompanions = companions.filter { activeCompanionIds.contains(it.id) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.viewModelScope.launch {
                val savedPath = ImageUtils.saveUriToInternalStorage(context, selectedUri.toString())
                if (savedPath != null) viewModel.updateGroupAvatar(savedPath)
            }
        }
    }
    ThemePageSlot(
        page = ThemePages.GROUP_DETAIL,
        host = {
            ThemePageHost(
                page = ThemePages.GROUP_DETAIL,
                items = activeCompanions.map { companion ->
                    ThemePageItem(id = companion.id.toString(), kind = "member", title = companion.name, avatar = companion.avatarUrl.orEmpty())
                },
                state = mapOf(
                    "group_id" to groupId.toString(),
                    "name" to groupData?.name.orEmpty(),
                    "avatar_url" to groupData?.avatarUrl.orEmpty(),
                    "member_count" to activeCompanions.size.toString(),
                    "member_ids" to activeCompanionIds.joinToString(",")
                ),
                dispatch = { action ->
                    when (action) {
                        is ThemePageAction.Invoke -> when (action.id) {
                            "pick_group_avatar" -> avatarPicker.launch("image/*")
                            "delete_group" -> {
                                viewModel.deleteGroup()
                                onGroupDeleted()
                            }
                            "back" -> onNavigateBack()
                        }
                        is ThemePageAction.Open -> when (action.route) {
                            "back" -> onNavigateBack()
                        }
                    }
                }
            )
        },
        fallback = { GroupDetailScreenContent(groupId = groupId, onNavigateBack = onNavigateBack, onGroupDeleted = onGroupDeleted) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailScreenContent(
    groupId: Long,
    onNavigateBack: () -> Unit,
    onGroupDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModelFactory(context.applicationContext as Application, groupId)
    )
    val groupData by viewModel.groupData.collectAsState()
    val companions by viewModel.allCompanions.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    val activeCompanionIds = groupData?.getCompanionIdList() ?: emptyList()
    val activeCompanions = companions.filter { activeCompanionIds.contains(it.id) }

    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            viewModel.viewModelScope.launch {
                val savedPath = ImageUtils.saveUriToInternalStorage(context, selectedUri.toString())
                if (savedPath != null) {
                    viewModel.updateGroupAvatar(savedPath)
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = themeText("ui_group_detail_screen_01"),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = themeText("ui_group_detail_screen_02"),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.size(32.dp))
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (groupData?.avatarUrl != null) {
                            AsyncImage(
                                model = groupData?.avatarUrl,
                                contentDescription = groupData?.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = groupData?.name?.firstOrNull()?.toString() ?: "?",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = groupData?.name ?: themeText("ui_group_detail_screen_03"),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = themeText("ui_group_detail_screen_04"),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { 
                                    newGroupName = groupData?.name ?: ""
                                    showEditNameDialog = true 
                                },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "${activeCompanions.size} 个AI成员",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = themeText("ui_group_detail_screen_05"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    activeCompanions.forEach { companion ->
                        GroupMemberItem(companion = companion)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Column {
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {}
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = themeText("ui_group_detail_screen_06"),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeleteDialog = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = themeText("ui_group_detail_screen_07"),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(themeText("ui_group_detail_screen_08")) },
            text = { Text(themeText("ui_group_detail_screen_09")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteGroup()
                        showDeleteDialog = false
                        onGroupDeleted()
                    }
                ) {
                    Text(themeText("ui_group_detail_screen_10"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(themeText("ui_group_detail_screen_11"))
                }
            }
        )
    }

    
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text(themeText("ui_group_detail_screen_12")) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text(themeText("ui_group_detail_screen_13")) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEditNameDialog = false
                    }
                ) {
                    Text(themeText("ui_group_detail_screen_14"), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text(themeText("ui_group_detail_screen_15"))
                }
            }
        )
    }
}

@Composable
private fun GroupMemberItem(
    companion: CompanionEntity
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (companion.avatarUrl != null) {
                AsyncImage(
                    model = companion.avatarUrl,
                    contentDescription = companion.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = companion.name.firstOrNull()?.toString() ?: "?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = companion.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (companion.personality != null) {
                Text(
                    text = companion.personality,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
