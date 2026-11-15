package dev.donrobo.ebike.ui.components

import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.donrobo.ebike.R
import dev.donrobo.ebike.ui.theme.AppDimens
import dev.donrobo.ebike.ui.theme.AppTheme

/**
 * Scroll behaviours a [ScreenFrame] offers its top bar: [collapsing] for a large bar ([PageHeader]: collapses as
 * content scrolls under it), [pinned] for a small one ([BackHeader]: stays, takes the scrolled-under colour). The
 * header shown claims its behaviour ([claim]) and the frame routes the content's nested scroll to it, so a small bar
 * never scrolls away (a small bar under exit-until-collapsed would collapse to nothing).
 */
@OptIn(ExperimentalMaterial3Api::class)
class ScreenScroll internal constructor(val collapsing: TopAppBarScrollBehavior, val pinned: TopAppBarScrollBehavior) {
    private var active: TopAppBarScrollBehavior = pinned

    /** The header in composition uses [behavior]; returns it. */
    @Composable
    internal fun claim(behavior: TopAppBarScrollBehavior): TopAppBarScrollBehavior {
        SideEffect { active = behavior }
        return behavior
    }

    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) = active.nestedScrollConnection.onPreScroll(available, source)
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            active.nestedScrollConnection.onPostScroll(consumed, available, source)
        override suspend fun onPreFling(available: Velocity) = active.nestedScrollConnection.onPreFling(available)
        override suspend fun onPostFling(consumed: Velocity, available: Velocity) = active.nestedScrollConnection.onPostFling(consumed, available)
    }
}

private val LocalScreenScroll = staticCompositionLocalOf<ScreenScroll?> { null }

/**
 * Header of sub-screens: Material small [TopAppBar] with a back [IconButton] and the title; [trailing] = actions.
 * Inside a [Screen]/[ScreenFrame] it stays pinned and takes Material's scrolled-under colour when content scrolls
 * under it; it adds the status-bar inset itself. [overMedia]: the screen starts with a picture (map) under the bar,
 * so the bar is transparent with tonal round buttons until content scrolls under it, and the title fades in then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackHeader(
    title: String, onBack: () -> Unit, modifier: Modifier = Modifier, overMedia: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val behavior = LocalScreenScroll.current?.let { it.claim(it.pinned) }
    val overlap = if (overMedia) behavior?.state?.overlappedFraction ?: 0f else 1f
    TopAppBar(
        title = { Text(title, Modifier.graphicsLayer { alpha = overlap }, style = AppTheme.type.barTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { RoundIconButton(AppIcons.ArrowBack, stringResource(R.string.common_back), onBack, bordered = overMedia) },
        actions = { trailing?.invoke(this) },
        colors = if (overMedia) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors(),
        scrollBehavior = behavior,
    )
}

/**
 * Material icon button. Plain [IconButton] by default; [bordered] = a tonal button on the `surface` colour that stays
 * readable over a map. [size] > 48 for glove-sized targets (handlebar tile editor).
 */
