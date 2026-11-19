package com.useinoski.gallerycleanup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.useinoski.gallerycleanup.ui.GalleryApp
import com.useinoski.gallerycleanup.ui.home.homeViewModelFactory
import com.useinoski.gallerycleanup.ui.rememberPermissionReader
import com.useinoski.gallerycleanup.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<com.useinoski.gallerycleanup.ui.home.HomeViewModel> {
        homeViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GalleryTheme {
                GalleryApp(
                    viewModel = viewModel,
                    reader = rememberPermissionReader(),
                )
            }
        }
    }
}
