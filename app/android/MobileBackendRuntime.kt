package com.xingshi.ai

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import com.xingshi.ai.common.AccountStorage
import com.xingshi.ai.database.*
import com.xingshi.ai.database.model.CompanionEntity
import com.xingshi.ai.database.repository.*
import com.xingshi.ai.domain.*
import com.xingshi.ai.feature.chat.ui.viewmodel.MessageAttachments
import com.xingshi.ai.feature.companion.ui.viewmodel.CompanionCreationRejection
import com.xingshi.ai.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

class MobileBackendRuntime private constructor(private val app: Application) : BackendAccountGateway {
    private val tokens = AuthTokenStore(app)
    private val environmentPrefs = app.getSharedPreferences("backend_environment", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(BackendAccountState(environment(), tokens.current()?.userId, tokens.current()?.generation ?: 0, canConfigureEnvironment = BuildConfig.DEBUG))
    override val state = mutableState.asStateFlow()
    private val lifecycle = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var consumer: com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer? = null
    private var group: com.xingshi.ai.network.BackendGroupGateway? = null
    private var http: BackendHttpClient? = null
    private val resources get() = BackendResources(http ?: error("请先登录"))
    private val db get() = AppDatabase.getDatabase(app)
    private val localPrefs get() = app.getSharedPreferences("backend_handoffs", Context.MODE_PRIVATE)

    init {
        tokens.onSessionCleared = {
            mutableState.value = BackendAccountState(environment(), generation = tokens.nextGeneration(), canConfigureEnvironment = BuildConfig.DEBUG)
            consumer?.stop()
            runtimeScope.launch { lifecycle.withLock {
                if (tokens.current() == null && AccountStorage.partition != "unbound") {
                    stopSession()
                    bindStorage(null)
                    ServiceRegistry.clear()
                    XingShiApplication.registerServiceProviders(app)
                }
            } }
        }
        tokens.current()?.takeIf { it.environment == environment() }?.let { AccountStorage.select(it.environment, it.userId) }
        ?: run { AccountStorage.unbind(); tokens.current()?.let { tokens.clear(it.generation) }; mutableState.value = BackendAccountState(environment(), canConfigureEnvironment = BuildConfig.DEBUG) }
        val networks = app.getSystemService(android.net.ConnectivityManager::class.java)
        networks.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { wake() }
        })
    }

    fun wake() {
        consumer?.wake()
        val session = tokens.current() ?: return
        sessionScope.launch { lifecycle.withLock {
            if (tokens.current()?.generation != session.generation) return@withLock
            runCatching {
                discover(); recoverDeletions()
                val profile = resources.profile()
                ServiceRegistry.get(UserRepository::class.java)?.updateUserName(profile.text("nickname"))
                mutableState.value = mutableState.value.copy(error = null)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(error = failure.message ?: "删除状态尚待确认")
            }
        } }
    }
    override fun reconnect() { wake() }

    private fun environment() = if (BuildConfig.DEBUG) environmentPrefs.getString("origin", BuildConfig.LOOM_BACKEND_ORIGIN).orEmpty() else BuildConfig.LOOM_BACKEND_ORIGIN
    override suspend fun configureEnvironment(origin: String) = lifecycle.withLock {
        check(BuildConfig.DEBUG) { "正式包的服务地址由构建配置指定" }
        val normalized = BackendHttpClient(origin, tokens, allowDebugHttp = true).origin
        if (normalized != environment()) {
            stopSession()
            tokens.current()?.let { tokens.clear(it.generation) }
            environmentPrefs.edit().putString("origin", normalized).commit()
            bindStorage(null)
            mutableState.value = BackendAccountState(normalized, generation = tokens.nextGeneration(), canConfigureEnvironment = BuildConfig.DEBUG)
        }
    }

    override suspend fun authenticate(account: String, password: String, register: Boolean) = lifecycle.withLock {
        val client = BackendResources(BackendHttpClient(environment(), tokens, allowDebugHttp = BuildConfig.DEBUG))
        val registrationId = if (register) {
            val key = "registration:$account"
            environmentPrefs.getString(key, null) ?: UUID.randomUUID().toString().also {
                check(environmentPrefs.edit().putString(key, it).commit()) { "无法保存注册操作身份" }
            }
        } else null
        val result = try { client.authenticate(account, password, registrationId) }
        catch (error: BackendHttpException) {
            if (register && error.status == 409) client.authenticate(account, password, null) else throw error
        }
        stopSession()
        val user = result.obj("user")
        val session = BackendSession(environment(), user.text("user_id"), result.text("session_id"), result.text("access_token"), result.text("refresh_token"),
            result.text("access_token_expires_at"), result.text("session_expires_at"), user.text("nickname"), tokens.nextGeneration())
        tokens.saveSession(session)
        bindStorage(session)
        ServiceRegistry.clear()
        XingShiApplication.registerServiceProviders(app)
        UserRepository(app).updateUserName(session.nickname)
        mutableState.value = BackendAccountState(session.environment, session.userId, session.generation, canConfigureEnvironment = BuildConfig.DEBUG)
        val initial = result.obj("initial_instance")
        if (initial.text("instance_id").isNotBlank()) mapInstance(initial, result.text("initial_conversation_id"))
        try { discover(); recoverDeletions() }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.value = mutableState.value.copy(error = failure.message ?: "登录已完成，助手同步待重连")
        }
    }

    override suspend fun logout() = logout(tokens.current())
    private suspend fun logout(expected: BackendSession?) = lifecycle.withLock {
        requireSession(expected)
        val session = tokens.current()
        try { if (session != null) resources.http.json("POST", "/api/auth/logout") }
        finally {
            stopSession()
            session?.let { tokens.clear(it.generation) }
            bindStorage(null)
            ServiceRegistry.clear()
            XingShiApplication.registerServiceProviders(app)
            mutableState.value = BackendAccountState(environment(), generation = tokens.nextGeneration(), canConfigureEnvironment = BuildConfig.DEBUG)
        }
    }

    override suspend fun updateProfile(nickname: String) = updateProfile(nickname, tokens.current())
    private suspend fun updateProfile(nickname: String, expected: BackendSession?) = lifecycle.withLock {
        requireSession(expected)
        val profile = resources.profile()
        val saved = resources.updateProfile(profile.number("version"), nickname)
        ServiceRegistry.getOrThrow(UserRepository::class.java).updateUserName(saved.text("nickname"))
    }

    private suspend fun discover() {
        var after: String? = null
        do {
            val page = resources.instances(after)
            for (item in page.array("items")) {
                mapInstance(item.jsonObject)
            }
            after = page.text("next_after_instance_id").ifBlank { null }
        } while (after != null)
    }

    private suspend fun mapInstance(summary: JsonObject, initialConversation: String = "") {
        val instanceId = summary.text("instance_id")
        val existing = db.backendHandoffDao().linkForInstance(instanceId)
        if (existing?.deletingOperationId != null) return
        val identity = resources.identity(instanceId)
        val localId = existing?.companionId ?: db.companionDao().insertCompanion(CompanionEntity(name = summary.text("display_name"), personality = ""))
        db.backendHandoffDao().saveLink((existing ?: BackendCompanionLink(localId, instanceId,
            initialConversation.ifBlank { identity.text("conversation_id") }, identity.number("version"))).copy(version = identity.number("version")))
    }

    private suspend fun save(draft: CloudCompanionDraft, expected: BackendSession?): Long = lifecycle.withLock {
        requireSession(expected)
        val rejection = CompanionCreationRejection(localPrefs)
        val draftId = draft.localId.takeIf { it > 0 } ?: localPrefs.getLong("new-companion", 0)
        val existing = if (draftId > 0) db.companionDao().getCompanionById(draftId) else null
        val localId = existing?.id ?: rejection.insert(draft.localId == 0L) {
            db.companionDao().insertCompanion(CompanionEntity(name = draft.name, personality = draft.personality,
                backstory = draft.backstory, speakingStyle = draft.speakingStyle, rawPrompt = draft.rawPrompt))
        }
        val old = db.backendHandoffDao().link(localId)
        check(old?.deletingOperationId == null) { "助手删除尚未完成" }
        val personaPayload = buildJsonObject {
            put("display_name", draft.name); put("personality", draft.personality)
            draft.backstory?.let { put("backstory", it) }; draft.speakingStyle?.let { put("speaking_style", it) }; draft.rawPrompt?.let { put("raw_prompt", it) }
        }
        val pendingKey = "pending-companion:$localId"
        var pending = localPrefs.getString(pendingKey, null)?.let { Json.parseToJsonElement(ChatMessageCrypto.decrypt(it)).jsonObject } ?: run {
            val expected = old?.let { resources.identity(it.instanceId).number("version") } ?: 0
            buildJsonObject { put("payload", personaPayload); put("expected_version", expected); put("operation_id", stableOperation("companion:$localId")) }
                .also { check(localPrefs.edit().putString(pendingKey, ChatMessageCrypto.encrypt(it.toString())).commit()) }
        }
        check(pending.obj("payload") == personaPayload) { "上次人设保存尚待确认，请先使用原编辑内容重试" }
        val (persona, identity) = rejection.handle(
            localId,
            createdHere = existing == null,
            deleteCompanion = { id ->
                db.companionDao().deleteCompanion(CompanionEntity(id = id, name = draft.name, personality = draft.personality))
            }
        ) {
            confirmConfigurationEdit(onRejected = { rejectConfigurationEdit(pendingKey, "companion:$localId", it) }) {
                val persona = if (pending.obj("ref").isNotEmpty()) pending.obj("ref") else {
                    val published = resources.persona(personaPayload)
                    val ref = published["ref"] as? JsonObject ?: published
                    pending = buildJsonObject { pending.forEach { (key, value) -> put(key, value) }; put("ref", ref) }
                    check(localPrefs.edit().putString(pendingKey, ChatMessageCrypto.encrypt(pending.toString())).commit())
                    ref
                }
                val operation = pending.text("operation_id")
                val identity = if (old == null) resources.createCompanion(operation, draft.name, persona)
                else resources.bindPersona(old.instanceId, pending.number("expected_version"), persona, operation).also { resources.rename(old.instanceId, draft.name) }
                persona to identity
            }
        }
        db.withTransaction {
            db.backendHandoffDao().saveLink(BackendCompanionLink(localId, identity.text("instance_id").ifBlank { old?.instanceId.orEmpty() }, identity.text("conversation_id").ifBlank { old?.conversationId.orEmpty() },
                identity.number("version"), persona.text("persona_config_id"), persona.number("version")))
            val saved = db.companionDao().getCompanionById(localId) ?: error("助手草稿不存在")
            db.companionDao().updateCompanion(saved.copy(name = draft.name, personality = draft.personality, backstory = draft.backstory ?: saved.backstory,
                speakingStyle = draft.speakingStyle ?: saved.speakingStyle, rawPrompt = draft.rawPrompt, updatedAt = System.currentTimeMillis()))
        }
        localPrefs.edit().remove("op:companion:$localId").remove(pendingKey).apply { if (draft.localId == 0L) remove("new-companion") }.commit()
        localId
    }

    private suspend fun publish(draft: ChatModelDraft, expected: BackendSession?): CloudChatModelPublish = lifecycle.withLock {
        requireSession(expected)
        val id = localPrefs.getString("model:${draft.localId}", null) ?: "mobile-${UUID.randomUUID()}".also { localPrefs.edit().putString("model:${draft.localId}", it).commit() }
        val key = "model-pending:${draft.localId}"
        val draftBody = chatModelConfigurationPayload(draft)
        var frozen = localPrefs.getString(key, null)?.let { Json.parseToJsonElement(ChatMessageCrypto.decrypt(it)).jsonObject } ?: run {
            val current = try {
                resources.chatConfiguration(id)
            } catch (failure: BackendHttpException) {
                throw IllegalStateException(chatFailure("读取云端主模型配置", failure), failure)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalStateException("云端主模型配置读取失败：${failure.message ?: "网络异常"}，请稍后重试", failure)
            }
            buildJsonObject {
                draftBody.forEach { (name, value) -> put(name, value) }
                put("operation_id", stableOperation("model-save:${draft.localId}")); put("expected_version", current?.get("version") ?: JsonNull)
            }.also { check(localPrefs.edit().putString(key, ChatMessageCrypto.encrypt(it.toString())).commit()) }
        }
        val legacy = !frozen.containsKey("provider")
        check(legacy || frozen.filterKeys { it !in setOf("operation_id", "expected_version") } == draftBody) { "原模型配置操作尚待确认，请使用原内容重试" }
        val saved = try {
            var receipt = confirmConfigurationEdit(onRejected = { rejectConfigurationEdit(key, "model-save:${draft.localId}", it) }) {
                resources.saveChatConfiguration(id, frozen)
            }
            if (legacy) {
                check(localPrefs.edit().remove(key).remove("op:model-save:${draft.localId}").commit())
                frozen = buildJsonObject {
                    draftBody.forEach { (name, value) -> put(name, value) }
                    put("operation_id", stableOperation("model-save:${draft.localId}")); put("expected_version", receipt.number("version"))
                }
                check(localPrefs.edit().putString(key, ChatMessageCrypto.encrypt(frozen.toString())).commit())
                receipt = confirmConfigurationEdit(onRejected = { rejectConfigurationEdit(key, "model-save:${draft.localId}", it) }) {
                    resources.saveChatConfiguration(id, frozen)
                }
            }
            receipt
        } catch (failure: BackendHttpException) {
            return@withLock if (failure.definitiveRejection) CloudChatModelPublish.Rejected(chatFailure("发布主模型配置", failure))
            else CloudChatModelPublish.Unknown("云端暂时无法确认发布结果（${failure.status}）")
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is StaleBackendSession) throw failure
            return@withLock CloudChatModelPublish.Unknown("云端暂时无法确认发布结果：${failure.message ?: "网络异常"}")
        }
        localPrefs.edit().remove("op:model-save:${draft.localId}").remove(key).commit()
        CloudChatModelPublish.Confirmed(cloudConfiguration(saved, draft.localId, pending = false))
    }

    private suspend fun attach(localId: Long, configurationId: String, expected: BackendSession?): Unit = lifecycle.withLock {
        requireSession(expected)
        val existing = localPrefs.getString("model:$localId", null)
        check(existing == null || existing == configurationId) { "本地缓存已指向其他云端主模型配置，请刷新后重试" }
        val stale = localPrefs.all.entries.filter { it.value == configurationId && it.key != "model:$localId" }.map { it.key }
        val editor = localPrefs.edit().putString("model:$localId", configurationId)
        stale.forEach { editor.remove(it) }
        check(editor.commit()) { "无法保存主模型配置引用" }
        Unit
    }

    private suspend fun configurations(expected: BackendSession?): CloudChatModelSnapshot = lifecycle.withLock {
        requireSession(expected)
        clearLegacyVersionCopies()
        val bindings = localPrefs.all.entries.mapNotNull { (name, value) ->
            if (!name.startsWith("model:")) null else (value as? String)?.let { reference -> name.removePrefix("model:").toLongOrNull()?.let { it to reference } }
        }.toMap()
        val pendingIds = localPrefs.all.keys.filter { it.startsWith("model-pending:") }.mapNotNull { it.removePrefix("model-pending:").toLongOrNull() }.toSet()
        val rows = mutableListOf<JsonObject>()
        var defaultId: String? = null
        var defaultVersion = 0L
        var after: String? = null
        do {
            val page = resources.chatConfigurations(after)
            if (after == null) {
                defaultId = page.text("default_configuration_id").ifBlank { null }
                defaultVersion = page.number("default_configuration_version")
            }
            rows += page.array("items").map { it.jsonObject }
            after = page.text("next_after_configuration_id").ifBlank { null }
        } while (after != null)
        CloudChatModelSnapshot(rows.map { item ->
            val configurationId = item.text("configuration_id")
            val localId = bindings.entries.firstOrNull { it.value == configurationId }?.key ?: 0L
            cloudConfiguration(item, localId, pending = localId > 0 && localId in pendingIds)
        }, defaultId, defaultVersion, pendingIds)
    }

    private suspend fun activate(localId: Long, expected: BackendSession?): CloudChatModelDefault = lifecycle.withLock {
        requireSession(expected)
        val id = localPrefs.getString("model:$localId", null) ?: error("该配置尚未发布到云端")
        val current = resources.chatConfiguration(id) ?: error("云端找不到该主模型配置")
        when (current.text("status")) {
            "ACTIVE" -> Unit
            "DISABLED" -> error("该主模型配置已在云端停用，请先启用后再设为账号默认")
            else -> error("该主模型配置的云端凭据不可用，请重新填写 Key 后保存")
        }
        val result = try {
            resources.defaultModel(id, current.number("version"), stableOperation("model-default:$localId"))
        } catch (failure: BackendHttpException) {
            throw IllegalStateException(chatFailure("切换账号默认主模型", failure), failure)
        }
        localPrefs.edit().remove("op:model-default:$localId").commit()
        consumer?.wake()
        CloudChatModelDefault(result.text("default_configuration_id"), result.number("default_configuration_version"),
            result.array("failed").map { value ->
                val item = value as? JsonObject ?: return@map value.jsonPrimitive.content
                val reason = when (item.text("reason")) {
                    "VERSION_CONFLICT_OR_UNAVAILABLE" -> "配置已在其他设备改变或暂不可用"
                    "" -> "绑定尚未生效"
                    else -> item.text("reason")
                }
                "${item.text("instance_id")}（$reason）"
            })
    }

    private suspend fun deleteConfiguration(localId: Long, expected: BackendSession?): Unit = lifecycle.withLock {
        requireSession(expected)
        val id = localPrefs.getString("model:$localId", null) ?: error("该配置尚未发布到云端")
        check(localPrefs.getString("model-pending:$localId", null) == null) { "上次主模型发布结果尚待确认，请先重试保存" }
        try {
            resources.deleteConfiguration(id, stableOperation("model-delete:$localId"))
        } catch (failure: BackendHttpException) {
            throw IllegalStateException(chatFailure("停用主模型配置", failure), failure)
        }
        localPrefs.edit().remove("op:model-delete:$localId").commit()
        consumer?.wake()
        Unit
    }

    private suspend fun choices(companionId: Long, expected: BackendSession?): List<CloudChatModelChoice> = lifecycle.withLock {
        requireSession(expected)
        val link = db.backendHandoffDao().link(companionId) ?: error("助手身份尚未恢复")
        check(link.deletingOperationId == null) { "助手正在删除" }
        val current = resources.chatBinding(link.instanceId)
        val choices = mutableListOf<CloudChatModelChoice>()
        var after: String? = null
        do {
            val page = resources.chatConfigurations(after)
            if (after == null && page.text("default_configuration_id").isNotBlank()) choices += CloudChatModelChoice(
                page.text("default_configuration_id"), page.number("default_configuration_version"), "继承账号默认普通聊天模型", "DEFAULT", current.text("selection_mode") == "DEFAULT")
            choices += page.array("items").map { it.jsonObject }.filter { it.text("status") == "ACTIVE" }.map { item ->
                CloudChatModelChoice(item.text("configuration_id"), item.number("version"), "${item.text("model")} · ${item.text("base_url")}",
                    selected = current.text("selection_mode") == "EXPLICIT" && current.text("configuration_id") == item.text("configuration_id") && current.number("configuration_version") == item.number("version"))
            }
            after = page.text("next_after_configuration_id").ifBlank { null }
        } while (after != null)
        choices
    }

    private suspend fun select(companionId: Long, choice: CloudChatModelChoice, expected: BackendSession?) = lifecycle.withLock {
        requireSession(expected)
        val link = db.backendHandoffDao().link(companionId) ?: error("助手身份尚未恢复")
        check(link.deletingOperationId == null) { "助手正在删除" }
        val key = "binding-pending:$companionId"
        val pending = localPrefs.getString(key, null)?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject {
            put("configuration_id", choice.configurationId); put("configuration_version", choice.version)
            put("selection_mode", choice.selectionMode); put("expected_instance_version", resources.identity(link.instanceId).number("version"))
            put("operation_id", UUID.randomUUID().toString())
        }.also { check(localPrefs.edit().putString(key, it.toString()).commit()) }
        check(pending.text("configuration_id") == choice.configurationId && pending.number("configuration_version") == choice.version && pending.text("selection_mode") == choice.selectionMode) { "原模型选择尚待确认，请先重试原选择" }
        val result = try { resources.bindChat(link.instanceId, pending) }
        catch (failure: BackendHttpException) {
            if (failure.status in setOf(403, 404, 409, 422)) localPrefs.edit().remove(key).commit()
            throw failure
        }
        db.backendHandoffDao().saveLink(link.copy(version = result.number("version"), provider = result.text("provider"), model = result.text("model")))
        check(localPrefs.edit().remove(key).commit())
        consumer?.wake()
        Unit
    }

    private suspend fun delete(localId: Long, expected: BackendSession?): Boolean = lifecycle.withLock {
        requireSession(expected)
        val link = db.backendHandoffDao().link(localId) ?: error("该助手身份尚未确认，未删除本机数据")
        val operation = link.deletingOperationId ?: stableOperation("delete:$localId").also {
            if (com.xingshi.ai.feature.chat.VoiceCallCoordinator.active.value?.companionId == localId) com.xingshi.ai.feature.chat.VoiceCallCoordinator.stopForAccountSwitch()
            consumer?.pauseCompanion(localId, it) ?: db.backendHandoffDao().saveLink(link.copy(deletingOperationId = it))
        }
        val result = resources.deletion(link.instanceId, operation)
        if (result.text("status") != "DELETED") {
            if (result.text("status") == "FAILED") error("删除未完成：${result.text("error_code")}")
            return@withLock false
        }
        finishLocalDeletion(link)
        true
    }

    private suspend fun recoverDeletions() {
        for ((_, encoded) in localPrefs.all.filterKeys { it.startsWith("deleted:") }) {
            val tombstone = Json.parseToJsonElement(encoded as String).jsonObject
            val link = db.backendHandoffDao().link(tombstone.number("companion_id"))
            if (link?.instanceId == tombstone.text("instance_id")) finishLocalDeletion(link)
        }
        for (link in db.backendHandoffDao().links().filter { it.deletingOperationId != null }) {
            var result = try { resources.deletionStatus(link.deletingOperationId!!) }
            catch (failure: BackendHttpException) {
                if (failure.status != 404) throw failure
                resources.deletion(link.instanceId, link.deletingOperationId!!)
            }
            if (result.text("status") in setOf("DELETING", "FAILED")) result = resources.deletion(link.instanceId, link.deletingOperationId!!)
            if (result.text("status") == "DELETED") finishLocalDeletion(link)
            else if (result.text("status") == "FAILED") error("删除未完成：${result.text("error_code")}")
        }
    }

    private suspend fun finishLocalDeletion(link: BackendCompanionLink) {
        val localId = link.companionId
        check(localPrefs.edit().putString("deleted:${link.instanceId}", buildJsonObject {
            put("companion_id", localId); put("instance_id", link.instanceId)
        }.toString()).commit()) { "无法保存已删除身份，暂保留本机资料" }
        val companion = db.companionDao().getCompanionById(localId)
        consumer?.forgetCompanion(localId)
        consumer?.reclaimBilibiliWorkFor(localId)
        cleanupPrivateMedia(localId, companion?.avatarUrl)
        com.xingshi.ai.feature.chat.data.MessageReadStore(app).remove(localId)
        com.xingshi.ai.feature.chat.VoiceTurnMetadataStore.removeCompanion(app, localId)
        db.withTransaction {
            db.groupMessageDao().preserveAuthor(localId, companion?.name.orEmpty(), tokens.current()?.userId ?: error("账号身份不可读取"), link.instanceId)
            ChatRepository(db.chatMessageDao()).clearChatHistory(localId)
            db.memoryDao().deleteCompanionMemories(localId); db.memoryDao().deleteCompanionTempMemories(localId)
            db.backendHandoffDao().deleteInputs(localId); db.backendHandoffDao().deleteReceipts(localId); db.backendHandoffDao().deleteLink(localId)
            companion?.let { db.companionDao().deleteCompanion(it) }
        }
        val editor = localPrefs.edit().remove("op:delete:$localId").remove("pending-companion:$localId").remove("op:companion:$localId").remove("binding-pending:$localId")
        localPrefs.all.keys.filter { it.startsWith("configuration-failed:companion:$localId:") }.forEach { editor.remove(it) }
        if (localPrefs.getLong("new-companion", 0) == localId) editor.remove("new-companion")
        check(editor.commit())
    }

    private suspend fun cleanupPrivateMedia(localId: Long, avatar: String?) {
        val candidates = ChatRepository(db.chatMessageDao()).getMessagesForCompanionSync(localId)
            .flatMap { MessageAttachments.mediaReferences(it) } + listOfNotNull(avatar)
        val retained = db.chatMessageDao().getOtherCompanionMessages(localId).map(ChatMessageCrypto::decryptFromStorage)
            .flatMap { MessageAttachments.mediaReferences(it) } +
            db.groupMessageDao().getAllMessagesSync().map(ChatMessageCrypto::decryptFromStorage).flatMap { listOf(it.linkString, it.content) } +
            db.companionDao().getAllCompanionsSync().filter { it.id != localId }.mapNotNull { it.avatarUrl } +
            listOfNotNull(ServiceRegistry.get(UserRepository::class.java)?.userAvatar?.value)
        com.xingshi.ai.plugins.PluginFramework.manager()?.removeCompanion(localId, retained)
        com.xingshi.ai.common.AccountMediaCleanup.remove(listOf(java.io.File(app.filesDir, "attachments"), java.io.File(app.filesDir, "avatars"),
            java.io.File(app.cacheDir, "voice_messages"), java.io.File(app.cacheDir, "voice_replay")), candidates, retained)
    }

    private fun rejectConfigurationEdit(key: String, operationKey: String, failure: BackendHttpException) {
        val original = localPrefs.getString(key, null) ?: return
        val frozen = Json.parseToJsonElement(ChatMessageCrypto.decrypt(original)).jsonObject
        val archive = buildJsonObject { put("pending", frozen); put("http_status", failure.status) }
        check(localPrefs.edit().putString("configuration-failed:$operationKey:${frozen.text("operation_id")}", ChatMessageCrypto.encrypt(archive.toString()))
            .remove(key).remove("op:$operationKey").commit()) { "无法保存失败配置草稿" }
    }

    private fun stableOperation(key: String): String = localPrefs.getString("op:$key", null) ?: UUID.randomUUID().toString().also {
        check(localPrefs.edit().putString("op:$key", it).commit()) { "无法保存操作身份" }
    }

    private fun clearLegacyVersionCopies() {
        val stale = localPrefs.all.keys.filter { it.startsWith("model-version:") }
        if (stale.isNotEmpty()) localPrefs.edit().apply { stale.forEach(::remove) }.commit()
    }

    private fun cloudConfiguration(receipt: JsonObject, localId: Long, pending: Boolean): CloudChatModelConfiguration {
        val parameters = receipt.obj("parameters")
        return CloudChatModelConfiguration(
            localId = localId,
            configurationId = receipt.text("configuration_id").ifBlank { localPrefs.getString("model:$localId", null).orEmpty() },
            version = receipt.number("version"),
            provider = receipt.text("provider"), protocol = receipt.text("protocol"),
            baseUrl = receipt.text("base_url"), model = receipt.text("model"),
            temperature = parameters["temperature"]?.jsonPrimitive?.floatOrNull ?: 0.7f,
            maxTokens = parameters["max_tokens"]?.jsonPrimitive?.intOrNull,
            status = receipt.text("status"),
            keyConfigured = receipt["api_key_configured"]?.jsonPrimitive?.booleanOrNull == true,
            pending = pending
        )
    }

    private fun chatFailure(action: String, failure: BackendHttpException): String {
        val reason = when {
            failure.serverMessage.contains("CHAT_CREDENTIAL_UNAVAILABLE") -> "云端凭据已不可用，请重新填写 API Key 后保存"
            failure.serverMessage.contains("CHAT_CONFIGURATION_UNAVAILABLE") -> "该配置已在云端停用或不可用"
            failure.status == 401 -> "登录已失效，请重新登录"
            failure.serverMessage.contains("VERSION_CONFLICT") || failure.status == 409 -> "配置版本已在其他设备改变，请刷新后重试"
            failure.serverMessage.contains("IDEMPOTENCY") -> "同一操作已提交过不同内容，请刷新后重新编辑"
            failure.status == 400 || failure.status == 413 || failure.status == 422 ->
                failure.serverMessage.ifBlank { "云端不接受该配置" }
            failure.status == 403 -> "当前账号无权修改主模型配置"
            failure.status == 404 -> "云端找不到可用的主模型配置"
            !failure.definitiveRejection -> "云端暂时无法确认结果，请重试同一操作以确认"
            else -> failure.serverMessage.ifBlank { failure.message ?: "云端请求失败（${failure.status}）" }
        }
        return "${action}失败：$reason"
    }

    private fun requireSession(expected: BackendSession?) {
        val current = tokens.current()
        if (expected == null || current?.generation != expected.generation || current.userId != expected.userId || current.environment != expected.environment) throw StaleBackendSession()
    }

    private suspend fun bindStorage(session: BackendSession?) {
        if (session == null) AccountStorage.unbind() else AccountStorage.select(session.environment, session.userId)
        withContext(Dispatchers.Main.immediate) {
            com.xingshi.ai.theme.framework.ThemeRuntime.registerOfficial(app, com.xingshi.ai.themes.official.OfficialTheme)
        }
    }

    private suspend fun stopSession() {
        mutableState.value = mutableState.value.copy(userId = null)
        com.xingshi.ai.feature.chat.VoiceCallCoordinator.stopForAccountSwitch()
        sessionScope.cancel(); consumer?.stopAndJoin(); consumer = null; group = null; http = null
        com.xingshi.ai.feature.chat.ui.viewmodel.InboundTurnDispatcher.stopAccount()
        com.xingshi.ai.feature.chat.ui.viewmodel.ConversationIntake.stopAccount()
        com.xingshi.ai.feature.groupchat.GroupReplyReceiver.stopAccount()
        com.xingshi.ai.feature.groupchat.GroupDeliveryRuntime.stopAccount()
        com.xingshi.ai.plugins.PluginFramework.resetAccount()
        com.xingshi.ai.common.AccountDataStores.close()
        AppDatabase.shutdown()
    }

    private fun startSession() {
        val session = tokens.current() ?: return
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        http = BackendHttpClient(session.environment, tokens, allowDebugHttp = BuildConfig.DEBUG)
        group = BackendGroupGateway(http!!, object : BackendGroupIdentity {
            override suspend fun instance(localId: Long) = db.backendHandoffDao().link(localId)?.let { BackendGroupInstance(it.instanceId, it.conversationId, it.provider, it.model) }
            override fun viewerId() = session.userId
            override fun viewerName() = ServiceRegistry.get(UserRepository::class.java)?.userName?.value.orEmpty()
            override suspend fun groups() = db.chatGroupDao().getAllGroupsSync().map { it.id }
            override suspend fun snapshot(groupId: Long): GroupConversationSnapshot? {
                val item = db.chatGroupDao().getGroupById(groupId) ?: return null
                val companions = db.companionDao().getAllCompanionsSync().associateBy { it.id }
                return GroupConversationSnapshot(item.id, item.name, item.createdAt,
                    item.getCompanionIdList().mapNotNull { id -> companions[id]?.let { GroupParticipant(id, it.name, it.avatarUrl) } },
                    com.xingshi.ai.database.repository.GroupMessageRepository(db.groupMessageDao()).getGroupHistory(groupId).map { message ->
                        GroupHistoryMessage(message.id, message.companionId.takeIf { it > 0 }, message.publishedAuthorName ?: if (message.companionId > 0) companions[message.companionId]?.name.orEmpty() else viewerName(),
                            message.content, message.timestamp, message.fileFormat.name, message.linkString, live = message.backendLive,
                            authorStableId = message.publishedAuthorStableId, authorInstanceId = message.publishedAuthorInstanceId)
                    })
            }
        }, app, ChatMessageCrypto::encrypt, ChatMessageCrypto::decrypt)
        consumer = com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer(app, http!!, tokens, group!!).also { it.start() }
        registerServices()
        com.xingshi.ai.feature.groupchat.GroupReplyReceiver.start(app)
        com.xingshi.ai.plugins.PluginFramework.init(app, com.xingshi.ai.feature.chat.ui.viewmodel.ChannelChatGateway(app))
        com.xingshi.ai.plugins.PluginFramework.manager()?.bindApplicationEntry(com.xingshi.ai.feature.chat.ui.viewmodel.ApplicationChatEntry(app))
        sessionScope.launch {
            lifecycle.withLock {
                if (tokens.current()?.generation != session.generation) return@withLock
                runCatching {
                    com.xingshi.ai.feature.chat.VoiceCallRecovery.recoverIfNeeded(app)
                    discover(); recoverDeletions()
                    val profile = resources.profile(); ServiceRegistry.getOrThrow(UserRepository::class.java).updateUserName(profile.text("nickname"))
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(error = failure.message ?: "账号同步尚待重连")
                }
            }
        }
    }

    companion object {
        lateinit var instance: MobileBackendRuntime; private set
        fun initialize(app: Application) { instance = MobileBackendRuntime(app) }
        fun registerServices() {
            if (!::instance.isInitialized) return
            val session = instance.tokens.current()
            ServiceRegistry.registerSingleton(BackendAccountGateway::class.java) { object : BackendAccountGateway {
                override val state = instance.state
                override suspend fun authenticate(account: String, password: String, register: Boolean) = instance.authenticate(account, password, register)
                override suspend fun configureEnvironment(origin: String) = instance.configureEnvironment(origin)
                override suspend fun logout() = instance.logout(session)
                override suspend fun updateProfile(nickname: String) = instance.updateProfile(nickname, session)
                override fun reconnect() {
                    instance.requireSession(session)
                    instance.wake()
                }
            } }
            ServiceRegistry.registerSingleton(CompanionCloudGateway::class.java) { object : CompanionCloudGateway {
                override suspend fun save(draft: CloudCompanionDraft) = instance.save(draft, session)
                override suspend fun delete(localId: Long) = instance.delete(localId, session)
            } }
            ServiceRegistry.registerSingleton(ChatModelCloudGateway::class.java) { object : ChatModelCloudGateway {
                override suspend fun publish(draft: ChatModelDraft) = instance.publish(draft, session)
                override suspend fun attach(localId: Long, configurationId: String) = instance.attach(localId, configurationId, session)
                override suspend fun configurations() = instance.configurations(session)
                override suspend fun activate(localId: Long) = instance.activate(localId, session)
                override suspend fun deleteConfiguration(localId: Long) = instance.deleteConfiguration(localId, session)
                override suspend fun choices(companionId: Long) = instance.choices(companionId, session)
                override suspend fun select(companionId: Long, choice: CloudChatModelChoice) = instance.select(companionId, choice, session)
            } }
            ServiceRegistry.registerSingleton(FormalGroupDeliverySink::class.java) { object : FormalGroupDeliverySink {
                override suspend fun save(groupId: Long, companionId: Long, messageId: String, content: String, createdAt: Long, payload: String): Long {
                    instance.requireSession(session)
                    return com.xingshi.ai.feature.groupchat.GroupReplyReceiver.saveFormal(instance.app, groupId, companionId, messageId, content, createdAt, payload, session!!.userId).localMessageId
                }
            } }
            val http = instance.http
            if (http != null) {
                ServiceRegistry.registerSingleton(StarCircleGateway::class.java) { BackendStarCircleGateway(http, object : PendingCircleOperations {
                    private val prefs = instance.localPrefs
                    override fun all(): Map<String, String> = prefs.all.filterKeys { it.startsWith("circle:") }.mapKeys { it.key.removePrefix("circle:") }.mapValues { ChatMessageCrypto.decrypt(it.value as String) }
                    override fun put(id: String, value: String) { check(prefs.edit().putString("circle:$id", ChatMessageCrypto.encrypt(value)).commit()) { "无法保存星圈操作身份" } }
                    override fun remove(id: String) { prefs.edit().remove("circle:$id").commit() }
                    override fun reject(id: String, original: String) {
                        check(prefs.edit().remove("circle:$id").commit()) { "无法结束失败的星圈操作" }
                    }
                }) }
                instance.group?.let { group -> ServiceRegistry.registerSingleton(GroupConversationGateway::class.java) { group } }
                instance.consumer?.let { consumer -> ServiceRegistry.registerSingleton(com.xingshi.ai.feature.chat.ui.viewmodel.BackendChatConsumer::class.java) { consumer } }
            } else if (instance.tokens.current() != null) instance.startSession()
        }
    }
}
