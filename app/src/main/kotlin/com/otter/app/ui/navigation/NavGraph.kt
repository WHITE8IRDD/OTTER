package com.otter.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.otter.app.ui.screens.cookies.CookiesScreen
import com.otter.app.ui.screens.history.HistoryScreen
import androidx.compose.ui.res.stringResource
import com.otter.app.R
import com.otter.app.ui.screens.home.HomeScreen
import com.otter.app.ui.screens.settings.SettingsScreen
import com.otter.app.ui.theme.ThemeViewModel

data class NavItem(
    val route: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val labelResId: Int
)

private val navItems = listOf(
    NavItem("home", Icons.Outlined.Download, Icons.Filled.Download, R.string.nav_home),
    NavItem("history", Icons.Outlined.History, Icons.Filled.History, R.string.nav_history),
    NavItem("settings", Icons.Outlined.Settings, Icons.Filled.Settings, R.string.nav_settings)
)

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object History : Screen("history")
    object Settings : Screen("settings")
    object Cookies : Screen("cookies")
}

@Composable
fun NavGraph(
    sharedUrl: String? = null,
    navigateToCookieLogin: Boolean = false,
    onSharedUrlConsumed: () -> Unit = {},
    themeViewModel: ThemeViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    LaunchedEffect(navigateToCookieLogin) {
        if (navigateToCookieLogin) {
            navController.navigate(Screen.Cookies.route)
        }
    }

    val haptic = LocalHapticFeedback.current

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .padding(top = 1.dp)
            ) {
                navItems.forEach { item ->
                    val selected = currentRoute == item.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.icon,
                                contentDescription = stringResource(item.labelResId),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(item.labelResId),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                route = Screen.Home.route,
                enterTransition = { fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it / 12 }, animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(180)) }
            ) {
                HomeScreen(
                    sharedUrl = sharedUrl,
                    onSharedUrlConsumed = onSharedUrlConsumed,
                    onNavigateToCookies = { navController.navigate(Screen.Cookies.route) },
                    themeViewModel = themeViewModel
                )
            }
            composable(
                route = Screen.History.route,
                enterTransition = { fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it / 12 }, animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(180)) }
            ) {
                HistoryScreen(
                    themeViewModel = themeViewModel,
                    onNavigateToHome = { navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route) { inclusive = true } } }
                )
            }
            composable(
                route = Screen.Settings.route,
                enterTransition = { fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it / 12 }, animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(180)) }
            ) {
                SettingsScreen(
                    onCookieLogin = { navController.navigate(Screen.Cookies.route) },
                    onSiteCookies = { navController.navigate(Screen.Cookies.route) },
                    themeViewModel = themeViewModel
                )
            }
            composable(
                route = Screen.Cookies.route,
                enterTransition = { fadeIn(animationSpec = tween(220)) + slideInVertically(initialOffsetY = { it / 12 }, animationSpec = tween(220)) },
                exitTransition = { fadeOut(animationSpec = tween(180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(220)) },
                popExitTransition = { fadeOut(animationSpec = tween(180)) }
            ) {
                CookiesScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
