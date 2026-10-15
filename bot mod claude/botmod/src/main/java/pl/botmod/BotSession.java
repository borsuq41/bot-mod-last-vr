package pl.botmod;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

/**
 * Prosty bot (klient protokolu Minecraft 1.21.4, protokol 769) dla serwerow offline-mode.
 * Nie uzywa klas Minecrafta - tylko gniazdo TCP, wiec dziala niezaleznie od mappingow.
 */
public final class BotSession implements Runnable {
    // ---- ID pakietow dla 1.21.4 (protokol 769). Jesli cos nie dziala - zmieniasz tylko tutaj. ----
    static final int PROTOCOL = 769;

    // login (clientbound)
    static final int L_DISCONNECT = 0x00, L_ENCRYPTION = 0x01, L_SUCCESS = 0x02, L_COMPRESSION = 0x03, L_PLUGIN_REQ = 0x04;
    // configuration (clientbound)
    static final int C_COOKIE_REQ = 0x00, C_DISCONNECT = 0x02, C_FINISH = 0x03, C_KEEPALIVE = 0x04,
            C_PING = 0x05, C_ADD_PACK = 0x09, C_KNOWN_PACKS = 0x0E;
    // play (clientbound)
    static final int P_CHUNK_BATCH_FINISHED = 0x0B, P_DISCONNECT = 0x1C, P_KEEPALIVE = 0x26, P_LOGIN = 0x2B,
            P_PING = 0x36, P_SYNC_POS = 0x41, P_START_CONFIG = 0x6F, P_SYSTEM_CHAT = 0x72;
    // serverbound play
    static final int S_TELEPORT_CONFIRM = 0x00, S_CHUNK_BATCH_RECEIVED = 0x09, S_ACK_CONFIG = 0x0E,
            S_KEEPALIVE = 0x1A, S_PONG = 0x29;

    enum State { LOGIN, CONFIG, PLAY }

    private final String name;
    private final String host;
    private final int port;
    private final Consumer<String> log;

    private volatile boolean running = true;
    private volatile boolean connected = false;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private int compression = -1;
    private State state = State.LOGIN;

