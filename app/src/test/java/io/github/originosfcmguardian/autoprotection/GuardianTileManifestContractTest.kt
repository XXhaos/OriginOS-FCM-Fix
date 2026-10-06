package io.github.originosfcmguardian.autoprotection

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class GuardianTileManifestContractTest {
    @Test
    fun tileLongPressResolvesToExportedMainActivity() {
        val activity = mainActivity()
        assertEquals("true", activity.androidAttribute("exported"))
        val preferencesFilters = activity.children("intent-filter").filter {
            it.names("action").contains(TILE_PREFERENCES)
        }
        assertEquals("MainActivity must handle tile long press exactly once", 1, preferencesFilters.size)
        assertEquals(setOf(TILE_PREFERENCES), preferencesFilters.single().names("action"))
        assertEquals(setOf(DEFAULT_CATEGORY), preferencesFilters.single().names("category"))
        assertTrue(preferencesFilters.single().children("data").isEmpty())
    }

    @Test
    fun launcherFilterRemainsSeparateFromTilePreferences() {
        val launcherFilters = mainActivity().children("intent-filter").filter {
            it.names("action").contains(MAIN_ACTION)
        }
        assertEquals(1, launcherFilters.size)
        assertEquals(setOf(MAIN_ACTION), launcherFilters.single().names("action"))
        assertEquals(setOf(LAUNCHER_CATEGORY), launcherFilters.single().names("category"))
    }

    private fun mainActivity(): Element {
        val local = File("src/main/AndroidManifest.xml")
        val manifest = if (local.isFile) local else File("app/src/main/AndroidManifest.xml")
        require(manifest.isFile) { "Missing manifest test input" }
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(manifest)
        return document.documentElement.children("application").single()
            .children("activity").single { it.androidAttribute("name") == ".MainActivity" }
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
            .filter { it.tagName == tag }

    private fun Element.names(tag: String): Set<String> =
        children(tag).map { it.androidAttribute("name") }.toSet()

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val MAIN_ACTION = "android.intent.action.MAIN"
        const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"
    }
}
