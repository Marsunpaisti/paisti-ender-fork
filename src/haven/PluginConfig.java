package haven;

public final class PluginConfig {
    private PluginConfig() {
    }

    public static CFG<Boolean> enabled(String configName, boolean defval) {
        if ((configName == null) || configName.trim().isEmpty()) {
            throw new IllegalArgumentException("plugin configName must not be blank");
        }
        for (int i = 0; i < configName.length(); i++) {
            char ch = configName.charAt(i);
            if (!isAllowedConfigNameChar(ch)) {
                throw new IllegalArgumentException("plugin configName must use only ASCII letters, digits, '_' or '-'");
            }
        }
        return new CFG<>("pluginv2." + configName + ".enabled", defval);
    }

    private static boolean isAllowedConfigNameChar(char ch) {
        return ((ch >= 'a') && (ch <= 'z'))
            || ((ch >= 'A') && (ch <= 'Z'))
            || ((ch >= '0') && (ch <= '9'))
            || (ch == '_')
            || (ch == '-');
    }
}
