package eu.nurkert.photomaps.resourcepack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ResourcePacksTest {

    @Test
    void releasesUseTheirGitHubRelease() {
        assertEquals(
                "https://github.com/nurkert/photo-maps/releases/download/v1.0.1/PhotoMaps-resourcepack-1.0.1.zip",
                ResourcePacks.releaseUrl("1.0.1"));
    }

    @Test
    void developmentBuildsHaveNoRelease() {
        assertNull(ResourcePacks.releaseUrl("1.0.1-SNAPSHOT"));
        assertNull(ResourcePacks.releaseUrl(""));
    }
}
