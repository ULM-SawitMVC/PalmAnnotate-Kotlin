package dev.sawitulm.palmannotate.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.sawitulm.palmannotate.ui.annotation.AnnotationScreen
import dev.sawitulm.palmannotate.ui.capture.CaptureFlowScreen
import dev.sawitulm.palmannotate.ui.carousel.CarouselScreen
import dev.sawitulm.palmannotate.ui.dedup.DeduplicationScreen
import dev.sawitulm.palmannotate.ui.home.HomeScreen
import dev.sawitulm.palmannotate.ui.results.ResultsScreen
import dev.sawitulm.palmannotate.ui.session.SessionDetailScreen
import dev.sawitulm.palmannotate.ui.viewer.DepthViewerScreen

/**
 * Routes. A "session" id is a RUN id; a "tree" is identified by its treeKey.
 *   HOME → SESSION_DETAIL(runId) → CAPTURE(runId) → ANNOTATION(treeKey)
 *                                → ANNOTATION(treeKey) → RESULTS/DEDUP(treeKey)
 */
object Routes {
    const val HOME = "home"
    const val SESSION_DETAIL = "session/{runId}"
    const val CAPTURE = "capture/{runId}"
    const val ANNOTATION = "annotation/{treeKey}"
    const val RESULTS = "results/{treeKey}"
    const val DEDUP = "dedup/{treeKey}"
    const val CAROUSEL = "carousel/{treeKey}"
    const val DEPTH = "depth/{treeKey}"

    fun sessionDetail(runId: String) = "session/$runId"
    fun capture(runId: String) = "capture/$runId"
    fun annotation(treeKey: String) = "annotation/$treeKey"
    fun results(treeKey: String) = "results/$treeKey"
    fun dedup(treeKey: String) = "dedup/$treeKey"
    fun carousel(treeKey: String) = "carousel/$treeKey"
    fun depth(treeKey: String) = "depth/$treeKey"
}

@Composable
fun PalmAnnotateNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(onSessionClick = { runId -> navController.navigate(Routes.sessionDetail(runId)) })
        }

        composable(
            route = Routes.SESSION_DETAIL,
            arguments = listOf(navArgument("runId") { type = NavType.StringType }),
        ) { entry ->
            val runId = entry.arguments?.getString("runId") ?: return@composable
            SessionDetailScreen(
                sessionId = runId,
                onBack = { navController.popBackStack() },
                onAddTree = { navController.navigate(Routes.capture(runId)) },
                // Carousel is now the primary annotation editor (tapping a tree opens it).
                onOpenTree = { treeKey -> navController.navigate(Routes.carousel(treeKey)) },
                onOpenCarousel = { treeKey -> navController.navigate(Routes.carousel(treeKey)) },
            )
        }

        composable(
            route = Routes.CAPTURE,
            arguments = listOf(navArgument("runId") { type = NavType.StringType }),
        ) { entry ->
            val runId = entry.arguments?.getString("runId") ?: return@composable
            CaptureFlowScreen(
                sessionId = runId,
                onTreeSaved = { treeKey ->
                    // Replace the capture screen with the new tree's carousel editor.
                    navController.navigate(Routes.carousel(treeKey)) {
                        popUpTo(Routes.capture(runId)) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.ANNOTATION,
            arguments = listOf(navArgument("treeKey") { type = NavType.StringType }),
        ) { entry ->
            val treeKey = entry.arguments?.getString("treeKey") ?: return@composable
            AnnotationScreen(
                sessionId = treeKey,
                onBack = { navController.popBackStack() },
                onViewResults = { navController.navigate(Routes.results(treeKey)) },
                onOpenDedup = { navController.navigate(Routes.dedup(treeKey)) },
                onOpenCarousel = { navController.navigate(Routes.carousel(treeKey)) },
            )
        }

        composable(
            route = Routes.RESULTS,
            arguments = listOf(navArgument("treeKey") { type = NavType.StringType }),
        ) { entry ->
            val treeKey = entry.arguments?.getString("treeKey") ?: return@composable
            ResultsScreen(
                sessionId = treeKey,
                onBack = { navController.popBackStack() },
                onCaptureNext = { runId ->
                    // Drop this tree's annotation/dedup/results off the stack, keep the
                    // session detail underneath, and open capture for the next tree.
                    navController.navigate(Routes.capture(runId)) {
                        popUpTo(Routes.SESSION_DETAIL) { inclusive = false }
                    }
                },
                onTreeList = { runId ->
                    // Return to the existing session detail (tree list); fall back to a
                    // fresh navigation if it isn't on the back stack for some reason.
                    if (!navController.popBackStack(Routes.SESSION_DETAIL, inclusive = false)) {
                        navController.navigate(Routes.sessionDetail(runId)) {
                            popUpTo(Routes.HOME) { inclusive = false }
                        }
                    }
                },
            )
        }

        composable(
            route = Routes.DEDUP,
            arguments = listOf(navArgument("treeKey") { type = NavType.StringType }),
        ) { entry ->
            val treeKey = entry.arguments?.getString("treeKey") ?: return@composable
            DeduplicationScreen(
                sessionId = treeKey,
                onBack = { navController.popBackStack() },
                onCompute = { navController.navigate(Routes.results(treeKey)) },
            )
        }

        composable(
            route = Routes.CAROUSEL,
            arguments = listOf(navArgument("treeKey") { type = NavType.StringType }),
        ) { entry ->
            val treeKey = entry.arguments?.getString("treeKey") ?: return@composable
            CarouselScreen(
                sessionId = treeKey,
                onBack = { navController.popBackStack() },
                onDedup = { navController.navigate(Routes.dedup(treeKey)) },
                onResults = { navController.navigate(Routes.results(treeKey)) },
                onDepth = { navController.navigate(Routes.depth(treeKey)) },
                onNextTree = { runId ->
                    // Save current tree, then open capture for the next tree; drop this
                    // tree's carousel off the stack so Back returns to the tree list.
                    navController.navigate(Routes.capture(runId)) {
                        popUpTo(Routes.SESSION_DETAIL) { inclusive = false }
                    }
                },
            )
        }

        composable(
            route = Routes.DEPTH,
            arguments = listOf(navArgument("treeKey") { type = NavType.StringType }),
        ) { entry ->
            val treeKey = entry.arguments?.getString("treeKey") ?: return@composable
            DepthViewerScreen(treeKey = treeKey, onBack = { navController.popBackStack() })
        }
    }
}
