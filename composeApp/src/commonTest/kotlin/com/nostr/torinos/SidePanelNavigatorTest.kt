package com.nostr.torinos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SidePanelNavigatorTest {
    @Test
    fun openRootReplacesHistoryAndIgnoresSameDestination() {
        val navigator = SidePanelNavigator()
        navigator.openRoot(SidePanelDestination.Settings)
        navigator.push(SidePanelDestination.MuteList)
        navigator.openRoot(SidePanelDestination.Search("#nostr"))

        assertEquals(listOf<SidePanelDestination>(SidePanelDestination.Search("#nostr")), destinations(navigator))
        assertEquals(2, navigator.drainRemoved().size)

        val current = navigator.current
        navigator.openRoot(SidePanelDestination.Search("#nostr"))
        assertSame(current, navigator.current)
        assertTrue(navigator.drainRemoved().isEmpty())
    }

    @Test
    fun pushIgnoresCurrentDestinationAndBackClosesAtRoot() {
        val navigator = SidePanelNavigator()
        navigator.openRoot(SidePanelDestination.Settings)
        navigator.push(SidePanelDestination.RelaySettings)
        navigator.push(SidePanelDestination.RelaySettings)
        assertEquals(
            listOf(SidePanelDestination.Settings, SidePanelDestination.RelaySettings),
            destinations(navigator),
        )

        navigator.back()
        assertEquals(SidePanelDestination.Settings, navigator.current?.destination)
        navigator.back()
        assertNull(navigator.current)
    }

    @Test
    fun removedEntriesKeepViewModelsUntilHostDisposesThem() {
        val navigator = SidePanelNavigator()
        navigator.openRoot(SidePanelDestination.Settings)
        val viewModel = createViewModel(checkNotNull(navigator.current))

        navigator.back()
        val removed = navigator.drainRemoved().single()
        assertFalse(viewModel.cleared)
        removed.viewModelStore.clear()
        assertTrue(viewModel.cleared)
    }

    @Test
    fun drainRemovedKeepsEntryShownDuringCloseAnimation() {
        val navigator = SidePanelNavigator()
        navigator.openRoot(SidePanelDestination.Settings)
        val closing = checkNotNull(navigator.current)
        navigator.back()

        assertTrue(navigator.drainRemoved(keep = closing).isEmpty())
        assertSame(closing, navigator.drainRemoved().single())
    }

    @Test
    fun disposeAllClearsVisibleAndPendingEntries() {
        val navigator = SidePanelNavigator()
        navigator.openRoot(SidePanelDestination.Settings)
        val removedViewModel = createViewModel(checkNotNull(navigator.current))
        navigator.openRoot(SidePanelDestination.Search(""))
        val visibleViewModel = createViewModel(checkNotNull(navigator.current))

        navigator.disposeAll()

        assertNull(navigator.current)
        assertTrue(removedViewModel.cleared)
        assertTrue(visibleViewModel.cleared)
        assertTrue(navigator.drainRemoved().isEmpty())
    }

    private fun destinations(navigator: SidePanelNavigator) = navigator.entries.map { it.destination }

    private fun createViewModel(entry: SidePanelEntry): TrackingViewModel =
        ViewModelProvider.create(
            entry,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                    TrackingViewModel() as T
            },
        )[TrackingViewModel::class]

    private class TrackingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }
}
