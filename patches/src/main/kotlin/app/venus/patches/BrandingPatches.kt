package app.venus.patches

import app.morphe.patcher.patch.ImageSize
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.imageOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.w3c.dom.Element
import java.io.File

internal const val DISCORD_PACKAGE = "com.discord"
private const val BRAND_ICON = "venus_brand_icon"
private const val BRAND_IMAGE = "venus_brand_image"
private const val BRAND_FOREGROUND = "venus_brand_foreground"
private const val BRAND_BACKGROUND = "venus_brand_background"
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val PACKAGE_NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
private val HEX_COLOR = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** Discord's own colors, so a recolored icon still looks like it belongs on the home screen. */
private val ICON_COLORS = mapOf(
    "Blurple" to "#5865F2",
    "Green" to "#57F287",
    "Yellow" to "#FEE75C",
    "Fuchsia" to "#EB459E",
    "Red" to "#ED4245",
    "Black" to "#23272A",
)

/** The user's image as an Android resource extension, by its first bytes (the file name can lie). */
internal fun imageExtension(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
    else -> null
}

/** aapt reads a leading @ or ? as a reference and treats quotes and backslashes specially. */
internal fun androidStringValue(text: String): String {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        .replace("\n", "\\n")
    return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
}

/** Every manifest value that names com.discord itself, renamed so both apps install side by side. */
internal fun renamePackage(manifest: org.w3c.dom.Document, newPackage: String) {
    val root = manifest.documentElement
    root.setAttribute("package", newPackage)
    val prefix = "$DISCORD_PACKAGE."
    fun rename(value: String) = if (value.startsWith(prefix)) newPackage + value.substring(DISCORD_PACKAGE.length) else value
    fun each(tag: String, block: (Element) -> Unit) {
        val nodes = manifest.getElementsByTagName(tag)
        for (i in 0 until nodes.length) block(nodes.item(i) as Element)
    }
    // Android refuses a second app declaring the same permission, and androidx builds this one from getPackageName().
    for (tag in listOf("permission", "uses-permission")) each(tag) {
        it.setAttribute("android:name", rename(it.getAttribute("android:name")))
    }
    // Two apps can't share a provider authority. Discord builds its file provider's from getPackageName() too.
    each("provider") { provider ->
        val authorities = provider.getAttribute("android:authorities")
        provider.setAttribute("android:authorities", authorities.split(";").joinToString(";", transform = ::rename))
    }
    // Activities sharing an affinity with real Discord's would open in its tasks.
    for (tag in listOf("activity", "activity-alias")) each(tag) {
        val affinity = it.getAttribute("android:taskAffinity")
        if (affinity.startsWith(prefix)) it.setAttribute("android:taskAffinity", rename(affinity))
    }
    each("meta-data") {
        if (it.getAttribute("android:value") == DISCORD_PACKAGE) it.setAttribute("android:value", newPackage)
    }
}

private fun ResourcePatchContext.writeResource(path: String, text: String) {
    get(path, false).apply { parentFile.mkdirs(); writeText(text) }
}

