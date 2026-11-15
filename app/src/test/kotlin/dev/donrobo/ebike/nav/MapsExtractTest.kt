package dev.donrobo.ebike.nav

import dev.donrobo.ebike.proto.Icon
import org.junit.Assert.*
import org.junit.Test

class MapsExtractTest {
    private fun nav(vararg t: String, icon: Icon = Icon.LEFT) = MapsExtract.navState(MapsExtract.parseTexts(t.toList()), icon)

    @Test fun extras() {
        assertEquals(NavState(200, Icon.LEFT, 3400), nav("200 m", "Hauptstraße", "12 min · 3,4 km · 14:05"))
        assertEquals(NavState(1200, Icon.LEFT, 0), nav("1,2 km", "Richtung Wien"))
    }

    @Test fun summaryFirstDoesNotWinDistance() {
        // RemoteViews tree order may put the summary line before the maneuver
        assertEquals(NavState(80, Icon.RIGHT, 5100), nav("1 h 5 min", "18 min · 5,1 km", "80 m", "Ringstraße", icon = Icon.RIGHT))
    }

    @Test fun arrival() {
        assertEquals(NavState(0, Icon.ARRIVED, 0), nav("Sie haben Ihr Ziel erreicht"))
        assertEquals(NavState(30, Icon.ARRIVED, 30), nav("30 m", "Destination on the right", "1 min · 30 m"))
        // "Ziel" in the summary line is not an arrival
        assertEquals(Icon.LEFT, nav("200 m", "5 min · 1 km · Ziel 14:05")!!.icon)
    }

    @Test fun nothing() {
        assertNull(nav("Navigation läuft", "12 min · 3,4 km"))
        assertNull(nav())
    }

    /** Q2: a distance-less title is sent (as 0 m) only if it is an instruction, not the app's idle text. */
    @Test fun instructionWithoutDistance() {
        assertTrue(MapsExtract.isInstructionWithoutDistance("Auf Lindenweg nach Nordwesten"))
        assertTrue(MapsExtract.isInstructionWithoutDistance("Head northwest on Main St"))
        assertFalse(MapsExtract.isInstructionWithoutDistance("Mit Google Maps fahren"))
        assertFalse(MapsExtract.isInstructionWithoutDistance("Driving with Google Maps"))
        assertFalse(MapsExtract.isInstructionWithoutDistance("12 min · 3,4 km · 14:05"))
        assertFalse(MapsExtract.isInstructionWithoutDistance(""))
        assertFalse(MapsExtract.isInstructionWithoutDistance(null))
    }

    @Test fun maskAlpha() {
        val m = MapsExtract.mask(intArrayOf(0x00FFFFFF, 0xFF000000.toInt(), 0x80FFFFFF.toInt(), 0x7FFFFFFF))
        assertArrayEquals(booleanArrayOf(false, true, true, false), m)
    }

    @Test fun maskCorner() {
        val bg = 0xFF1A73E8.toInt()  // opaque Maps blue
        val m = MapsExtract.mask(intArrayOf(bg, 0xFFFFFFFF.toInt(), 0xFF1C75EA.toInt(), 0xFFFFFFFF.toInt()))
        assertArrayEquals(booleanArrayOf(false, true, false, true), m)
    }

    @Test fun android16ProgressStyleTitle() {
        // seen on a Pixel 10 (Android 17): title carries distance and instruction, subText the ETA
        val t = MapsExtract.parseTexts(listOf("150 m · Nach links abbiegen, um auf Lindenweg zu bleiben", "Ankunft um 22:00"))
        assertEquals(150, t.distM)
        assertFalse(t.arrival)
        assertEquals(Icon.LEFT, t.textIcon)
    }

    @Test fun iconsFromText() {
        assertEquals(Icon.RIGHT, MapsExtract.iconFromText("Rechts abbiegen auf Hauptstraße"))
        assertEquals(Icon.SLIGHT_LEFT, MapsExtract.iconFromText("Leicht links abbiegen"))
        assertEquals(Icon.SLIGHT_RIGHT, MapsExtract.iconFromText("Rechts halten"))
        assertEquals(Icon.SHARP_RIGHT, MapsExtract.iconFromText("Scharf rechts abbiegen"))
        assertEquals(Icon.UTURN, MapsExtract.iconFromText("Bitte wenden"))
        assertEquals(Icon.STRAIGHT, MapsExtract.iconFromText("Weiter auf Lindenweg"))
        assertEquals(Icon.LEFT, MapsExtract.iconFromText("Turn left onto Main St"))
        assertNull(MapsExtract.iconFromText("Im Kreisverkehr zweite Ausfahrt nehmen"))
        assertNull(MapsExtract.iconFromText("Lindenweg"))
    }
}
