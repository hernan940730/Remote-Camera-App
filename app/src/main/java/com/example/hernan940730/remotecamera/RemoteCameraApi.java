package com.example.hernan940730.remotecamera;

import android.content.Context;
import android.content.DialogInterface;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import io.socket.client.Socket;
import io.socket.emitter.Emitter;

// Based on the official example from https://developer.android.com/guide/topics/media/camera.html

public class RemoteCameraApi extends AppCompatActivity {

    public static final int MEDIA_TYPE_IMAGE = 1;
    private static final String PICTURE_FILENAME = "picture.jpg";
    private static final String TAG = "RemoteCameraApi";
    private Camera mCamera;
    private CameraPreview mPreview;
    private boolean mFlashStatus = false;
    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    //Socket to connect with server
    private Socket socket;
    private SocketClient connection;

    /** Create a file Uri for saving an image or video */
    private static Uri getOutputMediaFileUri(int type){
        return Uri.fromFile(getOutputMediaFile(type));
    }

    /** Create a File for saving an image or video */
    private static File getOutputMediaFile(int type){
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MyCameraApp");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d("MyCameraApp", "failed to create directory");
                return null;
            }
        }

        // Create a media file name
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    PICTURE_FILENAME);
        } else {
            return null;
        }

        return mediaFile;
    }

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {

            File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
            if (pictureFile == null){
                Log.d(TAG, "Error creating media file, check storage permissions: ");
                return;
            }

            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();

                if(lastPictureRequest != null) {
                    JSONObject pars = new JSONObject();
                    try {
                        pars.put("request_id", lastPictureRequest.get("request_id"));
                        pars.put("image", new String(Base64.encode(data, Base64.DEFAULT), "UTF-8"));
                        socket.emit("picture-available", pars);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSONException while attempting to send image", e);
                    }
                }
                lastPictureRequest = null;

                Toast.makeText(RemoteCameraApi.this, "Image saved to " + pictureFile.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
                Log.d(TAG, "Saved image to " + pictureFile.getAbsolutePath());
            } catch (FileNotFoundException e) {
                Log.d(TAG, "File not found: " + e.getMessage());
            } catch (IOException e) {
                Log.d(TAG, "Error accessing file: " + e.getMessage());
            }

            mCamera.startPreview();
        }
    };

    private void toggleFlash() {
        mFlashStatus = !mFlashStatus;
        updateParameters();
    }

    private void updateParameters() {
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        parameters.setFlashMode(mFlashStatus ? Camera.Parameters.FLASH_MODE_TORCH :
                Camera.Parameters.FLASH_MODE_OFF);

        mPreview.setParameters(parameters);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mCamera = getCameraInstance();
        mPreview.setCamera(mCamera);
        mCamera.startPreview();
        wakeLock.acquire();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // Create an instance of Camera
        mCamera = getCameraInstance();


        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(this);
        updateParameters();
        mPreview.setCamera(mCamera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.camera_preview);
        preview.addView(mPreview);

        // Add a listener to the Capture button
        Button captureButton = (Button) findViewById(R.id.button_capture);
        captureButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        takePicture();
                    }
                }
        );

        Button flashButton = (Button) findViewById(R.id.button_flash);
        flashButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // get an image from the camera
                        toggleFlash();
                    }
                }
        );

        powerManager = (PowerManager)this.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "My Lock");

        String serverIP;
        String serverPort;
        if ( savedInstanceState != null &&
                (serverIP = savedInstanceState.getString("SERVER_IP")) != null &&
                (serverPort = savedInstanceState.getString("SERVER_PORT")) != null ) {
            initializeSocketClient(serverIP, serverPort);
        } else {
            showSocketAlert();
        }
    }

    private void takePicture() {
        // get an image from the camera
        mCamera.takePicture(null, null, mPicture);
    }

    private JSONObject lastPictureRequest;
    private Emitter.Listener onPictureRequested = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            RemoteCameraApi.this.runOnUiThread(
                    new Runnable() {
                        public void run() {
                            lastPictureRequest = (JSONObject) args[0];
                            takePicture();
                        }
                    });
        }
    };

    /** A safe way to get an instance of the Camera object. */
    private static Camera getCameraInstance(){
        Camera c = null;
        try {
            c = Camera.open(); // attempt to get a Camera instance
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
            Log.e(TAG, "Exception while initializing camera", e);
        }
        return c; // returns null if camera is unavailable
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();              // release the camera immediately on pause event
        wakeLock.release();
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
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
                String serverIp = serverIPText.getText().toString();
                String port = serverPortText.getText().toString();

                initializeSocketClient(serverIp, port);
            }
        });

        alert.show();
    }

    private void initializeSocketClient(String serverIp, String port) {
        connection = new SocketClient(serverIp, port);
        socket = connection.getSocket();
        socket.on("picture-requested", onPictureRequested);
        socket.connect();
    }

}
