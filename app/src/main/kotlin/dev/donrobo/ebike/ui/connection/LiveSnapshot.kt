package dev.donrobo.ebike.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.Snapshot
import dev.donrobo.ebike.settings.settings
import kotlinx.coroutines.flow.flowOf

/** [Repo.snapshot] of [bikeId] for a screen: live while that bike is connected, else its last known state. */
@Composable
fun rememberSnapshot(bikeId: String?): Snapshot? {
    val settings = LocalContext.current.settings
    val flow = remember(bikeId) { bikeId?.let { Repo.snapshotFlow(settings, it) } ?: flowOf(null) }
    return flow.collectAsStateWithLifecycle(bikeId?.let { Repo.snapshot(settings, it) }).value
}
