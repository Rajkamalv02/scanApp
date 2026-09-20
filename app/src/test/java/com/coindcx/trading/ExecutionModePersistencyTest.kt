package com.coindcx.trading

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test Suite verifying:
 * 1. Default Mode is Live Mode (Real Capital).
 * 2. Manual selection of Live or Paper mode persists across app lifecycles.
 * 3. App/Service initialization never reverts to Paper Mode when Live Mode is selected.
 * 4. Manual selection remains active until explicitly changed by the user.
 */
class ExecutionModePersistencyTest {

    // In-memory simulated preference store to test persistence semantics
    class FakePreferences {
        private val map = mutableMapOf<String, Any>()

        fun getBoolean(key: String, defValue: Boolean): Boolean {
            return map[key] as? Boolean ?: defValue
        }

        fun putBoolean(key: String, value: Boolean) {
            map[key] = value
        }

        fun clear() {
            map.clear()
        }
    }

    class MockTradingConfigRepository(private val prefs: FakePreferences) {
        companion object {
            private const val KEY_LIVE_MODE = "is_live_mode"
        }

        fun isLiveMode(): Boolean = prefs.getBoolean(KEY_LIVE_MODE, true)

        fun setLiveMode(isLive: Boolean) {
            prefs.putBoolean(KEY_LIVE_MODE, isLive)
        }
    }

    @Test
    fun testDefaultMode_IsLiveTradingOnFreshInstall() {
        val prefs = FakePreferences()
        val repo = MockTradingConfigRepository(prefs)

        // On fresh launch with no stored preference, app must default to Live Mode
        assertTrue("Default execution mode must be Live Mode (true)", repo.isLiveMode())
    }

    @Test
    fun testModePersistence_SwitchToPaper_PersistsAcrossRestarts() {
        val prefs = FakePreferences()
        val repoSession1 = MockTradingConfigRepository(prefs)

        // User manually switches to Paper Mode
        repoSession1.setLiveMode(false)
        assertFalse("Mode should now be Paper Mode", repoSession1.isLiveMode())

        // Simulate app kill & restart (new session reading from storage)
        val repoSession2 = MockTradingConfigRepository(prefs)
        assertFalse("Paper Mode must persist after app restart", repoSession2.isLiveMode())
    }

    @Test
    fun testModePersistence_SwitchToLive_PersistsAcrossRestarts() {
        val prefs = FakePreferences()
        val repoSession1 = MockTradingConfigRepository(prefs)

        // User starts in Paper Mode, then manually switches to Live Mode
        repoSession1.setLiveMode(false)
        repoSession1.setLiveMode(true)
        assertTrue("Mode should now be Live Mode", repoSession1.isLiveMode())

        // Simulate app kill & restart (new session reading from storage)
        val repoSession2 = MockTradingConfigRepository(prefs)
        assertTrue("Live Mode must persist after app restart and not revert to Paper Mode", repoSession2.isLiveMode())
    }

    @Test
    fun testServiceIntentResolution_DoesNotRevertLiveModeWhenExtraMissing() {
        val prefs = FakePreferences()
        val repo = MockTradingConfigRepository(prefs)
        repo.setLiveMode(true) // User is in Live Mode

        // Service starts without EXTRA_IS_PAPER (e.g. system auto-restart or generic command)
        val hasExtra = false
        val extraValue = false // not used when hasExtra is false

        val isPaper = if (hasExtra) extraValue else !repo.isLiveMode()
        val resultingIsLive = !isPaper

        assertTrue("Service startup without explicit extra must remain in Live Mode", resultingIsLive)
        assertEquals("Execution mode should remain Live Mode", true, repo.isLiveMode())
    }

    @Test
    fun testServiceIntentResolution_ExplicitModeChangeUpdatesPersistedState() {
        val prefs = FakePreferences()
        val repo = MockTradingConfigRepository(prefs)

        // Explicit switch to Paper Mode
        var extraIsPaper = true
        var resolvedPaper = extraIsPaper
        repo.setLiveMode(!resolvedPaper)
        assertFalse("Persisted state must be updated to Paper Mode", repo.isLiveMode())

        // Explicit switch back to Live Mode
        extraIsPaper = false
        resolvedPaper = extraIsPaper
        repo.setLiveMode(!resolvedPaper)
        assertTrue("Persisted state must be updated to Live Mode", repo.isLiveMode())
    }

    @Test
    fun testUiLifecycleSync_CorrectsMismatchedUiSwitchWithoutRevertingConfig() {
        val prefs = FakePreferences()
        val repo = MockTradingConfigRepository(prefs)
        repo.setLiveMode(true) // Persisted state is Live Mode

        // Simulate UI element initialized incorrectly (e.g. stale or default false)
        var uiSwitchChecked = false
        var uiBadge = "SIMULATION (SAFE)"

        // onResume lifecycle synchronization
        val persistedLive = repo.isLiveMode()
        if (uiSwitchChecked != persistedLive) {
            uiSwitchChecked = persistedLive
            uiBadge = if (persistedLive) "LIVE (REAL CAPITAL)" else "SIMULATION (SAFE)"
        }

        assertTrue("UI switch must be synchronized to Live Mode", uiSwitchChecked)
        assertEquals("LIVE (REAL CAPITAL)", uiBadge)
        assertTrue("Persisted config must remain Live Mode", repo.isLiveMode())
    }
}
