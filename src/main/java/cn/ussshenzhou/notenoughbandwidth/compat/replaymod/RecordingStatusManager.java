package cn.ussshenzhou.notenoughbandwidth.compat.replaymod;

import net.minecraft.network.Connection;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * @author MapleSugar365
 */
public class RecordingStatusManager {
    private static final Set<Connection> RECORDING_CONNECTIONS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static void setRecording(Connection connection, boolean recording) {
        if (recording) {
            RECORDING_CONNECTIONS.add(connection);
        } else {
            RECORDING_CONNECTIONS.remove(connection);
        }
    }

    public static boolean isRecording(Connection connection) {
        return RECORDING_CONNECTIONS.contains(connection);
    }

    public static void remove(Connection connection) {
        RECORDING_CONNECTIONS.remove(connection);
    }
}
