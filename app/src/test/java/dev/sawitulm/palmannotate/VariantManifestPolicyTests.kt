package dev.sawitulm.palmannotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ════════════════════════════════════════════════════════════════════════════════
// WS-22 — USB attach ownership and backup policy.
//
// Asserted against the SOURCE manifests, which are what the merger consumes. Comments are
// stripped first: the main manifest explains at length why the USB filter is NOT there, and a
// naive text search would find the explanation and conclude the opposite.
// ════════════════════════════════════════════════════════════════════════════════

private const val USB_ATTACH_ACTION = "android.hardware.usb.action.USB_DEVICE_ATTACHED"

class UsbAttachOwnershipTest {

    private val mainManifest by lazy { readXmlWithoutComments("app/src/main/AndroidManifest.xml") }
    private val fieldManifest by lazy { readXmlWithoutComments("app/src/field/AndroidManifest.xml") }

    @Test fun `the shared source set claims no USB auto-launch`() {
        assertFalse(
            "src/main must not declare the USB attach filter — all three variants inherit it",
            mainManifest.contains(USB_ATTACH_ACTION),
        )
    }

    @Test fun `the field variant is the attach owner`() {
        assertTrue(fieldManifest.contains(USB_ATTACH_ACTION))
        assertTrue("the vendor filter must be attached to the owner", fieldManifest.contains("@xml/orbbec_usb_filter"))
        assertTrue("the filter must land on MainActivity", fieldManifest.contains(".MainActivity"))
    }

    @Test fun `no diagnostic variant declares an attach filter`() {
        for (variant in listOf("debug", "trace", "release")) {
            val manifest = File("app/src/$variant/AndroidManifest.xml")
            val found = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
                .map { File(it, manifest.path) }
                .firstOrNull(File::isFile)
            if (found != null) {
                assertFalse(
                    "src/$variant must not claim USB auto-launch",
                    found.readText().replace(Regex("(?s)<!--.*?-->"), "").contains(USB_ATTACH_ACTION),
                )
            }
        }
    }

    @Test fun `USB host support stays declared for every variant`() {
        // Removing the auto-launch filter must not remove USB host capability: the runtime
        // permission path (OrbbecManager.requestPermission) still needs it in debug and trace.
        assertTrue(mainManifest.contains("android.hardware.usb.host"))
    }

    @Test fun `exactly one source set owns the attach intent`() {
        val owners = listOf("main", "field", "debug", "trace", "release").count { variant ->
            val found = generateSequence(File(System.getProperty("user.dir")!!).absoluteFile) { it.parentFile }
                .map { File(it, "app/src/$variant/AndroidManifest.xml") }
                .firstOrNull(File::isFile)
            found != null && found.readText()
                .replace(Regex("(?s)<!--.*?-->"), "")
                .contains(USB_ATTACH_ACTION)
        }
        assertEquals("exactly one source set may own USB_DEVICE_ATTACHED", 1, owners)
    }
}

class BackupPolicyTest {

    private val mainManifest by lazy { readXmlWithoutComments("app/src/main/AndroidManifest.xml") }

    @Test fun `auto backup is off`() {
        assertTrue(
            "the multi-GB dataset must not enter Auto Backup",
            mainManifest.contains("android:allowBackup=\"false\""),
        )
        assertFalse(mainManifest.contains("android:allowBackup=\"true\""))
    }

    @Test fun `both backup rule files are declared, not left to the platform default`() {
        assertTrue(mainManifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(mainManifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test fun `legacy full-backup rules exclude every data domain`() {
        val rules = readXmlWithoutComments("app/src/main/res/xml/backup_rules.xml")
        for (domain in listOf("external", "database", "sharedpref", "file", "root")) {
            assertTrue(
                "backup_rules.xml must exclude domain=$domain",
                rules.contains("<exclude domain=\"$domain\""),
            )
        }
        assertFalse("no domain may be re-included", rules.contains("<include"))
    }

    @Test fun `cloud backup and device transfer are both excluded on API 31+`() {
        val rules = readXmlWithoutComments("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue(rules.contains("<cloud-backup>"))
        assertTrue(rules.contains("<device-transfer>"))
        // Device transfer matters independently: copying the install id to a second tablet would
        // give two physical devices the same WS-12 capture-set identity.
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for (domain in listOf("external", "database", "sharedpref", "file", "root")) {
            assertTrue(
                "device-transfer must exclude domain=$domain",
                transfer.contains("<exclude domain=\"$domain\""),
            )
        }
        assertFalse("no domain may be re-included", rules.contains("<include"))
    }
}
