package org.fossify.gallery.activities

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.VIEW_TYPE_GRID
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.FileDirItem
import org.fossify.commons.views.MyGridLayoutManager
import org.fossify.gallery.R
import org.fossify.gallery.adapters.MediaAdapter
import org.fossify.gallery.ai.*
import org.fossify.gallery.asynctasks.GetMediaAsynctask
import org.fossify.gallery.databinding.ActivitySearchBinding
import org.fossify.gallery.extensions.*
import org.fossify.gallery.helpers.GridSpacingItemDecoration
import org.fossify.gallery.helpers.MediaFetcher
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.helpers.VIDEO_PLAYER_APP
import org.fossify.gallery.helpers.VIDEO_PLAYER_SYSTEM
import org.fossify.gallery.interfaces.MediaOperationsListener
import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import java.io.File

class SearchActivity : SimpleActivity(), MediaOperationsListener {
    override var isSearchBarEnabled = true
    
    private var mLastSearchedText = ""

    private var mCurrAsyncTask: GetMediaAsynctask? = null
    private var mAllMedia = ArrayList<ThumbnailItem>()
    private var searchJob: Job? = null
    private var useAi = false
    private var wasAiEnabled = false
    private val scopePath by lazy { intent.getStringExtra("ai_scope_path") ?: "" }

    private val binding by viewBinding(ActivitySearchBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        useAi = AiPreferences(this).enabled
        wasAiEnabled = useAi
        setupOptionsMenu()
        setupEdgeToEdge(
            padTopSystem = listOf(binding.searchMenu),
            padBottomImeAndSystem = listOf(binding.searchGrid)
        )
        binding.searchEmptyTextPlaceholder.setTextColor(getProperTextColor())
        binding.searchAiStatus.setTextColor(getProperTextColor())
        binding.searchAiStatus.setOnClickListener { startActivity(Intent(this, AiSearchSettingsActivity::class.java)) }
        getAllMedia()
        binding.searchFastscroller.updateColors(getProperPrimaryColor())
    }

