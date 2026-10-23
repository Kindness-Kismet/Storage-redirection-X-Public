package com.storage.redirect.x.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

@Immutable
data class ScreenRoute(
    val page: String,
    val packageName: String = "",
    val appName: String = "",
    val userId: Int = 0,
) : NavKey

@Stable
class AppNavigator(routes: List<ScreenRoute> = listOf(ScreenRoute("main"))) {
    val backStack = navBackStackOf(*routes.toTypedArray())
    var configVersion by mutableIntStateOf(0)
        private set

    fun push(page: String) = push(ScreenRoute(page))

    fun push(route: ScreenRoute) {
        if (backStack.lastOrNull() != route) backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) {
            val route = backStack.removeLastOrNull() as? ScreenRoute
            if (route?.page in setOf("rules", "templates")) configVersion++
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("Missing AppNavigator") }

@Composable
fun rememberAppNavigator(): AppNavigator =
    rememberSaveable(
        saver =
            listSaver(
                save = { navigator ->
                    navigator.backStack.flatMap { route ->
                        route as ScreenRoute
                        listOf(
                            route.page,
                            route.packageName,
                            route.appName,
                            route.userId.toString(),
                        )
                    }
                },
                restore = { values ->
                    AppNavigator(
                        values.chunked(4).map { ScreenRoute(it[0], it[1], it[2], it[3].toInt()) }
                    )
                },
            )
    ) {
        AppNavigator()
    }
