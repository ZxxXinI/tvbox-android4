package com.tvbox.android44.common.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.tvbox.android44.R;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public final class LanQrDialog {
    private LanQrDialog() {}

    public static List<String> localIpv4s() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress entry : network.getInterfaceAddresses()) {
                    InetAddress address = entry.getAddress();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        String ip = address.getHostAddress();
                        if (!ips.contains(ip)) ips.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
            // A device can restrict interface enumeration.
        }
        return ips;
    }

    public static AlertDialog create(Activity activity, @StringRes int title, @StringRes int hint, String url) {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_qr_config, null);
        TextView address = content.findViewById(R.id.qr_url);
        TextView message = content.findViewById(R.id.qr_hint);
        QrImageView image = content.findViewById(R.id.qr_image);
        address.setText(url);
        message.setText(hint);
        image.setCode(url, success -> message.setText(success ? hint : R.string.qr_manual_hint));

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.TvDialog)
                .setTitle(title).setView(content).setCancelable(true).create();
        dialog.setOnShowListener(ignored -> image.requestFocus());
        content.findViewById(R.id.qr_close).setOnClickListener(view -> dialog.dismiss());
        return dialog;
    }
}
