package com.example.watchmap

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.cardview.widget.CardView
import com.mapbox.android.core.permissions.PermissionsListener
import com.mapbox.android.core.permissions.PermissionsManager
import com.mapbox.maps.MapView
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.viewport
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.LocationProviderRequest
import com.mapbox.common.location.LocationServiceFactory
import com.mapbox.maps.plugin.viewport.data.FollowPuckViewportStateOptions
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.route.arrow.model.InvalidPointError
import com.mapbox.navigation.ui.maps.route.arrow.model.UpdateManeuverArrowValue
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.maps.CameraOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.search.autofill.AddressAutofill
import com.mapbox.search.autofill.AddressAutofillResult
import com.mapbox.search.autofill.AddressAutofillSuggestion
import com.mapbox.search.autofill.Query
import com.mapbox.search.ui.adapter.autofill.AddressAutofillUiAdapter
import com.mapbox.search.ui.view.CommonSearchViewConfiguration
import com.mapbox.search.ui.view.DistanceUnitType
import com.mapbox.search.ui.view.SearchResultsView
import androidx.core.graphics.toColorInt

class MainActivity : ComponentActivity(), PermissionsListener {
    private lateinit var mapView: MapView
    private lateinit var permissionsManager: PermissionsManager
    private lateinit var locationButton: FloatingActionButton
    private lateinit var markButton: FloatingActionButton
    private lateinit var profileButton: FloatingActionButton
    private lateinit var stopNavigationButton: FloatingActionButton // New button
    private lateinit var searchCardView: CardView
    private lateinit var maneuverView: MapboxManeuverView
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource
    private lateinit var navigationCamera: NavigationCamera

    private lateinit var addressAutofill: AddressAutofill
    private lateinit var searchResultsView: SearchResultsView
    private lateinit var addressAutofillUiAdapter: AddressAutofillUiAdapter
    private lateinit var searchEditText: AutoCompleteTextView
    private var ignoreNextQueryTextUpdate: Boolean = false

    private var destinationPoint: Point? = null
    private var destinationMarker: CircleAnnotationManager? = null
    private var originPoint: Point? = null
    private lateinit var routeLineApi: MapboxRouteLineApi
    private lateinit var routeLineView: MapboxRouteLineView
    private lateinit var routeArrowApi: MapboxRouteArrowApi
    private lateinit var routeArrowView: MapboxRouteArrowView
    private var isNavigating = false // Track if navigation is active

    private var isMarkingEnabled = false // Flag to track if marking is enabled

    // Add this property using requireMapboxNavigation
    private val mapboxNavigation: MapboxNavigation by requireMapboxNavigation(
        onResumedObserver = object : MapboxNavigationObserver {
            @SuppressLint("MissingPermission")
            override fun onAttached(mapboxNavigation: MapboxNavigation) {
                // Register observers when navigation is attached
                mapboxNavigation.startTripSession(withForegroundService = false)

                // Register RouteProgressObserver to detect route progress changes
                mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            }

            override fun onDetached(mapboxNavigation: MapboxNavigation) {
                // Clean up when detached
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            }
        },
        onInitialize = this::initNavigation
    )

    // Helper function to convert maneuver modifier to human-readable direction
    private var lastManeuverInstruction: String? = null
    private var lastDistanceRemaining: Float? = null
    private val DISTANCE_UPDATE_THRESHOLD = 50f

