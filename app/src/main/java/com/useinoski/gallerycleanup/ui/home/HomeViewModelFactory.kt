package com.useinoski.gallerycleanup.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.useinoski.gallerycleanup.media.MediaStoreMediaRepository
import com.useinoski.gallerycleanup.prefs.GALLERY_PREFS
import com.useinoski.gallerycleanup.prefs.GalleryPreferences

fun homeViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val preferences = GalleryPreferences(
        appContext.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE),
    )
    val repository = MediaStoreMediaRepository(appContext.contentResolver)
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    repository = repository,
                    onboardingStore = preferences,
                    audioScanStore = preferences,
                ) as T
            }
            error("Unknown ViewModel: $modelClass")
        }
    }
}
