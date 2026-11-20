package it.diy.hudnav

import androidx.lifecycle.MutableLiveData

/** Stato letto dalla schermata. Aggiornato dal servizio di navigazione. */
object HudState {
    val status = MutableLiveData("")
    val maneuverDist = MutableLiveData("")
    val maneuverMeters = MutableLiveData(-1)
    val maneuver = MutableLiveData("")
    val thenNext = MutableLiveData("")
    val trip = MutableLiveData("")
    val remainDist = MutableLiveData("")
    val remainTime = MutableLiveData("")
    val eta = MutableLiveData("")
    val road = MutableLiveData("")
    val turn = MutableLiveData(0)
    val bearing = MutableLiveData(0f)
    val limit = MutableLiveData("")
    val speed = MutableLiveData("")
    val alert = MutableLiveData("")
    val running = MutableLiveData(false)
    val simulating = MutableLiveData(false)
    val track = MutableLiveData<List<LatLon>>(emptyList())
    val rider = MutableLiveData<LatLon?>(null)
    val destination = MutableLiveData<LatLon?>(null)
    val cameras = MutableLiveData<List<RoadAlert>>(emptyList())
    val pois = MutableLiveData<List<PlaceHit>>(emptyList())
}
