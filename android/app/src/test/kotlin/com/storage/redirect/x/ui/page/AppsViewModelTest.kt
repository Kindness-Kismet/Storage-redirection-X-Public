package com.storage.redirect.x.ui.page

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.provider.AppCatalogSource
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

internal class FakeAppSource : AppCatalogSource {
    var reads = 0
    var iconReads = 0
    var timeReads = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getInstalledApps(): ImmutableList<AppInfo> {
        reads++
        gate?.await()
        return persistentListOf(
            AppInfo("example.alpha", "Alpha", false),
            AppInfo("example.beta", "Beta", false),
            AppInfo("system.app", "System", true),
        )
    }

    override suspend fun getIcon(packageName: String): Drawable {
        iconReads++
        return ColorDrawable(android.graphics.Color.GRAY)
    }

    override suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo> {
        timeReads++
        return apps
            .mapIndexed { index, app -> app.copy(firstInstallTime = index.toLong()) }
            .toPersistentList()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppsViewModelTest {
    private lateinit var store: ViewModelStore

    @Before
    fun setup() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        AppPreferences.put("apps_sort_mode", "NAME")
        AppPreferences.put("apps_show_system", false)
        AppPreferences.put("apps_redirected_first", true)
        store = ViewModelStore()
    }

    @After
    fun close() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun create(source: FakeAppSource): AppsViewModel =
        AppsViewModel(source).also { store.put("apps", it) }

    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun enteringAgainReusesTheLoadedListAndSearch() {
        val source = FakeAppSource()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        vm.setQuery("Beta")
        vm.ensureAppsLoaded()
        drain()
        assertEquals(1, source.reads)
        assertEquals("Beta", vm.uiState.value.query)
        assertFalse(vm.uiState.value.isLoading)
        vm.refreshApps()
        drain()
        assertEquals(2, source.reads)
        assertEquals("Beta", vm.uiState.value.query)
    }

    @Test
    fun onlyExplicitInstallTimeSortQueriesInstallTimes() {
        val source = FakeAppSource()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        assertEquals(0, source.timeReads)
        vm.setSortByName(false)
        drain()
        assertEquals(1, source.timeReads)
        vm.setSortByName(true)
        vm.setSortByName(false)
        drain()
        assertEquals(1, source.timeReads)
        assertEquals(1, source.reads)
    }

    @Test
    fun concurrentEntryAndRefreshShareTheInFlightLoad() {
        val source = FakeAppSource()
        source.gate = CompletableDeferred()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        vm.ensureAppsLoaded()
        vm.refreshApps()
        drain()
        assertEquals(1, source.reads)
        source.gate!!.complete(Unit)
        drain()
        assertFalse(vm.uiState.value.isLoading)
        vm.refreshApps()
        drain()
        assertEquals(2, source.reads)
    }

    @Test
    fun filtersAndIconUpdatesDoNotReenumerateOrResortTheCatalog() {
        val source = FakeAppSource()
        val vm = create(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val subscription = scope.launch { vm.filteredApps.collect {} }
        try {
            drain()
            assertEquals(2, vm.filteredApps.value.size)
            vm.setQuery("Beta")
            drain()
            assertEquals("example.beta", vm.filteredApps.value.single().packageName)
            val filtered = vm.filteredApps.value
            vm.requestIcon("example.beta")
            repeat(100) {
                drain()
                if (vm.uiState.value.icons.isEmpty()) Thread.sleep(5)
            }
            assertEquals(1, source.iconReads)
            vm.requestIcon("example.beta")
            drain()
            assertEquals(1, source.iconReads)
            assertSame(filtered, vm.filteredApps.value)
            vm.setQuery("")
            vm.setShowSystemApps(true)
            drain()
            assertEquals(3, vm.filteredApps.value.size)
            assertEquals(1, source.reads)
        } finally {
            subscription.cancel()
            scope.cancel()
        }
    }
}
