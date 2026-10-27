package com.bagaglimarocco.autista.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bagaglimarocco.autista.R
import com.bagaglimarocco.autista.data.StopKind
import com.bagaglimarocco.autista.data.StopStatus
import com.bagaglimarocco.autista.ui.theme.LocalStatus

@Composable
fun TripScreen(
    state: UiState,
    driverName: String,
    demo: Boolean,
    busy: Boolean,
    onGoNext: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val status = LocalStatus.current
    val current = state.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                val trip = state.trip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(driverName.ifBlank { null }, trip?.vehiclePlate).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (demo) Pill(stringResource(R.string.demo_badge), MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
                    if (busy) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(start = 8.dp).size(24.dp))
                    } else if (!demo) {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh), tint = Color.White) }
                    }
                    IconButton(onClick = onLogout, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.logout), tint = Color.White)
                    }
                }
                Text(trip?.route ?: stringResource(R.string.trip_title), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val total = state.stops.size.coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { state.deliveredCount / total.toFloat() },
                        modifier = Modifier.weight(1f).height(8.dp).clip(MaterialTheme.shapes.small),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                    Text(
                        stringResource(R.string.progress, state.deliveredCount, state.stops.size),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        state.trip?.let { trip ->
            item {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(CardShape)
                        .background(status.waitSoft)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(42.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.secondary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.DirectionsBoat, null, tint = MaterialTheme.colorScheme.onSecondary) }
                    Column {
                        Text("${stringResource(R.string.ferry)} ${trip.ferryRoute}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.ferry_info, trip.boardingBy, trip.departure) + " · " +
                                stringResource(R.string.bags_count, state.totalBags),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.trip == null) {
            item { EmptyNote(stringResource(R.string.no_trip)) }
            return@LazyColumn
        }

        item {
            Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle(stringResource(R.string.deliveries)) }
        }

        items(state.stops, key = { it.stop.id }) { sw ->
            val stop = sw.stop
            val done = stop.status == StopStatus.DELIVERED
            val active = current?.stop?.id == stop.id
            SurfaceCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                borderColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                borderWidth = if (active) 2 else 1,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberBadge(stop.position, done, active)
                    Column(Modifier.weight(1f)) {
                        Text("${stop.name} — ${stop.city}", style = MaterialTheme.typography.titleMedium)
                        val kind = stringResource(if (stop.kind == StopKind.HOME) R.string.kind_home else R.string.kind_agency)
                        Text(
                            "${stringResource(R.string.bags_count, sw.bags.size)} · $kind",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        done -> Pill(stringResource(R.string.status_delivered), status.okSoft, status.ok)
                        active -> Pill(stringResource(R.string.status_next), status.waitSoft, MaterialTheme.colorScheme.onSurface)
                        else -> Pill(stringResource(R.string.status_pending), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current != null) {
                    BigButton(stringResource(R.string.go_next), onGoNext, icon = Icons.Filled.Navigation)
                } else {
                    EmptyNote(stringResource(R.string.all_done))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pending = state.unsyncedCount > 0
                    Icon(
                        if (pending) Icons.Filled.CloudUpload else Icons.Filled.CloudDone,
                        null,
                        tint = if (pending) MaterialTheme.colorScheme.onSurfaceVariant else status.ok,
                    )
                    Text(
                        if (pending) stringResource(R.string.to_sync, state.unsyncedCount) else stringResource(R.string.all_synced),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
