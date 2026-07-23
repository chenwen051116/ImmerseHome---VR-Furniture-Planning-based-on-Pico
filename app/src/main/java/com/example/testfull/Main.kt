package com.example.testfull

import com.example.testfull.content.HomeStage
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope

fun mainApp(scope: SpatialAppScope) = with(scope) { DefaultStage { PicoTheme { HomeStage() } } }



