package cn.ussshenzhou.notenoughbandwidth.compat.replaymod;

import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Method;

/**
 * @author MapleSugar365
 */
public class ReplayModCompat {
    private static boolean replayModInstall;
    private static Class<?> replayRecordingClass;

    static {
        try {
            replayRecordingClass = Class.forName("com.replaymod.recording.ReplayModRecording");
            replayModInstall = true;
        } catch (ClassNotFoundException e) {
            replayModInstall = false;
        }
    }

    /**
     * Forwards the decoded MC Packet to ReplayMod's PacketListener.save() if recording.
     */
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
