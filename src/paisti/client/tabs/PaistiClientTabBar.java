package paisti.client.tabs;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class PaistiClientTabBar extends Panel implements KeyEventDispatcher {
    static final int HEIGHT = 34;
    static final int CLOSE_W = 18;
    static final int TAB_MIN_W = 92;
    static final int TAB_MAX_W = 180;
    static final int ADD_TAB_W = 130;
    static final String ADD_LABEL = "+ Add new tab";
    static final Color BG = new Color(28, 31, 36);
    static final Color ACTIVE_BG = new Color(64, 103, 150);
    static final Color INACTIVE_BG = new Color(48, 52, 59);
    static final Color FG = new Color(235, 238, 242);
    static final Color MUTED_FG = new Color(178, 184, 191);

    public enum HitKind {ADD, CLOSE, TAB}

    public static final class HitRegion {
        public final HitKind kind;
        public final Rectangle rect;
        public final PaistiClientTab tab;

        HitRegion(HitKind kind, Rectangle rect, PaistiClientTab tab) {
            this.kind = kind;
            this.rect = rect;
            this.tab = tab;
        }
    }

    private final PaistiClientTabManager manager;
    private final Component gameComponent;
    private final PaistiClientTabManager.Listener listener = this::scheduleRepaint;

    public PaistiClientTabBar(PaistiClientTabManager manager, Component gameComponent) {
        this.manager = manager;
        this.gameComponent = gameComponent;
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if(e.getButton() != MouseEvent.BUTTON1 || e.isPopupTrigger())
                    return;
                click(e.getX(), e.getY());
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        manager.addListener(listener);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    @Override
    public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        manager.removeListener(listener);
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(640, HEIGHT);
    }

    @Override
    public void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight() <= 0 ? HEIGHT : getHeight();
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        g.setFont(getFont());
        for(HitRegion region : layoutRegionsForTests(w, h)) {
            if(region.kind == HitKind.ADD)
                paintAddTab(g, region.rect);
            else if(region.kind == HitKind.CLOSE)
                paintClose(g, region.rect);
            else
                paintTab(g, region.rect, region.tab);
        }
    }

    public List<HitRegion> layoutRegionsForTests(int width, int height) {
        List<HitRegion> regions = new ArrayList<>();
        int h = height <= 0 ? HEIGHT : height;
        int x = 4;
        List<PaistiClientTab> tabs = manager.getTabs();
        int visibleTabs = 0;
        for(PaistiClientTab tab : tabs) {
            if(isVisibleTab(tab))
                visibleTabs++;
        }
        int addw = Math.min(TAB_MAX_W, Math.max(TAB_MIN_W, ADD_TAB_W));
        int available = Math.max(0, width - x - addw - 7);
        int tabw = visibleTabs == 0 ? TAB_MIN_W : Math.max(TAB_MIN_W, Math.min(TAB_MAX_W, available / visibleTabs));
        for(PaistiClientTab tab : tabs) {
            if(!isVisibleTab(tab))
                continue;
            Rectangle tabRect = new Rectangle(x, 4, tabw, h - 8);
            regions.add(new HitRegion(HitKind.TAB, tabRect, tab));
            regions.add(new HitRegion(HitKind.CLOSE, closeRect(tabRect), tab));
            x += tabw + 3;
        }
        regions.add(new HitRegion(HitKind.ADD, new Rectangle(x, 4, addw, h - 8), null));
        return regions;
    }

    private Rectangle closeRect(Rectangle tabRect) {
        int size = Math.min(CLOSE_W, Math.max(12, tabRect.height - 8));
        return new Rectangle(tabRect.x + tabRect.width - size - 6, tabRect.y + (tabRect.height - size) / 2, size, size);
    }

    private boolean isVisibleTab(PaistiClientTab tab) {
        return tab.isSelectable() || tab.isLogin();
    }

    private void click(int x, int y) {
        List<HitRegion> regions = layoutRegionsForTests(getWidth(), getHeight());
        for(HitRegion region : regions) {
            if(region.kind != HitKind.CLOSE || !region.rect.contains(x, y))
                continue;
            manager.closeTab(region.tab);
            refocusGame();
            repaint();
            return;
        }
        for(HitRegion region : regions) {
            if(!region.rect.contains(x, y))
                continue;
            if(region.kind == HitKind.ADD)
                manager.requestNewLoginTab();
            else if(region.kind == HitKind.TAB)
                manager.activateTab(region.tab);
            refocusGame();
            repaint();
            return;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if(e.getID() != KeyEvent.KEY_PRESSED || !isClientTabShortcut(e))
            return(false);
        switch(e.getKeyCode()) {
        case KeyEvent.VK_UP:
            manager.requestNewLoginTab();
            break;
        case KeyEvent.VK_DOWN:
            manager.closeActiveTab();
            break;
        case KeyEvent.VK_LEFT:
            manager.switchToPrevious();
            break;
        case KeyEvent.VK_RIGHT:
            manager.switchToNext();
            break;
        default:
            return(false);
        }
        e.consume();
        refocusGame();
        repaint();
        return(true);
    }

    private boolean isClientTabShortcut(KeyEvent e) {
        int mods = e.getModifiersEx();
        int required = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        int excluded = InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK | InputEvent.META_DOWN_MASK;
        return((mods & required) == required && (mods & excluded) == 0);
    }

    private void paintAddTab(Graphics g, Rectangle r) {
        g.setColor(INACTIVE_BG);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(FG);
        drawCentered(g, ADD_LABEL, r);
    }

    private void paintClose(Graphics g, Rectangle r) {
        g.setColor(MUTED_FG);
        drawCentered(g, "x", r);
    }

    private void paintTab(Graphics g, Rectangle r, PaistiClientTab tab) {
        boolean active = manager.getActiveTab() == tab;
        g.setColor(active ? ACTIVE_BG : INACTIVE_BG);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g.setColor(active ? FG : MUTED_FG);
        FontMetrics fm = g.getFontMetrics();
        String label = fitLabel(tab.label(), fm, r.width - CLOSE_W - 24);
        g.drawString(label, r.x + 8, r.y + ((r.height - fm.getHeight()) / 2) + fm.getAscent());
    }

    private String fitLabel(String label, FontMetrics fm, int max) {
        if(label == null || label.isEmpty() || max <= 0)
            return "";
        if(fm.stringWidth(label) <= max)
            return label;
        String ellipsis = "...";
        if(fm.stringWidth(ellipsis) > max)
            return "";
        String base = label;
        while(!base.isEmpty()) {
            base = base.substring(0, base.length() - 1);
            String candidate = base + ellipsis;
            if(fm.stringWidth(candidate) <= max)
                return candidate;
        }
        return ellipsis;
    }

    private void drawCentered(Graphics g, String text, Rectangle r) {
        FontMetrics fm = g.getFontMetrics();
        int tx = r.x + (r.width - fm.stringWidth(text)) / 2;
        int ty = r.y + ((r.height - fm.getHeight()) / 2) + fm.getAscent();
        g.drawString(text, tx, ty);
    }

    private void scheduleRepaint() {
        if(EventQueue.isDispatchThread())
            repaint();
        else
            EventQueue.invokeLater(this::repaint);
    }

    private void refocusGame() {
        if(gameComponent != null) {
            if(!gameComponent.requestFocusInWindow())
                gameComponent.requestFocus();
        }
    }
}
