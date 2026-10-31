package app.qthrone.mobile.ui.profiles

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.qthrone.mobile.R
import app.qthrone.mobile.aidl.TrafficData
import app.qthrone.mobile.bg.BaseService
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.GroupRepo
import app.qthrone.mobile.database.ProfileManager
import app.qthrone.mobile.database.ProxyEntity
import app.qthrone.mobile.database.ProxyGroup
import app.qthrone.mobile.ktx.FixedGridLayoutManager
import app.qthrone.mobile.ktx.runOnDefaultDispatcher
import app.qthrone.mobile.ui.ConfigurationFragment
import app.qthrone.mobile.ui.test.RowPhase
import app.qthrone.mobile.ui.test.TestUiState
import app.qthrone.mobile.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The profiles of one group. The database is the source of truth: every change (in this process through the
 * repository listeners, from `:bg` through [ProfilesDbWatcher]) reloads the group and applies the difference.
 * Only the live traffic counters, the drag order and profiles awaiting an undo live here.
 */
internal class ProfileListAdapter(private val fragment: ProfileListFragment) :
    RecyclerView.Adapter<ProfileRowHolder>(),
    ProfileManager.Listener,
    GroupRepo.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    companion object {
        /** Rebind in place (no change animation). */
        const val PAYLOAD_CONTENT = 1

        /** Only the selection / running / multi-select state changed. */
        const val PAYLOAD_STATE = 2

        /** Above this many rows a structural change is not animated. */
        private const val DIFF_LIMIT = 2000

        private const val AUTO_SELECTOR = "autoselector"
    }

    val groupId = fragment.groupId
    val host: ConfigurationFragment? get() = fragment.host

    /** The compact card of double column mode. */
    val isCompact: Boolean get() = fragment.isCompact

    /** Cards side by side: a grid row pairs their heights. */
    private val multiColumn: Boolean get() = fragment.isMultiColumn

    var group: ProxyGroup? = null
        private set
    val testItemsToShow: Int get() = group?.testItemsToShow ?: 0

    /** The group's order as stored. */
    var memberIds: List<Long> = emptyList()
        private set
    private var entities = HashMap<Long, ProxyEntity>()

    /** The shown rows: [memberIds] without the filtered and the undo-pending ones. */
    private val ids = ArrayList<Long>()
    val displayedIds: List<Long> get() = ids

    private var query = ""
    val isFiltered: Boolean get() = query.isNotBlank()

    private val hidden = HashSet<Long>()
    private var testState: TestUiState? = null
    private var phases: Map<Long, RowPhase> = emptyMap()
    private val pendingTraffic = HashSet<Long>()

    var loaded = false
        private set
    private var reloadJob: Job? = null
    private var reloadAgain = false
    var dragging = false
        private set
    private var orderChanged = false

    init {
        setHasStableIds(true)
    }

    fun phaseOf(id: Long): RowPhase? = phases[id]

    // ------------------------------------------------------------------------------------------------ adapter

    override fun getItemCount() = ids.size

    override fun getItemId(position: Int) = ids[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ProfileRowHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.layout_profile, parent, false), this
    )

    override fun onBindViewHolder(holder: ProfileRowHolder, position: Int) {
        entities[ids[position]]?.let(holder::bind)
    }

    override fun onBindViewHolder(holder: ProfileRowHolder, position: Int, payloads: List<Any>) {
        val profile = entities[ids[position]] ?: return
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE } &&
            holder.boundRows != null && holder.profile.id == profile.id
        ) {
            holder.bindState()
        } else {
            holder.bind(profile)
        }
    }

    override fun onViewRecycled(holder: ProfileRowHolder) {
        holder.onRecycled()
    }

    override fun onViewAttachedToWindow(holder: ProfileRowHolder) {
        super.onViewAttachedToWindow(holder)
        val profile = entities[holder.itemId] ?: return
        if (holder.boundTx == profile.tx && holder.boundRx == profile.rx) return
        if (fragment.isScrolling) pendingTraffic.add(profile.id) else notifyId(profile.id, PAYLOAD_CONTENT)
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** Reloads the group from the database; calls made while a reload runs (or during a drag) coalesce into one. */
    fun reload() {
        if (dragging || reloadJob?.isActive == true) {
            reloadAgain = true
            return
        }
        val scope = fragment.scope ?: return
        reloadJob = scope.launch(Dispatchers.Main.immediate) {
            do {
                reloadAgain = false
                val (loadedGroup, members) = withContext(Dispatchers.IO) {
                    GroupRepo.get(groupId) to ProfileManager.members(groupId)
                }
                // a removed group's page goes away with the tabs
                if (loadedGroup == null) return@launch
                if (dragging) {
                    reloadAgain = true
                    return@launch
                }
                apply(loadedGroup, members)
            } while (reloadAgain)
        }
    }

    private fun apply(newGroup: ProxyGroup, members: List<ProxyEntity>) {
        val displayChanged = group?.let { it.testItemsToShow != newGroup.testItemsToShow } == true
        group = newGroup
        val changed = HashSet<Long>()
        val next = HashMap<Long, ProxyEntity>(members.size * 2)
        for (entity in members) {
            val old = entities[entity.id]
            if (old != null) {
                // the service reports traffic live; the table only has its last flush
                if (old.rx > entity.rx) entity.rx = old.rx
                if (old.tx > entity.tx) entity.tx = old.tx
                if (old == entity) {
                    next[entity.id] = old
                    continue
                }
                changed.add(entity.id)
            }
            next[entity.id] = entity
        }
        entities = next
        memberIds = members.map { it.id }
        hidden.retainAll(next.keys)
        val oldPhases = phases
        phases = computePhases()
        if (phases != oldPhases) memberIds.filterTo(changed) { oldPhases[it] != phases[it] }
        val first = !loaded
        loaded = true
        publish(changed, displayChanged)
        fragment.showEmpty(memberIds.isEmpty())
        if (first) fragment.onFirstLoad()
    }

    private fun visibleIds(): List<Long> {
        val filter = query.trim()
        val hideAutoSelectors = host?.hideAutoSelectors == true
        return memberIds.filter { id ->
            val entity = entities[id]
            id !in hidden && (!hideAutoSelectors || entity?.type != AUTO_SELECTOR) &&
                (filter.isEmpty() || entity?.matches(filter) == true)
        }
    }

    private fun ProxyEntity.matches(filter: String) = displayName().contains(filter, ignoreCase = true) ||
        displayProtocol().contains(filter, ignoreCase = true) || displayAddress().contains(filter, ignoreCase = true)

    @SuppressLint("NotifyDataSetChanged")
    private fun publish(changed: Set<Long>, all: Boolean) {
        val next = visibleIds()
        if (next == ids) {
            if (all) {
                notifyItemRangeChanged(0, ids.size, PAYLOAD_CONTENT)
            } else if (changed.isNotEmpty()) {
                ids.forEachIndexed { index, id -> if (id in changed) notifyItemChanged(index, PAYLOAD_CONTENT) }
            }
            return
        }
        val atTop = !fragment.list.canScrollVertically(-1)
        val old = ArrayList(ids)
        ids.clear()
        ids.addAll(next)
        // grid rows re-pair after any insert/remove, which changes the neighbour alignment
        if (multiColumn || old.isEmpty() || old.size + next.size > DIFF_LIMIT) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition] == next[newPosition]
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                !all && old[oldPosition] !in changed

            override fun getChangePayload(oldPosition: Int, newPosition: Int): Any = PAYLOAD_CONTENT
        }).dispatchUpdatesTo(this)
        // A list at the top stays at the top while a sort moves its first row away.
        if (atTop) fragment.list.scrollToPosition(0)
    }

    fun setQuery(text: String) {
        if (text == query) return
        query = text
        if (loaded) publish(emptySet(), false)
    }

    // ------------------------------------------------------------------------------------------------ row state

    fun notifyId(id: Long, payload: Int) {
        val index = ids.indexOf(id)
        if (index >= 0) notifyItemChanged(index, payload)
    }

    /** Selection or running state changed for [changedIds] (null = every row). */
    fun notifyState(changedIds: Collection<Long>?) {
        if (changedIds == null) {
            notifyItemRangeChanged(0, ids.size, PAYLOAD_STATE)
        } else {
            changedIds.forEach { notifyId(it, PAYLOAD_STATE) }
        }
    }

    fun notifyAllContent() = notifyItemRangeChanged(0, ids.size, PAYLOAD_CONTENT)

    /** Queued / testing rows of a running session; results arrive through the database. */
    fun onTestState(state: TestUiState) {
        testState = state
        val old = phases
        phases = computePhases()
        if (phases == old) return
        ids.forEachIndexed { index, id -> if (old[id] != phases[id]) notifyItemChanged(index, PAYLOAD_CONTENT) }
        // :bg stored the result before reporting it: show it now rather than at the next table reload
        val finished = old.keys.filter { it !in phases }
        if (finished.isNotEmpty()) refreshEntities(finished)
    }

    private fun refreshEntities(profileIds: List<Long>) {
        val scope = fragment.scope ?: return
        scope.launch(Dispatchers.Main.immediate) {
            val fresh = withContext(Dispatchers.IO) { ProfileManager.getProfiles(profileIds) }
            if (dragging) return@launch
            for (entity in fresh) {
                val old = entities[entity.id] ?: continue
                if (entity.groupId != groupId) continue
                if (old.rx > entity.rx) entity.rx = old.rx
                if (old.tx > entity.tx) entity.tx = old.tx
                if (old == entity) continue
                entities[entity.id] = entity
                notifyId(entity.id, PAYLOAD_CONTENT)
            }
        }
    }

    private fun computePhases(): Map<Long, RowPhase> {
        val state = testState
        if (state == null || !state.running) return emptyMap()
        val map = HashMap<Long, RowPhase>()
        for (id in memberIds) {
            val row = state.rows[id] ?: continue
            if (row.phase != RowPhase.DONE) map[id] = row.phase
        }
        return map
    }

    /** The optional rows of the other cards of [position]'s grid row. */
    fun neighbourRows(position: Int): Int {
        if (position == RecyclerView.NO_POSITION || !multiColumn) return 0
        val layoutManager = fragment.list.layoutManager as? FixedGridLayoutManager ?: return 0
        val span = layoutManager.spanCount
        val start = layoutManager.rowIndexOf(position) * span
        val end = minOf(start + span, ids.size)
        var rows = 0
        for (i in start until end) {
            if (i == position) continue
            val profile = entities[ids[i]] ?: continue
            rows = rows or RowContent.of(fragment.requireContext(), profile, this).rows
        }
        return rows
    }

    fun refreshRowNeighbours(position: Int) {
        if (position == RecyclerView.NO_POSITION || !multiColumn) return
        val layoutManager = fragment.list.layoutManager as? FixedGridLayoutManager ?: return
        val span = layoutManager.spanCount
        val start = layoutManager.rowIndexOf(position) * span
        fragment.list.post {
            val end = minOf(start + span, ids.size)
            for (i in start until end) if (i != position) notifyItemChanged(i, PAYLOAD_CONTENT)
        }
    }

    // ------------------------------------------------------------------------------------------------ traffic

    private fun applyTraffic(data: List<TrafficData>) {
        for (update in data) {
            val profile = entities[update.id] ?: continue
            if (profile.tx == update.tx && profile.rx == update.rx) continue
            profile.tx = update.tx
            profile.rx = update.rx
            if (fragment.isScrolling) pendingTraffic.add(update.id) else notifyId(update.id, PAYLOAD_CONTENT)
        }
    }

    fun flushPendingTraffic() {
        if (pendingTraffic.isEmpty()) return
        pendingTraffic.forEach { notifyId(it, PAYLOAD_CONTENT) }
        pendingTraffic.clear()
    }

    /** Before the counters are reset in the database, so the reload that follows does not keep the old values. */
    fun clearTraffic() {
        for (profile in entities.values) {
            profile.tx = 0L
            profile.rx = 0L
        }
        notifyAllContent()
    }

    // ------------------------------------------------------------------------------------------------ order

    /** Drag and drop follows the desktop: refused while the list is filtered (and while selecting or picking). */
    fun canDrag(): Boolean {
        val host = host ?: return false
        if (host.select || isFiltered || !loaded) return false
        if (!dragging && host.selection.active) return false
        return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
    }

    fun startDrag(profile: ProxyEntity) {
        dragging = true
        orderChanged = false
        fragment.undoManager?.flush()
        host?.selection?.startFromDrag(profile)
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        ids.add(to, ids.removeAt(from))
        notifyItemMoved(from, to)
        orderChanged = true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun endDrag() {
        if (!dragging) return
        dragging = false
        val moved = orderChanged
        orderChanged = false
        if (moved) {
            val order = ids.toList()
            memberIds = order + memberIds.filter { it in hidden }
            runOnDefaultDispatcher { ProfileManager.setOrder(groupId, order) }
            if (multiColumn) notifyDataSetChanged()
        }
        host?.selection?.onDragFinished(moved)
        host?.onDragEnded()
        if (reloadAgain) reload()
    }

    // ------------------------------------------------------------------------------------------------ undo delete

    /** The single-profile delete: hidden now, deleted when the undo snackbar goes away. */
    fun removeWithUndo(profile: ProxyEntity) {
        val manager = fragment.undoManager ?: return
        val index = ids.indexOf(profile.id)
        if (index < 0) return
        hidden.add(profile.id)
        publish(emptySet(), false)
        manager.remove(index to profile)
    }

    override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        actions.forEach { hidden.remove(it.second.id) }
        publish(emptySet(), false)
    }

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        runOnDefaultDispatcher {
            for (profile in profiles) ProfileManager.deleteProfile(profile.groupId, profile.id)
        }
    }

    // ------------------------------------------------------------------------------------------------ listeners

    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId == groupId) fragment.onMain { reload() }
    }

    override suspend fun onUpdated(data: List<TrafficData>) {
        fragment.onMain { applyTraffic(data) }
    }

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        // noTraffic posts announce a selection change (handled by the screen), not new data
        if (!noTraffic && profile.groupId == groupId) fragment.onMain { reload() }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId == this.groupId) fragment.onMain { reload() }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit

    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != groupId) return
        fragment.onMain {
            val displayChanged = this.group?.testItemsToShow != group.testItemsToShow
            this.group = group
            if (displayChanged) notifyAllContent()
        }
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId == this.groupId) fragment.onMain { reload() }
    }
}
