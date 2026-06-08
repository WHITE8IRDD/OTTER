package com.otter.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
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

sealed class Screen(val route: String, val labelResId: Int, val icon: ImageVector) {
    object Home : Screen("home", R.string.nav_home, Icons.Default.Download)
    object History : Screen("history", R.string.nav_history, Icons.Default.History)
    object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
    object Cookies : Screen("cookies", R.string.nav_settings, Icons.Default.Settings)
}

val bottomNavScreens = listOf(Screen.Home, Screen.History, Screen.Settings)

@Composable
fun NavGraph(
    sharedUrl: String? = null,
    navigateToCookieLogin: Boolean = false,
    onSharedUrlConsumed: () -> Unit = {},
    themeViewModel: ThemeViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(navigateToCookieLogin) {
        if (navigateToCookieLogin) {
            navController.navigate(Screen.Cookies.route)
        }
    }

    val haptic = LocalHapticFeedback.current

    Scaffold(
        bottomBar = {
            Column {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.height(72.dp)
                ) {
                    bottomNavScreens.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = stringResource(screen.labelResId),
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    stringResource(screen.labelResId),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            selected = selected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        )
                    }
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
                HistoryScreen(themeViewModel = themeViewModel)
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
