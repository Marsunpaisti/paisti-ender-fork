package paisti.client.tabs;

import haven.UI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.KeyEventDispatcher;

public class PaistiClientTabBar extends JPanel implements KeyEventDispatcher {
    static final int HEIGHT = 24;
    static final int TAB_MIN_W = 92;
    static final int TAB_MAX_W = 180;
    static final int TAB_SHRINK_W = 48;
    static final int CLOSE_BUTTON_W = 24;
    static final int ADD_TAB_W = 130;
    static final String ADD_LABEL = "+ Add new tab";
    static final String TAB_PROPERTY = "paisti.tab";
    static final String CLOSE_TAB_PROPERTY = "paisti.closeTab";
    static final String ADD_TAB_PROPERTY = "paisti.addTab";
    static final Color BG = new Color(28, 31, 36);
    static final Color ACTIVE_BG = new Color(64, 103, 150);
    static final Color ACTIVE_HOVER_BG = new Color(74, 116, 166);
    static final Color INACTIVE_BG = new Color(48, 52, 59);
    static final Color INACTIVE_HOVER_BG = new Color(58, 63, 72);
    static final Color CLOSE_HOVER_BG = new Color(78, 83, 92);
    static final Color FG = new Color(235, 238, 242);
    static final Color MUTED_FG = new Color(178, 184, 191);

    private final PaistiClientTabManager manager;
    private final Component gameComponent;
    private final PaistiClientTabManager.Listener listener = this::scheduleRebuild;

    public PaistiClientTabBar(PaistiClientTabManager manager, Component gameComponent) {
        super(new TabRowLayout());
        this.manager = manager;
        this.gameComponent = gameComponent;
        setFont(UI.scale(new Font(Font.SANS_SERIF, Font.PLAIN, 13), 13));
        setOpaque(true);
        setBackground(BG);
        rebuildTabs();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        manager.addListener(listener);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        rebuildTabs();
    }

