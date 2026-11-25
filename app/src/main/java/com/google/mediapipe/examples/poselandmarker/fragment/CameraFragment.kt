/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.util.Size
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.mediapipe.examples.poselandmarker.MainActivity
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.UserProfileRepository
import com.google.mediapipe.examples.poselandmarker.UserProfileDialogFragment
import com.google.mediapipe.examples.poselandmarker.RepetitionTelemetryManager
import com.google.mediapipe.examples.poselandmarker.ExerciseType
import com.google.mediapipe.examples.poselandmarker.ExerciseStrategy
import com.google.mediapipe.examples.poselandmarker.SquatStrategy
import com.google.mediapipe.examples.poselandmarker.DeadliftStrategy
import com.google.mediapipe.examples.poselandmarker.BicepCurlStrategy
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.slm.SlmFeedbackDialogFragment
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Pose Landmarker"
        private const val METRICS_UI_UPDATE_INTERVAL_MS = 250L
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK
    @Volatile private var lastMetricsUiUpdateTime = 0L
    @Volatile private var currentExerciseType: ExerciseType = ExerciseType.SQUAT
    private lateinit var repetitionTelemetryManager: RepetitionTelemetryManager
    private lateinit var userProfileRepository: UserProfileRepository
    private var countdownTimer: CountDownTimer? = null
    private var seriesInProgress = false

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the PoseLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelCountdown()
        if(this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)

            // Close the PoseLandmarkerHelper and release resources
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        cancelCountdown()
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        userProfileRepository = UserProfileRepository(requireContext())
        if (!userProfileRepository.hasProfile() &&
            parentFragmentManager.findFragmentByTag("UserProfileDialog") == null
        ) {
            UserProfileDialogFragment.newInstance(isFirstLaunch = true)
                .show(parentFragmentManager, "UserProfileDialog")
        }

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the PoseLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = viewModel.currentMinPoseDetectionConfidence,
                minPoseTrackingConfidence = viewModel.currentMinPoseTrackingConfidence,
                minPosePresenceConfidence = viewModel.currentMinPosePresenceConfidence,
                currentDelegate = viewModel.currentDelegate,
                poseLandmarkerHelperListener = this
            )
            repetitionTelemetryManager = RepetitionTelemetryManager(poseLandmarkerHelper.exerciseFSM)
            repetitionTelemetryManager.setExercise(currentExerciseType, strategyFor(currentExerciseType))
        }

        // Attach listeners to UI control widgets
        parentFragmentManager.setFragmentResultListener(
            SlmFeedbackDialogFragment.RESULT_KEY, viewLifecycleOwner
        ) { _, _ -> if (this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.isPausedForSlm = false }
        initBottomSheetBehavior()
        initBottomSheetControls()
        fragmentCameraBinding.btnStartTrain.setOnClickListener {
            if (seriesInProgress || countdownTimer != null) endSeries()
            else startCountdown()
        }
    }

    /** Opens the existing profile dialog without changing the active navigation destination. */
    fun openProfileSettings() {
        if (!isAdded || seriesInProgress || countdownTimer != null) return
        if (parentFragmentManager.findFragmentByTag("UserProfileDialog") == null) {
            UserProfileDialogFragment.newInstance(isFirstLaunch = false)
                .show(parentFragmentManager, "UserProfileDialog")
        }
    }

    private fun initBottomSheetBehavior() {
        val behavior = BottomSheetBehavior.from(fragmentCameraBinding.bottomSheetLayout.root)
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED -> setSeriesButtonVisibility(true)
                    BottomSheetBehavior.STATE_EXPANDED -> setSeriesButtonVisibility(false)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (slideOffset > 0.15f) setSeriesButtonVisibility(false)
            }
        })
    }

    private fun setSeriesButtonVisibility(visible: Boolean) {
        val button = fragmentCameraBinding.btnStartTrain
        if (visible) {
            button.visibility = View.VISIBLE
            button.animate().alpha(1f).setDuration(150L).start()
        } else {
            button.animate().alpha(0f).setDuration(150L).withEndAction {
                button.visibility = View.GONE
            }.start()
        }
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence
            )

        fragmentCameraBinding.bottomSheetLayout.spinnerExercise.setSelection(0, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerExercise.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentExerciseType = when (position) {
                        1 -> ExerciseType.DEADLIFT
                        2 -> ExerciseType.BICEP_CURL
                        else -> ExerciseType.SQUAT
                    }
                    if (this@CameraFragment::repetitionTelemetryManager.isInitialized) {
                        repetitionTelemetryManager.setExercise(currentExerciseType, strategyFor(currentExerciseType))
                    }
                    if (!seriesInProgress && countdownTimer == null) fragmentCameraBinding.btnStartTrain.isEnabled = true
                    fragmentCameraBinding.tvRepetitionCount.text = "Repeticiones: 0"
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        // When clicked, lower pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseDetectionConfidence >= 0.2) {
                poseLandmarkerHelper.minPoseDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseDetectionConfidence <= 0.8) {
                poseLandmarkerHelper.minPoseDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseTrackingConfidence >= 0.2) {
                poseLandmarkerHelper.minPoseTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseTrackingConfidence <= 0.8) {
                poseLandmarkerHelper.minPoseTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPosePresenceConfidence >= 0.2) {
                poseLandmarkerHelper.minPosePresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPosePresenceConfidence <= 0.8) {
                poseLandmarkerHelper.minPosePresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        poseLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "PoseLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    private fun setSeriesButton(active: Boolean) {
        fragmentCameraBinding.btnStartTrain.text = if (active) "DETENER SERIE" else "INICIAR SERIE"
        fragmentCameraBinding.btnStartTrain.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (active) "#D32F2F" else "#007F8B")
        )
    }

    private fun startCountdown() {
        if (!this::repetitionTelemetryManager.isInitialized) return
        BottomSheetBehavior.from(fragmentCameraBinding.bottomSheetLayout.root).setState(
            BottomSheetBehavior.STATE_COLLAPSED
        )
        seriesInProgress = true
        poseLandmarkerHelper.exerciseFSM.setEnabled(false)
        setSeriesButton(active = true)
        fragmentCameraBinding.tvCountdown.visibility = View.VISIBLE
        fragmentCameraBinding.tvCountdown.text = "3"
        countdownTimer = object : CountDownTimer(3_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                fragmentCameraBinding.tvCountdown.text = ((millisUntilFinished + 999L) / 1_000L).toString()
            }

            override fun onFinish() {
                countdownTimer = null
                fragmentCameraBinding.tvCountdown.visibility = View.GONE
                repetitionTelemetryManager.startTrainingSet()
                poseLandmarkerHelper.exerciseFSM.setEnabled(true)
                fragmentCameraBinding.tvRepetitionCount.text = "Repeticiones: 0"
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
        if (_fragmentCameraBinding != null) {
            fragmentCameraBinding.tvCountdown.visibility = View.GONE
        }
        if (this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.exerciseFSM.setEnabled(true)
    }

    private fun endSeries() {
        if (countdownTimer != null) {
            cancelCountdown()
            seriesInProgress = false
            setSeriesButton(active = false)
            fragmentCameraBinding.tvRepetitionCount.text = "Repeticiones: 0"
            return
        }
        if (this::repetitionTelemetryManager.isInitialized && repetitionTelemetryManager.isTrainingActive) {
            val setJson = repetitionTelemetryManager.endTrainingSet(userProfileRepository.getProfile())
            setJson?.let { jsonOutput ->
                Log.d("JSON", jsonOutput)
                if (this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.isPausedForSlm = true
                SlmFeedbackDialogFragment.newInstance(jsonOutput)
                    .show(parentFragmentManager, "SlmFeedbackDialog")
            }
        }
        seriesInProgress = false
        if (this::poseLandmarkerHelper.isInitialized) poseLandmarkerHelper.exerciseFSM.setEnabled(true)
        setSeriesButton(active = false)
        fragmentCameraBinding.tvRepetitionCount.text = "Repeticiones: 0"
    }

    /** Builds the rule strategy corresponding to the exercise picker selection. */
    private fun strategyFor(type: ExerciseType): ExerciseStrategy = when (type) {
        ExerciseType.SQUAT -> SquatStrategy()
        ExerciseType.DEADLIFT -> DeadliftStrategy()
        ExerciseType.BICEP_CURL -> BicepCurlStrategy()
    }

    // Update the values displayed in the bottom sheet. Reset Poselandmarker
    // helper.
    private fun updateControlsUi() {
        if(this::poseLandmarkerHelper.isInitialized) {
            fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseDetectionConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseTrackingConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPosePresenceConfidence
                )

            // Needs to be cleared instead of reinitialized because the GPU
            // delegate needs to be initialized on the thread using it when applicable
            backgroundExecutor.execute {
                poseLandmarkerHelper.clearPoseLandmarker()
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            fragmentCameraBinding.overlay.clear()
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // Keep inference input independent of the PreviewView resolution.
        // The preview stays at its native 4:3 quality while analysis targets 640x480.
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                )
            )
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setResolutionSelector(analysisResolutionSelector)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        try {
                            detectPose(image)
                        } finally {
                            image.close()
                        }
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after pose have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: PoseLandmarkerHelper.ResultBundle
    ) {
        val landmarks = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull()
        if (this::repetitionTelemetryManager.isInitialized) {
            val pendingJson = if (landmarks != null) {
                repetitionTelemetryManager.processFrame(landmarks)
            } else {
                repetitionTelemetryManager.onNoPoseDetected()
            }
            pendingJson?.let { jsonOutput ->
                Log.d("BIOMECHANICS_JSON", jsonOutput)
            }
        }

        val currentTime = SystemClock.uptimeMillis()
        val shouldUpdateMetrics =
            currentTime - lastMetricsUiUpdateTime >= METRICS_UI_UPDATE_INTERVAL_MS
        if (shouldUpdateMetrics) lastMetricsUiUpdateTime = currentTime

        activity?.runOnUiThread {
            val binding = _fragmentCameraBinding
            if (binding != null) {
                if (shouldUpdateMetrics) {
                    (activity as? MainActivity)?.setMetrics(
                        fps = String.format(Locale.US, "%.1f FPS", resultBundle.framesPerSecond),
                        inferenceTime = String.format(Locale.US, "%d ms", resultBundle.inferenceTime)
                    )
                    if (this@CameraFragment::repetitionTelemetryManager.isInitialized) {
                        binding.tvRepetitionCount.text = "Repeticiones: ${repetitionTelemetryManager.repetitionCount}"
                        binding.tvFsmState.text = "Estado FSM: ${poseLandmarkerHelper.exerciseFSM.currentCondition.name}"
                        binding.btnStartTrain.isEnabled = true
                    }
                }

                // Pass necessary information to OverlayView for drawing on the canvas
                resultBundle.results.firstOrNull()?.let { result ->
                    binding.overlay.setResults(
                        result,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        RunningMode.LIVE_STREAM,
                        poseLandmarkerHelper.exerciseFSM.currentCondition
                    )
                }

                // Force a redraw
                binding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    PoseLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}
