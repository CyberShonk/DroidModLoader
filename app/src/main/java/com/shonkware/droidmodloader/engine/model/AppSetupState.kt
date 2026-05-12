package com.shonkware.droidmodloader.engine.model

data class AppSetupState(
    val setupComplete: Boolean,
    val activeProfileId: String?
)