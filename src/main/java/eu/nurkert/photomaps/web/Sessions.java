package eu.nurkert.photomaps.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The open upload pages. A session belongs to one player and, if it was opened with a photo code map, to that map,
 * whose QR code points to the session's page. Sessions live in memory only and end after a configurable time;
 * right-clicking the code again opens a new one.
 */
public final class Sessions {

    /**
     * @param mapId the photo code map showing this session, -1 for a session opened by command
     */
    public record Session(String token, UUID player, String playerName, int mapId, long expiresAt, AtomicBoolean busy) {

        public boolean isExpired(long now) {
            return now >= expiresAt;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private final Map<Integer, Session> byMap = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Sessions() {
        this(System::currentTimeMillis);
    }

    Sessions(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Opens a session, or extends the session the map already shows if it belongs to the same player (so the QR code
     * in their hand stays the same). A map shows at most one session.
     */
    public synchronized Session open(UUID player, String playerName, int mapId, long durationMillis) {
        long expiresAt = clock.getAsLong() + durationMillis;
        Session current = mapId >= 0 ? active(byMap.get(mapId)) : null;
        Session session;
        if (current != null && current.player().equals(player)) {
            session = new Session(current.token(), player, playerName, mapId, expiresAt, current.busy());
        } else {
            if (current != null) {
                byToken.remove(current.token());
            }
            session = new Session(newToken(), player, playerName, mapId, expiresAt, new AtomicBoolean());
        }
        byToken.put(session.token(), session);
        if (mapId >= 0) {
            byMap.put(mapId, session);
        }
        return session;
    }

    /**
     * @return the open session with this token, {@code null} if there is none or it expired
     */
    public Session get(String token) {
        return token == null ? null : active(byToken.get(token));
    }

    /**
     * @return the open session a photo code map shows, {@code null} if it shows none
     */
    public Session forMap(int mapId) {
        return active(byMap.get(mapId));
    }

    /** Forgets expired sessions. */
    public synchronized void purge() {
        long now = clock.getAsLong();
        byToken.values().removeIf(session -> session.isExpired(now));
        byMap.values().removeIf(session -> session.isExpired(now));
    }

    public void clear() {
        byToken.clear();
        byMap.clear();
    }

    private Session active(Session session) {
        return session != null && !session.isExpired(clock.getAsLong()) ? session : null;
    }

    /** 96 random bits, 16 URL-safe characters: unguessable, and short enough for a small QR code. */
    static String newToken() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
