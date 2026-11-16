package com.example.essence

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.essence.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showResult(
                    InstructionEngine.Essence(
                        title = "Нужен доступ к камере",
                        steps = listOf(
                            "Открой Настройки → Приложения → Суть → Разрешения → Камера.",
                            "Разреши доступ.",
                            "Вернись в приложение и нажми «Показать суть»."
                        )
                    )
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnCapture.setOnClickListener {
            if (hasCameraPermission()) {
                takePicture()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        binding.tvStatus.text = "Наведи камеру на предмет и нажми «Показать суть»"

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageCapture = capture

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            } catch (e: Exception) {
                showResult(
                    InstructionEngine.Essence(
                        title = "Ошибка камеры",
                        steps = listOf(
                            e.message ?: "Неизвестная ошибка камеры.",
                            "Попробуй перезапустить приложение.",
                            "Проверь, не занято ли приложение другой камерой."
                        )
                    )
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePicture() {
        val capture = imageCapture

        if (capture == null) {
            binding.tvStatus.text = "Камера ещё запускается. Попробуй через секунду."
            return
        }

        binding.btnCapture.isEnabled = false
        binding.tvStatus.text = "Снимаю и анализирую..."

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val mediaImage = image.image

                    if (mediaImage == null) {
                        image.close()
                        finishCapture(
                            InstructionEngine.Essence(
                                title = "Камера вернула пустой кадр",
                                steps = listOf(
                                    "Протри объектив.",
                                    "Сделай кадр ещё раз.",
                                    "Проверь освещение.",
                                    "Не закрывай камеру пальцем."
                                )
                            )
                        )
                        return
                    }

                    val inputImage = InputImage.fromMediaImage(
                        mediaImage,
                        image.imageInfo.rotationDegrees
                    )

                    analyze(inputImage) {
                        image.close()
                    }
                }

                override fun onFailure(exception: ImageCaptureException) {
                    finishCapture(
                        InstructionEngine.Essence(
                            title = "Не удалось сделать снимок",
                            steps = listOf(
                                exception.message ?: "Ошибка съёмки.",
                                "Перезапусти приложение.",
                                "Попробуй снова."
                            )
                        )
                    )
                }
            }
        )
    }

    private fun analyze(inputImage: InputImage, onFinished: () -> Unit) {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

        labeler.process(inputImage)
            .addOnSuccessListener { labels ->
                val best = labels
                    .maxByOrNull { it.confidence }
                    ?.takeIf { it.confidence >= 0.55f }

                val essence = InstructionEngine.build(best)

                onFinished()
                finishCapture(essence)
            }
            .addOnFailureListener { e ->
                onFinished()
                finishCapture(
                    InstructionEngine.Essence(
                        title = "ИИ не смог обработать кадр",
                        steps = listOf(
                            e.message ?: "Неизвестная ошибка распознавания.",
                            "Попробуй ещё раз при лучшем свете.",
                            "Сними объект крупнее."
                        )
                    )
                )
            }
    }

    private fun finishCapture(essence: InstructionEngine.Essence) {
        runOnUiThread {
            binding.btnCapture.isEnabled = true
            showResult(essence)
        }
    }

    private fun showResult(essence: InstructionEngine.Essence) {
        binding.tvStatus.text = essence.title

        val sb = StringBuilder()

        essence.steps.forEachIndexed { index, step ->
            sb.append(index + 1)
                .append(". ")
                .append(step)
                .append('\n')
        }

        sb.append('\n')
        sb.append(essence.disclaimer)

        binding.tvResult.text = sb.toString()
        binding.scrollResult.isVisible = true
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
