package com.asadbyte.downloaderapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.asadbyte.downloaderapp.download.DownloaderScreen
import com.asadbyte.downloaderapp.history.HistoryScreen
import com.asadbyte.downloaderapp.history.HistoryViewModel
import com.asadbyte.downloaderapp.ui.theme.DownloaderAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DownloaderAppTheme {
                MainApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp() {
    val navController = rememberNavController()
    // Use the default viewModel() factory which works with AndroidViewModel
    val historyViewModel: HistoryViewModel = viewModel()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (currentRoute?.startsWith("downloader") == true) "Downloader" else "History") },
                navigationIcon = {
                    if (currentRoute == "history") {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentRoute?.startsWith("downloader") == true) {
                        IconButton(onClick = { navController.navigate("history") }) {
                            Icon(Icons.Default.DateRange, contentDescription = "Download History")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "downloader", // Start at downloader without arguments
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                route = "downloader?url={url}",
                arguments = listOf(navArgument("url") {
                    type = NavType.StringType
                    nullable = true
                })
            ) { backStackEntry ->
                DownloaderScreen(
                    urlToStart = backStackEntry.arguments?.getString("url")
                )
            }
            composable("history") {
                HistoryScreen(
                    viewModel = historyViewModel,
                    navController = navController
                )
            }
        }
    }
}