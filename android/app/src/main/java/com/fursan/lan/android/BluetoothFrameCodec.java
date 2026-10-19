package com.fursan.lan.android;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BluetoothFrameCodec {
    static final int MAX_FRAME_BYTES = 64 * 1024;

    private BluetoothFrameCodec() { }

    static JSONObject readFrame(DataInputStream input) throws IOException {
        final int length;
        try {
            length = input.readInt();
        } catch (EOFException end) {
            throw end;
        }
        if (length < 1 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid Bluetooth frame length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        try {
            JSONObject frame = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (frame.length() == 0) throw new IOException("Bluetooth frame must not be empty");
            return frame;
        } catch (JSONException error) {
            throw new IOException("Invalid Bluetooth JSON frame", error);
        }
    }

    static void writeFrame(DataOutputStream output, JSONObject frame) throws IOException {
        if (frame == null || frame.length() == 0) throw new IOException("Bluetooth frame must not be empty");
        byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_FRAME_BYTES) {
            throw new IOException("Bluetooth frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }
}
