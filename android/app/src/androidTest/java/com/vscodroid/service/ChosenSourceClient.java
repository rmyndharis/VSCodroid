package com.vscodroid.service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A client that connects to a loopback port from the address and the port it is told to use.
 *
 * EditorGatewayOnDeviceTest runs it with app_process, in a process of its own: toybox's nc
 * connects from wherever the system picks, and what the test needs is a connection that another
 * process makes from a source it chose. It sends what it reads from its input, writes what comes
 * back to its output, and ends when the connection is closed.
 *
 * Arguments: the source address, the source port, the port to connect to. Java, and nothing but
 * the java packages, because it is loaded from the test APK alone, without the app's classes.
 *
 * No inline tag in this comment: with one, the lint of the Kotlin test that names this class
 * fails on JDK 17, where lint's own parser calls a List method that JDK 21 added.
 */
public final class ChosenSourceClient {
    private ChosenSourceClient() {}

    public static void main(String[] args) throws Exception {
        Socket socket = new Socket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(args[0]), Integer.parseInt(args[1])));
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Integer.parseInt(args[2])));
        final OutputStream out = socket.getOutputStream();
        Thread sender = new Thread(() -> {
            try {
                int value;
                while ((value = System.in.read()) >= 0) {
                    out.write(value);
                    out.flush();
                }
            } catch (Exception ignored) {
                // The connection or the input ended; the reader below ends the process.
            }
        });
        sender.setDaemon(true);
        sender.start();
        InputStream in = socket.getInputStream();
        int value;
        while ((value = in.read()) >= 0) {
            System.out.write(value);
            System.out.flush();
        }
        System.exit(0);
    }
}