    public BotSession(String name, String address, Consumer<String> log) {
        this.name = name;
        this.log = log;
        String h = address.trim();
        int p = 25565;
        boolean explicitPort = false;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            try {
                p = Integer.parseInt(h.substring(colon + 1));
                h = h.substring(0, colon);
                explicitPort = true;
            } catch (NumberFormatException ignored) {}
        }
        this.host = h;
        this.port = p;
        this.explicitPort = explicitPort;
    }

    private final boolean explicitPort;

    public boolean isRunning() { return running; }
    public boolean isInGame() { return connected && state == State.PLAY; }

    public void start() {
        Thread t = new Thread(this, "BotMod-" + name);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        try {
            InetSocketAddress target = explicitPort ? new InetSocketAddress(host, port) : resolveSrv(host, port);
            log.accept("Lacze z " + target.getHostString() + ":" + target.getPort() + " ...");
            socket = new Socket();
            socket.connect(target, 10_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(45_000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new BufferedOutputStream(socket.getOutputStream());
            connected = true;

            // Handshake -> stan LOGIN
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, PROTOCOL);
            writeString(hs, host);
            new DataOutputStream(hs).writeShort(port);
            writeVarInt(hs, 2);
            sendPacket(0x00, hs.toByteArray());

            // Login Start: nick + UUID offline
            ByteArrayOutputStream ls = new ByteArrayOutputStream();
            writeString(ls, name);
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            DataOutputStream lsd = new DataOutputStream(ls);
            lsd.writeLong(uuid.getMostSignificantBits());
            lsd.writeLong(uuid.getLeastSignificantBits());
            sendPacket(0x00, ls.toByteArray());
            log.accept("Wyslano logowanie jako " + name);

            while (running) {
                Pkt p = readPacket();
                handle(p);
            }
        } catch (EOFException e) {
            if (running) log.accept("Serwer zamknal polaczenie.");
        } catch (Exception e) {
            if (running) log.accept("Blad: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            connected = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            log.accept("Bot rozlaczony.");
        }
    }

    // ------------------------------------------------------------------ obsluga pakietow

    private void handle(Pkt p) throws IOException {
        DataInputStream b = p.body;
        switch (state) {
            case LOGIN -> {
                switch (p.id) {
                    case L_DISCONNECT -> {
                        log.accept("Wyrzucony: " + jsonText(readString(b)));
                        running = false;
                    }
                    case L_ENCRYPTION -> {
                        log.accept("Serwer jest w trybie premium (online-mode) - bot bez konta nie wejdzie.");
                        running = false;
                    }
                    case L_COMPRESSION -> compression = readVarInt(b);
                    case L_PLUGIN_REQ -> {
                        int id = readVarInt(b);
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        writeVarInt(o, id);
                        o.write(0); // successful = false
                        sendPacket(0x02, o.toByteArray());
                    }
                    case L_SUCCESS -> {
                        log.accept("Zalogowano, trwa konfiguracja...");
                        sendPacket(0x03, new byte[0]); // Login Acknowledged
                        state = State.CONFIG;
                        sendBrandAndInfo();
                    }
                    default -> {}
                }
            }
            case CONFIG -> {
                switch (p.id) {
                    case C_COOKIE_REQ -> {
                        String id = readString(b);
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        writeString(o, id);
                        o.write(0); // brak ciasteczka
                        sendPacket(0x01, o.toByteArray());
                    }
                    case C_DISCONNECT -> {
                        log.accept("Wyrzucony: " + nbtText(b));
                        running = false;
                    }
                    case C_KEEPALIVE -> sendPacket(0x04, longBytes(b.readLong()));
                    case C_PING -> sendPacket(0x05, intBytes(b.readInt()));
                    case C_KNOWN_PACKS -> {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        writeVarInt(o, 0);
                        sendPacket(0x07, o.toByteArray());
                    }
                    case C_ADD_PACK -> {
                        byte[] uuid = new byte[16];
                        b.readFully(uuid);
                        for (int status : new int[]{3, 0}) { // accepted, loaded
                            ByteArrayOutputStream o = new ByteArrayOutputStream();
                            o.write(uuid);
                            writeVarInt(o, status);
                            sendPacket(0x06, o.toByteArray());
                        }
                    }
                    case C_FINISH -> {
                        sendPacket(0x03, new byte[0]);
                        state = State.PLAY;
                    }
                    default -> {}
                }
            }
            case PLAY -> {
                switch (p.id) {
                    case P_KEEPALIVE -> sendPacket(S_KEEPALIVE, longBytes(b.readLong()));
                    case P_PING -> sendPacket(S_PONG, intBytes(b.readInt()));
                    case P_LOGIN -> log.accept("Bot wszedl na serwer!");
                    case P_SYNC_POS -> {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        writeVarInt(o, readVarInt(b));
                        sendPacket(S_TELEPORT_CONFIRM, o.toByteArray());
                    }
                    case P_CHUNK_BATCH_FINISHED -> {
                        ByteArrayOutputStream o = new ByteArrayOutputStream();
                        new DataOutputStream(o).writeFloat(2.0f);
                        sendPacket(S_CHUNK_BATCH_RECEIVED, o.toByteArray());
                    }
                    case P_DISCONNECT -> {
                        log.accept("Wyrzucony: " + nbtText(b));
                        running = false;
                    }
                    case P_SYSTEM_CHAT -> {
                        String t = nbtText(b);
                        if (!t.isBlank()) log.accept("[czat] " + t);
                    }
                    case P_START_CONFIG -> {
                        sendPacket(S_ACK_CONFIG, new byte[0]);
                        state = State.CONFIG;
                    }
                    default -> {}
                }
            }
        }
    }

    private void sendBrandAndInfo() throws IOException {
        // Plugin message: minecraft:brand
        ByteArrayOutputStream brand = new ByteArrayOutputStream();
        writeString(brand, "minecraft:brand");
        writeString(brand, "vanilla");
        sendPacket(0x02, brand.toByteArray());

        // Client Information
        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ci);
        writeString(ci, "pl_pl");
        d.writeByte(8);          // view distance
        writeVarInt(ci, 0);      // chat mode: enabled
        d.writeBoolean(true);    // chat colors
        d.writeByte(0x7F);       // skin parts
        writeVarInt(ci, 1);      // main hand: right
        d.writeBoolean(false);   // text filtering
        d.writeBoolean(true);    // allow server listings
        writeVarInt(ci, 0);      // particles: all
        sendPacket(0x00, ci.toByteArray());
    }

    // ------------------------------------------------------------------ I/O

    private static final class Pkt {
        final int id;
        final DataInputStream body;
        Pkt(int id, DataInputStream body) { this.id = id; this.body = body; }
    }

    private Pkt readPacket() throws IOException {
        int len = readVarInt(in);
        byte[] data = new byte[len];
        in.readFully(data);
        if (compression >= 0) {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            int dataLen = readVarInt(bis);
            byte[] rest = bis.readAllBytes();
            if (dataLen != 0) {
                Inflater inf = new Inflater();
                inf.setInput(rest);
                byte[] buf = new byte[dataLen];
                int off = 0;
                try {
                    while (off < dataLen) {
                        int n = inf.inflate(buf, off, dataLen - off);
                        if (n == 0 && (inf.finished() || inf.needsInput())) break;
                        off += n;
                    }
                } catch (java.util.zip.DataFormatException e) {
                    throw new IOException("Blad dekompresji", e);
                } finally {
                    inf.end();
                }
                data = buf;
            } else {
                data = rest;
            }
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        int id = readVarInt(bis);
        return new Pkt(id, new DataInputStream(new ByteArrayInputStream(bis.readAllBytes())));
    }

    private void sendPacket(int id, byte[] body) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, id);
        p.write(body);
        byte[] raw = p.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compression < 0) {
            writeVarInt(frame, raw.length);
            frame.write(raw);
        } else if (raw.length < compression) {
            writeVarInt(frame, raw.length + 1);
            writeVarInt(frame, 0);
            frame.write(raw);
        } else {
            Deflater def = new Deflater();
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream comp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!def.finished()) {
                int n = def.deflate(buf);
                comp.write(buf, 0, n);
            }
            def.end();
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            writeVarInt(inner, raw.length);
            inner.write(comp.toByteArray());
            writeVarInt(frame, inner.size());
            frame.write(inner.toByteArray());
        }
        synchronized (writeLock) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    // ------------------------------------------------------------------ pomocnicze

    static int readVarInt(InputStream s) throws IOException {
        int value = 0, shift = 0, b;
        do {
            b = s.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt za duzy");
        } while ((b & 0x80) != 0);
        return value;
    }

    static void writeVarInt(OutputStream s, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            s.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        s.write(value);
    }

    static void writeString(OutputStream s, String str) throws IOException {
        byte[] b = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(s, b.length);
        s.write(b);
    }

    static String readString(DataInputStream s) throws IOException {
        int len = readVarInt(s);
        byte[] b = new byte[len];
        s.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] longBytes(long v) {
        return java.nio.ByteBuffer.allocate(8).putLong(v).array();
    }

    private static byte[] intBytes(int v) {
        return java.nio.ByteBuffer.allocate(4).putInt(v).array();
    }

    /** Wyciaga tekst z JSON-a (login disconnect). */
    private static String jsonText(String json) {
        Matcher m = Pattern.compile("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        List<String> parts = new ArrayList<>();
        while (m.find()) parts.add(m.group(1).replace("\\n", " ").replace("\\\"", "\""));
        return parts.isEmpty() ? json : String.join(" ", parts);
    }

    /** Czyta tekst (komponent w formacie NBT, jak w pakietach 1.20.3+). */
    private static String nbtText(DataInputStream s) {
        try {
            int type = s.readByte();
            List<String> out = new ArrayList<>();
            readNbt(s, type, null, out);
            return String.join(" ", out).trim();
        } catch (Exception e) {
            return "(nie udalo sie odczytac tekstu)";
        }
    }

    private static void readNbt(DataInputStream s, int type, String name, List<String> out) throws IOException {
        switch (type) {
            case 0 -> {}
            case 1 -> s.readByte();
            case 2 -> s.readShort();
            case 3 -> s.readInt();
            case 4 -> s.readLong();
            case 5 -> s.readFloat();
            case 6 -> s.readDouble();
            case 7 -> s.skipNBytes(s.readInt());
            case 8 -> {
                String v = s.readUTF();
                if (name == null || name.equals("text") || name.equals("translate")) out.add(v);
            }
            case 9 -> {
                int t = s.readByte();
                int n = s.readInt();
                for (int i = 0; i < n; i++) readNbt(s, t, null, out);
            }
            case 10 -> {
                while (true) {
                    int t = s.readByte();
                    if (t == 0) break;
                    String k = s.readUTF();
                    readNbt(s, t, k, out);
                }
            }
            case 11 -> s.skipNBytes(s.readInt() * 4L);
            case 12 -> s.skipNBytes(s.readInt() * 8L);
            default -> throw new IOException("Nieznany typ NBT " + type);
        }
    }

    /** Rozwiazuje rekord SRV (_minecraft._tcp) - tak jak robi to gra; Aternos tego uzywa. */
    private static InetSocketAddress resolveSrv(String host, int defaultPort) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            Attribute a = attrs.get("srv");
            if (a != null && a.size() > 0) {
                String[] parts = a.get(0).toString().split(" ");
                int p = Integer.parseInt(parts[2]);
                String t = parts[3];
                if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
                return new InetSocketAddress(t, p);
            }
        } catch (Exception ignored) {}
        return new InetSocketAddress(host, defaultPort);
    }
}
