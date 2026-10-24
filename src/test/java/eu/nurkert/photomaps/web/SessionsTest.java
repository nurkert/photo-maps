package eu.nurkert.photomaps.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SessionsTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final Sessions sessions = new Sessions(now::get);
    private final UUID alex = UUID.randomUUID();
    private final UUID steve = UUID.randomUUID();

    @Test
    void tokensAreShortAndUrlSafe() {
        String token = Sessions.newToken();
        assertEquals(16, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void sessionsExpire() {
        Sessions.Session session = sessions.open(alex, "Alex", 7, 1_000);
        assertEquals(session, sessions.get(session.token()));
        assertEquals(session, sessions.forMap(7));
        now.addAndGet(1_000);
        assertNull(sessions.get(session.token()));
        assertNull(sessions.forMap(7));
    }

    @Test
    void reopeningByTheSamePlayerKeepsTheCode() {
        Sessions.Session first = sessions.open(alex, "Alex", 7, 1_000);
        now.addAndGet(900);
        Sessions.Session second = sessions.open(alex, "Alex", 7, 1_000);
        assertEquals(first.token(), second.token());
        now.addAndGet(500);
        assertEquals(second, sessions.get(first.token()));
    }

    @Test
    void anotherPlayerGetsANewCode() {
        Sessions.Session first = sessions.open(alex, "Alex", 7, 1_000);
        Sessions.Session second = sessions.open(steve, "Steve", 7, 1_000);
        assertNotEquals(first.token(), second.token());
        assertNull(sessions.get(first.token()));
        assertEquals(steve, sessions.forMap(7).player());
    }

    @Test
    void sessionsWithoutMapAreIndependent() {
        Sessions.Session a = sessions.open(alex, "Alex", -1, 1_000);
        Sessions.Session b = sessions.open(alex, "Alex", -1, 1_000);
        assertNotEquals(a.token(), b.token());
        assertEquals(a, sessions.get(a.token()));
    }
}
