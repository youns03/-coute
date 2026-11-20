package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Library : AppRoute

    @Serializable
    data class Reader(val bookId: String) : AppRoute

    @Serializable
    data object FileTranslator : AppRoute

    @Serializable
    data object PlayerLab : AppRoute

    @Serializable
    data object Models : AppRoute

    @Serializable
    data object Vocabulary : AppRoute

    @Serializable
    data object Settings : AppRoute
}
