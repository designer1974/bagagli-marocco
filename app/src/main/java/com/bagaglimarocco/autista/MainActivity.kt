package com.bagaglimarocco.autista

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bagaglimarocco.autista.ui.DeliverScreen
import com.bagaglimarocco.autista.ui.MainViewModel
import com.bagaglimarocco.autista.ui.ScanScreen
import com.bagaglimarocco.autista.ui.SignScreen
import com.bagaglimarocco.autista.ui.TripScreen
import com.bagaglimarocco.autista.ui.theme.AutistaTheme
import com.bagaglimarocco.autista.ui.theme.LocalStatus

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    Trip("trip", R.string.tab_trip, Icons.Filled.FormatListBulleted),
    Scan("scan", R.string.tab_scan, Icons.Filled.QrCodeScanner),
    Deliver("deliver", R.string.tab_deliver, Icons.Filled.Place),
    Sign("sign", R.string.tab_sign, Icons.Filled.Draw),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AutistaTheme { AutistaRoot() } }
    }
}

@Composable
private fun AutistaRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lastScanned by vm.lastScanned.collectAsStateWithLifecycle(initialValue = null)
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val lastWasError = remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            lastWasError.value = msg.isError
            snackbar.showSnackbar(context.getString(msg.res, *msg.args.toTypedArray()))
        }
    }

    fun go(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                val status = LocalStatus.current
                Snackbar(
                    snackbarData = data,
                    containerColor = if (lastWasError.value) status.bad else status.ok,
                    contentColor = MaterialTheme.colorScheme.surface,
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { go(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Trip.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Trip.route) { TripScreen(state, onGoNext = { go(Tab.Deliver.route) }) }
            composable(Tab.Scan.route) {
                ScanScreen(
                    state = state,
                    lastScanned = lastScanned,
                    onCode = { code, fromCamera -> vm.onScanned(code, fromCamera) },
                    onDamage = vm::reportDamage,
                )
            }
            composable(Tab.Deliver.route) {
                DeliverScreen(state, onScan = { go(Tab.Scan.route) }, onSign = { go(Tab.Sign.route) })
            }
            composable(Tab.Sign.route) {
                SignScreen(state) { code, signature ->
                    vm.confirmDelivery(code, signature) { go(Tab.Trip.route) }
                }
            }
        }
    }
}
