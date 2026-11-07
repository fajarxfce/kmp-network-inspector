package dev.fajar.inspector.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fajar.inspector.NetworkOutcome
import dev.fajar.inspector.NetworkSource
import dev.fajar.inspector.NetworkTrace
import dev.fajar.inspector.NetworkTraceSnapshot
import dev.fajar.inspector.NetworkTransfer

/**
 * A state-driven request list. The host owns snapshot collection, filter persistence, selection,
 * capture lifetime and navigation. [actions] can contain host-owned clear/pause controls.
 */
@Composable
fun NetworkTraceList(
    snapshot: NetworkTraceSnapshot,
    filter: NetworkTraceFilter,
    onFilterChange: (NetworkTraceFilter) -> Unit,
    onTraceSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val visible = remember(snapshot.traces, filter) { snapshot.traces.filter(filter::includes) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = filter.query.take(256),
                onValueChange = { onFilterChange(filter.copy(query = it.take(256))) },
                modifier = Modifier.weight(1f),
                label = { Text("Search requests", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
            )
            TraceFilters(filter, onFilterChange)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (snapshot.closed) "Capture stopped"
                else "${visible.size} of ${snapshot.traces.size} requests",
                Modifier.weight(1f).padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actions()
        }
        if (snapshot.evictedRequests > 0) {
            Text(
                "${snapshot.evictedRequests} earlier requests removed from this capture",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.droppedObservations > 0) {
            Text(
                "${snapshot.droppedObservations} observations missed",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        if (visible.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (snapshot.traces.isEmpty()) "No captured requests"
                    else "No matching requests",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(visible, key = { it.id }, contentType = { "request" }) { trace ->
                    TraceRow(trace, { onTraceSelected(trace.id) })
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun TraceRow(trace: NetworkTrace, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${trace.request.method} ${trace.request.endpoint.path}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${trace.request.endpoint.host} · ${sourceLabel(trace.request.source)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TraceOutcome(trace)
            (trace.transfer as? NetworkTransfer.Complete)
                ?.failure
                ?.takeIf { trace.outcome is NetworkOutcome.Response }
                ?.let {
                    Text(
                        failureLabel(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            if (trace.incomplete)
                Text(
                    "Capture incomplete",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            trace.durationMillis?.let {
                Text(
                    durationLabel(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TraceFilters(filter: NetworkTraceFilter, onChange: (NetworkTraceFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val color =
        if (filter.source != null || filter.status != NetworkTraceStatus.ALL)
            MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Filter requests" },
        ) {
            Canvas(Modifier.size(24.dp)) {
                listOf(3f to 21f, 6f to 18f, 10f to 14f).forEachIndexed { index, (start, end) ->
                    val y = (6 + index * 6).dp.toPx()
                    drawLine(
                        color,
                        Offset(start.dp.toPx(), y),
                        Offset(end.dp.toPx(), y),
                        2.dp.toPx(),
                    )
                }
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Text(
                "Source",
                Modifier.padding(16.dp).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            DropdownMenuItem(
                text = { Text("All sources") },
                leadingIcon = { RadioButton(filter.source == null, onClick = null) },
                onClick = {
                    onChange(filter.copy(source = null))
                    expanded = false
                },
            )
            NetworkSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text(sourceLabel(source)) },
                    leadingIcon = { RadioButton(filter.source == source, onClick = null) },
                    onClick = {
                        onChange(filter.copy(source = source))
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            Text(
                "Status",
                Modifier.padding(16.dp).semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
            )
            NetworkTraceStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(statusLabel(status)) },
                    leadingIcon = { RadioButton(filter.status == status, onClick = null) },
                    onClick = {
                        onChange(filter.copy(status = status))
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun statusLabel(status: NetworkTraceStatus) =
    when (status) {
        NetworkTraceStatus.ALL -> "All statuses"
        NetworkTraceStatus.PENDING -> "Pending"
        NetworkTraceStatus.HTTP_ERROR -> "HTTP errors"
        NetworkTraceStatus.TRANSPORT_FAILURE -> "Transport failures"
    }
