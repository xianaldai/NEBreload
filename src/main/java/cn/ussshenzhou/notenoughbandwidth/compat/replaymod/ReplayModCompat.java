package cn.ussshenzhou.notenoughbandwidth.compat.replaymod;

import cn.ussshenzhou.notenoughbandwidth.mapping.MappingSessionHolder;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * @author MapleSugar365
 */
public class ReplayModCompat {
    private static boolean replayModInstall;
    private static Class<?> replayRecordingClass;
    private static Field guiControlsField;
    private static Method isStoppedMethod;
    private static Method isPausedMethod;
    private static volatile Connection clientConnection;
    private static volatile boolean serverSupportsNebl;
    private static volatile boolean clientRecording;
    private static volatile PacketListener lastPacketListener;
    private static volatile ConnectionProtocol lastProtocol;
    private static volatile boolean resendActive;
    private static volatile long lastResendTime;
    private static final Map<Connection, Boolean> RECORDING_STATUS = new WeakHashMap<>();

    static {
        try {
            replayRecordingClass = Class.forName("com.replaymod.recording.ReplayModRecording");
            Class<?> handlerClass = replayRecordingClass.getMethod("getConnectionEventHandler").getReturnType();
            guiControlsField = handlerClass.getDeclaredField("guiControls");
            guiControlsField.setAccessible(true);
            Class<?> guiControlsClass = guiControlsField.getType();
            isStoppedMethod = guiControlsClass.getMethod("isStopped");
            isPausedMethod = guiControlsClass.getMethod("isPaused");
            replayModInstall = true;
        } catch (ClassNotFoundException e) {
            replayModInstall = false;
        } catch (ReflectiveOperationException e) {
            replayModInstall = false;
        }
    }

    public static void setClientConnection(Connection connection) {
        synchronized (RECORDING_STATUS) {
            clientConnection = connection;
            clientRecording = false;
            RECORDING_STATUS.clear();
            lastPacketListener = null;
            lastProtocol = null;
        }
    }

    public static void setServerSupportsNebl(boolean supports) {
        serverSupportsNebl = supports;
    }

    public static void onRecordingStatusAck() {
        resendActive = false;
    }

    public static boolean isRecordingActive() {
        return clientRecording;
    }

    private static boolean checkRecordingActive() {
        if (!replayModInstall) {
            return false;
        }
        try {
            Object recordingInstance = replayRecordingClass.getField("instance").get(null);
            if (recordingInstance == null) {
                return false;
            }
            Object handler = replayRecordingClass.getMethod("getConnectionEventHandler").invoke(recordingInstance);
            if (handler == null) {
                return false;
            }
            Object listener = handler.getClass().getMethod("getPacketListener").invoke(handler);
            if (listener == null) {
                return false;
            }
            Object guiControls = guiControlsField.get(handler);
            if (guiControls == null) {
                return false;
            }
            boolean stopped = isStoppedMethod != null && (Boolean) isStoppedMethod.invoke(guiControls);
            boolean paused = isPausedMethod != null && (Boolean) isPausedMethod.invoke(guiControls);
            return !stopped && !paused;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static void tickRecordingStatus() {
        if (!replayModInstall) {
            return;
        }
        Connection connection;
        boolean recording;
        synchronized (RECORDING_STATUS) {
            connection = clientConnection;
            if (connection != null && !connection.isConnected()) {
                clientConnection = null;
                clientRecording = false;
                RECORDING_STATUS.clear();
                return;
            }
            if (connection == null || !serverSupportsNebl) {
                return;
            }
            ConnectionProtocol currentProtocol = connection.getInboundProtocol().id();
            if (lastProtocol != null && lastProtocol != ConnectionProtocol.PLAY && currentProtocol == ConnectionProtocol.PLAY) {
                RECORDING_STATUS.clear();
                resendActive = true;
                lastResendTime = 0L;
                MappingSessionHolder.clear(connection);
                ZstdHelper.clearCache(connection);
            }
            lastProtocol = currentProtocol;
            if (currentProtocol != ConnectionProtocol.PLAY) {
                return;
            }
            PacketListener currentListener = connection.getPacketListener();
            if (lastPacketListener != currentListener) {
                lastPacketListener = currentListener;
                RECORDING_STATUS.clear();
            }
            recording = checkRecordingActive();
            clientRecording = recording;
            Boolean last = RECORDING_STATUS.get(connection);
            long now = System.currentTimeMillis();
            boolean needSend = last == null || last != recording;
            if (resendActive && now - lastResendTime >= 1000L) {
                lastResendTime = now;
                needSend = true;
            }
            if (!recording) {
                resendActive = false;
            }
            if (!needSend) {
                return;
            }
            RECORDING_STATUS.put(connection, recording);
        }
        try {
            connection.send(new ServerboundCustomPayloadPacket(new ReplayModRecordingStatusPayload(recording)), null, true);
        } catch (Exception ignored) {
        }
    }

    public static void captureSubPacket(Packet<?> mcPacket) {
        if (!replayModInstall) {
            return;
        }
        try {
            Object recordingInstance = replayRecordingClass.getField("instance").get(null);
            if (recordingInstance == null) return;

            Object handler = replayRecordingClass.getMethod("getConnectionEventHandler").invoke(recordingInstance);
            if (handler == null) return;

            Object listener = handler.getClass().getMethod("getPacketListener").invoke(handler);
            if (listener == null) return;

            for (Method m : listener.getClass().getMethods()) {
                if (!"save".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isInstance(mcPacket)) {
                    m.invoke(listener, mcPacket);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
