package com.tvbox.android44.feature.settings;

import android.os.SystemClock;

import com.tvbox.android44.app.TvBoxApp;
import com.tvbox.android44.feature.search.SearchFragment;

import java.net.InetAddress;

/** Uses an ephemeral loopback port without changing the production private-LAN requirement. */
public class LoopbackPhoneSearchFragment extends SearchFragment {
    @Override protected ConfigHttpServer createPhoneSearchSession(InetAddress address, ConfigHttpServer.SubmitListener submit,
                                                                   ConfigHttpServer.CloseListener close) {
        return new ConfigHttpServer(ConfigHttpServer.Mode.SEARCH, address, 0, 1, 300000,
                TvBoxApp.get().executors().scheduler(), TvBoxApp.get().executors()::main,
                SystemClock::elapsedRealtime, submit, close);
    }
}
