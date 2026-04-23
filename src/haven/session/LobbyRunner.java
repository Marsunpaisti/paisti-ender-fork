package haven.session;

import haven.Bootstrap;
import haven.UI;

public class LobbyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
        SessionManager.getInstance().waitForAddRequest();
        return new Bootstrap();
    }
}
