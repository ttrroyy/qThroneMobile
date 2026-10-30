package app.qthrone.mobile.group

import app.qthrone.mobile.bg.carrier.NativeHashPolicy
import app.qthrone.mobile.R
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.GroupSort
import app.qthrone.mobile.database.GroupSortAction
import app.qthrone.mobile.database.GroupSortMethod
import app.qthrone.mobile.database.ServerSortRule
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.database.SettingsMapper
import app.qthrone.mobile.ktx.Logs
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.ktx.readableMessage
import app.qthrone.mobile.outbound.Outbound
import app.qthrone.mobile.outbound.`import`.ProfileImport
import app.qthrone.mobile.route.ProviderRouting
import app.qthrone.mobile.bg.XrayGeoAssets
import app.qthrone.mobile.outbound.types.Custom
import app.qthrone.mobile.route.ProviderGeoAssets
import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject

/** GroupUpdater::refresh, importDocuments and afterUrlTest (GroupUpdater.cpp:465-683) over the Room data layer. */
internal object SubscriptionRefresh {

    const val AUTOSELECTOR = "autoselector"

    /** kInsertChunk (GroupUpdater.cpp:23). */
    private const val INSERT_CHUNK = 500

    class ImportResult(@JvmField val gid: Long, @JvmField val count: Int)

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** notice (GroupUpdater.cpp:166-176): a list of 1000 names or more collapses to one count line. */
    fun notice(names: List<String>, prefix: String, action: String): String {
        if (names.size >= 1000) return "$prefix $action ${names.size}\n"
        val sb = StringBuilder()
        for (name in names) sb.append(prefix).append(' ').append(name).append('\n')
        return sb.toString()
    }

    /** refresh (GroupUpdater.cpp:479-656); [notifyErrors]: fetch failures also go to the callbacks. */
    suspend fun refresh(gid: Long, showDiff: Boolean, notifyErrors: Boolean) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions

        val fetched = SubscriptionFetch.fetch(group.url.trim(), group.name, RequestIdentity.resolve(group))
        if (fetched.error != null) {
            if (notifyErrors) SubscriptionQueue.error(gid, fetched.error)
            return
        }
        // A body without a single profile is far likelier a broken or blocked response than an emptied subscription:
        // abort before the first write (no deletion, no update time). Parsing writes nothing, so it runs first.
        val payload = ProviderRouting.subscription(fetched.body, fetched.routing, options.routingProvider.ifBlank { "Happ" }, options.providerIgnoreImport)
        if(payload.warnings.isNotEmpty()) {
            val warning="Повреждённая маршрутизация пропущена; сохранённая маршрутизация не изменена. "+payload.warnings.distinct().joinToString("\n")
            Logs.w("${group.name}: $warning")
            if(notifyErrors) SubscriptionQueue.report(gid,"",warning,false)
        }
        val parsed = parse(payload.body)
        if (parsed.isEmpty()) {
            // GroupUpdater.cpp:746-752: panels answer a device or quota limit with an empty list and an announcement
            // saying why.
            val info = fetched.subInfo
            if (info.valid) {
                if (info.announce.isNotEmpty()) Logs.w(str(R.string.subs_announcement_from, group.name, info.announce))
                SagerDatabase.groupDao.setSubscriptionMetadata(gid, info.toJsonString())
                GroupRepo.postUpdate(gid)
            }
            val message = str(R.string.subs_empty_aborted)
            Logs.w("${group.name}: $message")
            if (notifyErrors) SubscriptionQueue.error(gid, message)
            return
        }
        // Android-only (#53): filtered servers never enter the sink, so the diff deletes the ones already stored.
        val servers = if (options.ignoreNameFilters) parsed else SubscriptionNameFilter.apply(parsed, options, group.name)
        if (servers.isEmpty()) {
            if (fetched.subInfo.valid) {
                SagerDatabase.groupDao.setSubscriptionMetadata(gid, fetched.subInfo.toJsonString())
                GroupRepo.postUpdate(gid)
            }
            val message = str(R.string.subs_filtered_all_aborted, parsed.size)
            Logs.w("${group.name}: $message")
            if (notifyErrors) SubscriptionQueue.error(gid, message)
            return
        }

