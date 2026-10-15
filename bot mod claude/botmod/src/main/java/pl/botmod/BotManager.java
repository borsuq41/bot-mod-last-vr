package pl.botmod;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Trzyma aktualnego bota i konsole - zostaje w pamieci po zamknieciu GUI. */
public final class BotManager {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<String> LINES = new ArrayList<>();
    private static BotSession session;

    private BotManager() {}

    public static synchronized void log(String msg) {
        LINES.add("[" + LocalTime.now().format(TIME) + "] " + msg);
        while (LINES.size() > 300) LINES.remove(0);
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized boolean isActive() {
        return session != null && session.isRunning();
    }

    public static synchronized void start(String nick, String address) {
        if (isActive()) return;
        session = new BotSession(nick, address, BotManager::log);
        session.start();
    }

    public static synchronized void stop() {
        if (session != null) session.stop();
    }
}