    @Override
    public void removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        manager.removeListener(listener);
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UI.scale(640), scaledHeight());
    }

    private void rebuildTabs() {
        removeAll();
        for(PaistiClientTab tab : manager.getTabs()) {
            if(!isVisibleTab(tab))
                continue;
            add(createTabComponent(tab));
        }
        add(createAddButton());
        revalidate();
        repaint();
    }

    private JPanel createTabComponent(PaistiClientTab tab) {
        boolean active = manager.getActiveTab() == tab;
        JPanel tabPanel = new JPanel(new TabComponentLayout());
        tabPanel.putClientProperty(TAB_PROPERTY, tab);
        tabPanel.setOpaque(true);
        tabPanel.setBackground(active ? ACTIVE_BG : INACTIVE_BG);
        tabPanel.setPreferredSize(new Dimension(scaledTabMaxWidth(), scaledInnerHeight()));
        tabPanel.setMinimumSize(new Dimension(scaledTabMinWidth(), scaledInnerHeight()));
        tabPanel.addMouseListener(middleClickCloseListener(tab));
        tabPanel.add(createTabButton(tab, active));
        tabPanel.add(createCloseButton(tab, active));
        return tabPanel;
    }

    private JButton createTabButton(PaistiClientTab tab, boolean active) {
        JButton button = createButton(tab.label(), active ? ACTIVE_BG : INACTIVE_BG, active ? FG : MUTED_FG);
        button.putClientProperty(TAB_PROPERTY, tab);
        button.putClientProperty("FlatLaf.style", String.format("background: %s; hoverBackground: %s; foreground: %s; hoverForeground: %s",
                hex(active ? ACTIVE_BG : INACTIVE_BG), hex(active ? ACTIVE_HOVER_BG : INACTIVE_HOVER_BG), hex(active ? FG : MUTED_FG), hex(FG)));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.addActionListener(e -> {
            manager.activateTab(tab);
            refocusGame();
        });
        button.addMouseListener(middleClickCloseListener(tab));
        return button;
    }

    private JButton createCloseButton(PaistiClientTab tab, boolean active) {
        JButton button = createButton("x", active ? ACTIVE_BG : INACTIVE_BG, MUTED_FG);
        button.putClientProperty(CLOSE_TAB_PROPERTY, tab);
        button.putClientProperty("FlatLaf.style", String.format("background: %s; hoverBackground: %s; foreground: %s; hoverForeground: %s",
                hex(active ? ACTIVE_BG : INACTIVE_BG), hex(CLOSE_HOVER_BG), hex(MUTED_FG), hex(FG)));
        button.setPreferredSize(new Dimension(scaledCloseButtonWidth(), scaledInnerHeight()));
        button.setBorder(BorderFactory.createEmptyBorder(0, UI.scale(2), 0, UI.scale(2)));
        button.addActionListener(e -> {
            closeTabAndRefocus(tab);
        });
        button.addMouseListener(middleClickCloseListener(tab));
        return button;
    }

    private JButton createAddButton() {
        JButton button = createButton(ADD_LABEL, INACTIVE_BG, MUTED_FG);
        button.putClientProperty(ADD_TAB_PROPERTY, Boolean.TRUE);
        button.putClientProperty("FlatLaf.style", String.format("background: %s; hoverBackground: %s; foreground: %s; hoverForeground: %s",
                hex(INACTIVE_BG), hex(INACTIVE_HOVER_BG), hex(MUTED_FG), hex(FG)));
        button.setPreferredSize(new Dimension(scaledAddTabWidth(), scaledInnerHeight()));
        button.addActionListener(e -> {
            manager.requestNewLoginTab();
            refocusGame();
        });
        return button;
    }

    private JButton createButton(String text, Color background, Color foreground) {
        JButton button = new JButton(text);
        button.setFont(getFont());
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(BorderFactory.createEmptyBorder(0, UI.scale(8), 0, UI.scale(8)));
        return button;
    }

    private static int scaledHeight() {
        return UI.scale(HEIGHT);
    }

    private static int scaledInnerHeight() {
        return scaledHeight();
    }

    private static int scaledTabMinWidth() {
        return UI.scale(TAB_MIN_W);
    }

    private static int scaledTabMaxWidth() {
        return UI.scale(TAB_MAX_W);
    }

    private static int scaledTabShrinkWidth() {
        return UI.scale(TAB_SHRINK_W);
    }

    private static int scaledCloseButtonWidth() {
        return UI.scale(CLOSE_BUTTON_W);
    }

    private static int scaledAddTabWidth() {
        return UI.scale(ADD_TAB_W);
    }

    private static int scaledRowGap() {
        return UI.scale(3);
    }

    private String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private MouseAdapter middleClickCloseListener(PaistiClientTab tab) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if(e.getButton() != MouseEvent.BUTTON2)
                    return;
                closeTabAndRefocus(tab);
                e.consume();
            }
        };
    }

    private void closeTabAndRefocus(PaistiClientTab tab) {
        manager.closeTab(tab);
        refocusGame();
    }

    private boolean isVisibleTab(PaistiClientTab tab) {
        return tab.isSelectable() || tab.isLogin();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if(e.getID() != KeyEvent.KEY_PRESSED)
            return(false);
        if(isCtrlTabShortcut(e)) {
            if((e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0)
                manager.switchToPrevious();
            else
                manager.switchToNext();
            e.consume();
            refocusGame();
            return(true);
        }
        if(!isClientTabShortcut(e))
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
        return(true);
    }

    private boolean isClientTabShortcut(KeyEvent e) {
        int mods = e.getModifiersEx();
        int required = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        int excluded = InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK | InputEvent.META_DOWN_MASK;
        return((mods & required) == required && (mods & excluded) == 0);
    }

    private boolean isCtrlTabShortcut(KeyEvent e) {
        int mods = e.getModifiersEx();
        int required = InputEvent.CTRL_DOWN_MASK;
        int excluded = InputEvent.ALT_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK | InputEvent.META_DOWN_MASK;
        return(e.getKeyCode() == KeyEvent.VK_TAB && (mods & required) == required && (mods & excluded) == 0);
    }

    private void scheduleRebuild() {
        EventQueue.invokeLater(this::rebuildTabs);
    }

    private static class TabRowLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int count = parent.getComponentCount();
            int width = in.left + in.right + Math.max(0, count - 1) * scaledRowGap();
            for(Component component : parent.getComponents())
                width += component.getPreferredSize().width;
            return new Dimension(width, scaledHeight());
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, scaledHeight());
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            Component[] components = parent.getComponents();
            if(components.length == 0)
                return;
            int h = Math.max(0, parent.getHeight() - in.top - in.bottom);
            int y = in.top;
            int tabs = 0;
            int fixedWidth = scaledAddTabWidth();
            for(Component component : components) {
                if(isTabComponent(component))
                    tabs++;
            }
            int gap = scaledRowGap();
            int gaps = Math.max(0, components.length - 1) * gap;
            int available = Math.max(0, parent.getWidth() - in.left - in.right - fixedWidth - gaps);
            int tabWidth = tabs == 0 ? 0 : Math.min(scaledTabMaxWidth(), available / tabs);
            int x = in.left;
            for(Component component : components) {
                int width;
                if(isTabComponent(component))
                    width = tabWidth;
                else
                    width = Math.min(scaledAddTabWidth(), Math.max(scaledTabShrinkWidth(), parent.getWidth() - x - in.right));
                component.setBounds(x, y, Math.max(0, width), h);
                x += width + gap;
            }
        }

        private static boolean isTabComponent(Component component) {
            return component instanceof JPanel && ((JPanel)component).getClientProperty(TAB_PROPERTY) != null;
        }
    }

    private static class TabComponentLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(scaledTabMaxWidth(), scaledInnerHeight());
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(scaledTabShrinkWidth(), scaledInnerHeight());
        }

        @Override
        public void layoutContainer(Container parent) {
            Component[] components = parent.getComponents();
            if(components.length < 2)
                return;
            Rectangle bounds = parent.getBounds();
            int closeWidth = Math.min(scaledCloseButtonWidth(), bounds.width);
            int labelWidth = Math.max(0, bounds.width - closeWidth);
            components[0].setBounds(0, 0, labelWidth, bounds.height);
            components[1].setBounds(labelWidth, 0, closeWidth, bounds.height);
        }
    }

    private void refocusGame() {
        if(gameComponent != null) {
            if(!gameComponent.requestFocusInWindow())
                gameComponent.requestFocus();
        }
    }
}
