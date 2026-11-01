package top.bangwu.taildeck

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import top.bangwu.taildeck.ui.HomeScreen
import top.bangwu.taildeck.ui.LoginScreen
import top.bangwu.taildeck.ui.LogsScreen
import top.bangwu.taildeck.ui.Motion
import top.bangwu.taildeck.ui.NodeScreen
import top.bangwu.taildeck.ui.SettingsScreen
import top.bangwu.taildeck.ui.TaildeckTheme
import top.bangwu.taildeck.ui.TerminalScreen
import top.bangwu.taildeck.ui.WebScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TaildeckTheme { AppNav() } }
    }
}

object Routes {
    fun node(name: String) = "node/${Uri.encode(name)}"
    fun web(url: String, title: String) = "web?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
    fun ssh(node: String) = "ssh/${Uri.encode(node)}"
}

@Composable
private fun AppNav() {
    val app = TaildeckApp.instance
    val nav = rememberNavController()
    val state by app.tailscale.state.collectAsStateWithLifecycle()
    val start = if (app.store.configured) "home" else "login"

    // The backend passes through NeedsLogin on every start; only an auth URL
    // means the stored login is really gone.
    LaunchedEffect(state.phase, state.authUrl) {
        val route = nav.currentDestination?.route
        if (state.phase == Phase.NEEDS_LOGIN && state.authUrl != null && route != "login") {
            nav.navigate("login") { popUpTo(0) }
        }
    }

    NavHost(
        nav,
        startDestination = start,
        // Material shared-axis style: forward pushes in from the right,
        // back (including predictive back) reverses it.
        enterTransition = { slideInHorizontally(Motion.slow()) { it / 5 } + fadeIn(Motion.slow()) },
        exitTransition = { slideOutHorizontally(Motion.slow()) { -it / 10 } + fadeOut(Motion.fast()) },
        popEnterTransition = { slideInHorizontally(Motion.slow()) { -it / 10 } + fadeIn(Motion.slow()) },
        popExitTransition = { slideOutHorizontally(Motion.slow()) { it / 5 } + fadeOut(Motion.fast()) },
    ) {
        composable(
            "login",
            enterTransition = { fadeIn(Motion.slow()) },
            exitTransition = { fadeOut(Motion.fast()) },
        ) {
            LoginScreen(onReady = { nav.navigate("home") { popUpTo(0) } })
        }
        composable(
            "home",
            enterTransition = {
                if (initialState.destination.route == "login") fadeIn(Motion.slow()) + scaleIn(Motion.slow(), initialScale = 0.96f)
                else slideInHorizontally(Motion.slow()) { it / 5 } + fadeIn(Motion.slow())
            },
        ) {
            HomeScreen(
                onNode = { nav.navigate(Routes.node(it)) },
                onOpenInApp = { nav.navigate(Routes.web(it.url, it.name)) },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("node/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) {
            NodeScreen(
                name = it.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenInApp = { url, title -> nav.navigate(Routes.web(url, title)) },
                onSsh = { node -> nav.navigate(Routes.ssh(node)) },
            )
        }
        composable(
            "web?url={url}&title={title}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            WebScreen(
                url = it.arguments?.getString("url").orEmpty(),
                title = it.arguments?.getString("title").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable("ssh/{node}", arguments = listOf(navArgument("node") { type = NavType.StringType })) {
            TerminalScreen(node = it.arguments?.getString("node").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onLogs = { nav.navigate("logs") },
                onLoggedOut = { nav.navigate("login") { popUpTo(0) } },
            )
        }
        composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
    }
}