    override fun onResume() {
        super.onResume()
        updateMenuColors()
        val enabled = AiPreferences(this).enabled
        if (enabled && !wasAiEnabled) useAi = true
        if (!enabled) useAi = false
        wasAiEnabled = enabled
        updateAiMenu()
        textChanged(mLastSearchedText)
        startAsyncTask(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        mCurrAsyncTask?.stopFetching()
    }

    private fun setupOptionsMenu() {
        binding.searchMenu.requireToolbar().inflateMenu(R.menu.menu_search)
        binding.searchMenu.toggleHideOnScroll(true)
        binding.searchMenu.setupMenu()
        binding.searchMenu.toggleForceArrowBackIcon(true)
        binding.searchMenu.focusView()
        updateAiMenu()

        binding.searchMenu.onNavigateBackClickListener = {
            if (binding.searchMenu.getCurrentQuery().isEmpty()) {
                finish()
            } else {
                binding.searchMenu.closeSearch()
            }
        }

        binding.searchMenu.onSearchTextChangedListener = { text ->
            mLastSearchedText = text
            textChanged(text)
        }

        binding.searchMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.toggle_filename -> toggleFilenameVisibility()
                R.id.ai_search_toggle -> {
                    useAi = !useAi
                    updateAiMenu()
                    textChanged(mLastSearchedText)
                }
                R.id.ai_search_settings -> startActivity(Intent(this, AiSearchSettingsActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.searchMenu.updateColors()
    }

    private fun updateAiMenu() {
        binding.searchMenu.requireToolbar().menu.findItem(R.id.ai_search_toggle)?.apply {
            isVisible = AiPreferences(this@SearchActivity).enabled
            isChecked = useAi
        }
        binding.searchMenu.updateHintText(getString(if (useAi) R.string.ai_search_hint else org.fossify.commons.R.string.search_files))
    }

    private fun textChanged(text: String) {
        searchJob?.cancel()
        val ai = useAi && text.isNotBlank()
        val snapshot = mAllMedia.toList()
        searchJob = lifecycleScope.launch {
            try {
                binding.searchAiStatus.beVisibleIf(useAi)
                if (ai) {
                    // Clear stale matches while a newer query is pending.
                    getMediaAdapter()?.updateMedia(arrayListOf())
                    binding.searchEmptyTextPlaceholder.beGone()
                    binding.searchAiStatus.setText(R.string.ai_searching)
                    delay(400)
                }
                val visibleMedia = withContext(Dispatchers.Default) {
                    val folders = HashMap<String, Boolean>()
                    val noMedia = HashMap<String, Boolean>()
                    val excluded = config.excludedFolders
                    val included = config.includedFolders
                    snapshot.filterIsInstance<Medium>().filter {
                        ensureActive()
                        !AiMediaReader.isProtected(applicationContext, it.parentPath) && !it.getIsInRecycleBin() &&
                            (!ai || (!it.name.startsWith('.') && !it.path.contains("/."))) &&
                            folders.getOrPut(it.parentPath) {
                                it.parentPath.shouldFolderBeVisible(excluded, included, !ai && config.shouldShowHidden, noMedia) { path, hidden -> noMedia[path] = hidden }
                            }
                    }
                }
                if (ai) {
                    if (!AiModelStore.ready(applicationContext)) {
                        binding.searchAiStatus.setText(R.string.ai_search_model_missing)
                        return@launch
                    }
                    val indexed = withContext(Dispatchers.IO) { AiIndexDatabase.get(applicationContext).stats().indexed }
                    if (indexed == 0) {
                        binding.searchAiStatus.setText(R.string.ai_search_empty_index)
                        return@launch
                    }
                    val query = AiEmbeddingEngine.text(applicationContext, text.trim())
                    val matches = withContext(Dispatchers.IO) {
                        val coroutineContext = currentCoroutineContext()
                        AiIndexDatabase.get(applicationContext).search(query, visibleMedia.map { it.path }.toHashSet()) { coroutineContext.ensureActive() }
                    }
                    val byPath = visibleMedia.associateBy { it.path }
                    val results = ArrayList<ThumbnailItem>(matches.mapNotNull { byPath[it.path] })
                    showResults(results)
                    binding.searchAiStatus.text = getString(R.string.ai_search_results, results.size)
                } else {
                    val grouped = withContext(Dispatchers.Default) {
                        val filtered = ArrayList(visibleMedia.filter { it.name.contains(text, true) })
                        filtered.sortBy { !it.name.startsWith(text, true) }
                        MediaFetcher(applicationContext).groupMedia(filtered, scopePath)
                    }
                    showResults(grouped)
                    if (useAi) binding.searchAiStatus.setText(if (AiModelStore.ready(applicationContext)) R.string.ai_search_hint else R.string.ai_search_model_missing)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ai) {
                    showResults(arrayListOf())
                    binding.searchAiStatus.text = getString(R.string.ai_search_error, e.message ?: "Try again")
                }
            }
        }
    }

    private fun showResults(media: ArrayList<ThumbnailItem>) {
        binding.searchEmptyTextPlaceholder.setText(org.fossify.commons.R.string.no_items_found)
        binding.searchEmptyTextPlaceholder.beVisibleIf(media.isEmpty())
        handleGridSpacing(media)
        getMediaAdapter()?.updateMedia(media)
    }

    private fun setupAdapter() {
        val currAdapter = binding.searchGrid.adapter
        if (currAdapter == null) {
            MediaAdapter(this, mAllMedia, this, false, false, "", binding.searchGrid) {
                if (it is Medium) {
                    itemClicked(it.path)
                }
            }.apply {
                binding.searchGrid.adapter = this
            }
            setupLayoutManager()
            handleGridSpacing(mAllMedia)
        } else if (mLastSearchedText.isEmpty()) {
            (currAdapter as MediaAdapter).updateMedia(mAllMedia)
            handleGridSpacing(mAllMedia)
        } else {
            textChanged(mLastSearchedText)
        }

        setupScrollDirection()
        textChanged(mLastSearchedText)
    }

    private fun handleGridSpacing(media: ArrayList<ThumbnailItem>) {
        val viewType = config.getFolderViewType(SHOW_ALL)
        if (viewType == VIEW_TYPE_GRID) {
            if (binding.searchGrid.itemDecorationCount > 0) {
                binding.searchGrid.removeItemDecorationAt(0)
            }

            val spanCount = config.mediaColumnCnt
            val spacing = config.thumbnailSpacing
            val decoration = GridSpacingItemDecoration(spanCount, spacing, config.scrollHorizontally, config.fileRoundedCorners, media, true)
            binding.searchGrid.addItemDecoration(decoration)
        }
    }

    private fun getMediaAdapter() = binding.searchGrid.adapter as? MediaAdapter

    private fun toggleFilenameVisibility() {
        config.displayFileNames = !config.displayFileNames
        getMediaAdapter()?.updateDisplayFilenames(config.displayFileNames)
    }

    private fun itemClicked(path: String) {
        if (!path.isVideoFast()) {
            openInViewPager(path)
            return
        }

        when (config.videoPlayerType) {
            VIDEO_PLAYER_SYSTEM -> openPath(path = path, forceChooser = false)
            VIDEO_PLAYER_APP -> if (config.gestureVideoPlayer) launchGesturePlayer(path) else openInViewPager(path)
            else -> openInViewPager(path) // unreachable by design
        }
    }

    private fun openInViewPager(path: String) {
        Intent(this, ViewPagerActivity::class.java).apply {
            putExtra(PATH, path)
            putExtra(SHOW_ALL, false)
            startActivity(this)
        }
    }

    private fun setupLayoutManager() {
        val viewType = config.getFolderViewType(SHOW_ALL)
        if (viewType == VIEW_TYPE_GRID) {
            setupGridLayoutManager()
        } else {
            setupListLayoutManager()
        }
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.searchGrid.layoutManager as MyGridLayoutManager
        if (config.scrollHorizontally) {
            layoutManager.orientation = RecyclerView.HORIZONTAL
            binding.searchGrid.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            layoutManager.orientation = RecyclerView.VERTICAL
            binding.searchGrid.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        layoutManager.spanCount = config.mediaColumnCnt
        val adapter = getMediaAdapter()
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter?.isASectionTitle(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.searchGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        layoutManager.orientation = RecyclerView.VERTICAL
    }

    private fun setupScrollDirection() {
        val viewType = config.getFolderViewType(SHOW_ALL)
        val scrollHorizontally = config.scrollHorizontally && viewType == VIEW_TYPE_GRID
        binding.searchFastscroller.setScrollVertically(!scrollHorizontally)
    }

    private fun getAllMedia() {
        getCachedMedia(scopePath) {
            if (it.isNotEmpty()) {
                mAllMedia = it.clone() as ArrayList<ThumbnailItem>
            }
            runOnUiThread {
                setupAdapter()
            }
            startAsyncTask(true)
        }
    }

    private fun startAsyncTask(updateItems: Boolean) {
        mCurrAsyncTask?.stopFetching()
        mCurrAsyncTask = GetMediaAsynctask(applicationContext, scopePath, showAll = scopePath.isEmpty()) {
            mAllMedia = it.clone() as ArrayList<ThumbnailItem>
            if (updateItems) {
                textChanged(mLastSearchedText)
            }
        }

        mCurrAsyncTask!!.execute()
    }

    override fun refreshItems() {
        startAsyncTask(true)
    }

    override fun tryDeleteFiles(fileDirItems: ArrayList<FileDirItem>, skipRecycleBin: Boolean) {
        val filtered = fileDirItems.filter { File(it.path).isFile && it.path.isMediaFile() } as ArrayList
        if (filtered.isEmpty()) {
            return
        }

        if (config.useRecycleBin && !skipRecycleBin && !filtered.first().path.startsWith(recycleBinPath)) {
            val movingItems = resources.getQuantityString(org.fossify.commons.R.plurals.moving_items_into_bin, filtered.size, filtered.size)
            toast(movingItems)

            movePathsInRecycleBin(filtered.map { it.path } as ArrayList<String>) {
                if (it) {
                    deleteFilteredFiles(filtered)
                } else {
                    toast(org.fossify.commons.R.string.unknown_error_occurred)
                }
            }
        } else {
            val deletingItems = resources.getQuantityString(org.fossify.commons.R.plurals.deleting_items, filtered.size, filtered.size)
            toast(deletingItems)
            deleteFilteredFiles(filtered)
        }
    }

    private fun deleteFilteredFiles(filtered: ArrayList<FileDirItem>) {
        deleteFiles(filtered) {
            if (!it) {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                return@deleteFiles
            }

            mAllMedia.removeAll { filtered.map { it.path }.contains((it as? Medium)?.path) }

            ensureBackgroundThread {
                val useRecycleBin = config.useRecycleBin
                filtered.forEach {
                    if (it.path.startsWith(recycleBinPath) || !useRecycleBin) {
                        deleteDBPath(it.path)
                    }
                }
            }
        }
    }

    override fun selectedPaths(paths: ArrayList<String>) {}

    override fun updateMediaGridDecoration(media: ArrayList<ThumbnailItem>) {}
}
