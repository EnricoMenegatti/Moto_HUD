package it.diy.hudnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseTest {
    @Test
    fun forkAgrees() {
        assertEquals(
            "Al bivio tieni la destra, in Via Romitino",
            Phrase.maneuver("fork", "slight right", "Via Romitino", 0, true)
        )
    }

    @Test
    fun turnHasPreposition() {
        assertEquals(
            "Svolta a destra, in Via Guglielmo Marconi",
            Phrase.maneuver("turn", "right", "Via Guglielmo Marconi", 0, true)
        )
    }

    @Test
    fun forkArmsLeaveOutTheRoadYouTake() {
        val at = LatLon(44.495476, 11.339)
        val arms = Geo.forkArms(at, listOf(14.0, 104.0, 191.0, 283.0), setOf(0, 1))
        assertEquals(2, arms.size)
        assertTrue(Geo.hav(at, arms[0][0]) in 10.0..18.0)
        assertTrue(Geo.hav(arms[0][0], arms[0][1]) in 28.0..36.0)
        assertTrue(Geo.forkArms(at, listOf(0.0, 180.0), setOf(0, 1)).isEmpty())
        assertEquals(1, Geo.forkArms(at, listOf(90.0, 100.0, 270.0), setOf(0)).size)
        val arm = listOf(listOf(at, LatLon(at.lat + 0.001, at.lon)))
        val near = RoadFork(100.0, arm)
        val far = RoadFork(200.0, arm)
        var shown = Geo.latchForks(listOf(near, far), emptyList(), 0.0, maxArms = 1)
        assertEquals(1, shown.size)
        assertEquals(100.0, shown[0].atM, 0.1)
        shown = Geo.latchForks(listOf(near, far), shown, 30.0, maxArms = 1)
        assertEquals(100.0, shown[0].atM, 0.1)
        shown = Geo.latchForks(listOf(near, far), shown, -15.0, maxArms = 1)
        assertEquals(1, shown.size)
        val visible = Geo.forksInView(
            listOf(RoadFork(50.0, arms), RoadFork(120.0, arms), RoadFork(800.0, arms)),
            0.0
        )
        assertEquals(4, visible.size)
        val route = listOf(LatLon(44.50, 11.340), LatLon(44.50, 11.350))
        val frame = Geo.RouteFrame(route, Geo.cumulate(route))
        val here = LatLon(44.50, 11.345)
        val same = LatLon(44.50004, 11.34505)
        val one = listOf(listOf(here, LatLon(44.501, 11.345)))
        val two = listOf(one[0], listOf(here, LatLon(44.499, 11.345)))
        val placed = Geo.placeForks(listOf(here to one, same to two), frame)
        assertEquals(1, placed.size)
        assertEquals(2, placed[0].arms.size)
    }

    @Test
    fun sideRoadLeavesTheTraceAndDropsTheOneYouAreOn() {
        val route = listOf(LatLon(44.50, 11.340), LatLon(44.50, 11.350))
        val frame = Geo.RouteFrame(route, Geo.cumulate(route))
        val center = LatLon(44.50, 11.345)
        val crossing = listOf(LatLon(44.4992, 11.345), LatLon(44.5008, 11.345))
        val onRoute = listOf(LatLon(44.50, 11.342), LatLon(44.50, 11.348))
        val foot = listOf(LatLon(44.4992, 11.346), LatLon(44.5008, 11.346))
        val kept = Net.sidePolylines(
            listOf("residential" to crossing, "primary" to onRoute, "footway" to foot),
            center,
            frame
        )
        assertEquals(1, kept.size)
        assertTrue(kept[0].any { it.lat < 44.50 })
        assertTrue(kept[0].any { it.lat > 44.50 })
    }

    @Test
    fun emptySidesClearTheHud() {
        val clear = sidePackets(emptyList())
        assertEquals(1, clear.size)
        assertEquals(0x08, clear[0][0].toInt() and 0xFF)
        assertEquals(1, clear[0][1].toInt() and 0xFF)
        assertEquals(0, clear[0][2].toInt() and 0xFF)
        val line = listOf(LatLon(44.5, 11.34), LatLon(44.501, 11.341))
        val pkt = sidePackets(listOf(line), 180)
        assertEquals(1, pkt.size)
        assertEquals(1, pkt[0][2].toInt() and 0xFF)
        assertEquals(2, pkt[0][3].toInt() and 0xFF)
    }

    @Test
    fun roundaboutExitReachesTheHud() {
        assertEquals(0x12, guideCode(8, 2))
        assertEquals(0x11, guideCode(8, 1))
        assertEquals(8, guideCode(8, 0))
        assertEquals(5, guideCode(5, 2))
    }

    @Test
    fun roundaboutExit() {
        assertEquals(
            "Alla rotonda prendi la seconda uscita, in Via Villanova",
            Phrase.maneuver("roundabout", "straight", "Via Villanova", 2, true)
        )
    }

    @Test
    fun waypointIsNotArrival() {
        assertTrue(Phrase.maneuver("arrive", "", "Via Roma", 0, false).startsWith("Tappa"))
        assertTrue(Phrase.maneuver("arrive", "", "Via Roma", 0, true).startsWith("Arrivo"))
    }

    @Test
    fun thenNextIsASentence() {
        assertEquals("Poi a fine strada a destra", Phrase.thenNext("A fine strada a destra"))
    }

    @Test
    fun limitFollowsTheRoadNotThePiazza() {
        val chosen = Speed.choose(
            listOf(
                Speed.Candidate("pedestrian", 10, 4.0),
                Speed.Candidate("residential", 50, 12.0),
                Speed.Candidate("service", 20, 8.0)
            )
        )
        assertEquals(50, chosen)
    }

    @Test
    fun limitHiddenWhenFar() {
        assertNull(Speed.choose(listOf(Speed.Candidate("primary", 70, 40.0))))
    }

    @Test
    fun polyline6KeepsThePoint() {
        val pts = Geo.decodePolyline("cxepuA{{}vUb`TjlV", 1_000_000.0)
        assertEquals(2, pts.size)
        assertEquals(45.37077, pts[0].lat, 1e-5)
        assertEquals(11.92699, pts[0].lon, 1e-5)
        assertEquals(45.36, pts[1].lat, 1e-5)
        assertEquals(11.915, pts[1].lon, 1e-5)
    }

    @Test
    fun zeroAndUnlimitedAreNotALimit() {
        assertNull(Speed.posted(0))
        assertNull(Speed.posted(255))
        assertEquals(50, Speed.posted(50))
    }

    @Test
    fun limitFollowsTheStretchAndStaysAtTheEnd() {
        val marks = listOf(Speed.Mark(0.0, 50), Speed.Mark(400.0, 50), Speed.Mark(800.0, 90))
        assertEquals(50, Speed.along(marks, 100.0))
        assertEquals(90, Speed.along(marks, 900.0))
        assertNull(Speed.along(marks, 2500.0))
        assertEquals(90, Speed.along(marks, 2500.0, holdGap = true))
        assertNull(Speed.along(emptyList(), 0.0))
    }

    @Test
    fun fasterCarAlternativeIsNotCalledSimilar() {
        val primary = route(800.0, "Via Roma", byCar = false)
        val faster = route(620.0, "Via Navegauro", byCar = true)
        val same = route(800.0, "Via Roma", byCar = false)
        val slower = route(980.0, "Via Piovese", byCar = false)
        val label = Net.routeLabel(faster, 1, listOf(primary, faster))
        assertEquals("-3 min · Via Navegauro · in auto", label)
        assertEquals("Simile · Via Roma", Net.routeLabel(same, 1, listOf(primary, same)))
        assertEquals("+3 min · Via Piovese", Net.routeLabel(slower, 1, listOf(primary, slower)))
    }

    private fun route(seconds: Double, road: String, byCar: Boolean): RoutePlan {
        val step = Step(0.0, seconds, road, 0, road)
        return RoutePlan(emptyList(), listOf(step), 1000.0, seconds, "", byCar)
    }

    @Test
    fun mapTapKeepsThePointWhenTheNameIsFar() {
        val tap = LatLon(44.50, 11.35)
        val far = LatLon(44.52, 11.35)
        val hit = Net.tappedPlace(tap, far, "Street Toast", "Via Irnerio", "Bologna")
        assertEquals(tap.lat, hit.lat, 0.0)
        assertEquals(tap.lon, hit.lon, 0.0)
        assertEquals("Punto sulla mappa", hit.name)
    }

    @Test
    fun mapTapUsesANearbyNameWithoutMoving() {
        val tap = LatLon(44.50, 11.35)
        val near = LatLon(44.50004, 11.35004)
        val hit = Net.tappedPlace(tap, near, "Via Schiavonia", "", "Bologna")
        assertEquals("Via Schiavonia", hit.name)
        assertEquals(tap.lat, hit.lat, 0.0)
        assertEquals(tap.lon, hit.lon, 0.0)
    }

    @Test
    fun arrowStaysOnTheBend() {
        val corner = listOf(LatLon(44.0, 11.0), LatLon(44.001, 11.0), LatLon(44.001, 11.001))
        val cum = Geo.cumulate(corner)
        val mid = Geo.pointAt(corner, cum, Geo.length(cum) * 0.5)
        val straight = LatLon(
            corner.first().lat + (corner.last().lat - corner.first().lat) * 0.5,
            corner.first().lon + (corner.last().lon - corner.first().lon) * 0.5
        )
        val onRoute = Geo.project(corner, cum, mid.lat, mid.lon)!!.second
        val cut = Geo.project(corner, cum, straight.lat, straight.lon)!!.second
        assertTrue(onRoute < 1.0)
        assertTrue(cut > 30.0)
    }

    @Test
    fun overpassDoesNotSnapToTheRoadBelow() {
        val bridge0 = LatLon(45.0, 11.0)
        val bridge1 = LatLon(45.0018, 11.0)
        val past = LatLon(45.0054, 11.0)
        val under0 = LatLon(45.0009, 11.00002)
        val under1 = LatLon(45.0009, 11.0004)
        val route = listOf(bridge0, bridge1, past, under0, under1)
        val cum = Geo.cumulate(route)
        val here = LatLon(45.0009, 11.000045)
        val jumped = Geo.project(route, cum, here.lat, here.lon)!!
        val kept = Geo.project(route, cum, here.lat, here.lon, nearS = 100.0, courseDeg = 0.0)!!
        assertTrue(jumped.first > 400.0)
        assertTrue(kept.first < 250.0)
    }

    @Test
    fun enteringARoundaboutIsTheRoundabout() {
        assertEquals(
            "Alla rotonda prendi la seconda uscita, in Via Villanova",
            Phrase.maneuver("exit roundabout", "right", "Via Villanova", 2, true)
        )
        val steps = mutableListOf(
            Step(0.0, 8.0, "Svolta a destra, in Via Roma", 5, "Via Roma"),
            Step(22.0, 12.0, "Alla rotonda prendi la seconda uscita, in Via Roma", 8, "Via Roma", 2),
            Step(90.0, 40.0, "Continua, in Via Roma", 0, "Via Roma")
        )
        Net.foldEntryIntoRoundabout(steps)
        assertEquals(2, steps.size)
        assertEquals(8, steps[0].turn)
        assertEquals(2, steps[0].exit)
        assertEquals(0.0, steps[0].atM, 0.1)
        assertEquals(90.0, steps[1].atM, 0.1)
        val far = mutableListOf(
            Step(0.0, 40.0, "Svolta a destra", 5, "Via Roma"),
            Step(200.0, 12.0, "Alla rotonda", 8, "Via Roma", 1)
        )
        Net.foldEntryIntoRoundabout(far)
        assertEquals(5, far[0].turn)
    }
}
