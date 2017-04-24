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
    private String SERVER_IP = "192.168.1.9";
    private String SERVER_PORT = "5000";
    private static final String LOG_TAG_SOCKET = "socket-io";

    private static SocketClient instance = new SocketClient();

    private SocketClient() { }

    public void initSocket(String SERVER_IP, String SERVER_PORT) {
        this.SERVER_IP = SERVER_IP;
        this.SERVER_PORT = SERVER_PORT;

        initSocket();
    }

    public void initSocket() {

        try {
            socket = IO.socket("http://" + SERVER_IP + ":" + SERVER_PORT);
        } catch (URISyntaxException e) {
            e.printStackTrace();
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
            Log.e(LOG_TAG_SOCKET, "Connected");
        }
    };

    private Emitter.Listener onConnectionError = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.e(LOG_TAG_SOCKET, "Failure in connection");
            Log.e(LOG_TAG_SOCKET, "Socket error: " + args[0].toString());
        }
    };

    public Socket getSocket() {
        return socket;
    }

    public static SocketClient getInstance() {
        return instance;
    }
}
