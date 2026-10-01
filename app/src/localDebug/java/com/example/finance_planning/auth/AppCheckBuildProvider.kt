package com.example.finance_planning.auth

internal object AppCheckBuildProvider {
    fun create(): com.google.firebase.appcheck.AppCheckProviderFactory =
        com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
}
