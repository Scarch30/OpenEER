// app/src/main/java/com/example/openeer/ui/library/MapSnapshotViewerActivity.kt
package com.example.openeer.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.core.DebugConfig
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.map.buildMapsUrl
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.panel.media.MediaActions
import com.example.openeer.ui.panel.media.MediaStripItem
import com.example.openeer.ui.MotherLinkInjector
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

class MapSnapshotViewerActivity : AppCompatActivity() {

    private val blockId: Long by lazy { intent.getLongExtra(EXTRA_BLOCK_ID, -1L) }
    private val routeGson by lazy { Gson() }
    private val blocksRepository by lazy { Injection.provideBlocksRepository(this) }
    private val mediaActions by lazy { MediaActions(this, blocksRepository) }
    private var openMapsAction: (() -> Unit)? = null
    private var hasShownMapsUnavailableMessage = false
    private lateinit var viewerToolbar: MaterialToolbar
    private var currentBlock: BlockEntity? = null
    private var currentChildName: String? = null
    private var linkMenuJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_snapshot_viewer)

        viewerToolbar = findViewById(R.id.viewerToolbar)
        setSupportActionBar(viewerToolbar)                         // ← attache la toolbar comme ActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        viewerToolbar.setNavigationOnClickListener { finish() }
        viewerToolbar.title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.map_snapshot_preview)

        val photoView = findViewById<PhotoView>(R.id.photoView)
        intent.getStringExtra(EXTRA_SNAPSHOT_URI)?.let { uriStr ->
            photoView.setImageURI(Uri.parse(uriStr))
        }

        setupOpenInMapsButton()
    }

    // ⬇️ C'EST ICI qu’on “gonfle” (inflate) le menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer_location, menu)
        val renameItem = menu.findItem(R.id.action_rename)
        val hasValidBlock = blockId > 0
        renameItem?.isVisible = hasValidBlock
        renameItem?.isEnabled = hasValidBlock
        val canInject = blockId > 0 && currentBlock != null
        menu.findItem(R.id.action_open_in_maps)?.isVisible = openMapsAction != null
        menu.findItem(R.id.action_inject_into_mother)?.isVisible = canInject
        menu.findItem(R.id.action_link_to_element)?.isVisible = canInject
        updateLinkedMenuItems(menu)
        return true
    }

    private fun updateLinkedMenuItems(menu: Menu) {
        val viewItem = menu.findItem(R.id.action_view_linked_items)
        val unlinkItem = menu.findItem(R.id.action_unlink)
        viewItem?.isVisible = false
        unlinkItem?.isVisible = false
        val id = blockId
        if (id <= 0) {
            linkMenuJob?.cancel()
            linkMenuJob = null
            return
        }
        linkMenuJob?.cancel()
        val job = lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { blocksRepository.getLinkCount(id) }
            if (count > 0) {
                viewItem?.isVisible = true
                viewItem?.title = getString(R.string.media_action_view_links, count)
                unlinkItem?.isVisible = true
            } else {
                viewItem?.isVisible = false
                unlinkItem?.isVisible = false
            }
        }
        linkMenuJob = job
        job.invokeOnCompletion {
            if (linkMenuJob === job) {
                linkMenuJob = null
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val canInject = blockId > 0 && currentBlock != null
        menu.findItem(R.id.action_open_in_maps)?.isVisible = openMapsAction != null
        menu.findItem(R.id.action_inject_into_mother)?.isVisible = canInject
        menu.findItem(R.id.action_link_to_element)?.isVisible = canInject
        updateLinkedMenuItems(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_rename -> {
            if (blockId > 0) {
                showRenameDialog()
            }
            true
        }
        R.id.action_open_in_maps -> {
            val action = openMapsAction
            if (action != null) {
                action()
            } else {
                showMapsUnavailableToast()
            }
            true
        }
        R.id.action_share -> { sharePlace(); true }
        R.id.action_inject_into_mother -> {
            logD { "click: blockId=$blockId" }
            injectIntoMother(); true
        }
        R.id.action_link_to_element -> { startLinkFlowForMap(); true }
        R.id.action_view_linked_items -> { openLinkedItems(); true }
        R.id.action_unlink -> { startUnlinkFlow(); true }
        R.id.action_delete -> { /* TODO: suppression */ true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        linkMenuJob?.cancel()
        linkMenuJob = null
        super.onDestroy()
    }

    private fun showRenameDialog() {
        lifecycleScope.launch {
            val currentName = withContext(Dispatchers.IO) {
                blocksRepository.getChildNameForBlock(blockId)
            }
            ChildNameDialog.show(
                context = this@MapSnapshotViewerActivity,
                initialValue = currentName,
                onSave = { newName ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        blocksRepository.setChildNameForBlock(blockId, newName)
                    }
                },
                onReset = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        blocksRepository.setChildNameForBlock(blockId, null)
                    }
                }
            )
        }
    }

    private fun sharePlace() {
        val label = intent.getStringExtra(EXTRA_PLACE_LABEL) ?: ""
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        val text = if (!lat.isNaN() && !lon.isNaN())
            "$label\nhttps://maps.google.com/?q=$lat,$lon"
        else label

        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            getString(R.string.media_action_share)
        ))
    }

    private fun setupOpenInMapsButton() {
        val button = findViewById<ExtendedFloatingActionButton>(R.id.openInMapsFab)
        button.visibility = View.GONE
        button.setOnClickListener(null)

        lifecycleScope.launch {
            val block = if (blockId > 0) {
                withContext(Dispatchers.IO) { blocksRepository.getBlock(blockId) }
            } else {
                null
            }

            val action = createMapsAction(block)
            openMapsAction = action
            currentBlock = block
            currentChildName = block?.childName

            if (action != null) {
                button.visibility = View.VISIBLE
                button.setOnClickListener { action() }
            } else {
                button.visibility = View.GONE
                if (!hasShownMapsUnavailableMessage) {
                    showMapsUnavailableToast()
                    hasShownMapsUnavailableMessage = true
                }
            }

            invalidateOptionsMenu()
        }
    }

    private fun startLinkFlowForMap() {
        val block = currentBlock ?: return
        val anchor = resolveMenuAnchor()
        val mediaUri = block.mediaUri
            ?: intent.getStringExtra(EXTRA_SNAPSHOT_URI)
            ?: ""
        val item = MediaStripItem.Image(
            blockId = block.id,
            mediaUri = mediaUri,
            mimeType = block.mimeType,
            type = block.type,
            childOrdinal = block.childOrdinal,
            childName = currentChildName
        )
        mediaActions.showLinkOnly(anchor, item)
    }

    private fun openLinkedItems() {
        if (blockId <= 0) return
        mediaActions.openLinkedItemsSheet(resolveMenuAnchor(), blockId)
    }

    private fun startUnlinkFlow() {
        if (blockId <= 0) return
        mediaActions.startUnlinkFlow(resolveMenuAnchor(), blockId) {
            invalidateOptionsMenu()
        }
    }

    private fun injectIntoMother() {
        Log.wtf("InjectMother", "canary: handler entered, blockId=${blockId}")
        val id = blockId
        if (id <= 0) {
            Log.wtf("InjectMother", "canary: ERROR")
            Toast.makeText(this, R.string.mother_injection_error, Toast.LENGTH_SHORT).show()
            logW { "toastFailureShown" }
            return
        }
        lifecycleScope.launch {
            logD { "resolveChild: id=$id" }
            Log.wtf("InjectMother", "canary: about to call repo.ensureMotherMainTextBlock")
            val result = MotherLinkInjector.inject(this@MapSnapshotViewerActivity, blocksRepository, id)
            val message = if (result is MotherLinkInjector.Result.Success) {
                R.string.mother_injection_success
            } else {
                R.string.mother_injection_error
            }
            if (result is MotherLinkInjector.Result.Success) {
                Log.wtf("InjectMother", "canary: hostTextId=${result.hostTextId}")
                Log.wtf("InjectMother", "canary: appendLinkedLine start=${result.start} end=${result.end}")
                Log.wtf("InjectMother", "canary: createInlineLink created=true")
                Log.wtf("InjectMother", "canary: SUCCESS")
            } else if (result is MotherLinkInjector.Result.Failure) {
                result.hostTextId?.let { hostId ->
                    Log.wtf("InjectMother", "canary: hostTextId=$hostId")
                }
                Log.wtf("InjectMother", "canary: createInlineLink created=false")
                Log.wtf("InjectMother", "canary: ERROR")
            }
            Toast.makeText(this@MapSnapshotViewerActivity, getString(message), Toast.LENGTH_SHORT).show()
            if (result is MotherLinkInjector.Result.Success) {
                logD { "inject.completed: host=${result.hostTextId} child=$id" }
            } else {
                logW { "toastFailureShown" }
            }
        }
    }

    private fun resolveMenuAnchor(): View {
        return if (this::viewerToolbar.isInitialized) {
            viewerToolbar
        } else {
            window?.decorView ?: findViewById(android.R.id.content)
        }
    }

    private fun createMapsAction(block: BlockEntity?): (() -> Unit)? {
        val extraLat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN).takeUnless { it.isNaN() }
        val extraLon = intent.getDoubleExtra(EXTRA_LON, Double.NaN).takeUnless { it.isNaN() }
        val extraLabel = intent.getStringExtra(EXTRA_PLACE_LABEL)?.takeIf { it.isNotBlank() }

        if (block?.type == BlockType.ROUTE) {
            val payload = block.routeJson?.let { json ->
                runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
            }
            if (payload != null) {
                val points = payload.points.map { LatLng(it.lat, it.lon) }
                val url = buildMapsUrl(points)
                if (url != null) {
                    return { openRouteInGoogleMaps(url) }
                }
                val single = payload.firstPoint()
                if (single != null) {
                    val label = extraLabel
                        ?: block.placeName?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.block_location_coordinates, single.lat, single.lon)
                    return { openCoordinateInMaps(single.lat, single.lon, label) }
                }
            }
        }

        val lat = extraLat ?: block?.lat
        val lon = extraLon ?: block?.lon
        if (lat != null && lon != null) {
            val label = extraLabel
                ?: block?.placeName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.block_location_coordinates, lat, lon)
            return { openCoordinateInMaps(lat, lon, label) }
        }

        return null
    }

    private fun openCoordinateInMaps(lat: Double, lon: Double, label: String) {
        val encodedLabel = Uri.encode(label)
        val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encodedLabel)")
        val pm = packageManager

        var launched = false
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (geoIntent.resolveActivity(pm) != null) {
            launched = runCatching { startActivity(geoIntent) }.isSuccess
        }

        if (!launched) {
            val url = "https://www.google.com/maps/search/?api=1&query=$lat,$lon"
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (fallbackIntent.resolveActivity(pm) != null) {
                launched = runCatching { startActivity(fallbackIntent) }.isSuccess
            }
        }

        if (!launched) {
            showMapsUnavailableToast()
        }
    }

    private fun openRouteInGoogleMaps(url: String) {
        val uri = Uri.parse(url)
        val packageManager = packageManager
        val mapsPackage = "com.google.android.apps.maps"
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(mapsPackage) }
        if (mapsIntent.resolveActivity(packageManager) != null) {
            intent.`package` = mapsPackage
        }

        if (!runCatching { startActivity(intent) }.isSuccess) {
            showMapsUnavailableToast()
        }
    }

    private fun showMapsUnavailableToast() {
        Toast.makeText(this, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_BLOCK_ID = "block_id"
        const val EXTRA_SNAPSHOT_URI = "snapshot_uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_PLACE_LABEL = "place_label"
    }
}

private const val LM_TAG = "InjectMother"

private inline fun logD(msg: () -> String) {
    if (DebugConfig.isDebug) android.util.Log.d(LM_TAG, msg())
}

private inline fun logW(msg: () -> String) {
    if (DebugConfig.isDebug) android.util.Log.w(LM_TAG, msg())
}

private inline fun logE(msg: () -> String, t: Throwable? = null) {
    if (DebugConfig.isDebug) android.util.Log.e(LM_TAG, msg(), t)
}
