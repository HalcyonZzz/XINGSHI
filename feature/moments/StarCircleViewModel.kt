package com.xingshi.ai.feature.moments

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.StarCircleGateway
import java.io.File

class StarCircleViewModel(app: Application) : AndroidViewModel(app) {
    val store = StarCircleStore(
        viewModelScope,
        { ServiceRegistry.get(StarCircleGateway::class.java) },
        preparation = ServiceRegistry.get(CircleMediaPreparationProvider::class.java)?.create()
            ?: CircleMediaPreparation.Unavailable,
        localMedia = CircleLocalMediaOwner(File(app.filesDir, "circle_media"))
    )

    init { store.refresh() }
}
