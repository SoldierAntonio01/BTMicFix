package com.btmicfix.assistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.btmicfix.service.RoutingToggleService

class ToggleAssistActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        val toggleIntent =
            Intent(
                this,
                RoutingToggleService::class.java
            ).apply {
                action =
                    RoutingToggleService.ACTION_TOGGLE
            }

        ContextCompat.startForegroundService(
            this,
            toggleIntent
        )

        finish()
    }
}
