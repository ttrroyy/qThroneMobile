package app.rawline.mobile.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PreferenceWidgetLayoutTest {
    @Test fun editorHasATranslucentManifestThemeBeforeItsActivityStarts() {
        val android = "http://schemas.android.com/apk/res/android"
        fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path))
        val manifest=parse("src/main/AndroidManifest.xml")
        val activities=manifest.getElementsByTagName("activity")
        val editor=(0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android,"name") == "app.rawline.mobile.ui.SubscriptionEditorActivity" }
        val styles=parse("src/main/res/values/mobile_subscription_editor.xml")
        val definitions=styles.getElementsByTagName("style")
        val theme=(0 until definitions.length).map { definitions.item(it) as Element }
            .single { "@style/"+it.getAttribute("name") == editor.getAttributeNS(android,"theme") }
        val items=theme.getElementsByTagName("item")
        assertTrue((0 until items.length).map { items.item(it) as Element }
            .any { it.getAttribute("name") == "android:windowIsTranslucent" && it.textContent == "true" })
    }

    @Test fun widgetContainerIsVisibleWhenAndroidXInflatesASwitch() {
        val android = "http://schemas.android.com/apk/res/android"
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/res/layout/mobile_preference.xml"))
        val elements = document.getElementsByTagName("*")
        val frame = (0 until elements.length).map { elements.item(it) as Element }
            .single { it.getAttributeNS(android, "id") == "@android:id/widget_frame" }
        val visibility = frame.getAttributeNS(android, "visibility")
        assertTrue("AndroidX adds the switch but does not unhide its container",
            visibility.isEmpty() || visibility == "visible")
    }
}
