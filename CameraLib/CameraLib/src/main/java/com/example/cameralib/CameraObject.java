package com.example.cameralib;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


/**
 * @author Noah Tobias Lackenberger
 * @version 1.3
 * @since  1.0
 */
public class CameraObject {

    // *************************************************************** Variable Section ***************************************************************

    /**
     * this refers to the main class of the program
     */
    private Context context;

    /**
     * a Semaphore is important, because it prevents the app from closing/exiting without closing the Camera.
     * So the Camera is not locked by this app and another can't use it
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * TextureView is used to draw the Preview
     */
    private TextureView mTextureView;

    /**
     * Variable for the file / picture that is capture
     */
    private File mFile;

    /**
     * the Size of the Preview
     */
    private Size mPreviewSize;

    /**
     * The ID of the Camera in the Device
     */
    private String mCameraId;

    /**
     * constant integer var for the permission request
     */
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    /**
     * the following 2 integer vars set the maximum preview size, the maximum Value is: 480 x 480 (Size of the Vuzix Blade Display)
     */
    private static final int MAX_PREVIEW_WIDTH = 480;
    private static final int MAX_PREVIEW_HEIGHT = 480;

    /**
     * SparseArray with the specific int values for the possible picture rotations
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    /**
     * a CameraCaptureSession is used to get the Preview of the Camera
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * The Camera "itself", a Camera is represented with this var
     */
    private CameraDevice mCameraDevice;

    /**
     * Is the Builder for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;
    /**
     * The Request mPreviewRequestBuilder generates
     */
    private CaptureRequest mPreviewRequest;

    /**
     * Handler for running Tasks in the Background
     */
    private Handler mBackgroundHandler;
    /**
     * additional Thread for running tasks that must not block the UI (User Interface)
     */
    private HandlerThread mBackgroundThread;

    /**
     * Handles image-capturing
     */
    private ImageReader mImageReader;

    /**
     * This SurfaceTextureListener handles several lifestyles, it opens the Camera, when the initializing is finished. It also changes the preview size, when a size change is detected
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    /**
     * a CameraStateCallback is called, when the state of the Camera is changed (closed, opened, error)
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            mCameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
            ((Activity)context).finish();
        }
    };


    // *************************************************************** Constructor Section ***************************************************************


    /**
     *      The constructor for a Camera Object
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param context the main class must be passed here as context
     * </p>
     * <p>
     *      @throws IllegalArgumentException is thrown if a passed argument is not valid
     * </p>
     */
    public CameraObject(Context context, TextureView textureView) throws IllegalArgumentException {
        if(context == null)
            throw new IllegalArgumentException("All parameters must have a Value!");

        this.mTextureView = textureView;
        this.context = context;
    }


    // *************************************************************** Method Section ***************************************************************

