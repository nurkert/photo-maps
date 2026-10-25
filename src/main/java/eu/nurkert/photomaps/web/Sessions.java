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
 * whose QR code points to the session's page. Sessions live in memory only and end after a configurable time or after
 * their upload; right-clicking the code opens a new one with a new token.
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
     * Opens a session with a fresh token. A map shows at most one session: opening a new one for a map ends the old
     * one, so an earlier QR code or link stops working at once.
     */
    public synchronized Session open(UUID player, String playerName, int mapId, long durationMillis) {
        if (mapId >= 0) {
            Session old = byMap.remove(mapId);
            if (old != null) {
                byToken.remove(old.token());
            }
        }
        Session session = new Session(
                newToken(), player, playerName, mapId, clock.getAsLong() + durationMillis, new AtomicBoolean());
        byToken.put(session.token(), session);
        if (mapId >= 0) {
            byMap.put(mapId, session);
        }
        return session;
    }

    /** Ends a session, after its picture arrived: its token is worthless from now on. */
    public synchronized void close(Session session) {
        byToken.remove(session.token(), session);
        if (session.mapId() >= 0) {
            byMap.remove(session.mapId(), session);
        }
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
