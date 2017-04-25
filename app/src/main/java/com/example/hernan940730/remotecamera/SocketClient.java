package com.example.hernan940730.remotecamera;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SocketClient {

    // Connection variables
    private Socket socket;
    private String serverIp;
    private String serverPort;

    private static final String TAG = "socket-io";

    public SocketClient(String serverIp, String serverPort) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;

        initSocket();
    }

    private void initSocket() {
        String url = "http://" + serverIp + ":" + serverPort;

        try {
            socket = IO.socket(url);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid URI: " + url, e);
        }

        socket.on(Socket.EVENT_CONNECT, onConnect);
        socket.on(Socket.EVENT_CONNECT_ERROR, onConnectionError);
    }

    private Emitter.Listener onConnect = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject pars = new JSONObject();
            try {
                pars.put("client_name", "Sample android client");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            socket.emit("register-device", pars);
            Log.e(TAG, "Connected");
        }
    };

    private Emitter.Listener onConnectionError = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.e(TAG, "Failure in connection");
            Log.e(TAG, "Socket error: " + args[0].toString());
        }
    };

    public Socket getSocket() {
        return socket;
    }


    public String getIP(){
        return serverIp;
    }
    public String getPort(){
        return serverPort;
    }
}
