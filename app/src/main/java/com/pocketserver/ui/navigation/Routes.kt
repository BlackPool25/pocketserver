package com.pocketserver.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Services : Route

    @Serializable
    data object Packages : Route

    @Serializable
    data object Activity : Route

    @Serializable
    data object System : Route

    @Serializable
    data class Detail(val serviceId: String) : Route
}