        ProviderRouteStore.apply(gid, payload.commands,servers.all { it.isXrayFullConfig() })
        val fullConfigs = servers.filterIsInstance<Custom>()
            .filter { it.isXrayFullConfig() }.map { it.config }
        if (fullConfigs.isNotEmpty()) {
            val needed = XrayGeoAssets.needed(fullConfigs)
            val provider = GroupRepo.get(gid)?.subOptions
            if (provider != null && provider.routingProvider.isNotBlank()) {
                val document = JsonInput.parseObjectOrNull(provider.providerRouting) ?: JsonObject()
                runCatching { ProviderGeoAssets.prepare(gid,document) }.onFailure { SubscriptionQueue.error(gid,it.readableMessage) }
            } else {
            val failures = XrayGeoAssets.ensure(needed)
            if (failures.isNotEmpty()) SubscriptionQueue.error(gid,failures.values.joinToString("\n"))
            }
        }
        SagerDatabase.groupDao.setSubscriptionInfo(gid, System.currentTimeMillis() / 1000, fetched.subInfo.toJsonString())
        // An unnamed subscription takes the provider's title, or the link's host; a name the user typed stays.
        GroupRepo.get(gid)?.takeIf { it.name.isBlank() }?.let { latest ->
            latest.name = fetched.subInfo.title.ifBlank { runCatching { java.net.URI(latest.url.trim()).host }.getOrNull().orEmpty() }
            if (latest.name.isNotBlank()) SagerDatabase.groupDao.update(latest)
        }
        // a new server period may change when the update worker has to wake
        if (DataStore.subServerInterval && fetched.subInfo.serverInterval != group.subInfo.serverInterval) SubscriptionScheduler.schedule()
        GroupRepo.postUpdate(gid)

        // Auto selectors are local state, not servers the remote sent: keep them out of the diff.
        val sticky = ProfileManager.members(gid).withIndex()
            .filter { it.value.type == AUTOSELECTOR }
            .map { it.index to it.value.id }
        val stickyIds = sticky.mapTo(HashSet()) { it.second }
        fun members(): List<ProxyEntity> = ProfileManager.members(gid).filter { it.id !in stickyIds }
        val previousNative = members().map { it.outbound }.filterIsInstance<app.qthrone.mobile.outbound.types.Qwdtt>()
        // Taken before anything is deleted, so route rules can follow a replaced node by its name.
        val routeNames = SubscriptionRouteRebind.referencedNames(members())
        val removed = ArrayList<Long>()

