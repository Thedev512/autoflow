package dev.autoflow.verify

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import dev.autoflow.core.blocks.Permission
import dev.autoflow.core.engine.LogEntry
import dev.autoflow.core.engine.LogLevel
import dev.autoflow.core.engine.RunPhase
import dev.autoflow.core.engine.RunStatus
import dev.autoflow.core.templates.Templates
import dev.autoflow.ui.common.AppInfo
import dev.autoflow.ui.editor.EditorActions
import dev.autoflow.ui.editor.EditorScreen
import dev.autoflow.ui.editor.EditorState
import dev.autoflow.ui.editor.InspectorHost
import dev.autoflow.ui.home.FlowSummary
import dev.autoflow.ui.home.HomeActions
import dev.autoflow.ui.home.HomeScreen
import dev.autoflow.ui.logs.LogsScreen
import dev.autoflow.ui.settings.SettingsActions
import dev.autoflow.ui.settings.SettingsModel
import dev.autoflow.ui.settings.SettingsScreen
import dev.autoflow.ui.settings.ThemeMode
import dev.autoflow.ui.theme.FallbackDark
import dev.autoflow.ui.theme.FallbackLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the real screens at Galaxy Tab A11 size (1340 x 800 px, density 1.33) and saves PNGs,
 * and drives the editor with simulated touches.
 */
