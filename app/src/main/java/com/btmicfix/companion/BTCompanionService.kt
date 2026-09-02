package com.btmicfix.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import com.btmicfix.service.RoutingToggleService
import com.btmicfix.util.Logger

class BTCompanionService :
    CompanionDeviceService() {

    override fun onDeviceAppeared(
        associationInfo: AssociationInfo
    ) {
        super.onDeviceAppeared(
            associationInfo
        )

        Logger.i(
            "Buds connected — waiting for pinch"
        )
    }

    override fun onDeviceDisappeared(
        associationInfo: AssociationInfo
    ) {
        super.onDeviceDisappeared(
            associationInfo
        )

        Logger.i(
            "Buds disconnected"
        )

        stopService(
            Intent(
                this,
                RoutingToggleService::class.java
            )
        )
    }
    }
