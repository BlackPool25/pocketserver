package com.pocketserver.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.pocketserver.R
import com.pocketserver.ui.activity.ActivityScreen
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.packages.PackagesScreen
import com.pocketserver.ui.services.ServiceDetailScreen
import com.pocketserver.ui.services.ServicesScreen
import com.pocketserver.ui.system.SystemScreen
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun AppShell(
    viewModel: PocketServerViewModel,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    val backStack = rememberNavBackStack(Route.Services)
    val currentRoute by remember {
        derivedStateOf { backStack.lastOrNull() ?: Route.Services }
    }

    val isImmersive = currentRoute is Route.Detail

    BackHandler(enabled = backStack.size > 1) {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        }
    }

    val entries: (NavKey) -> NavEntry<NavKey> = remember(viewModel) {
        { key ->
            val route = key as? Route ?: Route.Services
            NavEntry(key) {
                when (route) {
                    is Route.Services -> {
                        ServicesScreen(
                            viewModel = viewModel,
                            onNavigateToDetail = { id -> backStack.add(Route.Detail(id)) },
                            onNavigateToPackages = {
                                backStack.removeAll { it is Route.Packages }
                                backStack.add(Route.Packages)
                            },
                            onNavigateToSystem = {
                                backStack.removeAll { it is Route.System }
                                backStack.add(Route.System)
                            }
                        )
                    }
                    is Route.Packages -> {
                        PackagesScreen(viewModel = viewModel)
                    }
                    is Route.Activity -> {
                        ActivityScreen(
                            viewModel = viewModel,
                            onNavigateToServices = {
                                backStack.clear()
                                backStack.add(Route.Services)
                            },
                            onNavigateToDetail = { id ->
                                backStack.add(Route.Detail(id))
                            }
                        )
                    }
                    is Route.System -> {
                        SystemScreen(viewModel = viewModel)
                    }
                    is Route.Detail -> {
                        ServiceDetailScreen(
                            serviceId = route.serviceId,
                            viewModel = viewModel,
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeAt(backStack.size - 1)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(colors.background)) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // Adaptive Navigation Rail (>= 600dp)
            Row(modifier = Modifier.fillMaxSize()) {
                if (!isImmersive) {
                    NavigationRail(
                        containerColor = colors.raised,
                        contentColor = colors.textPrimary,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        NavigationRailItem(
                            selected = currentRoute is Route.Services,
                            onClick = {
                                backStack.clear()
                                backStack.add(Route.Services)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_layers),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_services), style = typography.caption) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = colors.surface,
                                selectedIconColor = colors.textPrimary,
                                unselectedIconColor = colors.textTertiary,
                                selectedTextColor = colors.textPrimary,
                                unselectedTextColor = colors.textTertiary
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute is Route.Packages,
                            onClick = {
                                backStack.removeAll { it is Route.Packages }
                                backStack.add(Route.Packages)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_package_2),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_packages), style = typography.caption) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = colors.surface,
                                selectedIconColor = colors.textPrimary,
                                unselectedIconColor = colors.textTertiary,
                                selectedTextColor = colors.textPrimary,
                                unselectedTextColor = colors.textTertiary
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute is Route.Activity,
                            onClick = {
                                backStack.removeAll { it is Route.Activity }
                                backStack.add(Route.Activity)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_history),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_activity), style = typography.caption) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = colors.surface,
                                selectedIconColor = colors.textPrimary,
                                unselectedIconColor = colors.textTertiary,
                                selectedTextColor = colors.textPrimary,
                                unselectedTextColor = colors.textTertiary
                            )
                        )
                        NavigationRailItem(
                            selected = currentRoute is Route.System,
                            onClick = {
                                backStack.removeAll { it is Route.System }
                                backStack.add(Route.System)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_system), style = typography.caption) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = colors.surface,
                                selectedIconColor = colors.textPrimary,
                                unselectedIconColor = colors.textTertiary,
                                selectedTextColor = colors.textPrimary,
                                unselectedTextColor = colors.textTertiary
                            )
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = entries
                    )
                }
            }
        } else {
            // Phone Navigation (< 600dp): Bottom Navigation Bar
            Scaffold(
                containerColor = colors.background,
                bottomBar = {
                    if (!isImmersive) {
                        NavigationBar(
                            containerColor = colors.raised,
                            contentColor = colors.textPrimary,
                            tonalElevation = 0.dp,
                            modifier = Modifier.navigationBarsPadding()
                        ) {
                            NavigationBarItem(
                                selected = currentRoute is Route.Services,
                                onClick = {
                                    backStack.clear()
                                    backStack.add(Route.Services)
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_layers),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_services), style = typography.caption) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = colors.surface,
                                    selectedIconColor = colors.textPrimary,
                                    unselectedIconColor = colors.textTertiary,
                                    selectedTextColor = colors.textPrimary,
                                    unselectedTextColor = colors.textTertiary
                                )
                            )
                            NavigationBarItem(
                                selected = currentRoute is Route.Packages,
                                onClick = {
                                    backStack.removeAll { it is Route.Packages }
                                    backStack.add(Route.Packages)
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_package_2),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_packages), style = typography.caption) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = colors.surface,
                                    selectedIconColor = colors.textPrimary,
                                    unselectedIconColor = colors.textTertiary,
                                    selectedTextColor = colors.textPrimary,
                                    unselectedTextColor = colors.textTertiary
                                )
                            )
                            NavigationBarItem(
                                selected = currentRoute is Route.Activity,
                                onClick = {
                                    backStack.removeAll { it is Route.Activity }
                                    backStack.add(Route.Activity)
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_history),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_activity), style = typography.caption) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = colors.surface,
                                    selectedIconColor = colors.textPrimary,
                                    unselectedIconColor = colors.textTertiary,
                                    selectedTextColor = colors.textPrimary,
                                    unselectedTextColor = colors.textTertiary
                                )
                            )
                            NavigationBarItem(
                                selected = currentRoute is Route.System,
                                onClick = {
                                    backStack.removeAll { it is Route.System }
                                    backStack.add(Route.System)
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_settings),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = { Text(stringResource(R.string.nav_system), style = typography.caption) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = colors.surface,
                                    selectedIconColor = colors.textPrimary,
                                    unselectedIconColor = colors.textTertiary,
                                    selectedTextColor = colors.textPrimary,
                                    unselectedTextColor = colors.textTertiary
                                )
                            )
                        }
                    }
                }
            ) { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = entries
                    )
                }
            }
        }
    }
}