        // Ids a running auto selector can no longer trust: deleted, or same id with new settings.
        val disturbed = ArrayList<Long>()
        var cleared = false
        if (DataStore.subClear) {
            Logs.i(str(R.string.subs_clearing))
            var doomed = members()
            if (options.keepWorking) doomed = doomed.filter { !it.isWorking() }
            val outcome = ProfileManager.batchDeleteProfiles(doomed.map { it.id })
            if (!outcome.ok) {
                val message = str(R.string.subs_db_error_retry)
                Logs.e(message)
                SubscriptionQueue.error(gid, message)
                return
            }
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)
            // A survivor still belongs to the subscription: fall through to the diff.
            cleared = members().isEmpty()
        }

        val old = ArrayList<OldEntry>()
        val working = HashSet<Long>()
        if (!cleared) {
            for (profile in members()) {
                old.add(OldEntry(profile.id, contentKey(profile), identityKey(profile), profile.outbound.displayTypeAndName()))
                if (options.keepWorking && profile.isWorking()) working.add(profile.id)
            }
        }
        val index = ContentIndex(old)
        val sink = ImportSink(gid, if (cleared) null else index)

        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (outbound in servers) {
            if(outbound is app.qthrone.mobile.outbound.types.Qwdtt) {
                NativeHashPolicy.preserve(outbound,previousNative)
            }
            sink.add(outbound)
        }
        sink.flush()
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))

        var changeText: String
        if (cleared) {
            changeText = if (sink.entries.size >= 1000) {
                "[+] ${sink.entries.size} profiles\n"
            } else {
                sink.entries.joinToString("") { "[+] ${it.display}\n" }
            }
        } else {
            val plan = SubscriptionReconcile.reconcile(old, sink.entries, index)
            for ((oldId, newId) in plan.updates) {
                val newEnt = ProfileManager.getProfile(newId)
                if (newEnt != null && ProfileManager.getProfile(oldId) != null) {
                    // The old row keeps its id, test results and traffic.
                    val incoming=newEnt.outbound
                    val previous=ProfileManager.getProfile(oldId)?.outbound
                    if(incoming is app.qthrone.mobile.outbound.types.Qwdtt && previous is app.qthrone.mobile.outbound.types.Qwdtt) {
                        incoming.deviceId=previous.deviceId
                        incoming.hashSource=previous.hashSource
                        SagerDatabase.proxyDao.updateOutbound(oldId,newEnt.type,newEnt.name,incoming.exportToJson().toCompact())
                    } else if(incoming is app.qthrone.mobile.outbound.types.OlcRtc && previous is app.qthrone.mobile.outbound.types.OlcRtc) {
                        incoming.deviceId=previous.deviceId
                        SagerDatabase.proxyDao.updateOutbound(oldId,newEnt.type,newEnt.name,incoming.exportToJson().toCompact())
                    } else SagerDatabase.proxyDao.updateOutbound(oldId, newEnt.type, newEnt.name, newEnt.outboundJson)
                }
                disturbed.add(oldId)
            }

            val stale = ArrayList<Long>()
            val keptWorking = ArrayList<Long>()
            for (id in plan.stale) (if (id in working) keptWorking else stale).add(id)

            val previousOrder = ProfileManager.memberIds(gid)
            val order = ArrayList(plan.order)
            for ((position, id) in sticky) order.add(minOf(position, order.size), id)

            val outcome = ProfileManager.batchDeleteProfiles(stale)
            if (!outcome.ok) dbError(gid)
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)

            // Survivors of the deletion (the running profile, working ones) return to their previous position.
            fun restore(id: Long): Boolean {
                if (id in order) return false
                val position = previousOrder.indexOf(id)
                order.add(if (position < 0) order.size else minOf(position, order.size), id)
                return true
            }
            val noticeKept = StringBuilder()
            for (id in outcome.kept) {
                if (!restore(id)) continue
                ProfileManager.getProfile(id)?.let {
                    noticeKept.append("[=] ").append(it.outbound.displayTypeAndName()).append('\n')
                }
            }
            val workingNames = ArrayList<String>()
            for (id in keptWorking) {
                if (!restore(id)) continue
                ProfileManager.getProfile(id)?.let { workingNames.add(it.outbound.displayTypeAndName()) }
            }
            ProfileManager.setOrder(gid, order)

            var deletedNames: List<String> = plan.deleted
            if (keptWorking.isNotEmpty()) {
                val doomed = stale.toHashSet()
                deletedNames = old.filter { it.id in doomed }.map { it.display }
            }

            changeText = "\n" + str(
                R.string.subs_change_summary,
                plan.added.size, notice(plan.added, "[+]", "added"),
                plan.updates.size, notice(plan.updated, "[~]", "updated"),
                deletedNames.size, notice(deletedNames, "[-]", "deleted"),
            )
            if (noticeKept.isNotEmpty()) changeText += "\n" + str(R.string.subs_still_in_use, noticeKept.toString())
            if (workingNames.isNotEmpty()) {
                changeText += "\n" + str(R.string.subs_working_kept, notice(workingNames, "[=]", "kept"))
            }
            if (plan.added.isEmpty() && plan.updates.isEmpty() && deletedNames.isEmpty() && keptWorking.isEmpty()) {
                changeText = str(R.string.subs_nothing)
            }
        }

        val filteredOut = parsed.size - servers.size
        if (filteredOut > 0) changeText += "\n" + str(R.string.subs_filtered_out, filteredOut) + "\n"
        val filtered = SubscriptionFilters.removeFlagged(gid, members(), options)
        changeText += filtered.report
        disturbed.addAll(filtered.deleted)
        removed.addAll(filtered.deleted)
        SubscriptionRouteRebind.rebind(gid, removed, routeNames)

        SagerDatabase.proxyDao.clearGroupTestResults(group.id)
        Logs.i("<<<<<<<< " + str(R.string.subs_change_of_log, group.name) + "\n" + changeText)
        SubscriptionQueue.report(
            gid,
            str(R.string.subs_change_of, group.name),
            changeText.trim().ifEmpty { str(R.string.subs_nothing) },
            showDiff && DataStore.subShowChangePopup,
        )
        SubscriptionQueue.groupChanged(gid, disturbed)

        if (options.effectiveSortRule == ServerSortRule.NAME) {
            GroupSort.sortProfiles(gid,
                GroupSortAction(GroupSortMethod.BY_NAME))
        }
        if (!notifyErrors && DataStore.subNotifyUpdate && changeText.trim() != str(R.string.subs_nothing)) {
            SubscriptionNotifications.updated(group)
        }
        if (options.urlTest) SubscriptionQueue.requestUrlTest(gid, members().map { it.id })
    }

    /** importDocuments (GroupUpdater.cpp:465-477): a plain import into [gid] (<= 0: the current group). */
    suspend fun importDocuments(gid: Long, documents: List<String>): ImportResult {
        val target = if (gid > 0) gid else GroupRepo.currentId()
        val sink = ImportSink(target, null)
        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (document in documents) {
            val payload = ProviderRouting.subscription(document,ignoreRouting = GroupRepo.get(target)?.subOptions?.providerIgnoreImport == true)
            for (outbound in parse(payload.body)) sink.add(outbound)
            ProviderRouteStore.apply(target, payload.commands)
        }
        sink.flush()
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))
        val count = sink.entries.count { it.id > 0 }
        Logs.i(str(R.string.subs_imported, count))
        return ImportResult(target, count)
    }

    /** afterUrlTest (GroupUpdater.cpp:669-683). */
    suspend fun afterUrlTest(gid: Long) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions
        if (!options.urlTest || (!options.removeUnavailable && options.effectiveSortRule != ServerSortRule.PING)) return

        val result = SubscriptionFilters.removeUnavailableAndSort(group, options)
        if (result.report.isNotEmpty()) {
            val heading = str(R.string.subs_after_url_test, group.name)
            Logs.i("<<<<<<<< $heading${result.report}")
            SubscriptionQueue.report(gid, heading.removeSuffix(":"), result.report.trim(), false)
        }
        SubscriptionQueue.groupChanged(gid, result.deleted)
    }

    internal fun dbError(gid: Long) {
        val message = str(R.string.subs_db_error_corrupted)
        Logs.e(message)
        SubscriptionQueue.error(gid, message)
    }

    private fun parse(body: String): List<Outbound> {
        val result = ProfileImport.parse(body, SettingsMapper.xrayVlessPreference())
        for (message in result.messages) Logs.w(message)
        return result.outbounds
    }

    /** The stored JSON of a type this build cannot parse is kept as is (re-exporting would drop its fields). */
    private fun contentKey(profile: ProxyEntity): String = SubscriptionReconcile.contentKey(
        if (profile.outbound.invalid) profile.outboundJson else profile.outbound.exportToJson().toCompact()
    )

    private fun identityKey(profile: ProxyEntity): String =
        subscriptionIdentityKey(profile.outbound)

    private fun subscriptionIdentityKey(outbound: Outbound): String = SubscriptionReconcile.identityKey(
        outbound.type,
        if (outbound is app.qthrone.mobile.outbound.types.Qwdtt) NativeHashPolicy.subscriptionIdentity(outbound)
        else outbound.exportIdentity().toCompact()
    )

    /** ImportSink (GroupUpdater.cpp:112-154): inserts in chunks, claiming old ids by identical content first. */
    private class ImportSink(private val gid: Long, private val index: ContentIndex?) {
        val entries = ArrayList<NewEntry>()
        private val chunk = ArrayList<Outbound>()
        private val entryIndex = ArrayList<Int>()

        suspend fun add(outbound: Outbound) {
            if(outbound is app.qthrone.mobile.outbound.types.Qwdtt && !outbound.deviceIdProvided) {
                outbound.deviceId=NativeHashPolicy.defaultDeviceId(DeviceDetails.get().hwid)
                outbound.deviceIdProvided=true
            }
            val entry = NewEntry(outbound.displayTypeAndName())
            if (index != null) {
                entry.content = SubscriptionReconcile.contentKey(outbound.exportToJson().toCompact())
                index.noteArrival(entry.content)
                entry.contentKnown = index.knows(entry.content)
                val oldId = index.claim(entry.content)
                if (oldId != null) {
                    entry.id = oldId
                    entry.reused = true
                    entries.add(entry)
                    return
                }
                if (!entry.contentKnown) {
                    entry.identity = subscriptionIdentityKey(outbound)
                }
            }
            entryIndex.add(entries.size)
            entries.add(entry)
            chunk.add(outbound)
            if (chunk.size >= INSERT_CHUNK) flush()
        }

        suspend fun flush() {
            if (chunk.isEmpty()) return
            try {
                val rows = ProfileManager.addProfileBatch(chunk, gid)
                rows.forEachIndexed { i, row -> entries[entryIndex[i]].id = row.id }
            } catch (e: Exception) {
                Logs.w(e)
            }
            chunk.clear()
            entryIndex.clear()
        }
    }
}