private fun ResourcePatchContext.addBackgroundColor(color: String) {
    val colors = get("res/values/colors.xml", false)
    if (!colors.exists()) colors.apply { parentFile.mkdirs(); writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n") }
    document("res/values/colors.xml").use { document ->
        val element = document.createElement("color")
        element.setAttribute("name", BRAND_BACKGROUND)
        element.textContent = color
        document.documentElement.appendChild(element)
    }
}

@Suppress("unused")
val customBranding = resourcePatch(
    name = "Custom branding",
    description = "Installs Venus as its own app next to the normal Discord app, with the name, color and icon you pick. " +
        "It starts signed out and keeps its own settings. Notifications work in both.",
    default = false,
) {
    compatibleWith(discord)
    section(PLUGINS)
    // A root mount (#121) lays the patched APK over the installed Discord, and Android keeps reading that Discord's own
    // manifest: a new package name can't be mounted over com.discord, and a new name or icon would never show. Morphe's
    // own Custom branding patches are left out of mounts for the same reason.
    // DISABLED means offered but unticked. This rule replaces Morphe's own, which turns `default` into ENABLED/DISABLED,
    // so returning ENABLED here ticked Custom branding for everyone who added the source, despite default = false.
    availability { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    }

    // Shown in this order in Morphe. Name and package are plain text fields with no presets: with presets Morphe draws
    // a dropdown, which hid that you can type anything.
    val appName = stringOption(
        key = "appName",
        default = "Venus",
        title = "App name",
        description = "Type the name shown under the icon on your home screen and in your app list.",
        required = true,
    ) { it != null && it.isNotBlank() && it.trim().length <= 50 }

    val packageName = stringOption(
        key = "packageName",
        default = "com.discord.venus",
        title = "Package name",
        description = "Type any name made of lowercase words joined by dots, like com.discord.venus. A name other than " +
            "com.discord installs Venus next to the normal Discord app. Type com.discord to replace Discord instead.",
        required = true,
    ) { it != null && it.trim().matches(PACKAGE_NAME) }

    val iconBackground = colorOption(
        key = "iconBackground",
        values = ICON_COLORS,
        title = "Icon color",
        description = "The color behind the logo, to tell the two apps apart at a glance. Leave empty for Discord's blurple.",
    ) { it.isNullOrBlank() || it.matches(HEX_COLOR) }

    val appIcon = imageOption(
        key = "appIcon",
        title = "Icon picture",
        description = "Replaces Discord's logo with your own square picture (PNG, JPG or WebP). Icon color still shows " +
            "through any transparent parts. Leave empty to keep Discord's logo.",
        recommendedSize = ImageSize(512, 512),
    ) { it.isNullOrBlank() || File(it).isFile }

    finalize {
        val newPackage = packageName.value?.trim().orEmpty()
        if (!newPackage.matches(PACKAGE_NAME))
            throw PatchException("\"$newPackage\" isn't a valid package name. Use lowercase letters, digits and dots, like com.discord.venus")
        val name = appName.value?.trim().orEmpty().ifEmpty { "Discord" }
        val image = appIcon.file
        val color = iconBackground.value?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length == 7) "#FF${it.substring(1)}" else it }?.uppercase()

        if (name != "Discord") document("res/values/strings.xml").use { document ->
            val strings = document.getElementsByTagName("string")
            val label = (0 until strings.length).map { strings.item(it) as Element }
                .firstOrNull { it.getAttribute("name") == "app_name" }
                ?: throw PatchException("Discord's app_name string is missing")
            label.textContent = androidStringValue(name)
        }

        val iconChanged = image != null || color != null
        if (iconChanged) {
            addBackgroundColor(color ?: "#FF5865F2")
            val foreground = if (image != null) {
                val bytes = image.readBytes()
                val extension = imageExtension(bytes) ?: throw PatchException("The App icon must be a PNG, JPG or WebP image")
                get("res/drawable-nodpi/$BRAND_IMAGE.$extension", false).apply { parentFile.mkdirs(); writeBytes(bytes) }
                // The launcher shows the middle 72 of an adaptive icon's 108dp, so a 1/6 inset fills exactly what's visible.
                writeResource("res/drawable-anydpi-v26/$BRAND_FOREGROUND.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <inset xmlns:android="$ANDROID_NS" android:drawable="@drawable/$BRAND_IMAGE" android:inset="16.667%" />
                """.trimIndent() + "\n")
                // Phones before Android 8 have no adaptive icons and show the image as it is.
                writeResource("res/mipmap-anydpi/$BRAND_ICON.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <bitmap xmlns:android="$ANDROID_NS" android:src="@drawable/$BRAND_IMAGE" android:gravity="fill" />
                """.trimIndent() + "\n")
                "@drawable/$BRAND_FOREGROUND"
            } else {
                writeResource("res/mipmap-anydpi/$BRAND_ICON.xml", """
                    <?xml version="1.0" encoding="utf-8"?>
                    <bitmap xmlns:android="$ANDROID_NS" android:src="@mipmap/ic_logo_square" android:gravity="fill" />
                """.trimIndent() + "\n")
                "@mipmap/ic_logo_foreground"
            }
            // A themed (monochrome) icon only keeps a picture's outline, so a custom image goes without one.
            val monochrome = if (image == null) "\n    <monochrome android:drawable=\"@mipmap/ic_logo_foreground\" />" else ""
            writeResource("res/mipmap-anydpi-v26/$BRAND_ICON.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <adaptive-icon xmlns:android="$ANDROID_NS">
                    <background android:drawable="@color/$BRAND_BACKGROUND" />
                    <foreground android:drawable="$foreground" />
                """.trimIndent() + monochrome + "\n</adaptive-icon>\n")
        }

        document("AndroidManifest.xml").use { manifest ->
            if (newPackage != DISCORD_PACKAGE) renamePackage(manifest, newPackage)
            if (iconChanged) {
                val application = manifest.getElementsByTagName("application").item(0) as Element
                application.setAttribute("android:icon", "@mipmap/$BRAND_ICON")
                application.setAttribute("android:roundIcon", "@mipmap/$BRAND_ICON")
            }
        }
    }
}

/** Discord's logo (Simple Icons, CC0) on its own 24-unit grid. */
private const val DISCORD_LOGO = "M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.946 2.4189-2.1568 2.4189Z"

/** Gradient stops per Google-style icon variant, as (offset, color). */
private val GOOGLE_ICON_STYLES = mapOf(
    "Blurple to violet" to "A",
    "Blue, violet and pink" to "B",
    "Blurple on a light tint" to "C",
    "Blurple with a soft shine" to "D",
)
private val GOOGLE_ICON_STOPS = mapOf(
    "A" to listOf("0" to "#FF5865F2", "1" to "#FF8B5CF6"),
    "B" to listOf("0" to "#FF3F7BFF", "0.55" to "#FF6E5CF6", "1" to "#FFD45CE8"),
    "C" to listOf("0" to "#FF6B78FF", "1" to "#FF4752C4"),
    "D" to listOf("0" to "#FF7A86FF", "0.6" to "#FF5865F2", "1" to "#FF4A50D9"),
)
private const val GOOGLE_ICON = "venus_google_icon"

@Suppress("unused")
val googleStyleIcon = resourcePatch(
    name = "Google-style icon",
    description = "Discord's icon like Google's own apps: a white circle with the logo in a soft gradient. " +
        "Themed icons keep working.",
    default = false,
) {
    compatibleWith(discord)
    section(PLUGINS)
    // A root mount keeps the installed Discord's manifest, so a new icon would never show (see Custom branding).
    availability { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    }

    val style = stringOption(
        key = "style",
        default = "A",
        values = GOOGLE_ICON_STYLES,
        title = "Colors",
        description = "The logo's gradient.",
        required = true,
    ) { it != null && it in GOOGLE_ICON_STOPS }

    finalize {
        val stops = GOOGLE_ICON_STOPS[style.value] ?: throw PatchException("Unknown icon style ${style.value}")
        val background = if (style.value == "C") "#FFEEF0FF" else "#FFFFFFFF"
        // The gradient runs corner to corner across the logo's own 24-unit grid.
        writeResource("res/color/${GOOGLE_ICON}_gradient.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <gradient xmlns:android="$ANDROID_NS" android:type="linear"
                android:startX="1" android:startY="3" android:endX="23" android:endY="21">
            """.trimIndent() + "\n" + stops.joinToString("") { (offset, color) ->
                "    <item android:offset=\"$offset\" android:color=\"$color\" />\n" } + "</gradient>\n")
        for ((name, fill) in listOf("${GOOGLE_ICON}_logo" to "@color/${GOOGLE_ICON}_gradient", "${GOOGLE_ICON}_mono" to "#FF000000"))
            writeResource("res/drawable/$name.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <vector xmlns:android="$ANDROID_NS" android:width="24dp" android:height="24dp"
                    android:viewportWidth="24" android:viewportHeight="24">
                    <path android:fillColor="$fill" android:pathData="$DISCORD_LOGO" />
                </vector>
            """.trimIndent() + "\n")
        // The launcher shows the middle 72dp of a 108dp adaptive icon; a 31.5% inset makes the logo 40dp wide there,
        // the size Google's own logos take.
        for (name in listOf("logo", "mono"))
            writeResource("res/drawable/${GOOGLE_ICON}_${name}_inset.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <inset xmlns:android="$ANDROID_NS" android:drawable="@drawable/${GOOGLE_ICON}_$name" android:inset="31.5%" />
            """.trimIndent() + "\n")
        writeResource("res/drawable/${GOOGLE_ICON}_background.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <shape xmlns:android="$ANDROID_NS" android:shape="rectangle"><solid android:color="$background" /></shape>
        """.trimIndent() + "\n")
        writeResource("res/mipmap-anydpi-v26/$GOOGLE_ICON.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="$ANDROID_NS">
                <background android:drawable="@drawable/${GOOGLE_ICON}_background" />
                <foreground android:drawable="@drawable/${GOOGLE_ICON}_logo_inset" />
                <monochrome android:drawable="@drawable/${GOOGLE_ICON}_mono_inset" />
            </adaptive-icon>
        """.trimIndent() + "\n")
        // Phones without adaptive icons: the same circle as a plain drawable.
        writeResource("res/mipmap-anydpi/$GOOGLE_ICON.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <layer-list xmlns:android="$ANDROID_NS">
                <item><shape android:shape="oval"><solid android:color="$background" /></shape></item>
                <item android:drawable="@drawable/${GOOGLE_ICON}_logo_inset" />
            </layer-list>
        """.trimIndent() + "\n")
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:icon", "@mipmap/$GOOGLE_ICON")
            application.setAttribute("android:roundIcon", "@mipmap/$GOOGLE_ICON")
            // Launcher entries that name their own icon (Discord's alternate app icons) follow it too.
            for (tag in listOf("activity", "activity-alias")) {
                val nodes = manifest.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    if (node.hasAttribute("android:icon")) node.setAttribute("android:icon", "@mipmap/$GOOGLE_ICON")
                    if (node.hasAttribute("android:roundIcon")) node.setAttribute("android:roundIcon", "@mipmap/$GOOGLE_ICON")
                }
            }
        }
    }
}
