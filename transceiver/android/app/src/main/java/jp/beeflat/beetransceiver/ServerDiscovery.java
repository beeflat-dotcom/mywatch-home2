package jp.beeflat.beetransceiver;



import java.net.DatagramPacket;

import java.net.DatagramSocket;

import java.net.InetAddress;

import java.nio.charset.StandardCharsets;

import java.util.UUID;



final class ServerDiscovery {

    private static final int PORT = 45455;

    static String discover() {

        DatagramSocket socket = null;

        try {

            String nonce = UUID.randomUUID().toString().replace("-", "");

            byte[] q = ("BEE_AUDIO_DISCOVER_V1:" + nonce).getBytes(StandardCharsets.US_ASCII);

            socket = new DatagramSocket(); socket.setBroadcast(true); socket.setSoTimeout(2200);

            DatagramPacket out = new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), PORT);

            socket.send(out);

            byte[] buf = new byte[512]; DatagramPacket in = new DatagramPacket(buf, buf.length);

            long end = System.currentTimeMillis() + 2500;

            while (System.currentTimeMillis() < end) {

                socket.receive(in);

                String s = new String(in.getData(), 0, in.getLength(), StandardCharsets.US_ASCII);

                String expected = "BEE_AUDIO_SERVER_V1:" + nonce + ":";

                if (!s.startsWith(expected)) continue;

                int port = Integer.parseInt(s.substring(expected.length()).trim());

                return "http://" + in.getAddress().getHostAddress() + ":" + port;

            }

        } catch (Exception ignored) {

        } finally { if (socket != null) socket.close(); }

        return "";

    }

    private ServerDiscovery() {}

}