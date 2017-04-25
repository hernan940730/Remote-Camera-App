package com.example.hernan940730.remotecamera;

import android.content.Context;
import android.content.DialogInterface;
import android.hardware.Camera;
import android.os.Bundle;
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
import java.io.UnsupportedEncodingException;

import io.socket.client.Socket;
import io.socket.emitter.Emitter;

// Based on the official example from https://developer.android.com/guide/topics/media/camera.html

public class RemoteCameraApi extends AppCompatActivity {
    private static final String TAG = "RemoteCameraApi";
    private Camera mCamera;
    private CameraPreview mPreview;
    private boolean mFlashStatus = false;
    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    //Socket to connect with server
    private Socket socket;
    private SocketClient connection;

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {

                if(lastPictureRequest != null) {
                    JSONObject pars = new JSONObject();
                    try {
                        pars.put("request_id", lastPictureRequest.get("request_id"));
                        pars.put("image", new String(Base64.encode(data, Base64.DEFAULT), "UTF-8"));
                        socket.emit("picture-available", pars);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSONException while attempting to send image", e);
                    } catch (UnsupportedEncodingException e) {
                        Log.e(TAG, "UnsupportedEncodingException while attempting to send image", e);
                    }
                }
                lastPictureRequest = null;

                Toast.makeText(RemoteCameraApi.this, "Picture taken", Toast.LENGTH_LONG).show();


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
