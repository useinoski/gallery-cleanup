package app.hearth.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.hearth.media.MediaStoreMediaRepository
import app.hearth.prefs.HEARTH_PREFS
import app.hearth.prefs.HearthPreferences

fun homeViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val preferences = HearthPreferences(
        appContext.getSharedPreferences(HEARTH_PREFS, Context.MODE_PRIVATE),
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
