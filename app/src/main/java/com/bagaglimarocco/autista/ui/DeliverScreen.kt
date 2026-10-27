package com.bagaglimarocco.autista.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bagaglimarocco.autista.R
import com.bagaglimarocco.autista.data.StopKind
import com.bagaglimarocco.autista.util.NavApps

@Composable
fun DeliverScreen(state: UiState, onScan: () -> Unit, onSign: () -> Unit) {
    val current = state.current
    if (current == null) {
        EmptyNote(stringResource(R.string.all_done))
        return
    }
    val context = LocalContext.current
    val stop = current.stop
    val noNav = stringResource(R.string.no_nav_app)
    fun warnIfFailed(ok: Boolean) {
        if (!ok) Toast.makeText(context, noNav, Toast.LENGTH_LONG).show()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SurfaceCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val kind = stringResource(if (stop.kind == StopKind.HOME) R.string.kind_home else R.string.kind_agency)
                    Text(
                        "${stringResource(R.string.stop_n_of, stop.position, state.stops.size)} · $kind",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stop.name, style = MaterialTheme.typography.headlineSmall)
                    Text("${stop.address}, ${stop.city}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${stringResource(R.string.bags_count, current.bags.size)} · ${stop.phone}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    stringResource(R.string.open_maps),
                    { warnIfFailed(NavApps.openGoogleMaps(context, stop.latitude, stop.longitude)) },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Map,
                )
                BigButton(
                    stringResource(R.string.open_waze),
                    { warnIfFailed(NavApps.openWaze(context, stop.latitude, stop.longitude)) },
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Navigation,
                    container = MaterialTheme.colorScheme.secondary,
                    content = MaterialTheme.colorScheme.onSecondary,
                )
            }
        }
        item {
            Text(
                stringResource(R.string.nav_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedButton(onClick = { warnIfFailed(NavApps.dial(context, stop.phone)) }, shape = CardShape) {
                Icon(Icons.Filled.Phone, null)
                Text("  " + stringResource(R.string.call))
            }
        }
        item { SectionTitle(stringResource(R.string.bags_for_stop)) }
        items(current.bags, key = { it.code }) { BagRow(it) }
        item {
            if (current.remaining.isNotEmpty()) {
                BigButton(stringResource(R.string.scan_bags), onScan, icon = Icons.Filled.QrCodeScanner)
            } else {
                BigButton(stringResource(R.string.go_sign), onSign, icon = Icons.Filled.Draw)
            }
        }
    }
}
