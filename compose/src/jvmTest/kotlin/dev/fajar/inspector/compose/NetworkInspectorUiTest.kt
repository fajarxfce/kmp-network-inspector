package dev.fajar.inspector.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.fajar.inspector.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test

class NetworkInspectorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkNarrowLargeTextRetainsSearchSelectionAndPendingResponseUpdates() =
        checkList(320, 640, 2f, true, "narrow-dark-large")

    @Test
    fun landscapeKeepsHostActionsAndRequestsVisible() = checkList(720, 375, 1f, false, "landscape")

    private fun checkList(width: Int, height: Int, scale: Float, dark: Boolean, name: String) {
        var snapshot by
            mutableStateOf(
                NetworkTraceSnapshot(listOf(trace(1), trace(2, NetworkOutcome.Response(401))))
            )
        var filter by mutableStateOf(NetworkTraceFilter())
        var selected: Long? = null
        var clears = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface(Modifier.size(width.dp, height.dp).testTag("inspector")) {
                        NetworkTraceList(snapshot, filter, { filter = it }, { selected = it }) {
                            TextButton(onClick = { clears++ }) { Text("Clear") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Search requests").assertIsDisplayed()
        compose
            .onNodeWithContentDescription("Filter requests")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("Pending").assertIsDisplayed()
        compose.onNodeWithText("GET /requests/1").performClick()
        compose.runOnIdle {
            assertEquals(1L, selected)
            snapshot =
                snapshot.copy(
                    traces =
                        listOf(
                            trace(1, NetworkOutcome.Response(200)),
                            trace(2, NetworkOutcome.Response(401)),
                        )
                )
        }
        compose.onNodeWithText("Pending").assertDoesNotExist()
        compose.onAllNodesWithText("GET /requests/1").assertCountEquals(1)
        compose.onNodeWithText("HTTP 200").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        Image.makeFromBitmap(compose.onNodeWithTag("inspector").captureToImage().asSkiaBitmap())
            .use { image ->
                checkNotNull(image.encodeToData()).use { data ->
                    File("build/reports/screenshots").mkdirs()
                    File("build/reports/screenshots/inspector-$name.png").writeBytes(data.bytes)
                }
            }
    }

    @Test
    fun searchAndSourceMenuEmitTheCurrentFilterAndKeepSelectionInTheHost() {
        var filter by mutableStateOf(NetworkTraceFilter())
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.size(375.dp, 780.dp)) {
                    NetworkTraceList(
                        NetworkTraceSnapshot(listOf(trace(1), trace(2))),
                        filter,
                        { filter = it },
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Search requests").performTextInput("/requests/2")
        compose.onNodeWithText("GET /requests/1").assertDoesNotExist()
        compose.onNodeWithText("GET /requests/2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filter requests").performClick()
        compose.onNodeWithText("Matrix").performClick()
        compose.onNodeWithText("No matching requests").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("/requests/2", filter.query)
            assertEquals(NetworkSource.MATRIX, filter.source)
        }
    }

    @Test
    fun clearedSelectionErasesDetailsInsteadOfRetainingAnEvictedTrace() {
        var selected: NetworkTrace? by mutableStateOf(trace(1, NetworkOutcome.Response(200)))
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.size(375.dp, 640.dp)) { NetworkTraceDetail(selected) }
            }
        }
        compose.onNodeWithText("HTTP 200").assertIsDisplayed()
        compose.onNodeWithText("example.test").assertIsDisplayed()
        compose.runOnIdle { selected = null }
        compose.onNodeWithText("Request is no longer available").assertIsDisplayed()
        compose.onNodeWithText("example.test").assertDoesNotExist()
        compose.onNodeWithText("HTTP 200").assertDoesNotExist()
    }

    private fun trace(id: Long, outcome: NetworkOutcome? = null) =
        NetworkTrace(
            id,
            1_794_096_000_000,
            NetworkRequest(
                NetworkSource.KTOR,
                "GET",
                NetworkEndpoint("https", "example.test", path = "/requests/$id"),
            ),
            outcome,
            if (outcome == null) null else 124,
        )

    @Test
    fun bodyFailureKeepsTheHttpStatusAndShowsTheSeparateTransferFailure() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.size(375.dp, 780.dp)) {
                    NetworkTraceDetail(
                        trace(1, NetworkOutcome.Response(200))
                            .copy(
                                transfer = NetworkTransfer.Complete(300, NetworkFailure.CONNECTION)
                            )
                    )
                }
            }
        }
        compose.onNodeWithText("HTTP 200").assertIsDisplayed()
        compose
            .onNodeWithText("Connection failed", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun completedTransferWithoutHeadersIsUnavailableAndNotPending() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.size(375.dp, 640.dp)) {
                    NetworkTraceList(
                        NetworkTraceSnapshot(
                            listOf(
                                trace(1)
                                    .copy(
                                        transfer = NetworkTransfer.Complete(20),
                                        incomplete = true,
                                    )
                            )
                        ),
                        NetworkTraceFilter(),
                        {},
                        {},
                    )
                }
            }
        }
        compose.onNodeWithText("Status unavailable").assertIsDisplayed()
        compose.onNodeWithText("Capture incomplete").assertIsDisplayed()
        compose.onNodeWithText("Pending").assertDoesNotExist()
        compose.onNodeWithText("HTTP 200").assertDoesNotExist()
    }
}
