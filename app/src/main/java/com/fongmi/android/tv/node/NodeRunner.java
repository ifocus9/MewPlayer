package com.fongmi.android.tv.node;

public class NodeRunner {

    private static volatile boolean loaded = false;

    public static synchronized void loadLibraries() {
        if (!loaded) {
            try {
                System.loadLibrary("c++_shared");
            } catch (Throwable ignored) {
            }
            System.loadLibrary("node");
            System.loadLibrary("node_bridge");
            loaded = true;
        }
    }

    public static native int startNodeWithArguments(String[] arguments);
}