    private val routeProgressObserver = object : RouteProgressObserver {
        override fun onRouteProgressChanged(routeProgress: RouteProgress) {
            val maneuvers = maneuverApi.getManeuvers(routeProgress)
            maneuverView.renderManeuvers(maneuvers)

            // Render route arrow and line
            mapView.getMapboxMap().getStyle()?.let { style ->
                val arrowResult = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
                routeArrowView.renderRouteArrow(style, arrowResult)

                routeLineApi.updateWithRouteProgress(routeProgress) { result ->
                    routeLineView.renderRouteLineUpdate(style, result)
                }
            }

            // Current step details
            val currentStepProgress = routeProgress.currentLegProgress?.currentStepProgress
            val distanceRemaining = currentStepProgress?.distanceRemaining

            val currentLegProgress = routeProgress.currentLegProgress
            val nextStep = currentLegProgress?.upcomingStep

            // Next step details
            val nextStepInstruction = nextStep?.maneuver()?.instruction()
            val nextStepDistance = nextStep?.distance()?.toFloat() ?: 0f
            val nextStepModifier = nextStep?.maneuver()?.modifier()?.toString()

            // Total route details
            val totalDistanceRemaining = routeProgress.distanceRemaining
            val totalDurationRemaining = routeProgress.durationRemaining

            // Determine if an update is needed
            val shouldUpdate =
                (nextStepInstruction != null) && (
                        (nextStepInstruction != lastManeuverInstruction) ||
                                (distanceRemaining != null && lastDistanceRemaining != null &&
                                        Math.abs(distanceRemaining - lastDistanceRemaining!!) > DISTANCE_UPDATE_THRESHOLD)
                        )

            if (shouldUpdate) {
                lastManeuverInstruction = nextStepInstruction
                lastDistanceRemaining = distanceRemaining

                // Prepare detailed navigation message
                val navigationMessage = buildString {
                    // Distance to current maneuver
                    append("${formatDistanceCompact(distanceRemaining ?: 0f)} | ")

                    // Next maneuver instruction
                    nextStepInstruction?.let {
                        append("$it | ")
                    }

                    // Next maneuver direction
                    val nextDirectionText = getDirectionText(nextStepModifier)
                    if (nextDirectionText.isNotEmpty()) {
                        append("$nextDirectionText | ")
                    }

                    // Remaining route details
                    append("${formatDistanceCompact(totalDistanceRemaining)} | ")
                    append("${formatDurationCompact(totalDurationRemaining)}")
                }

                // Display the notification
                runOnUiThread {
                    createNavigationUpdateNotification(navigationMessage)
                }
            }
        }
    }

    // Direction text mapping function using your existing implementation
    private fun getDirectionText(modifier: String?): String {
        return when (modifier) {
            // Basic directions with "Turn" prefix
            "uturn" -> "Turn U-Turn"
            "sharp right" -> "Turn Sharp Right"
            "right" -> "Turn Right"
            "slight right" -> "Turn Slight Right"
            "straight" -> "Continue Straight"
            "slight left" -> "Turn Slight Left"
            "left" -> "Turn Left"
            "sharp left" -> "Turn Sharp Left"

            // Additional directions
            "continue" -> "Continue"
            "merge" -> "Merge"
            "fork" -> "Fork"
            "ramp" -> "Take Ramp"
            "exit" -> "Take Exit"
            "roundabout" -> "Enter Roundabout"
            "rotary" -> "Enter Rotary"
            "roundabout turn" -> "Roundabout Turn"
            "off ramp" -> "Take Off Ramp"
            "on ramp" -> "Take On Ramp"
            "depart" -> "Depart"
            "arrive" -> "Arrive"
            "end of road" -> "End of Road"

            // Alternative forms
            "turn-uturn" -> "Turn U-Turn"
            "turn-sharp-right" -> "Turn Sharp Right"
            "turn-right" -> "Turn Right"
            "turn-slight-right" -> "Turn Slight Right"
            "turn-straight" -> "Continue Straight"
            "turn-slight-left" -> "Turn Slight Left"
            "turn-left" -> "Turn Left"
            "turn-sharp-left" -> "Turn Sharp Left"

            // Uppercase alternatives
            "UTURN" -> "Turn U-Turn"
            "SHARP_RIGHT" -> "Turn Sharp Right"
            "RIGHT" -> "Turn Right"
            "SLIGHT_RIGHT" -> "Turn Slight Right"
            "STRAIGHT" -> "Continue Straight"
            "SLIGHT_LEFT" -> "Turn Slight Left"
            "LEFT" -> "Turn Left"
            "SHARP_LEFT" -> "Turn Sharp Left"

            // Possible variants
            "bear right" -> "Bear Right"
            "bear left" -> "Bear Left"
            "continue straight" -> "Continue Straight"
            "keep right" -> "Keep Right"
            "keep left" -> "Keep Left"
            "make u-turn" -> "Turn U-Turn"
            "destination" -> "Destination"
            "destination right" -> "Destination on Right"
            "destination left" -> "Destination on Left"
            "waypoint" -> "Waypoint"
            "waypoint right" -> "Waypoint on Right"
            "waypoint left" -> "Waypoint on Left"

            else -> ""
        }
    }

