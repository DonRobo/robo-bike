package dev.donrobo.ebike.settings

import android.content.Context

/** The bikes of this installation (stateless view over [Settings]). */
val Context.bikes: Bikes get() = Bikes(settings, applicationContext.filesDir)
