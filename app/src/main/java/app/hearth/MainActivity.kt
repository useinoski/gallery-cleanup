package app.hearth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.viewModels
import app.hearth.ui.HearthApp
import app.hearth.ui.home.homeViewModelFactory
import app.hearth.ui.rememberPermissionReader
import app.hearth.ui.theme.HearthTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<app.hearth.ui.home.HomeViewModel> {
        homeViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HearthTheme {
                HearthApp(
                    viewModel = viewModel,
                    reader = rememberPermissionReader(),
                )
            }
        }
    }
}
