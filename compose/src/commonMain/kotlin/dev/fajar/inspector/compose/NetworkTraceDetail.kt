package dev.fajar.inspector.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fajar.inspector.NetworkFailure
import dev.fajar.inspector.NetworkOutcome
import dev.fajar.inspector.NetworkSource
import dev.fajar.inspector.NetworkTrace
import dev.fajar.inspector.NetworkTransfer
import kotlin.time.Instant

/** The host supplies the current selected trace; null erases details after clear/eviction. */
@Composable
fun NetworkTraceDetail(trace: NetworkTrace?, modifier: Modifier = Modifier) {
    if (trace == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Request is no longer available",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val request = trace.request
    val endpoint = request.endpoint
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    request.method,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
                TraceOutcome(trace)
            }
        }
        item("source") { TraceField("Source", sourceLabel(request.source)) }
        request.attemptNumber?.let { number ->
            item("attempt") { TraceField("Attempt", number.toString()) }
        }
        item("host") { TraceField("Host", endpoint.host) }
        item("scheme") { TraceField("Protocol", endpoint.scheme) }
        endpoint.port?.let { port -> item("port") { TraceField("Port", port.toString()) } }
        item("path") { TraceField("Path", endpoint.path) }
        item("started") {
            TraceField(
                "Started (UTC)",
                Instant.fromEpochMilliseconds(trace.startedAtEpochMillis).toString(),
            )
        }
        item("duration") {
            TraceField(
                if (trace.outcome is NetworkOutcome.Response) "Time to response" else "Duration",
                trace.durationMillis?.let(::durationLabel)
                    ?: if (trace.transfer == NetworkTransfer.Unavailable) "Not recorded"
                    else "Pending",
            )
        }
        trace.transfer?.let { transfer ->
            item("transfer") {
                TraceField(
                    "Transfer",
                    when (transfer) {
                        NetworkTransfer.InProgress -> "Receiving response"
                        NetworkTransfer.Unavailable -> "Not fully recorded"
                        is NetworkTransfer.Complete ->
                            "${transfer.failure?.let(::failureLabel) ?: "Complete"} (${durationLabel(transfer.durationMillis)})"
                    },
                )
            }
        }
        if (trace.incomplete)
            item("incomplete") {
                Text("Capture incomplete", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        item("bytes") {
            TraceField("Observed response bytes", trace.responseBytes?.toString() ?: "Not recorded")
        }
        if (request.metadataTruncated || trace.responseHeadersTruncated) {
            item("truncated") {
                Text(
                    "Some metadata exceeded capture limits",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (endpoint.queryNames.isNotEmpty()) {
            item("query-title") { TraceSection("Query names") }
            itemsIndexed(endpoint.queryNames, key = { index, _ -> "query-$index" }) { _, name ->
                TraceField(name, "Value not captured")
            }
        }
        item("request-title") { TraceSection("Request headers") }
        if (request.headers.isEmpty()) {
            item("request-empty") {
                Text("No headers recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(request.headers, key = { index, _ -> "request-header-$index" }) { _, header ->
            TraceField(header.name, header.value)
        }
        item("response-title") { TraceSection("Response headers") }
        if (trace.responseHeaders.isEmpty()) {
            item("response-empty") {
                Text("No headers recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(trace.responseHeaders, key = { index, _ -> "response-header-$index" }) {
            _,
            header ->
            TraceField(header.name, header.value)
        }
        item("body") { TraceField("Bodies", "Body content is not captured") }
    }
}

@Composable
private fun TraceSection(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TraceField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
internal fun TraceOutcome(trace: NetworkTrace) {
    val outcome = trace.outcome
    val failed =
        outcome is NetworkOutcome.Failed ||
            (outcome is NetworkOutcome.Response && outcome.status >= 400)
    val label =
        when (outcome) {
            null ->
                if (
                    trace.transfer is NetworkTransfer.Complete ||
                        trace.transfer == NetworkTransfer.Unavailable
                )
                    "Status unavailable"
                else "Pending"
            is NetworkOutcome.Response -> "HTTP ${outcome.status}"
            is NetworkOutcome.Failed -> failureLabel(outcome.category)
        }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

internal fun sourceLabel(source: NetworkSource) =
    when (source) {
        NetworkSource.KTOR -> "Ktor"
        NetworkSource.MATRIX -> "Matrix"
        NetworkSource.CUSTOM -> "Custom"
    }

internal fun durationLabel(milliseconds: Long): String =
    if (milliseconds < 1_000) "$milliseconds ms"
    else "${milliseconds / 1_000}.${(milliseconds % 1_000).toString().padStart(3, '0')} s"

internal fun failureLabel(failure: NetworkFailure): String =
    when (failure) {
        NetworkFailure.DNS -> "DNS failure"
        NetworkFailure.CONNECTION -> "Connection failed"
        NetworkFailure.TLS -> "TLS failure"
        NetworkFailure.TIMEOUT -> "Timeout"
        NetworkFailure.CANCELLED -> "Cancelled"
        NetworkFailure.UNKNOWN -> "Transport failed"
    }
