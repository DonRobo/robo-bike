package dev.donrobo.ebike.ui.setup

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.donrobo.ebike.BikeCompanion
import dev.donrobo.ebike.Repo
import dev.donrobo.ebike.nav.MapsListener
import dev.donrobo.ebike.settings.Setting
import dev.donrobo.ebike.settings.settings
import dev.donrobo.ebike.ui.disclosure.Disclosure
import dev.donrobo.ebike.ui.disclosure.DisclosureDialog
import dev.donrobo.ebike.ui.disclosure.disclosureBefore

/** Setup bookkeeping. */
object SetupSettings {
    /** The runtime dialog for [p] has been shown once (to tell "not asked yet" from "denied for good"). */
    fun asked(p: AppPermission) = Setting.bool("setup.asked.${p.name.lowercase()}", false)
}

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

fun readPermissionFacts(ctx: Context): PermissionFacts {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val am = ctx.getSystemService(ActivityManager::class.java)
    return PermissionFacts(
        bluetoothScan = ctx.granted(Manifest.permission.BLUETOOTH_SCAN),
        bluetoothConnect = ctx.granted(Manifest.permission.BLUETOOTH_CONNECT),
        notificationListener = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx),
        fineLocation = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseLocation = ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocation = ctx.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        batteryOptimizationIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
        backgroundRestricted = am?.isBackgroundRestricted == true,
        postNotifications = Build.VERSION.SDK_INT < 33 || ctx.granted(Manifest.permission.POST_NOTIFICATIONS),
        companionAssociated = BikeCompanion.associatedMacs(ctx).isNotEmpty(),
    )
}

/** Runtime permissions behind [p] (empty for special accesses). */
fun runtimePermissions(p: AppPermission): List<String> = when (p) {
    AppPermission.BLUETOOTH -> listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    AppPermission.LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    // Must be asked on its own, after LOCATION; on API 30+ the system answers with its settings page.
    AppPermission.BACKGROUND_LOCATION -> listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    AppPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    AppPermission.NOTIFICATION_ACCESS, AppPermission.BACKGROUND_RUN -> emptyList()
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Current permission states plus "Erlauben" for each, one permission at a time. Refreshes on resume (the rider may
 * come back from a settings page) and after every dialog. Sensitive ones first show their prominent [disclosure]
 * (render it with [PermissionDisclosureHost]); only [agree] goes on to the system.
 */
@Stable
class PermissionRequests internal constructor(
    private val ctx: Context,
    private val launchRuntime: (Array<String>) -> Unit,
    private val launchIntent: (Intent) -> Unit,
) {
    var facts by mutableStateOf(readPermissionFacts(ctx)); private set
    val items: List<PermissionItem> get() = permissionItems(facts)

    /** The system's name for "Allow all the time" (shown in the background-location entry). */
    val backgroundOptionLabel: String = ctx.packageManager.backgroundPermissionOptionLabel.toString()

    /** The disclosure waiting for "Agree" or "Not now" (null = none open). */
    var disclosure by mutableStateOf<Disclosure?>(null); private set
    private var pending: Pair<AppPermission, PermissionAction>? = null

    fun refresh() { facts = readPermissionFacts(ctx) }

    fun request(p: AppPermission) {
        val item = items.first { it.permission == p }
        val perms = runtimePermissions(p)
        val activity = ctx.findActivity()
        val rationale = activity != null && perms.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        val asked = ctx.settings[SetupSettings.asked(p)]
        val action = actionFor(item, asked, rationale, facts.backgroundRestricted)
        val d = disclosureBefore(p, action)
        if (d != null) {
            pending = p to action
            disclosure = d
        } else {
            perform(p, action)
        }
    }

    /** The rider agreed to the open disclosure: on to the system dialog or page. */
    fun agree() {
        val (p, action) = pending ?: return
        pending = null
        disclosure = null
        perform(p, action)
    }

    /** "Not now", back or a tap outside: no consent, nothing is asked. */
    fun decline() {
        pending = null
        disclosure = null
    }

    private fun perform(p: AppPermission, action: PermissionAction) {
        val perms = runtimePermissions(p)
        when (action) {
            PermissionAction.REQUEST -> {
                if (perms.isEmpty()) return
                ctx.settings[SetupSettings.asked(p)] = true
                launchRuntime(perms.toTypedArray())
            }
            PermissionAction.APP_SETTINGS ->
                launchIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            PermissionAction.LISTENER_SETTINGS -> launchIntent(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MapsListener::class.java).flattenToString()),
            )
            PermissionAction.BATTERY_SETTINGS -> launchIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            PermissionAction.NONE -> {}
        }
    }
}

@Composable
fun rememberPermissionRequests(): PermissionRequests {
    val ctx = LocalContext.current
    var holder: PermissionRequests? = null
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { holder?.refresh() }
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { holder?.refresh() }
    val requests = remember {
        PermissionRequests(ctx, { runtime.launch(it) }) { intent ->
            try {
                settingsPage.launch(intent)
            } catch (e: ActivityNotFoundException) {
                // Some systems lack the detail pages: fall back to the general lists.
                val fallback = when (intent.action) {
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    else -> null
                }
                Repo.log("settings page ${intent.action} missing: $e")
                fallback?.let { runCatching { settingsPage.launch(Intent(it)) } }
            }
        }
    }
    holder = requests
    LifecycleResumeEffect(Unit) {
        requests.refresh()
        onPauseOrDispose { }
    }
    return requests
}

/** Shows the disclosure [requests] is waiting on, if any; part of every screen that offers "Erlauben". */
@Composable
fun PermissionDisclosureHost(requests: PermissionRequests) {
    val d = requests.disclosure ?: return
    DisclosureDialog(d, requests::agree, requests::decline, requests.backgroundOptionLabel)
}