@OptIn(ExperimentalTestApi::class)
class ScreensTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val tabDensity = Density(1.33f, 1f)

    @Composable
    private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides tabDensity) {
            MaterialTheme(colorScheme = if (dark) FallbackDark else FallbackLight, content = content)
        }
    }

    private val noEditor = EditorActions({}, {}, {}, {}, {}, {}, {})
    private val host = InspectorHost(
        apps = listOf(AppInfo("Spotify", "com.spotify.music"), AppInfo("YouTube", "com.google.android.youtube")),
        onLoadApps = {},
        granted = setOf(Permission.NOTIFICATIONS),
        onGrant = {},
    )

    private fun shot(name: String, width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) =
        runDesktopComposeUiTest(width, height) {
            setContent { Themed(dark, content) }
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            val img = onRoot().captureToImage().toAwtImage()
            ImageIO.write(img, "png", File(outDir, "$name.png"))
        }

    @Test fun homeScreen() {
        val docs = Templates.all.take(5).map { it.build() }
        val now = System.currentTimeMillis()
        val statuses = listOf(
            RunStatus(docs[0].id, "r1", RunPhase.WAITING, now, detail = "Waiting: Battery level"),
            RunStatus(docs[1].id, "r2", RunPhase.RUNNING, now, detail = "Running: Text to speech"),
            null,
            RunStatus(docs[3].id, "r3", RunPhase.SLEEPING, now, wakeAt = now + 3_600_000),
            RunStatus(docs[4].id, "r4", RunPhase.FAILED, now, detail = "Brightness: permission missing"),
        )
        val flows = docs.mapIndexed { i, d -> FlowSummary(d.id, d.name, d.blocks.map { it.type }, false, statuses[i]) }
        val actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
        for (dark in listOf(false, true)) {
            shot("home-${if (dark) "dark" else "light"}", 1340, 800, dark) { HomeScreen(flows, Templates.all, actions) }
        }
    }

    @Test fun editorLandscapeWithInspector() {
        val doc = Templates.all.first { it.title == "Dice roller" }.build()
        val state = EditorState(doc)
        val loop = doc.blocks.first { it.type == "logic.loop" }
        state.selection = setOf(loop.id)
        val status = RunStatus(doc.id, "r", RunPhase.RUNNING, 0, activeBlocks = setOf(doc.blocks.first { it.type == "logic.random" }.id), detail = "Running: Random")
        shot("editor-landscape-dark", 1340, 800, true) { EditorScreen(state, status, host, noEditor) }
        shot("editor-landscape-light", 1340, 800, false) { EditorScreen(state, status, host, noEditor) }
    }

    @Test fun editorPortrait() {
        val doc = Templates.all.first { it.title == "Good morning" }.build()
        val state = EditorState(doc)
        state.selection = setOf(doc.blocks.first { it.type == "trigger.time" }.id)
        shot("editor-portrait-light", 800, 1340, false) { EditorScreen(state, null, host, noEditor) }
        state.clearSelection()
        shot("editor-portrait-dark-noselection", 800, 1340, true) { EditorScreen(state, null, host, noEditor) }
    }

    @Test fun logsAndSettings() {
        val t0 = 1_760_360_000_000L
        val logs = listOf(
            LogEntry("f", "run2", t0 + 9_000, LogLevel.INFO, null, null, "Flow finished"),
            LogEntry("f", "run2", t0 + 8_200, LogLevel.DEBUG, "b3", "Notification", "OK", 14),
            LogEntry("f", "run2", t0 + 8_150, LogLevel.DEBUG, "b2", "HTTP request", "→ OK", 812),
            LogEntry("f", "run2", t0 + 7_000, LogLevel.DEBUG, "b1", "Time / schedule", "OK", 3_590_000),
            LogEntry("f", "run1", t0, LogLevel.ERROR, "b4", "Brightness", "Modify system settings isn't allowed yet"),
            LogEntry("f", "run1", t0 - 50, LogLevel.INFO, null, null, "Flow started"),
        )
        shot("logs-dark", 1340, 800, true) { LogsScreen("Good morning", null, logs, {}, {}) }
        val model = SettingsModel(ThemeMode.SYSTEM, true, true, null, setOf(Permission.NOTIFICATIONS, Permission.EXACT_ALARM), false, "0.1.0")
        shot("settings-light", 800, 1340, false) {
            SettingsScreen(model, SettingsActions({}, {}, {}, {}, {}, {}, {}))
        }
    }

    @Test fun editorGesturesUndoAndPalette() = runDesktopComposeUiTest(1340, 800) {
        val doc = Templates.blank()
        val state = EditorState(doc)
        setContent { Themed(false) { EditorScreen(state, null, host, noEditor) } }
        waitForIdle()

        // Tap a palette item: adds a block under the selected start block and wires it up.
        val start = doc.blocks.single()
        state.selection = setOf(start.id)
        onAllNodes(hasSetTextAction())[0].performTextInput("toast")
        waitForIdle()
        onNodeWithText("Toast").performClick()
        waitForIdle()
        assertEquals(2, state.doc.blocks.size)
        val toast = state.doc.blocks.first { it.type == "action.toast" }
        assertEquals(start.id, state.doc.links.single().from)
        assertEquals(toast.id, state.doc.links.single().to)

        // Drag the new block on the canvas by its title.
        val before = state.doc.block(toast.id)!!
        onAllNodesWithText("Hello")[0].performTouchInput { swipe(center, center + Offset(200f, 120f), 300) }
        waitForIdle()
        val after = state.doc.block(toast.id)!!
        assertNotEquals(before.x, after.x)
        assertTrue("moved right by roughly 200px / 1.33 density", after.x - before.x in 100f..200f)

        // Undo the drag, then undo the add.
        onNodeWithContentDescription("Undo").performClick(); waitForIdle()
        assertEquals(before.x, state.doc.block(toast.id)!!.x)
        onNodeWithContentDescription("Undo").performClick(); waitForIdle()
        assertEquals(1, state.doc.blocks.size)
        onNodeWithContentDescription("Redo").performClick(); waitForIdle()
        assertEquals(2, state.doc.blocks.size)

        // Search the palette.
        state.clearSelection()
        waitForIdle()
        onAllNodes(hasSetTextAction())[0].performTextClearance()
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        ImageIO.write(img, "png", File(outDir, "editor-after-gestures.png"))
    }

    @Test fun drawArrowFromPortToBlock() = runDesktopComposeUiTest(1340, 800) {
        val base = Templates.blank()
        val state = EditorState(base)
        state.addBlock(dev.autoflow.core.blocks.BlockCatalog["action.vibrate"]!!, Offset(500f, 300f))
        state.clearSelection()
        setContent { Themed(true) { EditorScreen(state, null, host, noEditor) } }
        waitForIdle()
        val start = state.doc.blocks.first { it.type == "flow.start" }
        val vib = state.doc.blocks.first { it.type == "action.vibrate" }
        // Screen positions (canvas-local) of the start block's output dot and the vibrate block's middle.
        val from = state.toScreen(Offset(start.x + 100f, start.y + 84f)) + canvasOffset(state)
        val to = state.toScreen(Offset(vib.x + 100f, vib.y + 40f)) + canvasOffset(state)
        onRoot().performTouchInput { swipe(from, to, 400) }
        waitForIdle()
        assertEquals(1, state.doc.links.size)
        assertEquals(vib.id, state.doc.links.single().to)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "editor-arrow-dark.png"))
    }

    /** The canvas sits right of the 280dp palette and below the top bar (64dp). */
    private fun canvasOffset(state: EditorState) = Offset(281f * 1.33f, 64f * 1.33f)
}
