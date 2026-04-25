package paisti.client.tabs;

public final class PaistiClientTabHotkeys {
    private PaistiClientTabHotkeys() {
    }

    public static void addSession() {
        PaistiClientTabManager.getInstance().requestNewLoginTab();
    }

    public static void removeSession() {
        PaistiClientTabManager.getInstance().closeActiveTab();
    }

    public static void previousSession() {
        PaistiClientTabManager.getInstance().switchToPrevious();
    }

    public static void nextSession() {
        PaistiClientTabManager.getInstance().switchToNext();
    }
}
