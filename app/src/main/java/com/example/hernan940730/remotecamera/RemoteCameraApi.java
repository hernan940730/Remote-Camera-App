package com.example.hernan940730.remotecamera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.Socket;
import io.socket.emitter.Emitter;


public class RemoteCameraApi extends AppCompatActivity {

    private static final String TAG = "RemoteCameraApi";
    private static final String ACCESS_TO_CAMERA_ERROR = "App could not access to camera";

    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private Button takePictureButton;
    private Button torchButton;
    private TextureView textureView;

    private boolean flashState;

    private CameraManager cManager;
    private CameraDevice[] cDevices;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCaptureSession previewCaptureSession;

    private Size imageDimension;

    final File file = new File(Environment.getExternalStorageDirectory() + "/pic.jpg");

    ImageReader reader;

    //Socket to connect with server
    private Socket socket;
    private SocketClient connection;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray ORIENTATIONS2 = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);

        ORIENTATIONS2.append(Surface.ROTATION_0, 0);
        ORIENTATIONS2.append(Surface.ROTATION_90, 270);
        ORIENTATIONS2.append(Surface.ROTATION_180, 0);
        ORIENTATIONS2.append(Surface.ROTATION_270, 90);
    }

    private CameraCaptureSession.StateCallback cSessionStateCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            //Toast.makeText(RemoteCameraApi.this, "New Capture Session", Toast.LENGTH_SHORT).show();
            previewCaptureSession = session;
            updateCameraPreview(session);
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            showErrorMessage("Session Error", "Camera Capture Session could not be configured");
        }
    };

    private CameraDevice.StateCallback cStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cDevices[0] = camera;
            createCameraPreview(camera);
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            showErrorMessage("Camera Error", "Camera is disconnected or was not found");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            showErrorMessage("Camera Error", "Camera could not be opened");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        
        textureView.setRotation(ORIENTATIONS2.get(rotation));

        assert textureView != null;
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                createCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                closeCamera();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });

        takePictureButton = (Button) findViewById(R.id.btn_takepicture);
        assert takePictureButton != null;
        takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });

        flashState = false;
        torchButton = (Button) findViewById(R.id.btn_torchmode);
        assert torchButton != null;
        torchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Boolean isFlashAvailable = getApplicationContext().getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);

                if (!isFlashAvailable) {
                    showErrorMessage("Error!!!", "Your device doesn't support flash light!");
                    return;
                }
                if (flashState) {
                    turnOffTorchMode(previewCaptureSession, previewRequestBuilder);
                } else {
                    turnOnTorchMode(previewCaptureSession, previewRequestBuilder);
                }
                flashState = !flashState;
            }
        });


        //Connection

        if(socket == null) {

            String serverIP;
            String serverPort;
            if ( savedInstanceState != null &&
                    (serverIP = savedInstanceState.getString("SERVER_IP")) != null &&
                    (serverPort = savedInstanceState.getString("SERVER_PORT")) != null ) {
                connection = SocketClient.getInstance();
                connection.initSocket(serverIP, serverPort);
                socket = connection.getSocket();
                socket.on("picture-requested", onPictureRequested);
                socket.connect();

            }
            else{
                showSocketAlert();
            }



        }
        //Toast.makeText(this, "App on created", Toast.LENGTH_SHORT).show();
    }

    /*
        Answer to "picture-requested" message send by server
     */

    private JSONObject data;
    private Emitter.Listener onPictureRequested = new Emitter.Listener() {

        @Override
        public void call(final Object... args) {
            RemoteCameraApi.this.runOnUiThread(
                    new Runnable() {
                        public void run() {
                            data = (JSONObject) args[0];
                            takePicture();
                        }
                    });
        }
    };


    public void takePicture() {
        if (cManager == null) {
            showErrorMessage("Take Picture Error", "Camera Manager is null");
            return;
        }
        if (cDevices == null) {
            showErrorMessage("Take Picture Error", "Camera Device is null");
            return;
        }
        if (previewRequestBuilder == null) {
            showErrorMessage("Take Picture Error", "Preview Request Builder is null");
            return;
        }
        if (previewCaptureSession == null) {
            showErrorMessage("Take Picture Error", "Camera Capture Session is null");
            return;
        }
        try {
            final CaptureRequest.Builder captureRequestBuilder = cDevices[0].createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(reader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            if (flashState) {
                turnOnTorchMode(previewCaptureSession, captureRequestBuilder);
            }

            previewCaptureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(RemoteCameraApi.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                }
            }, null);

        } catch (CameraAccessException e) {
            showErrorMessage("Take Picture Error", ACCESS_TO_CAMERA_ERROR);
            e.printStackTrace();
        }
    }

    private void createCamera() {
        cManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String cIds[] = cManager.getCameraIdList();
            cDevices = new CameraDevice[cIds.length];

            imageDimension = cManager.getCameraCharacteristics(cIds[0]).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceTexture.class)[0];

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_CAMERA_PERMISSION);
                return;
            }

            cManager.openCamera(cIds[0], cStateCallback, null);

        } catch (CameraAccessException e) {
            showErrorMessage("Create Camera Error", ACCESS_TO_CAMERA_ERROR);
            e.printStackTrace();
        }
    }

    private void updateCamera() {

    }

    private void closeCamera() {

        if (previewCaptureSession != null) {
            previewCaptureSession.close();
        }

        if (cDevices[0] != null) {
            cDevices[0].close();
        }

    }

    private void turnOnTorchMode(CameraCaptureSession session, CaptureRequest.Builder builder) {
        try {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            session.setRepeatingRequest(previewRequestBuilder.build(), null, null);
        } catch (Exception e) {
            showErrorMessage("Turn on flash Error", "App could not turn on flash");
            e.printStackTrace();
        }
    }

    private void turnOffTorchMode(CameraCaptureSession session, CaptureRequest.Builder builder) {
        try {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            session.setRepeatingRequest(previewRequestBuilder.build(), null, null);
        } catch (Exception e) {
            showErrorMessage("Turn off flash Error", "App could not turn off flash");
            e.printStackTrace();
        }
    }

    private void createCameraPreview(CameraDevice camera) {

        if (cManager == null) {
            showErrorMessage("Create Capture Session Error", "Camera Manager is null");
            return;
        }

        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            if (imageDimension == null) {
                showErrorMessage("Create Camera Preview Error", "Image dimension is null");
                return;
            }
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            previewRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

            previewRequestBuilder.addTarget(surface);

            CameraCharacteristics cCharacteristics = cManager.getCameraCharacteristics(cDevices[0].getId());

            Size[] jpegSizes = null;

            if (cCharacteristics != null) {
                if (cCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                    jpegSizes = cCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            .getOutputSizes(ImageFormat.JPEG);
                }
            }

            int width = 640;
            int height = 480;

            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }

            reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                        if(data != null) {
                            JSONObject pars = new JSONObject();
                            try {
                                pars.put("request_id", data.get("request_id"));
                                pars.put("image", new String(Base64.encode(bytes, Base64.DEFAULT), "UTF-8"));
                                socket.emit("picture-available", pars);
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }
                        data = null;

                    } catch (FileNotFoundException e) {
                        showErrorMessage("Image Reader Listener Error", "File not found");
                        e.printStackTrace();
                    } catch (IOException e) {
                        showErrorMessage("Image Reader Listener Error", "IO Exception");
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            }, null);


            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(reader.getSurface());

            camera.createCaptureSession(surfaces, cSessionStateCallback, null);


        } catch (CameraAccessException e) {
            showErrorMessage("Create Camera Preview Error", ACCESS_TO_CAMERA_ERROR);
            e.printStackTrace();
        }

    }

    private void updateCameraPreview(CameraCaptureSession session) {
        if (previewRequestBuilder == null) {
            showErrorMessage("Update Preview Error", "Preview Request Builder is null");
            return;
        }

        previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        try {
            session.setRepeatingRequest(previewRequestBuilder.build(), null, null);

        } catch (CameraAccessException e) {
            showErrorMessage("Update Preview Error", ACCESS_TO_CAMERA_ERROR);
            e.printStackTrace();
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            finish();
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
            } else {
                startActivity(getIntent());
            }
        }
    }

    @Override
    protected void onStart() {
        //Toast.makeText(this, "App Started", Toast.LENGTH_SHORT).show();
        super.onStart();
    }

    @Override
    protected void onResume() {
        //Toast.makeText(this, "App Resumed", Toast.LENGTH_SHORT).show();
        super.onResume();
    }

    @Override
    protected void onPause() {
        //Toast.makeText(this, "App Paused", Toast.LENGTH_SHORT).show();
        super.onPause();
        if (flashState) {
            turnOffTorchMode(previewCaptureSession, previewRequestBuilder);
            flashState = false;
        }
    }

    @Override
    protected void onStop() {
        //Toast.makeText(this, "App Stopped", Toast.LENGTH_SHORT).show();
        if(socket != null) {
            socket.close();
        }
        super.onStop();
        closeCamera();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if(connection != null) {
            outState.putString("SERVER_IP", connection.getIP());
            outState.putString("SERVER_PORT", connection.getPort());
        }
        super.onSaveInstanceState(outState);
    }

    private void showErrorMessage(String title, String message) {
        AlertDialog alert = new AlertDialog.Builder(this)
                .create();
        alert.setTitle(title);
        alert.setMessage(message);
        alert.setButton(DialogInterface.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        alert.show();
    }

    private void showSocketAlert() {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        alert.setTitle("Server Address");

        final LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        final TextView ipTitle = new TextView(this);
        final TextView portTitle = new TextView(this);
        ipTitle.setText("IP:");
        portTitle.setText("PORT:");
        final EditText serverIPText = new EditText(this);
        serverIPText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        serverIPText.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        final EditText serverPortText = new EditText(this);
        serverPortText.setInputType(InputType.TYPE_CLASS_NUMBER);

        linearLayout.addView(ipTitle);
        linearLayout.addView(serverIPText);
        linearLayout.addView(portTitle);
        linearLayout.addView(serverPortText);
        alert.setView(linearLayout);

        alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                connection = SocketClient.getInstance();
                connection.initSocket(serverIPText.getText().toString(), serverPortText.getText().toString());
                socket = connection.getSocket();
                socket.on("picture-requested", onPictureRequested);
                socket.connect();
            }
        });

        alert.show();
    }

}
