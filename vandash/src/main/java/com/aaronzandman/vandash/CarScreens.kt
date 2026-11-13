package com.aaronzandman.vandash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Android Auto entry point. Android Auto starts this when you open Van Dash on the car screen. */
class DashCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        // Installed from GitHub (not the Play Store) for use on your own phone, so any Android Auto host is accepted.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        VLog.add(carContext, "Opened on the Android Auto screen")
        return NearbyScreen(carContext)
    }
}

/** Shared helpers for both car screens. */
internal object CarUi {
    fun limit(c: CarContext, type: Int, fallback: Int): Int = try {
        if (c.carAppApiLevel >= 2) c.getCarService(ConstraintManager::class.java).getContentLimit(type) else fallback
    } catch (_: Exception) { fallback }

    fun hasLocation(c: CarContext) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun distanceText(mi: Double, rest: String): CharSequence {
        val s = SpannableString("  · $rest")
        s.setSpan(DistanceSpan.create(Distance.create(mi, Distance.UNIT_MILES)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return s
    }

    /** Hands the store to the navigation app chosen in Android Auto (Google Maps or Waze). */
    fun navigate(c: CarContext, s: Stores.S) {
        val label = Uri.encode("Publix ${s.num}")
        val uri = Uri.parse("geo:0,0?q=${s.lat},${s.lng}($label)")
        try {
            c.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
            VLog.add(c, "Started directions to store ${s.num}")
            Stores.setLastStore(c, s.num)
        } catch (e: Exception) {
            VLog.add(c, "Couldn't start directions to store ${s.num}: ${e.message}")
            CarToast.makeText(c, "Couldn't open navigation", CarToast.LENGTH_LONG).show()
        }
    }
}

/** Map with the closest stores. Tap a store to start directions. */
class NearbyScreen(c: CarContext) : Screen(c) {
    private var here: Pair<Double, Double>? = Stores.phoneLocation(c)
    private var shownFrom: Pair<Double, Double>? = null

    private val listener = LocationListener { l: Location ->
        here = l.latitude to l.longitude
        Stores.saveLocation(carContext, l.latitude, l.longitude)
        val from = shownFrom
        // Re-sort when we've moved about a quarter mile.
        if (from == null || Stores.miles(from.first, from.second, l.latitude, l.longitude) > 0.25) invalidate()
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            @SuppressLint("MissingPermission")
            override fun onStart(owner: LifecycleOwner) {
                if (!CarUi.hasLocation(carContext)) return
                try {
                    val lm = carContext.getSystemService(LocationManager::class.java)
                    for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                        if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 10_000L, 100f, listener, Looper.getMainLooper())
                    }
                } catch (e: Exception) { VLog.add(carContext, "Location updates failed: ${e.message}") }
            }
            override fun onStop(owner: LifecycleOwner) {
                try { carContext.getSystemService(LocationManager::class.java).removeUpdates(listener) } catch (_: Exception) {}
            }
        })
    }

    override fun onGetTemplate(): Template {
        val pos = here ?: Stores.phoneLocation(carContext)
        if (pos == null) {
            return MessageTemplate.Builder("Van Dash doesn't know where you are yet. Open Van Dash on your phone and allow location.")
                .setTitle("Van Dash")
                .setHeaderAction(Action.APP_ICON)
                .addAction(Action.Builder().setTitle("Try again").setOnClickListener { invalidate() }.build())
                .build()
        }
        shownFrom = pos
        val max = CarUi.limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST, 6)
        val near = Stores.nearest(carContext, pos.first, pos.second, max)
        val items = ItemList.Builder().setNoItemsMessage("No stores in the list")
        near.forEachIndexed { i, (s, mi) ->
            items.addItem(Row.Builder()
                .setTitle("Store ${s.num} · ${s.city}")
                .addText(CarUi.distanceText(mi, s.address))
                .setMetadata(Metadata.Builder().setPlace(
                    Place.Builder(CarLocation.create(s.lat, s.lng))
                        .setMarker(PlaceMarker.Builder().setLabel("${i + 1}").setColor(CarColor.GREEN).build())
                        .build()).build())
                .setOnClickListener { CarUi.navigate(carContext, s) }
                .build())
        }
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("More").setOnClickListener {
                screenManager.push(StoreListScreen(carContext, pos))
            }.build())
            .build()
        return PlaceListMapTemplate.Builder()
            .setTitle("Nearby Publix")
            .setHeaderAction(Action.APP_ICON)
            .setItemList(items.build())
            .setActionStrip(strip)
            .setCurrentLocationEnabled(CarUi.hasLocation(carContext))
            .build()
    }
}

/** Plain list of more stores, closest first (Android Auto decides how many fit). */
class StoreListScreen(c: CarContext, private val pos: Pair<Double, Double>) : Screen(c) {
    override fun onGetTemplate(): Template {
        val max = CarUi.limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6)
        val items = ItemList.Builder().setNoItemsMessage("No stores in the list")
        Stores.nearest(carContext, pos.first, pos.second, max).forEach { (s, mi) ->
            items.addItem(Row.Builder()
                .setTitle("Store ${s.num} · ${s.city}")
                .addText(CarUi.distanceText(mi, s.address))
                .setOnClickListener { CarUi.navigate(carContext, s) }
                .build())
        }
        return ListTemplate.Builder()
            .setTitle("More stores")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
