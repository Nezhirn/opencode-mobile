package ai.opencode.mobile.ui

import ai.opencode.mobile.OpenCodeApplication
import ai.opencode.mobile.data.AppRepository
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras

fun CreationExtras.repository(): AppRepository =
    (this[APPLICATION_KEY] as OpenCodeApplication).repository
