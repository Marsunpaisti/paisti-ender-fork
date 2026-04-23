package paisti.client;

import haven.*;

public class PMapView extends MapView {
    public PMapView(Coord sz, Glob glob, Coord2d cc, long plgob) {
	super(sz, glob, cc, plgob);
    }

    @Override
    protected void attached() {
	super.attached();
	if(ui instanceof PUI) {
	    PUI.of(ui).overlayManager().syncMapOverlayAttachment();
	}
    }
}