    /**
     *      This method starts the camera. It must be called right after the constructor
     * <p>
     *      @version 1.0
     * </p>
     */
    public void startCamera() {


        if(this.mTextureView.isAvailable()) {
            this.openCamera(this.mTextureView.getWidth(), this.mTextureView.getHeight());
        } else {
            this.mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    /**
     *      This Method opens the camera properly
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param width the width of the output size of the camera picture / preview
     *      @param height the height of the output size of the camera picture / preview
     * </p>
     * <p>
     *      @throws RuntimeException
     * </p>
     */
    public void openCamera(int width, int height) throws RuntimeException {
        if((ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            this.requestCameraPermission();
            return;
        }

        this.setUpCameraOutputs(width, height);
        this.configureTransform(width, height);

        CameraManager manager = (CameraManager)this.context.getSystemService(Context.CAMERA_SERVICE);

        try {
            if(!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("TIMEOUT: Could not lock the Camera opening!");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock the camera opening!", e);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     *      This method closes the camera, sets all camera devices free and stops all background-activities
     * <p>
     *      It must be called before the super constructor of "onPause()"!!!!
     * </p>
     * <p>
     *      @version  1.0
     * </p>
     * <p>
     *      @throws RuntimeException
     * </p>
     */
    public void closeCamera() throws RuntimeException {
        try {
            mCameraOpenCloseLock.acquire();
            if(mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if(mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if(mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing!", e);
        } finally {
            mCameraOpenCloseLock.release();
        }

        this.stopBackgroundThread();
    }

    /**
     *      This method handles all things for resuming the camera (check if the camera is available, open the camera, etc.)
     * <p>
     *      It must be called after the super constructor of "onResume()"!!!!
     * </p>
     * <p>
     *      @version  1.0
     * </p>
     */
    public void resumeCamera() {
        this.startBackgroundThread();

        if(this.mTextureView.isAvailable()) {
            this.openCamera(this.mTextureView.getWidth(), this.mTextureView.getHeight());
        } else {
            this.mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    /**
     *      This method sets up vars, that are related to the camera (preview size, camera characteristics, etc.)
     *<p>
     *      @version 1.0
     *</p>
     * <p>
     *      @param width the width of the output size of the camera picture / preview
     *      @param height the height of the output size of the camera picture / preview
     * </p>
     */
    private void setUpCameraOutputs(int width, int height)  {
        CameraManager manager = (CameraManager)this.context.getSystemService(Context.CAMERA_SERVICE);

        try {
            for(String cameraID : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);

                // The Vuzix Blade has no Selfi Camera, so we just skip it
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                    continue; // skip

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map == null) {
                    continue;
                }

                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());

                this.mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
                this.mImageReader.setOnImageAvailableListener(null, this.mBackgroundHandler);

                Point displaySize = new Point();
                ((Activity)context).getWindowManager().getDefaultDisplay().getSize(displaySize);

                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if(maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }
                if(maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                this.mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, maxPreviewWidth, maxPreviewHeight, largest);

                this.mCameraId = cameraID;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // This must never happen! The Camera2-API is supported for the Vuzix Blade
            System.out.println("The NPE is caused because of a User Fault! An NPE is normally thrown, because Camera2-API is not supported on a device. " +
                    "Because of the official Blade-Documentation we know, that Camera2-API is supported!");
            e.printStackTrace();
        }
    }

    /**
     *      This method creates a CameraCaptureSession for a camera preview
     * <p>
     *      @version 1.0
     * </p>
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // Set the Buffer size to the size of the camera preview we want
            texture.setDefaultBufferSize(this.mPreviewSize.getWidth(), this.mPreviewSize.getHeight());

            // Output Surface
            Surface surface = new Surface(texture);

            // with the output surface we set up a CaptureRequest.Builder
            this.mPreviewRequestBuilder = this.mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            this.mPreviewRequestBuilder.addTarget(surface);

            //Create a CameraCaptureSession for the Preview
            this.mCameraDevice.createCaptureSession(Arrays.asList(surface, this.mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            // if the camera is already closed
                            if(mCameraDevice == null) {
                                return;
                            }

                            //If the session is ready, the preview will start (and displayed)
                            mCaptureSession = session;
                            try {
                                //here we initialize the auto focus
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                //start displaying the preview
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);     // the repeating request is in the Background
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            showToast("Failed");
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     *      This method asks for the permission of the Camera
     * <p>
     *      @version 1.0
     * </p>
     */
    private void requestCameraPermission() {
        if(ActivityCompat.shouldShowRequestPermissionRationale((Activity) context, Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(context).setMessage("R string request permission")
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                        }
                    }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ((Activity)context).finish();
                }
            }).create();
        } else {
            ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    /**
     *      This method must be called in the on onRequestPermissionsResult method. It handles the successful case of the Permission result
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param requestCode requestCode parameter of onRequestPermissionsResult
     *      @param permissions permissions parameter of onRequestPermissionsResult
     *      @param grantResults grantResults parameter of onRequestPermissionsResult
     *      @return false ... the super constructor of onRequestPermissionsResult must be called! -- true ... must not be handled!
     * </p>
     */
    public boolean permissionResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if(grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText((Activity)context, "ERROR: Permission denied", Toast.LENGTH_LONG).show();
            }
        } else {
            return false;
        }

        return true;
    }

    /**
     *      This method creates a new Background thread
     * <p>
     *      @version 1.0
     * </p>
     */
    private void startBackgroundThread() {
        this.mBackgroundThread = new HandlerThread("CameraBackground");
        this.mBackgroundThread.start();
        this.mBackgroundHandler = new Handler(this.mBackgroundThread.getLooper());  // Create a Background Handler with the Loop Message of the Handler Thread (in this case: "CameraBackground")
    }

    /**
     *      This method stops the Background Thread and the Background Handler
     * <p>
     *      @version 1.0
     * </p>
     */
    private void stopBackgroundThread() {
        this.mBackgroundThread.quitSafely();
        try {
            this.mBackgroundThread.join();
            this.mBackgroundThread = null;
            this.mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     *      This method selects the best fitting Size for the Preview
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param choices  the available Sizes
     *      @param textureViewWidth the width of the texture View element in activity_main.xml
     *      @param textureViewHeight the height of the texture View element in activity_main.xml
     *      @param maxWidth the maximum width = width of the display
     *      @param maxHeight the maximum height = height of the display
     *      @param aspectRatio the Ratio in which the display's height and width are (for example: 4:3, 16:4, ...)
     *      @return The best fitting Size for the Preview
     * </p>
     */
    private static Size chooseOptimalSize(@NonNull Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, @NonNull Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();       // Sizes, that are bigger than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();    // Sizes, tat are smaller than the preview Surface

        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for(Size option : choices) {
            if(option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == (option.getWidth() * h / w)) {
                if(option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick best fitting Size (smallest that is big enough or largest that is not big enough (if no size is big enough))
        if(bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e("Camera2", "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    /**
     *      This method transform the camera preview and rotates it to the right display orientation
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param viewWidth width of the camera preview
     *      @param viewHeight height of the camera preview
     * </p>
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if(this.mTextureView == null || this.mPreviewSize == null) {
            return;
        }

        int rotation = ((Activity)context).getWindowManager().getDefaultDisplay().getRotation();

        Matrix matrix = new Matrix();

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, this.mPreviewSize.getHeight(), this.mPreviewSize.getWidth());

        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if(Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float)viewHeight / this.mPreviewSize.getHeight(), (float) viewWidth / this.mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        this.mTextureView.setTransform(matrix);
    }

    /**
     *      This method shows a {@link Toast} in the UI thread
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param text the text that will be shown
     * </p>
     */
    private void showToast(final String text) {
        ((Activity)context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     *      This method is responsible for taking pictures via an request, and saves the taken image to the gallery
     * <p>
     *      @version 1.4
     * </p>
     * <p>
     * @throws CameraAccessException is thrown, when the camera access is denied
     * </p>
     */
    public void takePicture() throws CameraAccessException {
        if(this.mCameraDevice == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR);
        }

        CameraManager manager = (CameraManager)((Activity)context).getSystemService(Context.CAMERA_SERVICE);

        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);
            Size[] jpegSizes = null;

            jpegSizes = Objects.requireNonNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).getOutputSizes(ImageFormat.JPEG);

            int width = 1600;
            int height = 1200;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[5].getWidth();
                height = jpegSizes[5].getHeight();
            }

            ImageReader imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);

            List<Surface> outputSurface = new ArrayList<>(2);
            outputSurface.add(imageReader.getSurface());
            outputSurface.add(new Surface(mTextureView.getSurfaceTexture()));

            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            int rotation = ((Activity)context).getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));


            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @RequiresApi(api = Build.VERSION_CODES.O)
                @Override
                public void onImageAvailable(ImageReader reader) {
                    String fileName = "IMG_" +  new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg";

                    // Create Folder in gallery for the application
                    File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/SickCameraApplication");
                    if(!folder.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        folder.mkdirs();
                    }

                    mFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/SickCameraApplication", fileName);      // create new File

                    mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mFile)); // Save picture via ImageSaver class

                    scanFile(context, mFile, MIME_TYPES_IMAGE.JPG.toString());  // Tell the MediaScanner about the new file to refresh the system
                }
            };

            imageReader.setOnImageAvailableListener(readerListener, this.mBackgroundHandler);

            final CameraCaptureSession.CaptureCallback captureListener  = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    showToast("Saved!");
                    createCameraPreviewSession();
                }
            };

            mCameraDevice.createCaptureSession(outputSurface, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     *      This method forces an update for the filesystem via the MediaScanner, in order to see the taken pictures in the filesystem
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @param context the application context, that triggers the update
     *      @param file the file that is new in the system
     *      @param mimeType the type of the file
     * </p>
     */
    private void scanFile(Context context, File file, String mimeType) {
        MediaScannerConnection.scanFile(context, new String[] {file.getAbsolutePath()}, new String[] {mimeType}, null);
    }

    /**
     *      This method returns the URI / path of the most recent picture
     * <p>
     *      @version 1.0
     * </p>
     * <p>
     *      @return the URI from the most recent file
     * </p>
     */
    public Uri returnLatestFileUri() {
        return Uri.fromFile(this.mFile);
    }


    // *************************************************************** Class Section ***************************************************************


    /**
     *      This class compares sizes based on their areas
     * <p>
     *      @version 1.0
     *      @since 1.0
     * </p>
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size o1, Size o2) {
            // Casts to prevent overflows
            return Long.signum((long) o1.getWidth() * o1.getHeight() -
                    (long) o2.getWidth() * o2.getHeight());
        }
    }

    /**
     *      This class is responsible for storing a picture
     * <p>
     *      @version 1.0
     *      @since 1.2
     * </p>
     */
    private static class ImageSaver implements Runnable {
        private final Image mImage;
        private final File mFile;

        ImageSaver(Image image, File file) {
            this.mImage = image;
            this.mFile = file;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }


    // *************************************************************** Enum Section ***************************************************************


    /**
     *      This enum contains the valid mime_Types
     * <p>
     *      @since 1.3
     * </p>
     */
    private enum MIME_TYPES_IMAGE {
        JPEG {
            @Override
            @NonNull
            public String toString() {
                return "image/jpeg";
            }
        },
        BMP {
            @Override
            @NonNull
            public String toString() {
                return "image/bmp";
            }
        },
        GIF {
            @Override
            @NonNull
            public String toString() {
                return "image/gif";
            }
        },
        JPG {
            @Override
            @NonNull
            public String toString() {
                return "image/jpg";
            }
        },
        PNG {
            @Override
            @NonNull
            public String toString() {
                return "image/png";
            }
        };
    }

    /**
     *      This enum contains the valid mime_Types
     * <p>
     *      @since 1.3
     * </p>
     */
    private enum MIME_TYPES_VIDEO {
        WAV {
            @Override
            @NonNull
            public String toString() {
                return "video/wav";
            }
        },
        MP4 {
            @Override
            @NonNull
            public String toString() {
                return "video/mp4";
            }
        };
    }
}
