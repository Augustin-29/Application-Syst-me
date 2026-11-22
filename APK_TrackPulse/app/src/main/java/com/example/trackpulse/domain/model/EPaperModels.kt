package com.example.trackpulse.domain.model

enum class EPaperTemplate(
    val code: Byte,
    val label: String,
    val description: String
) {
    ANTITHEFT_STATUS(0x00, "Statut Antivol", "Veille, batterie, satellites & état cadenas"),
    TRACK_HUD(0x01, "Compteur Circuit", "Vitesse max, G-Force lat/long & chrono"),
    CUSTOM_CARD(0x02, "Écran Personnalisé", "Titre pilote, véhicule et message libre"),
    MINIMAL_QR(0x03, "Badge Identification", "ID boîtier, statut charge & secours"),
    EASTER_EGG(0x04, "Mini-Jeu Arcade", "Rendu 1-bit rafraîchissement partiel 0,3 s");

    companion object {
        fun fromCode(code: Byte): EPaperTemplate =
            entries.firstOrNull { it.code == code } ?: ANTITHEFT_STATUS
    }
}

enum class EPaperRefreshMode(val code: Byte, val label: String, val durationLabel: String) {
    PARTIAL(0x00, "Partiel", "0,3 s (rapide)"),
    FULL(0x01, "Complet", "2,0 s (net, anti-ghosting)");

    companion object {
        fun fromCode(code: Byte): EPaperRefreshMode =
            entries.firstOrNull { it.code == code } ?: PARTIAL
    }
}

data class EPaperConfig(
    val template: EPaperTemplate = EPaperTemplate.TRACK_HUD,
    val refreshMode: EPaperRefreshMode = EPaperRefreshMode.PARTIAL,
    val invertColors: Boolean = false,
    val showBattery: Boolean = true,
    val showGpsSats: Boolean = true,
    val autoSwitchWithMode: Boolean = true,
    val customTitle: String = "TRACKPULSE #01",
    val customSubtitle: String = "KARTING LE MANS"
)

enum class EasterEggGame(
    val code: Byte,
    val title: String,
    val controlsHint: String,
    val description: String
) {
    PULSE_RUNNER(
        code = 0x01,
        title = "Pulse Runner",
        controlsHint = "BP1 (PB2) : Sauter  •  BP2 (PC13) : Glisser",
        description = "Course d'obstacles rétro 200×200 sur E-Paper avec effets sonores Buzzer TIM2."
    ),
    TRACK_DODGE(
        code = 0x02,
        title = "Track Dodge",
        controlsHint = "BP1 (PB2) : Gauche  •  BP2 (PC13) : Droite",
        description = "Slalom circuit 3 voies à vitesse croissante sur dalle Waveshare 1,54\" V2."
    );

    companion object {
        fun fromCode(code: Byte): EasterEggGame =
            entries.firstOrNull { it.code == code } ?: PULSE_RUNNER
    }
}

data class EasterEggScore(
    val game: EasterEggGame,
    val lastScore: Int = 0,
    val highScore: Int = 0,
    val gamesPlayed: Int = 0,
    val playTimeSec: Int = 0
)
