package haven;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigHomeDirTest {
    @Test
    @Tag("unit")
    void defaultModeUsesStableHashdirHome() {
        Path cacheBase = Paths.get("C:/Users/test/AppData/Roaming/Haven and Hearth/data");
        Path workdir = Paths.get("C:/repo/bin");

        Path home = Config.resolveHomeDir(null, cacheBase, workdir);

        assertEquals(cacheBase.getParent().resolve("paisti-ender-fork"), home);
    }

    @Test
    @Tag("unit")
    void explicitHashdirUsesStableNamedFolder() {
        Path cacheBase = Paths.get("C:/Users/test/AppData/Roaming/Haven and Hearth/data");
        Path workdir = Paths.get("C:/repo/bin-dev");

        Path home = Config.resolveHomeDir("hashdir", cacheBase, workdir);

        assertEquals(cacheBase.getParent().resolve("paisti-ender-fork"), home);
    }

    @Test
    @Tag("unit")
    void explicitWorkdirStillUsesWorkingDirectory() {
        Path cacheBase = Paths.get("C:/Users/test/AppData/Roaming/Haven and Hearth/data");
        Path workdir = Paths.get("C:/repo/bin-dev");

        Path home = Config.resolveHomeDir("workdir", cacheBase, workdir);

        assertEquals(workdir, home);
    }
}
