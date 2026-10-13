package com.google.android.datatransport.runtime;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.TransportScheduleCallback;

/**
 * RWR: a do-nothing stand-in for Google's datatransport runtime.
 *
 * MediaPipe Tasks (eye tracking) contains a usage-statistics client (RemoteLoggingClient) that sends events to
 * Google's Clearcut logging service through this runtime and its CCT backend. RWR never sends analytics, so the
 * real runtime and backend are excluded from the build (app/build.gradle.kts) and this class, with the same name
 * and the only methods MediaPipe calls, drops every event. Nothing is queued, scheduled or transmitted.
 */
public final class TransportRuntime {
    private static final TransportRuntime INSTANCE = new TransportRuntime();

    private TransportRuntime() {}

    public static void initialize(Context context) {}

    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    public TransportFactory newFactory(Destination destination) {
        return DROP_ALL;
    }

    private static final Transport<Object> NO_OP = new Transport<Object>() {
        @Override public void send(Event<Object> event) {}

        @Override public void schedule(Event<Object> event, TransportScheduleCallback callback) {
            if (callback != null) callback.onSchedule(null);
        }
    };

    private static final TransportFactory DROP_ALL = new TransportFactory() {
        @Override @SuppressWarnings("unchecked")
        public <T> Transport<T> getTransport(String name, Class<T> type, Transformer<T, byte[]> transformer) {
            return (Transport<T>) NO_OP;
        }

        @Override @SuppressWarnings("unchecked")
        public <T> Transport<T> getTransport(String name, Class<T> type, Encoding encoding, Transformer<T, byte[]> transformer) {
            return (Transport<T>) NO_OP;
        }
    };
}
