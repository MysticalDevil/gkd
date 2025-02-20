package li.songe.gkd.ui.home

import android.webkit.URLUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import li.songe.gkd.data.GithubPoliciesAsset
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.RpcError
import li.songe.gkd.data.SubsItem
import li.songe.gkd.data.SubsVersion
import li.songe.gkd.db.DbSet
import li.songe.gkd.util.FILE_UPLOAD_URL
import li.songe.gkd.util.LoadStatus
import li.songe.gkd.util.SortTypeOption
import li.songe.gkd.util.appInfoCacheFlow
import li.songe.gkd.util.clickCountFlow
import li.songe.gkd.util.client
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.map
import li.songe.gkd.util.orderedAppInfosFlow
import li.songe.gkd.util.ruleSummaryFlow
import li.songe.gkd.util.storeFlow
import li.songe.gkd.util.subsIdToRawFlow
import li.songe.gkd.util.subsItemsFlow
import li.songe.gkd.util.toast
import li.songe.gkd.util.updateSubscription
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeVm @Inject constructor() : ViewModel() {
    val tabFlow = MutableStateFlow(controlNav)

    val uploadStatusFlow = MutableStateFlow<LoadStatus<GithubPoliciesAsset>?>(null)
    var uploadJob: Job? = null

    fun uploadZip(zipFile: File) {
        uploadJob = viewModelScope.launchTry(Dispatchers.IO) {
            uploadStatusFlow.value = LoadStatus.Loading()
            try {
                val response =
                    client.submitFormWithBinaryData(url = FILE_UPLOAD_URL, formData = formData {
                        append("\"file\"", zipFile.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, "application/x-zip-compressed")
                            append(HttpHeaders.ContentDisposition, "filename=\"file.zip\"")
                        })
                    }) {
                        onUpload { bytesSentTotal, contentLength ->
                            if (uploadStatusFlow.value is LoadStatus.Loading) {
                                uploadStatusFlow.value =
                                    LoadStatus.Loading(bytesSentTotal / contentLength.toFloat())
                            }
                        }
                    }
                if (response.headers["X_RPC_OK"] == "true") {
                    val policiesAsset = response.body<GithubPoliciesAsset>()
                    uploadStatusFlow.value = LoadStatus.Success(policiesAsset)
                } else if (response.headers["X_RPC_OK"] == "false") {
                    uploadStatusFlow.value = LoadStatus.Failure(response.body<RpcError>())
                } else {
                    uploadStatusFlow.value = LoadStatus.Failure(Exception(response.bodyAsText()))
                }
            } catch (e: Exception) {
                uploadStatusFlow.value = LoadStatus.Failure(e)
            }
        }
    }

    private val latestRecordFlow =
        DbSet.clickLogDao.queryLatest().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val latestRecordDescFlow = combine(
        latestRecordFlow, subsIdToRawFlow, appInfoCacheFlow
    ) { latestRecord, subsIdToRaw, appInfoCache ->
        if (latestRecord == null) return@combine null
        val groupName =
            subsIdToRaw[latestRecord.subsId]?.apps?.find { a -> a.id == latestRecord.appId }?.groups?.find { g -> g.key == latestRecord.groupKey }?.name
        val appName = appInfoCache[latestRecord.appId]?.name
        val appShowName = appName ?: latestRecord.appId ?: ""
        if (groupName != null) {
            if (groupName.contains(appShowName)) {
                groupName
            } else {
                "$appShowName-$groupName"
            }
        } else {
            appShowName
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val subsStatusFlow = combine(ruleSummaryFlow, clickCountFlow) { allRules, clickCount ->
        allRules.numText + if (clickCount > 0) {
            "/${clickCount}点击"
        } else {
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun addSubsFromUrl(url: String) = viewModelScope.launchTry(Dispatchers.IO) {
        if (refreshingFlow.value) return@launchTry
        if (!URLUtil.isNetworkUrl(url)) {
            toast("非法链接")
            return@launchTry
        }
        val subItems = subsItemsFlow.value
        if (subItems.any { it.updateUrl == url }) {
            toast("订阅链接已存在")
            return@launchTry
        }
        refreshingFlow.value = true
        try {
            val text = try {
                client.get(url).bodyAsText()
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                toast("下载订阅文件失败")
                return@launchTry
            }
            val newSubsRaw = try {
                RawSubscription.parse(text)
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                toast("解析订阅文件失败")
                return@launchTry
            }
            if (subItems.any { it.id == newSubsRaw.id }) {
                toast("订阅已存在")
                return@launchTry
            }
            if (newSubsRaw.id < 0) {
                toast("订阅id不可为${newSubsRaw.id}\n负数id为内部使用")
                return@launchTry
            }
            val newItem = SubsItem(
                id = newSubsRaw.id,
                updateUrl = newSubsRaw.updateUrl ?: url,
                order = if (subItems.isEmpty()) 1 else (subItems.maxBy { it.order }.order + 1)
            )
            updateSubscription(newSubsRaw)
            DbSet.subsItemDao.insert(newItem)
            toast("成功添加订阅")
        } finally {
            refreshingFlow.value = false
        }

    }

    val refreshingFlow = MutableStateFlow(false)
    fun refreshSubs() = viewModelScope.launch(Dispatchers.IO) {
        if (refreshingFlow.value) return@launch
        refreshingFlow.value = true
        var errorNum = 0
        val oldSubItems = subsItemsFlow.value
        val newSubsItems = oldSubItems.mapNotNull { oldItem ->
            if (oldItem.updateUrl == null || oldItem.id < 0) return@mapNotNull null
            val oldSubsRaw = subsIdToRawFlow.value[oldItem.id]
            try {
                if (oldSubsRaw?.checkUpdateUrl != null) {
                    try {
                        val subsVersion =
                            client.get(oldSubsRaw.checkUpdateUrl).body<SubsVersion>()
                        LogUtils.d("快速检测更新成功", subsVersion)
                        if (subsVersion.id != oldSubsRaw.id) {
                            toast("${oldItem.id}:checkUpdateUrl获取id不一致")
                            return@mapNotNull null
                        }
                        if (subsVersion.version <= oldSubsRaw.version) {
                            return@mapNotNull null
                        }
                    } catch (e: Exception) {
                        LogUtils.d("快速检测更新失败", oldItem, e)
                    }
                }
                val newSubsRaw = RawSubscription.parse(
                    client.get(oldSubsRaw?.updateUrl ?: oldItem.updateUrl).bodyAsText()
                )
                if (newSubsRaw.id != oldItem.id) {
                    toast("${oldItem.id}:updateUrl获取id不一致")
                    return@mapNotNull null
                }
                if (oldSubsRaw != null && newSubsRaw.version <= oldSubsRaw.version) {
                    return@mapNotNull null
                }
                val newItem = oldItem.copy(
                    mtime = System.currentTimeMillis(),
                )
                updateSubscription(newSubsRaw)
                newItem
            } catch (e: Exception) {
                e.printStackTrace()
                errorNum++
                null
            }
        }
        if (newSubsItems.isEmpty()) {
            if (errorNum == oldSubItems.size) {
                toast("更新失败")
            } else {
                toast("暂无更新")
            }
        } else {
            DbSet.subsItemDao.update(*newSubsItems.toTypedArray())
            toast("更新 ${newSubsItems.size} 条订阅")
        }
        delay(500)
        refreshingFlow.value = false
    }

    private val appIdToOrderFlow = DbSet.clickLogDao.queryLatestUniqueAppIds().map { appIds ->
        appIds.mapIndexed { index, appId -> appId to index }.toMap()
    }

    val sortTypeFlow = storeFlow.map(viewModelScope) { s ->
        SortTypeOption.allSubObject.find { o -> o.value == s.sortType } ?: SortTypeOption.SortByName
    }
    val showSystemAppFlow = storeFlow.map(viewModelScope) { s -> s.showSystemApp }
    val showHiddenAppFlow = storeFlow.map(viewModelScope) { s -> s.showHiddenApp }
    val searchStrFlow = MutableStateFlow("")
    private val debounceSearchStrFlow = searchStrFlow.debounce(200)
        .stateIn(viewModelScope, SharingStarted.Eagerly, searchStrFlow.value)
    val appInfosFlow =
        combine(orderedAppInfosFlow.combine(showHiddenAppFlow) { appInfos, showHiddenApp ->
            if (showHiddenApp) {
                appInfos
            } else {
                appInfos.filter { a -> !a.hidden }
            }
        }.combine(showSystemAppFlow) { appInfos, showSystemApp ->
            if (showSystemApp) {
                appInfos
            } else {
                appInfos.filter { a -> !a.isSystem }
            }
        }, sortTypeFlow, appIdToOrderFlow) { appInfos, sortType, appIdToOrder ->
            when (sortType) {
                SortTypeOption.SortByAppMtime -> {
                    appInfos.sortedBy { a -> -a.mtime }
                }

                SortTypeOption.SortByTriggerTime -> {
                    appInfos.sortedBy { a -> appIdToOrder[a.id] ?: Int.MAX_VALUE }
                }

                SortTypeOption.SortByName -> {
                    appInfos
                }
            }
        }.combine(debounceSearchStrFlow) { appInfos, debounceSearchStr ->
            if (debounceSearchStr.isBlank()) {
                appInfos
            } else {
                (appInfos.filter { a -> a.name.contains(debounceSearchStr) } + appInfos.filter { a ->
                    a.id.contains(
                        debounceSearchStr
                    )
                }).distinct()
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

}