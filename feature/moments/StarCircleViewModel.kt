package com.xingshi.ai.feature.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingshi.ai.domain.ServiceRegistry
import com.xingshi.ai.domain.StarCircleGateway

class StarCircleViewModel : ViewModel() {
    val store = StarCircleStore(viewModelScope) { ServiceRegistry.get(StarCircleGateway::class.java) }

    init { store.refresh() }
}