    // Helper function to format distance in a compact way (e.g., "50m" or "12km")
    private fun formatDistanceCompact(meters: Float): String {
        return when {
            meters < 1000 -> "${meters.toInt()}m"
            else -> "${(meters / 1000).toInt()}km"
        }
    }

    // Helper function to format duration in a compact way (e.g., "5min" or "1.1h")
    private fun formatDurationCompact(seconds: Double): String {
        return when {
            seconds < 3600 -> "${(seconds / 60).toInt()}min"
            else -> {
                val hours = seconds / 3600
                "%.1fh".format(hours)
            }
        }
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Navigation Updates"
            val descriptionText = "Displays turn-by-turn navigation instructions"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NAVIGATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNavigationUpdateNotification(message: String) {
        try {
            // Ensure notification channel is created
            createNotificationChannel()

            // Check for notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    // Permission not granted - don't crash, just return
                    Log.d("MainActivity", "Notification permission not granted")
                    return
                }
            }

            val builder = NotificationCompat.Builder(this, NAVIGATION_CHANNEL_ID)
                .setContentTitle("Wrist Route")
                .setContentText(message)
                .setSmallIcon(R.drawable.baseline_assistant_navigation_24)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)

            with(NotificationManagerCompat.from(this)) {
                try {
                    notify(NAVIGATION_NOTIFICATION_ID, builder.build())
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Failed to show notification: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Notification creation failed: ${e.message}")
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted - you can now show notifications
                    Log.d("MainActivity", "Notification permission granted")
                } else {
                    // Permission denied - handle accordingly
                    Log.d("MainActivity", "Notification permission denied")
                }
            }
        }
    }

    companion object {
        private const val NAVIGATION_CHANNEL_ID = "navigation_channel"
        private const val NAVIGATION_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    }


    // Define distance formatter options
    // Replace your existing distance formatter definition with this:
    private val distanceFormatter: DistanceFormatterOptions by lazy {
        DistanceFormatterOptions.Builder(this)
            .unitType(UnitType.METRIC) // This changes miles to kilometers
            .build()
    }
    // Create an instance of the Maneuver API
    private val maneuverApi: MapboxManeuverApi by lazy {
        MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatter))
    }

    // Add this initialization function
    private fun initNavigation() {
        MapboxNavigationApp.setup(
            NavigationOptions.Builder(this)
                .build()
        )

        // Initialize route line components
        val routeLineApiOptions = MapboxRouteLineApiOptions.Builder().build()
        routeLineApi = MapboxRouteLineApi(routeLineApiOptions)

        val routeLineViewOptions = MapboxRouteLineViewOptions.Builder(this).build()
        routeLineView = MapboxRouteLineView(routeLineViewOptions)

        // Initialize route arrow components
        val routeArrowOptions = RouteArrowOptions.Builder(this).build()
        routeArrowApi = MapboxRouteArrowApi()
        routeArrowView = MapboxRouteArrowView(routeArrowOptions)

        // Initialize viewport data source and navigation camera
        viewportDataSource = MapboxNavigationViewportDataSource(mapView.getMapboxMap())
        navigationCamera = NavigationCamera(
            mapView.getMapboxMap(),
            mapView.camera,
            viewportDataSource
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize address autofill
        addressAutofill = AddressAutofill.create()

        // Initialize search views
        searchEditText = findViewById(R.id.searchEditText)
        searchResultsView = findViewById(R.id.search_results_view)

        // Initialize views
        mapView = findViewById(R.id.mapView)
        locationButton = findViewById(R.id.locationButton)
        markButton = findViewById(R.id.markButton)
        profileButton = findViewById(R.id.profileButton)
        stopNavigationButton = findViewById(R.id.stopNavigationButton) // Initialize the new button
        searchCardView = findViewById(R.id.searchCardView)
        maneuverView = findViewById(R.id.maneuverView)

        createNotificationChannel()
        handleLocationButtonClick()
        handleLocationButtonClick()

        searchResultsView.initialize(
            SearchResultsView.Configuration(
                commonConfiguration = CommonSearchViewConfiguration(DistanceUnitType.IMPERIAL)
            )
        )

        addressAutofillUiAdapter = AddressAutofillUiAdapter(
            view = searchResultsView,
            addressAutofill = addressAutofill
        )

        // Set up search text listener
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
                if (ignoreNextQueryTextUpdate) {
                    ignoreNextQueryTextUpdate = false
                    return
                }

                val query = Query.create(text.toString())
                if (query != null) {
                    lifecycleScope.launchWhenStarted {
                        addressAutofillUiAdapter.search(query)
                    }
                }
                searchResultsView.isVisible = query != null
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable) {}
        })

        // Set up address autofill selection listener
        addressAutofillUiAdapter.addSearchListener(object : AddressAutofillUiAdapter.SearchListener {
            override fun onSuggestionSelected(suggestion: AddressAutofillSuggestion) {
                selectAutofillSuggestion(suggestion)
            }

            override fun onSuggestionsShown(suggestions: List<AddressAutofillSuggestion>) {}
            override fun onError(e: Exception) {
                Toast.makeText(this@MainActivity, "Address search error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
        // Initially hide the maneuver view and stop navigation button since there's no route yet
        maneuverView.visibility = View.GONE
        stopNavigationButton.visibility = View.GONE

        // Set up button click listeners
        locationButton.setOnClickListener {
            handleLocationButtonClick()
        }

        markButton.setOnClickListener {
            isMarkingEnabled = true // Enable marking mode
            Toast.makeText(this, "Tap on the map to mark a location", Toast.LENGTH_SHORT).show()
            markLocationPoint()

            // Only request routes if both origin and destination are set
            if (originPoint != null && destinationPoint != null) {
                requestRoute()
            } else {
                Toast.makeText(this, "Please set both origin and destination points", Toast.LENGTH_SHORT).show()
            }
        }

        // Add this in your onCreate() method, after initializing the views:
        searchCardView.setOnClickListener {
            // Show the search interface
            searchResultsView.visibility = View.VISIBLE
            searchEditText.requestFocus()
            searchEditText.showKeyboard()

            // Hide the profile button while searching
            profileButton.visibility = View.GONE

            Toast.makeText(this, "Enter your destination address", Toast.LENGTH_SHORT).show()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
                if (ignoreNextQueryTextUpdate) {
                    ignoreNextQueryTextUpdate = false
                    return
                }

                val query = Query.create(text.toString())
                if (query != null) {
                    lifecycleScope.launchWhenStarted {
                        addressAutofillUiAdapter.search(query)
                    }
                    // Show results when typing
                    searchResultsView.visibility = View.VISIBLE
                } else {
                    // Hide results when empty
                    searchResultsView.visibility = View.GONE
                }
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable) {}
        })

        // Set up stop navigation button listener
        stopNavigationButton.setOnClickListener {
            stopNavigation()
        }

        profileButton.setOnClickListener {
            // Your profile button logic
        }
    }

    fun View.showKeyboard() {
        if (requestFocus()) {
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun View.hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    // Add a method to stop navigation
    private fun stopNavigation() {
        // Clear active routes
        mapboxNavigation.setNavigationRoutes(emptyList())

        // Clear the route line from map
        routeLineApi.clearRouteLine { value ->
            mapView.getMapboxMap().getStyle()?.let { style ->
                routeLineView.renderClearRouteLineValue(style, value)
            }
        }

        // Clear the route arrow from map
        val style = mapView.getMapboxMap().getStyle()
        if (style != null) {
            routeArrowView.render(style, routeArrowApi.clearArrows())
        }

        // Remove the destination marker
        destinationMarker?.deleteAll()
        destinationMarker = null

        // Reset the camera to follow the puck
        navigationCamera.requestNavigationCameraToFollowing()

        // Hide navigation UI elements
        maneuverView.visibility = View.GONE
        stopNavigationButton.visibility = View.GONE

        // Clear the search text
        searchEditText.text.clear()

        // Show search and profile elements
        searchCardView.visibility = View.VISIBLE
        profileButton.visibility = View.VISIBLE

        // Reset navigation state
        isNavigating = false
        destinationPoint = null

        Toast.makeText(this, "Navigation stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestRoute() {
        if (originPoint == null || destinationPoint == null) {
            Toast.makeText(this, "Origin and destination points are required", Toast.LENGTH_SHORT).show()
            return
        }

        val routeLineColorResources = RouteLineColorResources.Builder()
            .routeDefaultColor("#000000".toColorInt())
            .alternativeRouteDefaultColor("#424242".toColorInt())   // Dark gray for alternatives
            .routeCasingColor("#212121".toColorInt())
            .routeClosureColor("#C52222".toColorInt())// Very dark gray for outline
            .build()


        val routeOptions = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .profile(DirectionsCriteria.PROFILE_WALKING)
            .alternatives(true)
            .annotationsList(
                listOf(
                    DirectionsCriteria.ANNOTATION_CONGESTION_NUMERIC,
                    DirectionsCriteria.ANNOTATION_DISTANCE,
                    DirectionsCriteria.ANNOTATION_DURATION
                )
            )
            .coordinatesList(listOf(originPoint, destinationPoint))
            .bearingsList(
                listOf(
                    Bearing.builder()
                        .degrees(45.0)
                        .build(),
                    null
                )
            )
            .build()

        val routeLineViewOptions = MapboxRouteLineViewOptions.Builder(this)
            .routeLineColorResources(routeLineColorResources)
            .routeLineBelowLayerId("road-label")
            .build()
        routeLineView = MapboxRouteLineView(routeLineViewOptions)

        mapboxNavigation.requestRoutes(routeOptions,
            object : NavigationRouterCallback {
                override fun onCanceled(
                    routeOptions: RouteOptions,
                    routerOrigin: String
                ) {
                    Log.d("MainActivity", "Route request canceled")
                    Toast.makeText(
                        this@MainActivity,
                        "Route request canceled",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    Log.e("MainActivity", "Route request failed: ${reasons.firstOrNull()?.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Route request failed: ${reasons.firstOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: String
                ) {
                    mapboxNavigation.setNavigationRoutes(routes)
                    routeLineApi.setNavigationRoutes(routes) { value ->
                        mapView.getMapboxMap().getStyle()?.let { style ->
                            routeLineView.renderRouteDrawData(style, value)
                        }
                    }

                    // Update viewport to show the entire route
                    viewportDataSource.onRouteChanged(routes.first())
                    viewportDataSource.evaluate()
                    navigationCamera.requestNavigationCameraToOverview()

                    // Set navigation state to active
                    isNavigating = true

                    // Show the maneuver view and stop navigation button
                    // Hide search and profile elements
                    runOnUiThread {
                        // Show navigation UI elements
                        maneuverView.visibility = View.VISIBLE
                        stopNavigationButton.visibility = View.VISIBLE

                        // Hide search and profile elements
                        searchCardView.visibility = View.GONE
                        profileButton.visibility = View.GONE
                    }
                }
            }
        )
    }

    private fun selectAutofillSuggestion(suggestion: AddressAutofillSuggestion) {
        lifecycleScope.launchWhenStarted {
            val response = addressAutofill.select(suggestion)
            response.onValue { result ->
                handleAutofillResult(result)
            }.onError {
                Toast.makeText(this@MainActivity, "Failed to get address details", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAutofillResult(result: AddressAutofillResult) {
        // Update the map with the selected location
        destinationPoint = result.suggestion.coordinate
        addMarkerAtPoint(destinationPoint!!)

        // Update the search field with the formatted address
        ignoreNextQueryTextUpdate = true
        searchEditText.setText(result.suggestion.formattedAddress)
        searchEditText.clearFocus()

        // Hide the search results
        searchResultsView.isVisible = false
        searchResultsView.hideKeyboard()

        // If we have an origin point, request a route
        if (originPoint != null) {
            requestRoute()
        } else {
            Toast.makeText(this, "Please set your current location first", Toast.LENGTH_SHORT).show()
        }

        // Center the map on the selected location
        mapView.getMapboxMap().setCamera(
            CameraOptions.Builder()
                .center(result.suggestion.coordinate)
                .zoom(14.0)
                .build()
        )
    }

    private fun markLocationPoint(){
        // Set up touch listener for the map
        mapView.getMapboxMap().addOnMapClickListener { point ->
            if (isMarkingEnabled) {
                addMarkerAtPoint(point)
                destinationPoint = point
                isMarkingEnabled = false // Disable marking mode after marking a point

                // Request route after setting the destination
                if (originPoint != null) {
                    requestRoute()
                } else {
                    Toast.makeText(this, "Please set origin point first", Toast.LENGTH_SHORT).show()
                }
            }
            true // Return true to indicate the event is consumed
        }

        // Check for location permissions
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            enableLocationComponent()
        } else {
            permissionsManager = PermissionsManager(this)
            permissionsManager.requestLocationPermissions(this)
        }
    }

    private fun enableLocationComponent() {
        with(mapView) {
            location.locationPuck = createDefault2DPuck(withBearing = true)
            location.enabled = true
            location.puckBearing = PuckBearing.COURSE
            location.puckBearingEnabled = true
            location.pulsingEnabled = true

            try {
                location.pulsingColor = R.color.black
            } catch (e: Exception) {
                location.pulsingColor = android.R.color.black
            }

            // Center map on user's location initially
            viewport.transitionTo(
                targetState = viewport.makeFollowPuckViewportState(),
                transition = viewport.makeImmediateViewportTransition()
            )
        }
    }

    private fun addMarkerAtPoint(point: Point) {
        try {
            // Create a simple circle marker without an image
            val circleManager = mapView.annotations.createCircleAnnotationManager()
            val circleOptions = CircleAnnotationOptions()
                .withPoint(point)
                .withCircleRadius(8.0)
                .withCircleColor("#C52222") // Red circle
                .withCircleStrokeWidth(2.0)
                .withCircleStrokeColor("#292D32") // White stroke

            circleManager.create(circleOptions)

            // Save the marker manager reference
            destinationMarker = circleManager

            // Set this as destination
            destinationPoint = point
        } catch (e: Exception) {
            Log.e("MainActivity", "Error adding marker: ${e.message}", e)
            Toast.makeText(this, "Error marking location: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLocationButtonClick() {
        // Check if location component is already enabled
        if (!mapView.location.enabled) {
            // First click: Enable location component
            enableLocationComponent()
            return
        }

        // Get the current device location
        val locationProvider = LocationServiceFactory.getOrCreate()
            .getDeviceLocationProvider(LocationProviderRequest.Builder().build())

        if (locationProvider.isValue) {
            locationProvider.value?.getLastLocation { location ->
                if (location != null) {
                    // Store the current location in originPoint
                    originPoint = Point.fromLngLat(location.longitude, location.latitude)
                    Log.d("MainActivity", "Origin point set to: ${originPoint?.latitude()}, ${originPoint?.longitude()}")

                    // Center map on current location with zoom
                    mapView.viewport.transitionTo(
                        targetState = mapView.viewport.makeFollowPuckViewportState(
                            options = FollowPuckViewportStateOptions.Builder()
                                .zoom(15.0)
                                .pitch(0.0)
                                .build()
                        ),
                        transition = mapView.viewport.makeImmediateViewportTransition()
                    )

                    // Show confirmation toast
                    Toast.makeText(this, "Origin point set!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Unable to retrieve current location", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "Location provider not available", Toast.LENGTH_SHORT).show()
        }
    }

    // PermissionsListener implementation
    override fun onExplanationNeeded(permissionsToExplain: List<String>) {
        // You could show a dialog explaining why you need location permissions
    }

    override fun onPermissionResult(granted: Boolean) {
        if (granted) {
            enableLocationComponent()
        } else {
            // Handle permission denial
            // You might want to show a toast or dialog explaining limitations
        }
    }
}

private fun MapboxRouteArrowView.renderRouteArrow(
    style: Style,
    expected: Expected<InvalidPointError, UpdateManeuverArrowValue>
) {
}