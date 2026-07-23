package com.example.testfull.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import com.example.testfull.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
