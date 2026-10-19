package com.fursan.lan.android;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class BluetoothFrameCodecTest {
    @Test
    public void roundTripsUtf8JsonFrames() throws Exception {
        JSONObject input = new JSONObject().put("v", 1).put("event", "chat").put("text", "مرحباً");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BluetoothFrameCodec.writeFrame(new DataOutputStream(bytes), input);
        JSONObject output = BluetoothFrameCodec.readFrame(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(input.getInt("v"), output.getInt("v"));
        assertEquals(input.getString("event"), output.getString("event"));
        assertEquals(input.getString("text"), output.getString("text"));
    }

    @Test
    public void readsFramesWhenUnderlyingStreamReturnsOneByteAtATime() throws Exception {
        JSONObject input = new JSONObject().put("v", 1).put("text", "fragmented");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BluetoothFrameCodec.writeFrame(new DataOutputStream(bytes), input);
        InputStream oneByteReads = new ByteArrayInputStream(bytes.toByteArray()) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(1, length));
            }
        };
        JSONObject output = BluetoothFrameCodec.readFrame(new DataInputStream(oneByteReads));
        assertEquals(input.getInt("v"), output.getInt("v"));
        assertEquals(input.getString("text"), output.getString("text"));
    }

    @Test
    public void rejectsOversizedOutboundAndInboundFrames() throws Exception {
        JSONObject oversized = new JSONObject().put("data", new String(new char[BluetoothFrameCodec.MAX_FRAME_BYTES]).replace('\0', 'x'));
        assertThrows(IOException.class, () -> BluetoothFrameCodec.writeFrame(new DataOutputStream(new ByteArrayOutputStream()), oversized));

        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(invalid);
        output.writeInt(BluetoothFrameCodec.MAX_FRAME_BYTES + 1);
        assertThrows(IOException.class, () -> BluetoothFrameCodec.readFrame(new DataInputStream(new ByteArrayInputStream(invalid.toByteArray()))));
    }

    @Test
    public void rejectsMalformedJson() throws Exception {
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(invalid);
        byte[] payload = "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(payload.length);
        output.write(payload);
        assertThrows(IOException.class, () -> BluetoothFrameCodec.readFrame(new DataInputStream(new ByteArrayInputStream(invalid.toByteArray()))));
    }
}