@Composable
fun RoundIconButton(
    icon: LineIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    size: Dp = AppDimens.touch,
    enabled: Boolean = true,
) {
    val m = modifier.then(if (size != AppDimens.touch) Modifier.size(size) else Modifier)
    if (bordered) FilledTonalIconButton(
        onClick, m, enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { AppIcon(icon, contentDescription) }
    else IconButton(onClick, m, enabled) { AppIcon(icon, contentDescription) }
}

/**
 * Header of the tab roots: Material [LargeTopAppBar] with the page title (Pixel's large title), which collapses as
 * content scrolls. [trailing] = actions on the title's row (e.g. "Rad wechseln"). [subtitle] and [below] (e.g.
 * Liste | Statistik tabs) sit under the bar and do not collapse with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val behavior = LocalScreenScroll.current?.let { it.claim(it.collapsing) }
    // One container colour for the bar and everything under it (subtitle, tabs): otherwise the bar turns to its
    // scrolled colour while the transparent tab row lets the list show through, its divider drawn across the cards.
    val defaults = TopAppBarDefaults.topAppBarColors()
    val fraction = behavior?.state?.collapsedFraction ?: 0f
    val container = lerp(defaults.containerColor, defaults.scrolledContainerColor, FastOutLinearInEasing.transform(fraction))
    Column(modifier.fillMaxWidth().background(container)) {
        // The bar draws the title twice, large in the expanded row (headlineMedium) and small in the collapsed 64 dp row
        // (titleLarge); it picks the style per row, so the title must not set one. Here those two roles are the page
        // title and the back header's title.
        val type = MaterialTheme.typography
        MaterialTheme(typography = type.copy(headlineMedium = AppTheme.type.pageTitle, titleLarge = AppTheme.type.barTitle)) {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { trailing?.invoke(this) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container),
                scrollBehavior = behavior,
            )
        }
        if (subtitle != null) Text(
            subtitle, Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (below == null) 8.dp else 0.dp),
            style = AppTheme.type.bodySmall, color = AppTheme.colors.textMuted,
        )
        if (below != null) Column(Modifier.fillMaxWidth().padding(top = 4.dp), content = below)
    }
}

/**
 * Material [Scaffold] for a screen with a top bar ([BackHeader] or [PageHeader]) and its own scrolling [content],
 * e.g. a lazy list. [content] receives the padding for the bar and the system bars; apply it as content padding so
 * the list scrolls under the bar and the gesture area. The bar's scroll behaviour is wired up here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(header: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable (PaddingValues) -> Unit) {
    val collapsing = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pinned = TopAppBarDefaults.pinnedScrollBehavior()
    val scroll = remember(collapsing, pinned) { ScreenScroll(collapsing, pinned) }
    CompositionLocalProvider(LocalScreenScroll provides scroll) {
        Scaffold(
            modifier.fillMaxSize().nestedScroll(scroll.connection),
            topBar = header,
            containerColor = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

/** Page padding for a [ScreenFrame] body: the frame's [inner] padding plus 20 dp sides, [top] and 24 dp bottom. */
@Composable
fun pagePadding(inner: PaddingValues, top: Dp = 8.dp): PaddingValues {
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return PaddingValues(
        start = inner.calculateStartPadding(dir) + AppDimens.pagePadding,
        end = inner.calculateEndPadding(dir) + AppDimens.pagePadding,
        top = inner.calculateTopPadding() + top,
        bottom = inner.calculateBottomPadding() + 24.dp,
    )
}

/** [ScreenFrame] with a scrolling column body: 20 dp sides, [bodyTop] above, [gap] between cards. */
@Composable
fun Screen(header: @Composable () -> Unit, modifier: Modifier = Modifier, bodyTop: Dp = 8.dp, gap: Dp = AppDimens.cardGap, body: @Composable ColumnScope.() -> Unit) {
    ScreenFrame(header, modifier) { inner ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding(inner, bodyTop)),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = body,
        )
    }
}

/** One entry of the bottom navigation. */
data class BottomNavItem(val label: String, val icon: LineIcon)

/**
 * Material 3 [NavigationBar] (scheme colours, primary label when selected, labels always shown). Adds the
 * navigation-bar inset itself.
 */
@Composable
fun BottomNavBar(items: List<BottomNavItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier) {
        items.forEachIndexed { i, item ->
            val sel = i == selectedIndex
            NavigationBarItem(
                selected = sel,
                onClick = { onSelect(i) },
                icon = { AppIcon(item.icon, null, size = 24.dp, strokeWidth = if (sel) 2.2f else 1.8f) },
                label = { Text(item.label, maxLines = 1, style = if (sel) AppTheme.type.tab.copy(fontWeight = AppTheme.type.labelStrong.fontWeight) else AppTheme.type.tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = AppTheme.colors.textMuted,
                    unselectedTextColor = AppTheme.colors.textMuted,
                ),
            )
        }
    }
}

