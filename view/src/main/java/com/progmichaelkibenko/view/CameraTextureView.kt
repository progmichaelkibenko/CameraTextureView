package com.progmichaelkibenko.view

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import java.io.*
import java.util.*
import android.hardware.camera2.CameraAccessException
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat


class CameraTextureView : TextureView, View.OnClickListener{
    companion object {
        val TAG = CameraTextureView::class.java.simpleName
    }

    private val ORIENTATIONS : SparseIntArray by lazy {
        val array = SparseIntArray()
        array.append(Surface.ROTATION_0, 90)
        array.append(Surface.ROTATION_90, 0)
        array.append(Surface.ROTATION_180, 270)
        array.append(Surface.ROTATION_270, 180)
        array
    }

    private lateinit var cameraID : String
    private var cameraDevice : CameraDevice? = null
    private lateinit var cameraCaptureSession : CameraCaptureSession
    private lateinit var captureRequestBuilder : CaptureRequest.Builder
    private lateinit var imageDimension : Size
    private var imageReader : ImageReader? = null
    private lateinit var file : File
    private var flashSupported : Boolean = false
    private var backgroundHandler : Handler? = null
    private var backgroundThread: HandlerThread? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        setOnClickListener(this)
    }

    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(p0: SurfaceTexture?, p1: Int, p2: Int) {
            openCamera()
        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return false
        }

        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {

        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {

        }
    }

    private val stateCallback = object : CameraDevice.StateCallback(){
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onError(camera: CameraDevice, p1: Int) {
            closeCamera()
        }

        override fun onDisconnected(camera: CameraDevice) {
            closeCamera()

        }

        override fun onClosed(camera: CameraDevice) {
            super.onClosed(camera)
        }
    }

    override fun onClick(view: View?) {
        takePicture()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback(){
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            createCameraPreview()
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            super.onCaptureFailed(session, request, failure)
        }
    }

    private fun startBackgroundThread(){
        backgroundThread = HandlerThread(TAG)
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    @Throws(Exception::class)
    private fun stopBackgroundThread(){
        backgroundThread?.quitSafely()
        backgroundThread?.join()
        backgroundThread = null
        backgroundHandler = null
    }

    @Throws(CameraAccessException::class)
    private fun takePicture(){
        if(cameraDevice == null){
            return
        }
        val cameraManager : CameraManager = context?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraCharacteristics : CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
        val sizes = cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG)
        var width = 640
        var height = 480
        if(sizes != null && sizes.isNotEmpty()){
            width = sizes[0].width
            height = sizes[0].height
        }
        val reader : ImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
        val outPutSurface = listOf(reader.surface, Surface(surfaceTexture))
        val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            this?.addTarget(reader.surface)
            this?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            this?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation.toInt()))
        }
        val file = File("${Environment.getExternalStorageDirectory()}/${System.currentTimeMillis()}.jpg")
        val imageReader = object : ImageReader.OnImageAvailableListener{
            override fun onImageAvailable(p0: ImageReader?) {
                var image : Image? = null
                try{
                    image = reader.acquireLatestImage()
                    var bytes = byteArrayOf()
                    image.planes[0].buffer.run {
                        get(bytes)
                        save(bytes)
                    }
                }catch (e : FileNotFoundException){
                    e.printStackTrace()
                }catch (e : IOException){
                    e.printStackTrace()
                }finally {
                    image?.close()
                }
            }

            fun save(bytes : ByteArray){
                var outputStream : OutputStream? = null
                try{
                    outputStream = FileOutputStream(file)
                    outputStream.write(bytes)
                }finally {
                    outputStream?.close()
                }
            }
        }

        reader.setOnImageAvailableListener(imageReader, backgroundHandler)

        val captureListener = object : CameraCaptureSession.CaptureCallback(){
            override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
            ) {
                super.onCaptureCompleted(session, request, result)

            }
        }

        cameraDevice?.createCaptureSession(outPutSurface, object : CameraCaptureSession.StateCallback(){
            override fun onConfigured(session: CameraCaptureSession) {
                try{
                    if(captureBuilder!= null) {
                        session.capture(captureBuilder.build(), captureListener, backgroundHandler)
                    }
                }catch (e : CameraAccessException){
                    e.printStackTrace()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {

            }
        }, backgroundHandler)
    }

    @Throws(CameraAccessException::class)
    private fun createCameraPreview(){
        surfaceTexture.setDefaultBufferSize(imageDimension.width, imageDimension.height)
        val surface = Surface(surfaceTexture)
        captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(surface)
        cameraDevice?.createCaptureSession(Arrays.asList(surface), object : CameraCaptureSession.StateCallback(){
            override fun onConfigured(session: CameraCaptureSession) {
                cameraCaptureSession = session
                updatePreview()
            }

            override fun onConfigureFailed(session : CameraCaptureSession) {}
        }, null)
    }

    @Throws(CameraAccessException::class)
    private fun openCamera(){
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraID = cameraManager.cameraIdList[0]
        val characteristics = cameraManager.getCameraCharacteristics(cameraID)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        imageDimension = map!!.getOutputSizes(SurfaceTexture::class.java)[0]
        if(ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            Toast.makeText(context, "You cant access to the camera, please grant permission", Toast.LENGTH_LONG).show()
            return
        }
        cameraManager.openCamera(cameraID, stateCallback, backgroundHandler)
    }

    private fun updatePreview(){
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
    }

    private fun closeCamera(){
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    fun onResume(){
        try{

            startBackgroundThread()
            if(isAvailable){
                openCamera()
            }else{
                surfaceTextureListener = mSurfaceTextureListener
            }
        }catch (e : CameraAccessException){
            e.printStackTrace()
        }

    }

    fun onPause(){
        stopBackgroundThread()
        closeCamera()
    }

}